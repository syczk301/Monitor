#![forbid(unsafe_code)]

use anyhow::{Context, Result};
use async_stream::stream;
use axum::{
    Json, Router,
    body::{Body, Bytes},
    extract::{Path as AxumPath, Query, State},
    http::{HeaderValue, Request, StatusCode, header},
    response::{Html, IntoResponse, Response},
    routing::{delete, get, patch, post},
};
use monitor_media::{MediaController, RuntimeStatus};
use monitor_storage::{
    AppPaths, RecordingMode, RecordingSchedule, RemoteNode, Repository, Settings, list_recordings,
    load_or_create_settings, resolve_recording, save_settings_atomic,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    convert::Infallible,
    env,
    fs::File,
    io::BufReader,
    net::SocketAddr,
    path::PathBuf,
    pin::Pin,
    sync::{Arc, RwLock},
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpStream,
    sync::watch,
    time::timeout,
};
use tokio_rustls::TlsConnector;
use tower::ServiceExt;
use tower_http::{compression::CompressionLayer, services::ServeFile, trace::TraceLayer};

const INDEX_HTML: &str = include_str!("../../../assets/web/index.html");
const HISTORY_HTML: &str = include_str!("../../../assets/web/history.html");
const RECORDINGS_HTML: &str = include_str!("../../../assets/web/recordings.html");
const TRAY_ICON: &[u8] = include_bytes!("../../../assets/tray_icon.png");

#[derive(Clone)]
pub struct PreviewHub {
    sender: watch::Sender<Arc<Vec<u8>>>,
}

#[derive(Clone)]
pub struct AudioHub {
    sender: watch::Sender<Arc<Vec<u8>>>,
}

impl AudioHub {
    pub fn new() -> Self {
        let (sender, _) = watch::channel(Arc::new(Vec::new()));
        Self { sender }
    }
    pub fn publish(&self, pcm: Vec<u8>) {
        self.sender.send_replace(Arc::new(pcm));
    }
    fn subscribe(&self) -> watch::Receiver<Arc<Vec<u8>>> {
        self.sender.subscribe()
    }
}

impl Default for AudioHub {
    fn default() -> Self {
        Self::new()
    }
}

impl PreviewHub {
    pub fn new() -> Self {
        let (sender, _) = watch::channel(Arc::new(Vec::new()));
        Self { sender }
    }

    pub fn publish(&self, jpeg: Vec<u8>) {
        self.sender.send_replace(Arc::new(jpeg));
    }

    pub fn subscribe(&self) -> watch::Receiver<Arc<Vec<u8>>> {
        self.sender.subscribe()
    }
}

impl Default for PreviewHub {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone)]
pub struct WebState {
    pub settings: Arc<RwLock<Settings>>,
    pub paths: AppPaths,
    pub repository: Repository,
    pub runtime: Arc<RwLock<RuntimeStatus>>,
    pub media: MediaController,
    pub preview: PreviewHub,
    pub audio: AudioHub,
}

pub async fn serve(state: WebState, shutdown: watch::Receiver<bool>) -> Result<()> {
    let address: SocketAddr = state
        .settings
        .read()
        .expect("settings poisoned")
        .bind_address
        .parse()?;
    let tls_cert = state.paths.tls_cert.clone();
    let tls_key = state.paths.tls_key.clone();
    let app = router(state);
    let tls_config = axum_server::tls_rustls::RustlsConfig::from_pem_file(tls_cert, tls_key)
        .await
        .context("Camera Monitor TLS certificate or private key could not be loaded")?;
    let handle = axum_server::Handle::new();
    let shutdown_handle = handle.clone();
    tokio::spawn(async move {
        let mut shutdown = shutdown;
        while !*shutdown.borrow() {
            if shutdown.changed().await.is_err() {
                break;
            }
        }
        shutdown_handle.graceful_shutdown(Some(Duration::from_secs(5)));
    });
    tracing::info!(%address, "Rust native HTTPS server started");
    axum_server::bind_rustls(address, tls_config)
        .handle(handle)
        .serve(app.into_make_service())
        .await?;
    Ok(())
}

