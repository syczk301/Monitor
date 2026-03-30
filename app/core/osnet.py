"""Minimal OSNet (Omni-Scale Network) for person re-identification.

Architecture from: Zhou et al., "Omni-Scale Feature Learning for Person Re-Identification", ICCV 2019.
Pretrained weights from: https://huggingface.co/kaiyangzhou/osnet
"""

from __future__ import annotations

import urllib.request
from pathlib import Path

import torch
import torch.nn as nn

_WEIGHTS_URL = (
    "https://huggingface.co/kaiyangzhou/osnet/resolve/main/"
    "osnet_x1_0_msmt17_combineall_256x128_amsgrad_ep150_stp60_lr0.0015"
    "_b64_fb10_softmax_labelsmooth_flip_jitter.pth"
)
_WEIGHTS_DIR = Path("data")
_WEIGHTS_FILE = _WEIGHTS_DIR / "osnet_x1_0_msmt17.pth"


class _ConvBlock(nn.Module):
    def __init__(self, in_c: int, out_c: int, k: int, s: int = 1, p: int = 0) -> None:
        super().__init__()
        self.conv = nn.Conv2d(in_c, out_c, k, stride=s, padding=p, bias=False)
        self.bn = nn.BatchNorm2d(out_c)
        self.relu = nn.ReLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.relu(self.bn(self.conv(x)))


class _Conv1x1BN(nn.Module):
    def __init__(self, in_c: int, out_c: int) -> None:
        super().__init__()
        self.conv = nn.Conv2d(in_c, out_c, 1, bias=False)
        self.bn = nn.BatchNorm2d(out_c)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.bn(self.conv(x))


class _LightConv3x3(nn.Module):
    def __init__(self, in_c: int, out_c: int) -> None:
        super().__init__()
        self.conv1 = nn.Conv2d(in_c, out_c, 1, bias=False)
        self.conv2 = nn.Conv2d(out_c, out_c, 3, padding=1, groups=out_c, bias=False)
        self.bn = nn.BatchNorm2d(out_c)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.bn(self.conv2(self.conv1(x)))


class _ChannelGate(nn.Module):
    def __init__(self, in_c: int, reduction: int = 16) -> None:
        super().__init__()
        mid = in_c // reduction
        self.global_avgpool = nn.AdaptiveAvgPool2d(1)
        self.fc1 = nn.Conv2d(in_c, mid, 1, bias=True)
        self.relu = nn.ReLU(inplace=True)
        self.fc2 = nn.Conv2d(mid, in_c, 1, bias=True)
        self.sigmoid = nn.Sigmoid()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        g = self.global_avgpool(x)
        g = self.sigmoid(self.fc2(self.relu(self.fc1(g))))
        return x * g


class _OSBlock(nn.Module):
    def __init__(self, in_c: int, out_c: int, br: int = 4) -> None:
        super().__init__()
        mid = out_c // br
        self.conv1 = _ConvBlock(in_c, mid, 1)
        self.conv2a = _LightConv3x3(mid, mid)
        self.conv2b = nn.Sequential(_LightConv3x3(mid, mid), _LightConv3x3(mid, mid))
        self.conv2c = nn.Sequential(
            _LightConv3x3(mid, mid), _LightConv3x3(mid, mid), _LightConv3x3(mid, mid),
        )
        self.conv2d = nn.Sequential(
            _LightConv3x3(mid, mid), _LightConv3x3(mid, mid),
            _LightConv3x3(mid, mid), _LightConv3x3(mid, mid),
        )
        self.gate = _ChannelGate(mid)
        self.conv3 = _Conv1x1BN(mid, out_c)
        self.downsample = _Conv1x1BN(in_c, out_c) if in_c != out_c else None
        self.relu = nn.ReLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        residual = x
        x1 = self.conv1(x)
        x2 = self.gate(self.conv2a(x1) + self.conv2b(x1) + self.conv2c(x1) + self.conv2d(x1))
        x3 = self.conv3(x2)
        if self.downsample is not None:
            residual = self.downsample(residual)
        return self.relu(x3 + residual)


class OSNet(nn.Module):
    """OSNet x1.0 -- outputs 512-dim feature vectors."""

    def __init__(self) -> None:
        super().__init__()
        ch = [64, 256, 384, 512]
        self.conv1 = _ConvBlock(3, ch[0], 7, s=2, p=3)
        self.maxpool = nn.MaxPool2d(3, stride=2, padding=1)
        self.conv2 = nn.Sequential(
            _OSBlock(ch[0], ch[1]), _OSBlock(ch[1], ch[1]),
            nn.Sequential(_ConvBlock(ch[1], ch[1], 1), nn.AvgPool2d(2, 2)),
        )
        self.conv3 = nn.Sequential(
            _OSBlock(ch[1], ch[2]), _OSBlock(ch[2], ch[2]),
            nn.Sequential(_ConvBlock(ch[2], ch[2], 1), nn.AvgPool2d(2, 2)),
        )
        self.conv4 = nn.Sequential(_OSBlock(ch[2], ch[3]), _OSBlock(ch[3], ch[3]))
        self.conv5 = _ConvBlock(ch[3], ch[3], 1)
        self.global_avgpool = nn.AdaptiveAvgPool2d(1)
        self.fc = nn.Sequential(nn.Linear(ch[3], 512, bias=True), nn.BatchNorm1d(512))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.maxpool(self.conv1(x))
        x = self.conv2(x)
        x = self.conv3(x)
        x = self.conv5(self.conv4(x))
        x = self.global_avgpool(x).view(x.size(0), -1)
        return self.fc(x)


def load_osnet(device: str = "cpu") -> OSNet | None:
    """Load OSNet with MSMT17 pretrained weights (auto-download)."""
    _WEIGHTS_DIR.mkdir(parents=True, exist_ok=True)
    if not _WEIGHTS_FILE.exists():
        print(f"Downloading OSNet weights to {_WEIGHTS_FILE} ...")
        try:
            urllib.request.urlretrieve(_WEIGHTS_URL, str(_WEIGHTS_FILE))
            print("Download complete.")
        except Exception as e:
            print(f"Failed to download OSNet weights: {e}")
            return None

    state = torch.load(str(_WEIGHTS_FILE), map_location=device, weights_only=True)
    if "state_dict" in state:
        state = state["state_dict"]

    model = OSNet()
    model_keys = set(model.state_dict().keys())
    loaded, skipped = 0, 0
    filtered = {}
    for k, v in state.items():
        if k in model_keys:
            filtered[k] = v
            loaded += 1
        else:
            skipped += 1
    model.load_state_dict(filtered, strict=False)
    print(f"OSNet loaded: {loaded} matched, {skipped} skipped (classifier etc.)")
    model.eval().to(device)
    return model
