from dataclasses import dataclass
from typing import Any

import cv2
import numpy as np

from app.config import settings
from app.core.entities import Detection


@dataclass(slots=True)
class ROIOccupancyState:
    last_bbox: tuple[int, int, int, int] | None = None
    occupied_feature: np.ndarray | None = None
    empty_feature: np.ndarray | None = None
    last_seen_ms: int = 0


@dataclass(slots=True)
class AutoFocusState:
    bbox: tuple[int, int, int, int]
    last_motion_ms: int


class PersonDetector:
    def __init__(self) -> None:
        self._model: Any | None = None
        self._last_auto_roi_scan_ms = -settings.auto_roi_scan_interval_ms
        try:
            import torch

            cv2.setNumThreads(max(1, settings.inference_cpu_threads))
            torch.set_num_threads(max(1, settings.inference_cpu_threads))
            try:
                torch.set_num_interop_threads(1)
            except RuntimeError:
                pass

            from ultralytics import YOLO

            self._model = YOLO(settings.detector_model)
            use_cuda = settings.inference_device in {"cuda", "gpu"} and torch.cuda.is_available()
            if use_cuda:
                self._model.to("cuda")
            else:
                self._model.to("cpu")
        except Exception:
            self._model = None
        self._hog = cv2.HOGDescriptor()
        getter = getattr(cv2, "HOGDescriptor_getDefaultPeopleDetector")
        default_detector = getter()
        self._hog.setSVMDetector(default_detector)
        self._roi_states: dict[int, ROIOccupancyState] = {}
        self._auto_focus_states: list[AutoFocusState] = []
        self._bg_subtractor = cv2.createBackgroundSubtractorMOG2(
            history=240,
            varThreshold=20,
            detectShadows=False,
        )

    def detect(
        self,
        frame: np.ndarray,
        rois: list[dict] | None = None,
        ts_ms: int | None = None,
    ) -> list[Detection]:
        if self._model is not None:
            return self._detect_yolo(frame, rois=rois or [], ts_ms=ts_ms)
        return self._detect_hog(frame)

    def _detect_yolo(
        self,
        frame: np.ndarray,
        rois: list[dict],
        ts_ms: int | None = None,
    ) -> list[Detection]:
        if self._model is None:
            return []
        current_ts_ms = ts_ms if ts_ms is not None else 0

        all_boxes = []
        all_confs = []
        all_sources: list[str] = []
        all_can_start: list[bool] = []
        roi_direct_hits: dict[int, list[tuple[tuple[int, int, int, int], float]]] = {}
        scan_rois = list(rois)

        motion_boxes: list[tuple[int, int, int, int]] = []
        if settings.auto_roi_enabled:
            motion_boxes = self._motion_candidates(frame)
            self._update_auto_focus_states(motion_boxes, current_ts_ms)

        # 1. 广视角全画幅扫描
        res = self._model.predict(
            frame,
            conf=min(0.35, settings.detector_confidence),
            iou=settings.detector_iou,
            classes=[0, *settings.detector_furniture_classes],
            imgsz=settings.detector_imgsz,
            verbose=False,
        )
        full_people: list[tuple[tuple[int, int, int, int], float]] = []
        full_furniture: list[tuple[tuple[int, int, int, int], float]] = []
        if res and res[0].boxes is not None:
            classes = res[0].boxes.cls
            for xyxy, conf, class_id in zip(res[0].boxes.xyxy, res[0].boxes.conf, classes, strict=False):
                x1, y1, x2, y2 = [int(v) for v in xyxy.tolist()]
                candidate = ((x1, y1, x2, y2), float(conf.item()))
                if int(class_id.item()) == 0:
                    if candidate[1] >= settings.detector_confidence and self._passes_person_geometry_filter(
                        candidate[0], frame_shape=frame.shape[:2]
                    ):
                        full_people.append(candidate)
                else:
                    full_furniture.append(candidate)
        for bbox, confidence in self._suppress_furniture_conflicts(full_people, full_furniture):
            x1, y1, x2, y2 = bbox
            all_boxes.append([x1, y1, x2 - x1, y2 - y1])
            all_confs.append(confidence)
            all_sources.append("full")
            all_can_start.append(self._can_start_person_track(bbox, confidence, current_ts_ms))

        # 2. 自动候选区域补扫，不再强依赖手动 ROI
        should_scan_auto_rois = (
            settings.auto_roi_enabled
            and current_ts_ms - self._last_auto_roi_scan_ms >= settings.auto_roi_scan_interval_ms
        )
        if should_scan_auto_rois:
            scan_rois.extend(self._auto_focus_rois(frame, rois, current_ts_ms, motion_boxes=motion_boxes))
            self._last_auto_roi_scan_ms = current_ts_ms

        # 3. ROI / 自动候选区域局部增强扫描
        for roi in scan_rois:
            roi_id = int(roi.get("id", 0))
            bbox = tuple(int(v) for v in roi.get("bbox", [0, 0, 0, 0]))
            local_hits: list[tuple[tuple[int, int, int, int], float]] = []
            for mapped_box, mapped_conf in self._detect_in_roi(frame, roi):
                x1, y1, x2, y2 = mapped_box
                all_boxes.append([x1, y1, x2 - x1, y2 - y1])
                all_confs.append(mapped_conf)
                all_sources.append("auto_roi" if roi.get("auto") else "roi")
                all_can_start.append(self._can_start_person_track(mapped_box, mapped_conf, current_ts_ms))
                local_hits.append((mapped_box, mapped_conf))
            if roi_id:
                roi_direct_hits[roi_id] = local_hits

        detections: list[Detection] = []
        if not all_boxes:
            return self._occupancy_only_fallback(frame, rois, current_ts_ms)

        # 4. 结果合并与去重 (NMS)
        indices = cv2.dnn.NMSBoxes(all_boxes, all_confs, score_threshold=0.1, nms_threshold=0.3)
        if len(indices) > 0:
            accepted_boxes: list[tuple[int, int, int, int]] = []
            ordered_indices = sorted(indices.flatten(), key=lambda idx: all_confs[idx], reverse=True)
            for i in ordered_indices:
                x, y, w, h = all_boxes[i]
                bbox = (x, y, x + w, y + h)
                if self._overlaps_existing_person(bbox, accepted_boxes):
                    continue
                accepted_boxes.append(bbox)
                detections.append(
                    Detection(
                        bbox=bbox,
                        confidence=all_confs[i],
                        class_id=0,
                        source=all_sources[i],
                        can_start_track=all_can_start[i],
                    )
                )
        detections.extend(self._build_occupancy_fallbacks(frame, rois, detections, roi_direct_hits, current_ts_ms))
        return detections

    def _detect_in_roi(
        self,
        frame: np.ndarray,
        roi: dict,
    ) -> list[tuple[tuple[int, int, int, int], float]]:
        bbox = tuple(int(v) for v in roi.get("bbox", [0, 0, 0, 0]))
        roi_params = self._roi_params(roi)
        fh, fw = frame.shape[:2]
        ex1, ey1, ex2, ey2 = self._expand_bbox(bbox, frame_shape=(fh, fw))
        if ex2 <= ex1 or ey2 <= ey1:
            return []

        roi_box = self._clip_bbox(bbox, frame_shape=(fh, fw))
        upper_body_box = self._upper_body_focus_box(roi_box, frame_shape=(fh, fw))
        is_auto = bool(roi.get("auto"))
        if is_auto:
            view_specs = [
                (roi_box[0], roi_box[1], roi_box[2], roi_box[3], roi_params["upscale_factor"], False, 0.04, False),
                (upper_body_box[0], upper_body_box[1], upper_body_box[2], upper_body_box[3], roi_params["enhanced_upscale_factor"], True, 0.14, True),
            ]
            view_specs.extend(
                self._sliding_windows(
                    upper_body_box,
                    roi_params,
                    max_windows=settings.auto_roi_sliding_window_max_windows,
                )
            )
        else:
            view_specs = [
                (ex1, ey1, ex2, ey2, roi_params["upscale_factor"], False, 0.04, False),
                (roi_box[0], roi_box[1], roi_box[2], roi_box[3], roi_params["enhanced_upscale_factor"], True, 0.08, False),
                (upper_body_box[0], upper_body_box[1], upper_body_box[2], upper_body_box[3], roi_params["enhanced_upscale_factor"], True, 0.16, True),
            ]
            view_specs.extend(self._sliding_windows(upper_body_box, roi_params))

        detections: list[tuple[tuple[int, int, int, int], float]] = []
        for sx1, sy1, sx2, sy2, upscale, enhance, _conf_bonus, upper_body_preferred in view_specs:
            if sx2 <= sx1 or sy2 <= sy1:
                continue
            crop = frame[sy1:sy2, sx1:sx2]
            if crop.size == 0:
                continue
            if enhance:
                crop = self._enhance_crop(crop)
            ch, cw = crop.shape[:2]
            max_model_side = int(roi_params["imgsz"])
            effective_upscale = max(1.0, min(upscale, max_model_side / max(1, max(ch, cw))))
            if effective_upscale > 1.01:
                target_w = max(cw + 1, int(round(cw * effective_upscale)))
                target_h = max(ch + 1, int(round(ch * effective_upscale)))
                upscaled = cv2.resize(crop, (target_w, target_h), interpolation=cv2.INTER_CUBIC)
            else:
                upscaled = crop
            c_res = self._model.predict(
                upscaled,
                conf=roi_params["confidence"],
                iou=settings.detector_iou,
                classes=[0, *settings.detector_furniture_classes],
                imgsz=roi_params["imgsz"],
                verbose=False,
            )
            view_people: list[tuple[tuple[int, int, int, int], float]] = []
            view_furniture: list[tuple[tuple[int, int, int, int], float]] = []
            if c_res and c_res[0].boxes is not None:
                classes = c_res[0].boxes.cls
                for xyxy, conf, class_id in zip(c_res[0].boxes.xyxy, c_res[0].boxes.conf, classes, strict=False):
                    cx1, cy1, cx2, cy2 = [int(v) for v in xyxy.tolist()]
                    fx1 = int(round(cx1 / effective_upscale)) + sx1
                    fy1 = int(round(cy1 / effective_upscale)) + sy1
                    fx2 = int(round(cx2 / effective_upscale)) + sx1
                    fy2 = int(round(cy2 / effective_upscale)) + sy1
                    mapped = self._clip_bbox((fx1, fy1, fx2, fy2), frame_shape=(fh, fw))
                    confidence = float(conf.item())
                    if int(class_id.item()) != 0:
                        view_furniture.append((mapped, confidence))
                        continue
                    if upper_body_preferred:
                        mapped = self._upper_body_detection_bbox(mapped, focus_box=roi_box, frame_shape=(fh, fw))
                    mapped_conf = confidence
                    if is_auto and not self._passes_auto_detection_filter(
                        mapped,
                        roi_box=roi_box,
                        frame_shape=(fh, fw),
                        confidence=mapped_conf,
                    ):
                        continue
                    if not self._passes_person_geometry_filter(mapped, frame_shape=(fh, fw)):
                        continue
                    view_people.append((mapped, mapped_conf))
            detections.extend(self._suppress_furniture_conflicts(view_people, view_furniture))
        return detections

    def _build_occupancy_fallbacks(
        self,
        frame: np.ndarray,
        rois: list[dict],
        detections: list[Detection],
        roi_direct_hits: dict[int, list[tuple[tuple[int, int, int, int], float]]],
        ts_ms: int,
    ) -> list[Detection]:
        fallbacks: list[Detection] = []
        roi_ids = {int(roi.get("id", 0)) for roi in rois if roi.get("id") is not None}
        self._roi_states = {roi_id: state for roi_id, state in self._roi_states.items() if roi_id in roi_ids}

        for roi in rois:
            roi_id = int(roi.get("id", 0))
            if roi_id <= 0:
                continue
            bbox = tuple(int(v) for v in roi.get("bbox", [0, 0, 0, 0]))
            state = self._roi_states.setdefault(roi_id, ROIOccupancyState())
            roi_feature = self._extract_roi_feature(frame, bbox)

            candidates = list(roi_direct_hits.get(roi_id, []))
            for det in detections:
                if self._bbox_overlap_ratio(det.bbox, bbox) > 0.15:
                    candidates.append((det.bbox, det.confidence))

            if candidates:
                best_bbox, _ = max(
                    candidates,
                    key=lambda item: item[1] + self._bbox_overlap_ratio(item[0], bbox) * 0.25,
                )
                state.last_bbox = best_bbox
                state.last_seen_ms = ts_ms
                state.occupied_feature = self._ema_feature(state.occupied_feature, roi_feature)
                return_empty_update = (
                    state.empty_feature is None
                    or self._feature_distance(roi_feature, state.empty_feature) > 0.02
                )
                if return_empty_update:
                    state.empty_feature = self._ema_feature(state.empty_feature, roi_feature, alpha=0.04)
                continue

            if state.empty_feature is None:
                state.empty_feature = roi_feature.copy()
            else:
                if ts_ms - state.last_seen_ms > settings.roi_occupancy_hold_ms:
                    state.empty_feature = self._ema_feature(state.empty_feature, roi_feature, alpha=0.05)

            occupancy = self._occupied_without_detection(state, roi_feature, ts_ms)
            if occupancy:
                fallback_bbox = self._clip_bbox(
                    self._occupancy_bbox(state, bbox),
                    frame_shape=frame.shape[:2],
                )
                fallbacks.append(
                    Detection(
                        bbox=fallback_bbox,
                        confidence=0.24,
                        class_id=0,
                        source="occupancy",
                        can_start_track=False,
                    )
                )

        return fallbacks

    def _occupancy_only_fallback(
        self,
        frame: np.ndarray,
        rois: list[dict],
        ts_ms: int,
    ) -> list[Detection]:
        return self._build_occupancy_fallbacks(frame, rois, [], {}, ts_ms)

    @staticmethod
    def _clip_bbox(
        bbox: tuple[int, int, int, int],
        frame_shape: tuple[int, int],
    ) -> tuple[int, int, int, int]:
        fh, fw = frame_shape
        x1, y1, x2, y2 = bbox
        return (
            max(0, min(x1, fw - 1)),
            max(0, min(y1, fh - 1)),
            max(1, min(x2, fw)),
            max(1, min(y2, fh)),
        )

    @staticmethod
    def _expand_bbox(
        bbox: tuple[int, int, int, int],
        frame_shape: tuple[int, int],
    ) -> tuple[int, int, int, int]:
        fh, fw = frame_shape
        x1, y1, x2, y2 = bbox
        pad_x = max(24, int((x2 - x1) * settings.roi_expand_ratio))
        pad_y = max(24, int((y2 - y1) * settings.roi_expand_ratio))
        return (
            max(0, x1 - pad_x),
            max(0, y1 - pad_y),
            min(fw, x2 + pad_x),
            min(fh, y2 + pad_y),
        )

    @staticmethod
    def _enhance_crop(crop: np.ndarray) -> np.ndarray:
        lab = cv2.cvtColor(crop, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        clahe = cv2.createCLAHE(clipLimit=2.2, tileGridSize=(8, 8))
        l = clahe.apply(l)
        enhanced = cv2.merge((l, a, b))
        enhanced = cv2.cvtColor(enhanced, cv2.COLOR_LAB2BGR)
        blur = cv2.GaussianBlur(enhanced, (0, 0), 1.2)
        return cv2.addWeighted(enhanced, 1.35, blur, -0.35, 0)

    def _sliding_windows(
        self,
        bbox: tuple[int, int, int, int],
        roi_params: dict[str, float | int],
        max_windows: int | None = None,
    ) -> list[tuple[int, int, int, int, float, bool, float, bool]]:
        x1, y1, x2, y2 = bbox
        width = x2 - x1
        height = y2 - y1
        if width < 80 or height < 80:
            return []

        tile_w = max(64, int(width * settings.roi_sliding_window_size_ratio))
        tile_h = max(64, int(height * settings.roi_sliding_window_size_ratio))
        step_x = max(20, int(tile_w * settings.roi_sliding_window_stride_ratio))
        step_y = max(20, int(tile_h * settings.roi_sliding_window_stride_ratio))
        xs = self._sliding_positions(x1, x2, tile_w, step_x)
        ys = self._sliding_positions(y1, y2, tile_h, step_y)
        roi_cx = (x1 + x2) / 2
        roi_cy = (y1 + y2) / 2
        windows: list[tuple[float, tuple[int, int, int, int, float, bool, float, bool]]] = []
        for wx1 in xs:
            for wy1 in ys:
                wx2 = min(x2, wx1 + tile_w)
                wy2 = min(y2, wy1 + tile_h)
                center_dist = abs(((wx1 + wx2) / 2) - roi_cx) + abs(((wy1 + wy2) / 2) - roi_cy)
                windows.append(
                    (
                        center_dist,
                        (wx1, wy1, wx2, wy2, float(roi_params["enhanced_upscale_factor"]), True, 0.14, True),
                    )
                )
        windows.sort(key=lambda item: item[0])
        limit = max_windows if max_windows is not None else settings.roi_sliding_window_max_windows
        return [window for _, window in windows[:limit]]

    @staticmethod
    def _roi_params(roi: dict) -> dict[str, float | int]:
        params = roi.get("params", {}) or {}
        if roi.get("auto"):
            return {
                "confidence": float(params.get("confidence", settings.auto_roi_detector_confidence)),
                "imgsz": int(params.get("imgsz", settings.auto_roi_detector_imgsz)),
                "upscale_factor": float(params.get("upscale_factor", settings.auto_roi_upscale_factor)),
                "enhanced_upscale_factor": float(
                    params.get("enhanced_upscale_factor", settings.auto_roi_enhanced_upscale_factor)
                ),
            }
        return {
            "confidence": float(params.get("confidence", settings.roi_detector_confidence)),
            "imgsz": int(params.get("imgsz", settings.roi_detector_imgsz)),
            "upscale_factor": float(params.get("upscale_factor", settings.roi_upscale_factor)),
            "enhanced_upscale_factor": float(
                params.get("enhanced_upscale_factor", settings.roi_enhanced_upscale_factor)
            ),
        }

    def _auto_focus_rois(
        self,
        frame: np.ndarray,
        manual_rois: list[dict],
        ts_ms: int,
        motion_boxes: list[tuple[int, int, int, int]] | None = None,
    ) -> list[dict]:
        fh, fw = frame.shape[:2]
        manual_boxes = [
            self._clip_bbox(tuple(int(v) for v in roi.get("bbox", [0, 0, 0, 0])), frame_shape=(fh, fw))
            for roi in manual_rois
            if roi.get("bbox")
        ]
        if motion_boxes is None:
            motion_boxes = self._motion_candidates(frame)
            self._update_auto_focus_states(motion_boxes, ts_ms)

        candidates: list[tuple[float, tuple[int, int, int, int]]] = []
        for state in self._auto_focus_states:
            age_ratio = min(1.0, max(0.0, (ts_ms - state.last_motion_ms) / max(1, settings.auto_roi_memory_ms)))
            candidates.append((1.0 - age_ratio * 0.35, state.bbox))

        if settings.auto_roi_use_scene_windows and motion_boxes:
            for idx, bbox in enumerate(self._scene_windows(frame_shape=(fh, fw))):
                score = max(
                    (
                        self._bbox_iou(bbox, motion_bbox) + self._bbox_overlap_ratio(motion_bbox, bbox)
                        for motion_bbox in motion_boxes
                    ),
                    default=0.0,
                )
                if score < 0.18:
                    continue
                candidates.append((0.18 + score + max(0.0, 0.08 - idx * 0.01), bbox))

        selected = self._select_candidate_boxes(
            candidates,
            excluded_boxes=manual_boxes,
            max_count=settings.auto_roi_max_windows,
        )
        return [{"bbox": list(bbox), "auto": True} for bbox in selected]

    def _update_auto_focus_states(
        self,
        motion_boxes: list[tuple[int, int, int, int]],
        ts_ms: int,
    ) -> None:
        active_states = [
            state
            for state in self._auto_focus_states
            if ts_ms - state.last_motion_ms <= settings.auto_roi_memory_ms
        ]
        for motion_bbox in motion_boxes:
            best_state: AutoFocusState | None = None
            best_score = 0.0
            for state in active_states:
                score = max(
                    self._bbox_iou(state.bbox, motion_bbox),
                    self._bbox_overlap_ratio(motion_bbox, state.bbox),
                )
                if score > best_score:
                    best_score = score
                    best_state = state
            if best_state is not None and best_score >= 0.2:
                best_state.bbox = self._merge_bboxes(best_state.bbox, motion_bbox)
                best_state.last_motion_ms = ts_ms
            else:
                active_states.append(AutoFocusState(bbox=motion_bbox, last_motion_ms=ts_ms))
        active_states.sort(key=lambda state: state.last_motion_ms, reverse=True)
        self._auto_focus_states = active_states[: max(settings.auto_roi_max_windows * 2, 4)]

    def _motion_candidates(
        self,
        frame: np.ndarray,
    ) -> list[tuple[int, int, int, int]]:
        fh, fw = frame.shape[:2]
        analysis_scale = min(1.0, settings.motion_analysis_max_width / max(1, fw))
        analysis_w = max(1, int(round(fw * analysis_scale)))
        analysis_h = max(1, int(round(fh * analysis_scale)))
        if analysis_scale < 1.0:
            analysis_frame = cv2.resize(frame, (analysis_w, analysis_h), interpolation=cv2.INTER_AREA)
        else:
            analysis_frame = frame
        band_top = int(analysis_h * settings.auto_roi_band_top_ratio)
        band_bottom = int(analysis_h * settings.auto_roi_band_bottom_ratio)
        gray = cv2.cvtColor(analysis_frame, cv2.COLOR_BGR2GRAY)
        gray = cv2.GaussianBlur(gray, (5, 5), 0)
        fg_mask = self._bg_subtractor.apply(gray, learningRate=0.003)
        _, fg_mask = cv2.threshold(fg_mask, 210, 255, cv2.THRESH_BINARY)
        fg_mask[:band_top, :] = 0
        fg_mask[band_bottom:, :] = 0
        fg_ratio = float(cv2.countNonZero(fg_mask)) / float(max(1, fg_mask.size))
        if fg_ratio > 0.58:
            return []

        kernel = np.ones((5, 5), dtype=np.uint8)
        fg_mask = cv2.morphologyEx(fg_mask, cv2.MORPH_OPEN, kernel, iterations=1)
        fg_mask = cv2.dilate(fg_mask, kernel, iterations=2)

        min_area = analysis_h * analysis_w * settings.auto_roi_min_area_ratio
        scale_x = fw / float(analysis_w)
        scale_y = fh / float(analysis_h)
        candidates: list[tuple[int, int, int, int]] = []
        contours, _ = cv2.findContours(fg_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            area = cv2.contourArea(contour)
            if area < min_area:
                continue
            x, y, w, h = cv2.boundingRect(contour)
            bbox = self._clip_bbox(
                (
                    int(round(x * scale_x)),
                    int(round(y * scale_y)),
                    int(round((x + w) * scale_x)),
                    int(round((y + h) * scale_y)),
                ),
                frame_shape=(fh, fw),
            )
            candidates.append(self._expand_bbox(bbox, frame_shape=(fh, fw)))
        return candidates

    @staticmethod
    def _scene_windows(
        frame_shape: tuple[int, int],
    ) -> list[tuple[int, int, int, int]]:
        fh, fw = frame_shape
        band_top = int(fh * settings.auto_roi_band_top_ratio)
        band_bottom = int(fh * settings.auto_roi_band_bottom_ratio)
        window_w = max(180, int(fw * settings.auto_roi_window_width_ratio))
        window_h = max(160, int(fh * settings.auto_roi_window_height_ratio))
        step_x = max(48, int(window_w * settings.auto_roi_window_stride_ratio))
        step_y = max(48, int(window_h * settings.auto_roi_window_stride_ratio))
        xs = PersonDetector._sliding_positions(0, fw, window_w, step_x)
        ys = PersonDetector._sliding_positions(band_top, max(band_top + 1, band_bottom), window_h, step_y)
        boxes: list[tuple[int, int, int, int]] = []
        for y1 in ys:
            for x1 in xs:
                boxes.append((x1, y1, min(fw, x1 + window_w), min(fh, y1 + window_h)))
        boxes.sort(key=lambda box: (box[1], abs(((box[0] + box[2]) / 2) - (fw / 2))))
        return boxes

    @staticmethod
    def _select_candidate_boxes(
        candidates: list[tuple[float, tuple[int, int, int, int]]],
        excluded_boxes: list[tuple[int, int, int, int]],
        max_count: int,
    ) -> list[tuple[int, int, int, int]]:
        selected: list[tuple[int, int, int, int]] = []
        for _, bbox in sorted(candidates, key=lambda item: item[0], reverse=True):
            overlaps_manual = any(
                PersonDetector._bbox_iou(bbox, excluded) >= settings.auto_roi_overlap_threshold
                or PersonDetector._bbox_overlap_ratio(bbox, excluded) >= 0.5
                for excluded in excluded_boxes
            )
            if overlaps_manual:
                continue
            overlaps_selected = any(
                PersonDetector._bbox_iou(bbox, existing) >= settings.auto_roi_overlap_threshold
                for existing in selected
            )
            if overlaps_selected:
                continue
            selected.append(bbox)
            if len(selected) >= max_count:
                break
        return selected

    @staticmethod
    def _passes_auto_detection_filter(
        bbox: tuple[int, int, int, int],
        roi_box: tuple[int, int, int, int],
        frame_shape: tuple[int, int],
        confidence: float,
    ) -> bool:
        if confidence < settings.auto_roi_min_detection_confidence:
            return False
        fh, fw = frame_shape
        x1, y1, x2, y2 = bbox
        width = max(1, x2 - x1)
        height = max(1, y2 - y1)
        area = width * height
        aspect_ratio = width / height
        min_width = max(26, int(fw * 0.022))
        min_height = max(40, int(fh * 0.065))
        min_area = int(fw * fh * 0.0022)
        if width < min_width or height < min_height or area < min_area:
            return False
        if aspect_ratio < 0.20 or aspect_ratio > 1.08:
            return False
        center_y = (y1 + y2) / 2
        if center_y < fh * settings.auto_roi_band_top_ratio:
            return False
        roi_coverage = PersonDetector._bbox_overlap_ratio(roi_box, bbox)
        return roi_coverage >= 0.7

    @staticmethod
    def _passes_person_geometry_filter(
        bbox: tuple[int, int, int, int],
        frame_shape: tuple[int, int],
    ) -> bool:
        fh, fw = frame_shape
        x1, y1, x2, y2 = bbox
        width = max(1, x2 - x1)
        height = max(1, y2 - y1)
        aspect_ratio = width / height
        area_ratio = (width * height) / float(max(1, fw * fh))
        if width < max(20, int(fw * 0.012)):
            return False
        if height < max(56, int(fh * 0.045)):
            return False
        if area_ratio < 0.0008:
            return False
        return 0.18 <= aspect_ratio <= 1.05

    @staticmethod
    def _suppress_furniture_conflicts(
        people: list[tuple[tuple[int, int, int, int], float]],
        furniture: list[tuple[tuple[int, int, int, int], float]],
    ) -> list[tuple[tuple[int, int, int, int], float]]:
        accepted: list[tuple[tuple[int, int, int, int], float]] = []
        for person_bbox, person_confidence in people:
            conflict = any(
                PersonDetector._bbox_overlap_ratio(furniture_bbox, person_bbox)
                >= settings.detector_furniture_overlap_threshold
                and furniture_confidence >= person_confidence * settings.detector_furniture_score_ratio
                for furniture_bbox, furniture_confidence in furniture
            )
            if not conflict:
                accepted.append((person_bbox, person_confidence))
        return accepted

    @staticmethod
    def _overlaps_existing_person(
        bbox: tuple[int, int, int, int],
        accepted_boxes: list[tuple[int, int, int, int]],
    ) -> bool:
        for existing in accepted_boxes:
            if PersonDetector._bbox_iou(bbox, existing) >= 0.28:
                return True
            containment = max(
                PersonDetector._bbox_overlap_ratio(bbox, existing),
                PersonDetector._bbox_overlap_ratio(existing, bbox),
            )
            if containment >= 0.65:
                return True
        return False

    def _can_start_person_track(
        self,
        bbox: tuple[int, int, int, int],
        confidence: float,
        ts_ms: int,
    ) -> bool:
        if confidence >= settings.detector_static_new_track_confidence:
            return True
        for state in self._auto_focus_states:
            if ts_ms - state.last_motion_ms > settings.auto_roi_memory_ms:
                continue
            if (
                self._bbox_iou(bbox, state.bbox) >= 0.08
                or self._bbox_overlap_ratio(state.bbox, bbox) >= 0.12
            ):
                return True
        return False

    @staticmethod
    def _merge_bboxes(
        box_a: tuple[int, int, int, int],
        box_b: tuple[int, int, int, int],
    ) -> tuple[int, int, int, int]:
        ax1, ay1, ax2, ay2 = box_a
        bx1, by1, bx2, by2 = box_b
        return (
            min(ax1, bx1),
            min(ay1, by1),
            max(ax2, bx2),
            max(ay2, by2),
        )

    @staticmethod
    def _upper_body_focus_box(
        bbox: tuple[int, int, int, int],
        frame_shape: tuple[int, int],
    ) -> tuple[int, int, int, int]:
        fh, fw = frame_shape
        x1, y1, x2, y2 = bbox
        width = x2 - x1
        height = y2 - y1
        pad_x = int(width * settings.roi_upper_body_side_pad_ratio)
        pad_top = int(height * settings.roi_upper_body_top_pad_ratio)
        focus_bottom = y1 + int(height * settings.roi_upper_body_bottom_ratio)
        return (
            max(0, x1 - pad_x),
            max(0, y1 - pad_top),
            min(fw, x2 + pad_x),
            min(fh, focus_bottom),
        )

    @staticmethod
    def _upper_body_detection_bbox(
        bbox: tuple[int, int, int, int],
        focus_box: tuple[int, int, int, int],
        frame_shape: tuple[int, int],
    ) -> tuple[int, int, int, int]:
        fh, fw = frame_shape
        x1, y1, x2, y2 = bbox
        width = x2 - x1
        height = y2 - y1
        new_y2 = y1 + int(height * settings.roi_upper_body_bottom_ratio)
        pad_x = int(width * settings.roi_upper_body_side_pad_ratio)
        fx1, fy1, fx2, fy2 = focus_box
        return (
            max(0, min(x1 - pad_x, fw - 1)),
            max(0, min(y1, fh - 1)),
            max(1, min(x2 + pad_x, fw)),
            max(1, min(min(new_y2, fy2), fh)),
        )

    @staticmethod
    def _sliding_positions(start: int, end: int, window_size: int, step: int) -> list[int]:
        if end - start <= window_size:
            return [start]
        positions = list(range(start, end - window_size + 1, step))
        last = end - window_size
        if positions[-1] != last:
            positions.append(last)
        return positions

    def _extract_roi_feature(
        self,
        frame: np.ndarray,
        bbox: tuple[int, int, int, int],
    ) -> np.ndarray:
        fh, fw = frame.shape[:2]
        x1, y1, x2, y2 = self._clip_bbox(bbox, frame_shape=(fh, fw))
        crop = frame[y1:y2, x1:x2]
        if crop.size == 0:
            return np.zeros((96,), dtype=np.float32)
        resized = cv2.resize(crop, (24, 24), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
        grad_x = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        grad_y = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        grad = cv2.magnitude(grad_x, grad_y)
        bins = np.linspace(0.0, 1.0, 17)
        grad_hist, _ = np.histogram(np.clip(grad, 0.0, 1.0), bins=bins)
        feature = np.concatenate(
            (
                gray.reshape(-1)[::8],
                grad_hist.astype(np.float32),
            )
        ).astype(np.float32)
        norm = float(np.linalg.norm(feature))
        if norm == 0:
            return feature
        return feature / norm

    @staticmethod
    def _ema_feature(
        current: np.ndarray | None,
        new_value: np.ndarray,
        alpha: float | None = None,
    ) -> np.ndarray:
        if current is None or current.shape != new_value.shape:
            return new_value.copy()
        mix = alpha if alpha is not None else settings.roi_feature_update_alpha
        merged = (1.0 - mix) * current + mix * new_value
        norm = float(np.linalg.norm(merged))
        if norm == 0:
            return merged.astype(np.float32)
        return (merged / norm).astype(np.float32)

    @staticmethod
    def _feature_similarity(vec_a: np.ndarray | None, vec_b: np.ndarray | None) -> float:
        if vec_a is None or vec_b is None:
            return 0.0
        den = float(np.linalg.norm(vec_a) * np.linalg.norm(vec_b))
        if den == 0:
            return 0.0
        return float(np.dot(vec_a, vec_b) / den)

    @staticmethod
    def _feature_distance(vec_a: np.ndarray | None, vec_b: np.ndarray | None) -> float:
        if vec_a is None or vec_b is None or vec_a.shape != vec_b.shape:
            return 1.0
        return float(np.mean(np.abs(vec_a - vec_b)))

    def _occupied_without_detection(
        self,
        state: ROIOccupancyState,
        roi_feature: np.ndarray,
        ts_ms: int,
    ) -> bool:
        if state.last_bbox is None or state.occupied_feature is None:
            return False
        if ts_ms - state.last_seen_ms > settings.roi_occupancy_hold_ms:
            return False
        similarity = self._feature_similarity(roi_feature, state.occupied_feature)
        empty_distance = self._feature_distance(roi_feature, state.empty_feature)
        return (
            similarity >= settings.roi_occupancy_similarity_threshold
            or empty_distance >= settings.roi_empty_feature_distance_threshold
        )

    @staticmethod
    def _occupancy_bbox(
        state: ROIOccupancyState,
        roi_bbox: tuple[int, int, int, int],
    ) -> tuple[int, int, int, int]:
        if state.last_bbox is not None:
            x1, y1, x2, y2 = state.last_bbox
            pad_x = max(12, (x2 - x1) // 8)
            pad_y = max(12, (y2 - y1) // 8)
            return (x1 - pad_x, y1 - pad_y, x2 + pad_x, y2 + pad_y)
        x1, y1, x2, y2 = roi_bbox
        w = x2 - x1
        h = y2 - y1
        return (
            x1 + int(w * 0.12),
            y1 + int(h * 0.10),
            x2 - int(w * 0.12),
            y2 - int(h * 0.08),
        )

    @staticmethod
    def _bbox_overlap_ratio(
        box_a: tuple[int, int, int, int],
        box_b: tuple[int, int, int, int],
    ) -> float:
        ax1, ay1, ax2, ay2 = box_a
        bx1, by1, bx2, by2 = box_b
        inter_x1 = max(ax1, bx1)
        inter_y1 = max(ay1, by1)
        inter_x2 = min(ax2, bx2)
        inter_y2 = min(ay2, by2)
        inter_w = max(0, inter_x2 - inter_x1)
        inter_h = max(0, inter_y2 - inter_y1)
        inter_area = inter_w * inter_h
        area_b = max(0, bx2 - bx1) * max(0, by2 - by1)
        if area_b == 0:
            return 0.0
        return inter_area / area_b

    @staticmethod
    def _bbox_iou(
        box_a: tuple[int, int, int, int],
        box_b: tuple[int, int, int, int],
    ) -> float:
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
        if union <= 0:
            return 0.0
        return inter_area / union

    def _detect_hog(self, frame: np.ndarray) -> list[Detection]:
        rects, weights = self._hog.detectMultiScale(frame, winStride=(4, 4), padding=(8, 8), scale=1.03)
        detections: list[Detection] = []
        for (x, y, w, h), weight in zip(rects, weights, strict=False):
            detections.append(
                Detection(
                    bbox=(int(x), int(y), int(x + w), int(y + h)),
                    confidence=float(weight),
                    class_id=0,
                )
            )
        return detections
