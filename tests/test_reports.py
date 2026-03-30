from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from app.analytics.report import ReportService
from app.storage.database import create_sqlite_session
from app.storage.repository import PersonEventRepository


def test_daily_report(tmp_path: Path) -> None:
    session_factory = create_sqlite_session(tmp_path / "report.db")
    repo = PersonEventRepository(session_factory=session_factory, dedupe_seconds=0)
    base = datetime(2026, 1, 1, 8, 0, tzinfo=timezone.utc)
    repo.upsert_event("p1", int(base.timestamp() * 1000))
    repo.upsert_event("p2", int((base.replace(hour=9)).timestamp() * 1000))
    report = ReportService(repo).generate_daily(day=base)
    assert report["report_type"] == "daily"
    assert report["unique_persons"] == 2
    assert report["total_appearances"] == 2
