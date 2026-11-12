// Package tsbridge embeds a private Tailscale node, without a system VPN.
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var interfaceJSON atomic.Value

type interfaceInfo struct {
	Name      string
	Index     int
	MTU       int
	Up        bool
	Loopback  bool
	Addresses []string
}

func init() {
	interfaceJSON.Store("[]")
	// Android app UIDs cannot enumerate interfaces through Go's netlink API.
	// Supply the supported netmon hook with snapshots from Android's Java API.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		var snapshot []interfaceInfo
		if err := json.Unmarshal([]byte(interfaceJSON.Load().(string)), &snapshot); err != nil {
			return nil, err
		}
		result := make([]netmon.Interface, 0, len(snapshot))
		for _, item := range snapshot {
			flags := net.FlagBroadcast | net.FlagMulticast
			if item.Up {
				flags |= net.FlagUp | net.FlagRunning
			}
			if item.Loopback {
				flags |= net.FlagLoopback
			}
			addrs := make([]net.Addr, 0, len(item.Addresses))
			for _, value := range item.Addresses {
				ip, prefix, err := net.ParseCIDR(value)
				if err != nil {
					continue
				}
				prefix.IP = ip
				addrs = append(addrs, prefix)
			}
			result = append(result, netmon.Interface{Interface: &net.Interface{
				Index: item.Index, MTU: item.MTU, Name: item.Name, Flags: flags,
			}, AltAddrs: addrs})
		}
		return result, nil
	})
}

// Node owns its persistent identity and every open monitoring stream.
type Node struct {
	server  *tsnet.Server
	mu      sync.Mutex
	closed  bool
	streams map[*Stream]bool
}

// SocketBinder binds native sockets to Android's selected physical network.
// Go opens sockets via syscalls, which bypass Android's libc process binding.
// The callback must not close fd or fall back to an unbound system route.
type SocketBinder interface {
	BindSocket(fd int) error
}

// SetInterfaces must be called before Start and whenever physical links change.
func SetInterfaces(snapshot string) error {
	var values []interfaceInfo
	if err := json.Unmarshal([]byte(snapshot), &values); err != nil {
		return err
	}
	interfaceJSON.Store(snapshot)
	return nil
}

func Start(directory, hostname string) (*Node, error) {
	return startServer(directory, hostname, "")
}

func startServer(directory, hostname, controlURL string) (*Node, error) {
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	// Android has no writable HOME or system log directory. LocalBackend's
	// socket logger also needs this path, independently of tsnet.Server.Dir.
	if err := os.Setenv("TS_LOGS_DIR", directory); err != nil {
		return nil, err
	}
	s := &tsnet.Server{Dir: directory, Hostname: hostname, ControlURL: controlURL, UserLogf: func(string, ...any) {}}
	if err := s.Start(); err != nil {
		s.Close()
		return nil, err
	}
	return &Node{server: s, streams: make(map[*Stream]bool)}, nil
}

func (n *Node) NetworkChanged() {
	if monitor, ok := n.server.Sys().NetMon.GetOK(); ok {
		monitor.InjectEvent()
	}
	// Android may replace a Network while retaining the interface name and
	// addresses. netmon cannot see its handle; recreate UDP sockets so the
	// per-FD hook applies the newly selected Network in that case as well.
	if magic, ok := n.server.Sys().MagicSock.GetOK(); ok {
		magic.Rebind()
		magic.ReSTUN("android-network-changed")
	}
}

func (n *Node) Status() (string, error) {
	lc, err := n.server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	status, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return "", err
	}
	value := struct {
		State   string
		AuthURL string
		IPs     []string
		NodeID  string
	}{
		State: status.BackendState, AuthURL: status.AuthURL,
	}
	for _, ip := range status.TailscaleIPs {
		value.IPs = append(value.IPs, ip.String())
	}
	if status.Self != nil {
		value.NodeID = string(status.Self.ID)
	}
	data, err := json.Marshal(value)
	return string(data), err
}

func (n *Node) Dial(host string, port int) (*Stream, error) {
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil, errors.New("Tailscale stopped")
	}
	n.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, err := n.server.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	stream := &Stream{conn: conn, owner: n}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		conn.Close()
		return nil, errors.New("Tailscale stopped")
	}
	n.streams[stream] = true
	return stream, nil
}

func (n *Node) Close() error {
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil
	}
	n.closed = true
	streams := make([]*Stream, 0, len(n.streams))
	for stream := range n.streams {
		streams = append(streams, stream)
	}
	n.mu.Unlock()
	for _, stream := range streams {
		stream.Close()
	}
	return n.server.Close()
}

type Stream struct {
	conn  net.Conn
	owner *Node
}

// Return bytes rather than mutating a gomobile slice (JNI copies input slices).
func (s *Stream) Read(size int) ([]byte, error) {
	if size < 1 || size > 65536 {
		return nil, errors.New("invalid read size")
	}
	buffer := make([]byte, size)
	count, err := s.conn.Read(buffer)
	if count > 0 {
		return buffer[:count], nil
	}
	if errors.Is(err, io.EOF) {
		return []byte{}, nil
	}
	return nil, err
}

func (s *Stream) Write(data []byte) error {
	for len(data) > 0 {
		count, err := s.conn.Write(data)
		if err != nil {
			return err
		}
		if count == 0 {
			return io.ErrShortWrite
		}
		data = data[count:]
	}
	return nil
}

func (s *Stream) Close() error {
	if s.owner != nil {
		s.owner.mu.Lock()
		delete(s.owner.streams, s)
		s.owner.mu.Unlock()
	}
	return s.conn.Close()
}
