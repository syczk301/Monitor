from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import app.core.pipeline as pipeline_module
from app.core.entities import InferencePacket, TrackedObject
import numpy as np


class _Dummy:
    def __init__(self, *args, **kwargs) -> None:
        pass


@dataclass
class _FakeVisit:
    person_id: str
    source_track_id: int | None
    appeared_ms: int
    left_ms: int | None = None


class _FakeRepository:
    def __init__(self) -> None:
        self.next_visit_id = 1
        self.visits: dict[int, _FakeVisit] = {}
        self.created_visit_ids: list[int] = []
        self.reopened_visit_ids: list[int] = []
        self.closed_visit_ids: list[int] = []
        self.deleted_visit_ids: list[int] = []
        self.upserted_person_ids: list[str] = []

    def create_visit(self, person_id: str, appeared_ms: int, source_track_id: int | None = None) -> int:
        visit_id = self.next_visit_id
        self.next_visit_id += 1
        self.visits[visit_id] = _FakeVisit(
            person_id=person_id,
            source_track_id=source_track_id,
            appeared_ms=appeared_ms,
        )
        self.created_visit_ids.append(visit_id)
        return visit_id

    def reopen_visit(self, visit_id: int, source_track_id: int | None = None) -> bool:
        visit = self.visits.get(visit_id)
        if visit is None:
            return False
        visit.left_ms = None
        visit.source_track_id = source_track_id
        self.reopened_visit_ids.append(visit_id)
        return True

    def close_visit(self, visit_id: int, left_ms: int) -> None:
        visit = self.visits.get(visit_id)
        if visit is None:
            return
        visit.left_ms = left_ms
        self.closed_visit_ids.append(visit_id)

    def delete_visit(self, visit_id: int) -> bool:
        if visit_id not in self.visits:
            return False
        del self.visits[visit_id]
        self.deleted_visit_ids.append(visit_id)
        return True

    def upsert_event(self, person_id: str, timestamp_ms: int) -> None:
        self.upserted_person_ids.append(person_id)

    def get_visit_note(self, visit_id: int) -> str:
        return ""

    def load_identity_templates(self) -> dict:
        return {}

    def save_identity_templates(self, payload: dict) -> int:
        return 0


def _track(track_id: int, person_id: str, ts_ms: int) -> TrackedObject:
    now = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
    return TrackedObject(
        track_id=track_id,
        bbox=(100, 100, 180, 260),
        first_seen=now,
        last_seen=now,
        trajectory=[(140, 180)],
        feature=None,
        person_id=person_id,
    )


def test_record_loop_merges_short_reconnect_and_drops_short_visit(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    repo = _FakeRepository()
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=repo, identity_store=repo, stream_source="0")
    pipeline._visit_create_min_seen_frames = 2
    pipeline._visit_create_min_duration_ms = 100
    pipeline._visit_merge_window_ms = 2_000
    pipeline._visit_discard_under_seconds = 0.5
    pipeline._leave_timeout_ms = 150

    worker = threading.Thread(target=pipeline._record_loop, daemon=True)
    worker.start()

    packets = [
        InferencePacket(frame_id=1, ts_ms=1_000, tracks=[_track(1, "person-a", 1_000)], encoded_frame=b""),
        InferencePacket(frame_id=2, ts_ms=1_700, tracks=[_track(1, "person-a", 1_700)], encoded_frame=b""),
        InferencePacket(frame_id=3, ts_ms=1_950, tracks=[], encoded_frame=b""),
        InferencePacket(frame_id=4, ts_ms=2_300, tracks=[_track(2, "person-a", 2_300)], encoded_frame=b""),
        InferencePacket(frame_id=5, ts_ms=2_900, tracks=[_track(2, "person-a", 2_900)], encoded_frame=b""),
        InferencePacket(frame_id=6, ts_ms=3_150, tracks=[], encoded_frame=b""),
        InferencePacket(frame_id=7, ts_ms=4_000, tracks=[_track(3, "person-b", 4_000)], encoded_frame=b""),
        InferencePacket(frame_id=8, ts_ms=4_150, tracks=[_track(3, "person-b", 4_150)], encoded_frame=b""),
        InferencePacket(frame_id=9, ts_ms=4_320, tracks=[], encoded_frame=b""),
    ]
    for packet in packets:
        pipeline.result_queue.put(packet)
        time.sleep(0.03)

    time.sleep(0.2)
    pipeline._stop_event.set()
    worker.join(timeout=2)

    assert repo.created_visit_ids == [1, 2]
    assert repo.reopened_visit_ids == [1]
    assert repo.deleted_visit_ids == [2]
    assert repo.upserted_person_ids == ["person-a", "person-b"]
    assert 1 in repo.closed_visit_ids


