use anyhow::{Context, Result};
use chrono::{Datelike, Local, Timelike};
use crossbeam_channel::{Receiver, Sender, bounded};
use fs2::available_space;
use monitor_storage::{RecordingMode, RecordingSchedule, Settings};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::{
        Arc, RwLock,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};
use windows::{
    Foundation::{PropertyType, PropertyValue},
    Graphics::Imaging::{BitmapEncoder, BitmapPropertySet, BitmapSize, BitmapTypedValue},
    Media::{
        Capture::Frames::{
            MediaFrameReader, MediaFrameReaderAcquisitionMode, MediaFrameReaderStartStatus,
            MediaFrameSourceGroup, MediaFrameSourceKind,
        },
        Capture::{
            MediaCapture, MediaCaptureInitializationSettings, MediaCaptureMemoryPreference,
            MediaCaptureSharingMode, StreamingCaptureMode,
        },
        MediaProperties::{MediaEncodingProfile, VideoEncodingQuality},
    },
    Storage::{
        StorageFile,
        Streams::{DataReader, InMemoryRandomAccessStream},
    },
    Win32::{
        Media::{
            Audio::{
                AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, IAudioCaptureClient,
                IAudioClient, IMMDeviceEnumerator, MMDeviceEnumerator, WAVE_FORMAT_PCM,
                WAVEFORMATEXTENSIBLE, eCapture, eConsole,
            },
            KernelStreaming::WAVE_FORMAT_EXTENSIBLE,
            Multimedia::KSDATAFORMAT_SUBTYPE_IEEE_FLOAT,
        },
        System::Com::{
            CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree,
            CoUninitialize,
        },
    },
    core::HSTRING,
};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct CameraDevice {
    pub id: String,
    pub name: String,
    pub has_microphone: bool,
}

#[derive(Clone, Debug, Serialize)]
pub struct RuntimeStatus {
    pub camera_id: String,
    pub camera_name: String,
    pub capture_status: String,
    pub capture_backend: String,
    pub fps: f64,
    pub width: u32,
    pub height: u32,
    pub encoder: String,
    pub recording_active: bool,
    pub recording_status: String,
    pub recording_file: String,
    pub recording_bitrate: u32,
    pub microphone_status: String,
    pub inference_fps: f64,
    pub tracked_targets: u32,
    pub preview_clients: u32,
    pub audio_clients: u32,
    pub preview_status: String,
    pub preview_error: String,
    pub last_error: String,
}

impl RuntimeStatus {
    pub fn new(settings: &Settings) -> Self {
        Self {
            camera_id: settings.camera_id.clone(),
            camera_name: String::new(),
            capture_status: "initializing".into(),
            capture_backend: "Media Foundation".into(),
            fps: 0.0,
            width: settings.width,
            height: settings.height,
            encoder: "native-h264".into(),
            recording_active: false,
            recording_status: "waiting".into(),
            recording_file: String::new(),
            recording_bitrate: settings.bitrate,
            microphone_status: "initializing".into(),
            inference_fps: 0.0,
            tracked_targets: 0,
            preview_clients: 0,
            audio_clients: 0,
            preview_status: "idle".into(),
            preview_error: String::new(),
            last_error: String::new(),
        }
    }
}

enum RecorderCommand {
    SetMode(RecordingMode),
    SetSchedule(RecordingSchedule),
    SetCaptureSettings(u32, u32, u32, u32, Sender<Result<()>>),
    ListCameras(Sender<Result<Vec<CameraDevice>>>),
    SelectCamera(String, Sender<Result<CameraDevice>>),
    Shutdown,
}

pub type PreviewPublisher = Arc<dyn Fn(Vec<u8>) + Send + Sync + 'static>;
pub type AudioPublisher = Arc<dyn Fn(Vec<u8>) + Send + Sync + 'static>;

struct PreviewReader {
    _capture: MediaCapture,
    reader: MediaFrameReader,
    started: bool,
}

#[derive(Clone)]
pub struct MediaController {
    commands: Sender<RecorderCommand>,
}

impl MediaController {
    pub fn set_mode(&self, mode: RecordingMode) -> Result<()> {
        self.commands.send(RecorderCommand::SetMode(mode))?;
        Ok(())
    }

    pub fn set_schedule(&self, schedule: RecordingSchedule) -> Result<()> {
        self.commands.send(RecorderCommand::SetSchedule(schedule))?;
        Ok(())
    }

    pub fn list_cameras(&self) -> Result<Vec<CameraDevice>> {
        let (sender, receiver) = bounded(1);
        self.commands.send(RecorderCommand::ListCameras(sender))?;
        receiver
            .recv_timeout(Duration::from_secs(3))
            .context("camera enumeration timed out")?
    }

    pub fn select_camera(&self, camera_id: String) -> Result<CameraDevice> {
        let (sender, receiver) = bounded(1);
        self.commands
            .send(RecorderCommand::SelectCamera(camera_id, sender))?;
        receiver
            .recv_timeout(Duration::from_secs(5))
            .context("camera selection timed out")?
    }

