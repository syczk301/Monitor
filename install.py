"""Create desktop shortcut and optional startup entry for Camera Monitor."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import win32com.client

APP_NAME = "Camera Monitor"
BASE_DIR = Path(__file__).resolve().parent
ICON_PATH = BASE_DIR / "assets" / "tray_icon.ico"
TRAY_SCRIPT = BASE_DIR / "tray_app.py"
_DL_ENV = Path(os.environ.get("USERPROFILE", "~")) / ".conda" / "envs" / "DL"
PYTHONW = _DL_ENV / "pythonw.exe" if (_DL_ENV / "pythonw.exe").exists() else Path(sys.executable).parent / "pythonw.exe"


def _create_shortcut(shortcut_path: Path) -> None:
    shell = win32com.client.Dispatch("WScript.Shell")
    lnk = shell.CreateShortCut(str(shortcut_path))
    lnk.TargetPath = str(PYTHONW)
    lnk.Arguments = f'"{TRAY_SCRIPT}"'
    lnk.WorkingDirectory = str(BASE_DIR)
    lnk.Description = "Camera Monitor - 智能监控系统"
    if ICON_PATH.exists():
        lnk.IconLocation = str(ICON_PATH)
    lnk.Save()


def install_desktop() -> None:
    desktop = Path(os.environ.get("USERPROFILE", "~")) / "Desktop"
    lnk = desktop / f"{APP_NAME}.lnk"
    _create_shortcut(lnk)
    print(f"[OK] 桌面快捷方式: {lnk}")


def install_startup() -> None:
    startup = Path(
        win32com.client.Dispatch("WScript.Shell")
        .SpecialFolders("Startup")
    )
    lnk = startup / f"{APP_NAME}.lnk"
    _create_shortcut(lnk)
    print(f"[OK] 开机自启动: {lnk}")


def uninstall_startup() -> None:
    startup = Path(
        win32com.client.Dispatch("WScript.Shell")
        .SpecialFolders("Startup")
    )
    lnk = startup / f"{APP_NAME}.lnk"
    if lnk.exists():
        lnk.unlink()
        print(f"[OK] 已移除开机自启动: {lnk}")
    else:
        print("[--] 开机自启动未设置")


def main() -> None:
    print(f"=== {APP_NAME} 安装工具 ===")
    print(f"Python: {PYTHONW}")
    print(f"项目目录: {BASE_DIR}")
    print()
    print("1. 创建桌面快捷方式")
    print("2. 创建桌面快捷方式 + 开机自启动")
    print("3. 仅开机自启动")
    print("4. 移除开机自启动")
    print("0. 退出")
    print()

    choice = input("请选择 [1]: ").strip() or "1"

    if choice == "1":
        install_desktop()
    elif choice == "2":
        install_desktop()
        install_startup()
    elif choice == "3":
        install_startup()
    elif choice == "4":
        uninstall_startup()
    elif choice == "0":
        return
    else:
        print("无效选择")
        return

    print("\n完成! 双击桌面图标即可启动监控服务。")


if __name__ == "__main__":
    main()