def test_release_keeps_pipeline_alive_when_local_recording_enabled(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    repo = _FakeRepository()
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=repo, identity_store=repo, stream_source="0")
    pipeline._recording_mode = "continuous"
    pipeline._local_recording_enabled = True
    pipeline._client_count = 1

    pipeline.release()

    assert pipeline._client_count == 0
    assert pipeline._idle_timer is None


def test_auto_recording_starts_for_stable_presence_and_stops_after_deadline(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=_FakeRepository(), stream_source="0")
    pipeline._recording_mode = "auto"
    pipeline._recording_active = False
    pipeline._local_recording_enabled = False

    pipeline._update_auto_recording_presence(2)
    assert pipeline._recording_active is True
    assert pipeline._local_recording_enabled is True
    assert pipeline._recording_triggered_by == "person"
    assert pipeline._preroll_pending is True

    pipeline._update_auto_recording_presence(0)
    assert pipeline._auto_stop_deadline is not None
    pipeline._auto_stop_deadline = time.monotonic() - 0.01
    monkeypatch.setattr(pipeline, "_close_audio_writer", lambda finalize=True: None)
    monkeypatch.setattr(pipeline, "_close_video_writer", lambda: None)
    monkeypatch.setattr(pipeline, "_unsubscribe_audio", lambda: None)
    pipeline._refresh_auto_recording_state()
    assert pipeline._recording_active is False
    assert pipeline._local_recording_enabled is False


def test_preroll_buffer_keeps_only_last_three_seconds(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=_FakeRepository(), stream_source="0")
    pipeline._recording_mode = "auto"
    frame = np.zeros((120, 160, 3), dtype=np.uint8)
    for ts_ms in (0, 1000, 2000, 3000, 4000):
        pipeline._remember_preroll_frame(ts_ms, frame)
    assert [ts for ts, _ in pipeline._preroll_frames] == [1000, 2000, 3000, 4000]


def test_recording_mode_is_persisted(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=_FakeRepository(), stream_source="0")
    pipeline._recording_settings_path = tmp_path / "recording_settings.json"
    pipeline._client_count = 1
    monkeypatch.setattr(pipeline, "start", lambda: None)
    result = pipeline.set_recording_mode("auto")
    assert result["recording_mode"] == "auto"
    assert '"auto"' in pipeline._recording_settings_path.read_text(encoding="utf-8")


def test_cleanup_expired_recordings_keeps_recent_segment_window(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    repo = _FakeRepository()
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=repo, identity_store=repo, stream_source="0")
    pipeline._local_recording_output_dir = tmp_path
    pipeline._local_recording_retention_days = 1
    pipeline._local_recording_segment_minutes = 720

    day1 = tmp_path / "2024-01-01"
    day2 = tmp_path / "2024-01-02"
    day1.mkdir()
    day2.mkdir()
    first_file = day1 / "2024-01-01_00-00-00.mp4"
    second_file = day1 / "2024-01-01_12-00-00.mp4"
    third_file = day2 / "2024-01-02_00-00-00.mp4"
    first_file.write_bytes(b"first")
    second_file.write_bytes(b"second")
    third_file.write_bytes(b"third")

    pipeline._cleanup_expired_recordings()

    assert not first_file.exists()
    assert second_file.exists()
    assert third_file.exists()


