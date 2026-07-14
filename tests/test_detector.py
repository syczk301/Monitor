from __future__ import annotations

import cv2
import numpy as np

from app.config import settings
from app.core.detector import PersonDetector


def test_scene_windows_stay_within_frame_bounds() -> None:
    boxes = PersonDetector._scene_windows((720, 1280))

    assert boxes
    for x1, y1, x2, y2 in boxes:
        assert 0 <= x1 < x2 <= 1280
        assert 0 <= y1 < y2 <= 720


def test_select_candidate_boxes_prefers_distinct_boxes_and_skips_manual_overlap() -> None:
    candidates = [
        (1.0, (100, 120, 320, 360)),
        (0.9, (110, 130, 330, 370)),
        (0.8, (700, 180, 940, 420)),
        (0.7, (960, 210, 1180, 430)),
    ]
    selected = PersonDetector._select_candidate_boxes(
        candidates,
        excluded_boxes=[(90, 110, 340, 390)],
        max_count=3,
    )

    assert selected == [(700, 180, 940, 420), (960, 210, 1180, 430)]


def test_auto_roi_params_use_auto_defaults() -> None:
    params = PersonDetector._roi_params({"auto": True, "params": {}})

    assert params["confidence"] >= 0.3
    assert params["imgsz"] >= 960
    assert params["enhanced_upscale_factor"] > params["upscale_factor"]


def test_auto_detection_filter_rejects_small_wide_false_positive() -> None:
    valid = PersonDetector._passes_auto_detection_filter(
        bbox=(820, 410, 900, 560),
        roi_box=(780, 360, 980, 620),
        frame_shape=(720, 1280),
        confidence=max(0.5, settings.auto_roi_min_detection_confidence),
    )
    invalid = PersonDetector._passes_auto_detection_filter(
        bbox=(1040, 520, 1140, 570),
        roi_box=(980, 420, 1180, 640),
        frame_shape=(720, 1280),
        confidence=max(0.5, settings.auto_roi_min_detection_confidence),
    )

    assert valid is True
    assert invalid is False


def test_furniture_conflict_rejects_weaker_person_candidate() -> None:
    people = [((100, 100, 260, 420), 0.65)]
    furniture = [((90, 90, 270, 430), 0.78)]

    assert PersonDetector._suppress_furniture_conflicts(people, furniture) == []


def test_furniture_conflict_keeps_strong_person_candidate() -> None:
    people = [((100, 100, 260, 420), 0.92)]
    furniture = [((90, 160, 270, 430), 0.74)]

    assert PersonDetector._suppress_furniture_conflicts(people, furniture) == people


def test_cross_scale_person_deduplication_rejects_contained_upper_body_box() -> None:
    full_body = (100, 100, 300, 600)
    upper_body = (120, 110, 280, 360)

    assert PersonDetector._overlaps_existing_person(upper_body, [full_body]) is True


def test_motion_candidates_are_mapped_back_from_reduced_analysis_frame() -> None:
    class _Background:
        def apply(self, gray: np.ndarray, learningRate: float) -> np.ndarray:
            assert gray.shape[1] == settings.motion_analysis_max_width
            mask = np.zeros_like(gray)
            cv2.rectangle(mask, (120, 100), (200, 240), 255, thickness=-1)
            return mask

    detector = object.__new__(PersonDetector)
    detector._bg_subtractor = _Background()
    frame = np.zeros((1440, 2560, 3), dtype=np.uint8)

    boxes = detector._motion_candidates(frame)

    assert boxes
    x1, y1, x2, y2 = boxes[0]
    assert 0 <= x1 < x2 <= 2560
    assert 0 <= y1 < y2 <= 1440
    assert x1 < 120 * 4 < x2
    assert y1 < 100 * 4 < y2
