"""Converts shots/*.png to compact JPGs (720 px wide) in shots_jpg/.

UI v1 screens also get a side-by-side image with their approved reference
from docs/design-reference/ (vs-<screen>.jpg), and screens on a production
scene a side-by-side image with that scene asset (scene-<screen>.jpg), for
visual review.
"""
import glob
import os

from PIL import Image

# Screenshot key -> reference file it should be compared with.
REFERENCES = {
    "dashboard_active": "13-dashboard_active.webp",
    "time": "04-time.webp",
    "contact": "06-contact.webp",
    "commitment": "08-commitment.webp",
    "review": "09-review.webp",
    "lock": "16-lock.webp",
    "failure": "20-failure.webp",
    "ds_light": "09-review.webp",
    "ds_scenic": "13-dashboard_active.webp",
    "ds_recovery": "20-failure.webp",
    "ds_lock_shell": "16-lock.webp",
}

# Screenshot key -> production scene it is drawn on (app/src/main/assets/scenes/v1/).
SCENES = {
    "dashboard_active": "morning",
    "time": "day",
    "dashboard": "evening",
    "lock": "dawn",
    "progress_early": "morning",
    "progress_middle": "day",
    "progress_final": "evening",
    "motivation": "day",
    "failure": "recovery",
    "restart_time": "recovery",
    "success": "evening",
    "ds_lock_shell": "dawn",
    "ds_recovery": "recovery",
}


def side_by_side(left_path, img, out):
    left = Image.open(left_path).convert("RGB")
    h = img.height
    left = left.resize((int(left.width * h / left.height), h), Image.LANCZOS)
    pair = Image.new("RGB", (left.width + img.width + 24, h), (255, 255, 255))
    pair.paste(left, (0, 0))
    pair.paste(img, (left.width + 24, 0))
    pair.save(out, quality=84, optimize=True)
    print(out, os.path.getsize(out))


os.makedirs("shots_jpg", exist_ok=True)
for path in sorted(glob.glob("shots/*.png")):
    img = Image.open(path).convert("RGB")
    w = 720
    img = img.resize((w, int(img.height * w / img.width)), Image.LANCZOS)
    name = os.path.basename(path)[:-4]
    out = os.path.join("shots_jpg", name + ".jpg")
    img.save(out, quality=84, optimize=True)
    print(out, os.path.getsize(out))

    key = name.split("-", 1)[1] if "-" in name else name
    ref_name = REFERENCES.get(key)
    ref_path = os.path.join("docs", "design-reference", ref_name) if ref_name else None
    if ref_path and os.path.exists(ref_path):
        side_by_side(ref_path, img, os.path.join("shots_jpg", "vs-" + key + ".jpg"))
    scene = SCENES.get(key)
    scene_path = os.path.join("app", "src", "main", "assets", "scenes", "v1", scene + ".webp") if scene else None
    if scene_path and os.path.exists(scene_path):
        side_by_side(scene_path, img, os.path.join("shots_jpg", "scene-" + key + ".jpg"))
