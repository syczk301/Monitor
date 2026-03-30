from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import numpy as np
from pymongo import MongoClient, ReturnDocument

from app.config import settings
from app.storage.database import ms_to_dt


@dataclass(slots=True)
class PersonEventRow:
    person_id: str
    first_seen: datetime
    last_seen: datetime
    dwell_seconds: float
    appearance_count: int


@dataclass(slots=True)
class RawAppearanceRow:
    id: int
    person_id: str
    seen_at: datetime


@dataclass(slots=True)
class VisitRecordRow:
    id: int
    person_id: str
    source_track_id: int | None
    appeared_at: datetime
    left_at: datetime | None
    stay_seconds: float
    note: str


class MongoRepository:
    def __init__(self, database: Any, client: MongoClient | None = None, dedupe_seconds: int | None = None) -> None:
        self.database = database
        self.client = client
        self.dedupe_seconds = dedupe_seconds if dedupe_seconds is not None else settings.dedupe_window_seconds
        self.person_events = database["person_events"]
        self.raw_appearances = database["raw_appearances"]
        self.visit_records = database["visit_records"]
        self.identity_templates = database[settings.mongodb_identity_collection]
        self.person_notes = database["person_notes"]
        self.rois = database["rois"]
        self.counters = database["counters"]

    @classmethod
    def from_uri(
        cls,
        *,
        uri: str,
        database: str,
        dedupe_seconds: int | None = None,
        server_selection_timeout_ms: int = 15_000,
    ) -> "MongoRepository":
        client = MongoClient(uri, serverSelectionTimeoutMS=server_selection_timeout_ms)
        client.admin.command("ping")
        return cls(database=client[database], client=client, dedupe_seconds=dedupe_seconds)

    def upsert_event(self, person_id: str, timestamp_ms: int) -> None:
        at = ms_to_dt(timestamp_ms)
        last_raw = self.raw_appearances.find_one({"person_id": person_id}, sort=[("seen_at", -1)])
        if last_raw is not None:
            last_seen_at = self._ensure_aware(last_raw["seen_at"])
            if at - last_seen_at < timedelta(seconds=self.dedupe_seconds):
                event = self.person_events.find_one({"_id": person_id})
                if event is not None:
                    first_seen = self._ensure_aware(event["first_seen"])
                    last_seen = max(self._ensure_aware(event["last_seen"]), at)
                    self.person_events.update_one(
                        {"_id": person_id},
                        {"$set": {"last_seen": last_seen, "dwell_seconds": max(0.0, (last_seen - first_seen).total_seconds())}},
                    )
                return

        self.raw_appearances.insert_one(
            {
                "_id": self._next_id("raw_appearances"),
                "person_id": person_id,
                "seen_at": at,
            }
        )
        event = self.person_events.find_one({"_id": person_id})
        if event is None:
            payload = {
                "_id": person_id,
                "first_seen": at,
                "last_seen": at,
                "dwell_seconds": 0.0,
                "appearance_count": 1,
            }
        else:
            first_seen = self._ensure_aware(event["first_seen"])
            payload = {
                "_id": person_id,
                "first_seen": first_seen,
                "last_seen": at,
                "dwell_seconds": max(0.0, (at - first_seen).total_seconds()),
                "appearance_count": int(event.get("appearance_count", 0)) + 1,
            }
        self.person_events.replace_one({"_id": person_id}, payload, upsert=True)

    def list_events(self, limit: int = 200) -> list[PersonEventRow]:
        docs = self.person_events.find({}).sort("last_seen", -1).limit(limit)
        return [
            PersonEventRow(
                person_id=str(doc["_id"]),
                first_seen=self._ensure_aware(doc["first_seen"]),
                last_seen=self._ensure_aware(doc["last_seen"]),
                dwell_seconds=float(doc.get("dwell_seconds", 0.0)),
                appearance_count=int(doc.get("appearance_count", 0)),
            )
            for doc in docs
        ]

    def create_visit(self, person_id: str, appeared_ms: int, source_track_id: int | None = None) -> int:
        visit_id = self._next_id("visit_records")
        inherited_note = self.get_person_note(person_id)
        self.visit_records.insert_one(
            {
                "_id": visit_id,
                "person_id": person_id,
                "source_track_id": source_track_id,
                "appeared_at": ms_to_dt(appeared_ms),
                "left_at": None,
                "stay_seconds": 0.0,
                "note": inherited_note,
            }
        )
        return visit_id

    def close_visit(self, visit_id: int, left_ms: int) -> None:
        row = self.visit_records.find_one({"_id": visit_id})
        if row is None:
            return
        left_at = ms_to_dt(left_ms)
        appeared_at = self._ensure_aware(row["appeared_at"])
        self.visit_records.update_one(
            {"_id": visit_id},
            {"$set": {"left_at": left_at, "stay_seconds": max(0.0, (left_at - appeared_at).total_seconds())}},
        )

    def reopen_visit(self, visit_id: int, source_track_id: int | None = None) -> bool:
        result = self.visit_records.update_one(
            {"_id": visit_id},
            {"$set": {"left_at": None, "stay_seconds": 0.0, "source_track_id": source_track_id}},
        )
        return bool(result.matched_count)

    def list_visits(self, limit: int = 200) -> list[VisitRecordRow]:
        docs = self.visit_records.find({}).sort("appeared_at", -1).limit(limit)
        return [self._visit_from_doc(doc) for doc in docs]

    def update_visit_note(self, visit_id: int, note: str) -> bool:
        result = self.visit_records.update_one({"_id": visit_id}, {"$set": {"note": note}})
        return bool(result.matched_count)

    def get_visit_note(self, visit_id: int) -> str:
        row = self.visit_records.find_one({"_id": visit_id}, {"note": 1})
        return str(row.get("note", "")) if row else ""

    def get_person_note(self, person_id: str) -> str:
        row = self.person_notes.find_one({"_id": person_id}, {"note": 1})
        if row:
            return str(row.get("note", ""))
        latest = self.visit_records.find_one(
            {"person_id": person_id, "note": {"$ne": ""}},
            {"note": 1},
            sort=[("appeared_at", -1)],
        )
        return str(latest.get("note", "")) if latest else ""

    def update_person_note(self, person_id: str, note: str) -> int:
        self.person_notes.replace_one(
            {"_id": person_id},
            {"_id": person_id, "note": note, "updated_at": datetime.now(tz=timezone.utc)},
            upsert=True,
        )
        result = self.visit_records.update_many({"person_id": person_id}, {"$set": {"note": note}})
        return int(result.matched_count)

    def delete_visit(self, visit_id: int) -> bool:
        result = self.visit_records.delete_one({"_id": visit_id})
        return bool(result.deleted_count)

    def delete_visits(self, visit_ids: list[int]) -> int:
        result = self.visit_records.delete_many({"_id": {"$in": visit_ids}})
        return int(result.deleted_count)

    def summary(self) -> dict[str, float]:
        total = self.person_events.count_documents({})
        dwell_values = [float(doc.get("dwell_seconds", 0.0)) for doc in self.person_events.find({}, {"dwell_seconds": 1})]
        avg = (sum(dwell_values) / len(dwell_values)) if dwell_values else 0.0
        return {"unique_persons": float(total), "avg_dwell_seconds": float(avg)}

    def load_identity_templates(self) -> dict[str, list[np.ndarray]]:
        templates_by_person: dict[str, list[np.ndarray]] = {}
        for doc in self.identity_templates.find({}, {"templates": 1}):
            rows = doc.get("templates", [])
            templates = [np.asarray(row, dtype=np.float32).flatten() for row in rows if row]
            if templates:
                templates_by_person[str(doc["_id"])] = templates
        return templates_by_person

    def save_identity_templates(self, templates_by_person: dict[str, list[np.ndarray]]) -> int:
        saved = 0
        updated_at = datetime.now(tz=timezone.utc)
        for person_id, templates in templates_by_person.items():
            payload = [np.asarray(feature, dtype=np.float32).flatten().tolist() for feature in templates if np.asarray(feature).size]
            self.identity_templates.replace_one(
                {"_id": person_id},
                {"_id": person_id, "templates": payload, "template_count": len(payload), "updated_at": updated_at},
                upsert=True,
            )
            saved += len(payload)
        return saved

    def list_raw_appearances_between(self, start: datetime, end: datetime) -> list[RawAppearanceRow]:
        docs = self.raw_appearances.find({"seen_at": {"$gte": start, "$lt": end}}).sort("seen_at", 1)
        return [
            RawAppearanceRow(
                id=int(doc["_id"]),
                person_id=str(doc["person_id"]),
                seen_at=self._ensure_aware(doc["seen_at"]),
            )
            for doc in docs
        ]

    def list_rois(self) -> list[dict]:
        docs = self.rois.find({}).sort("_id", 1)
        return [self._normalize_roi({"id": int(doc["_id"]), "bbox": list(doc.get("bbox", [])), "params": doc.get("params", {})}) for doc in docs]

    def add_roi(self, bbox: list[int], params: dict | None = None) -> dict:
        roi_id = self._next_id("rois")
        roi = self._normalize_roi({"id": roi_id, "bbox": bbox, "params": params or {}})
        self.rois.replace_one({"_id": roi_id}, {"_id": roi_id, "bbox": roi["bbox"], "params": roi["params"]}, upsert=True)
        return roi

    def update_roi(self, roi_id: int, params: dict | None = None, bbox: list[int] | None = None) -> dict | None:
        current = self.rois.find_one({"_id": roi_id})
        if current is None:
            return None
        roi = {"id": roi_id, "bbox": current.get("bbox", []), "params": current.get("params", {})}
        if bbox is not None:
            roi["bbox"] = bbox
        if params is not None:
            merged = dict(roi.get("params", {}))
            merged.update(params)
            roi["params"] = merged
        normalized = self._normalize_roi(roi)
        self.rois.replace_one(
            {"_id": roi_id},
            {"_id": roi_id, "bbox": normalized["bbox"], "params": normalized["params"]},
            upsert=True,
        )
        return normalized

    def delete_roi(self, roi_id: int) -> bool:
        result = self.rois.delete_one({"_id": roi_id})
        return bool(result.deleted_count)

    def _next_id(self, name: str) -> int:
        row = self.counters.find_one_and_update(
            {"_id": name},
            {"$inc": {"value": 1}},
            upsert=True,
            return_document=ReturnDocument.AFTER,
        )
        return int(row["value"])

    @staticmethod
    def _ensure_aware(value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    def _visit_from_doc(self, doc: dict) -> VisitRecordRow:
        return VisitRecordRow(
            id=int(doc["_id"]),
            person_id=str(doc["person_id"]),
            source_track_id=doc.get("source_track_id"),
            appeared_at=self._ensure_aware(doc["appeared_at"]),
            left_at=self._ensure_aware(doc["left_at"]) if doc.get("left_at") else None,
            stay_seconds=float(doc.get("stay_seconds", 0.0)),
            note=str(doc.get("note", "")),
        )

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
