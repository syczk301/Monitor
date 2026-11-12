package stackprobe

import (
	"context"
	"fmt"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"math/rand"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type stats struct {
	state atomic.Pointer[tcp.TCPEndpointState]
}

func makeStack(t *testing.T, last byte) (*stack.Stack, *channel.Endpoint, *stats) {
	p := new(stats)
	s := stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocolProbe(func(st *tcp.TCPEndpointState) { p.state.Store(st) })}})
	for _, opt := range []tcpip.SettableTransportProtocolOption{
		&tcpip.TCPReceiveBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: 1 << 20, Max: 8 << 20},
		&tcpip.TCPSendBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: 1 << 20, Max: 6 << 20},
	} {
		if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, opt); err != nil {
			t.Fatal(err)
		}
	}
	cubic := tcpip.CongestionControlOption("cubic")
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &cubic); err != nil {
		t.Fatal(err)
	}
	ep := channel.New(32768, 1280, "")
	if err := s.CreateNIC(1, ep); err != nil {
		t.Fatal(err)
	}
	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 0, 0, last}).WithPrefix()}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s, ep, p
}

type delivery struct {
	pkt *stack.PacketBuffer
	due time.Time
}

func link(ctx context.Context, from, to *channel.Endpoint, delay time.Duration, loss float64, seed int64, label string, wg *sync.WaitGroup) {
	queue := make(chan delivery, 32768)
	wg.Add(2)
	go func() {
		defer wg.Done()
		defer close(queue)
		rng := rand.New(rand.NewSource(seed))
		synPrinted := false
		for {
			pkt := from.ReadContext(ctx)
			if pkt == nil {
				return
			}
			th := header.TCP(pkt.TransportHeader().Slice())
			if len(th) < 20 {
				b := pkt.ToBuffer()
				wire := b.Flatten()
				if len(wire) >= 40 {
					ip := header.IPv4(wire)
					off := int(ip.HeaderLength())
					if len(wire) >= off+20 {
						th = header.TCP(append([]byte(nil), wire[off:]...))
					}
				}
				b.Release()
			}
			if len(th) >= 20 && th.Flags().Contains(header.TCPFlagSyn) && !synPrinted {
				synPrinted = true
				opts := header.ParseSynOptions(th.Options(), th.Flags().Contains(header.TCPFlagAck))
				fmt.Printf("SYN %s MSS=%d WS=%d SACK=%v rawWindow=%d\n", label, opts.MSS, opts.WS, opts.SACKPermitted, th.WindowSize())
			}
			if rng.Float64() < loss {
				pkt.DecRef()
				continue
			}
			inbound := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: pkt.ToBuffer()})
			pkt.DecRef()
			item := delivery{inbound, time.Now().Add(delay)}
			select {
			case queue <- item:
			case <-ctx.Done():
				inbound.DecRef()
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		for item := range queue {
			wait := time.Until(item.due)
			if wait > 0 {
				timer := time.NewTimer(wait)
				select {
				case <-timer.C:
				case <-ctx.Done():
					if !timer.Stop() {
						select {
						case <-timer.C:
						default:
						}
					}
					item.pkt.DecRef()
					for left := range queue {
						left.pkt.DecRef()
					}
					return
				}
			}
			if ctx.Err() == nil {
				to.InjectInbound(ipv4.ProtocolNumber, item.pkt)
			}
			item.pkt.DecRef()
		}
	}()
}
