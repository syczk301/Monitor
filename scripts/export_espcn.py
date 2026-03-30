"""Export a trained ESPCN 2x super-resolution model to TorchScript Lite format."""

from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F


class ESPCN(nn.Module):
    """Efficient Sub-Pixel CNN for 2x upscaling (3-channel RGB)."""

    def __init__(self, upscale_factor: int = 2) -> None:
        super().__init__()
        self.conv1 = nn.Conv2d(3, 64, kernel_size=5, padding=2)
        self.conv2 = nn.Conv2d(64, 32, kernel_size=3, padding=1)
        self.conv3 = nn.Conv2d(32, 3 * upscale_factor ** 2, kernel_size=3, padding=1)
        self.pixel_shuffle = nn.PixelShuffle(upscale_factor)
        self.relu = nn.ReLU(inplace=True)
        self._init_weights()

    def _init_weights(self) -> None:
        for m in [self.conv1, self.conv2]:
            nn.init.kaiming_normal_(m.weight, mode="fan_in", nonlinearity="relu")
            if m.bias is not None:
                nn.init.zeros_(m.bias)
        nn.init.normal_(self.conv3.weight, mean=0.0, std=0.001)
        if self.conv3.bias is not None:
            nn.init.zeros_(self.conv3.bias)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.relu(self.conv1(x))
        x = self.relu(self.conv2(x))
        x = self.pixel_shuffle(self.conv3(x))
        return torch.clamp(x, 0.0, 1.0)


def generate_hr_batch(batch_size: int, patch_size: int) -> torch.Tensor:
    """Generate synthetic HR patches with varied textures and edges."""
    hr = torch.zeros(batch_size, 3, patch_size, patch_size)
    x = torch.linspace(0, 1, patch_size).view(1, 1, 1, patch_size)
    y = torch.linspace(0, 1, patch_size).view(1, 1, patch_size, 1)

    for i in range(batch_size):
        n_components = torch.randint(2, 6, (1,)).item()
        img = torch.zeros(1, 3, patch_size, patch_size)
        for _ in range(n_components):
            freq_x = (torch.rand(1) * 12 + 0.5).item()
            freq_y = (torch.rand(1) * 12 + 0.5).item()
            phase = (torch.rand(1) * 6.283).item()
            amp = (torch.rand(1) * 0.3 + 0.05).item()
            color = torch.rand(3).view(1, 3, 1, 1)
            pattern = torch.sin(x * freq_x + y * freq_y + phase) * amp
            img += pattern * color

        edge_prob = torch.rand(1).item()
        if edge_prob > 0.5:
            cx = torch.rand(1).item()
            direction = 1.0 if torch.rand(1).item() > 0.5 else -1.0
            edge = torch.sigmoid((x - cx) * direction * 20) * 0.3
            edge_color = torch.rand(3).view(1, 3, 1, 1)
            img += edge * edge_color

        img = img + 0.4
        noise = torch.randn_like(img) * 0.01
        img = (img + noise).clamp(0.0, 1.0)
        hr[i] = img[0]

    return hr


def train(model: ESPCN, epochs: int = 600, batch_size: int = 16, patch_size: int = 128) -> None:
    """Train on synthetic LR-HR pairs (bicubic downscale -> original)."""
    model.train()
    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
    scheduler = torch.optim.lr_scheduler.StepLR(optimizer, step_size=200, gamma=0.5)
    criterion = nn.MSELoss()

    for epoch in range(1, epochs + 1):
        hr = generate_hr_batch(batch_size, patch_size)
        lr = F.interpolate(hr, scale_factor=0.5, mode="bicubic", align_corners=False).clamp(0, 1)

        output = model(lr)
        loss = criterion(output, hr)

        optimizer.zero_grad()
        loss.backward()
        optimizer.step()
        scheduler.step()

        if epoch % 100 == 0:
            print(f"  epoch {epoch}/{epochs}  loss={loss.item():.6f}  lr={scheduler.get_last_lr()[0]:.6f}")


def main() -> None:
    model = ESPCN(upscale_factor=2)

    print("Training ESPCN on synthetic patches...")
    train(model)

    model.eval()
    example = torch.rand(1, 3, 128, 128)
    with torch.no_grad():
        out = model(example)
    print(f"Input: {example.shape} -> Output: {out.shape}")

    scripted = torch.jit.script(model)
    output_dir = Path(__file__).resolve().parent.parent / "android-app" / "app" / "src" / "main" / "assets"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "espcn_x2.ptl"
    scripted._save_for_lite_interpreter(str(output_path))
    print(f"Saved to {output_path} ({output_path.stat().st_size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
