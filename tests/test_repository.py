from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from app.storage.database import create_sqlite_session
from app.storage.repository import PersonEventRepository


def test_dedupe_window(tmp_path: Path) -> None:
    db_file = tmp_path / "test.db"
    session_factory = create_sqlite_session(db_file)
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=300)
    repo.upsert_event("person-a", 1_000)
    repo.upsert_event("person-a", 200_000)
    repo.upsert_event("person-a", 400_000)
    events = repo.list_events(limit=10)
    assert len(events) == 1
    assert events[0].appearance_count == 2


def test_summary(tmp_path: Path) -> None:
    db_file = tmp_path / "test.db"
    session_factory = create_sqlite_session(db_file)
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=5)
    repo.upsert_event("person-a", 1_000)
    repo.upsert_event("person-b", 10_000)
    summary = repo.summary()
    assert summary["unique_persons"] == 2.0


def test_visit_appear_and_leave_and_note(tmp_path: Path) -> None:
    db_file = tmp_path / "test.db"
    session_factory = create_sqlite_session(db_file)
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=5)
    visit_id = repo.create_visit("person-a", appeared_ms=1_000, source_track_id=7)
    repo.close_visit(visit_id, left_ms=6_000)
    assert repo.update_visit_note(visit_id, "临时访客")
    visits = repo.list_visits(limit=10)
    assert len(visits) == 1
    visit = visits[0]
    assert visit.person_id == "person-a"
    assert visit.left_at is not None
    assert visit.stay_seconds == 5.0
    assert visit.note == "临时访客"


def test_visit_can_be_reopened(tmp_path: Path) -> None:
    db_file = tmp_path / "test.db"
    session_factory = create_sqlite_session(db_file)
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=5)
    visit_id = repo.create_visit("person-a", appeared_ms=1_000, source_track_id=7)
    repo.close_visit(visit_id, left_ms=4_000)

    reopened = repo.reopen_visit(visit_id, source_track_id=9)
    visit = repo.list_visits(limit=10)[0]

    assert reopened is True
    assert visit.id == visit_id
    assert visit.left_at is None
    assert visit.stay_seconds == 0.0
    assert visit.source_track_id == 9


def test_person_note_updates_all_visits_and_is_inherited(tmp_path: Path) -> None:
    db_file = tmp_path / "test.db"
    session_factory = create_sqlite_session(db_file)
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=5)
    first_visit = repo.create_visit("person-a", appeared_ms=1_000, source_track_id=1)
    second_visit = repo.create_visit("person-a", appeared_ms=2_000, source_track_id=2)

    updated = repo.update_person_note("person-a", "常客")
    third_visit = repo.create_visit("person-a", appeared_ms=3_000, source_track_id=3)
    visits = repo.list_visits(limit=10)

    assert updated == 2
    assert repo.get_visit_note(first_visit) == "常客"
    assert repo.get_visit_note(second_visit) == "常客"
    assert repo.get_visit_note(third_visit) == "常客"
    assert all(visit.note == "常客" for visit in visits)


def test_identity_template_roundtrip(tmp_path: Path) -> None:
    db_file = tmp_path / "test.db"
    session_factory = create_sqlite_session(db_file)
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=5)
    templates = {
        "person-a": [
            np.array([0.1, 0.2, 0.3], dtype=np.float32),
            np.array([0.4, 0.5, 0.6], dtype=np.float32),
        ],
        "person-b": [np.array([0.7, 0.8, 0.9], dtype=np.float32)],
    }

    saved = repo.save_identity_templates(templates)
    loaded = repo.load_identity_templates()

    assert saved == 3
    assert set(loaded) == {"person-a", "person-b"}
    assert len(loaded["person-a"]) == 2
    assert np.allclose(loaded["person-a"][0], templates["person-a"][0])
    assert np.allclose(loaded["person-b"][0], templates["person-b"][0])


def test_raw_appearances_and_rois(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.chdir(tmp_path)
    session_factory = create_sqlite_session(tmp_path / "test.db")
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=0)
    repo.upsert_event("person-a", 1_000)
    repo.upsert_event("person-b", 2_000)

    rows = repo.list_raw_appearances_between(
        start=datetime.fromtimestamp(0, tz=timezone.utc),
        end=datetime.fromtimestamp(10, tz=timezone.utc),
    )
    roi = repo.add_roi([1, 2, 3, 4], params={"confidence": 0.2, "upscale_factor": 3.4})
    updated_roi = repo.update_roi(roi["id"], bbox=[2, 3, 4, 5], params={"imgsz": 1024})

    assert len(rows) == 2
    assert updated_roi is not None
    assert updated_roi["bbox"] == [2, 3, 4, 5]
    assert updated_roi["params"]["confidence"] == 0.2
    assert updated_roi["params"]["imgsz"] == 1024
    assert repo.list_rois() == [updated_roi]
    assert repo.delete_roi(roi["id"])
    assert repo.list_rois() == []