pub fn router(state: WebState) -> Router {
    Router::new()
        .route("/", get(|| async { Html(INDEX_HTML) }))
        .route("/history", get(|| async { Html(HISTORY_HTML) }))
        .route("/recordings", get(|| async { Html(RECORDINGS_HTML) }))
        .route("/api/tray-icon", get(tray_icon))
        .route("/stream", get(mjpeg_stream))
        .route("/api/audio/pcm", get(audio_pcm))
        .route("/api/audio/info", get(audio_info))
        .route("/api/health", get(health))
        .route("/stats", get(stats))
        .route("/api/selected_stats", get(selected_stats))
        .route("/api/capture_info", get(capture_info))
        .route("/api/selected_capture_info", get(selected_capture_info))
        .route("/api/local_cameras", get(local_cameras))
        .route("/api/cameras", get(cameras).post(select_camera))
        .route("/api/remote_nodes", get(remote_nodes).post(add_remote_node))
        .route("/api/remote_stream/{node}", get(remote_stream))
        .route("/api/remote_audio/{node}", get(remote_audio))
        .route("/api/camera_settings", post(camera_settings))
        .route("/api/recording_mode", post(recording_mode))
        .route(
            "/api/recording_schedule",
            get(recording_schedule).post(set_recording_schedule),
        )
        .route(
            "/api/selected_recording_status",
            get(selected_recording_status),
        )
        .route("/api/local_recording", post(local_recording))
        .route("/api/local_recordings", get(local_recordings))
        .route("/api/recordings", get(recordings))
        .route("/api/recordings/file", get(recording_file))
        .route("/api/recordings/playback", get(recording_file))
        .route("/api/visits", get(visits))
        .route("/api/visits/bulk-delete", post(bulk_delete_visits))
        .route("/api/persons/{id}/note", patch(update_person_note))
        .route("/api/visits/{id}/note", patch(update_visit_note))
        .route("/api/visits/{id}", delete(delete_visit))
        .route("/api/events", get(events))
        .route("/api/rois", get(empty_list).post(ai_unavailable))
        .route(
            "/api/rois/{id}",
            patch(ai_unavailable).delete(ai_unavailable),
        )
        .route("/api/reports/daily", get(daily_report))
        .route("/api/reports/weekly", get(weekly_report))
        .layer(CompressionLayer::new())
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn tray_icon() -> impl IntoResponse {
    ([(header::CONTENT_TYPE, "image/png")], TRAY_ICON)
}

async fn health(State(state): State<WebState>) -> Json<Value> {
    let runtime = state.runtime.read().expect("runtime poisoned").clone();
    Json(json!({
        "status": if runtime.last_error.is_empty() { "ok" } else { "degraded" },
        "runtime": "rust-native",
        "protocol": "https",
        "version": env!("CARGO_PKG_VERSION"),
        "computer_name": computer_name(),
        "fps": runtime.fps,
        "inference_fps": 0.0,
        "latency_ms": 0.0,
        "tracked_targets": 0,
        "gpu_utilization": 0.0,
        "capture_status": runtime.capture_status,
        "capture_backend": runtime.capture_backend,
        "camera_id": runtime.camera_id,
        "camera_name": runtime.camera_name,
        "encoder": runtime.encoder,
        "ai_enabled": false,
        "preview_clients": runtime.preview_clients,
        "preview_status": runtime.preview_status,
        "preview_error": runtime.preview_error,
        "recording_bitrate": runtime.recording_bitrate,
        "last_error": runtime.last_error,
    }))
}

#[derive(Clone, Serialize)]
struct CameraOption {
    id: String,
    name: String,
    source: &'static str,
    online: bool,
    stream_url: String,
    audio_url: String,
}

async fn local_cameras(State(state): State<WebState>) -> Response {
    let controller = state.media.clone();
    match tokio::task::spawn_blocking(move || controller.list_cameras()).await {
        Ok(Ok(devices)) => {
            Json(json!({"cameras": prefer_physical_cameras(devices)})).into_response()
        }
        Ok(Err(error)) => internal_error(error),
        Err(error) => internal_error(error),
    }
}

async fn cameras(State(state): State<WebState>) -> Response {
    let controller = state.media.clone();
    let local_devices = match tokio::task::spawn_blocking(move || controller.list_cameras()).await {
        Ok(Ok(devices)) => prefer_physical_cameras(devices),
        Ok(Err(error)) => return internal_error(error),
        Err(error) => return internal_error(error),
    };
    let runtime = state.runtime.read().expect("runtime poisoned").clone();
    let settings = state.settings.read().expect("settings poisoned").clone();
    let local_computer_name = computer_name();
    let mut devices: Vec<CameraOption> = local_devices
        .into_iter()
        .map(|device| CameraOption {
            id: format!("local:{}", device.id),
            name: camera_display_name(&local_computer_name, &device.name),
            source: "local",
            online: true,
            stream_url: "/stream".into(),
            audio_url: "/api/audio/pcm".into(),
        })
        .collect();
    let mut discovered_names = Vec::new();
    for (node_index, node) in settings.remote_nodes.iter().enumerate() {
        let remote_computer_name = fetch_remote_computer_name(node)
            .await
            .unwrap_or_else(|| node.name.clone());
        if remote_computer_name != node.name {
            discovered_names.push((node.address.clone(), remote_computer_name.clone()));
        }
        match fetch_remote_cameras(node).await {
            Ok(remote_devices) if !remote_devices.is_empty() => {
                let remote_devices = prefer_physical_cameras(remote_devices);
                devices.extend(remote_devices.into_iter().map(|device| CameraOption {
                    id: format!("remote:{node_index}:{}", device.id),
                    name: camera_display_name(&remote_computer_name, &device.name),
                    source: "remote",
                    online: true,
                    stream_url: format!("/api/remote_stream/{node_index}"),
                    audio_url: format!("/api/remote_audio/{node_index}"),
                }));
            }
            _ => devices.push(CameraOption {
                id: format!("remote:{node_index}:"),
                name: camera_display_name(&remote_computer_name, "摄像头（离线）"),
                source: "remote",
                online: false,
                stream_url: format!("/api/remote_stream/{node_index}"),
                audio_url: format!("/api/remote_audio/{node_index}"),
            }),
        }
    }
    if !discovered_names.is_empty()
        && let Err(error) = persist_remote_computer_names(&state, &discovered_names)
    {
        tracing::warn!(%error, "failed to persist discovered remote computer names");
    }
    let local_selected = if runtime.camera_id.is_empty() {
        settings.camera_id.clone()
    } else {
        runtime.camera_id
    };
    let fallback_selected = (!local_selected.is_empty()).then(|| format!("local:{local_selected}"));
    let selected_id = if devices
        .iter()
        .any(|device| device.online && device.id == settings.selected_camera_key)
    {
        settings.selected_camera_key
    } else {
        fallback_selected.unwrap_or_default()
    };
    Json(json!({
        "cameras": devices,
        "selected_id": selected_id,
    }))
    .into_response()
}

fn prefer_physical_cameras(
    devices: Vec<monitor_media::CameraDevice>,
) -> Vec<monitor_media::CameraDevice> {
    let has_physical = devices
        .iter()
        .any(|device| !device.id.to_ascii_uppercase().contains("SWD#SGDEVAPI#"));
    if !has_physical {
        return devices;
    }
    devices
        .into_iter()
        .filter(|device| !device.id.to_ascii_uppercase().contains("SWD#SGDEVAPI#"))
        .collect()
}

#[derive(Deserialize)]
struct CameraSelectionPayload {
    camera_id: String,
}

async fn select_camera(
    State(state): State<WebState>,
    Json(payload): Json<CameraSelectionPayload>,
) -> Response {
    if payload.camera_id.trim().is_empty() {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({"detail":"camera_id is required"})),
        )
            .into_response();
    }
    let camera_key = payload.camera_id;
    let selected = if let Some(camera_id) = camera_key.strip_prefix("local:") {
        let camera_id = camera_id.to_owned();
        let controller = state.media.clone();
        let device =
            match tokio::task::spawn_blocking(move || controller.select_camera(camera_id)).await {
                Ok(Ok(device)) => device,
                Ok(Err(error)) => {
                    return (
                        StatusCode::CONFLICT,
                        Json(json!({"detail": error.to_string()})),
                    )
                        .into_response();
                }
                Err(error) => return internal_error(error),
            };
        CameraOption {
            id: camera_key.clone(),
            name: camera_display_name(&computer_name(), &device.name),
            source: "local",
            online: true,
            stream_url: "/stream".into(),
            audio_url: "/api/audio/pcm".into(),
        }
    } else if let Some(remote_key) = camera_key.strip_prefix("remote:") {
        let Some((node_index, remote_camera_id)) = remote_key.split_once(':') else {
            return invalid_camera_key();
        };
        let Ok(node_index) = node_index.parse::<usize>() else {
            return invalid_camera_key();
        };
        let node = {
            let settings = state.settings.read().expect("settings poisoned");
            settings.remote_nodes.get(node_index).cloned()
        };
        let Some(node) = node else {
            return invalid_camera_key();
        };
        if remote_camera_id.is_empty() {
            return (
                StatusCode::CONFLICT,
                Json(json!({"detail":"远程电脑当前离线"})),
            )
                .into_response();
        }
        if let Err(error) = select_remote_camera(&node, remote_camera_id).await {
            return (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("远程摄像头切换失败：{error}")})),
            )
                .into_response();
        }
        let camera_name = fetch_remote_cameras(&node)
            .await
            .ok()
            .and_then(|devices| {
                devices
                    .into_iter()
                    .find(|device| device.id == remote_camera_id)
            })
            .map(|device| device.name)
            .unwrap_or_else(|| "摄像头".into());
        let remote_computer_name = fetch_remote_computer_name(&node)
            .await
            .unwrap_or_else(|| node.name.clone());
        CameraOption {
            id: camera_key.clone(),
            name: camera_display_name(&remote_computer_name, &camera_name),
            source: "remote",
            online: true,
            stream_url: format!("/api/remote_stream/{node_index}"),
            audio_url: format!("/api/remote_audio/{node_index}"),
        }
    } else {
        return invalid_camera_key();
    };
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        settings.selected_camera_key = camera_key.clone();
        if let Some(camera_id) = camera_key.strip_prefix("local:") {
            settings.camera_id = camera_id.to_owned();
        }
    }
    let mut persisted = match load_or_create_settings(&state.paths) {
        Ok(settings) => settings,
        Err(error) => return internal_error(error),
    };
    persisted.selected_camera_key = camera_key.clone();
    if let Some(camera_id) = camera_key.strip_prefix("local:") {
        persisted.camera_id = camera_id.to_owned();
    }
    if let Err(error) = save_settings_atomic(&state.paths, &persisted) {
        return internal_error(error);
    }
    Json(json!({"selected": selected})).into_response()
}

