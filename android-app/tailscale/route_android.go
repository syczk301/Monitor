package tsbridge

import "tailscale.com/net/netmon"

// SetDefaultRoute supplies Android's selected physical link to netmon.
func SetDefaultRoute(interfaceName, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(interfaceName)
	netmon.UpdateLastKnownDefaultGateway(gateway)
}
