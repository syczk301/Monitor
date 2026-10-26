use serde::Serialize;
use std::{
    net::{Ipv4Addr, SocketAddr, TcpStream},
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
use windows::Win32::{
    NetworkManagement::{
        IpHelper::{
            GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER, GAA_FLAG_SKIP_MULTICAST,
            GetAdaptersAddresses, IP_ADAPTER_ADDRESSES_LH,
        },
        Ndis::IfOperStatusUp,
    },
    Networking::WinSock::{
        AF_INET, GetNameInfoW, NI_NAMEREQD, SOCKADDR_IN, WSACleanup, WSADATA, WSAStartup, socklen_t,
    },
};

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct ConnectionEndpoint {
    pub mode: &'static str,
    pub url: String,
    pub network_id: Option<String>,
}

#[derive(Default)]
struct Cache {
    updated: Option<Instant>,
    bind: String,
    values: Vec<ConnectionEndpoint>,
}

/// Enumerate the host's real overlay adapters. No shell, CLI or fixed IP mapping.
pub fn connection_endpoints(bind: &str) -> Vec<ConnectionEndpoint> {
    static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
    let Ok(mut cache) = CACHE.get_or_init(Default::default).try_lock() else {
        return Vec::new();
    };
    if cache.bind == bind
        && cache
            .updated
            .is_some_and(|at| at.elapsed() < Duration::from_secs(30))
    {
        return cache.values.clone();
    }
    let values = enumerate(bind);
    cache.bind = bind.to_owned();
    cache.updated = Some(Instant::now());
    cache.values = values.clone();
    values
}

fn network_id(name: &str) -> Option<String> {
    let start = name.find('[')? + 1;
    let end = name[start..].find(']')? + start;
    let value = &name[start..end];
    (value.len() == 16 && value.bytes().all(|c| c.is_ascii_hexdigit()))
        .then(|| value.to_ascii_lowercase())
}

fn tailscale_dns(value: &str) -> Option<String> {
    let value = value.trim_end_matches('.').to_ascii_lowercase();
    (value.ends_with(".ts.net")
        && value.len() <= 253
        && value.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'-')
        }))
    .then_some(value)
}

fn enumerate(bind: &str) -> Vec<ConnectionEndpoint> {
    let Ok(bound) = bind.parse::<SocketAddr>() else {
        return Vec::new();
    };
    let mut length = 0u32;
    let flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    // API-owned linked pointers stay inside this aligned buffer for its lifetime.
    unsafe { GetAdaptersAddresses(AF_INET.0 as u32, flags, None, None, &mut length) };
    if length == 0 || length > 16 * 1024 * 1024 {
        return Vec::new();
    }
    let mut storage = vec![0u64; (length as usize).div_ceil(8)];
    let first = storage.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
    if unsafe { GetAdaptersAddresses(AF_INET.0 as u32, flags, None, Some(first), &mut length) } != 0
    {
        return Vec::new();
    }
    let mut wsa = WSADATA::default();
    let winsock = unsafe { WSAStartup(0x0202, &mut wsa) } == 0;
    let mut result = Vec::new();
    let mut adapter = first;
    while !adapter.is_null() {
        let item = unsafe { &*adapter };
        if item.OperStatus == IfOperStatusUp {
            let name = if item.FriendlyName.is_null() {
                String::new()
            } else {
                unsafe { item.FriendlyName.to_string() }.unwrap_or_default()
            };
            let lower = name.to_ascii_lowercase();
            if lower.contains("zerotier") || lower.contains("tailscale") {
                let mut unicast = item.FirstUnicastAddress;
                while !unicast.is_null() {
                    let address = unsafe { &*unicast };
                    if !address.Address.lpSockaddr.is_null()
                        && address.Address.iSockaddrLength as usize >= size_of::<SOCKADDR_IN>()
                        && unsafe { (*address.Address.lpSockaddr).sa_family } == AF_INET
                    {
                        let socket = unsafe { &*address.Address.lpSockaddr.cast::<SOCKADDR_IN>() };
                        let ip =
                            Ipv4Addr::from(unsafe { socket.sin_addr.S_un.S_addr }.to_ne_bytes());
                        if !ip.is_loopback() && !ip.is_unspecified() && !ip.is_link_local() {
                            if lower.contains("zerotier")
                                && (bound.ip().is_unspecified() || bound.ip() == ip)
                            {
                                if let Some(id) = network_id(&name) {
                                    result.push(ConnectionEndpoint {
                                        mode: "zerotier",
                                        url: format!("https://{ip}:{}", bound.port()),
                                        network_id: Some(id),
                                    });
                                }
                            } else if lower.contains("tailscale") && winsock &&
                                TcpStream::connect_timeout(&SocketAddr::new(ip.into(), 443), Duration::from_millis(500)).is_ok() {
                                let mut hostname = [0u16; 1025];
                                if unsafe {
                                    GetNameInfoW(
                                        address.Address.lpSockaddr,
                                        socklen_t(address.Address.iSockaddrLength),
                                        Some(&mut hostname),
                                        None,
                                        NI_NAMEREQD as i32,
                                    )
                                } == 0
                                {
                                    let end = hostname
                                        .iter()
                                        .position(|&c| c == 0)
                                        .unwrap_or(hostname.len());
                                    if let Some(dns) =
                                        tailscale_dns(&String::from_utf16_lossy(&hostname[..end]))
                                    {
                                        result.push(ConnectionEndpoint {
                                            mode: "tailscale",
                                            url: format!("https://{dns}"),
                                            network_id: None,
                                        });
                                    }
                                }
                            }
                        }
                    }
                    unicast = address.Next;
                }
            }
        }
        adapter = item.Next;
    }
    if winsock {
        unsafe { WSACleanup() };
    }
    result.sort_by(|a, b| a.url.cmp(&b.url));
    result.dedup();
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn extracts_only_explicit_overlay_network_ids() {
        assert_eq!(
            network_id("ZeroTier One [76FC96E4983D7F72]"),
            Some("76fc96e4983d7f72".into())
        );
        assert_eq!(network_id("ZeroTier One [not-a-network]"), None);
        assert_eq!(network_id("Ethernet"), None);
    }
    #[test]
    fn accepts_only_actual_magic_dns_names() {
        assert_eq!(
            tailscale_dns("CAMERA.tail123.ts.net."),
            Some("camera.tail123.ts.net".into())
        );
        assert_eq!(tailscale_dns("camera.example.com"), None);
        assert_eq!(tailscale_dns("camera.ts.net.evil.example"), None);
        assert_eq!(tailscale_dns("https://camera.ts.net"), None);
    }
}
