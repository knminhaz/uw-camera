"""Reference-based quality metrics; change statistics are deliberately kept separate."""
from pathlib import Path
import io
import math
import numpy as np
from PIL import Image, ImageOps

MAX_PIXELS = 20_000_000

def rgb(path):
    with Image.open(path) as image:
        if image.width * image.height > MAX_PIXELS:
            raise ValueError("Image exceeds the analysis pixel limit")
        return np.asarray(ImageOps.exif_transpose(image).convert("RGB"), dtype=np.float64)

def gaussian_valid(image, size=11, sigma=1.5):
    weights = np.exp(-((np.arange(size) - size // 2) ** 2) / (2 * sigma * sigma))
    weights /= weights.sum()
    rows = np.lib.stride_tricks.sliding_window_view(image, size, axis=0)
    first = np.einsum("hwck,k->hwc", rows, weights, optimize=False)
    columns = np.lib.stride_tricks.sliding_window_view(first, size, axis=1)
    return np.einsum("hwck,k->hwc", columns, weights, optimize=False)

def quality(reference, candidate):
    """RGB PSNR and Gaussian SSIM (11x11, sigma=1.5, population covariance)."""
    if reference.shape != candidate.shape:
        raise ValueError("Ground truth must already match image dimensions and geometry")
    mse = float(np.mean((reference - candidate) ** 2))
    perfect = mse == 0
    psnr = None if perfect else 10 * math.log10(255 * 255 / mse)
    score = None
    if min(reference.shape[:2]) >= 11:
        a = gaussian_valid(reference); b = gaussian_valid(candidate)
        aa = gaussian_valid(reference * reference) - a * a
        bb = gaussian_valid(candidate * candidate) - b * b
        ab = gaussian_valid(reference * candidate) - a * b
        c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
        score = float(np.mean(((2 * a * b + c1) * (2 * ab + c2)) /
                             ((a * a + b * b + c1) * (aa + bb + c2))))
    return {"psnrDb": psnr, "perfectMatch": perfect, "ssim": score, "mse": mse}

def change(baseline, candidate):
    if baseline.shape != candidate.shape:
        return None
    difference = np.abs(baseline - candidate)
    return {"meanAbsoluteDelta": float(difference.mean()),
            "changedPixelsPercent": float(np.any(difference >= 2, axis=2).mean() * 100)}

def thumbnail(source, destination):
    with Image.open(source) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        image.thumbnail((720, 540))
        destination.parent.mkdir(parents=True, exist_ok=True)
        image.save(destination, "JPEG", quality=88)

def difference_png(left, right):
    a, b = rgb(left), rgb(right)
    if a.shape != b.shape:
        raise ValueError("Select two stages with matching dimensions")
    delta = np.abs(a-b).mean(axis=2)
    strength = np.clip(delta / 40, 0, 1)
    colors = np.stack((245 * strength, 90 * strength, 25 + 190 * strength), axis=2)
    image = Image.fromarray(colors.astype(np.uint8))
    buffer = io.BytesIO(); image.save(buffer, "PNG")
    return buffer.getvalue()