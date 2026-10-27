"""Generates the 3D preview backdrop: a calm daylight landscape (grass, oak trees, hills, sky),
seamless horizontally, softly blurred. Original art, generated procedurally."""
import math
import random
import numpy as np
from PIL import Image

W, H = 512, 256
OUT = "src/main/resources/assets/flanwarfaredesigner/textures/gui/preview_backdrop.png"
rnd = random.Random(1609)
img = np.zeros((H, W, 3), dtype=np.float32)


def periodic(x, terms):
    return sum(a * math.sin(2 * math.pi * f * x / W + p) for a, f, p in terms)


def blend(c1, c2, t):
    return tuple(c1[i] * (1 - t) + c2[i] * t for i in range(3))


# Sky: soft gradient, warm glow around the sun.
SKY_TOP, SKY_HORIZON = (104, 160, 228), (206, 230, 248)
for y in range(H):
    img[y, :, :] = blend(SKY_TOP, SKY_HORIZON, min(1.0, y / 170.0))
sun_x, sun_y = 360, 46
for y in range(H):
    for x in range(W):
        dx = min(abs(x - sun_x), W - abs(x - sun_x))
        d = math.hypot(dx, (y - sun_y) * 1.1)
        g = math.exp(-(d / 60.0) ** 2) * 0.55 + math.exp(-(d / 14.0) ** 2) * 0.6
        if g > 0.004:
            img[y, x] = blend(img[y, x], (255, 247, 222), min(1.0, g))

# Blocky Minecraft-style clouds (8 px cells), wrapping around.
for _ in range(9):
    cx, cy = rnd.randrange(W), rnd.randrange(18, 70)
    cells = set()
    width = rnd.randrange(4, 9)
    for gx in range(width):
        cells.add((gx, 0))
        if 0 < gx < width - 1 and rnd.random() < 0.7:
            cells.add((gx, 1))
        if 1 < gx < width - 2 and rnd.random() < 0.35:
            cells.add((gx, -1))
    for (gx, gy) in cells:
        for yy in range(cy + gy * 6, cy + gy * 6 + 6):
            for xx in range(cx + gx * 8, cx + gx * 8 + 8):
                img[yy, xx % W] = blend(img[yy, xx % W], (250, 252, 255), 0.82)


def hill_layer(base_y, terms, color, top_color, haze, step):
    heights = []
    for x in range(W):
        qx = (x // step) * step
        h = base_y - periodic(qx, terms)
        heights.append(int(round(h / 2.0) * 2))
    for x in range(W):
        for y in range(max(0, heights[x]), H):
            c = top_color if y < heights[x] + 3 else color
            img[y, x] = blend(c, SKY_HORIZON, haze)
    return heights


def tree(x0, ground, scale, haze, trunk=(104, 76, 46), leaves=(58, 128, 48), dark=(44, 104, 38)):
    tw, th = max(2, int(4 * scale)), int(9 * scale)
    lw, lh = int(24 * scale), int(20 * scale)
    for y in range(ground - th, ground):
        for x in range(x0 - tw // 2, x0 - tw // 2 + tw):
            img[y, x % W] = blend(trunk, SKY_HORIZON, haze)
    top = ground - th - lh + int(4 * scale)
    for y in range(top, top + lh):
        for x in range(x0 - lw // 2, x0 - lw // 2 + lw):
            if (y - top < 3 or y - top > lh - 4) and (x - (x0 - lw // 2) < 3 or x - (x0 - lw // 2) > lw - 4):
                continue  # rounded-ish corners
            cell = ((x // max(2, int(4 * scale))) + (y // max(2, int(4 * scale)))) % 3 == 0
            img[y, x % W] = blend(dark if cell else leaves, SKY_HORIZON, haze)


# Far hills (hazy) with tiny trees.
far = hill_layer(150, [(14, 2, 0.4), (7, 5, 2.1), (3, 11, 4.0)], (112, 158, 120), (126, 170, 128), 0.45, 4)
for i in range(20):
    x = (i * 26 + rnd.randrange(0, 14)) % W
    tree(x, far[x] + 2, 0.55, 0.42)
# Middle hills with oak trees.
mid = hill_layer(186, [(12, 3, 1.3), (6, 7, 0.2), (3, 13, 2.7)], (78, 138, 58), (104, 172, 72), 0.12, 4)
for i in range(9):
    x = (i * 57 + rnd.randrange(6, 30)) % W
    tree(x, mid[x] + 3, 1.0 + rnd.random() * 0.35, 0.12)
# Foreground meadow.
front = hill_layer(222, [(5, 2, 2.2), (3, 6, 0.9)], (86, 150, 60), (112, 184, 76), 0.0, 8)
for x in range(W):
    for y in range(front[x], H):
        n = rnd.random()
        if n < 0.08:
            img[y, x] = blend(img[y, x], (64, 120, 46), 0.6)
        elif n > 0.95:
            img[y, x] = blend(img[y, x], (150, 205, 100), 0.5)
for i in range(70):  # grass tufts and a few flowers
    x = rnd.randrange(W)
    y0 = front[x]
    col = (70, 140, 52) if i % 9 else ((236, 214, 70) if i % 2 else (220, 80, 70))
    for k in range(rnd.randrange(3, 7)):
        img[max(0, y0 - k), x] = col

# Soft focus: separable Gaussian, wrapping horizontally (seamless), clamped vertically.
def gaussian(a, sigma, axis, wrap):
    r = int(3 * sigma)
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    out = np.zeros_like(a)
    for i, w in enumerate(k):
        s = i - r
        if wrap:
            out += w * np.roll(a, s, axis=axis)
        else:
            idx = np.clip(np.arange(a.shape[axis]) - s, 0, a.shape[axis] - 1)
            out += w * np.take(a, idx, axis=axis)
    return out

img = gaussian(img, 1.7, 1, True)
img = gaussian(img, 1.7, 0, False)
# Calm, slightly muted grade so it sits behind a dark UI.
img = img * 0.92 + 8
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(OUT)
print("ok", OUT)
