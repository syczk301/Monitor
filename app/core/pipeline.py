from __future__ import annotations

import queue
import threading
import time
from collections.abc import Generator
from dataclasses import dataclass

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


class VideoAnalyticsPipeline:
    def __init__(
        self,
        repository: PersonEventRepository,
        identity_store: object | None = None,
        stream_source: str | None = None,
    ) -> None:
        self.stream_source = stream_source if stream_source is not None else settings.stream_source
        self.repository = repository
        self.identity_store = identity_store if identity_store is not None else repository
        self.detector = PersonDetector()
        self.tracker = MultiObjectTracker()
        self.face_engine = PersonEmbeddingEngine()
        self.reid = ReIDRegistry()
        self.identity_memory = TemporalIdentityMemory()
        self.frame_queue: queue.Queue[FramePacket] = queue.Queue(maxsize=settings.frame_queue_size)
        self.result_queue: queue.Queue[InferencePacket] = queue.Queue(maxsize=settings.result_queue_size)
        self._stop_event = threading.Event()
        self._capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
        self._inference_thread = threading.Thread(target=self._inference_loop, daemon=True)
        self._record_thread = threading.Thread(target=self._record_loop, daemon=True)
        self._latest_frame: bytes = b""
        self._has_inference_frame: bool = False
        self._presence_by_track_id: dict[int, PresenceState] = {}
        self._pending_by_track_id: dict[int, PendingPresenceState] = {}
        self._recently_closed_by_person_id: dict[str, RecentlyClosedVisit] = {}
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
        self._actual_width: int = 0
        self._actual_height: int = 0
        self._client_count = 0
        self._client_lock = threading.Lock()
        self._idle_timer: threading.Timer | None = None
        self._idle_timeout_seconds = 15

    def start(self) -> None:
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

    def stop(self) -> None:
        self._stop_event.set()
        for thread in (self._capture_thread, self._inference_thread, self._record_thread):
            if thread.is_alive():
                thread.join(timeout=2)
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
            if self._client_count == 0:
                self._idle_timer = threading.Timer(self._idle_timeout_seconds, self._idle_shutdown)
                self._idle_timer.daemon = True
                self._idle_timer.start()

    def _idle_shutdown(self) -> None:
        with self._client_lock:
            if self._client_count == 0:
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

    def get_capture_info(self) -> dict:
        return {
            "requested_width": settings.camera_width,
            "requested_height": settings.camera_height,
            "actual_width": self._actual_width,
            "actual_height": self._actual_height,
            "mjpeg_quality": settings.mjpeg_quality,
            "capture_status": self.stats.capture_status,
            "capture_backend": self.stats.capture_backend,
        }

    def _capture_loop(self) -> None:
        cap: cv2.VideoCapture | None = None
        failed_reads = 0
        corrupted_reads = 0
        frame_id = 0
        prev_display_ts = time.time()
        target_interval = 1.0 / max(1, settings.target_fps)
        while not self._stop_event.is_set():
            loop_start = time.monotonic()
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
            
            ts_ms = int(time.time() * 1000)
            with self._overlay_lock:
                cur_tracks = list(self._overlay_tracks)
                cur_notes = dict(self._overlay_notes)
            rendered = self._render_fast(frame, cur_tracks, ts_ms, cur_notes)
            ok_enc, buf = cv2.imencode(".jpg", rendered, [int(cv2.IMWRITE_JPEG_QUALITY), settings.mjpeg_quality])
            if ok_enc:
                self._latest_frame = bytes(buf)
            
            now = time.time()
            dt = now - prev_display_ts
            prev_display_ts = now
            if dt > 0:
                self.stats.fps = 0.8 * self.stats.fps + 0.2 * (1.0 / dt)
            
            packet = FramePacket(frame_id=frame_id, ts_ms=ts_ms, frame=frame)
            try:
                self.frame_queue.put_nowait(packet)
            except queue.Full:
                try:
                    self.frame_queue.get_nowait()
                except queue.Empty:
                    pass
                try:
                    self.frame_queue.put_nowait(packet)
                except queue.Full:
                    pass
            frame_id += 1

            elapsed = time.monotonic() - loop_start
            sleep_time = target_interval - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)
        if cap is not None:
            cap.release()

    def _inference_loop(self) -> None:
        prev_ts = time.time()
        latencies: list[float] = []
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
                latency = max(0.0, time.time() * 1000 - packet.ts_ms)
                latencies.append(latency)
                if len(latencies) > 120:
                    latencies.pop(0)
                self.stats.avg_latency_ms = float(np.mean(latencies)) if latencies else 0.0
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
        for state in self._presence_by_track_id.values():
            self._finalize_presence(track_id=-1, state=state, left_ms=state.last_seen_ms)
        self._presence_by_track_id.clear()
        self._pending_by_track_id.clear()

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