    pub fn set_capture_settings(
        &self,
        width: u32,
        height: u32,
        fps: u32,
        bitrate: u32,
    ) -> Result<()> {
        let (sender, receiver) = bounded(1);
        self.commands.send(RecorderCommand::SetCaptureSettings(
            width, height, fps, bitrate, sender,
        ))?;
        receiver
            .recv_timeout(Duration::from_secs(5))
            .context("camera settings update timed out")?
    }
}

pub struct MediaService {
    commands: Sender<RecorderCommand>,
    status: Arc<RwLock<RuntimeStatus>>,
    worker: Option<thread::JoinHandle<()>>,
    audio_shutdown: Arc<AtomicBool>,
    audio_worker: Option<thread::JoinHandle<()>>,
}

impl MediaService {
    pub fn start(
        settings: Settings,
        preview: Option<PreviewPublisher>,
        audio: Option<AudioPublisher>,
    ) -> Result<Self> {
        recover_partials(&settings.recording_root)?;
        let (commands, rx) = bounded(4);
        let status = Arc::new(RwLock::new(RuntimeStatus::new(&settings)));
        let worker_status = status.clone();
        let audio_shutdown = Arc::new(AtomicBool::new(false));
        let audio_stop = audio_shutdown.clone();
        let audio_status = status.clone();
        let audio_worker = thread::Builder::new()
            .name("native-wasapi-capture".into())
            .spawn(move || wasapi_audio_thread(audio_status, audio, audio_stop))?;
        let worker = thread::Builder::new()
            .name("native-media-recorder".into())
            .spawn(move || recorder_thread(settings, rx, worker_status, preview))?;
        Ok(Self {
            commands,
            status,
            worker: Some(worker),
            audio_shutdown,
            audio_worker: Some(audio_worker),
        })
    }

    pub fn status(&self) -> Arc<RwLock<RuntimeStatus>> {
        self.status.clone()
    }

    pub fn controller(&self) -> MediaController {
        MediaController {
            commands: self.commands.clone(),
        }
    }

    pub fn set_mode(&self, mode: RecordingMode) -> Result<()> {
        self.commands.send(RecorderCommand::SetMode(mode))?;
        Ok(())
    }

    pub fn set_schedule(&self, schedule: RecordingSchedule) -> Result<()> {
        self.commands.send(RecorderCommand::SetSchedule(schedule))?;
        Ok(())
    }

    pub fn shutdown(&mut self) {
        self.audio_shutdown.store(true, Ordering::Release);
        let _ = self.commands.send(RecorderCommand::Shutdown);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        if let Some(worker) = self.audio_worker.take() {
            let _ = worker.join();
        }
    }
}