fn invalid_camera_key() -> Response {
    (
        StatusCode::UNPROCESSABLE_ENTITY,
        Json(json!({"detail":"invalid camera key"})),
    )
        .into_response()
}

async fn remote_nodes(State(state): State<WebState>) -> Json<Value> {
    let nodes = state
        .settings
        .read()
        .expect("settings poisoned")
        .remote_nodes
        .clone();
    Json(json!({"nodes": nodes}))
}

#[derive(Deserialize)]
struct RemoteNodePayload {
    name: String,
    address: String,
}

async fn add_remote_node(
    State(state): State<WebState>,
    Json(payload): Json<RemoteNodePayload>,
) -> Response {
    let raw_address = payload.address.trim();
    let address = if raw_address.contains("://") {
        raw_address.to_owned()
    } else {
        format!("https://{raw_address}")
    };
    let valid_https = parse_remote_endpoint(&address)
        .map(|endpoint| endpoint.tls)
        .unwrap_or(false);
    if !valid_https || payload.name.trim().is_empty() {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({"detail":"name and HTTPS address (IP:port) are required"})),
        )
            .into_response();
    }
    let node = RemoteNode {
        name: payload.name.trim().to_owned(),
        address,
    };
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        if let Some(existing) = settings
            .remote_nodes
            .iter_mut()
            .find(|existing| existing.address == node.address)
        {
            *existing = node.clone();
        } else {
            settings.remote_nodes.push(node.clone());
        }
    }
    let mut persisted = match load_or_create_settings(&state.paths) {
        Ok(settings) => settings,
        Err(error) => return internal_error(error),
    };
    if let Some(existing) = persisted
        .remote_nodes
        .iter_mut()
        .find(|existing| existing.address == node.address)
    {
        *existing = node.clone();
    } else {
        persisted.remote_nodes.push(node.clone());
    }
    if let Err(error) = save_settings_atomic(&state.paths, &persisted) {
        return internal_error(error);
    }
    Json(json!({"node": node})).into_response()
}

async fn remote_stream(
    State(state): State<WebState>,
    AxumPath(node_index): AxumPath<usize>,
) -> Response {
    let node = {
        let settings = state.settings.read().expect("settings poisoned");
        settings.remote_nodes.get(node_index).cloned()
    };
    let Some(node) = node else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let (mut socket, initial, status, content_type) =
        match open_remote_response(&node, "GET", "/stream", None).await {
            Ok(response) => response,
            Err(error) => return (StatusCode::BAD_GATEWAY, error.to_string()).into_response(),
        };
    if status != 200 {
        return (
            StatusCode::BAD_GATEWAY,
            format!("remote stream returned {status}"),
        )
            .into_response();
    }
    let body = Body::from_stream(stream! {
        if !initial.is_empty() {
            yield Ok::<Bytes, std::io::Error>(Bytes::from(initial));
        }
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            match socket.read(&mut buffer).await {
                Ok(0) => break,
                Ok(read) => yield Ok::<Bytes, std::io::Error>(Bytes::copy_from_slice(&buffer[..read])),
                Err(_) => break,
            }
        }
    });
    let mut response = Response::new(body);
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&content_type).unwrap_or_else(|_| {
            HeaderValue::from_static("multipart/x-mixed-replace; boundary=frame")
        }),
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

async fn remote_audio(
    State(state): State<WebState>,
    AxumPath(node_index): AxumPath<usize>,
) -> Response {
    let node = {
        let settings = state.settings.read().expect("settings poisoned");
        settings.remote_nodes.get(node_index).cloned()
    };
    let Some(node) = node else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let (mut socket, initial, status, _) =
        match open_remote_response(&node, "GET", "/api/audio/pcm", None).await {
            Ok(response) => response,
            Err(error) => return (StatusCode::BAD_GATEWAY, error.to_string()).into_response(),
        };
    if status != 200 {
        return (
            StatusCode::BAD_GATEWAY,
            format!("remote audio returned {status}"),
        )
            .into_response();
    }
    let body = Body::from_stream(stream! {
        if !initial.is_empty() {
            yield Ok::<Bytes, std::io::Error>(Bytes::from(initial));
        }
        let mut buffer = vec![0u8; 32 * 1024];
        loop {
            match socket.read(&mut buffer).await {
                Ok(0) => break,
                Ok(read) => yield Ok::<Bytes, std::io::Error>(Bytes::copy_from_slice(&buffer[..read])),
                Err(_) => break,
            }
        }
    });
    let mut response = Response::new(body);
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
        .headers_mut()
        .insert("X-Audio-Sample-Rate", HeaderValue::from_static("16000"));
    response
        .headers_mut()
        .insert("X-Audio-Channels", HeaderValue::from_static("1"));
    response
}

async fn fetch_remote_cameras(node: &RemoteNode) -> Result<Vec<monitor_media::CameraDevice>> {
    let (_, initial, status, _) =
        open_remote_response(node, "GET", "/api/local_cameras", None).await?;
    if status == 200 {
        if initial.len() > 1024 * 1024 {
            anyhow::bail!("remote camera list is too large");
        }
        let value: Value = serde_json::from_slice(&initial)?;
        if let Ok(devices) = serde_json::from_value(value["cameras"].clone()) {
            return Ok(devices);
        }
    }
    if let Ok((_, initial, 200, _)) = open_remote_response(node, "GET", "/api/cameras", None).await
    {
        if initial.len() > 1024 * 1024 {
            anyhow::bail!("remote camera list is too large");
        }
        let value: Value = serde_json::from_slice(&initial)?;
        if let Ok(devices) = serde_json::from_value(value["cameras"].clone()) {
            return Ok(devices);
        }
    }
    let (_, _, status, _) = open_remote_response(node, "GET", "/api/health", None).await?;
    if status == 200 {
        return Ok(vec![monitor_media::CameraDevice {
            id: "__default__".into(),
            name: "摄像头".into(),
            has_microphone: false,
        }]);
    }
    anyhow::bail!("remote health returned {status}")
}