def test_segment_output_path_uses_daily_directory(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    repo = _FakeRepository()
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=repo, identity_store=repo, stream_source="0")
    pipeline._local_recording_output_dir = pipeline_module.Path(r"D:\download\Monitor")

    segment_start = datetime(2024, 1, 2, 13, 0, 0)

    assert pipeline._segment_output_path(segment_start) == pipeline_module.Path(
        r"D:\download\Monitor\2024-01-02\2024-01-02_13-00-00.mp4"
    )


def test_list_recordings_groups_daily_files(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    repo = _FakeRepository()
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=repo, identity_store=repo, stream_source="0")
    pipeline._local_recording_output_dir = tmp_path
    day_dir = tmp_path / "2024-01-02"
    day_dir.mkdir()
    (day_dir / "2024-01-02_13-00-00.mp4").write_bytes(b"a")
    (tmp_path / "monitor_20240101_120000.mp4").write_bytes(b"b")
    (day_dir / "2024-01-02_13-00-00.video.mp4").write_bytes(b"ignore")

    groups = pipeline.list_recordings()

    assert groups[0]["day"] == "2024-01-02"
    assert groups[0]["items"][0]["relative_path"] == "2024-01-02/2024-01-02_13-00-00.mp4"
    assert groups[1]["day"] == "未归档"
    assert groups[1]["items"][0]["relative_path"] == "monitor_20240101_120000.mp4"
    assert groups[1]["items"][0]["started_at"] == "2024-01-01T12:00:00"


def test_resolve_recording_path_rejects_escape(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    repo = _FakeRepository()
    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=repo, identity_store=repo, stream_source="0")
    pipeline._local_recording_output_dir = tmp_path

    try:
        pipeline.resolve_recording_path("../escape.mp4")
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_prepare_recording_frame_caps_output_at_1080p(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    frame = np.zeros((2160, 3840, 3), dtype=np.uint8)

    resized = pipeline_module.VideoAnalyticsPipeline._prepare_recording_frame(frame)

    assert resized.shape[:2] == (1080, 1920)


def test_prepare_recording_frame_keeps_smaller_input(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    frame = np.zeros((720, 1280, 3), dtype=np.uint8)

    resized = pipeline_module.VideoAnalyticsPipeline._prepare_recording_frame(frame)

    assert resized.shape[:2] == (720, 1280)


def test_video_writer_uses_packet_timestamps_to_preserve_duration(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    class _Writer:
        def __init__(self) -> None:
            self.frames: list[np.ndarray] = []

        def write(self, frame: np.ndarray) -> None:
            self.frames.append(frame.copy())

    pipeline = pipeline_module.VideoAnalyticsPipeline(repository=_FakeRepository(), stream_source="0")
    writer = _Writer()
    pipeline._video_writer = writer
    pipeline._video_writer_fps = 30.0
    pipeline._video_timeline_start_ms = 1_000
    monkeypatch.setattr(pipeline, "_ensure_video_writer", lambda packet: writer)

    for frame_id, ts_ms in enumerate((1_000, 1_100, 1_200), start=1):
        frame = np.full((8, 8, 3), frame_id, dtype=np.uint8)
        packet = pipeline_module.FramePacket(frame_id=frame_id, ts_ms=ts_ms, frame=frame)
        assert pipeline._write_video_packet(packet) is True

    assert len(writer.frames) == 7
    assert int(writer.frames[-1][0, 0, 0]) == 3


def test_segment_basename_uses_readable_format(monkeypatch) -> None:
    monkeypatch.setattr(pipeline_module, "PersonDetector", _Dummy)
    monkeypatch.setattr(pipeline_module, "MultiObjectTracker", _Dummy)
    monkeypatch.setattr(pipeline_module, "PersonEmbeddingEngine", _Dummy)
    monkeypatch.setattr(pipeline_module, "ReIDRegistry", _Dummy)
    monkeypatch.setattr(pipeline_module, "TemporalIdentityMemory", _Dummy)

    assert (
        pipeline_module.VideoAnalyticsPipeline._segment_basename(datetime(2024, 1, 2, 13, 5, 9))
        == "2024-01-02_13-05-09"
    )
