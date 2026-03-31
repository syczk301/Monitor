from __future__ import annotations

from app.audio_stream import AudioStreamConfig, MicrophoneAudioStreamer
from app.analytics.report import ReportService
from app.config import settings
from app.core.pipeline import VideoAnalyticsPipeline
from app.storage.database import create_sqlite_session
from app.storage.mongo import MongoIdentityTemplateStore
from app.storage.mongo_repository import MongoRepository
from app.storage.repository import PersonEventRepository


class AppServices:
    def __init__(self) -> None:
        self.session_factory = None
        self.identity_store = None

        if settings.use_mongodb_storage:
            try:
                self.repository = MongoRepository.from_uri(
                    uri=settings.mongodb_uri,
                    database=settings.mongodb_database,
                    dedupe_seconds=settings.dedupe_window_seconds,
                )
                self.identity_store = self.repository
                print(f"MongoDB storage enabled: {settings.mongodb_database}")
            except Exception as exc:
                print(f"MongoDB storage unavailable, fallback to SQLite: {exc!s}")
                self.session_factory = create_sqlite_session(settings.db_path)
                self.repository = PersonEventRepository(self.session_factory)
                self.identity_store = self.repository
        else:
            self.session_factory = create_sqlite_session(settings.db_path)
            self.repository = PersonEventRepository(self.session_factory)
            self.identity_store = self.repository

        if not settings.use_mongodb_storage and settings.use_mongodb_for_identity_templates:
            try:
                self.identity_store = MongoIdentityTemplateStore.from_uri(
                    uri=settings.mongodb_uri,
                    database=settings.mongodb_database,
                    collection=settings.mongodb_identity_collection,
                )
                print(
                    f"MongoDB identity store enabled: "
                    f"{settings.mongodb_database}.{settings.mongodb_identity_collection}"
                )
            except Exception as exc:
                print(f"MongoDB identity store unavailable, fallback to primary storage: {exc!s}")
                self.identity_store = self.repository

        self.reports = ReportService(self.repository)
        self.audio = MicrophoneAudioStreamer(
            AudioStreamConfig(
                sample_rate=settings.audio_sample_rate,
                channels=settings.audio_channels,
                block_frames=settings.audio_block_frames,
            )
        )
        self.pipeline = VideoAnalyticsPipeline(
            repository=self.repository,
            identity_store=self.identity_store,
        )


services = AppServices()
