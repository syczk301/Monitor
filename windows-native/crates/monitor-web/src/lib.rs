#![forbid(unsafe_code)]

use anyhow::Result;
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
    AppPaths, RecordingMode, Repository, Settings, list_recordings, load_or_create_settings,
    resolve_recording, save_settings_atomic,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    convert::Infallible,
    net::SocketAddr,
    sync::{Arc, RwLock},
};
use tokio::sync::watch;
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
    let app = router(state);
    let listener = tokio::net::TcpListener::bind(address).await?;
    tracing::info!(%address, "Rust native web server started");
    axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            let mut shutdown = shutdown;
            while !*shutdown.borrow() {
                if shutdown.changed().await.is_err() {
                    break;
                }
            }
        })
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
        .route("/api/capture_info", get(capture_info))
        .route("/api/camera_settings", post(camera_settings))
        .route("/api/recording_mode", post(recording_mode))
        .route("/api/local_recording", post(local_recording))
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
        "fps": runtime.fps,
        "inference_fps": 0.0,
        "latency_ms": 0.0,
        "tracked_targets": 0,
        "gpu_utilization": 0.0,
        "capture_status": runtime.capture_status,
        "capture_backend": runtime.capture_backend,
        "encoder": runtime.encoder,
        "ai_enabled": false,
        "preview_clients": runtime.preview_clients,
        "preview_status": runtime.preview_status,
        "preview_error": runtime.preview_error,
        "recording_bitrate": runtime.recording_bitrate,
        "last_error": runtime.last_error,
    }))
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

async fn capture_info(State(state): State<WebState>) -> Json<Value> {
    let runtime = state.runtime.read().expect("runtime poisoned").clone();
    let settings = state.settings.read().expect("settings poisoned").clone();
    Json(json!({
        "requested_width": settings.width,
        "requested_height": settings.height,
        "actual_width": runtime.width,
        "actual_height": runtime.height,
        "mjpeg_quality": 85,
        "target_fps": settings.fps,
        "capture_status": runtime.capture_status,
        "capture_backend": runtime.capture_backend,
        "stream_clients": runtime.preview_clients,
        "preview_status": runtime.preview_status,
        "preview_error": runtime.preview_error,
        "local_recording_enabled": runtime.recording_active,
        "recording_mode": match settings.recording_mode { RecordingMode::Off => "off", RecordingMode::Continuous => "continuous" },
        "recording_active": runtime.recording_active,
        "recording_triggered_by": if runtime.recording_active { "continuous" } else { "none" },
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

#[derive(Deserialize)]
struct CameraSettings {
    width: u32,
    height: u32,
    fps: u32,
}

async fn camera_settings(
    State(state): State<WebState>,
    Json(payload): Json<CameraSettings>,
) -> Response {
    if !(640..=3840).contains(&payload.width)
        || !(480..=2160).contains(&payload.height)
        || !(5..=60).contains(&payload.fps)
    {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({"detail":"invalid camera settings"})),
        )
            .into_response();
    }
    {
        let mut settings = state.settings.write().expect("settings poisoned");
        settings.width = payload.width;
        settings.height = payload.height;
        settings.fps = payload.fps;
    }
    let mut persisted = match load_or_create_settings(&state.paths) {
        Ok(settings) => settings,
        Err(error) => return internal_error(error),
    };
    persisted.width = payload.width;
    persisted.height = payload.height;
    persisted.fps = payload.fps;
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

async fn recordings(State(state): State<WebState>) -> Response {
    let settings = state.settings.read().expect("settings poisoned").clone();
    match list_recordings(&settings) {
        Ok(items) => {
            let mut groups: Vec<(String, Vec<Value>)> = Vec::new();
            for item in items {
                let entry = json!({
                    "relative_path": item.path,
                    "filename": item.name,
                    "started_at": item.modified_at.to_rfc3339(),
                    "size_bytes": item.size_bytes,
                    "modified_at": item.modified_at.to_rfc3339(),
                });
                if let Some((_, entries)) = groups.iter_mut().find(|(day, _)| day == &item.day) {
                    entries.push(entry);
                } else {
                    groups.push((item.day, vec![entry]));
                }
            }
            Json(
                groups
                    .into_iter()
                    .map(|(day, items)| json!({ "day": day, "items": items }))
                    .collect::<Vec<_>>(),
            )
            .into_response()
        }
        Err(error) => internal_error(error),
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
    let path = match resolve_recording(&settings, &query.path) {
        Ok(path) => path,
        Err(error) => return (StatusCode::NOT_FOUND, error.to_string()).into_response(),
    };
    match ServeFile::new(path).oneshot(request).await {
        Ok(response) => response.map(Body::new),
        Err(error) => internal_error(error),
    }
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
            let mut chunk = Vec::with_capacity(jpeg.len() + 64);
            chunk.extend_from_slice(b"--frame\r\nContent-Type: image/jpeg\r\n\r\n");
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
}
