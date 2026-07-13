from __future__ import annotations

import asyncio
import hashlib
import queue
import subprocess
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

from app.services.manager import services

templates = Jinja2Templates(directory="app/web/templates")
router = APIRouter(prefix="/api")


class VisitNotePayload(BaseModel):
    note: str = Field(default="", max_length=500)


class BulkDeletePayload(BaseModel):
    visit_ids: list[int]


class ROIParamsPayload(BaseModel):
    confidence: float | None = Field(default=None, ge=0.01, le=0.99)
    imgsz: int | None = Field(default=None, ge=320, le=1920)
    upscale_factor: float | None = Field(default=None, ge=1.0, le=6.0)
    enhanced_upscale_factor: float | None = Field(default=None, ge=1.0, le=8.0)


class ROIPayload(BaseModel):
    bbox: list[int]
    params: ROIParamsPayload | None = None


@router.get("/health")
def health() -> dict:
    stats = services.pipeline.stats
    return {
        "status": "ok",
        "fps": stats.fps,
        "latency_ms": stats.avg_latency_ms,
        "tracked_targets": stats.tracked_targets,
        "gpu_utilization": stats.gpu_utilization,
        "capture_status": stats.capture_status,
        "capture_backend": stats.capture_backend,
    }


@router.get("/events")
def events(limit: int = 200) -> list[dict]:
    rows = services.repository.list_events(limit=limit)
    return [
        {
            "person_id": row.person_id,
            "first_seen": row.first_seen.isoformat(),
            "last_seen": row.last_seen.isoformat(),
            "dwell_seconds": row.dwell_seconds,
            "appearance_count": row.appearance_count,
        }
        for row in rows
    ]


@router.get("/visits")
def visits(limit: int = 200) -> list[dict]:
    rows = services.repository.list_visits(limit=limit)
    return [
        {
            "id": row.id,
            "person_id": row.person_id,
            "appeared_at": row.appeared_at.isoformat(),
            "left_at": row.left_at.isoformat() if row.left_at else None,
            "stay_seconds": row.stay_seconds,
            "source_track_id": row.source_track_id,
            "note": row.note,
            "status": "已离开" if row.left_at else "在场",
        }
        for row in rows
    ]


@router.patch("/visits/{visit_id}/note")
def update_visit_note(visit_id: int, payload: VisitNotePayload) -> dict:
    updated = services.repository.update_visit_note(visit_id=visit_id, note=payload.note.strip())
    if not updated:
        raise HTTPException(status_code=404, detail="visit not found")
    return {"ok": True, "visit_id": visit_id}


@router.patch("/persons/{person_id}/note")
def update_person_note(person_id: str, payload: VisitNotePayload) -> dict:
    updated_count = services.repository.update_person_note(person_id=person_id, note=payload.note.strip())
    return {"ok": True, "person_id": person_id, "updated_count": updated_count}


@router.delete("/visits/{visit_id}")
def delete_visit(visit_id: int) -> dict:
    deleted = services.repository.delete_visit(visit_id=visit_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="visit not found")
    return {"ok": True, "visit_id": visit_id}


@router.get("/rois")
def get_rois() -> list[dict]:
    return services.repository.list_rois()


@router.post("/rois")
def add_roi(payload: ROIPayload) -> dict:
    params = payload.params.model_dump(exclude_none=True) if payload.params else None
    return services.repository.add_roi(payload.bbox, params=params)


@router.patch("/rois/{roi_id}")
def update_roi(roi_id: int, payload: ROIPayload) -> dict:
    params = payload.params.model_dump(exclude_none=True) if payload.params else None
    updated = services.repository.update_roi(roi_id=roi_id, bbox=payload.bbox, params=params)
    if updated is None:
        raise HTTPException(status_code=404, detail="roi not found")
    return updated


@router.delete("/rois/{roi_id}")
def delete_roi(roi_id: int) -> dict:
    services.repository.delete_roi(roi_id)
    return {"ok": True}

@router.post("/visits/bulk-delete")
def bulk_delete_visits(payload: BulkDeletePayload) -> dict:
    count = services.repository.delete_visits(payload.visit_ids)
    return {"ok": True, "deleted_count": count}


@router.get("/capture_info")
def capture_info() -> dict:
    return services.pipeline.get_capture_info()


@router.get("/recordings")
def recordings() -> list[dict]:
    return services.pipeline.list_recordings()


@router.get("/recordings/file")
def recording_file(path: str) -> FileResponse:
    try:
        recording_path = services.pipeline.resolve_recording_path(path)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="recording not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return FileResponse(
        recording_path,
        media_type="video/mp4",
        filename=recording_path.name,
        content_disposition_type="inline",
    )


