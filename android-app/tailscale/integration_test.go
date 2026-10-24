package tsbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http/httptest"
	"testing"
	"time"

	"tailscale.com/net/netns"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

func TestEmbeddedNodesExchangeTrafficAndRestoreIdentity(t *testing.T) {
	netns.SetEnabled(false)
	defer netns.SetEnabled(true)
	// Use real host interfaces for this host-side integration test.
	ifaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	var snapshot []interfaceInfo
	for _, nic := range ifaces {
		entry := interfaceInfo{Name: nic.Name, Index: nic.Index, MTU: nic.MTU, Up: nic.Flags&net.FlagUp != 0, Loopback: nic.Flags&net.FlagLoopback != 0}
		addresses, _ := nic.Addrs()
		for _, address := range addresses {
			entry.Addresses = append(entry.Addresses, address.String())
		}
		snapshot = append(snapshot, entry)
	}
	data, _ := json.Marshal(snapshot)
	if err := SetInterfaces(string(data)); err != nil {
		t.Fatal(err)
	}
	control := &testcontrol.Server{DERPMap: integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")}
	control.HTTPTestServer = httptest.NewServer(control)
	defer control.HTTPTestServer.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	server, err := startServer(t.TempDir(), "camera-server", control.HTTPTestServer.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	serverStatus, err := server.server.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	listener, err := server.server.Listen("tcp", ":8000")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		peer, err := listener.Accept()
		if err != nil {
			return
		}
		defer peer.Close()
		io.Copy(peer, peer)
	}()
	directory := t.TempDir()
	client, err := startServer(directory, "camera-android", control.HTTPTestServer.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	clientStatus, err := client.server.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	stream, err := client.Dial(serverStatus.TailscaleIPs[0].String(), 8000)
	if err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte("video-audio-test"), 4096)
	writeDone := make(chan error, 1)
	go func() { writeDone <- stream.Write(payload) }()
	var received []byte
	for len(received) < len(payload) {
		chunk, err := stream.Read(16384)
		if err != nil {
			t.Fatal(err)
		}
		if len(chunk) == 0 {
			t.Fatal("unexpected EOF")
		}
		received = append(received, chunk...)
	}
	if err := <-writeDone; err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, received) {
		t.Fatal("tailnet stream corrupted")
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	restored, err := startServer(directory, "camera-android", control.HTTPTestServer.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	restoredStatus, err := restored.server.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if restoredStatus.Self.PublicKey != clientStatus.Self.PublicKey {
		t.Fatal("node identity changed across restart")
	}
	if _, err := client.Dial(serverStatus.TailscaleIPs[0].String(), 8000); err == nil {
		t.Fatal("closed transport accepted traffic")
	}
}
