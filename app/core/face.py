from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np
import torch
import torch.nn as nn

try:
    from torchvision import transforms
    from PIL import Image
except ImportError:
    transforms = None

from app.config import settings
from app.core.osnet import load_osnet


def _cosine_similarity(vec1: np.ndarray, vec2: np.ndarray) -> float:
    if vec1.shape != vec2.shape:
        return -1.0
    den = float(np.linalg.norm(vec1) * np.linalg.norm(vec2))
    if den == 0:
        return 0.0
    return float(np.dot(vec1, vec2) / den)


def _normalize_feature(vec: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vec))
    if norm == 0:
        return vec.astype(np.float32)
    return (vec / norm).astype(np.float32)


@dataclass(slots=True)
class RecentIdentitySample:
    track_id: int
    person_id: str
    bbox: tuple[int, int, int, int]
    feature: np.ndarray
    last_seen_ms: int


class PersonEmbeddingEngine:
    def __init__(self) -> None:
        use_cuda = settings.inference_device in {"cuda", "gpu"} and torch.cuda.is_available()
        self.device = "cuda" if use_cuda else "cpu"
        self._model = None
        self.transform = None

        try:
            self._model = load_osnet(self.device)
            if self._model is not None and transforms is not None:
                self.transform = transforms.Compose([
                    transforms.Resize((256, 128)),
                    transforms.ToTensor(),
                    transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
                ])
        except Exception as e:
            print("Failed to load OSNet:", e)

    def embed_128d(self, frame: np.ndarray, bbox: tuple[int, int, int, int]) -> np.ndarray | None:
        x1, y1, x2, y2 = bbox
        h, w = frame.shape[:2]
        x1 = max(0, min(x1, w - 1))
        y1 = max(0, min(y1, h - 1))
        x2 = max(1, min(x2, w))
        y2 = max(1, min(y2, h))
        crop = frame[y1:y2, x1:x2]
        if crop.size == 0 or x2 <= x1 or y2 <= y1:
            return None

        handcrafted = self._handcrafted_embedding(crop)
        if self._model is None or self.transform is None:
            return handcrafted

        rgb = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
        pil_img = Image.fromarray(rgb)
        input_tensor = self.transform(pil_img).unsqueeze(0).to(self.device)

        with torch.no_grad():
            feat = self._model(input_tensor).cpu().numpy().flatten()

        # OSNet is trained in its native 512-dimensional embedding space.
        # Interpolating that vector down to a smaller dimension changes the
        # learned geometry and makes unrelated people look artificially close.
        deep = _normalize_feature(feat)
        if deep.size == settings.feature_dim:
            return deep
        return self._resize_feature(deep, settings.feature_dim)

    @staticmethod
    def crop_quality_score(frame: np.ndarray, bbox: tuple[int, int, int, int]) -> float:
        x1, y1, x2, y2 = bbox
        fh, fw = frame.shape[:2]
        x1 = max(0, min(x1, fw - 1))
        y1 = max(0, min(y1, fh - 1))
        x2 = max(1, min(x2, fw))
        y2 = max(1, min(y2, fh))
        width = x2 - x1
        height = y2 - y1
        if width < 24 or height < 64 or height <= 0:
            return 0.0
        aspect_ratio = width / height
        if aspect_ratio < 0.18 or aspect_ratio > 1.05:
            return 0.0
        crop = frame[y1:y2, x1:x2]
        if crop.size == 0:
            return 0.0
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        mean_brightness = float(gray.mean())
        if mean_brightness < 12.0 or mean_brightness > 248.0:
            return 0.0
        size_score = min(1.0, height / max(96.0, fh * 0.15))
        sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        sharpness_score = min(1.0, sharpness / 80.0)
        exposure_score = 1.0 if 28.0 <= mean_brightness <= 232.0 else 0.5
        return size_score * 0.5 + sharpness_score * 0.3 + exposure_score * 0.2

    @staticmethod
    def _resize_feature(vec: np.ndarray, target_dim: int) -> np.ndarray:
        if target_dim <= 0:
            return np.zeros((0,), dtype=np.float32)
        flat = vec.astype(np.float32).flatten()
        if flat.size == 0:
            return np.zeros((target_dim,), dtype=np.float32)
        if flat.size == target_dim:
            return _normalize_feature(flat)
        resized = np.interp(
            np.linspace(0, flat.size - 1, target_dim),
            np.arange(flat.size),
            flat,
        ).astype(np.float32)
        return _normalize_feature(resized)

    def _handcrafted_embedding(self, crop: np.ndarray) -> np.ndarray:
        # Favor torso and clothing cues; this is more stable than a plain grayscale histogram.
        h, w = crop.shape[:2]
        if h < 4 or w < 4:
            return np.zeros((settings.feature_dim,), dtype=np.float32)

        focus_top = max(0, int(h * 0.15))
        focus_bottom = min(h, int(h * 0.9))
        focus_left = max(0, int(w * 0.1))
        focus_right = min(w, int(w * 0.9))
        focus = crop[focus_top:focus_bottom, focus_left:focus_right]
        if focus.size == 0:
            focus = crop

        hsv = cv2.cvtColor(focus, cv2.COLOR_BGR2HSV)
        upper = hsv[: max(1, hsv.shape[0] // 2), :]
        lower = hsv[max(0, hsv.shape[0] // 2):, :]
        if lower.size == 0:
            lower = upper

        upper_hist = cv2.calcHist([upper], [0, 1, 2], None, [8, 4, 4], [0, 180, 0, 256, 0, 256]).flatten()
        lower_hist = cv2.calcHist([lower], [0, 1, 2], None, [8, 4, 4], [0, 180, 0, 256, 0, 256]).flatten()

        shape = cv2.resize(focus, (16, 32), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(shape, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
        grad_x = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        grad_y = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        grad_mag = cv2.magnitude(grad_x, grad_y)

        lowres = gray.reshape(-1)
        grad_hist = cv2.calcHist([grad_mag], [0], None, [32], [0, float(max(1e-6, grad_mag.max()))]).flatten()
        geom = np.array([h / max(w, 1), w / max(h, 1)], dtype=np.float32)

        feature = np.concatenate(
            (
                upper_hist.astype(np.float32),
                lower_hist.astype(np.float32),
                lowres.astype(np.float32),
                grad_hist.astype(np.float32),
                geom,
            )
        )
        return self._resize_feature(feature, settings.feature_dim)


class ReIDRegistry:
    MAX_TEMPLATES = 5

    def __init__(self, threshold: float | None = None) -> None:
        self.threshold = threshold if threshold is not None else settings.reid_similarity_threshold
        self._store: dict[str, list[np.ndarray]] = {}
        self._dirty_person_ids: set[str] = set()

    def resolve_person_id(self, feature: np.ndarray) -> str:
        best_id, best_score = self.match_person_id(feature)
        if best_id and best_score >= self.threshold:
            if best_score >= max(0.82, self.threshold + 0.08):
                self._add_template(best_id, feature)
            return best_id
        new_id = self._create_person_id(feature)
        self._store[new_id] = [feature.copy()]
        self._dirty_person_ids.add(new_id)
        return new_id

    def match_person_id(self, feature: np.ndarray) -> tuple[str, float]:
        best_id = ""
        best_score = -1.0
        for person_id, templates in self._store.items():
            if not templates:
                continue
            template_score = max(_cosine_similarity(feature, tmpl) for tmpl in templates)
            centroid = _normalize_feature(np.mean(np.stack(templates), axis=0))
            centroid_score = _cosine_similarity(feature, centroid)
            score = max(template_score, centroid_score * 0.98)
            if score > best_score:
                best_score = score
                best_id = person_id
        return best_id, best_score

    def update_feature(self, person_id: str, feature: np.ndarray) -> None:
        if person_id in self._store:
            templates = self._store[person_id]
            best_score = max((_cosine_similarity(feature, tmpl) for tmpl in templates), default=-1.0)
            if best_score >= max(0.82, self.threshold + 0.08):
                self._add_template(person_id, feature)

    def load_templates(self, templates_by_person: dict[str, list[np.ndarray]]) -> None:
        self._store = {
            person_id: [np.asarray(feature, dtype=np.float32).copy() for feature in templates]
            for person_id, templates in templates_by_person.items()
            if templates
        }
        self._dirty_person_ids.clear()

    def snapshot_templates(self, person_ids: set[str] | None = None) -> dict[str, list[np.ndarray]]:
        target_ids = person_ids if person_ids is not None else set(self._store)
        snapshots: dict[str, list[np.ndarray]] = {}
        for person_id in target_ids:
            templates = self._store.get(person_id, [])
            if templates:
                snapshots[person_id] = [feature.copy() for feature in templates]
        return snapshots

    def get_dirty_person_ids(self) -> set[str]:
        return set(self._dirty_person_ids)

    def mark_persisted(self, person_ids: set[str]) -> None:
        self._dirty_person_ids.difference_update(person_ids)

    def _add_template(self, person_id: str, feature: np.ndarray) -> None:
        templates = self._store.get(person_id, [])
        # check if this angle is already well-represented
        for tmpl in templates:
            if _cosine_similarity(feature, tmpl) > 0.85:
                # update in-place with EMA
                tmpl[:] = 0.8 * tmpl + 0.2 * feature
                self._dirty_person_ids.add(person_id)
                return
        # new angle, add as new template
        if len(templates) < self.MAX_TEMPLATES:
            templates.append(feature.copy())
        else:
            # replace the least similar one
            worst_idx = 0
            worst_score = 1.0
            for i, tmpl in enumerate(templates):
                s = _cosine_similarity(feature, tmpl)
                if s < worst_score:
                    worst_score = s
                    worst_idx = i
            templates[worst_idx] = feature.copy()
        self._store[person_id] = templates
        self._dirty_person_ids.add(person_id)

    @staticmethod
    def _create_person_id(feature: np.ndarray) -> str:
        quantized = np.round(feature.astype(np.float32), 2)
        digest = hashlib.md5(quantized.tobytes(), usedforsecurity=False).hexdigest()[:12]
        return f"person-{digest}"


class TemporalIdentityMemory:
    def __init__(
        self,
        *,
        similarity_threshold: float | None = None,
        window_ms: int | None = None,
        max_distance_px: int | None = None,
    ) -> None:
        self.similarity_threshold = (
            similarity_threshold
            if similarity_threshold is not None
            else settings.reid_recent_similarity_threshold
        )
        self.window_ms = window_ms if window_ms is not None else settings.reid_recent_window_ms
        self.max_distance_px = (
            max_distance_px if max_distance_px is not None else settings.reid_recent_distance_px
        )
        self._samples: dict[int, RecentIdentitySample] = {}

    def remember(
        self,
        *,
        track_id: int,
        person_id: str,
        bbox: tuple[int, int, int, int],
        feature: np.ndarray,
        ts_ms: int,
    ) -> None:
        self._samples[track_id] = RecentIdentitySample(
            track_id=track_id,
            person_id=person_id,
            bbox=bbox,
            feature=feature.copy(),
            last_seen_ms=ts_ms,
        )
        self.prune(ts_ms)

    def match(
        self,
        *,
        track_id: int,
        bbox: tuple[int, int, int, int],
        feature: np.ndarray,
        ts_ms: int,
        active_track_ids: set[int],
    ) -> str | None:
        self.prune(ts_ms)
        best_person_id: str | None = None
        best_score = -1.0
        for sample in self._samples.values():
            if sample.track_id == track_id or sample.track_id in active_track_ids:
                continue
            similarity = _cosine_similarity(feature, sample.feature)
            if similarity < self.similarity_threshold:
                continue
            if self._center_distance_px(bbox, sample.bbox) > self.max_distance_px:
                continue
            if similarity > best_score:
                best_score = similarity
                best_person_id = sample.person_id
        return best_person_id

    def prune(self, ts_ms: int) -> None:
        expired = [
            track_id
            for track_id, sample in self._samples.items()
            if ts_ms - sample.last_seen_ms > self.window_ms
        ]
        for track_id in expired:
            del self._samples[track_id]

    @staticmethod
    def _center_distance_px(
        box_a: tuple[int, int, int, int],
        box_b: tuple[int, int, int, int],
    ) -> float:
        ax = (box_a[0] + box_a[2]) / 2
        ay = (box_a[1] + box_a[3]) / 2
        bx = (box_b[0] + box_b[2]) / 2
        by = (box_b[1] + box_b[3]) / 2
        return float(np.hypot(ax - bx, ay - by))
