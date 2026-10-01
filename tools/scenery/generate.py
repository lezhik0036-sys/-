"""
Renders MAMA's background landscapes (no photos, no network): layered
mountains from fractal noise with atmospheric perspective, conifer forests,
mist, a lake with a blurred reflection, sky glow, stars, film grain.

    pip install numpy pillow scipy
    python3 tools/scenery/generate.py   # writes app/src/main/assets/scenes/*.jpg

Deterministic (fixed seeds), so the output is reproducible. Replace the JPGs
with final photography later; the app only reads the files.
"""
import os

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, map_coordinates

W, H = 1080, 2340
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "scenes")


def fbm1d(n, seed, octaves=7, base=3.0, rough=0.55):
    rng = np.random.default_rng(seed)
    x = np.linspace(0, 1, n)
    out = np.zeros(n)
    amp, freq = 1.0, base
    for _ in range(octaves):
        k = int(freq) + 2
        pts = rng.uniform(-1, 1, k)
        xs = np.linspace(0, 1, k)
        out += amp * np.interp(x, xs, pts)
        amp *= rough
        freq *= 2.1
    return out / 2.0


def noise2d(h, w, seed, scale, octaves=5):
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp = 1.0
    for o in range(octaves):
        sh, sw = max(2, int(h / scale) + 2), max(2, int(w / scale) + 2)
        g = rng.uniform(-1, 1, (sh, sw))
        yy, xx = np.mgrid[0:h, 0:w]
        out += amp * map_coordinates(g, [yy / scale, xx / scale], order=3, mode="reflect")
        amp *= 0.5
        scale /= 2.0
    return out


def lerp(a, b, t):
    return a + (b - a) * t


def col(hexs):
    hexs = hexs.lstrip("#")
    return np.array([int(hexs[i:i + 2], 16) for i in (0, 2, 4)], dtype=float) / 255.0


