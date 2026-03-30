from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta, timezone


class ReportService:
    def __init__(self, repository) -> None:
        self.repository = repository

    def generate_daily(self, day: datetime | None = None) -> dict:
        target = day or datetime.now(tz=timezone.utc)
        start = datetime(target.year, target.month, target.day, tzinfo=timezone.utc)
        end = start + timedelta(days=1)
        return self._build_report(start=start, end=end, title="daily")

    def generate_weekly(self, day: datetime | None = None) -> dict:
        target = day or datetime.now(tz=timezone.utc)
        start = datetime(target.year, target.month, target.day, tzinfo=timezone.utc) - timedelta(
            days=target.weekday()
        )
        end = start + timedelta(days=7)
        return self._build_report(start=start, end=end, title="weekly")

    def _build_report(self, start: datetime, end: datetime, title: str) -> dict:
        records = self.repository.list_raw_appearances_between(start=start, end=end)
        hourly = Counter(record.seen_at.hour for record in records)
        peak_hour = max(hourly, key=lambda hour: hourly[hour]) if hourly else None
        unique_persons = len({record.person_id for record in records})
        total = len(records)
        return {
            "report_type": title,
            "start": start.isoformat(),
            "end": end.isoformat(),
            "total_appearances": total,
            "unique_persons": unique_persons,
            "peak_hour": peak_hour,
            "hourly_distribution": dict(sorted(hourly.items())),
        }
