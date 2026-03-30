"""Camera Monitor -- system tray application.

Double-click the tray icon to open the web UI.
Right-click for menu: start/stop server, auto-start toggle, exit.
"""

from __future__ import annotations

import logging
import os
import sys
import threading
import time
import webbrowser
from pathlib import Path

import pystray
from PIL import Image

APP_NAME = "Camera Monitor"
HOST = "0.0.0.0"
PORT = 8000
WEB_URL = f"http://localhost:{PORT}"

_BASE_DIR = Path(__file__).resolve().parent
_ICON_PATH = _BASE_DIR / "assets" / "tray_icon.ico"
_ICON_PNG = _BASE_DIR / "assets" / "tray_icon.png"
_LOG_FILE = _BASE_DIR / "data" / "tray.log"

_LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    filename=str(_LOG_FILE),
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("tray")


class ServerManager:
    def __init__(self) -> None:
        self._thread: threading.Thread | None = None
        self._server: object | None = None
        self.running = False
        self.error: str | None = None

    def start(self) -> None:
        if self.running:
            return
        self.error = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            os.chdir(str(_BASE_DIR))
            if sys.stdout is None:
                sys.stdout = open(os.devnull, "w")
            if sys.stderr is None:
                sys.stderr = open(os.devnull, "w")
            import uvicorn
            config = uvicorn.Config(
                "app.api.main:app", host=HOST, port=PORT,
                log_level="info", access_log=False,
                log_config=None,
            )
            self._server = uvicorn.Server(config)
            self.running = True
            log.info("Server starting on %s:%s", HOST, PORT)
            self._server.run()
        except Exception as e:
            self.error = str(e)
            log.exception("Server failed to start")
        finally:
            self.running = False
            log.info("Server stopped")

    def stop(self) -> None:
        if self._server and self.running:
            self._server.should_exit = True
            if self._thread:
                self._thread.join(timeout=5)
        self.running = False


def _get_startup_lnk() -> Path:
    try:
        import win32com.client
        folder = win32com.client.Dispatch("WScript.Shell").SpecialFolders("Startup")
        return Path(folder) / f"{APP_NAME}.lnk"
    except Exception:
        return Path(os.environ.get("APPDATA", "")) / r"Microsoft\Windows\Start Menu\Programs\Startup" / f"{APP_NAME}.lnk"


def _is_autostart() -> bool:
    return _get_startup_lnk().exists()


def _set_autostart(enable: bool) -> None:
    lnk = _get_startup_lnk()
    if enable:
        try:
            import win32com.client
            shell = win32com.client.Dispatch("WScript.Shell")
            shortcut = shell.CreateShortCut(str(lnk))
            pythonw = Path(sys.executable).parent / "pythonw.exe"
            shortcut.TargetPath = str(pythonw)
            shortcut.Arguments = f'"{Path(__file__).resolve()}"'
            shortcut.WorkingDirectory = str(_BASE_DIR)
            if _ICON_PATH.exists():
                shortcut.IconLocation = str(_ICON_PATH)
            shortcut.Save()
        except Exception:
            pass
    else:
        if lnk.exists():
            lnk.unlink()


def _load_icon() -> Image.Image:
    for p in (_ICON_PNG, _ICON_PATH):
        if p.exists():
            return Image.open(p)
    return Image.new("RGBA", (64, 64), (99, 102, 241, 255))


def main() -> None:
    log.info("Tray app starting, python=%s", sys.executable)

    mgr = ServerManager()
    icon_image = _load_icon()
    _icon_ref: list[pystray.Icon | None] = [None]

    def _update_title() -> None:
        icon = _icon_ref[0]
        if icon is None:
            return
        if mgr.running:
            icon.title = "Camera Monitor - 运行中"
        elif mgr.error:
            icon.title = f"Camera Monitor - 启动失败"
        else:
            icon.title = "Camera Monitor - 已停止"

    def _status_watcher() -> None:
        """Poll server status and update tray tooltip."""
        while True:
            time.sleep(2)
            _update_title()

    def on_open_web(icon: pystray.Icon, item: pystray.MenuItem) -> None:
        webbrowser.open(WEB_URL)

    def on_start(icon: pystray.Icon, item: pystray.MenuItem) -> None:
        mgr.start()

    def on_stop(icon: pystray.Icon, item: pystray.MenuItem) -> None:
        mgr.stop()

    def on_autostart(icon: pystray.Icon, item: pystray.MenuItem) -> None:
        _set_autostart(not _is_autostart())

    def on_exit(icon: pystray.Icon, item: pystray.MenuItem) -> None:
        mgr.stop()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("打开监控面板", on_open_web, default=True),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("启动服务", on_start, visible=lambda item: not mgr.running),
        pystray.MenuItem("停止服务", on_stop, visible=lambda item: mgr.running),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("开机自启动", on_autostart, checked=lambda item: _is_autostart()),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("退出", on_exit),
    )

    icon = pystray.Icon(APP_NAME, icon_image, "Camera Monitor - 启动中...", menu)
    _icon_ref[0] = icon

    mgr.start()
    threading.Thread(target=_status_watcher, daemon=True).start()

    icon.run()


if __name__ == "__main__":
    main()