fn computer_name() -> String {
    std::env::var("COMPUTERNAME")
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "Windows 电脑".to_owned())
}

fn camera_display_name(computer_name: &str, camera_name: &str) -> String {
    format!("{} · {}", computer_name.trim(), camera_name.trim())
}

async fn fetch_remote_computer_name(node: &RemoteNode) -> Option<String> {
    let (_, initial, status, _) = open_remote_response(node, "GET", "/api/health", None)
        .await
        .ok()?;
    if status != 200 || initial.len() > 1024 * 1024 {
        return None;
    }
    let value: Value = serde_json::from_slice(&initial).ok()?;
    value
        .get("computer_name")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
}

fn persist_remote_computer_names(
    state: &WebState,
    discovered_names: &[(String, String)],
) -> Result<()> {
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        for (address, computer_name) in discovered_names {
            if let Some(node) = settings
                .remote_nodes
                .iter_mut()
                .find(|node| node.address == *address)
            {
                node.name = computer_name.clone();
            }
        }
    }
    let mut persisted = load_or_create_settings(&state.paths)?;
    for (address, computer_name) in discovered_names {
        if let Some(node) = persisted
            .remote_nodes
            .iter_mut()
            .find(|node| node.address == *address)
        {
            node.name = computer_name.clone();
        }
    }
    save_settings_atomic(&state.paths, &persisted)
}

async fn select_remote_camera(node: &RemoteNode, camera_id: &str) -> Result<()> {
    if camera_id == "__default__" {
        let (_, _, status, _) = open_remote_response(node, "GET", "/api/health", None).await?;
        if status == 200 {
            return Ok(());
        }
        anyhow::bail!("remote health returned {status}");
    }
    let mut last_status = 0;
    for candidate in [format!("local:{camera_id}"), camera_id.to_owned()] {
        let body = serde_json::to_vec(&json!({"camera_id": candidate}))?;
        let (_, _, status, _) =
            open_remote_response(node, "POST", "/api/cameras", Some(&body)).await?;
        if status == 200 {
            return Ok(());
        }
        last_status = status;
    }
    anyhow::bail!("remote camera selection returned {last_status}")
}

async fn open_remote_response(
    node: &RemoteNode,
    method: &str,
    path: &str,
    body: Option<&[u8]>,
) -> Result<(RemoteStream, Vec<u8>, u16, String)> {
    let endpoint = parse_remote_endpoint(&node.address)?;
    let mut socket = connect_remote(&endpoint).await?;
    let body = body.unwrap_or_default();
    let request = format!(
        "{method} {path} HTTP/1.0\r\nHost: {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        endpoint.authority,
        body.len()
    );
    socket.write_all(request.as_bytes()).await?;
    if !body.is_empty() {
        socket.write_all(body).await?;
    }
    let mut received = Vec::with_capacity(4096);
    let header_end = loop {
        if received.len() > 64 * 1024 {
            anyhow::bail!("remote response headers are too large");
        }
        let mut chunk = [0u8; 4096];
        let read = timeout(Duration::from_secs(5), socket.read(&mut chunk)).await??;
        if read == 0 {
            anyhow::bail!("remote response ended before headers");
        }
        received.extend_from_slice(&chunk[..read]);
        if let Some(position) = received.windows(4).position(|window| window == b"\r\n\r\n") {
            break position + 4;
        }
    };
    let header_text = std::str::from_utf8(&received[..header_end])?;
    let status = header_text
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|value| value.parse::<u16>().ok())
        .context("remote response status is invalid")?;
    let content_type = header_text
        .lines()
        .find_map(|line| {
            line.split_once(':')
                .filter(|(name, _)| name.eq_ignore_ascii_case("content-type"))
        })
        .map(|(_, value)| value.trim().to_owned())
        .unwrap_or_else(|| "application/octet-stream".into());
    let mut body_bytes = received.split_off(header_end);
    if path != "/stream" && path != "/api/audio/pcm" && !path.starts_with("/api/recordings/file?") {
        timeout(Duration::from_secs(5), socket.read_to_end(&mut body_bytes)).await??;
    }
    Ok((socket, body_bytes, status, content_type))
}

trait AsyncRemoteStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T> AsyncRemoteStream for T where T: AsyncRead + AsyncWrite + Unpin + Send {}
type RemoteStream = Pin<Box<dyn AsyncRemoteStream>>;

#[derive(Debug)]
struct RemoteEndpoint {
    tls: bool,
    authority: String,
    socket: SocketAddr,
}

fn parse_remote_endpoint(address: &str) -> Result<RemoteEndpoint> {
    let address = address.trim().trim_end_matches('/');
    let (tls, authority) = if let Some(value) = address.strip_prefix("https://") {
        (true, value)
    } else if let Some(value) = address.strip_prefix("http://") {
        (false, value)
    } else {
        (false, address)
    };
    let socket = authority
        .parse::<SocketAddr>()
        .with_context(|| format!("invalid remote monitor address: {address}"))?;
    Ok(RemoteEndpoint {
        tls,
        authority: authority.to_owned(),
        socket,
    })
}

async fn connect_remote(endpoint: &RemoteEndpoint) -> Result<RemoteStream> {
    if !endpoint.tls {
        anyhow::bail!("remote Camera Monitor connections require HTTPS");
    }
    let socket = timeout(
        Duration::from_millis(1200),
        TcpStream::connect(endpoint.socket),
    )
    .await??;
    let ca_path = private_ca_path()?;
    let mut ca_reader =
        BufReader::new(File::open(&ca_path).with_context(|| {
            format!("private CA certificate is missing: {}", ca_path.display())
        })?);
    let mut roots = rustls::RootCertStore::empty();
    for certificate in rustls_pemfile::certs(&mut ca_reader) {
        roots.add(certificate?)?;
    }
    if roots.is_empty() {
        anyhow::bail!("private CA certificate contains no certificates");
    }
    let config = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let connector = TlsConnector::from(Arc::new(config));
    let server_name = rustls::pki_types::ServerName::IpAddress(endpoint.socket.ip().into());
    let tls_stream = timeout(
        Duration::from_secs(3),
        connector.connect(server_name, socket),
    )
    .await??;
    Ok(Box::pin(tls_stream))
}

fn private_ca_path() -> Result<PathBuf> {
    if let Some(path) = env::var_os("CAMERA_MONITOR_TLS_CA") {
        return Ok(PathBuf::from(path));
    }
    let local = env::var_os("LOCALAPPDATA").context("LOCALAPPDATA is unavailable")?;
    Ok(PathBuf::from(local)
        .join("CameraMonitor")
        .join("tls")
        .join("ca.crt"))
}

async fn stats(State(state): State<WebState>) -> Json<Value> {
    let runtime = state.runtime.read().expect("runtime poisoned").clone();
    Json(json!({
        "fps": runtime.fps,
        "avg_latency_ms": 0.0,
        "tracked_targets": 0,
        "gpu_utilization": 0.0,
        "capture_status": runtime.capture_status,
        "capture_backend": runtime.capture_backend,
    }))
}

