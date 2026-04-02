import os
from pathlib import Path

from pydantic import BaseModel, Field


def _load_dotenv(dotenv_path: str = ".env") -> None:
    path = Path(dotenv_path)
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        os.environ.setdefault(key, value)


_load_dotenv()


def _env_flag(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


class Settings(BaseModel):
    app_name: str = "camera-monitor"
    db_path: Path = Path("data/monitor.db")
    stream_source: str = "0"
    max_latency_ms: int = 500
    dedupe_window_seconds: int = 300
    reid_similarity_threshold: float = 0.32
    reid_recent_similarity_threshold: float = 0.55
    reid_recent_window_ms: int = 10_000
    reid_recent_distance_px: int = 280
    reid_persist_interval_seconds: int = 5
    visit_create_min_seen_frames: int = 3
    visit_create_min_duration_ms: int = 600
    visit_merge_window_ms: int = 12_000
    visit_discard_under_seconds: float = 1.2
    use_mongodb_storage: bool = _env_flag("USE_MONGODB_STORAGE", default=False)
    use_mongodb_for_identity_templates: bool = _env_flag("USE_MONGODB_IDENTITY_TEMPLATES", default=False)
    mongodb_uri: str = os.getenv("MONGODB_URI", "mongodb://127.0.0.1:27017")
    mongodb_database: str = os.getenv("MONGODB_DATABASE", "camera_monitor")
    mongodb_identity_collection: str = os.getenv("MONGODB_IDENTITY_COLLECTION", "identity_templates")
    detector_model: str = os.getenv("DETECTOR_MODEL", "yolo26s.pt")
    detector_confidence: float = 0.50
    detector_iou: float = 0.45
    auto_roi_enabled: bool = _env_flag("AUTO_ROI_ENABLED", default=True)
    auto_roi_detector_confidence: float = 0.14
    auto_roi_detector_imgsz: int = 1280
    auto_roi_upscale_factor: float = 3.6
    auto_roi_enhanced_upscale_factor: float = 4.4
    auto_roi_memory_ms: int = 12_000
    auto_roi_min_detection_confidence: float = 0.34
    auto_roi_use_scene_windows: bool = _env_flag("AUTO_ROI_USE_SCENE_WINDOWS", default=False)
    auto_roi_band_top_ratio: float = 0.22
    auto_roi_band_bottom_ratio: float = 0.92
    auto_roi_window_width_ratio: float = 0.24
    auto_roi_window_height_ratio: float = 0.34
    auto_roi_window_stride_ratio: float = 0.44
    auto_roi_max_windows: int = 4
    auto_roi_sliding_window_max_windows: int = 2
    auto_roi_min_area_ratio: float = 0.004
    auto_roi_overlap_threshold: float = 0.38
    roi_detector_confidence: float = 0.18
    roi_detector_imgsz: int = 960
    roi_expand_ratio: float = 0.35
    roi_upscale_factor: float = 3.0
    roi_enhanced_upscale_factor: float = 3.5
    roi_upper_body_bottom_ratio: float = 0.72
    roi_upper_body_side_pad_ratio: float = 0.08
    roi_upper_body_top_pad_ratio: float = 0.06
    roi_sliding_window_size_ratio: float = 0.78
    roi_sliding_window_stride_ratio: float = 0.45
    roi_sliding_window_max_windows: int = 6
    roi_occupancy_hold_ms: int = 2500
    roi_occupancy_similarity_threshold: float = 0.90
    roi_empty_feature_distance_threshold: float = 0.11
    roi_feature_update_alpha: float = 0.18
    tracker_high_confidence: float = 0.5
    tracker_low_confidence: float = 0.12
    tracker_new_track_confidence: float = 0.55
    tracker_match_iou_threshold: float = 0.28
    tracker_low_conf_iou_threshold: float = 0.16
    tracker_display_max_missed: int = 3
    tracker_min_confirmed_hits: int = 2
    camera_width: int = int(os.getenv("CAMERA_WIDTH", "3840"))
    camera_height: int = int(os.getenv("CAMERA_HEIGHT", "2160"))
    mjpeg_quality: int = int(os.getenv("MJPEG_QUALITY", "85"))
    target_fps: int = int(os.getenv("TARGET_FPS", "30"))
    audio_sample_rate: int = int(os.getenv("AUDIO_SAMPLE_RATE", "16000"))
    audio_channels: int = int(os.getenv("AUDIO_CHANNELS", "1"))
    audio_block_frames: int = int(os.getenv("AUDIO_BLOCK_FRAMES", "2048"))
    frame_queue_size: int = 1
    result_queue_size: int = 32
    feature_dim: int = Field(default=128, ge=64, le=512)
    gpu_max_utilization: int = 70


settings = Settings()
