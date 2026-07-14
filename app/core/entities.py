from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

import numpy as np


@dataclass(slots=True)
class Detection:
    bbox: tuple[int, int, int, int]
    confidence: float
    class_id: int
    source: str = "model"
    can_start_track: bool = True


@dataclass(slots=True)
class TrackedObject:
    track_id: int
    bbox: tuple[int, int, int, int]
    first_seen: datetime
    last_seen: datetime
    trajectory: list[tuple[int, int]] = field(default_factory=list)
    feature: np.ndarray | None = None
    person_id: str | None = None
    detection_confidence: float = 0.0
    detection_source: str = "model"


@dataclass(slots=True)
class FramePacket:
    frame_id: int
    ts_ms: int
    frame: np.ndarray


@dataclass(slots=True)
class InferencePacket:
    frame_id: int
    ts_ms: int
    tracks: list[TrackedObject]
    encoded_frame: bytes
