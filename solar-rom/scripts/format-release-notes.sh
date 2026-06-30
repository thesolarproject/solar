#!/usr/bin/env bash
# Release notes for Solar APK + ROM bundle.
# Usage: format-release-notes.sh <release.json> > RELEASE_NOTES.md
set -euo pipefail

RELEASE_JSON="${1:-release.json}"
[ -f "$RELEASE_JSON" ] || {
    echo "usage: $0 <release.json>" >&2
    exit 1
}

python3 - "$RELEASE_JSON" <<'PY'
import json
import subprocess
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    meta = json.load(handle)

tag = meta["tag"]
version = meta.get("version_name", "")
channel = meta.get("channel", "stable")
sha = meta.get("short_sha", "")

label = "nightly" if channel == "nightly" else "main"
print(f"# Solar {version} ({sha}) — {label}")
print()
print("> **Solar** is a custom firmware and launcher for the [Innioasis Y1](https://github.com/y1-community/InnioasisY1Themes) — a full interface replacement with podcast downloads, a global quick menu, **Reach** (Deezer + Soulseek), Y1 theme support, and **Rockbox-Y1** on the same ROM (switch without reboot).")
print()
print("### Features")
print()
print("- 🎵 **Reach** — search, play and download music from Deezer and Soulseek")
print("- 🎙️ **Podcasts** — search and download episodes to your Y1 over the internet")
print("- ⚡ **Quick menu** — global context menu for playback, queue, Wi‑Fi, Bluetooth, and more")
print("- 🎨 **Y1 themes** — install and apply themes from the original Y1 firmware")
print("- 🔊 **Rockbox-Y1** — co-installed; switch launchers from Settings (no reboot)")
print()
print("## Assets")
print()
print("- `app-release.apk` — signed Solar launcher")
print("- `rom.zip` — Y1 type A firmware (2.0.0+, MT6572)")
print("- `rom_type_b.zip` — Y1 type B firmware (before 2.0.0, MT6572)")
print()
print("## Recent commits")
print()
try:
    log = subprocess.check_output(
        ["git", "log", "-10", "--pretty=format:- %h %s"],
        text=True,
    )
    print(log)
except Exception:
    print(f"- Build {tag}")
PY