async fn selected_stats(State(state): State<WebState>) -> Response {
    let remote_node = {
        let settings = state.settings.read().expect("settings poisoned");
        settings
            .selected_camera_key
            .strip_prefix("remote:")
            .and_then(|key| key.split_once(':'))
            .and_then(|(node_index, _)| node_index.parse::<usize>().ok())
            .and_then(|node_index| settings.remote_nodes.get(node_index).cloned())
    };
    let Some(node) = remote_node else {
        return stats(State(state)).await.into_response();
    };
    match open_remote_response(&node, "GET", "/stats", None).await {
        Ok((_, body, 200, _)) => match serde_json::from_slice::<Value>(&body) {
            Ok(value) => Json(value).into_response(),
            Err(error) => (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("远程统计信息解析失败：{error}")})),
            )
                .into_response(),
        },
        Ok((_, _, status, _)) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"detail": format!("远程统计信息请求失败：HTTP {status}")})),
        )
            .into_response(),
        Err(error) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"detail": format!("无法读取远程统计信息：{error}")})),
        )
            .into_response(),
    }
}

fn schedule_in_window(schedule: &RecordingSchedule) -> bool {
    use chrono::{Datelike, Local, Timelike};
    let now = Local::now();
    schedule.contains(
        now.weekday().number_from_monday() as u8,
        now.hour() * 60 + now.minute(),
    )
}

fn recording_mode_label(mode: &RecordingMode) -> &'static str {
    match mode {
        RecordingMode::Off => "off",
        RecordingMode::Continuous => "continuous",
        RecordingMode::Schedule => "schedule",
    }
}

async fn capture_info(State(state): State<WebState>) -> Json<Value> {
    let runtime = state.runtime.read().expect("runtime poisoned").clone();
    let settings = state.settings.read().expect("settings poisoned").clone();
    let in_window = schedule_in_window(&settings.recording_schedule);
    Json(json!({
        "requested_width": settings.width,
        "requested_height": settings.height,
        "requested_bitrate": settings.bitrate,
        "actual_width": runtime.width,
        "actual_height": runtime.height,
        "mjpeg_quality": 75,
        "target_fps": settings.fps,
        "capture_status": runtime.capture_status,
        "capture_backend": runtime.capture_backend,
        "stream_clients": runtime.preview_clients,
        "preview_status": runtime.preview_status,
        "preview_error": runtime.preview_error,
        "local_recording_enabled": runtime.recording_active,
        "recording_mode": recording_mode_label(&settings.recording_mode),
        "recording_active": runtime.recording_active,
        "recording_triggered_by": if runtime.recording_active {
            recording_mode_label(&settings.recording_mode)
        } else {
            "none"
        },
        "recording_schedule": settings.recording_schedule,
        "schedule_in_window": in_window,
        "auto_stop_remaining_ms": 0,
        "local_recording_status": runtime.recording_status,
        "local_recording_output_dir": settings.recording_root,
        "local_recording_retention_days": settings.retention_days,
        "local_recording_segment_minutes": 60,
        "local_recording_current_file": runtime.recording_file,
        "encoder": runtime.encoder,
        "ai_enabled": false,
    }))
}

async fn selected_capture_info(State(state): State<WebState>) -> Response {
    let remote_node = {
        let settings = state.settings.read().expect("settings poisoned");
        settings
            .selected_camera_key
            .strip_prefix("remote:")
            .and_then(|key| key.split_once(':'))
            .and_then(|(node_index, _)| node_index.parse::<usize>().ok())
            .and_then(|node_index| settings.remote_nodes.get(node_index).cloned())
    };
    let Some(node) = remote_node else {
        return capture_info(State(state)).await.into_response();
    };
    match open_remote_response(&node, "GET", "/api/capture_info", None).await {
        Ok((_, body, 200, _)) => match serde_json::from_slice::<Value>(&body) {
            Ok(value) => Json(value).into_response(),
            Err(error) => (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("远程摄像头状态解析失败：{error}")})),
            )
                .into_response(),
        },
        Ok((_, _, status, _)) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"detail": format!("远程摄像头状态请求失败：HTTP {status}")})),
        )
            .into_response(),
        Err(error) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"detail": format!("无法连接远程摄像头：{error}")})),
        )
            .into_response(),
    }
}

#[derive(Deserialize, Serialize)]
struct CameraSettings {
    width: u32,
    height: u32,
    fps: u32,
    #[serde(default)]
    bitrate: Option<u32>,
}

async fn camera_settings(
    State(state): State<WebState>,
    Json(payload): Json<CameraSettings>,
) -> Response {
    if !(640..=3840).contains(&payload.width)
        || !(480..=2160).contains(&payload.height)
        || !(5..=60).contains(&payload.fps)
        || payload
            .bitrate
            .map_or(false, |bitrate| !(256_000..=16_000_000).contains(&bitrate))
    {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({"detail":"invalid camera settings"})),
        )
            .into_response();
    }

    let remote_node = {
        let settings = state.settings.read().expect("settings poisoned");
        settings
            .selected_camera_key
            .strip_prefix("remote:")
            .and_then(|key| key.split_once(':'))
            .and_then(|(node_index, _)| node_index.parse::<usize>().ok())
            .and_then(|node_index| settings.remote_nodes.get(node_index).cloned())
    };
    if let Some(node) = remote_node {
        let body = match serde_json::to_vec(&payload) {
            Ok(body) => body,
            Err(error) => return internal_error(error),
        };
        return match open_remote_response(&node, "POST", "/api/camera_settings", Some(&body)).await
        {
            Ok((_, body, 200, _)) => match serde_json::from_slice::<Value>(&body) {
                Ok(value) => Json(value).into_response(),
                Err(error) => (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({"detail": format!("远程设置响应解析失败：{error}")})),
                )
                    .into_response(),
            },
            Ok((_, body, status, _)) => {
                let detail = serde_json::from_slice::<Value>(&body)
                    .ok()
                    .and_then(|value| value["detail"].as_str().map(str::to_owned))
                    .unwrap_or_else(|| format!("HTTP {status}"));
                (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({"detail": format!("远程摄像头设置失败：{detail}")})),
                )
                    .into_response()
            }
            Err(error) => (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("无法连接远程摄像头：{error}")})),
            )
                .into_response(),
        };
    }

    let controller = state.media.clone();
    let width = payload.width;
    let height = payload.height;
    let fps = payload.fps;
    let bitrate = payload
        .bitrate
        .unwrap_or_else(|| state.settings.read().expect("settings poisoned").bitrate);
    if let Err(error) = tokio::task::spawn_blocking(move || {
        controller.set_capture_settings(width, height, fps, bitrate)
    })
    .await
    .unwrap_or_else(|error| Err(error.into()))
    {
        return internal_error(error);
    }
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        settings.width = payload.width;
        settings.height = payload.height;
        settings.fps = payload.fps;
        settings.bitrate = bitrate;
    }
    let mut persisted = match load_or_create_settings(&state.paths) {
        Ok(settings) => settings,
        Err(error) => return internal_error(error),
    };
    persisted.width = payload.width;
    persisted.height = payload.height;
    persisted.fps = payload.fps;
    persisted.bitrate = bitrate;
    if let Err(error) = save_settings_atomic(&state.paths, &persisted) {
        return internal_error(error);
    }
    capture_info(State(state)).await.into_response()
}

