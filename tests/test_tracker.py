from __future__ import annotations

from app.core.entities import Detection
from app.core.tracker import MultiObjectTracker


def test_tracker_assigns_stable_id() -> None:
    tracker = MultiObjectTracker(iou_threshold=0.1, max_missed=2, min_confirmed_hits=2)
    first = tracker.update([Detection(bbox=(10, 10, 50, 70), confidence=0.9, class_id=0)], 1_000)
    second = tracker.update([Detection(bbox=(12, 12, 52, 72), confidence=0.9, class_id=0)], 1_100)
    assert first == []
    assert len(second) == 1
    assert second[0].track_id == 1


def test_tracker_removes_lost_tracks() -> None:
    tracker = MultiObjectTracker(iou_threshold=0.2, max_missed=1)
    tracker.update([Detection(bbox=(0, 0, 10, 10), confidence=0.8, class_id=0)], 1_000)
    tracker.update([], 1_100)
    tracker.update([], 1_200)
    assert len(tracker.active_tracks) == 0


def test_tracker_recovers_track_with_low_confidence_detection() -> None:
    tracker = MultiObjectTracker(
        iou_threshold=0.2,
        low_confidence=0.1,
        high_confidence=0.5,
        new_track_confidence=0.6,
        max_missed=2,
        min_confirmed_hits=1,
    )
    first = tracker.update([Detection(bbox=(100, 100, 160, 220), confidence=0.92, class_id=0)], 1_000)
    second = tracker.update([Detection(bbox=(104, 102, 164, 222), confidence=0.22, class_id=0)], 1_100)

    assert len(first) == 1
    assert len(second) == 1
    assert first[0].track_id == second[0].track_id


def test_tracker_uses_prediction_to_keep_same_id_after_short_gap() -> None:
    tracker = MultiObjectTracker(
        iou_threshold=0.2,
        low_confidence=0.1,
        high_confidence=0.5,
        new_track_confidence=0.6,
        max_missed=3,
        min_confirmed_hits=1,
    )
    first = tracker.update([Detection(bbox=(50, 60, 110, 180), confidence=0.9, class_id=0)], 1_000)
    tracker.update([Detection(bbox=(60, 60, 120, 180), confidence=0.88, class_id=0)], 1_100)
    tracker.update([], 1_200)
    fourth = tracker.update([Detection(bbox=(72, 60, 132, 180), confidence=0.82, class_id=0)], 1_300)

    assert len(first) == 1
    assert len(fourth) == 1
    assert first[0].track_id == fourth[0].track_id


def test_tracker_does_not_create_track_from_hold_only_detection() -> None:
    tracker = MultiObjectTracker(min_confirmed_hits=1)

    tracks = tracker.update(
        [
            Detection(
                bbox=(100, 100, 220, 420),
                confidence=0.95,
                class_id=0,
                source="occupancy",
                can_start_track=False,
            )
        ],
        1_000,
    )

    assert tracks == []
    assert tracker.active_tracks == []