impl Drop for MediaService {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn recorder_thread(
    mut settings: Settings,
    commands: Receiver<RecorderCommand>,
    status: Arc<RwLock<RuntimeStatus>>,
    preview: Option<PreviewPublisher>,
) {
    let apartment = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.ok();
    if let Err(error) = apartment {
        set_error(&status, format!("COM initialization failed: {error}"));
        return;
    }
    let mut capture: Option<MediaCapture> = None;
    let mut recording = None;
    let mut active_paths: Option<(PathBuf, PathBuf)> = None;
    let mut segment_hour = None;
    let mut retry_at = Instant::now();
    let mut frame_reader: Option<PreviewReader> = None;
    let mut last_preview = Instant::now();
    let mut selected_camera_id =
        (!settings.camera_id.is_empty()).then(|| settings.camera_id.clone());

    loop {
        let command_timeout = preview_command_timeout(
            preview.is_some()
                && status
                    .read()
                    .map(|runtime| runtime.preview_clients > 0)
                    .unwrap_or(false),
            settings.fps,
            last_preview.elapsed(),
        );
        match commands.recv_timeout(command_timeout) {
            Ok(RecorderCommand::Shutdown)
            | Err(crossbeam_channel::RecvTimeoutError::Disconnected) => break,
            Ok(RecorderCommand::SetMode(mode)) => settings.recording_mode = mode,
            Ok(RecorderCommand::SetSchedule(schedule)) => settings.recording_schedule = schedule,
            Ok(RecorderCommand::SetCaptureSettings(width, height, fps, bitrate, response)) => {
                finish_recording(&mut recording, &mut active_paths, &status);
                segment_hour = None;
                stop_preview_reader(&mut frame_reader);
                capture = None;
                settings.width = width;
                settings.height = height;
                settings.fps = fps;
                settings.bitrate = bitrate;
                retry_at = Instant::now();
                update(&status, |s| {
                    s.width = width;
                    s.height = height;
                    s.fps = 0.0;
                    s.capture_status = "initializing".into();
                    s.preview_status = "waiting-camera".into();
                    s.preview_error.clear();
                    s.last_error.clear();
                });
                let _ = response.send(Ok(()));
            }
            Ok(RecorderCommand::ListCameras(response)) => {
                let _ = response.send(list_camera_devices());
            }
            Ok(RecorderCommand::SelectCamera(camera_id, response)) => {
                let result = find_camera(&camera_id);
                if let Ok(device) = &result {
                    finish_recording(&mut recording, &mut active_paths, &status);
                    segment_hour = None;
                    stop_preview_reader(&mut frame_reader);
                    capture = None;
                    selected_camera_id = Some(device.id.clone());
                    settings.camera_id = device.id.clone();
                    retry_at = Instant::now();
                    update(&status, |s| {
                        s.camera_id = device.id.clone();
                        s.camera_name = device.name.clone();
                        s.capture_status = "initializing".into();
                        s.preview_status = "waiting-camera".into();
                        s.preview_error.clear();
                        s.last_error.clear();
                    });
                }
                let _ = response.send(result);
            }
            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
        }

        let now = Local::now();
        let schedule_in_window = settings.recording_schedule.contains(
            now.weekday().number_from_monday() as u8,
            now.hour() * 60 + now.minute(),
        );
        let should_record = match settings.recording_mode {
            RecordingMode::Continuous => true,
            RecordingMode::Schedule => schedule_in_window,
            RecordingMode::Off => false,
        };
        let current_hour = now.hour();
        let rotate = segment_hour.is_some_and(|hour| hour != current_hour);

        if (!should_record || rotate) && recording.is_some() {
            finish_recording(&mut recording, &mut active_paths, &status);
            segment_hour = None;
        }
        if !should_record {
            let idle_status = match settings.recording_mode {
                RecordingMode::Schedule => "schedule-waiting",
                _ => "off",
            };
            update(&status, |s| {
                s.recording_active = false;
                s.recording_status = idle_status.into();
            });
            let live_clients = status
                .read()
                .map(|s| s.preview_clients + s.audio_clients)
                .unwrap_or(0);
            if live_clients > 0 && capture.is_none() && Instant::now() >= retry_at {
                if let Err(error) =
                    ensure_capture(&mut capture, selected_camera_id.as_deref(), &status)
                {
                    set_error(&status, format!("camera preview start failed: {error:#}"));
                    retry_at = Instant::now() + Duration::from_secs(2);
                } else {
                    update(&status, |s| {
                        s.capture_status = "running".into();
                        s.microphone_status = "running".into();
                        s.last_error.clear();
                    });
                }
            }
            publish_preview_if_needed(
                &capture,
                &mut frame_reader,
                selected_camera_id.as_deref(),
                settings.width,
                settings.height,
                settings.fps,
                &status,
                preview.as_ref(),
                &mut last_preview,
            );
            continue;
        }
        if recording.is_some() || Instant::now() < retry_at {
            publish_preview_if_needed(
                &capture,
                &mut frame_reader,
                selected_camera_id.as_deref(),
                settings.width,
                settings.height,
                settings.fps,
                &status,
                preview.as_ref(),
                &mut last_preview,
            );
            continue;
        }

        if capture.is_none() {
            if let Err(error) = ensure_capture(&mut capture, selected_camera_id.as_deref(), &status)
            {
                set_error(&status, format!("camera initialization failed: {error:#}"));
                retry_at = Instant::now() + Duration::from_secs(2);
                continue;
            }
        }
        match start_recording(
            &settings,
            &mut capture,
            selected_camera_id.as_deref(),
            &status,
        ) {
            Ok(started) => {
                segment_hour = Some(current_hour);
                active_paths = Some((started.partial.clone(), started.final_path));
                recording = Some(started.recording);
                update(&status, |s| {
                    s.capture_status = "running".into();
                    s.microphone_status = "running".into();
                    s.fps = settings.fps as f64;
                    s.width = started.width;
                    s.height = started.height;
                    s.encoder = started.encoder.into();
                    s.recording_bitrate = started.bitrate;
                    s.recording_active = true;
                    s.recording_status = "recording".into();
                    s.recording_file = started.partial.to_string_lossy().into_owned();
                    s.last_error.clear();
                });
                publish_preview_if_needed(
                    &capture,
                    &mut frame_reader,
                    selected_camera_id.as_deref(),
                    settings.width,
                    settings.height,
                    settings.fps,
                    &status,
                    preview.as_ref(),
                    &mut last_preview,
                );
            }
            Err(error) => {
                set_error(&status, format!("native recorder start failed: {error:#}"));
                retry_at = Instant::now() + Duration::from_secs(2);
                capture = None;
            }
        }
    }
    finish_recording(&mut recording, &mut active_paths, &status);
    stop_preview_reader(&mut frame_reader);
    unsafe { CoUninitialize() };
}

struct WasapiCapture {
    client: IAudioClient,
    capture: IAudioCaptureClient,
    sample_rate: u32,
    channels: usize,
    bits_per_sample: u16,
    float_samples: bool,
}

impl WasapiCapture {
    fn open() -> Result<Self> {
        let enumerator: IMMDeviceEnumerator = unsafe {
            CoCreateInstance(
                &MMDeviceEnumerator,
                None::<&windows::core::IUnknown>,
                CLSCTX_ALL,
            )?
        };
        let device = unsafe { enumerator.GetDefaultAudioEndpoint(eCapture, eConsole)? };
        let client: IAudioClient = unsafe { device.Activate(CLSCTX_ALL, None)? };
        let format = unsafe { client.GetMixFormat()? };
        if format.is_null() {
            anyhow::bail!("WASAPI returned an empty mix format");
        }
        let basic = unsafe { std::ptr::read_unaligned(format) };
        let format_tag = basic.wFormatTag;
        let bits_per_sample = basic.wBitsPerSample;
        let sample_rate = basic.nSamplesPerSec;
        let channels = basic.nChannels;
        let float_samples = if format_tag as u32 == WAVE_FORMAT_EXTENSIBLE {
            let extended =
                unsafe { std::ptr::read_unaligned(format.cast::<WAVEFORMATEXTENSIBLE>()) };
            let subformat = unsafe { std::ptr::addr_of!(extended.SubFormat).read_unaligned() };
            subformat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT
        } else {
            format_tag == 3
        };
        let initialize =
            unsafe { client.Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 1_000_000, 0, format, None) };
        unsafe { CoTaskMemFree(Some(format.cast())) };
        initialize?;
        if !float_samples && !(format_tag as u32 == WAVE_FORMAT_PCM && bits_per_sample == 16) {
            anyhow::bail!(
                "unsupported WASAPI format tag={} bits={}",
                format_tag,
                bits_per_sample
            );
        }
        let capture: IAudioCaptureClient = unsafe { client.GetService()? };
        unsafe { client.Start()? };
        Ok(Self {
            client,
            capture,
            sample_rate,
            channels: channels.max(1) as usize,
            bits_per_sample,
            float_samples,
        })
    }

