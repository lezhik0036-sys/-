"""Converts shots/*.png to compact JPGs (540 px wide) in shots_jpg/."""
import glob
import os

from PIL import Image

os.makedirs("shots_jpg", exist_ok=True)
for path in sorted(glob.glob("shots/*.png")):
    img = Image.open(path).convert("RGB")
    w = 540
    img = img.resize((w, int(img.height * w / img.width)), Image.LANCZOS)
    out = os.path.join("shots_jpg", os.path.basename(path)[:-4] + ".jpg")
    img.save(out, quality=82, optimize=True)
    print(out, os.path.getsize(out))
