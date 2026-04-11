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
    pipeline._local_recording_enabled = True
    pipeline._client_count = 1

    pipeline.release()

    assert pipeline._client_count == 0
    assert pipeline._idle_timer is None


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
    first_file = day1 / "monitor_20240101_000000.mp4"
    second_file = day1 / "monitor_20240101_120000.mp4"
    third_file = day2 / "monitor_20240102_000000.mp4"
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
        r"D:\download\Monitor\2024-01-02\monitor_20240102_130000.mp4"
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
    (day_dir / "monitor_20240102_130000.mp4").write_bytes(b"a")
    (tmp_path / "monitor_20240101_120000.mp4").write_bytes(b"b")
    (day_dir / "monitor_20240102_130000.video.mp4").write_bytes(b"ignore")

    groups = pipeline.list_recordings()

    assert groups[0]["day"] == "2024-01-02"
    assert groups[0]["items"][0]["relative_path"] == "2024-01-02/monitor_20240102_130000.mp4"
    assert groups[1]["day"] == "未归档"
    assert groups[1]["items"][0]["relative_path"] == "monitor_20240101_120000.mp4"


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