    fn pump(&self, publisher: &AudioPublisher) -> Result<()> {
        loop {
            let packet_frames = unsafe { self.capture.GetNextPacketSize()? };
            if packet_frames == 0 {
                return Ok(());
            }
            let mut pointer = std::ptr::null_mut();
            let mut frames = 0;
            let mut flags = 0;
            unsafe {
                self.capture
                    .GetBuffer(&mut pointer, &mut frames, &mut flags, None, None)?;
            }
            let converted = if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 {
                vec![0; frames as usize * 16_000 / self.sample_rate as usize * 2]
            } else {
                self.convert(pointer, frames as usize)?
            };
            unsafe { self.capture.ReleaseBuffer(frames)? };
            if !converted.is_empty() {
                publisher(converted);
            }
        }
    }

    fn convert(&self, pointer: *const u8, frames: usize) -> Result<Vec<u8>> {
        if pointer.is_null() || frames == 0 || self.sample_rate == 0 {
            return Ok(Vec::new());
        }
        let output_frames = frames.saturating_mul(16_000) / self.sample_rate as usize;
        let mut output = Vec::with_capacity(output_frames * 2);
        for output_index in 0..output_frames {
            let source_index =
                (output_index.saturating_mul(self.sample_rate as usize) / 16_000).min(frames - 1);
            let mixed = if self.float_samples && self.bits_per_sample == 32 {
                let samples = unsafe {
                    std::slice::from_raw_parts(
                        pointer.cast::<f32>(),
                        frames.saturating_mul(self.channels),
                    )
                };
                samples[source_index * self.channels..(source_index + 1) * self.channels]
                    .iter()
                    .copied()
                    .sum::<f32>()
                    / self.channels as f32
            } else if !self.float_samples && self.bits_per_sample == 16 {
                let samples = unsafe {
                    std::slice::from_raw_parts(
                        pointer.cast::<i16>(),
                        frames.saturating_mul(self.channels),
                    )
                };
                samples[source_index * self.channels..(source_index + 1) * self.channels]
                    .iter()
                    .map(|sample| *sample as f32 / i16::MAX as f32)
                    .sum::<f32>()
                    / self.channels as f32
            } else {
                anyhow::bail!("unsupported WASAPI sample layout");
            };
            let pcm = (mixed.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
            output.extend_from_slice(&pcm.to_le_bytes());
        }
        Ok(output)
    }
}

impl Drop for WasapiCapture {
    fn drop(&mut self) {
        unsafe {
            let _ = self.client.Stop();
        }
    }
}

fn wasapi_audio_thread(
    status: Arc<RwLock<RuntimeStatus>>,
    publisher: Option<AudioPublisher>,
    shutdown: Arc<AtomicBool>,
) {
    if unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }
        .ok()
        .is_err()
    {
        update(&status, |s| s.microphone_status = "error".into());
        return;
    }
    let mut capture: Option<WasapiCapture> = None;
    while !shutdown.load(Ordering::Acquire) {
        let clients = status.read().map(|s| s.audio_clients).unwrap_or(0);
        if clients == 0 || publisher.is_none() {
            capture = None;
            if let Ok(mut current) = status.write()
                && !current.recording_active
            {
                current.microphone_status = "idle".into();
            }
            thread::sleep(Duration::from_millis(100));
            continue;
        }
        if capture.is_none() {
            match WasapiCapture::open() {
                Ok(active) => {
                    capture = Some(active);
                    update(&status, |s| s.microphone_status = "running".into());
                }
                Err(error) => {
                    tracing::warn!(%error, "WASAPI microphone initialization failed");
                    update(&status, |s| s.microphone_status = format!("error: {error}"));
                    thread::sleep(Duration::from_secs(2));
                    continue;
                }
            }
        }
        if let Some(active) = capture.as_ref()
            && let Err(error) = active.pump(publisher.as_ref().expect("publisher checked"))
        {
            tracing::warn!(%error, "WASAPI microphone capture failed");
            update(&status, |s| s.microphone_status = format!("error: {error}"));
            capture = None;
        }
        thread::sleep(Duration::from_millis(5));
    }
    drop(capture);
    unsafe { CoUninitialize() };
}

