package stackprobe

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type udpDispatcher struct {
	conn   *net.UDPConn
	peer   *net.UDPAddr
	counts *wireCounts
	framed bool
}
type wireCounts struct{ sent, sendErrors, received, queueDrops atomic.Int64 }

func (u udpDispatcher) DeliverNetworkPacket(_ tcpip.NetworkProtocolNumber, p *stack.PacketBuffer) {
	b := p.ToBuffer()
	defer b.Release()
	seq := u.counts.sent.Add(1)
	data := b.Flatten()
	if u.framed {
		framed := make([]byte, len(data)+8)
		binary.LittleEndian.PutUint64(framed, uint64(seq))
		copy(framed[8:], data)
		data = framed
	}
	if n, err := u.conn.WriteToUDP(data, u.peer); err != nil || n != len(data) {
		u.counts.sendErrors.Add(1)
	}
}
func (u udpDispatcher) DeliverLinkPacket(_ tcpip.NetworkProtocolNumber, p *stack.PacketBuffer) {}
func TestLWIPReceiver(t *testing.T) {
	delayMs, _ := strconv.Atoi(os.Getenv("LINK_DELAY_MS"))
	loss, _ := strconv.ParseFloat(os.Getenv("LINK_LOSS"), 64)
	bsdMode := os.Getenv("LWIP_BSD")
	if bsdMode == "" {
		bsdMode = "1020"
	}
	counts := new(wireCounts)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sender, wire, probe := makeStack(t, 1)
	defer sender.Close()
	wire.SetMTU(2800)
	endpoint := channel.New(32768, 2800, "")
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	udp.SetReadBuffer(4 << 20)
	temp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	peer := temp.LocalAddr().(*net.UDPAddr)
	temp.Close()
	endpoint.Attach(udpDispatcher{udp, peer, counts, bsdMode != ""})
	go func() {
		buf := make([]byte, 65536)
		for {
			n, _, err := udp.ReadFromUDP(buf)
			if err != nil {
				return
			}
			counts.received.Add(1)
			p := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), buf[:n]...))})
			var list stack.PacketBufferList
			list.PushBack(p)
			if n, err := endpoint.WritePackets(list); n != 1 || err != nil {
				counts.queueDrops.Add(1)
			}
			p.DecRef()
		}
	}()
	var wg sync.WaitGroup
	link(ctx, wire, endpoint, time.Duration(delayMs)*time.Millisecond, loss, 4, "gvisor-to-lwip", &wg)
	link(ctx, endpoint, wire, time.Duration(delayMs)*time.Millisecond, loss, 5, "lwip-to-gvisor", &wg)
	defer func() { cancel(); wire.Close(); endpoint.Close(); wg.Wait() }()
	listener, err := gonet.ListenTCP(sender, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{10, 0, 0, 1}), Port: 8000}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	name := "lwip-bsd" + bsdMode
	executable := ".\\lwip_receiver_bsd.exe"
	if label := os.Getenv("LWIP_LABEL"); label != "" {
		name += "-" + label
	}
	logfile, err := os.Create(fmt.Sprintf("%s-delay%d-loss%.4f.log", name, delayMs, loss))
	if err != nil {
		t.Fatal(err)
	}
	defer logfile.Close()
	child := exec.CommandContext(ctx, executable, strconv.Itoa(peer.Port), strconv.Itoa(udp.LocalAddr().(*net.UDPAddr).Port), bsdMode)
	child.Stdout = logfile
	child.Stderr = logfile
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- child.Wait() }()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, err := listener.Accept()
		if err == nil {
			accepted <- c
		}
	}()
	var conn net.Conn
	select {
	case conn = <-accepted:
	case <-time.After(7 * time.Second):
		t.Fatal("accept timeout")
	}
	defer conn.Close()
	deadline := time.Now().Add(20 * time.Second)
	conn.SetDeadline(deadline)
	writeDone := make(chan struct{})
	go func() {
		defer close(writeDone)
		payload := make([]byte, 65536)
		for i := range payload {
			payload[i] = byte(i % 251)
		}
		next := time.Now()
		for time.Now().Before(deadline) {
			if _, err := conn.Write(payload); err != nil {
				return
			}
			next = next.Add(time.Second / 30)
			if d := time.Until(next); d > 0 {
				time.Sleep(d)
			}
		}
	}()
	for second := 1; second <= 20; second++ {
		time.Sleep(time.Second)
		if st := probe.state.Load(); st != nil {
			row := map[string]any{"second": second, "cwnd": st.Sender.SndCwnd, "window": st.Sender.SndWnd, "MSS": st.Sender.MaxPayloadSize, "rttMs": float64(st.Sender.RTTState.SRTT) / 1e6}
			data, _ := json.Marshal(row)
			fmt.Println(string(data))
		}
	}
	<-writeDone
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("receiver timeout")
	}
	fmt.Printf("WIRE sent=%d sendErrors=%d received=%d queueDrops=%d\n", counts.sent.Load(), counts.sendErrors.Load(), counts.received.Load(), counts.queueDrops.Load())
}
