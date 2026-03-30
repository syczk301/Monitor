from __future__ import annotations

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

    assert params["confidence"] < 0.2
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
