from __future__ import annotations

import queue
import json
import shutil
import subprocess
import threading
import time
import wave
from collections import deque
from collections.abc import Generator
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from app.config import settings
from app.core.detector import PersonDetector
from app.core.entities import FramePacket, InferencePacket
from app.core.face import PersonEmbeddingEngine, ReIDRegistry, TemporalIdentityMemory
from app.core.tracker import MultiObjectTracker
from app.storage.repository import PersonEventRepository


_chinese_font_cache = {}
def _get_chinese_font(size: int):
    if size in _chinese_font_cache:
        return _chinese_font_cache[size]
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", size)
    except Exception:
        font = ImageFont.load_default()
    _chinese_font_cache[size] = font
    return font


@dataclass(slots=True)
class RuntimeStats:
    fps: float = 0.0
    avg_latency_ms: float = 0.0
    tracked_targets: int = 0
    gpu_utilization: float = 0.0
    capture_status: str = "initializing"
    capture_backend: str = "-"


@dataclass(slots=True)
class PresenceState:
    visit_id: int
    person_id: str
    first_seen_ms: int
    last_seen_ms: int


@dataclass(slots=True)
class PendingPresenceState:
    person_id: str
    first_seen_ms: int
    last_seen_ms: int
    seen_frames: int = 1


@dataclass(slots=True)
class RecentlyClosedVisit:
    visit_id: int
    left_ms: int


@dataclass(slots=True)
class RecordingItem:
    day: str
    relative_path: str
    filename: str
    started_at: str
    size_bytes: int
    modified_at: str