#[derive(Deserialize)]
struct RecordingModePayload {
    mode: String,
}

async fn recording_mode(
    State(state): State<WebState>,
    Json(payload): Json<RecordingModePayload>,
) -> Response {
    let mode = match payload.mode.as_str() {
        "off" => RecordingMode::Off,
        "continuous" => RecordingMode::Continuous,
        "schedule" => RecordingMode::Schedule,
        "auto" => {
            return (
                StatusCode::CONFLICT,
                Json(json!({"detail":"需要安装 AI 插件"})),
            )
                .into_response();
        }
        _ => {
            return (
                StatusCode::UNPROCESSABLE_ENTITY,
                Json(json!({"detail":"invalid recording mode"})),
            )
                .into_response();
        }
    };
    if let Err(error) = state.media.set_mode(mode.clone()) {
        return internal_error(error);
    }
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        settings.recording_mode = mode.clone();
    }
    let mut persisted = match load_or_create_settings(&state.paths) {
        Ok(settings) => settings,
        Err(error) => return internal_error(error),
    };
    persisted.recording_mode = mode;
    if let Err(error) = save_settings_atomic(&state.paths, &persisted) {
        return internal_error(error);
    }
    capture_info(State(state)).await.into_response()
}

#[derive(Deserialize)]
struct RecordingSchedulePayload {
    start: String,
    end: String,
    #[serde(default)]
    days: Vec<u8>,
}

async fn recording_schedule(State(state): State<WebState>) -> Json<Value> {
    let (mode, schedule) = {
        let settings = state.settings.read().expect("settings poisoned");
        (
            settings.recording_mode.clone(),
            settings.recording_schedule.clone(),
        )
    };
    let runtime = state.runtime.read().expect("runtime poisoned").clone();
    Json(json!({
        "mode": recording_mode_label(&mode),
        "schedule": schedule,
        "schedule_in_window": schedule_in_window(&schedule),
        "recording_active": runtime.recording_active,
        "recording_status": runtime.recording_status,
        "recording_device_id": "local",
        "recording_device_name": computer_name(),
    }))
}

async fn selected_recording_status(State(state): State<WebState>) -> Response {
    let remote_node = {
        let settings = state.settings.read().expect("settings poisoned");
        settings
            .selected_camera_key
            .strip_prefix("remote:")
            .and_then(|key| key.split_once(':'))
            .and_then(|(node_index, _)| {
                let node_index = node_index.parse::<usize>().ok()?;
                settings
                    .remote_nodes
                    .get(node_index)
                    .cloned()
                    .map(|node| (node_index, node))
            })
    };
    let Some((node_index, node)) = remote_node else {
        return recording_schedule(State(state)).await.into_response();
    };
    match open_remote_response(&node, "GET", "/api/recording_schedule", None).await {
        Ok((_, body, 200, _)) => match serde_json::from_slice::<Value>(&body) {
            Ok(mut value) => {
                value["recording_device_id"] = json!(format!("remote:{node_index}"));
                value["recording_device_name"] = json!(node.name);
                Json(value).into_response()
            }
            Err(error) => (
                StatusCode::BAD_GATEWAY,
                Json(json!({"detail": format!("远端录像状态解析失败：{error}")})),
            )
                .into_response(),
        },
        Ok((_, _, status, _)) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"detail": format!("远端录像状态请求失败：HTTP {status}")})),
        )
            .into_response(),
        Err(error) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({"detail": format!("无法读取远端录像状态：{error}")})),
        )
            .into_response(),
    }
}

async fn set_recording_schedule(
    State(state): State<WebState>,
    Json(payload): Json<RecordingSchedulePayload>,
) -> Response {
    let mut days = payload.days;
    days.sort_unstable();
    days.dedup();
    let schedule = RecordingSchedule {
        start: payload.start.trim().to_owned(),
        end: payload.end.trim().to_owned(),
        days,
    };
    if !schedule.is_valid() {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({"detail":"invalid recording schedule (expect HH:MM, days 1-7)"})),
        )
            .into_response();
    }
    if let Err(error) = state.media.set_schedule(schedule.clone()) {
        return internal_error(error);
    }
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        settings.recording_schedule = schedule.clone();
    }
    let mut persisted = match load_or_create_settings(&state.paths) {
        Ok(settings) => settings,
        Err(error) => return internal_error(error),
    };
    persisted.recording_schedule = schedule;
    if let Err(error) = save_settings_atomic(&state.paths, &persisted) {
        return internal_error(error);
    }
    recording_schedule(State(state)).await.into_response()
}

#[derive(Deserialize)]
struct LocalRecordingPayload {
    enabled: bool,
}

async fn local_recording(
    State(state): State<WebState>,
    Json(payload): Json<LocalRecordingPayload>,
) -> Response {
    recording_mode(
        State(state),
        Json(RecordingModePayload {
            mode: if payload.enabled { "continuous" } else { "off" }.into(),
        }),
    )
    .await
}

fn local_recording_groups(settings: &Settings) -> Result<Vec<Value>> {
    let device_name = computer_name();
    let mut groups: Vec<(String, Vec<Value>)> = Vec::new();
    for item in list_recordings(settings)? {
        let entry = json!({
            "relative_path": item.path,
            "filename": item.name,
            "started_at": item.modified_at.to_rfc3339(),
            "size_bytes": item.size_bytes,
            "modified_at": item.modified_at.to_rfc3339(),
            "device_id": "local",
            "device_name": device_name,
        });
        if let Some((_, entries)) = groups.iter_mut().find(|(day, _)| day == &item.day) {
            entries.push(entry);
        } else {
            groups.push((item.day, vec![entry]));
        }
    }
    Ok(groups
        .into_iter()
        .map(|(day, items)| json!({ "day": day, "items": items }))
        .collect())
}

async fn local_recordings(State(state): State<WebState>) -> Response {
    let settings = state.settings.read().expect("settings poisoned").clone();
    match local_recording_groups(&settings) {
        Ok(groups) => Json(groups).into_response(),
        Err(error) => internal_error(error),
    }
}

