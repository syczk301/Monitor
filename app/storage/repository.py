from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
from sqlalchemy import func, select

from app.config import settings
from app.storage.database import IdentityTemplate, PersonEvent, RawAppearance, VisitRecord, ms_to_dt


class PersonEventRepository:
    def __init__(self, session_factory, dedupe_seconds: int | None = None) -> None:
        self.session_factory = session_factory
        self.dedupe_seconds = dedupe_seconds if dedupe_seconds is not None else settings.dedupe_window_seconds

    def upsert_event(self, person_id: str, timestamp_ms: int) -> None:
        at = ms_to_dt(timestamp_ms)
        with self.session_factory() as session:
            last_raw_stmt = (
                select(RawAppearance)
                .where(RawAppearance.person_id == person_id)
                .order_by(RawAppearance.seen_at.desc())
                .limit(1)
            )
            last_raw = session.scalar(last_raw_stmt)
            if last_raw is not None:
                last_seen_at = self._ensure_aware(last_raw.seen_at)
                if at - last_seen_at < timedelta(seconds=self.dedupe_seconds):
                    event = session.get(PersonEvent, person_id)
                    if event is not None:
                        event.last_seen = max(self._ensure_aware(event.last_seen), at)
                        event.dwell_seconds = max(
                            0.0,
                            (
                                self._ensure_aware(event.last_seen) - self._ensure_aware(event.first_seen)
                            ).total_seconds(),
                        )
                        session.commit()
                    return
            session.add(RawAppearance(person_id=person_id, seen_at=at))
            event = session.get(PersonEvent, person_id)
            if event is None:
                event = PersonEvent(
                    person_id=person_id,
                    first_seen=at,
                    last_seen=at,
                    dwell_seconds=0.0,
                    appearance_count=1,
                )
                session.add(event)
            else:
                event.last_seen = at
                event.appearance_count += 1
                event.dwell_seconds = max(
                    0.0,
                    (self._ensure_aware(event.last_seen) - self._ensure_aware(event.first_seen)).total_seconds(),
                )
            session.commit()

    @staticmethod
    def _ensure_aware(value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    def list_events(self, limit: int = 200) -> list[PersonEvent]:
        with self.session_factory() as session:
            stmt = select(PersonEvent).order_by(PersonEvent.last_seen.desc()).limit(limit)
            return list(session.scalars(stmt))

    def create_visit(self, person_id: str, appeared_ms: int, source_track_id: int | None = None) -> int:
        appeared_at = ms_to_dt(appeared_ms)
        inherited_note = self.get_person_note(person_id)
        with self.session_factory() as session:
            row = VisitRecord(
                person_id=person_id,
                source_track_id=source_track_id,
                appeared_at=appeared_at,
                left_at=None,
                stay_seconds=0.0,
                note=inherited_note,
            )
            session.add(row)
            session.commit()
            session.refresh(row)
            return row.id

    def close_visit(self, visit_id: int, left_ms: int) -> None:
        left_at = ms_to_dt(left_ms)
        with self.session_factory() as session:
            row = session.get(VisitRecord, visit_id)
            if row is None:
                return
            row.left_at = left_at
            row.stay_seconds = max(
                0.0,
                (self._ensure_aware(row.left_at) - self._ensure_aware(row.appeared_at)).total_seconds(),
            )
            session.commit()

    def reopen_visit(self, visit_id: int, source_track_id: int | None = None) -> bool:
        with self.session_factory() as session:
            row = session.get(VisitRecord, visit_id)
            if row is None:
                return False
            row.left_at = None
            row.stay_seconds = 0.0
            row.source_track_id = source_track_id
            session.commit()
            return True

    def list_visits(self, limit: int = 200) -> list[VisitRecord]:
        with self.session_factory() as session:
            stmt = select(VisitRecord).order_by(VisitRecord.appeared_at.desc()).limit(limit)
            return list(session.scalars(stmt))

    def update_visit_note(self, visit_id: int, note: str) -> bool:
        with self.session_factory() as session:
            row = session.get(VisitRecord, visit_id)
            if row is None:
                return False
            row.note = note
            session.commit()
            return True

    def get_visit_note(self, visit_id: int) -> str:
        with self.session_factory() as session:
            row = session.get(VisitRecord, visit_id)
            return row.note if row else ""

    def get_person_note(self, person_id: str) -> str:
        with self.session_factory() as session:
            stmt = (
                select(VisitRecord)
                .where(VisitRecord.person_id == person_id, VisitRecord.note != "")
                .order_by(VisitRecord.appeared_at.desc())
                .limit(1)
            )
            row = session.scalar(stmt)
            return row.note if row else ""

    def update_person_note(self, person_id: str, note: str) -> int:
        with self.session_factory() as session:
            stmt = select(VisitRecord).where(VisitRecord.person_id == person_id)
            rows = session.scalars(stmt).all()
            for row in rows:
                row.note = note
            session.commit()
            return len(rows)

    def delete_visit(self, visit_id: int) -> bool:
        with self.session_factory() as session:
            row = session.get(VisitRecord, visit_id)
            if row is None:
                return False
            session.delete(row)
            session.commit()
            return True

    def delete_visits(self, visit_ids: list[int]) -> int:
        with self.session_factory() as session:
            stmt = select(VisitRecord).where(VisitRecord.id.in_(visit_ids))
            rows = session.scalars(stmt).all()
            count = 0
            for row in rows:
                session.delete(row)
                count += 1
            session.commit()
            return count

    def summary(self) -> dict[str, float]:
        with self.session_factory() as session:
            count_stmt = select(func.count(PersonEvent.person_id))
            avg_stmt = select(func.avg(PersonEvent.dwell_seconds))
            total = session.scalar(count_stmt) or 0
            avg = session.scalar(avg_stmt) or 0.0
            return {"unique_persons": float(total), "avg_dwell_seconds": float(avg)}

    def load_identity_templates(self) -> dict[str, list[np.ndarray]]:
        with self.session_factory() as session:
            stmt = select(IdentityTemplate).order_by(IdentityTemplate.person_id, IdentityTemplate.template_index)
            rows = list(session.scalars(stmt))
        templates: dict[str, list[np.ndarray]] = {}
        for row in rows:
            feature = np.frombuffer(row.feature_blob, dtype=np.float32)
            if feature.size != row.feature_dim:
                continue
            templates.setdefault(row.person_id, []).append(feature.copy())
        return templates

    def save_identity_templates(self, templates_by_person: dict[str, list[np.ndarray]]) -> int:
        if not templates_by_person:
            return 0
        person_ids = list(templates_by_person)
        updated_at = datetime.now(tz=timezone.utc)
        with self.session_factory() as session:
            stmt = select(IdentityTemplate).where(IdentityTemplate.person_id.in_(person_ids))
            for row in session.scalars(stmt):
                session.delete(row)
            saved = 0
            for person_id, templates in templates_by_person.items():
                for idx, feature in enumerate(templates):
                    vec = np.asarray(feature, dtype=np.float32).flatten()
                    if vec.size == 0:
                        continue
                    session.add(
                        IdentityTemplate(
                            person_id=person_id,
                            template_index=idx,
                            feature_blob=vec.tobytes(),
                            feature_dim=int(vec.size),
                            updated_at=updated_at,
                        )
                    )
                    saved += 1
            session.commit()
            return saved

    def list_raw_appearances_between(self, start: datetime, end: datetime) -> list[RawAppearance]:
        with self.session_factory() as session:
            stmt = (
                select(RawAppearance)
                .where(RawAppearance.seen_at >= start, RawAppearance.seen_at < end)
                .order_by(RawAppearance.seen_at.asc())
            )
            return list(session.scalars(stmt))

    def list_rois(self) -> list[dict]:
        path = Path("data/rois.json")
        if not path.exists():
            return []
        try:
            rois = json.loads(path.read_text())
            return [self._normalize_roi(roi) for roi in rois]
        except Exception:
            return []

    def add_roi(self, bbox: list[int], params: dict | None = None) -> dict:
        rois = self.list_rois()
        roi = self._normalize_roi(
            {
                "id": int(datetime.now(tz=timezone.utc).timestamp() * 1000),
                "bbox": bbox,
                "params": params or {},
            }
        )
        rois.append(roi)
        path = Path("data/rois.json")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(rois))
        return roi

    def update_roi(self, roi_id: int, params: dict | None = None, bbox: list[int] | None = None) -> dict | None:
        rois = self.list_rois()
        updated: dict | None = None
        for roi in rois:
            if roi.get("id") != roi_id:
                continue
            if bbox is not None:
                roi["bbox"] = bbox
            if params is not None:
                merged = dict(roi.get("params", {}))
                merged.update(params)
                roi["params"] = merged
            updated = self._normalize_roi(roi)
            roi.update(updated)
            break
        if updated is None:
            return None
        path = Path("data/rois.json")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(rois))
        return updated

    def delete_roi(self, roi_id: int) -> bool:
        rois = self.list_rois()
        filtered = [roi for roi in rois if roi.get("id") != roi_id]
        path = Path("data/rois.json")
        if not path.exists() and not filtered:
            return False
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(filtered))
        return len(filtered) != len(rois)

    @staticmethod
    def _normalize_roi(roi: dict) -> dict:
        params = roi.get("params", {}) or {}
        return {
            "id": int(roi.get("id", 0)),
            "bbox": [int(v) for v in roi.get("bbox", [0, 0, 0, 0])],
            "params": {
                "confidence": float(params.get("confidence", settings.roi_detector_confidence)),
                "imgsz": int(params.get("imgsz", settings.roi_detector_imgsz)),
                "upscale_factor": float(params.get("upscale_factor", settings.roi_upscale_factor)),
                "enhanced_upscale_factor": float(
                    params.get("enhanced_upscale_factor", settings.roi_enhanced_upscale_factor)
                ),
            },
        }
