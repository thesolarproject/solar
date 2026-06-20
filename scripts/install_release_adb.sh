#!/usr/bin/env bash
# Download a GitHub release from thesolarproject/solar and install on the connected Y1.
# Usage: ./scripts/install_release_adb.sh [tag|stable|nightly|latest]
#   stable   — newest v* release
#   nightly  — newest nightly-* release
#   latest   — first release in GitHub list (default)
#   v0.2 / nightly-42 — explicit tag
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

REPO="${SOLAR_GITHUB_REPO:-thesolarproject/solar}"
SELECT="${1:-latest}"
PAT="${SOLAR_UPDATES_PAT:-}"
SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

api() {
  local url="https://api.github.com/repos/${REPO}/releases?per_page=40"
  local auth=()
  [[ -n "$PAT" ]] && auth=(-H "Authorization: Bearer ${PAT}")
  curl -fsSL "${auth[@]}" -H "Accept: application/vnd.github+json" -H "User-Agent: SolarInstall/1.0" "$url"
}

pick_release() {
  local json tag url
  json="$(api)"
  case "$SELECT" in
    stable)
      tag="$(python3 - <<'PY' "$json"
import json, sys
rels = json.loads(sys.argv[1])
best = None
for r in rels:
    t = r.get("tag_name", "")
    if not t.startswith("v"): continue
    if best is None or t > best: best = t
print(best or "")
PY
)"
      ;;
    nightly)
      tag="$(python3 - <<'PY' "$json"
import json, sys
rels = json.loads(sys.argv[1])
best = None
best_n = -1
for r in rels:
    t = r.get("tag_name", "")
    if not t.startswith("nightly-"): continue
    try: n = int(t.split("-", 1)[1])
    except: n = 0
    if n > best_n: best_n, best = n, t
print(best or "")
PY
)"
      ;;
    latest)
      tag="$(python3 - <<'PY' "$json"
import json, sys
rels = json.loads(sys.argv[1])
for r in rels:
    t = r.get("tag_name", "")
    if t.startswith("v") or t.startswith("nightly-"):
        print(t); break
PY
)"
      ;;
    *)
      tag="$SELECT"
      ;;
  esac
  [[ -n "$tag" ]] || { echo "No matching release for: $SELECT" >&2; exit 1; }
  url="$(python3 - <<'PY' "$json" "$tag"
import json, sys
rels = json.loads(sys.argv[1])
want = sys.argv[2]
for r in rels:
    if r.get("tag_name") != want: continue
    for a in r.get("assets") or []:
        if a.get("name") == "app-release.apk":
            print(a.get("browser_download_url", "")); break
    break
PY
)"
  [[ -n "$url" ]] || { echo "No app-release.apk for tag $tag" >&2; exit 1; }
  echo "$tag|$url"
}

echo "== Waiting for device =="
"${ADB[@]}" wait-for-device

IFS='|' read -r TAG APK_URL <<< "$(pick_release)"
echo "== Release: $TAG =="
TMP="$(mktemp -t solar-release-XXXX.apk)"
trap 'rm -f "$TMP"' EXIT

DL=(curl -fsSL -o "$TMP")
[[ -n "$PAT" ]] && DL+=(-H "Authorization: Bearer ${PAT}")
"${DL[@]}" "$APK_URL"
echo "== Downloaded $(du -h "$TMP" | awk '{print $1}') =="

DEVICE_APK="/data/local/tmp/solar-update.apk"
"${ADB[@]}" push "$TMP" "$DEVICE_APK"

is_system() {
  "${ADB[@]}" shell pm path com.solar.launcher 2>/dev/null | tr -d '\r' | grep -q '^package:/system/'
}

run_su() {
  "${ADB[@]}" shell "su -c '$*'" 2>/dev/null || "${ADB[@]}" shell "$*"
}

if is_system; then
  echo "== System app — fast userdata overlay install (no reboot) =="
  if run_su "pm install -r -d $DEVICE_APK"; then
    "${ADB[@]}" shell am force-stop com.solar.launcher >/dev/null 2>&1 || true
    echo "DONE: installed $TAG as userdata overlay over system app"
  else
    echo "pm install failed for system app overlay" >&2
    exit 1
  fi
else
  echo "== User install — pm install -r =="
  if run_su "pm install -r $DEVICE_APK"; then
    echo "DONE: installed $TAG"
  else
    echo "pm install failed — try: ./scripts/clean_install_system.sh" >&2
    exit 1
  fi
fi