async fn recordings(State(state): State<WebState>) -> Response {
    let settings = state.settings.read().expect("settings poisoned").clone();
    let local_groups = match local_recording_groups(&settings) {
        Ok(groups) => groups,
        Err(error) => return internal_error(error),
    };
    let mut groups: Vec<(String, Vec<Value>)> = Vec::new();
    merge_recording_groups(&mut groups, local_groups, None);

    for (node_index, node) in settings.remote_nodes.iter().enumerate() {
        let response = match open_remote_response(node, "GET", "/api/local_recordings", None).await
        {
            Ok((_, body, 200, _)) => Some(body),
            _ => match open_remote_response(node, "GET", "/api/recordings", None).await {
                Ok((_, body, 200, _)) => Some(body),
                _ => None,
            },
        };
        let Some(body) = response else { continue };
        if body.len() > 8 * 1024 * 1024 {
            tracing::warn!(node = %node.address, "remote recording list is too large");
            continue;
        }
        let Ok(remote_groups) = serde_json::from_slice::<Vec<Value>>(&body) else {
            tracing::warn!(node = %node.address, "remote recording list is invalid");
            continue;
        };
        let remote_name = fetch_remote_computer_name(node)
            .await
            .unwrap_or_else(|| node.name.clone());
        merge_recording_groups(
            &mut groups,
            remote_groups,
            Some((node_index, remote_name.as_str())),
        );
    }

    groups.sort_by(|left, right| right.0.cmp(&left.0));
    for (_, items) in &mut groups {
        items.sort_by(|left, right| {
            right["started_at"]
                .as_str()
                .unwrap_or_default()
                .cmp(left["started_at"].as_str().unwrap_or_default())
        });
    }
    Json(
        groups
            .into_iter()
            .map(|(day, items)| json!({ "day": day, "items": items }))
            .collect::<Vec<_>>(),
    )
    .into_response()
}

fn merge_recording_groups(
    target: &mut Vec<(String, Vec<Value>)>,
    source: Vec<Value>,
    remote: Option<(usize, &str)>,
) {
    for group in source {
        let Some(day) = group.get("day").and_then(Value::as_str) else {
            continue;
        };
        let Some(items) = group.get("items").and_then(Value::as_array) else {
            continue;
        };
        let destination = if let Some((_, entries)) = target.iter_mut().find(|(key, _)| key == day)
        {
            entries
        } else {
            target.push((day.to_owned(), Vec::new()));
            &mut target.last_mut().expect("group was inserted").1
        };
        for item in items {
            let mut item = item.clone();
            if let Some((node_index, device_name)) = remote {
                let Some(remote_path) = item.get("relative_path").and_then(Value::as_str) else {
                    continue;
                };
                item["relative_path"] = json!(format!("remote:{node_index}:{remote_path}"));
                item["device_id"] = json!(format!("remote:{node_index}"));
                item["device_name"] = json!(device_name);
            }
            destination.push(item);
        }
    }
}

#[derive(Deserialize)]
struct RecordingQuery {
    path: String,
}

async fn recording_file(
    State(state): State<WebState>,
    Query(query): Query<RecordingQuery>,
    request: Request<Body>,
) -> Response {
    let settings = state.settings.read().expect("settings poisoned").clone();
    if let Some(remote) = query.path.strip_prefix("remote:") {
        let Some((node_index, remote_path)) = remote.split_once(':') else {
            return (StatusCode::BAD_REQUEST, "invalid remote recording id").into_response();
        };
        let Ok(node_index) = node_index.parse::<usize>() else {
            return (StatusCode::BAD_REQUEST, "invalid remote node id").into_response();
        };
        let Some(node) = settings.remote_nodes.get(node_index) else {
            return StatusCode::NOT_FOUND.into_response();
        };
        let remote_uri = format!(
            "/api/recordings/file?path={}",
            percent_encode_query(remote_path)
        );
        let (mut socket, initial, status, content_type) =
            match open_remote_response(node, "GET", &remote_uri, None).await {
                Ok(response) => response,
                Err(error) => return (StatusCode::BAD_GATEWAY, error.to_string()).into_response(),
            };
        if status != 200 {
            return (
                StatusCode::from_u16(status).unwrap_or(StatusCode::BAD_GATEWAY),
                "remote recording is unavailable",
            )
                .into_response();
        }
        let body = Body::from_stream(stream! {
            if !initial.is_empty() {
                yield Ok::<Bytes, std::io::Error>(Bytes::from(initial));
            }
            let mut buffer = vec![0u8; 64 * 1024];
            loop {
                match socket.read(&mut buffer).await {
                    Ok(0) => break,
                    Ok(read) => yield Ok::<Bytes, std::io::Error>(Bytes::copy_from_slice(&buffer[..read])),
                    Err(error) => {
                        yield Err(error);
                        break;
                    }
                }
            }
        });
        let mut response = Response::new(body);
        response.headers_mut().insert(
            header::CONTENT_TYPE,
            HeaderValue::from_str(&content_type)
                .unwrap_or_else(|_| HeaderValue::from_static("video/mp4")),
        );
        response
            .headers_mut()
            .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
        return response;
    }
    let path = match resolve_recording(&settings, &query.path) {
        Ok(path) => path,
        Err(error) => return (StatusCode::NOT_FOUND, error.to_string()).into_response(),
    };
    match ServeFile::new(path).oneshot(request).await {
        Ok(response) => response.map(Body::new),
        Err(error) => internal_error(error),
    }
}

fn percent_encode_query(value: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut encoded = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            encoded.push(byte as char);
        } else {
            encoded.push('%');
            encoded.push(HEX[(byte >> 4) as usize] as char);
            encoded.push(HEX[(byte & 0x0f) as usize] as char);
        }
    }
    encoded
}

#[derive(Deserialize)]
struct LimitQuery {
    limit: Option<u32>,
}

async fn visits(State(state): State<WebState>, Query(query): Query<LimitQuery>) -> Response {
    match state
        .repository
        .list_visits(query.limit.unwrap_or(200).min(1000))
    {
        Ok(rows) => Json(rows).into_response(),
        Err(error) => internal_error(error),
    }
}

#[derive(Deserialize)]
struct NotePayload {
    note: String,
}

#[derive(Deserialize)]
struct BulkDeletePayload {
    visit_ids: Vec<i64>,
}

async fn bulk_delete_visits(
    State(state): State<WebState>,
    Json(payload): Json<BulkDeletePayload>,
) -> Response {
    match state.repository.delete_visits(&payload.visit_ids) {
        Ok(count) => Json(json!({"ok":true,"deleted_count":count})).into_response(),
        Err(error) => internal_error(error),
    }
}

async fn update_person_note(
    State(state): State<WebState>,
    AxumPath(id): AxumPath<String>,
    Json(payload): Json<NotePayload>,
) -> Response {
    match state
        .repository
        .update_person_note(&id, payload.note.trim())
    {
        Ok(count) => Json(json!({"ok":true,"person_id":id,"updated_count":count})).into_response(),
        Err(error) => internal_error(error),
    }
}

async fn update_visit_note(
    State(state): State<WebState>,
    AxumPath(id): AxumPath<i64>,
    Json(payload): Json<NotePayload>,
) -> Response {
    match state.repository.update_visit_note(id, payload.note.trim()) {
        Ok(true) => Json(json!({"ok":true,"visit_id":id})).into_response(),
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(json!({"detail":"visit not found"})),
        )
            .into_response(),
        Err(error) => internal_error(error),
    }
}

