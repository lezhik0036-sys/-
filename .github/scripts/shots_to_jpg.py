"""Converts shots/*.png to compact JPGs (720 px wide) in shots_jpg/.

UI v1 example screens also get a side-by-side image with their approved
reference from docs/design-reference/ (vs-<screen>.jpg), for visual review.
"""
import glob
import os

from PIL import Image

# Screenshot key -> reference file it should be compared with.
REFERENCES = {
    "ds_light": "09-review.webp",
    "ds_scenic": "13-dashboard_active.webp",
    "ds_recovery": "20-failure.webp",
    "ds_lock_shell": "16-lock.webp",
}

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
        ref = Image.open(ref_path).convert("RGB")
        h = img.height
        ref = ref.resize((int(ref.width * h / ref.height), h), Image.LANCZOS)
        pair = Image.new("RGB", (ref.width + img.width + 24, h), (255, 255, 255))
        pair.paste(ref, (0, 0))
        pair.paste(img, (ref.width + 24, 0))
        cmp_out = os.path.join("shots_jpg", "vs-" + key + ".jpg")
        pair.save(cmp_out, quality=84, optimize=True)
        print(cmp_out, os.path.getsize(cmp_out))
