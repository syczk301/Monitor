from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import numpy as np

from app.analytics.report import ReportService
from app.storage.mongo import MongoIdentityTemplateStore
from app.storage.mongo_repository import MongoRepository


@dataclass
class _UpdateResult:
    matched_count: int


@dataclass
class _DeleteResult:
    deleted_count: int


@dataclass
class _ReplaceResult:
    matched_count: int = 1


@dataclass
class _UpdateManyResult:
    matched_count: int


class FakeCursor:
    def __init__(self, docs: list[dict]) -> None:
        self.docs = [doc.copy() for doc in docs]

    def sort(self, field: str, direction: int) -> "FakeCursor":
        self.docs.sort(key=lambda doc: doc.get(field), reverse=direction < 0)
        return self

    def limit(self, count: int) -> "FakeCursor":
        self.docs = self.docs[:count]
        return self

    def __iter__(self):
        return iter([doc.copy() for doc in self.docs])


class FakeMongoCollection:
    def __init__(self) -> None:
        self.docs: dict[Any, dict] = {}

    def find(self, filter_doc: dict | None = None, projection: dict | None = None) -> FakeCursor:
        matched = [self._project(doc, projection) for doc in self.docs.values() if self._match(doc, filter_doc or {})]
        return FakeCursor(matched)

    def find_one(self, filter_doc: dict, projection: dict | None = None, sort: list[tuple[str, int]] | None = None) -> dict | None:
        docs = [doc for doc in self.docs.values() if self._match(doc, filter_doc)]
        if sort:
            field, direction = sort[0]
            docs.sort(key=lambda doc: doc.get(field), reverse=direction < 0)
        if not docs:
            return None
        return self._project(docs[0], projection)

    def insert_one(self, document: dict) -> None:
        self.docs[document["_id"]] = document.copy()

    def replace_one(self, filter_doc: dict, document: dict, upsert: bool = False) -> _ReplaceResult:
        assert upsert is True
        self.docs[filter_doc["_id"]] = document.copy()
        return _ReplaceResult()

    def update_one(self, filter_doc: dict, update_doc: dict) -> _UpdateResult:
        row = self.find_one(filter_doc)
        if row is None:
            return _UpdateResult(matched_count=0)
        target = self.docs[row["_id"]]
        for key, value in update_doc.get("$set", {}).items():
            target[key] = value
        return _UpdateResult(matched_count=1)

    def update_many(self, filter_doc: dict, update_doc: dict) -> _UpdateManyResult:
        matched_ids = [doc["_id"] for doc in self.docs.values() if self._match(doc, filter_doc)]
        for doc_id in matched_ids:
            for key, value in update_doc.get("$set", {}).items():
                self.docs[doc_id][key] = value
        return _UpdateManyResult(matched_count=len(matched_ids))

    def delete_one(self, filter_doc: dict) -> _DeleteResult:
        row = self.find_one(filter_doc)
        if row is None:
            return _DeleteResult(deleted_count=0)
        del self.docs[row["_id"]]
        return _DeleteResult(deleted_count=1)

    def delete_many(self, filter_doc: dict) -> _DeleteResult:
        matched_ids = [doc["_id"] for doc in self.docs.values() if self._match(doc, filter_doc)]
        for doc_id in matched_ids:
            del self.docs[doc_id]
        return _DeleteResult(deleted_count=len(matched_ids))

    def count_documents(self, filter_doc: dict) -> int:
        return sum(1 for doc in self.docs.values() if self._match(doc, filter_doc))

    def estimated_document_count(self) -> int:
        return len(self.docs)

    def find_one_and_update(self, filter_doc: dict, update_doc: dict, upsert: bool = False, return_document: Any | None = None) -> dict:
        row = self.find_one(filter_doc)
        if row is None:
            assert upsert is True
            row = {"_id": filter_doc["_id"], "value": 0}
        target = self.docs.setdefault(row["_id"], row.copy())
        for key, delta in update_doc.get("$inc", {}).items():
            target[key] = int(target.get(key, 0)) + int(delta)
        return target.copy()

    @staticmethod
    def _project(doc: dict, projection: dict | None) -> dict:
        if not projection:
            return doc.copy()
        projected = {"_id": doc["_id"]}
        for key, enabled in projection.items():
            if enabled and key in doc:
                projected[key] = doc[key]
        return projected

    @staticmethod
    def _match(doc: dict, filter_doc: dict) -> bool:
        for key, expected in filter_doc.items():
            actual = doc.get(key)
            if isinstance(expected, dict):
                if "$in" in expected and actual not in expected["$in"]:
                    return False
                if "$ne" in expected and actual == expected["$ne"]:
                    return False
                if "$gte" in expected and actual < expected["$gte"]:
                    return False
                if "$lt" in expected and actual >= expected["$lt"]:
                    return False
            elif actual != expected:
                return False
        return True


