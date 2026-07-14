from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from app.config import settings
from app.core.entities import Detection, TrackedObject


@dataclass(slots=True)
class TrackState:
    obj: TrackedObject
    missed: int = 0
    hits: int = 1
    age: int = 1
    last_detection_confidence: float = 0.0
    velocity_xy: tuple[float, float] = (0.0, 0.0)


def _iou(box_a: tuple[int, int, int, int], box_b: tuple[int, int, int, int]) -> float:
    ax1, ay1, ax2, ay2 = box_a
    bx1, by1, bx2, by2 = box_b
    inter_x1 = max(ax1, bx1)
    inter_y1 = max(ay1, by1)
    inter_x2 = min(ax2, bx2)
    inter_y2 = min(ay2, by2)
    inter_w = max(0, inter_x2 - inter_x1)
    inter_h = max(0, inter_y2 - inter_y1)
    inter_area = inter_w * inter_h
    area_a = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    area_b = max(0, bx2 - bx1) * max(0, by2 - by1)
    union = area_a + area_b - inter_area
    return inter_area / union if union > 0 else 0.0


def _bbox_center(box: tuple[int, int, int, int]) -> tuple[float, float]:
    x1, y1, x2, y2 = box
    return ((x1 + x2) / 2.0, (y1 + y2) / 2.0)


def _shift_bbox(
    box: tuple[int, int, int, int],
    dx: float,
    dy: float,
) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = box
    return (
        int(round(x1 + dx)),
        int(round(y1 + dy)),
        int(round(x2 + dx)),
        int(round(y2 + dy)),
    )


