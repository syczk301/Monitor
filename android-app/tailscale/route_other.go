//go:build !android

package tsbridge

// SetDefaultRoute is provided by the OS on desktop builds.
func SetDefaultRoute(interfaceName, gateway string) {}

// Desktop sockets already follow their OS routing policy.
func SetSocketBinder(binder SocketBinder) {}
