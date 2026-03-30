from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import DateTime, Float, Integer, LargeBinary, String, UniqueConstraint, create_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker


class Base(DeclarativeBase):
    pass


class PersonEvent(Base):
    __tablename__ = "person_events"

    person_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    first_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_seen: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    dwell_seconds: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    appearance_count: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class RawAppearance(Base):
    __tablename__ = "raw_appearances"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    person_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True, nullable=False)


class VisitRecord(Base):
    __tablename__ = "visit_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    person_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    source_track_id: Mapped[int | None] = mapped_column(Integer, index=True, nullable=True)
    appeared_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True, nullable=False)
    left_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True, nullable=True)
    stay_seconds: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    note: Mapped[str] = mapped_column(String(500), nullable=False, default="")


class IdentityTemplate(Base):
    __tablename__ = "identity_templates"
    __table_args__ = (UniqueConstraint("person_id", "template_index", name="uq_identity_template_slot"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    person_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    template_index: Mapped[int] = mapped_column(Integer, nullable=False)
    feature_blob: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    feature_dim: Mapped[int] = mapped_column(Integer, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


def create_sqlite_session(db_path: Path) -> sessionmaker:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(f"sqlite:///{db_path}", future=True)
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)


def ms_to_dt(timestamp_ms: int) -> datetime:
    return datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc)
