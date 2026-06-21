#!/usr/bin/env bash
# Publish Solar APKs + updates.xml to thesolarproject/solar-update (GitHub Pages OTA).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

PAT="${SOLAR_GITHUB_PAT:-${SOLAR_UPDATES_PAT:-}}"
UPDATE_REPO="${SOLAR_UPDATE_REPO:-thesolarproject/solar-update}"
SOURCE_REPO="${SOLAR_GITHUB_REPO:-thesolarproject/solar}"
PAGES_BASE="${SOLAR_OTA_PAGES_BASE:-https://thesolarproject.github.io/solar-update/}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

auth_curl() {
  if [[ -n "$PAT" ]]; then
    curl -fsSL -H "Authorization: token $PAT" "$@"
  else
    curl -fsSL "$@"
  fi
}

apk_name_for_tag() {
  local tag="$1"
  echo "solar-${tag}.apk"
}

write_updates_xml() {
  local dir="$1"
  python3 - "$dir" "$PAGES_BASE" <<'PY'
import glob, os, re, sys

out_dir, base = sys.argv[1], sys.argv[2]
if not base.endswith("/"):
    base += "/"

entries = []
for path in sorted(glob.glob(os.path.join(out_dir, "solar-*.apk"))):
    name = os.path.basename(path)
    m = re.match(r"solar-(v[\d.]+|nightly-\d+)\.apk$", name)
    if not m:
        continue
    tag = m.group(1)
    nightly = tag.startswith("nightly-")
    if nightly:
        version_name = tag
        version_code = int(tag.split("-", 1)[1])
    else:
        version_name = tag[1:]
        version_code = 0
    entries.append((tag, version_name, version_code, nightly, name))

def sort_key(item):
    tag, version_name, version_code, nightly, _ = item
    if nightly:
        return (0, version_code)
    parts = [int(x) for x in version_name.split(".") if x.isdigit()]
    while len(parts) < 3:
        parts.append(0)
    return (1, tuple(parts))

entries.sort(key=sort_key, reverse=True)

max_nightlies = int(os.environ.get("SOLAR_OTA_MAX_NIGHTLIES", "12"))
stable = [e for e in entries if not e[3]]
nightly = [e for e in entries if e[3]]
if len(nightly) > max_nightlies:
    nightly = nightly[:max_nightlies]
entries = nightly + stable
entries.sort(key=sort_key, reverse=True)

lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    f'<solar-updates base="{base}">',
]
for tag, version_name, version_code, nightly, apk in entries:
    lines.append(
        f'  <release tag="{tag}" versionName="{version_name}" versionCode="{version_code}" '
        f'nightly="{"true" if nightly else "false"}" apk="{apk}"/>'
    )
lines.append("</solar-updates>")
lines.append("")
with open(os.path.join(out_dir, "updates.xml"), "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines))
print(f"wrote updates.xml ({len(entries)} releases)")
PY
}

clone_update_repo() {
  local dest="$1"
  if [[ -z "$PAT" ]]; then
    echo "ERROR: set SOLAR_GITHUB_PAT for push to $UPDATE_REPO" >&2
    exit 1
  fi
  git clone --depth 1 "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" "$dest"
}

push_update_repo() {
  local dir="$1"
  local msg="$2"
  cd "$dir"
  git config user.name "thesolarproject"
  git config user.email "anonymous@local"
  git add -A updates.xml solar-*.apk 2>/dev/null || git add updates.xml
  if git diff --staged --quiet; then
    echo "No OTA changes to push"
    return 0
  fi
  git commit -m "$msg"
  git push "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" HEAD:main
}

reset_catalog() {
  echo "== Reset OTA catalog in github.com/${UPDATE_REPO} =="
  clone_update_repo "$WORK/repo"
  rm -f "$WORK/repo"/solar-*.apk "$WORK/repo"/updates.xml 2>/dev/null || true
  cat > "$WORK/repo/updates.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<solar-updates base="${PAGES_BASE}">
</solar-updates>
EOF
  push_update_repo "$WORK/repo" "Reset OTA catalog."
}

sync_from_releases() {
  echo "== Sync APKs from github.com/${SOURCE_REPO} releases =="
  clone_update_repo "$WORK/repo"
  rm -f "$WORK/repo"/solar-*.apk "$WORK/repo/updates.xml" 2>/dev/null || true
  auth_curl "https://api.github.com/repos/${SOURCE_REPO}/releases?per_page=100" \
    > "$WORK/releases.json"
  python3 - "$WORK/repo" "$WORK/releases.json" <<'PY'
import json, os, subprocess, sys

dest, json_path = sys.argv[1], sys.argv[2]
with open(json_path, encoding="utf-8") as handle:
    data = json.load(handle)
if isinstance(data, dict):
    raise SystemExit(data.get("message", "releases API error"))

seen = set()
for rel in data:
    tag = rel.get("tag_name", "").strip()
    if not tag or tag in seen:
        continue
    apk_asset = None
    for asset in rel.get("assets", []):
        if asset.get("name") == "app-release.apk":
            apk_asset = asset
            break
    if not apk_asset:
        continue
    seen.add(tag)
    out = os.path.join(dest, f"solar-{tag}.apk")
    url = apk_asset["browser_download_url"]
    print(f"download {tag} -> {os.path.basename(out)}")
    subprocess.check_call(["curl", "-fsSL", "-L", "-o", out, url])

print(f"synced {len(seen)} apk(s)")
PY
  write_updates_xml "$WORK/repo"
  push_update_repo "$WORK/repo" "Sync OTA catalog from ${SOURCE_REPO} releases."
}

add_release() {
  local apk="$1" tag="$2" version_name="$3" version_code="$4" nightly="$5"
  [[ -f "$apk" ]] || { echo "Missing APK: $apk" >&2; exit 1; }
  clone_update_repo "$WORK/repo"
  cp "$apk" "$WORK/repo/$(apk_name_for_tag "$tag")"
  write_updates_xml "$WORK/repo"
  push_update_repo "$WORK/repo" "OTA: ${tag} (${version_name})."
}

usage() {
  echo "Usage: $0 sync-from-releases" >&2
  echo "       $0 reset" >&2
  echo "       $0 add --apk PATH --tag TAG --version-name NAME --version-code N [--nightly]" >&2
  exit 1
}

case "${1:-}" in
  sync-from-releases)
    sync_from_releases
    ;;
  reset)
    reset_catalog
    ;;
  add)
    shift
    APK="" TAG="" VERSION_NAME="" VERSION_CODE="" NIGHTLY=false
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        --version-name) VERSION_NAME="$2"; shift 2 ;;
        --version-code) VERSION_CODE="$2"; shift 2 ;;
        --nightly) NIGHTLY=true; shift ;;
        *) usage ;;
      esac
    done
    [[ -n "$APK" && -n "$TAG" ]] || usage
    [[ -n "$VERSION_NAME" ]] || VERSION_NAME="$TAG"
    [[ -n "$VERSION_CODE" ]] || VERSION_CODE="0"
    add_release "$APK" "$TAG" "$VERSION_NAME" "$VERSION_CODE" "$NIGHTLY"
    ;;
  *)
    usage
    ;;
esac