class FakeMongoDatabase:
    def __init__(self) -> None:
        self.collections: dict[str, FakeMongoCollection] = {}

    def __getitem__(self, name: str) -> FakeMongoCollection:
        return self.collections.setdefault(name, FakeMongoCollection())


def test_mongo_identity_template_store_roundtrip() -> None:
    collection = FakeMongoCollection()
    store = MongoIdentityTemplateStore(collection=collection)
    templates = {
        "person-a": [
            np.array([0.1, 0.2, 0.3], dtype=np.float32),
            np.array([0.4, 0.5, 0.6], dtype=np.float32),
        ],
        "person-b": [np.array([0.7, 0.8, 0.9], dtype=np.float32)],
    }

    saved = store.save_identity_templates(templates)
    loaded = store.load_identity_templates()

    assert saved == 3
    assert set(loaded) == {"person-a", "person-b"}
    assert np.allclose(loaded["person-a"][0], templates["person-a"][0])
    assert np.allclose(loaded["person-a"][1], templates["person-a"][1])
    assert np.allclose(loaded["person-b"][0], templates["person-b"][0])


def test_mongo_repository_supports_full_persistence_flow() -> None:
    repo = MongoRepository(database=FakeMongoDatabase(), dedupe_seconds=300)

    repo.upsert_event("person-a", 1_000)
    repo.upsert_event("person-a", 200_000)
    repo.upsert_event("person-a", 400_000)
    repo.upsert_event("person-b", 500_000)

    events = repo.list_events(limit=10)
    summary = repo.summary()

    visit_id = repo.create_visit("person-a", appeared_ms=1_000, source_track_id=7)
    repo.close_visit(visit_id, left_ms=6_000)
    repo.update_visit_note(visit_id, "VIP")
    assert repo.reopen_visit(visit_id, source_track_id=8)
    repo.close_visit(visit_id, left_ms=7_000)
    visits = repo.list_visits(limit=10)

    raw = repo.list_raw_appearances_between(
        start=datetime.fromtimestamp(0, tz=timezone.utc),
        end=datetime.fromtimestamp(1000, tz=timezone.utc),
    )

    roi = repo.add_roi([10, 20, 30, 40], params={"confidence": 0.17, "enhanced_upscale_factor": 4.2})
    updated_roi = repo.update_roi(roi["id"], bbox=[11, 21, 31, 41], params={"imgsz": 1280})
    templates = {"person-a": [np.array([0.1, 0.2, 0.3], dtype=np.float32)]}
    repo.save_identity_templates(templates)
    loaded_templates = repo.load_identity_templates()
    updated = repo.update_person_note("person-a", "常客")
    inherited_visit_id = repo.create_visit("person-a", appeared_ms=7_000, source_track_id=9)

    report = ReportService(repo).generate_daily(day=datetime(1970, 1, 1, tzinfo=timezone.utc))

    assert len(events) == 2
    assert events[0].person_id == "person-b"
    assert summary["unique_persons"] == 2.0
    assert len(visits) == 1
    assert visits[0].note == "VIP"
    assert visits[0].source_track_id == 8
    assert len(raw) == 3
    assert updated_roi is not None
    assert updated_roi["bbox"] == [11, 21, 31, 41]
    assert updated_roi["params"]["confidence"] == 0.17
    assert updated_roi["params"]["imgsz"] == 1280
    assert repo.list_rois() == [updated_roi]
    assert repo.delete_roi(roi["id"])
    assert np.allclose(loaded_templates["person-a"][0], templates["person-a"][0])
    assert updated == 1
    assert repo.get_visit_note(inherited_visit_id) == "常客"
    assert report["total_appearances"] == 3
    assert report["unique_persons"] == 2
