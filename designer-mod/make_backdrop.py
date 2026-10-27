"""Generates the 3D preview backdrop: a Minecraft-style dusk landscape (twilight sky with a warm
horizon, blocky mountains, a dark forest, stone blocks in front), seamless horizontally, with a
depth-of-field blur that is stronger in the distance. Original art, generated procedurally."""
import math
import random
import numpy as np
from PIL import Image

W, H = 512, 256
OUT = "src/main/resources/assets/flanwarfaredesigner/textures/gui/preview_backdrop.png"
rnd = random.Random(2207)


def periodic(x, terms):
    return sum(a * math.sin(2 * math.pi * f * x / W + p) for a, f, p in terms)


def lerp(c1, c2, t):
    return np.array(c1, np.float32) * (1 - t) + np.array(c2, np.float32) * t


def gaussian(a, sigma, axis, wrap):
    r = max(1, int(3 * sigma))
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


def blur(layer, sigma):
    if sigma <= 0:
        return layer
    return gaussian(gaussian(layer, sigma, 1, True), sigma, 0, False)


def composite(base, layer_rgb, alpha):
    return base * (1 - alpha[..., None]) + layer_rgb * alpha[..., None]


def blurred_layer(base, rgb, a, sigma):
    """Composites a layer with depth blur, premultiplied so edges get no dark halo."""
    ba = blur(a, sigma)
    brgb = blur(rgb * a[..., None], sigma) / np.maximum(ba[..., None], 1e-4)
    return composite(base, brgb, ba)


# Sky: deep blue at the top, violet, then warm orange at the horizon (dusk).
sky = np.zeros((H, W, 3), np.float32)
stops = [(0, (34, 48, 92)), (70, (62, 70, 120)), (120, (150, 96, 110)), (155, (226, 128, 72)), (175, (246, 176, 96)),
         (256, (246, 176, 96))]
for y in range(H):
    for (y0, c0), (y1, c1) in zip(stops, stops[1:]):
        if y0 <= y <= y1:
            sky[y, :] = lerp(c0, c1, (y - y0) / max(1, y1 - y0))
            break
# A few faint blocky clouds catching the light.
for _ in range(7):
    cx, cy, w = rnd.randrange(W), rnd.randrange(60, 120), rnd.randrange(24, 64)
    for yy in range(cy, cy + 5):
        for xx in range(cx, cx + w):
            sky[yy, xx % W] = lerp(sky[yy, xx % W], (236, 150, 120), 0.22)
img = blur(sky, 1.5)


def layer_from_heights(heights, color_fn):
    rgb = np.zeros((H, W, 3), np.float32)
    a = np.zeros((H, W), np.float32)
    for x in range(W):
        for y in range(max(0, heights[x]), H):
            rgb[y, x] = color_fn(x, y, y - heights[x])
            a[y, x] = 1.0
    return rgb, a


def stepped(base_y, terms, step, vstep):
    out = []
    for x in range(W):
        qx = (x // step) * step
        h = base_y - periodic(qx, terms)
        out.append(int(round(h / vstep) * vstep))
    return out


# Far blocky mountains, cool blue-grey, lit warm on the left slopes.
mount = stepped(132, [(34, 2, 0.6), (18, 3, 2.2), (9, 7, 4.1), (5, 13, 1.0)], 8, 6)
def mount_color(x, y, d):
    lit = mount[(x - 8) % W] > mount[x]  # slope rising to the right: catches the sunset
    base = (52, 56, 84) if not lit else (92, 74, 96)
    if (x // 8 + y // 6) % 5 == 0:
        base = tuple(c * 0.88 for c in base)
    if d < 3 and lit:
        base = (150, 104, 104)  # sunlit ridge
    return lerp(base, (120, 86, 104), 0.18)
rgb, a = layer_from_heights(mount, mount_color)
img = blurred_layer(img, rgb, a, 2.8)

# Forest: dense dark spruce and oak canopies, blocky.
forest_rgb = np.zeros((H, W, 3), np.float32)
forest_a = np.zeros((H, W), np.float32)
ground = stepped(196, [(6, 3, 0.4), (3, 8, 2.0)], 8, 4)
for x in range(W):
    for y in range(ground[x], H):
        forest_rgb[y, x] = (16, 30, 22)
        forest_a[y, x] = 1
for i in range(70):
    x0 = (i * 7 + rnd.randrange(0, 7)) % W
    g = ground[x0]
    spruce = rnd.random() < 0.7
    h = rnd.randrange(30, 58)
    shade = rnd.uniform(0.6, 0.95)
    leaf = np.array((24, 48, 34) if spruce else (34, 62, 34), np.float32) * shade
    dark = leaf * 0.72
    for k in range(h):
        y = g - k
        if spruce:
            half = max(1, int((h - k) * 0.28)) if k > 4 else 1
            half = (half // 2) * 2 + 1
        else:
            half = 9 if k > 8 else 1
            if k > h - 4:
                half = 6
        for xx in range(x0 - half, x0 + half + 1):
            cell = ((xx // 3) + (y // 3)) % 4 == 0
            forest_rgb[y, xx % W] = dark if cell else leaf
            forest_a[y, xx % W] = 1
# Warm rim light on the treetops facing the sunset.
for x in range(W):
    col = forest_a[:, x]
    top = int(np.argmax(col > 0)) if col.any() else H
    if top >= ground[x] - 2:
        continue  # no tree here: no rim light on the ground
    for y in range(top, min(H, top + 2)):
        forest_rgb[y, x] = lerp(forest_rgb[y, x], (160, 110, 70), 0.45)
img = blurred_layer(img, forest_rgb, forest_a, 2.0)

# Foreground: grass edge and grey stone bricks at the very bottom (out of focus).
fg_rgb = np.zeros((H, W, 3), np.float32)
fg_a = np.zeros((H, W), np.float32)
stone_top = stepped(230, [(4, 2, 1.3), (3, 5, 0.3)], 16, 4)
for x in range(W):
    for y in range(stone_top[x], H):
        bx, by = x // 16, (y - stone_top[x]) // 8
        tone = 0.85 + 0.15 * ((bx * 7 + by * 3) % 5) / 4
        mortar = (x % 16 == 0) or ((y - stone_top[x]) % 8 == 0)
        c = (58, 58, 62) if mortar else tuple(v * tone for v in (112, 110, 112))
        if y - stone_top[x] < 2:
            c = (70, 108, 52)  # grass lip
        fg_rgb[y, x] = c
        fg_a[y, x] = 1
img = blurred_layer(img, fg_rgb, fg_a, 3.2)

# Atmosphere: warm haze near the horizon, slightly darker corners for a cinematic feel.
yy = np.arange(H)[:, None]
haze = np.exp(-((yy - 150) / 30.0) ** 2) * 0.14
img = img * (1 - haze[..., None]) + np.array((240, 150, 90), np.float32) * haze[..., None]
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(OUT)
print("ok")
