from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import numpy as np
from pymongo import MongoClient


class MongoIdentityTemplateStore:
    def __init__(self, collection: Any, client: MongoClient | None = None) -> None:
        self.collection = collection
        self.client = client

    @classmethod
    def from_uri(
        cls,
        *,
        uri: str,
        database: str,
        collection: str,
        server_selection_timeout_ms: int = 15_000,
    ) -> "MongoIdentityTemplateStore":
        client = MongoClient(uri, serverSelectionTimeoutMS=server_selection_timeout_ms)
        client.admin.command("ping")
        return cls(collection=client[database][collection], client=client)

    def load_identity_templates(self) -> dict[str, list[np.ndarray]]:
        templates_by_person: dict[str, list[np.ndarray]] = {}
        for doc in self.collection.find({}, {"templates": 1}):
            person_id = str(doc.get("_id", ""))
            if not person_id:
                continue
            rows = doc.get("templates", [])
            templates: list[np.ndarray] = []
            for row in rows:
                vec = np.asarray(row, dtype=np.float32).flatten()
                if vec.size:
                    templates.append(vec)
            if templates:
                templates_by_person[person_id] = templates
        return templates_by_person

    def save_identity_templates(self, templates_by_person: dict[str, list[np.ndarray]]) -> int:
        saved = 0
        updated_at = datetime.now(tz=timezone.utc)
        for person_id, templates in templates_by_person.items():
            payload = []
            for feature in templates:
                vec = np.asarray(feature, dtype=np.float32).flatten()
                if vec.size:
                    payload.append(vec.tolist())
            self.collection.replace_one(
                {"_id": person_id},
                {
                    "_id": person_id,
                    "templates": payload,
                    "template_count": len(payload),
                    "updated_at": updated_at,
                },
                upsert=True,
            )
            saved += len(payload)
        return saved