def render(cfg, name):
    img = np.zeros((H, W, 3))
    yy, xx = np.mgrid[0:H, 0:W]
    v = yy / H

    # Sky: vertical gradient + glow + soft cloud banding.
    top, mid, low = col(cfg["sky"][0]), col(cfg["sky"][1]), col(cfg["sky"][2])
    t = np.clip(v / cfg["horizon"], 0, 1)[..., None]
    sky = np.where(t < 0.6, lerp(top, mid, t / 0.6), lerp(mid, low, (t - 0.6) / 0.4))
    gx, gy, gr = cfg["glow_pos"]
    d = np.sqrt(((xx / W - gx) * 1.0) ** 2 + ((yy / H - gy) * 0.55) ** 2)
    glow = np.exp(-(d / gr) ** 2)[..., None]
    sky = sky + col(cfg["glow"]) * glow * cfg["glow_strength"]
    clouds = gaussian_filter(noise2d(H, W, cfg["seed"] + 5, 420, 4), (6, 40))
    sky = sky + (clouds[..., None] * 0.05) * col(cfg["cloud"])
    img[:] = sky
    if cfg.get("sun"):
        sx, sy, sr = cfg["sun"]
        sd = np.sqrt((xx - sx * W) ** 2 + (yy - sy * H) ** 2)
        img += (np.clip(1 - sd / sr, 0, 1) ** 0.4)[..., None] * col("#FFF1CF") * 0.9
        img += (np.exp(-(sd / (sr * 9)) ** 2) * 0.35)[..., None] * col(cfg["glow"])

    if cfg.get("stars"):
        rng = np.random.default_rng(cfg["seed"] + 9)
        n = 900
        sx = rng.integers(0, W, n)
        sy = (rng.random(n) ** 1.6 * H * cfg["horizon"] * 0.75).astype(int)
        b = rng.random(n) ** 3
        star = np.zeros((H, W))
        star[sy, sx] = b
        star = gaussian_filter(star, 0.8) * 6 + gaussian_filter(star, 2.5) * 3
        img += star[..., None] * np.array([1.0, 1.0, 0.95])
        mx, my, mr = cfg["moon"]
        md = np.sqrt((xx - mx * W) ** 2 + (yy - my * H) ** 2)
        img += (np.clip(1 - md / mr, 0, 1) ** 0.25)[..., None] * np.array([0.92, 0.94, 0.86])
        img += (np.exp(-(md / (mr * 6)) ** 2) * 0.18)[..., None] * col(cfg["glow"])

    horizon_px = int(cfg["horizon"] * H)
    fog = col(cfg["fog"])
    # Mountain layers, far to near.
    for i, layer in enumerate(cfg["layers"]):
        base, amp, seed, colr, haze, forest = layer
        ridge = fbm1d(W, seed, octaves=8, base=layer[1] * 30 + 2) * amp * H
        ridge = base * H - (ridge - ridge.min()) * 0.9 - amp * H * 0.15
        ridge = gaussian_filter(ridge, 1.5)
        mask = (yy >= ridge[None, :]).astype(float)
        mask = gaussian_filter(mask, 0.9)
        # Rock texture and light from the glow side.
        tex = noise2d(H, W, seed + 100, 90, 5)
        gullies = gaussian_filter(noise2d(H, W, seed + 140, 30, 3), (6, 2))
        slope = gaussian_filter(np.gradient(ridge), 14)
        side = np.sign(np.arange(W) - gx * W)
        lit = np.clip(0.5 - slope * side * 0.35, 0, 1)
        below = np.clip((yy - ridge[None, :]) / (amp * H * 1.2 + 1), 0, 1)
        shade = 1 + 0.10 * tex + 0.05 * gullies + cfg.get("rim", 0.25) * (lit[None, :] - 0.5) * (1 - below) * (1 - haze)
        depth = np.clip((yy - ridge[None, :]) / (amp * H * 2.5 + 1), 0, 1)
        c = col(colr)[None, None, :] * shade[..., None]
        c = lerp(c, fog[None, None, :], (haze * (1 - 0.5 * depth))[..., None])
        if forest:
            # Dense conifer canopy: sharp spiky noise along the ridge.
            rng = np.random.default_rng(seed + 7)
            spikes = np.zeros(W)
            for _ in range(int(W / 4)):
                x0 = rng.integers(0, W)
                hgt = forest * H * (0.4 + rng.random())
                wid = hgt * 0.22
                xs = np.arange(max(0, int(x0 - wid)), min(W, int(x0 + wid)))
                prof = hgt * (1 - np.abs(xs - x0) / wid)
                spikes[xs] = np.maximum(spikes[xs], prof)
            canopy = ridge - spikes
            fmask = (yy >= canopy[None, :]).astype(float)
            fmask = gaussian_filter(fmask, 0.7)
            mask = np.maximum(mask, fmask)
            ftex = noise2d(H, W, seed + 33, 14, 3)
            fcol = col(cfg["forest"])[None, None, :] * (1 + 0.12 * ftex[..., None])
            fcol = lerp(fcol, fog[None, None, :], (haze * 0.6)[..., None] if np.ndim(haze) else haze * 0.6)
            c = np.where((fmask > 0.5)[..., None], fcol, c)
        img = lerp(img, c, mask[..., None])
        # Mist above the next layer.
        mist = np.exp(-((yy - (base * H + amp * H * 0.3)) / (H * 0.035)) ** 2)
        mist *= 0.55 + 0.45 * gaussian_filter(noise2d(H, W, seed + 3, 260, 3), (4, 30))
        img = lerp(img, fog[None, None, :], (mist * cfg["mist"])[..., None])

    # Lake: blurred, darkened mirror of the scene above the waterline, with ripples.
    if cfg.get("lake"):
        wl = horizon_px
        above = img[:wl].copy()
        refl = above[::-1][: H - wl]
        if refl.shape[0] < H - wl:
            refl = np.concatenate([refl, np.repeat(refl[-1:], H - wl - refl.shape[0], 0)])
        ripple = noise2d(H - wl, W, cfg["seed"] + 21, 6, 2)
        shift = (ripple * 6).astype(int)
        cols = np.clip(np.arange(W)[None, :] + shift, 0, W - 1)
        refl = refl[np.arange(H - wl)[:, None], cols]
        refl = gaussian_filter(refl, (3, 1.5, 0))
        deep = np.linspace(0, 1, H - wl)[:, None, None]
        water = lerp(refl * 0.72, col(cfg["water"])[None, None, :], deep * 0.75)
        lines = (np.sin(np.arange(H - wl) * 0.9 + ripple[:, :1].ravel() * 3)[:, None] > 0.96) * 0.025
        img[wl:] = water + lines[..., None]
        # Shore line.
        shore = gaussian_filter((np.abs(yy - wl) < 3).astype(float), 1.5)
        img = lerp(img, col(cfg["forest"])[None, None, :] * 0.8, (shore * 0.7)[..., None])

    # Foreground pines framing the edges: ragged branch tiers, thin tops.
    rng = np.random.default_rng(cfg["seed"] + 77)
    fg = np.zeros((H, W))
    for side in (0, 1):
        for _ in range(8):
            x0 = (rng.uniform(-0.02, 0.2) if side == 0 else rng.uniform(0.8, 1.02)) * W
            top_y = horizon_px - rng.uniform(0.10, 0.38) * H
            bot_y = horizon_px + 0.02 * H
            hgt = bot_y - top_y
            rows = int(bot_y) - int(top_y)
            ragged = gaussian_filter(rng.normal(0, 1, rows), 1.2)
            lean = np.cumsum(rng.normal(0, 0.15, rows))
            tiers = rng.uniform(7, 11)
            for k, y in enumerate(range(int(top_y), int(bot_y))):
                p = k / hgt
                tier = (p * tiers + rng.uniform(0, 0.05)) % 1
                half = (0.012 + 0.16 * p ** 0.9) * hgt * 0.32 * (0.35 + 0.65 * tier ** 0.7) * (1 + 0.35 * ragged[k])
                half = max(half, 2.0)
                c0 = x0 + lean[k] * 0.6
                a, b = int(max(0, c0 - half)), int(min(W, c0 + half))
                if a < b:
                    fg[y, a:b] = 1
    fg = np.maximum(fg, gaussian_filter(fg, 1.2) * 0.9)
    fg = gaussian_filter(fg, 0.8)
    if cfg.get("lake"):
        fg_refl = fg[: horizon_px][::-1][: H - horizon_px]
        fg[horizon_px: horizon_px + fg_refl.shape[0]] = np.maximum(
            fg[horizon_px: horizon_px + fg_refl.shape[0]], gaussian_filter(fg_refl, (4, 2)) * 0.6
        )
    img = lerp(img, col(cfg["forest"])[None, None, :] * 0.7, fg[..., None])

    # Readability overlay, vignette, grain.
    ov_top, ov_bot = cfg["overlay"]
    over = np.where(v < 0.35, ov_top * (1 - v / 0.35), ov_bot * np.clip((v - 0.45) / 0.55, 0, 1) ** 1.2)
    img = lerp(img, col(cfg["overlay_color"])[None, None, :], over[..., None])
    vig = ((xx / W - 0.5) ** 2 + (yy / H - 0.5) ** 2 * 0.6)
    img *= (1 - 0.45 * vig)[..., None]
    grain = np.random.default_rng(cfg["seed"] + 1).normal(0, cfg["grain"], (H, W, 1))
    img = img + grain
    img = np.clip(img, 0, 1)
    out = Image.fromarray((img * 255).astype(np.uint8))
    os.makedirs(OUT, exist_ok=True)
    out.save(os.path.join(OUT, name + ".jpg"), quality=86, optimize=True, progressive=True)
    print("wrote", name)


