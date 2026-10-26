//! Read-only metadata fixture for Android integration tests. Does not start a camera.
use axum::{Json, Router, routing::get};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let paths = monitor_storage::AppPaths::discover()?;
    let tls =
        axum_server::tls_rustls::RustlsConfig::from_pem_file(paths.tls_cert, paths.tls_key).await?;
    let app = Router::new().route(
        "/api/health",
        get(|| async { Json(monitor_web::connection_metadata("0.0.0.0:8000".into()).await) }),
    );
    axum_server::bind_rustls(std::net::SocketAddr::from(([127, 0, 0, 1], 19443)), tls)
        .serve(app.into_make_service())
        .await?;
    Ok(())
}