class MultiObjectTracker:
    def __init__(
        self,
        iou_threshold: float | None = None,
        max_missed: int = 45,
        high_confidence: float | None = None,
        low_confidence: float | None = None,
        new_track_confidence: float | None = None,
        low_conf_iou_threshold: float | None = None,
        display_max_missed: int | None = None,
        min_confirmed_hits: int | None = None,
    ) -> None:
        self.iou_threshold = (
            iou_threshold if iou_threshold is not None else settings.tracker_match_iou_threshold
        )
        self.max_missed = max_missed
        self.high_confidence = (
            high_confidence if high_confidence is not None else settings.tracker_high_confidence
        )
        self.low_confidence = low_confidence if low_confidence is not None else settings.tracker_low_confidence
        self.new_track_confidence = (
            new_track_confidence
            if new_track_confidence is not None
            else settings.tracker_new_track_confidence
        )
        self.low_conf_iou_threshold = (
            low_conf_iou_threshold
            if low_conf_iou_threshold is not None
            else settings.tracker_low_conf_iou_threshold
        )
        self.display_max_missed = (
            display_max_missed if display_max_missed is not None else settings.tracker_display_max_missed
        )
        self.min_confirmed_hits = (
            min_confirmed_hits if min_confirmed_hits is not None else settings.tracker_min_confirmed_hits
        )
        self._next_id = 1
        self._tracks: dict[int, TrackState] = {}

    def update(self, detections: list[Detection], ts_ms: int) -> list[TrackedObject]:
        now = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        for state in self._tracks.values():
            state.age += 1

        high_detections = [det for det in detections if det.confidence >= self.high_confidence]
        low_detections = [
            det for det in detections if self.low_confidence <= det.confidence < self.high_confidence
        ]

        track_ids = list(self._tracks)
        stage1_matches, unmatched_track_ids, unmatched_high = self._associate(
            track_ids=track_ids,
            detections=high_detections,
            iou_threshold=self.iou_threshold,
        )
        self._apply_matches(stage1_matches, high_detections, now)

        stage2_matches, unmatched_track_ids, _ = self._associate(
            track_ids=unmatched_track_ids,
            detections=low_detections,
            iou_threshold=self.low_conf_iou_threshold,
        )
        self._apply_matches(stage2_matches, low_detections, now)

        for track_id in unmatched_track_ids:
            state = self._tracks.get(track_id)
            if state is None:
                continue
            state.missed += 1
            if state.missed > self.max_missed:
                del self._tracks[track_id]

        for det in unmatched_high:
            if det.confidence < self.new_track_confidence or not det.can_start_track:
                continue
            self._create_track(det, now)

        active = [
            state.obj
            for state in self._tracks.values()
            if state.missed <= self.display_max_missed and self._should_display(state)
        ]
        return sorted(active, key=lambda obj: obj.track_id)

    def _associate(
        self,
        track_ids: list[int],
        detections: list[Detection],
        iou_threshold: float,
    ) -> tuple[list[tuple[int, int]], list[int], list[Detection]]:
        pairs: list[tuple[float, int, int]] = []
        for track_id in track_ids:
            state = self._tracks.get(track_id)
            if state is None:
                continue
            predicted_bbox = self._predicted_bbox(state)
            for det_idx, det in enumerate(detections):
                score = _iou(predicted_bbox, det.bbox)
                if score >= iou_threshold:
                    pairs.append((score + det.confidence * 0.001, track_id, det_idx))

        pairs.sort(reverse=True)
        matched_track_ids: set[int] = set()
        matched_det_indices: set[int] = set()
        matches: list[tuple[int, int]] = []
        for _, track_id, det_idx in pairs:
            if track_id in matched_track_ids or det_idx in matched_det_indices:
                continue
            matched_track_ids.add(track_id)
            matched_det_indices.add(det_idx)
            matches.append((track_id, det_idx))

        unmatched_track_ids = [track_id for track_id in track_ids if track_id not in matched_track_ids]
        unmatched_detections = [
            det for det_idx, det in enumerate(detections) if det_idx not in matched_det_indices
        ]
        return matches, unmatched_track_ids, unmatched_detections

    def _apply_matches(
        self,
        matches: list[tuple[int, int]],
        detections: list[Detection],
        now: datetime,
    ) -> None:
        for track_id, det_idx in matches:
            state = self._tracks.get(track_id)
            if state is None:
                continue
            det = detections[det_idx]
            prev_cx, prev_cy = _bbox_center(state.obj.bbox)
            next_cx, next_cy = _bbox_center(det.bbox)
            state.velocity_xy = (next_cx - prev_cx, next_cy - prev_cy)
            state.obj.bbox = det.bbox
            state.obj.last_seen = now
            state.obj.trajectory.append((int(round(next_cx)), int(round(next_cy))))
            state.missed = 0
            state.hits += 1
            state.last_detection_confidence = det.confidence
            state.obj.detection_confidence = det.confidence
            state.obj.detection_source = det.source

    def _create_track(self, det: Detection, now: datetime) -> None:
        cx, cy = _bbox_center(det.bbox)
        obj = TrackedObject(
            track_id=self._next_id,
            bbox=det.bbox,
            first_seen=now,
            last_seen=now,
            trajectory=[(int(round(cx)), int(round(cy)))],
            feature=None,
            person_id=None,
            detection_confidence=det.confidence,
            detection_source=det.source,
        )
        self._tracks[self._next_id] = TrackState(
            obj=obj,
            missed=0,
            hits=1,
            age=1,
            last_detection_confidence=det.confidence,
            velocity_xy=(0.0, 0.0),
        )
        self._next_id += 1

    def _predicted_bbox(self, state: TrackState) -> tuple[int, int, int, int]:
        dx, dy = state.velocity_xy
        prediction_scale = min(2, max(1, state.missed + 1))
        return _shift_bbox(state.obj.bbox, dx * prediction_scale, dy * prediction_scale)

    def _should_display(self, state: TrackState) -> bool:
        return state.hits >= self.min_confirmed_hits

    @property
    def active_tracks(self) -> list[TrackedObject]:
        return [state.obj for state in self._tracks.values()]