DAWN = dict(
    seed=11, horizon=0.66, sun=(0.60, 0.405, 24), rim=0.45,
    sky=["#5E7A86", "#C9AE86", "#F4CF93"], glow="#FFD796", glow_pos=(0.60, 0.46, 0.30), glow_strength=0.60,
    cloud="#FFE9C8", fog="#E9C38F", forest="#17231B", water="#24342B", mist=0.40,
    layers=[
        (0.44, 0.10, 101, "#A0927F", 0.55, 0),
        (0.50, 0.09, 102, "#727563", 0.38, 0),
        (0.57, 0.07, 103, "#3E4C3B", 0.20, 0.020),
        (0.63, 0.05, 104, "#212F26", 0.07, 0.035),
    ],
    lake=True, overlay=(0.08, 0.80), overlay_color="#0B1712", grain=0.018,
)

NIGHT = dict(
    seed=21, horizon=0.64, stars=True, moon=(0.74, 0.15, 16),
    sky=["#06110E", "#15282207", "#324739"][:2] + ["#324739"], glow="#C6D39D", glow_pos=(0.5, 0.50, 0.38), glow_strength=0.20,
    cloud="#9FB3A6", fog="#2D4339", forest="#060E0B", water="#06100C", mist=0.30,
    layers=[
        (0.45, 0.11, 201, "#2B3D35", 0.55, 0),
        (0.51, 0.09, 202, "#1D2E27", 0.38, 0),
        (0.58, 0.07, 203, "#12201A", 0.20, 0.020),
        (0.64, 0.05, 204, "#0A1511", 0.06, 0.035),
    ],
    lake=True, overlay=(0.40, 0.88), overlay_color="#091714", grain=0.014,
)
NIGHT["sky"] = ["#06110E", "#152822", "#324739"]

DUSK = dict(
    seed=31, horizon=0.64,
    sky=["#121A18", "#3A3B33", "#73604E"], glow="#E39C7B", glow_pos=(0.5, 0.48, 0.35), glow_strength=0.35,
    cloud="#C9A48E", fog="#4A473D", forest="#080E0B", water="#0A100D", mist=0.25,
    layers=[
        (0.45, 0.10, 301, "#4A4C43", 0.55, 0),
        (0.51, 0.09, 302, "#30362F", 0.38, 0),
        (0.58, 0.07, 303, "#1C231F", 0.18, 0.020),
        (0.64, 0.05, 304, "#0F1512", 0.05, 0.035),
    ],
    lake=True, overlay=(0.45, 0.90), overlay_color="#091714", grain=0.014,
)

if __name__ == "__main__":
    render(DAWN, "dawn")
    render(NIGHT, "night")
    render(DUSK, "dusk")
