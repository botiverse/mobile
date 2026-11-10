#!/usr/bin/env python3
"""Verify the composed store screenshots and write manifest.json + a single-file review page.

Usage: review.py <final dir>

Checks every PNG under <final>/<store>/<device>/<lang>/ against the size its device must have
(and that it has no alpha channel), writes <final>/manifest.json, and <final>/index.html with
JPEG thumbnails (at most 540px wide) embedded, grouped by store / device / language.
Exits non-zero on any size or alpha mismatch.
"""
import base64
import io
import json
import os
import sys

from PIL import Image

EXPECTED = {
    ("play", "phone"): (1080, 1920),
    ("play", "tablet-7"): (1920, 1080),
    ("play", "tablet-10"): (2560, 1440),
    ("play", "feature-graphic"): (1024, 500),
    ("appstore", "iphone-6.9"): (1320, 2868),
    ("appstore", "iphone-6.5"): (1284, 2778),
    ("appstore", "ipad-13"): (2064, 2752),
    ("appstore", "duo-inner-portrait"): (2007, 2853),
    ("appstore", "duo-inner-landscape"): (2853, 2007),
    ("appstore", "duo-outer"): (1398, 2034),
}
DEVICE_ORDER = [d for _, d in EXPECTED]
LANG_NAMES = {"en": "English", "zh-Hans": "简体中文", "ja": "日本語"}

root = os.path.abspath(sys.argv[1])
files, errors = [], []
for store in ("play", "appstore"):
    for device in DEVICE_ORDER:
        ddir = os.path.join(root, store, device)
        if not os.path.isdir(ddir):
            continue
        for lang in sorted(os.listdir(ddir), key=lambda l: list(LANG_NAMES).index(l) if l in LANG_NAMES else 99):
            for name in sorted(os.listdir(os.path.join(ddir, lang))):
                if not name.endswith(".png"):
                    continue
                rel = f"{store}/{device}/{lang}/{name}"
                im = Image.open(os.path.join(root, rel))
                want = EXPECTED[(store, device)]
                if im.size != want:
                    errors.append(f"{rel}: {im.size} != {want}")
                if im.mode != "RGB":
                    errors.append(f"{rel}: mode {im.mode} (want RGB, no alpha)")
                files.append({"path": rel, "store": store, "device": device, "lang": lang,
                              "scene": name[:-4], "width": im.width, "height": im.height, "mode": im.mode})

with open(os.path.join(root, "manifest.json"), "w") as f:
    json.dump({"count": len(files), "files": files}, f, indent=1, ensure_ascii=False)


def thumb(rel):
    im = Image.open(os.path.join(root, rel)).convert("RGB")
    width = min(540, im.width) if im.width > im.height else min(300, im.width)
    im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


parts = []
for store, title in (("play", "Google Play"), ("appstore", "App Store")):
    store_files = [f for f in files if f["store"] == store]
    if not store_files:
        continue
    parts.append(f"<h2>{title}</h2>")
    for device in DEVICE_ORDER:
        dev = [f for f in store_files if f["device"] == device]
        if not dev:
            continue
        w, h = EXPECTED[(store, device)]
        parts.append(f"<h3>{device} <small>{w}×{h}</small></h3>")
        for lang in LANG_NAMES:
            group = [f for f in dev if f["lang"] == lang]
            if not group:
                continue
            parts.append(f'<div class="lang">{LANG_NAMES[lang]} <code>{lang}</code></div><div class="row">')
            for f in group:
                cls = "land" if f["width"] > f["height"] else "port"
                parts.append(f'<figure class="{cls}"><img src="{thumb(f["path"])}"><figcaption>{f["path"]}</figcaption></figure>')
            parts.append("</div>")

status = f'<p class="bad">{len(errors)} problems: {"; ".join(errors)}</p>' if errors else \
    f'<p class="ok">All {len(files)} PNGs verified: exact store pixel size, RGB without alpha.</p>'
page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Raft store screenshots</title>
<style>
body{{margin:0;padding:32px 40px 80px;background:#FFFAEF;color:#141111;font:16px/1.5 system-ui,-apple-system,'Segoe UI',sans-serif}}
h1{{font-size:30px;margin:0 0 6px}} h2{{margin:48px 0 8px;font-size:26px;border-bottom:3px solid #141111;padding-bottom:6px}}
h3{{margin:30px 0 6px;font-size:20px}} h3 small{{font-weight:400;color:#6b6262;font-size:14px}}
.lang{{margin:12px 0 6px;font-weight:600}}
.row{{display:flex;gap:14px;overflow-x:auto;padding-bottom:8px}}
figure{{flex:none;margin:0}} .port img{{width:200px}} .land img{{width:400px}}
img{{border:2px solid #141111;border-radius:8px;display:block}}
figcaption{{font:11px/1.4 ui-monospace,monospace;color:#6b6262;margin-top:3px}}
.ok{{color:#2c6b1f;font-weight:600}} .bad{{color:#b00020;font-weight:600}}
</style></head><body>
<h1>Raft store screenshots</h1>
<p>Style A (raft-ui brutalist). App UI rendered from the production Compose screens with fictional demo content.
Windows of 840dp and wider use the app's wide layout (top bar, rail, list + detail). Thumbnails here; full PNGs sit next to this file.</p>
{status}
{''.join(parts)}
</body></html>"""
with open(os.path.join(root, "index.html"), "w") as f:
    f.write(page)
print(f"{len(files)} files, index.html {os.path.getsize(os.path.join(root, 'index.html')) // 1024} KB")
if errors:
    print("\n".join(errors))
    sys.exit(1)