fn publish_preview_if_needed(
    capture: &Option<MediaCapture>,
    reader: &mut Option<PreviewReader>,
    camera_id: Option<&str>,
    width: u32,
    height: u32,
    fps: u32,
    status: &Arc<RwLock<RuntimeStatus>>,
    publisher: Option<&PreviewPublisher>,
    last_preview: &mut Instant,
) {
    let clients = status.read().map(|s| s.preview_clients).unwrap_or(0);
    if clients == 0 {
        update(status, |s| s.preview_status = "idle".into());
        return;
    }
    let frame_interval = preview_frame_interval(fps);
    if publisher.is_none() || last_preview.elapsed() < frame_interval {
        return;
    }
    *last_preview = Instant::now();
    if reader.is_none() {
        match capture
            .as_ref()
            .context("camera is not initialized")
            .and_then(|_| create_shared_preview_reader(camera_id, width, height))
        {
            Ok(active) => {
                *reader = Some(active);
                update(status, |s| {
                    s.preview_status = "running".into();
                    s.preview_error.clear();
                });
            }
            Err(error) => {
                update(status, |s| {
                    s.preview_status = "error".into();
                    s.preview_error = format!("{error:#}");
                });
                return;
            }
        }
    }
    if let Some(active) = reader.as_mut()
        && !active.started
    {
        match active
            .reader
            .StartAsync()
            .and_then(|operation| operation.join())
        {
            Ok(MediaFrameReaderStartStatus::Success) => {
                active.started = true;
                update(status, |s| {
                    s.preview_status = "running".into();
                    s.preview_error.clear();
                });
            }
            Ok(other) => {
                update(status, |s| {
                    s.preview_status = "error".into();
                    s.preview_error = format!("preview frame reader failed to start: {other:?}");
                });
                return;
            }
            Err(error) => {
                update(status, |s| {
                    s.preview_status = "error".into();
                    s.preview_error = error.to_string();
                });
                return;
            }
        }
    }
    let result = (|| -> Result<Vec<u8>> {
        let frame = reader
            .as_ref()
            .context("preview reader is unavailable")?
            .reader
            .TryAcquireLatestFrame()?;
        let bitmap = frame.VideoMediaFrame()?.SoftwareBitmap()?;
        encode_jpeg(&bitmap)
    })();
    match result {
        Ok(jpeg) => {
            update(status, |s| {
                s.preview_status = "running".into();
                s.preview_error.clear();
            });
            publisher.expect("publisher checked")(jpeg);
        }
        Err(error) => {
            tracing::debug!(%error, "preview frame was unavailable");
            update(status, |s| {
                s.preview_status = "waiting-frame".into();
                s.preview_error = format!("{error:#}");
            });
        }
    }
}

const RECORDER_IDLE_POLL_INTERVAL: Duration = Duration::from_millis(15);

fn preview_frame_interval(fps: u32) -> Duration {
    Duration::from_secs_f64(1.0 / f64::from(fps.clamp(1, 60)))
}

fn preview_command_timeout(
    preview_active: bool,
    fps: u32,
    elapsed_since_preview: Duration,
) -> Duration {
    if !preview_active {
        return RECORDER_IDLE_POLL_INTERVAL;
    }
    preview_frame_interval(fps)
        .saturating_sub(elapsed_since_preview)
        .min(RECORDER_IDLE_POLL_INTERVAL)
}

const PREVIEW_MAX_EDGE: u32 = 1280;

fn preview_dimensions(width: u32, height: u32) -> (u32, u32) {
    let longest_edge = width.max(height);
    if longest_edge <= PREVIEW_MAX_EDGE || longest_edge == 0 {
        return (width, height);
    }
    let scaled_width =
        (u64::from(width) * u64::from(PREVIEW_MAX_EDGE) / u64::from(longest_edge)) as u32;
    let scaled_height =
        (u64::from(height) * u64::from(PREVIEW_MAX_EDGE) / u64::from(longest_edge)) as u32;
    (scaled_width.max(2) & !1, scaled_height.max(2) & !1)
}

fn create_shared_preview_reader(
    camera_id: Option<&str>,
    width: u32,
    height: u32,
) -> Result<PreviewReader> {
    let capture = MediaCapture::new()?;
    let initialization = MediaCaptureInitializationSettings::new()?;
    initialization.SetStreamingCaptureMode(StreamingCaptureMode::Video)?;
    initialization.SetMemoryPreference(MediaCaptureMemoryPreference::Cpu)?;
    initialization.SetSharingMode(MediaCaptureSharingMode::SharedReadOnly)?;
    if let Some((group, _)) = preferred_source_group(camera_id)? {
        initialization.SetSourceGroup(&group)?;
    }
    capture
        .InitializeWithSettingsAsync(&initialization)?
        .join()?;
    let sources = capture.FrameSources()?;
    let mut selected = None;
    for entry in sources {
        let source = entry.Value()?;
        if source.Info()?.SourceKind()? == MediaFrameSourceKind::Color {
            selected = Some(source);
            break;
        }
    }
    let source = selected.context("no color frame source is available")?;
    let (preview_width, preview_height) = preview_dimensions(width, height);
    let reader = capture
        .CreateFrameReaderWithSubtypeAndSizeAsync(
            &source,
            &HSTRING::from("BGRA8"),
            BitmapSize {
                Width: preview_width,
                Height: preview_height,
            },
        )?
        .join()?;
    reader.SetAcquisitionMode(MediaFrameReaderAcquisitionMode::Realtime)?;
    Ok(PreviewReader {
        _capture: capture,
        reader,
        started: false,
    })
}

