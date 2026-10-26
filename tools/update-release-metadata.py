#!/usr/bin/env python3
"""Publish the locally built APK and module ZIP into dist/site for a release."""
from __future__ import annotations

import hashlib
import json
import re
from datetime import date
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = sys.argv[1] if len(sys.argv) > 1 else "0.3.4"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
MODULE = ROOT / "dist/daepilot-module.zip"
SITE = ROOT / "dist/site"
OUT_DIR = SITE / "dl" / VERSION
CHUNK = 1024 * 1024
APK_NAME = f"DaePilot-{VERSION}-debug.apk"

if OUT_DIR.exists():
    raise SystemExit(f"refusing to overwrite existing release directory: {OUT_DIR}")
if not APK.is_file() or not MODULE.is_file():
    raise SystemExit("build APK and module ZIP before publishing release metadata")

html_path = SITE / "index.html"
html = html_path.read_text(encoding="utf-8")

# Detect previous version from html
match = re.search(r'<span class="version">([\d\.]+) · debug</span>', html)
if not match:
    raise SystemExit("could not detect current version in index.html")
PREV_VERSION = match.group(1)

OUT_DIR.mkdir(parents=True)
try:
    assets = [(APK, APK_NAME, "apk"), (MODULE, "daepilot-module.zip", "zip")]
    chunk_hashes: dict[str, list[str]] = {}
    sums: list[str] = []
    for source, name, key in assets:
        target = OUT_DIR / name
        shutil.copyfile(source, target)
        data = target.read_bytes()
        sums.append(f"{hashlib.sha256(data).hexdigest()}  {name}")
        target_txt = OUT_DIR / f"{name}.txt"
        shutil.copyfile(target, target_txt)
        chunk_hashes[key] = [
            hashlib.sha256(data[i : i + CHUNK]).hexdigest()
            for i in range(0, len(data), CHUNK)
        ]
    (OUT_DIR / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="ascii")

    apk_size = APK.stat().st_size
    zip_size = MODULE.stat().st_size
    apk_mib = apk_size / (1024 * 1024)
    zip_mib = zip_size / (1024 * 1024)
    apk_hash = hashlib.sha256(APK.read_bytes()).hexdigest()
    zip_hash = hashlib.sha256(MODULE.read_bytes()).hexdigest()

    # Read previous values
    prev_apk_size = re.search(r'data-name="DaePilot-[\d\.]+-debug\.apk"[^>]*data-size="(\d+)"', html)
    prev_zip_size = re.search(r'data-name="daepilot-module\.zip"[^>]*data-size="(\d+)"', html)
    prev_apk_hash = re.search(r'<div><b>APK SHA-256</b><br><code>([a-f0-9]{64})</code></div>', html)
    prev_zip_hash = re.search(r'<div><b>模块 SHA-256</b><br><code>([a-f0-9]{64})</code></div>', html)

    # Replace version
    html = html.replace(PREV_VERSION, VERSION)

    if prev_apk_size:
        html = html.replace(prev_apk_size.group(1), str(apk_size))
        html = html.replace(f"{int(prev_apk_size.group(1)):,}", f"{apk_size:,}")
    if prev_zip_size:
        html = html.replace(prev_zip_size.group(1), str(zip_size))
        html = html.replace(f"{int(prev_zip_size.group(1)):,}", f"{zip_size:,}")

    if prev_apk_hash:
        html = html.replace(prev_apk_hash.group(1), apk_hash)
    if prev_zip_hash:
        html = html.replace(prev_zip_hash.group(1), zip_hash)

    html = re.sub(r'<small>[\d\.]+ MiB</small>', f'<small>{apk_mib:.1f} MiB</small>', html, count=1)
    # The second one is zip size
    def repl_zip(m):
        return f'<small>{zip_mib:.1f} MiB</small>'
    html = re.sub(r'(模块 zip\s*)<small>[\d\.]+ MiB</small>', rf'\g<1><small>{zip_mib:.1f} MiB</small>', html)

    html = html.replace("2026-10-26", date.today().isoformat())
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version_code = re.search(r"^\s*versionCode\s*=\s*(\d+)", gradle, re.MULTILINE)
    if not version_code:
        raise RuntimeError("could not read versionCode from app/build.gradle.kts")
    html = re.sub(r"versionCode \d+", f"versionCode {version_code.group(1)}", html, count=1)

    module_version = re.search(r'buildConfigField\("String", "MODULE_VERSION", "\\"([\d\.]+)\\""\)', gradle)
    if module_version:
        html = re.sub(r"模块 [\d\.]+", f"模块 {module_version.group(1)}", html, count=1)

    start = '<script id="chunk-hashes" type="application/json">'
    a = html.index(start) + len(start)
    b = html.index("</script>", a)
    html = html[:a] + json.dumps(chunk_hashes, separators=(",", ":")) + html[b:]
    html_path.write_text(html, encoding="utf-8")
except Exception:
    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR)
    raise

print(f"published local release assets to {OUT_DIR}")
print(f"APK: {apk_size} bytes sha256={apk_hash}")
print(f"module: {zip_size} bytes sha256={zip_hash}")