@router.get("/recordings/playback")
def recording_playback(path: str):
    try:
        recording_path = services.pipeline.resolve_recording_path(path)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="recording not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    stat = recording_path.stat()
    cache_key = hashlib.sha1(
        f"stable-v2:{recording_path}:{stat.st_size}:{stat.st_mtime_ns}".encode("utf-8")
    ).hexdigest()
    cache_dir = Path("data/playback_cache")
    cache_dir.mkdir(parents=True, exist_ok=True)
    browser_path = cache_dir / f"{cache_key}.mp4"

    if browser_path.exists() and browser_path.stat().st_size > 0:
        return FileResponse(
            browser_path,
            media_type="video/mp4",
            filename=recording_path.name,
            content_disposition_type="inline",
        )

    ffmpeg = services.pipeline._get_ffmpeg_executable()
    if ffmpeg is None:
        raise HTTPException(status_code=503, detail="ffmpeg unavailable")
    temp_path = browser_path.with_suffix(".tmp.mp4")
    temp_path.unlink(missing_ok=True)

    completed = subprocess.run(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                str(recording_path),
                "-map",
                "0:v:0",
                "-map",
                "0:a?",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-crf",
                "28",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-movflags",
                "+faststart",
                str(temp_path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
    if completed.returncode != 0 or not temp_path.exists() or temp_path.stat().st_size == 0:
        temp_path.unlink(missing_ok=True)
        detail = completed.stderr.strip() or "transcode failed"
        raise HTTPException(status_code=500, detail=detail[-500:])
    temp_path.replace(browser_path)

    return FileResponse(
        browser_path,
        media_type="video/mp4",
        filename=recording_path.name,
        content_disposition_type="inline",
    )


@router.get("/audio/info")
def audio_info() -> dict:
    return {
        "sample_rate": services.audio.sample_rate,
        "channels": services.audio.channels,
        "dtype": services.audio.dtype,
        "status": "ok" if not services.audio.last_error else "degraded",
        "last_error": services.audio.last_error,
    }


class CameraSettingsPayload(BaseModel):
    width: int = Field(ge=640, le=3840)
    height: int = Field(ge=480, le=2160)
    fps: int = Field(ge=5, le=60)


class LocalRecordingPayload(BaseModel):
    enabled: bool


class RecordingModePayload(BaseModel):
    mode: Literal["off", "auto", "continuous"]


@router.post("/camera_settings")
def update_camera_settings(payload: CameraSettingsPayload) -> dict:
    return services.pipeline.apply_camera_settings(payload.width, payload.height, payload.fps)


@router.post("/local_recording")
def update_local_recording(payload: LocalRecordingPayload) -> dict:
    return services.pipeline.set_local_recording_enabled(payload.enabled)


@router.post("/recording_mode")
def update_recording_mode(payload: RecordingModePayload) -> dict:
    return services.pipeline.set_recording_mode(payload.mode)


@router.get("/reports/daily")
def report_daily() -> dict:
    return services.reports.generate_daily()


@router.get("/reports/weekly")
def report_weekly() -> dict:
    return services.reports.generate_weekly()


def create_fastapi_app() -> FastAPI:
    app = FastAPI(title="Camera Monitor API", version="0.1.0")
    app.include_router(router)

    @app.on_event("startup")
    def startup() -> None:
        if services.pipeline.should_run_in_background():
            services.pipeline.start()
        else:
            services.pipeline._publish_placeholder_frame("等待客户端连接...")

    @app.on_event("shutdown")
    def shutdown() -> None:
        with services.pipeline._client_lock:
            if services.pipeline._idle_timer is not None:
                services.pipeline._idle_timer.cancel()
        services.pipeline.stop()
        services.audio.stop()

    @app.get("/", response_class=HTMLResponse)
    def home(request: Request) -> HTMLResponse:
        return templates.TemplateResponse("index.html", {"request": request})

    @app.get("/api/tray-icon")
    def tray_icon() -> FileResponse:
        return FileResponse("assets/tray_icon.png", media_type="image/png")

    @app.get("/history", response_class=HTMLResponse)
    def history(request: Request) -> HTMLResponse:
        return templates.TemplateResponse("history.html", {"request": request})

    @app.get("/recordings", response_class=HTMLResponse)
    def recordings_page(request: Request) -> HTMLResponse:
        return templates.TemplateResponse("recordings.html", {"request": request})

    @app.get("/stream")
    async def stream(request: Request) -> StreamingResponse:
        async def mjpeg_stream():
            services.pipeline.acquire()
            try:
                while True:
                    if await request.is_disconnected():
                        break
                    frame = services.pipeline.latest_frame
                    if frame:
                        yield (
                            b"--frame\r\n"
                            b"Content-Type: image/jpeg\r\n\r\n" + frame + b"\r\n"
                        )
                    await asyncio.sleep(0.03)
            finally:
                services.pipeline.release()

        return StreamingResponse(
            mjpeg_stream(),
            media_type="multipart/x-mixed-replace; boundary=frame",
        )

    @app.get("/api/audio/pcm")
    async def audio_pcm(request: Request) -> StreamingResponse:
        try:
            subscriber_id, subscriber_queue = services.audio.subscribe()
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

        async def pcm_stream():
            try:
                while True:
                    if await request.is_disconnected():
                        break
                    try:
                        chunk = subscriber_queue.get(timeout=1.0)
                    except queue.Empty:
                        continue
                    yield chunk
            finally:
                services.audio.unsubscribe(subscriber_id)

        return StreamingResponse(
            pcm_stream(),
            media_type="application/octet-stream",
            headers={
                "Cache-Control": "no-store",
                "X-Audio-Sample-Rate": str(services.audio.sample_rate),
                "X-Audio-Channels": str(services.audio.channels),
                "X-Audio-DType": services.audio.dtype,
            },
        )

    @app.get("/stats")
    def stats() -> JSONResponse:
        runtime = services.pipeline.stats
        return JSONResponse(
            {
                "fps": runtime.fps,
                "avg_latency_ms": runtime.avg_latency_ms,
                "tracked_targets": runtime.tracked_targets,
                "gpu_utilization": runtime.gpu_utilization,
                "capture_status": runtime.capture_status,
                "capture_backend": runtime.capture_backend,
                "kpi_target_latency_ms": 500,
                "kpi_target_tracks": 50,
            }
        )

    return app


app = create_fastapi_app()