fn encode_jpeg(bitmap: &windows::Graphics::Imaging::SoftwareBitmap) -> Result<Vec<u8>> {
    let stream = InMemoryRandomAccessStream::new()?;
    let options = BitmapPropertySet::new()?;
    let quality =
        BitmapTypedValue::Create(&PropertyValue::CreateSingle(0.75)?, PropertyType::Single)?;
    options.Insert(&HSTRING::from("ImageQuality"), &quality)?;
    let encoder = BitmapEncoder::CreateWithEncodingOptionsAsync(
        BitmapEncoder::JpegEncoderId()?,
        &stream,
        &options,
    )?
    .join()?;
    encoder.SetSoftwareBitmap(bitmap)?;
    encoder.FlushAsync()?.join()?;
    let size: u32 = stream
        .Size()?
        .try_into()
        .context("JPEG frame is too large")?;
    let input = stream.GetInputStreamAt(0)?;
    let reader = DataReader::CreateDataReader(&input)?;
    let loaded = reader.LoadAsync(size)?.join()?;
    let mut bytes = vec![0; loaded as usize];
    reader.ReadBytes(&mut bytes)?;
    let _ = reader.Close();
    let _ = stream.Close();
    Ok(bytes)
}

struct StartedRecording {
    recording: windows::Media::Capture::LowLagMediaRecording,
    partial: PathBuf,
    final_path: PathBuf,
    width: u32,
    height: u32,
    bitrate: u32,
    encoder: &'static str,
}

fn start_recording(
    settings: &Settings,
    capture: &mut Option<MediaCapture>,
    camera_id: Option<&str>,
    status: &Arc<RwLock<RuntimeStatus>>,
) -> Result<StartedRecording> {
    fs::create_dir_all(&settings.recording_root)?;
    prune_expired_recordings(&settings.recording_root, settings.retention_days)?;
    reclaim_recording_space(&settings.recording_root, 10 * 1024 * 1024 * 1024)?;

    ensure_capture(capture, camera_id, status)?;

    match prepare_recording(
        capture.as_ref().expect("capture initialized"),
        settings,
        settings.width,
        settings.height,
        settings.bitrate,
        VideoEncodingQuality::HD1080p,
        "hardware-h264",
    ) {
        Ok(recording) => Ok(recording),
        Err(hardware_error) => {
            tracing::warn!(%hardware_error, "1080p encoder unavailable; falling back to 720p");
            prepare_recording(
                capture.as_ref().expect("capture initialized"),
                settings,
                1280,
                720,
                2_000_000,
                VideoEncodingQuality::HD720p,
                "software-h264-720p",
            )
            .with_context(|| format!("1080p failed first: {hardware_error:#}"))
        }
    }
}

fn ensure_capture(
    capture: &mut Option<MediaCapture>,
    camera_id: Option<&str>,
    status: &Arc<RwLock<RuntimeStatus>>,
) -> Result<()> {
    if capture.is_some() {
        return Ok(());
    }
    let native = MediaCapture::new()?;
    let initialization = MediaCaptureInitializationSettings::new()?;
    initialization.SetMemoryPreference(MediaCaptureMemoryPreference::Auto)?;
    if let Some((group, device)) = preferred_source_group(camera_id)? {
        initialization.SetStreamingCaptureMode(if device.has_microphone {
            StreamingCaptureMode::AudioAndVideo
        } else {
            StreamingCaptureMode::Video
        })?;
        initialization.SetSourceGroup(&group)?;
        update(status, |s| {
            s.camera_id = device.id.clone();
            s.camera_name = device.name.clone();
        });
    } else {
        anyhow::bail!("no camera is available");
    }
    native
        .InitializeWithSettingsAsync(&initialization)?
        .join()?;
    *capture = Some(native);
    Ok(())
}

fn list_camera_devices() -> Result<Vec<CameraDevice>> {
    Ok(camera_source_groups()?
        .into_iter()
        .map(|(_, device)| device)
        .collect())
}

fn find_camera(camera_id: &str) -> Result<CameraDevice> {
    camera_source_groups()?
        .into_iter()
        .find(|(_, device)| device.id == camera_id)
        .map(|(_, device)| device)
        .with_context(|| format!("camera is no longer available: {camera_id}"))
}

fn preferred_source_group(
    camera_id: Option<&str>,
) -> Result<Option<(MediaFrameSourceGroup, CameraDevice)>> {
    let groups = camera_source_groups()?;
    if let Some(camera_id) = camera_id {
        return groups
            .into_iter()
            .find(|(_, device)| device.id == camera_id)
            .map(Some)
            .with_context(|| format!("camera is no longer available: {camera_id}"));
    }
    Ok(groups.into_iter().next())
}

