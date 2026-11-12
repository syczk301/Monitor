package tsbridge

import (
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
)

// SetSocketBinder must be installed before Start and cleared only after Close.
func SetSocketBinder(binder SocketBinder) {
	if binder == nil {
		netns.SetAndroidBindToNetworkFunc(nil)
		return
	}
	netns.SetAndroidBindToNetworkFunc(binder.BindSocket)
}

// SetDefaultRoute supplies Android's selected physical link to netmon.
func SetDefaultRoute(interfaceName, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(interfaceName)
	netmon.UpdateLastKnownDefaultGateway(gateway)
}
