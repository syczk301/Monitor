from __future__ import annotations

import queue
import threading
from dataclasses import dataclass


@dataclass(slots=True)
class AudioStreamConfig:
    sample_rate: int = 16000
    channels: int = 1
    block_frames: int = 2048
    dtype: str = "int16"
    queue_size: int = 24


class MicrophoneAudioStreamer:
    def __init__(self, config: AudioStreamConfig | None = None) -> None:
        self.config = config or AudioStreamConfig()
        self._lock = threading.Lock()
        self._subscribers: dict[int, queue.Queue[bytes]] = {}
        self._next_subscriber_id = 1
        self._stream = None
        self._last_error = ""
        try:
            import sounddevice as sounddevice
        except Exception as exc:  # pragma: no cover - import depends on host machine
            sounddevice = None
            self._last_error = f"麦克风模块不可用: {exc!s}"
        self._sounddevice = sounddevice

    @property
    def sample_rate(self) -> int:
        return self.config.sample_rate

    @property
    def channels(self) -> int:
        return self.config.channels

    @property
    def dtype(self) -> str:
        return self.config.dtype

    @property
    def last_error(self) -> str:
        return self._last_error

    def subscribe(self) -> tuple[int, queue.Queue[bytes]]:
        with self._lock:
            if not self._subscribers:
                self._ensure_started()
            subscriber_id = self._next_subscriber_id
            self._next_subscriber_id += 1
            subscriber_queue: queue.Queue[bytes] = queue.Queue(maxsize=self.config.queue_size)
            self._subscribers[subscriber_id] = subscriber_queue
            return subscriber_id, subscriber_queue

    def unsubscribe(self, subscriber_id: int) -> None:
        with self._lock:
            self._subscribers.pop(subscriber_id, None)
            if not self._subscribers:
                self._stop_locked()

    def stop(self) -> None:
        with self._lock:
            self._subscribers.clear()
            self._stop_locked()

    def _ensure_started(self) -> None:
        if self._stream is not None:
            return
        if self._sounddevice is None:
            raise RuntimeError(self._last_error or "麦克风模块不可用")
        try:
            self._stream = self._sounddevice.RawInputStream(
                samplerate=self.config.sample_rate,
                channels=self.config.channels,
                dtype=self.config.dtype,
                blocksize=self.config.block_frames,
                callback=self._on_audio,
            )
            self._stream.start()
            self._last_error = ""
        except Exception as exc:  # pragma: no cover - hardware dependent
            self._stream = None
            self._last_error = f"无法打开默认麦克风: {exc!s}"
            raise RuntimeError(self._last_error) from exc

    def _stop_locked(self) -> None:
        stream = self._stream
        self._stream = None
        if stream is None:
            return
        try:
            stream.stop()
        except Exception:
            pass
        try:
            stream.close()
        except Exception:
            pass

    def _on_audio(self, indata, frames, time_info, status) -> None:  # pragma: no cover - callback driven
        del frames, time_info
        if status:
            self._last_error = str(status)
        chunk = bytes(indata)
        with self._lock:
            subscribers = list(self._subscribers.values())
        for subscriber_queue in subscribers:
            try:
                subscriber_queue.put_nowait(chunk)
            except queue.Full:
                try:
                    subscriber_queue.get_nowait()
                except queue.Empty:
                    pass
                try:
                    subscriber_queue.put_nowait(chunk)
                except queue.Full:
                    pass
