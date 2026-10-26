fn main() {
    for endpoint in monitor_media::connection_endpoints("0.0.0.0:8000") {
        println!(
            "{} {} {}",
            endpoint.mode,
            endpoint.url,
            endpoint.network_id.unwrap_or_default()
        );
    }
}