fn camera_source_groups() -> Result<Vec<(MediaFrameSourceGroup, CameraDevice)>> {
    let groups = MediaFrameSourceGroup::FindAllAsync()?.join()?;
    let mut with_audio = Vec::new();
    let mut color_only = Vec::new();
    for group in groups {
        let mut has_color = false;
        let mut has_audio = false;
        for info in group.SourceInfos()? {
            match info.SourceKind()? {
                MediaFrameSourceKind::Color => has_color = true,
                MediaFrameSourceKind::Audio => has_audio = true,
                _ => {}
            }
        }
        if has_color {
            let device = CameraDevice {
                id: group.Id()?.to_string(),
                name: group.DisplayName()?.to_string(),
                has_microphone: has_audio,
            };
            if has_audio {
                with_audio.push((group, device));
            } else {
                color_only.push((group, device));
            }
        }
    }
    with_audio.extend(color_only);
    Ok(with_audio)
}

fn stop_preview_reader(reader: &mut Option<PreviewReader>) {
    if let Some(active) = reader.take() {
        if active.started {
            let _ = active
                .reader
                .StopAsync()
                .and_then(|operation| operation.join());
        }
        let _ = active.reader.Close();
    }
}

fn prepare_recording(
    capture: &MediaCapture,
    settings: &Settings,
    width: u32,
    height: u32,
    bitrate: u32,
    quality: VideoEncodingQuality,
    encoder: &'static str,
) -> Result<StartedRecording> {
    let (partial, final_path) = next_segment_paths(&settings.recording_root)?;
    fs::File::create(&partial)?;
    let result = (|| -> Result<StartedRecording> {
        let profile = MediaEncodingProfile::CreateMp4(quality)?;
        if let Ok(video) = profile.Video() {
            video.SetWidth(width)?;
            video.SetHeight(height)?;
            video.SetBitrate(bitrate)?;
            let ratio = video.FrameRate()?;
            ratio.SetNumerator(30)?;
            ratio.SetDenominator(1)?;
        }
        if let Ok(audio) = profile.Audio() {
            audio.SetSampleRate(48_000)?;
            audio.SetChannelCount(1)?;
            audio.SetBitrate(96_000)?;
        }
        let file =
            StorageFile::GetFileFromPathAsync(&HSTRING::from(partial.to_string_lossy().as_ref()))?
                .join()?;
        let recorder = capture.PrepareLowLagRecordToStorageFileAsync(&profile, &file)?;
        let recorder = recorder.join()?;
        recorder.StartAsync()?.join()?;
        Ok(StartedRecording {
            recording: recorder,
            partial: partial.clone(),
            final_path,
            width,
            height,
            bitrate,
            encoder,
        })
    })();
    if result.is_err() {
        let _ = fs::remove_file(&partial);
    }
    result
}

fn prune_expired_recordings(root: &Path, retention_days: u32) -> Result<()> {
    if retention_days == 0 || !root.exists() {
        return Ok(());
    }
    let cutoff = std::time::SystemTime::now()
        .checked_sub(Duration::from_secs(retention_days as u64 * 86_400))
        .context("invalid retention window")?;
    for day in fs::read_dir(root)? {
        let day = day?.path();
        if !day.is_dir() || day.file_name().and_then(|v| v.to_str()) == Some("recovery") {
            continue;
        }
        for item in fs::read_dir(&day)? {
            let path = item?.path();
            if path.extension().and_then(|v| v.to_str()) == Some("mp4")
                && path.metadata()?.modified()? < cutoff
            {
                let _ = fs::remove_file(path);
            }
        }
        if fs::read_dir(&day)?.next().is_none() {
            let _ = fs::remove_dir(day);
        }
    }
    Ok(())
}

fn reclaim_recording_space(root: &Path, target_free: u64) -> Result<()> {
    loop {
        if available_space(root)? >= target_free {
            return Ok(());
        }
        let Some(oldest) = oldest_recording_file(root)? else {
            anyhow::bail!(
                "recording disk has less than 10 GB available and no recordings left to overwrite"
            );
        };
        tracing::info!(path = %oldest.display(), "loop recording: overwriting oldest recording to free space");
        fs::remove_file(&oldest)?;
        if let Some(day) = oldest.parent() {
            if fs::read_dir(day)?.next().is_none() {
                let _ = fs::remove_dir(day);
            }
        }
    }
}

fn oldest_recording_file(root: &Path) -> Result<Option<PathBuf>> {
    if !root.exists() {
        return Ok(None);
    }
    let mut oldest: Option<(std::time::SystemTime, PathBuf)> = None;
    for day in fs::read_dir(root)? {
        let day = day?.path();
        if !day.is_dir() || day.file_name().and_then(|v| v.to_str()) == Some("recovery") {
            continue;
        }
        for item in fs::read_dir(&day)? {
            let path = item?.path();
            if path.extension().and_then(|v| v.to_str()) == Some("mp4") {
                let modified = path.metadata()?.modified()?;
                if oldest.as_ref().map_or(true, |(time, _)| modified < *time) {
                    oldest = Some((modified, path));
                }
            }
        }
    }
    Ok(oldest.map(|(_, path)| path))
}