async fn delete_visit(State(state): State<WebState>, AxumPath(id): AxumPath<i64>) -> Response {
    match state.repository.delete_visit(id) {
        Ok(true) => Json(json!({"ok":true,"visit_id":id})).into_response(),
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Json(json!({"detail":"visit not found"})),
        )
            .into_response(),
        Err(error) => internal_error(error),
    }
}

async fn events() -> Json<Vec<Value>> {
    Json(Vec::new())
}
async fn empty_list() -> Json<Vec<Value>> {
    Json(Vec::new())
}
async fn ai_unavailable() -> Response {
    (
        StatusCode::CONFLICT,
        Json(json!({"detail":"需要安装 AI 插件"})),
    )
        .into_response()
}

async fn daily_report(State(state): State<WebState>) -> Response {
    report(state, 1)
}
async fn weekly_report(State(state): State<WebState>) -> Response {
    report(state, 7)
}

fn report(state: WebState, days: u32) -> Response {
    match state.repository.summary() {
        Ok((persons, average)) => Json(json!({
            "period_days": days,
            "unique_persons": persons,
            "avg_dwell_seconds": average,
            "ai_enabled": false,
        }))
        .into_response(),
        Err(error) => internal_error(error),
    }
}

async fn audio_info(State(state): State<WebState>) -> Json<Value> {
    let runtime = state.runtime.read().expect("runtime poisoned");
    Json(
        json!({"sample_rate":16000,"channels":1,"dtype":"int16","status":runtime.microphone_status,"last_error":runtime.last_error}),
    )
}

async fn audio_pcm(State(state): State<WebState>) -> Response {
    let mut receiver = state.audio.subscribe();
    increment_audio_clients(&state.runtime, 1);
    let runtime = state.runtime.clone();
    let body = Body::from_stream(stream! {
        let _guard = AudioGuard(runtime);
        loop {
            if receiver.changed().await.is_err() { break; }
            let pcm = receiver.borrow().clone();
            if pcm.is_empty() { continue; }
            yield Ok::<Bytes, Infallible>(Bytes::copy_from_slice(&pcm));
        }
    });
    let mut response = Response::new(body);
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    response
        .headers_mut()
        .insert("X-Audio-Sample-Rate", HeaderValue::from_static("16000"));
    response
        .headers_mut()
        .insert("X-Audio-Channels", HeaderValue::from_static("1"));
    response
        .headers_mut()
        .insert("X-Audio-DType", HeaderValue::from_static("int16"));
    response
}

async fn mjpeg_stream(State(state): State<WebState>) -> Response {
    let mut receiver = state.preview.subscribe();
    increment_clients(&state.runtime, 1);
    let runtime = state.runtime.clone();
    let body = Body::from_stream(stream! {
        let _guard = PreviewGuard(runtime);
        loop {
            if receiver.changed().await.is_err() { break; }
            let jpeg = receiver.borrow().clone();
            if jpeg.is_empty() { continue; }
            let mut chunk = Vec::with_capacity(jpeg.len() + 96);
            chunk.extend_from_slice(b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ");
            chunk.extend_from_slice(jpeg.len().to_string().as_bytes());
            chunk.extend_from_slice(b"\r\n\r\n");
            chunk.extend_from_slice(&jpeg);
            chunk.extend_from_slice(b"\r\n");
            yield Ok::<Bytes, Infallible>(Bytes::from(chunk));
        }
    });
    let mut response = Response::new(body);
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("multipart/x-mixed-replace; boundary=frame"),
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
        .headers_mut()
        .insert("X-Accel-Buffering", HeaderValue::from_static("no"));
    response
}

struct PreviewGuard(Arc<RwLock<RuntimeStatus>>);
impl Drop for PreviewGuard {
    fn drop(&mut self) {
        increment_clients(&self.0, -1);
    }
}

struct AudioGuard(Arc<RwLock<RuntimeStatus>>);
impl Drop for AudioGuard {
    fn drop(&mut self) {
        increment_audio_clients(&self.0, -1);
    }
}

fn increment_audio_clients(runtime: &Arc<RwLock<RuntimeStatus>>, delta: i32) {
    if let Ok(mut runtime) = runtime.write() {
        runtime.audio_clients = runtime.audio_clients.saturating_add_signed(delta);
    }
}

fn increment_clients(runtime: &Arc<RwLock<RuntimeStatus>>, delta: i32) {
    if let Ok(mut runtime) = runtime.write() {
        runtime.preview_clients = runtime.preview_clients.saturating_add_signed(delta);
    }
}

fn internal_error(error: impl std::fmt::Display) -> Response {
    tracing::error!(%error, "request failed");
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(json!({"detail":error.to_string()})),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dashboard_is_embedded() {
        assert!(INDEX_HTML.contains("/stream"));
        assert!(RECORDINGS_HTML.contains("video"));
    }

    #[test]
    fn camera_name_uses_computer_name() {
        assert_eq!(
            camera_display_name("DESKTOP-CAMERA", "USB Camera"),
            "DESKTOP-CAMERA · USB Camera"
        );
    }

    #[test]
    fn remote_endpoint_preserves_https_ip_identity() {
        let endpoint = parse_remote_endpoint("https://10.95.194.233:8000/").unwrap();
        assert!(endpoint.tls);
        assert_eq!(endpoint.authority, "10.95.194.233:8000");
        assert_eq!(endpoint.socket, "10.95.194.233:8000".parse().unwrap());

        let plaintext = parse_remote_endpoint("http://10.95.194.233:8000").unwrap();
        assert!(!plaintext.tls);
    }

    #[test]
    fn physical_camera_hides_windows_virtual_camera_group() {
        let devices = vec![
            monitor_media::CameraDevice {
                id: r"\\?\SWD#SGDEVAPI#VIRTUAL#{camera}".into(),
                name: "YourCameraGroup".into(),
                has_microphone: false,
            },
            monitor_media::CameraDevice {
                id: r"\\?\USB#VID_09DA&PID_2703#{camera}".into(),
                name: "A4tech FHD 1080P RGB PC Camera".into(),
                has_microphone: true,
            },
        ];
        let filtered = prefer_physical_cameras(devices);
        assert_eq!(filtered.len(), 1);
        assert!(filtered[0].id.contains("USB#VID_09DA"));
    }

    #[test]
    fn recording_query_path_is_percent_encoded() {
        assert_eq!(
            percent_encode_query("active:2026-08-18/file 01.mp4"),
            "active%3A2026-08-18%2Ffile%2001.mp4"
        );
    }

    #[test]
    fn remote_recordings_are_tagged_with_device() {
        let mut target = Vec::new();
        merge_recording_groups(
            &mut target,
            vec![json!({
                "day": "2026-08-18",
                "items": [{"relative_path":"active:2026-08-18/test.mp4","started_at":"2026-08-18T10:00:00+08:00"}]
            })],
            Some((0, "PC-ZKK")),
        );
        assert_eq!(target[0].1[0]["device_name"], "PC-ZKK");
        assert_eq!(
            target[0].1[0]["relative_path"],
            "remote:0:active:2026-08-18/test.mp4"
        );
    }
}