class VideoAnalyticsPipeline:
    _recording_max_width = 1920
    _recording_max_height = 1080

    def __init__(
        self,
        repository: PersonEventRepository,
        identity_store: object | None = None,
        stream_source: str | None = None,
        audio_streamer: object | None = None,
    ) -> None:
        self.stream_source = stream_source if stream_source is not None else settings.stream_source
        self.repository = repository
        self.identity_store = identity_store if identity_store is not None else repository
        self.audio_streamer = audio_streamer
        self.detector = PersonDetector()
        self.tracker = MultiObjectTracker()
        self.face_engine = PersonEmbeddingEngine()
        self.reid = ReIDRegistry()
        self.identity_memory = TemporalIdentityMemory()
        self.frame_queue: queue.Queue[FramePacket] = queue.Queue(maxsize=settings.frame_queue_size)
        self.result_queue: queue.Queue[InferencePacket] = queue.Queue(maxsize=settings.result_queue_size)
        self._stop_event = threading.Event()
        self._capture_restart_event = threading.Event()
        self._capture_restart_complete_event = threading.Event()
        self._capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
        self._inference_thread = threading.Thread(target=self._inference_loop, daemon=True)
        self._record_thread = threading.Thread(target=self._record_loop, daemon=True)
        self._video_record_thread = threading.Thread(target=self._video_record_loop, daemon=True)
        self._audio_record_thread = threading.Thread(target=self._audio_record_loop, daemon=True)
        self._mux_thread = threading.Thread(target=self._mux_loop, daemon=True)
        self._latest_frame: bytes = b""
        self._has_inference_frame: bool = False
        self._presence_by_track_id: dict[int, PresenceState] = {}
        self._pending_by_track_id: dict[int, PendingPresenceState] = {}
        self._recently_closed_by_person_id: dict[str, RecentlyClosedVisit] = {}
        self._video_queue: queue.Queue[FramePacket] = queue.Queue(maxsize=2)
        self._mux_queue: queue.Queue[tuple[Path, Path, Path | None]] = queue.Queue(maxsize=8)
        self._leave_timeout_ms = 1500
        self._visit_create_min_seen_frames = max(1, settings.visit_create_min_seen_frames)
        self._visit_create_min_duration_ms = max(0, settings.visit_create_min_duration_ms)
        self._visit_merge_window_ms = max(0, settings.visit_merge_window_ms)
        self._visit_discard_under_seconds = max(0.0, settings.visit_discard_under_seconds)
        self._capture_error = ""
        self._camera_backends = self._build_camera_backends()
        self._backend_cursor = 0
        self.stats = RuntimeStats()
        # Shared overlay data: updated by inference, read by capture for rendering
        self._overlay_lock = threading.Lock()
        self._overlay_tracks: list = []
        self._overlay_notes: dict = {}
        # Cached PIL-rendered overlay (only updated when inference produces new results)
        self._cached_pil_overlay: bytes | None = None
        self._cached_overlay_version = 0
        self._display_overlay_version = -1
        self._template_persist_interval_seconds = max(1, settings.reid_persist_interval_seconds)
        self._last_template_persist_ts = 0.0
        self._template_persist_lock = threading.Lock()
        self._actual_width: int = 0
        self._actual_height: int = 0
        self._client_count = 0
        self._client_lock = threading.Lock()
        self._idle_timer: threading.Timer | None = None
        self._idle_timeout_seconds = 15
        self._recording_settings_path = Path("data/recording_settings.json")
        self._recording_mode = self._load_recording_mode()
        self._local_recording_enabled = self._recording_mode == "continuous"
        self._recording_active = self._local_recording_enabled
        self._recording_triggered_by = "continuous" if self._recording_active else "none"
        self._auto_stop_deadline: float | None = None
        self._stable_presence_count = 0
        self._recording_state_lock = threading.Lock()
        self._preroll_seconds = 3
        self._auto_stop_delay_seconds = 10
        self._preroll_frames: deque[tuple[int, bytes]] = deque()
        self._preroll_pending = False
        self._local_recording_output_dir = Path(settings.local_recording_output_dir)
        self._local_recording_retention_days = max(1, settings.local_recording_retention_days)
        self._local_recording_segment_minutes = max(1, settings.local_recording_segment_minutes)
        self._local_recording_status = "waiting" if self._local_recording_enabled else "disabled"
        self._local_recording_current_file = ""
        self._video_writer: cv2.VideoWriter | None = None
        self._video_writer_segment_start: datetime | None = None
        self._video_writer_path: Path | None = None
        self._video_temp_path: Path | None = None
        self._video_writer_fps = 0.0
        self._video_timeline_start_ms: int | None = None
        self._video_frames_written = 0
        self._video_last_frame: np.ndarray | None = None
        self._audio_wave_file: wave.Wave_write | None = None
        self._audio_temp_path: Path | None = None
        self._audio_subscriber_id: int | None = None
        self._audio_subscriber_queue: queue.Queue[bytes] | None = None
        self._ffmpeg_executable: str | None = None
        self._recording_lock = threading.Lock()

    def _load_recording_mode(self) -> str:
        try:
            payload = json.loads(self._recording_settings_path.read_text(encoding="utf-8"))
            mode = payload.get("mode")
            return mode if mode in {"off", "auto", "continuous"} else "off"
        except Exception:
            return "off"

    def _persist_recording_mode(self) -> None:
        self._recording_settings_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = self._recording_settings_path.with_suffix(".tmp")
        temp_path.write_text(json.dumps({"mode": self._recording_mode}, ensure_ascii=False), encoding="utf-8")
        temp_path.replace(self._recording_settings_path)

    def start(self) -> None:
        if self.is_running():
            return
        self._publish_placeholder_frame("正在启动视频服务…")
        self._has_inference_frame = False
        self._stop_event.clear()
        self._load_identity_templates()
        self._last_template_persist_ts = time.monotonic()
        if not self._capture_thread.is_alive():
            self._capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
            self._capture_thread.start()
        if not self._inference_thread.is_alive():
            self._inference_thread = threading.Thread(target=self._inference_loop, daemon=True)
            self._inference_thread.start()
        if not self._record_thread.is_alive():
            self._record_thread = threading.Thread(target=self._record_loop, daemon=True)
            self._record_thread.start()
        if not self._video_record_thread.is_alive():
            self._video_record_thread = threading.Thread(target=self._video_record_loop, daemon=True)
            self._video_record_thread.start()
        if not self._audio_record_thread.is_alive():
            self._audio_record_thread = threading.Thread(target=self._audio_record_loop, daemon=True)
            self._audio_record_thread.start()
        if not self._mux_thread.is_alive():
            self._mux_thread = threading.Thread(target=self._mux_loop, daemon=True)
            self._mux_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._unsubscribe_audio()
        for thread in (
            self._capture_thread,
            self._inference_thread,
            self._record_thread,
            self._video_record_thread,
            self._audio_record_thread,
            self._mux_thread,
        ):
            if thread.is_alive():
                thread.join(timeout=2)
        with self._recording_lock:
            self._close_audio_writer(finalize=True)
            self._close_video_writer()
        self._persist_identity_templates(force=True)

    def acquire(self) -> None:
        with self._client_lock:
            if self._idle_timer is not None:
                self._idle_timer.cancel()
                self._idle_timer = None
            self._client_count += 1
            if self._client_count == 1:
                self.start()

    def release(self) -> None:
        with self._client_lock:
            self._client_count = max(0, self._client_count - 1)
            if self._client_count == 0 and not self._should_keep_running_without_clients():
                self._idle_timer = threading.Timer(self._idle_timeout_seconds, self._idle_shutdown)
                self._idle_timer.daemon = True
                self._idle_timer.start()

    def _idle_shutdown(self) -> None:
        with self._client_lock:
            if self._client_count == 0 and not self._should_keep_running_without_clients():
                self.stop()
                self._publish_placeholder_frame("等待客户端连接...")

    @property
    def latest_frame(self) -> bytes:
        return self._latest_frame

    def generate_mjpeg(self) -> Generator[bytes, None, None]:
        while not self._stop_event.is_set():
            if self._latest_frame:
                yield (
                    b"--frame\r\n"
                    b"Content-Type: image/jpeg\r\n\r\n" + self._latest_frame + b"\r\n"
                )
            time.sleep(0.03)

    def apply_camera_settings(self, width: int, height: int, fps: int) -> dict:
        """Apply camera settings by reopening capture without restarting the pipeline."""
        settings.camera_width = width
        settings.camera_height = height
        settings.target_fps = fps
        if self.is_running():
            self._actual_width = 0
            self._actual_height = 0
            self.stats.capture_status = "switching"
            self._publish_placeholder_frame("正在切换分辨率...")
            self._drain_queue(self.frame_queue)
            self._capture_restart_complete_event.clear()
            self._capture_restart_event.set()
            self._capture_restart_complete_event.wait(timeout=5.0)
        return self.get_capture_info()

    def set_local_recording_enabled(self, enabled: bool) -> dict:
        return self.set_recording_mode("continuous" if enabled else "off")

    def set_recording_mode(self, mode: str) -> dict:
        if mode not in {"off", "auto", "continuous"}:
            raise ValueError("invalid recording mode")
        with self._recording_state_lock:
            self._recording_mode = mode
            self._auto_stop_deadline = None
            if mode == "continuous":
                self._recording_active = True
                self._recording_triggered_by = "continuous"
                self._local_recording_enabled = True
                self._local_recording_status = "waiting"
            elif mode == "auto":
                self._recording_active = self._stable_presence_count > 0
                self._recording_triggered_by = "person" if self._recording_active else "none"
                self._local_recording_enabled = self._recording_active
                self._preroll_pending = self._recording_active
                self._local_recording_status = "waiting" if not self._recording_active else "starting"
            else:
                self._recording_active = False
                self._recording_triggered_by = "none"
                self._local_recording_enabled = False
                self._local_recording_status = "disabled"
        settings.local_recording_enabled = mode == "continuous"
        self._persist_recording_mode()
        with self._client_lock:
            if self._idle_timer is not None:
                self._idle_timer.cancel()
                self._idle_timer = None
            should_stop = mode == "off" and self._client_count == 0 and not settings.pipeline_run_without_clients
        if mode != "off":
            self.start()
        else:
            with self._recording_lock:
                self._close_audio_writer(finalize=True)
                self._close_video_writer()
            self._unsubscribe_audio()
            self._drain_queue(self._video_queue)
            if should_stop:
                self.stop()
                self._publish_placeholder_frame("等待客户端连接...")
        return self.get_capture_info()

    def get_capture_info(self) -> dict:
        return {
            "requested_width": settings.camera_width,
            "requested_height": settings.camera_height,
            "actual_width": self._actual_width,
            "actual_height": self._actual_height,
            "mjpeg_quality": settings.mjpeg_quality,
            "target_fps": settings.target_fps,
            "capture_status": self.stats.capture_status,
            "capture_backend": self.stats.capture_backend,
            "local_recording_enabled": self._local_recording_enabled,
            "recording_mode": self._recording_mode,
            "recording_active": self._recording_active,
            "recording_triggered_by": self._recording_triggered_by,
            "auto_stop_remaining_ms": max(0, int((self._auto_stop_deadline - time.monotonic()) * 1000)) if self._auto_stop_deadline else 0,
            "local_recording_status": self._local_recording_status,
            "local_recording_output_dir": str(self._local_recording_output_dir),
            "local_recording_retention_days": self._local_recording_retention_days,
            "local_recording_segment_minutes": self._local_recording_segment_minutes,
            "local_recording_current_file": self._local_recording_current_file,
        }

    def is_running(self) -> bool:
        return (
            not self._stop_event.is_set()
            and any(
                thread.is_alive()
                for thread in (
                    self._capture_thread,
                    self._inference_thread,
                    self._record_thread,
                )
            )
        )

    def should_run_in_background(self) -> bool:
        return self._should_keep_running_without_clients()

    def _should_keep_running_without_clients(self) -> bool:
        return settings.pipeline_run_without_clients or self._recording_mode != "off"

    @staticmethod
    def _drain_queue(q: queue.Queue) -> None:
        while True:
            try:
                q.get_nowait()
            except queue.Empty:
                return

    @staticmethod
    def _segment_start(ts_ms: int, segment_minutes: int) -> datetime:
        dt = datetime.fromtimestamp(ts_ms / 1000)
        minute = (dt.minute // segment_minutes) * segment_minutes
        return dt.replace(minute=minute, second=0, microsecond=0)

    def _capture_loop(self) -> None:
        cap: cv2.VideoCapture | None = None
        failed_reads = 0
        corrupted_reads = 0
        frame_id = 0
        awaiting_restart_frame = False
        display_latency_ms = 0.0
        while not self._stop_event.is_set():
            if self._capture_restart_event.is_set():
                if cap is not None:
                    cap.release()
                cap = None
                self._capture_restart_event.clear()
                awaiting_restart_frame = True
                failed_reads = 0
                corrupted_reads = 0

            loop_start = time.monotonic()
            target_interval = 1.0 / max(1, settings.target_fps)
            if cap is None or not cap.isOpened():
                cap = self._open_capture()
                if not cap.isOpened():
                    self.stats.capture_status = "disconnected"
                    self._capture_error = "无法打开摄像头/视频流"
                    self._publish_placeholder_frame(self._capture_error)
                    time.sleep(0.5)
                    continue
            ok, frame = cap.read()
            if not ok:
                failed_reads += 1
                self.stats.capture_status = "disconnected"
                self._capture_error = "读取视频帧失败"
                self._publish_placeholder_frame(self._capture_error)
                if failed_reads >= 10:
                    cap.release()
                    self._switch_camera_backend()
                    cap = None
                    failed_reads = 0
                time.sleep(0.1)
                continue
            failed_reads = 0
            if self._is_corrupted_frame(frame):
                corrupted_reads += 1
                self.stats.capture_status = "degraded"
                self._capture_error = "检测到异常图像，正在切换采集后端"
                self._publish_placeholder_frame(self._capture_error)
                if corrupted_reads >= 5:
                    cap.release()
                    self._switch_camera_backend()
                    cap = None
                    corrupted_reads = 0
                time.sleep(0.05)
                continue
            corrupted_reads = 0
            self.stats.capture_status = "running"
            self._capture_error = ""
            h, w = frame.shape[:2]
            self._actual_width = w
            self._actual_height = h
            if awaiting_restart_frame:
                awaiting_restart_frame = False
                self._capture_restart_complete_event.set()
            
            ts_ms = int(time.time() * 1000)
            with self._overlay_lock:
                cur_tracks = list(self._overlay_tracks)
                cur_notes = dict(self._overlay_notes)
            rendered = self._render_fast(frame, cur_tracks, ts_ms, cur_notes)
            ok_enc, buf = cv2.imencode(".jpg", rendered, [int(cv2.IMWRITE_JPEG_QUALITY), settings.mjpeg_quality])
            if ok_enc:
                self._latest_frame = bytes(buf)
            
            # The dashboard FPS represents the configured output rate.  Capture
            # throughput can be lower on slow hardware, but must not make the
            # selected camera setting appear to have changed by itself.
            self.stats.fps = float(settings.target_fps)

            # Report the latency of the frame users actually see: capture,
            # overlay rendering and JPEG encoding.  Inference runs
            # asynchronously, so its queue age is not display latency and can
            # grow very large during model warm-up.
            frame_latency_ms = (time.monotonic() - loop_start) * 1000.0
            if display_latency_ms <= 0.0:
                display_latency_ms = frame_latency_ms
            else:
                display_latency_ms = 0.8 * display_latency_ms + 0.2 * frame_latency_ms
            self.stats.avg_latency_ms = display_latency_ms
            
            packet = FramePacket(frame_id=frame_id, ts_ms=ts_ms, frame=frame)
            self._put_latest(self.frame_queue, packet)
            recording_frame = self._prepare_recording_frame(rendered)
            self._remember_preroll_frame(ts_ms, recording_frame)
            self._put_latest(
                self._video_queue,
                FramePacket(frame_id=frame_id, ts_ms=ts_ms, frame=recording_frame),
            )
            frame_id += 1

            elapsed = time.monotonic() - loop_start
            sleep_time = target_interval - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)
        if cap is not None:
            cap.release()

    def _remember_preroll_frame(self, ts_ms: int, frame: np.ndarray) -> None:
        if self._recording_mode != "auto":
            return
        ok, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
        if not ok:
            return
        with self._recording_state_lock:
            self._preroll_frames.append((ts_ms, bytes(encoded)))
            cutoff = ts_ms - self._preroll_seconds * 1000
            while self._preroll_frames and self._preroll_frames[0][0] < cutoff:
                self._preroll_frames.popleft()

    def _inference_loop(self) -> None:
        prev_ts = time.time()
        frame_counter = 0
        cached_notes: dict = {}
        cached_rois: list[dict] = []
        while not self._stop_event.is_set():
            try:
                packet = self.frame_queue.get(timeout=0.2)
            except queue.Empty:
                continue
            try:
                if frame_counter % 30 == 0:
                    cached_rois = self.repository.list_rois()
                detections = self.detector.detect(packet.frame, rois=cached_rois, ts_ms=packet.ts_ms)
                tracks = self.tracker.update(detections, packet.ts_ms)
                active_track_ids = {track.track_id for track in tracks}
                
                # Feature extraction only every 5 frames or for new tracks
                do_reid = (frame_counter % 5 == 0)
                for track in tracks:
                    if do_reid or track.person_id is None:
                        feature = self.face_engine.embed_128d(packet.frame, track.bbox)
                        if feature is not None:
                            track.feature = feature
                            if track.person_id is None:
                                recent_person_id = self.identity_memory.match(
                                    track_id=track.track_id,
                                    bbox=track.bbox,
                                    feature=feature,
                                    ts_ms=packet.ts_ms,
                                    active_track_ids=active_track_ids,
                                )
                                if recent_person_id is not None:
                                    track.person_id = recent_person_id
                                    self.reid.update_feature(recent_person_id, feature)
                                else:
                                    track.person_id = self.reid.resolve_person_id(feature)
                            else:
                                self.reid.update_feature(track.person_id, feature)
                    if track.person_id and track.feature is not None:
                        self.identity_memory.remember(
                            track_id=track.track_id,
                            person_id=track.person_id,
                            bbox=track.bbox,
                            feature=track.feature,
                            ts_ms=packet.ts_ms,
                        )
                
                # Refresh notes cache every 30 frames
                if frame_counter % 30 == 0:
                    cached_notes = {}
                    for track in tracks:
                        state = self._presence_by_track_id.get(track.track_id)
                        if state:
                            note = self.repository.get_visit_note(state.visit_id)
                            if note:
                                cached_notes[track.track_id] = note
                else:
                    # Just update for new tracks
                    for track in tracks:
                        if track.track_id not in cached_notes:
                            state = self._presence_by_track_id.get(track.track_id)
                            if state:
                                note = self.repository.get_visit_note(state.visit_id)
                                if note:
                                    cached_notes[track.track_id] = note
                
                notes = {tid: n for tid, n in cached_notes.items()}
                frame_counter += 1
                self._persist_identity_templates()
                
                # Update shared overlay data for capture thread rendering
                with self._overlay_lock:
                    self._overlay_tracks = list(tracks)
                    self._overlay_notes = dict(notes)
                    self._cached_overlay_version += 1
                
                encoded = b""
                output = InferencePacket(
                    frame_id=packet.frame_id,
                    ts_ms=packet.ts_ms,
                    tracks=tracks,
                    encoded_frame=encoded,
                )
                self._put_latest(self.result_queue, output)
                now = time.time()
                dt = now - prev_ts
                prev_ts = now
                self.stats.tracked_targets = len(tracks)
                self.stats.gpu_utilization = min(
                    settings.gpu_max_utilization,
                    30.0 + len(tracks) * 0.5 + self.stats.fps * 0.2,
                )
            except Exception as exc:
                self._publish_placeholder_frame(f"分析异常: {exc!s}"[:240])

    def _load_identity_templates(self) -> None:
        templates = self.identity_store.load_identity_templates()
        self.reid.load_templates(templates)

    def _persist_identity_templates(self, force: bool = False) -> None:
        with self._template_persist_lock:
            dirty_person_ids = self.reid.get_dirty_person_ids()
            if not dirty_person_ids:
                return
            now = time.monotonic()
            if not force and now - self._last_template_persist_ts < self._template_persist_interval_seconds:
                return
            payload = self.reid.snapshot_templates(dirty_person_ids)
            if not payload:
                self.reid.mark_persisted(dirty_person_ids)
                self._last_template_persist_ts = now
                return
            self.identity_store.save_identity_templates(payload)
            self.reid.mark_persisted(set(payload))
            self._last_template_persist_ts = now

    def _video_record_loop(self) -> None:
        while not self._stop_event.is_set():
            self._refresh_auto_recording_state()
            try:
                packet = self._video_queue.get(timeout=0.2)
            except queue.Empty:
                if not self._local_recording_enabled:
                    with self._recording_lock:
                        self._close_audio_writer(finalize=True)
                        self._close_video_writer()
                continue
            if not self._local_recording_enabled:
                with self._recording_lock:
                    self._close_audio_writer(finalize=True)
                    self._close_video_writer()
                continue
            try:
                with self._recording_lock:
                    for preroll_packet in self._take_preroll_packets():
                        self._write_video_packet(preroll_packet)
                    if not self._write_video_packet(packet):
                        continue
                self._local_recording_status = "recording"
            except Exception as exc:
                self._local_recording_status = f"error: {exc!s}"[:120]
                with self._recording_lock:
                    self._close_audio_writer(finalize=True)
                    self._close_video_writer()
                time.sleep(0.2)
        with self._recording_lock:
            self._close_audio_writer(finalize=True)
            self._close_video_writer()

    def _take_preroll_packets(self) -> list[FramePacket]:
        with self._recording_state_lock:
            if not self._preroll_pending:
                return []
            encoded_frames = list(self._preroll_frames)
            self._preroll_pending = False
        packets: list[FramePacket] = []
        for ts_ms, encoded in encoded_frames:
            frame = cv2.imdecode(np.frombuffer(encoded, dtype=np.uint8), cv2.IMREAD_COLOR)
            if frame is not None:
                packets.append(FramePacket(frame_id=-1, ts_ms=ts_ms, frame=frame))
        return packets

    def _audio_record_loop(self) -> None:
        while not self._stop_event.is_set():
            if not self._local_recording_enabled:
                self._unsubscribe_audio()
                time.sleep(0.2)
                continue
            if not self._ensure_audio_subscription():
                time.sleep(1.0)
                continue
            audio_queue = self._audio_subscriber_queue
            if audio_queue is None:
                time.sleep(0.2)
                continue
            try:
                chunk = audio_queue.get(timeout=0.2)
            except queue.Empty:
                continue
            with self._recording_lock:
                if self._audio_wave_file is not None:
                    self._audio_wave_file.writeframes(chunk)
        self._unsubscribe_audio()

    def _mux_loop(self) -> None:
        while not self._stop_event.is_set() or not self._mux_queue.empty():
            try:
                final_path, video_temp_path, audio_temp_path = self._mux_queue.get(timeout=0.2)
            except queue.Empty:
                continue
            self._finalize_segment(final_path, video_temp_path, audio_temp_path)

    def _ensure_video_writer(self, packet: FramePacket) -> cv2.VideoWriter | None:
        packet_time = datetime.fromtimestamp(packet.ts_ms / 1000.0)
        if self._recording_mode == "auto" and self._video_writer_segment_start is not None:
            segment_end_ms = int(self._video_writer_segment_start.timestamp() * 1000) + self._local_recording_segment_minutes * 60_000
            if self._video_writer is not None and self._video_temp_path is not None and packet.ts_ms < segment_end_ms:
                return self._video_writer
        segment_start = packet_time if self._recording_mode == "auto" else self._segment_start(packet.ts_ms, self._local_recording_segment_minutes)
        frame_height, frame_width = packet.frame.shape[:2]
        if (
            self._video_writer is not None
            and self._video_writer_segment_start == segment_start
            and self._video_temp_path is not None
        ):
            return self._video_writer
        self._close_audio_writer(finalize=True)
        self._close_video_writer()
        self._cleanup_expired_recordings(now=segment_start)
        segment_dir = self._segment_directory(segment_start)
        segment_dir.mkdir(parents=True, exist_ok=True)
        stem = self._segment_basename(segment_start)
        fps = float(max(1, settings.target_fps))
        writer, path = self._create_video_writer(
            stem=f"{stem}.video",
            output_dir=segment_dir,
            frame_width=frame_width,
            frame_height=frame_height,
            fps=fps,
        )
        if writer is None or path is None:
            self._local_recording_status = "writer_unavailable"
            return None
        self._open_audio_writer(segment_start)
        self._video_writer = writer
        self._video_writer_fps = fps
        self._video_timeline_start_ms = packet.ts_ms
        self._video_frames_written = 0
        self._video_last_frame = None
        self._video_writer_segment_start = segment_start
        self._video_temp_path = path
        self._video_writer_path = self._segment_output_path(segment_start)
        self._local_recording_current_file = str(self._video_writer_path)
        self._local_recording_status = "recording"
        return writer

    def _write_video_packet(self, packet: FramePacket) -> bool:
        """Write a frame according to capture time instead of arrival count.

        OpenCV's VideoWriter produces constant-frame-rate files.  The capture
        and resize pipeline may deliver fewer frames than the configured FPS,
        especially at high camera resolutions.  Writing each delivered frame
        only once would therefore shorten the file and make playback look
        accelerated.  Duplicate the previous frame as needed so file duration
        follows the packets' wall-clock timestamps.
        """
        writer = self._ensure_video_writer(packet)
        if writer is None:
            return False

        fps = self._video_writer_fps or float(max(1, settings.target_fps))
        if self._video_timeline_start_ms is None or packet.ts_ms < self._video_timeline_start_ms:
            self._video_timeline_start_ms = packet.ts_ms
            self._video_frames_written = 0
            self._video_last_frame = None

        elapsed_ms = max(0, packet.ts_ms - self._video_timeline_start_ms)
        target_frame_count = max(1, int(round(elapsed_ms * fps / 1000.0)) + 1)
        frames_due = target_frame_count - self._video_frames_written
        if frames_due <= 0:
            self._video_last_frame = packet.frame
            return True

        # A prolonged camera disconnect must not cause an unbounded catch-up
        # loop.  Preserve normal gaps (including the 10-second auto-stop
        # window), but start a fresh timing baseline after a larger outage.
        max_catch_up_frames = max(1, int(round(fps * 10.0)))
        if frames_due > max_catch_up_frames:
            self._video_timeline_start_ms = packet.ts_ms
            self._video_frames_written = 0
            frames_due = 1

        fill_frame = self._video_last_frame if self._video_last_frame is not None else packet.frame
        for _ in range(frames_due - 1):
            writer.write(fill_frame)
        writer.write(packet.frame)
        self._video_frames_written += frames_due
        self._video_last_frame = packet.frame
        return True

    def _create_video_writer(
        self,
        stem: str,
        output_dir: Path,
        frame_width: int,
        frame_height: int,
        fps: float,
    ) -> tuple[cv2.VideoWriter | None, Path | None]:
        fourcc_fn = getattr(cv2, "VideoWriter_fourcc", None)
        if not callable(fourcc_fn):
            return None, None
        codecs = (
            ("mp4v", ".mp4"),
            ("avc1", ".mp4"),
        )
        for codec, suffix in codecs:
            path = output_dir / f"{stem}{suffix}"
            writer = cv2.VideoWriter(
                str(path),
                fourcc_fn(*codec),
                fps,
                (frame_width, frame_height),
            )
            if writer.isOpened():
                return writer, path
            writer.release()
            if path.exists():
                path.unlink(missing_ok=True)
        return None, None

    def _close_video_writer(self) -> None:
        if self._video_writer is not None:
            self._video_writer.release()
        self._video_writer = None
        self._video_writer_fps = 0.0
        self._video_timeline_start_ms = None
        self._video_frames_written = 0
        self._video_last_frame = None
        self._video_temp_path = None
        self._video_writer_segment_start = None
        self._video_writer_path = None
        self._local_recording_current_file = ""
        if self._recording_mode == "auto":
            self._local_recording_status = "waiting"
        elif self._local_recording_enabled and self.is_running():
            self._local_recording_status = "waiting"
        elif not self._local_recording_enabled:
            self._local_recording_status = "disabled"

    def _open_audio_writer(self, segment_start: datetime) -> None:
        if self.audio_streamer is None:
            return
        audio_path = self._segment_audio_temp_path(segment_start)
        audio_file = wave.open(str(audio_path), "wb")
        audio_file.setnchannels(getattr(self.audio_streamer, "channels", settings.audio_channels))
        audio_file.setsampwidth(2)
        audio_file.setframerate(getattr(self.audio_streamer, "sample_rate", settings.audio_sample_rate))
        self._audio_wave_file = audio_file
        self._audio_temp_path = audio_path

    def _close_audio_writer(self, finalize: bool = False) -> None:
        audio_wave = self._audio_wave_file
        audio_temp_path = self._audio_temp_path
        final_path = self._video_writer_path
        video_temp_path = self._video_temp_path
        if audio_wave is not None:
            audio_wave.close()
        self._audio_wave_file = None
        self._audio_temp_path = None
        if finalize and final_path is not None and video_temp_path is not None and video_temp_path.exists():
            queued = self._queue_segment_for_finalize(final_path, video_temp_path, audio_temp_path)
            if not queued:
                self._finalize_segment(final_path, video_temp_path, audio_temp_path)

    def _queue_segment_for_finalize(self, final_path: Path, video_temp_path: Path, audio_temp_path: Path | None) -> bool:
        try:
            self._mux_queue.put_nowait((final_path, video_temp_path, audio_temp_path))
            return True
        except queue.Full:
            return False

    def _finalize_segment(self, final_path: Path, video_temp_path: Path, audio_temp_path: Path | None) -> None:
        final_path.parent.mkdir(parents=True, exist_ok=True)
        if final_path.exists():
            final_path.unlink(missing_ok=True)
        has_audio = audio_temp_path is not None and audio_temp_path.exists() and audio_temp_path.stat().st_size > 44
        ffmpeg_executable = self._get_ffmpeg_executable()
        if ffmpeg_executable is None:
            shutil.move(str(video_temp_path), str(final_path))
            if has_audio and audio_temp_path is not None:
                audio_fallback_path = final_path.with_suffix(".wav")
                audio_fallback_path.unlink(missing_ok=True)
                shutil.move(str(audio_temp_path), str(audio_fallback_path))
            self._local_recording_status = "ffmpeg_unavailable"
            return

        command = [
            ffmpeg_executable,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(video_temp_path),
        ]
        if has_audio and audio_temp_path is not None:
            command.extend(["-i", str(audio_temp_path), "-map", "0:v:0", "-map", "1:a:0"])
        else:
            command.extend(["-map", "0:v:0", "-an"])
        command.extend(
            [
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "26",
                "-pix_fmt", "yuv420p",
            ]
        )
        if has_audio:
            command.extend(["-c:a", "aac", "-b:a", "128k", "-shortest"])
        command.extend(["-movflags", "+faststart", str(final_path)])

        completed_process = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
        )
        if completed_process.returncode == 0:
            video_temp_path.unlink(missing_ok=True)
            if audio_temp_path is not None:
                audio_temp_path.unlink(missing_ok=True)
            return
        shutil.move(str(video_temp_path), str(final_path))
        if has_audio and audio_temp_path is not None:
            audio_fallback_path = final_path.with_suffix(".wav")
            audio_fallback_path.unlink(missing_ok=True)
            shutil.move(str(audio_temp_path), str(audio_fallback_path))
        error_text = completed_process.stderr.strip() or "unknown"
        self._local_recording_status = f"mux_error: {error_text}"[:120]

    def _get_ffmpeg_executable(self) -> str | None:
        if self._ffmpeg_executable:
            return self._ffmpeg_executable
        binary = shutil.which("ffmpeg")
        if binary:
            self._ffmpeg_executable = binary
            return binary
        try:
            import imageio_ffmpeg

            self._ffmpeg_executable = imageio_ffmpeg.get_ffmpeg_exe()
            return self._ffmpeg_executable
        except Exception:
            return None

    def _ensure_audio_subscription(self) -> bool:
        if self.audio_streamer is None:
            self._local_recording_status = "audio_unavailable"
            return False
        if self._audio_subscriber_queue is not None:
            return True
        try:
            subscriber_id, subscriber_queue = self.audio_streamer.subscribe()
        except Exception as exc:
            self._local_recording_status = f"audio_error: {exc!s}"[:120]
            return False
        self._audio_subscriber_id = subscriber_id
        self._audio_subscriber_queue = subscriber_queue
        return True

    def _unsubscribe_audio(self) -> None:
        if self.audio_streamer is None or self._audio_subscriber_id is None:
            self._audio_subscriber_id = None
            self._audio_subscriber_queue = None
            return
        try:
            self.audio_streamer.unsubscribe(self._audio_subscriber_id)
        except Exception:
            pass
        self._audio_subscriber_id = None
        self._audio_subscriber_queue = None

    def _segment_output_path(self, segment_start: datetime) -> Path:
        return self._segment_directory(segment_start) / f"{self._segment_basename(segment_start)}.mp4"

    def _segment_audio_temp_path(self, segment_start: datetime) -> Path:
        return self._segment_directory(segment_start) / f"{self._segment_basename(segment_start)}.audio.wav"

    def _segment_directory(self, segment_start: datetime) -> Path:
        return self._local_recording_output_dir / segment_start.strftime("%Y-%m-%d")

    @staticmethod
    def _segment_basename(segment_start: datetime) -> str:
        return segment_start.strftime("%Y-%m-%d_%H-%M-%S")

    @classmethod
    def _prepare_recording_frame(cls, frame: np.ndarray) -> np.ndarray:
        height, width = frame.shape[:2]
        scale = min(
            cls._recording_max_width / max(1, width),
            cls._recording_max_height / max(1, height),
            1.0,
        )
        if scale >= 1.0:
            return frame
        target_width = max(2, int(round(width * scale)))
        target_height = max(2, int(round(height * scale)))
        if target_width % 2 != 0:
            target_width -= 1
        if target_height % 2 != 0:
            target_height -= 1
        return cv2.resize(frame, (target_width, target_height), interpolation=cv2.INTER_AREA)

    def list_recordings(self) -> list[dict]:
        root = self._local_recording_output_dir
        if not root.exists():
            return []
        items: list[RecordingItem] = []
        for path in root.rglob("*.mp4"):
            if not path.is_file():
                continue
            if ".video." in path.name:
                continue
            started_at = self._recording_started_at(path)
            if not started_at:
                continue
            day = path.parent.name if path.parent != root else "未归档"
            relative_path = path.relative_to(root).as_posix()
            modified_at = datetime.fromtimestamp(path.stat().st_mtime).isoformat()
            items.append(
                RecordingItem(
                    day=day,
                    relative_path=relative_path,
                    filename=path.name,
                    started_at=started_at,
                    size_bytes=path.stat().st_size,
                    modified_at=modified_at,
                )
            )
        items.sort(key=lambda item: item.relative_path, reverse=True)
        grouped: dict[str, list[RecordingItem]] = {}
        for item in items:
            grouped.setdefault(item.day, []).append(item)
        ordered_days = sorted((day for day in grouped if day != "未归档"), reverse=True)
        if "未归档" in grouped:
            ordered_days.append("未归档")
        return [
            {
                "day": day,
                "items": [
                    {
                        "relative_path": item.relative_path,
                        "filename": item.filename,
                        "started_at": item.started_at,
                        "size_bytes": item.size_bytes,
                        "modified_at": item.modified_at,
                    }
                    for item in grouped[day]
                ],
            }
            for day in ordered_days
        ]

    def resolve_recording_path(self, relative_path: str) -> Path:
        root = self._local_recording_output_dir.resolve()
        candidate = (root / relative_path).resolve()
        if root not in candidate.parents and candidate != root:
            raise ValueError("recording path escapes output dir")
        if not candidate.is_file():
            raise FileNotFoundError(relative_path)
        if (
            candidate.suffix.lower() != ".mp4"
            or ".video." in candidate.name
            or not self._recording_started_at(candidate)
        ):
            raise ValueError("unsupported recording file")
        return candidate

    @staticmethod
    def _recording_started_at(path: Path) -> str:
        stem = path.stem
        raw = stem[len("monitor_"):] if stem.startswith("monitor_") else stem
        for fmt in ("%Y-%m-%d_%H-%M-%S", "%Y%m%d_%H%M%S"):
            try:
                return datetime.strptime(raw, fmt).isoformat()
            except ValueError:
                continue
        return ""

    def _cleanup_expired_recordings(self, now: datetime | None = None) -> None:
        if not self._local_recording_output_dir.exists():
            return
        del now
        max_segments = max(
            1,
            (self._local_recording_retention_days * 24 * 60) // max(1, self._local_recording_segment_minutes),
        )
        groups: dict[str, list[Path]] = {}
        for path in self._local_recording_output_dir.rglob("*"):
            if not path.is_file():
                continue
            name = path.name
            if ".audio." in name or ".video." in name:
                continue
            if path.suffix.lower() not in {".mp4", ".wav"}:
                continue
            if not self._recording_started_at(path):
                continue
            key = str(path.with_suffix(""))
            groups.setdefault(key, []).append(path)
        if len(groups) <= max_segments:
            return
        for key in sorted(groups)[: len(groups) - max_segments]:
            for path in groups[key]:
                path.unlink(missing_ok=True)
        for directory in sorted(self._local_recording_output_dir.rglob("*"), reverse=True):
            if directory.is_dir():
                try:
                    directory.rmdir()
                except OSError:
                    pass

    def _record_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                packet = self.result_queue.get(timeout=0.2)
            except queue.Empty:
                continue
            self._prune_recently_closed(packet.ts_ms)
            visible_track_ids: set[int] = set()
            for track in packet.tracks:
                if not track.person_id:
                    continue
                track_last_seen_ms = self._track_last_seen_ms(track)
                if track_last_seen_ms + 250 < packet.ts_ms:
                    continue
                visible_track_ids.add(track.track_id)
                state = self._presence_by_track_id.get(track.track_id)
                if state is not None and state.person_id == track.person_id:
                    state.last_seen_ms = packet.ts_ms
                    self._pending_by_track_id.pop(track.track_id, None)
                    continue

                if state is not None:
                    self._finalize_presence(track.track_id, state, packet.ts_ms)

                pending = self._pending_by_track_id.get(track.track_id)
                if pending is None or pending.person_id != track.person_id:
                    pending = PendingPresenceState(
                        person_id=track.person_id,
                        first_seen_ms=packet.ts_ms,
                        last_seen_ms=packet.ts_ms,
                        seen_frames=1,
                    )
                    self._pending_by_track_id[track.track_id] = pending
                else:
                    pending.last_seen_ms = packet.ts_ms
                    pending.seen_frames += 1

                if not self._pending_is_stable(pending):
                    continue

                visit_id, reused_recent_visit = self._activate_visit_for_track(
                    track_id=track.track_id,
                    person_id=track.person_id,
                    ts_ms=packet.ts_ms,
                )
                if not reused_recent_visit:
                    self.repository.upsert_event(person_id=track.person_id, timestamp_ms=packet.ts_ms)
                self._presence_by_track_id[track.track_id] = PresenceState(
                    visit_id=visit_id,
                    person_id=track.person_id,
                    first_seen_ms=pending.first_seen_ms,
                    last_seen_ms=packet.ts_ms,
                )
                del self._pending_by_track_id[track.track_id]

            leaving_track_ids: list[int] = []
            for track_id, state in self._presence_by_track_id.items():
                if track_id in visible_track_ids:
                    continue
                if packet.ts_ms - state.last_seen_ms >= self._leave_timeout_ms:
                    self._finalize_presence(track_id, state, state.last_seen_ms)
                    leaving_track_ids.append(track_id)
            for track_id in leaving_track_ids:
                del self._presence_by_track_id[track_id]
            stale_pending_track_ids: list[int] = []
            for track_id, pending in self._pending_by_track_id.items():
                if track_id in visible_track_ids:
                    continue
                if packet.ts_ms - pending.last_seen_ms >= self._leave_timeout_ms:
                    stale_pending_track_ids.append(track_id)
            for track_id in stale_pending_track_ids:
                del self._pending_by_track_id[track_id]
            self._update_auto_recording_presence(len(self._presence_by_track_id))
        for state in self._presence_by_track_id.values():
            self._finalize_presence(track_id=-1, state=state, left_ms=state.last_seen_ms)
        self._presence_by_track_id.clear()
        self._pending_by_track_id.clear()
        self._update_auto_recording_presence(0)

    def _update_auto_recording_presence(self, presence_count: int) -> None:
        with self._recording_state_lock:
            self._stable_presence_count = presence_count
            if self._recording_mode != "auto":
                return
            if presence_count > 0:
                self._auto_stop_deadline = None
                if not self._recording_active:
                    self._recording_active = True
                    self._local_recording_enabled = True
                    self._recording_triggered_by = "person"
                    self._local_recording_status = "starting"
                    self._preroll_pending = True
            elif self._recording_active and self._auto_stop_deadline is None:
                self._auto_stop_deadline = time.monotonic() + self._auto_stop_delay_seconds

    def _refresh_auto_recording_state(self) -> None:
        should_close = False
        with self._recording_state_lock:
            if (
                self._recording_mode == "auto"
                and self._recording_active
                and self._auto_stop_deadline is not None
                and time.monotonic() >= self._auto_stop_deadline
            ):
                self._recording_active = False
                self._local_recording_enabled = False
                self._recording_triggered_by = "none"
                self._local_recording_status = "waiting"
                self._auto_stop_deadline = None
                should_close = True
        if should_close:
            with self._recording_lock:
                self._close_audio_writer(finalize=True)
                self._close_video_writer()
            self._unsubscribe_audio()

    def _activate_visit_for_track(self, track_id: int, person_id: str, ts_ms: int) -> tuple[int, bool]:
        recent = self._recently_closed_by_person_id.get(person_id)
        if recent is not None and ts_ms - recent.left_ms <= self._visit_merge_window_ms:
            if self.repository.reopen_visit(recent.visit_id, source_track_id=track_id):
                del self._recently_closed_by_person_id[person_id]
                return recent.visit_id, True
        visit_id = self.repository.create_visit(
            person_id=person_id,
            appeared_ms=ts_ms,
            source_track_id=track_id,
        )
        return visit_id, False

    def _finalize_presence(self, track_id: int, state: PresenceState, left_ms: int) -> None:
        stay_seconds = max(0.0, (left_ms - state.first_seen_ms) / 1000.0)
        if stay_seconds < self._visit_discard_under_seconds:
            self.repository.delete_visit(state.visit_id)
            self._recently_closed_by_person_id.pop(state.person_id, None)
        else:
            self.repository.close_visit(state.visit_id, left_ms)
            self._recently_closed_by_person_id[state.person_id] = RecentlyClosedVisit(
                visit_id=state.visit_id,
                left_ms=left_ms,
            )
        self._pending_by_track_id.pop(track_id, None)

    def _pending_is_stable(self, pending: PendingPresenceState) -> bool:
        duration_ms = pending.last_seen_ms - pending.first_seen_ms
        return (
            pending.seen_frames >= self._visit_create_min_seen_frames
            and duration_ms >= self._visit_create_min_duration_ms
        )

    def _prune_recently_closed(self, ts_ms: int) -> None:
        self._recently_closed_by_person_id = {
            person_id: info
            for person_id, info in self._recently_closed_by_person_id.items()
            if ts_ms - info.left_ms <= self._visit_merge_window_ms
        }

    @staticmethod
    def _track_last_seen_ms(track: object) -> int:
        last_seen = getattr(track, "last_seen", None)
        if last_seen is None:
            return 0
        return int(last_seen.timestamp() * 1000)

    @staticmethod
    def _put_latest(q: queue.Queue, item: object) -> None:
        try:
            q.put_nowait(item)
        except queue.Full:
            try:
                q.get_nowait()
            except queue.Empty:
                pass
            q.put_nowait(item)

    @staticmethod
    def _outlined_text(
        img: np.ndarray, text: str, org: tuple[int, int],
        scale: float, color: tuple[int, int, int], thickness: int,
    ) -> None:
        cv2.putText(img, text, org, cv2.FONT_HERSHEY_DUPLEX, scale, (0, 0, 0), thickness + 3, cv2.LINE_AA)
        cv2.putText(img, text, org, cv2.FONT_HERSHEY_DUPLEX, scale, color, thickness, cv2.LINE_AA)

    @staticmethod
    def _render_fast(frame: np.ndarray, tracks: list, ts_ms: int, notes: dict) -> np.ndarray:
        out = frame.copy()
        _, w = out.shape[:2]
        scale = max(w / 1280, 1.0)
        thick = max(1, round(scale))
        ts_text = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts_ms / 1000))
        VideoAnalyticsPipeline._outlined_text(out, ts_text, (16, int(32 * scale)), 0.7 * scale, (0, 255, 0), thick)
        tracks_with_notes: list = []
        for obj in tracks:
            x1, y1, x2, y2 = obj.bbox
            cv2.rectangle(out, (x1, y1), (x2, y2), (0, 128, 255), max(2, round(2 * scale)))
            note = notes.get(obj.track_id)
            if note:
                tracks_with_notes.append(obj)
            else:
                label = f"T{obj.track_id} {obj.person_id or 'unknown'}"
                VideoAnalyticsPipeline._outlined_text(
                    out, label, (x1, max(20, y1 - int(8 * scale))), 0.55 * scale, (0, 128, 255), thick,
                )
        if tracks_with_notes:
            out = VideoAnalyticsPipeline._draw_note_overlay(out, tracks_with_notes, notes, scale)
        return out

    @staticmethod
    def _render(frame: np.ndarray, tracks: list, ts_ms: int, notes: dict) -> np.ndarray:
        out = frame.copy()
        _, w = out.shape[:2]
        scale = max(w / 1280, 1.0)
        thick = max(1, round(scale))
        ts_text = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts_ms / 1000))
        VideoAnalyticsPipeline._outlined_text(out, ts_text, (16, int(32 * scale)), 0.7 * scale, (0, 255, 0), thick)
        
        for obj in tracks:
            x1, y1, x2, y2 = obj.bbox
            cv2.rectangle(out, (x1, y1), (x2, y2), (0, 128, 255), max(2, round(2 * scale)))
            note = notes.get(obj.track_id)
            if not note:
                label = f"T{obj.track_id} {obj.person_id or 'unknown'}"
                VideoAnalyticsPipeline._outlined_text(
                    out, label, (x1, max(20, y1 - int(8 * scale))), 0.55 * scale, (0, 128, 255), thick,
                )

        tracks_with_notes = [obj for obj in tracks if notes.get(obj.track_id)]
        if tracks_with_notes:
            out = VideoAnalyticsPipeline._draw_note_overlay(out, tracks_with_notes, notes, scale)

        return out

    @staticmethod
    def _draw_note_overlay(frame: np.ndarray, tracks: list, notes: dict, scale: float) -> np.ndarray:
        pil_img = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(pil_img)
        font_size = max(16, int(22 * scale))
        font = _get_chinese_font(font_size)

        for obj in tracks:
            x1, y1, _, _ = obj.bbox
            note = notes.get(obj.track_id)
            if not note:
                continue
            nx, ny = x1, max(0, y1 - font_size - 4)

            draw.text((nx - 1, ny - 1), note, font=font, fill=(0, 0, 0))
            draw.text((nx + 1, ny - 1), note, font=font, fill=(0, 0, 0))
            draw.text((nx - 1, ny + 1), note, font=font, fill=(0, 0, 0))
            draw.text((nx + 1, ny + 1), note, font=font, fill=(0, 0, 0))
            draw.text((nx, ny), note, font=font, fill=(255, 128, 0))

        return cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)

    def _open_capture(self) -> cv2.VideoCapture:
        source = self.stream_source
        if source.isdigit():
            index = int(source)
            if self._camera_backends:
                backend_name, backend_id = self._camera_backends[self._backend_cursor % len(self._camera_backends)]
                cap = cv2.VideoCapture(index, backend_id)
                self.stats.capture_backend = backend_name
                cap.set(cv2.CAP_PROP_CONVERT_RGB, 1)
                fourcc_fn = getattr(cv2, "VideoWriter_fourcc", None)
                if callable(fourcc_fn):
                    cap.set(cv2.CAP_PROP_FOURCC, fourcc_fn(*"MJPG"))
                cap.set(cv2.CAP_PROP_FRAME_WIDTH, settings.camera_width)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, settings.camera_height)
                return cap
            self.stats.capture_backend = "AUTO"
            return cv2.VideoCapture(index)
        cap = cv2.VideoCapture(source, getattr(cv2, "CAP_FFMPEG", cv2.CAP_ANY))
        self.stats.capture_backend = "FFMPEG"
        if not cap.isOpened():
            cap = cv2.VideoCapture(source)
            self.stats.capture_backend = "AUTO"
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        return cap

    def _publish_placeholder_frame(self, message: str) -> None:
        frame = np.zeros((720, 1280, 3), dtype=np.uint8)
        cv2.putText(frame, "No Video Signal", (40, 120), cv2.FONT_HERSHEY_SIMPLEX, 2.0, (0, 165, 255), 3)
        cv2.putText(frame, message, (40, 190), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 165, 255), 2)
        cv2.putText(
            frame,
            f"source={self.stream_source}",
            (40, 240),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.8,
            (0, 165, 255),
            2,
        )
        ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
        if ok:
            self._latest_frame = bytes(buf)

    @staticmethod
    def _build_camera_backends() -> list[tuple[str, int]]:
        backends: list[tuple[str, int]] = []
        dshow = getattr(cv2, "CAP_DSHOW", -1)
        msmf = getattr(cv2, "CAP_MSMF", -1)
        cap_any = getattr(cv2, "CAP_ANY", -1)
        if dshow >= 0:
            backends.append(("DSHOW", dshow))
        if msmf >= 0:
            backends.append(("MSMF", msmf))
        if cap_any >= 0:
            backends.append(("ANY", cap_any))
        return backends

    def _switch_camera_backend(self) -> None:
        if not self.stream_source.isdigit():
            return
        if not self._camera_backends:
            return
        self._backend_cursor = (self._backend_cursor + 1) % len(self._camera_backends)

    @staticmethod
    def _is_corrupted_frame(frame: np.ndarray) -> bool:
        if frame.size == 0 or frame.ndim != 3 or frame.shape[2] < 3:
            return True
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        std_value = float(np.std(gray))
        b = frame[:, :, 0].astype(np.int16)
        g = frame[:, :, 1].astype(np.int16)
        r = frame[:, :, 2].astype(np.int16)
        bg_diff = float(np.mean(np.abs(b - g)))
        gr_diff = float(np.mean(np.abs(g - r)))
        line_noise = float(np.mean(np.abs(np.diff(gray.astype(np.int16), axis=0))))
        return std_value > 55.0 and bg_diff > 35.0 and gr_diff > 35.0 and line_noise > 45.0