fn finish_recording(
    recording: &mut Option<windows::Media::Capture::LowLagMediaRecording>,
    paths: &mut Option<(PathBuf, PathBuf)>,
    status: &Arc<RwLock<RuntimeStatus>>,
) {
    if let Some(native) = recording.take() {
        let result = (|| -> windows::core::Result<()> {
            native.StopAsync()?.join()?;
            native.FinishAsync()?.join()?;
            Ok(())
        })();
        if let Err(error) = result {
            set_error(status, format!("finalizing recording failed: {error}"));
        }
    }
    if let Some((partial, final_path)) = paths.take() {
        if partial.exists() {
            if let Err(error) = fs::rename(&partial, &final_path) {
                set_error(status, format!("renaming recording failed: {error}"));
            }
        }
    }
    update(status, |s| {
        s.recording_active = false;
        s.recording_file.clear();
        if s.last_error.is_empty() {
            s.recording_status = "waiting".into();
        }
    });
}

fn next_segment_paths(root: &Path) -> Result<(PathBuf, PathBuf)> {
    let now = Local::now();
    let directory = root.join(format!(
        "{:04}-{:02}-{:02}",
        now.year(),
        now.month(),
        now.day()
    ));
    fs::create_dir_all(&directory)?;
    let stem = format!(
        "{:04}-{:02}-{:02}_{:02}-00-00",
        now.year(),
        now.month(),
        now.day(),
        now.hour()
    );
    for suffix in 0..100 {
        let stem = if suffix == 0 {
            stem.clone()
        } else {
            format!("{stem}_{suffix:02}")
        };
        let final_path = directory.join(format!("{stem}.mp4"));
        let partial = directory.join(format!("{stem}.partial.mp4"));
        if !final_path.exists() && !partial.exists() {
            return Ok((partial, final_path));
        }
    }
    anyhow::bail!("too many recording collisions for current hour")
}

fn recover_partials(root: &Path) -> Result<()> {
    if !root.exists() {
        return Ok(());
    }
    let recovery = root.join("recovery");
    for day in fs::read_dir(root)? {
        let day = day?.path();
        if !day.is_dir() || day == recovery {
            continue;
        }
        for item in fs::read_dir(day)? {
            let path = item?.path();
            if path
                .file_name()
                .and_then(|v| v.to_str())
                .is_some_and(|v| v.ends_with(".partial.mp4"))
            {
                fs::create_dir_all(&recovery)?;
                let destination =
                    recovery.join(path.file_name().context("partial has no filename")?);
                let _ = fs::rename(path, destination);
            }
        }
    }
    Ok(())
}

fn update(status: &Arc<RwLock<RuntimeStatus>>, apply: impl FnOnce(&mut RuntimeStatus)) {
    if let Ok(mut status) = status.write() {
        apply(&mut status);
    }
}

fn set_error(status: &Arc<RwLock<RuntimeStatus>>, message: String) {
    tracing::error!("{message}");
    update(status, |s| {
        s.capture_status = "error".into();
        s.recording_status = "error".into();
        s.recording_active = false;
        s.last_error = message;
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runtime_starts_with_ai_disabled() {
        let status = RuntimeStatus::new(&Settings::default());
        assert_eq!(status.inference_fps, 0.0);
        assert_eq!(status.tracked_targets, 0);
        assert_eq!(status.capture_backend, "Media Foundation");
    }

    #[test]
    fn preview_wait_tracks_the_next_frame_deadline() {
        assert_eq!(
            preview_command_timeout(false, 30, Duration::ZERO),
            RECORDER_IDLE_POLL_INTERVAL
        );
        assert_eq!(
            preview_command_timeout(true, 30, Duration::from_millis(10)),
            RECORDER_IDLE_POLL_INTERVAL
        );

        let nearly_due = preview_command_timeout(true, 30, Duration::from_millis(30));
        assert!(nearly_due > Duration::ZERO);
        assert!(nearly_due < Duration::from_millis(4));
        assert_eq!(
            preview_command_timeout(true, 30, Duration::from_millis(34)),
            Duration::ZERO
        );
    }

    #[test]
    fn preview_dimensions_cap_only_the_preview_stream() {
        assert_eq!(preview_dimensions(1920, 1080), (1280, 720));
        assert_eq!(preview_dimensions(3840, 2160), (1280, 720));
        assert_eq!(preview_dimensions(1280, 720), (1280, 720));
        assert_eq!(preview_dimensions(640, 480), (640, 480));
    }

    #[test]
    fn segment_names_do_not_overwrite_existing_files() {
        let root = tempfile::tempdir().unwrap();
        let (first_partial, first_final) = next_segment_paths(root.path()).unwrap();
        fs::write(&first_final, b"existing").unwrap();
        let (second_partial, second_final) = next_segment_paths(root.path()).unwrap();
        assert_ne!(first_final, second_final);
        assert_ne!(first_partial, second_partial);
        assert!(
            second_final
                .file_stem()
                .unwrap()
                .to_string_lossy()
                .ends_with("_01")
        );
    }
}
