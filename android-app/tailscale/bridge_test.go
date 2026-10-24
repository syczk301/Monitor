package tsbridge

import (
	"io"
	"net"
	"testing"

	"tailscale.com/net/netmon"
)

func TestAndroidInterfaceSnapshot(t *testing.T) {
	err := SetInterfaces(`[{"Name":"wlan0","Index":3,"MTU":1500,"Up":true,"Addresses":["192.168.1.20/24","2001:db8::20/64"]}]`)
	if err != nil {
		t.Fatal(err)
	}
	interfaces, err := netmon.GetInterfaceList()
	if err != nil || len(interfaces) != 1 {
		t.Fatalf("interfaces=%v err=%v", interfaces, err)
	}
	addresses, err := interfaces[0].Addrs()
	if err != nil || len(addresses) != 2 || addresses[0].String() != "192.168.1.20/24" {
		t.Fatalf("addresses=%v err=%v", addresses, err)
	}
	if err := SetInterfaces("invalid"); err == nil {
		t.Fatal("invalid snapshot accepted")
	}
}

func TestStreamCopiesAcrossBindingAndHandlesEOF(t *testing.T) {
	a, b := net.Pipe()
	stream := &Stream{conn: a}
	defer stream.Close()
	go func() { defer b.Close(); b.Write([]byte("camera-frame")) }()
	data, err := stream.Read(1024)
	if err != nil || string(data) != "camera-frame" {
		t.Fatalf("read=%q err=%v", data, err)
	}
	data, err = stream.Read(1024)
	if err != nil || len(data) != 0 {
		t.Fatalf("EOF=%q err=%v", data, err)
	}
	if _, err := stream.Read(-1); err == nil {
		t.Fatal("negative read accepted")
	}
}

func TestStreamWriteAndCloseUnblocksRead(t *testing.T) {
	a, b := net.Pipe()
	stream := &Stream{conn: a}
	done := make(chan error, 1)
	go func() {
		buffer := make([]byte, 5)
		_, err := io.ReadFull(b, buffer)
		if string(buffer) != "audio" {
			t.Error(string(buffer))
		}
		done <- err
	}()
	if err := stream.Write([]byte("audio")); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	go func() { _, err := stream.Read(100); done <- err }()
	stream.Close()
	if err := <-done; err == nil {
		t.Fatal("closed stream read succeeded")
	}
	b.Close()
}
