#!/usr/bin/env bash
# Publish Solar OTA catalog to thesolarproject/solar-update (GitHub Pages).
#
# Design (2026-07-16):
#   - solar GitHub Releases own the APK binary (app-release.apk per tag).
#   - solar-update only holds updates.xml (+ optional artist-separators.csv).
#   - New releases use apk="https://github.com/.../releases/download/<tag>/app-release.apk"
#     so devices download from the solar release, not a second copy of the APK.
#   - Push uses fetch+rebase retry to survive main/nightly CI races on solar-update.
#
# Usage:
#   publish-ota-updates.sh add --tag TAG --version-name NAME --version-code N [--nightly] [--apk PATH]
#   publish-ota-updates.sh sync-from-releases
#   publish-ota-updates.sh push-catalog | reset
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

PAT="${SOLAR_GITHUB_PAT:-${SOLAR_UPDATES_PAT:-}}"
UPDATE_REPO="${SOLAR_UPDATE_REPO:-thesolarproject/solar-update}"
SOURCE_REPO="${SOLAR_GITHUB_REPO:-thesolarproject/solar}"
PAGES_BASE="${SOLAR_OTA_PAGES_BASE:-https://thesolarproject.github.io/solar-update/}"
# Prefer release assets on solar (no binary in solar-update). Set 0 to copy APK into the catalog repo.
OTA_LINK_RELEASES="${SOLAR_OTA_LINK_RELEASES:-1}"
PUSH_RETRIES="${SOLAR_OTA_PUSH_RETRIES:-8}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

auth_curl() {
  if [[ -n "$PAT" ]]; then
    curl -fsSL -H "Authorization: token $PAT" "$@"
  else
    curl -fsSL "$@"
  fi
}

remote_url() {
  echo "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git"
}

clone_update_repo() {
  local dest="$1"
  if [[ -z "$PAT" ]]; then
    echo "ERROR: set SOLAR_GITHUB_PAT for push to $UPDATE_REPO" >&2
    exit 1
  fi
  # Full history not needed; depth 1 is fine — we rebase onto latest main on conflict.
  if ! git clone --depth 20 "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" "$dest"; then
    echo "::error::SOLAR_GITHUB_PAT cannot clone github.com/${UPDATE_REPO}" >&2
    exit 1
  fi
  git -C "$dest" config user.name "thesolarproject"
  git -C "$dest" config user.email "anonymous@local"
}

# Commit staged files and push main with fetch/rebase retries (main vs nightly race).
push_update_repo() {
  local dir="$1"
  local msg="$2"
  cd "$dir"

  if git diff --staged --quiet 2>/dev/null; then
    # Nothing staged — stage catalog files if dirty in tree
    git add -A updates.xml artist-separators.csv 2>/dev/null || true
  fi
  if git diff --staged --quiet; then
    echo "No OTA catalog changes to push"
    return 0
  fi
  git commit -m "$msg" || true

  local attempt=1
  local url
  url="$(remote_url)"
  while [[ "$attempt" -le "$PUSH_RETRIES" ]]; do
    echo "== Push solar-update attempt ${attempt}/${PUSH_RETRIES} =="
    git fetch "$url" main 2>/dev/null || git fetch origin main 2>/dev/null || true
    # Prefer rebase onto remote main so concurrent OTA publishes serialize.
    if git rev-parse --verify FETCH_HEAD >/dev/null 2>&1; then
      if ! git rebase FETCH_HEAD; then
        echo "rebase conflict — regenerating updates.xml on top of remote" >&2
        git rebase --abort 2>/dev/null || true
        git reset --hard FETCH_HEAD
        # Caller must re-apply change after hard reset: return special code
        return 42
      fi
    fi
    if git push "$url" HEAD:main; then
      echo "Pushed OTA catalog to github.com/${UPDATE_REPO}"
      return 0
    fi
    echo "push rejected (likely concurrent update) — retry in ${attempt}s" >&2
    sleep "$attempt"
    attempt=$((attempt + 1))
  done
  echo "::error::Failed to push OTA catalog to ${UPDATE_REPO} after ${PUSH_RETRIES} attempts" >&2
  return 1
}

copy_artist_separator_catalog() {
  local dest="$1"
  local src="$ROOT/catalog/artist-separators.csv"
  if [[ -f "$src" ]]; then
    cp "$src" "$dest/artist-separators.csv"
    echo "copied artist-separators.csv"
  fi
}

# Upsert one release into updates.xml. apk may be a relative name or absolute https URL.
upsert_release_xml() {
  local dir="$1" tag="$2" version_name="$3" version_code="$4" nightly="$5" apk="$6"
  python3 - "$dir" "$PAGES_BASE" "$tag" "$version_name" "$version_code" "$nightly" "$apk" <<'PY'
import glob, os, re, sys
from datetime import datetime, timezone

out_dir, base, tag, version_name, version_code, nightly_s, apk = sys.argv[1:8]
if not base.endswith("/"):
    base += "/"
nightly = nightly_s.lower() in ("1", "true", "yes")

TS_RE = re.compile(r"^(?:nightly-)?(\d{8}-\d{4})$")
LEGACY_NIGHTLY_RE = re.compile(r"^nightly-(\d+)$")
SEMVER_RE = re.compile(r"^v(\d+\.\d+(?:\.\d+)?)$")

def version_code_for_tag(t: str, fallback: int) -> int:
    m = TS_RE.match(t)
    if m:
        body = m.group(1)
        y, mo, d = int(body[0:4]), int(body[4:6]), int(body[6:8])
        hh, mm = int(body[9:11]), int(body[11:13])
        dt = datetime(y, mo, d, hh, mm, tzinfo=timezone.utc)
        epoch = datetime(2020, 1, 1, tzinfo=timezone.utc)
        return int((dt - epoch).total_seconds() // 60)
    m = LEGACY_NIGHTLY_RE.match(t)
    if m:
        return int(m.group(1))
    return fallback

def parse_existing(path: str):
    entries = []
    if not os.path.isfile(path):
        return entries
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r"<release\s+([^>/]+)/?>", text):
        attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        t = attrs.get("tag") or ""
        a = attrs.get("apk") or ""
        if not t or not a:
            continue
        vn = attrs.get("versionName") or t
        try:
            vc = int(attrs.get("versionCode") or "0")
        except ValueError:
            vc = 0
        n = attrs.get("nightly", "false").lower() == "true" or t.startswith("nightly-")
        entries.append((t, vn, vc, n, a))
    return entries

def from_local_apks(d: str):
    entries = []
    for path in sorted(glob.glob(os.path.join(d, "solar-*.apk"))):
        name = os.path.basename(path)
        m = re.match(r"solar-(.+)\.apk$", name)
        if not m:
            continue
        t = m.group(1)
        if not (TS_RE.match(t) or LEGACY_NIGHTLY_RE.match(t) or SEMVER_RE.match(t)):
            continue
        n = t.startswith("nightly-")
        vn = t[1:] if SEMVER_RE.match(t) else t
        vc = version_code_for_tag(t, 0)
        entries.append((t, vn, vc, n, name))
    return entries

xml_path = os.path.join(out_dir, "updates.xml")
by_tag = {}
for e in from_local_apks(out_dir) + parse_existing(xml_path):
    by_tag[e[0]] = e

# Upsert this release (absolute URL preferred for new entries).
vc = int(version_code) if str(version_code).isdigit() else 0
if vc <= 0:
    vc = version_code_for_tag(tag, 0)
by_tag[tag] = (tag, version_name or tag, vc, nightly, apk)

entries = list(by_tag.values())

def sort_key(item):
    t, vn, vc, n, _a = item
    if n or TS_RE.match(t):
        return (0 if n else 1, vc)
    parts = [int(x) for x in vn.split(".") if x.isdigit()]
    while len(parts) < 3:
        parts.append(0)
    return (2, tuple(parts))

entries.sort(key=sort_key, reverse=True)
max_nightlies = int(os.environ.get("SOLAR_OTA_MAX_NIGHTLIES", "12"))
stable = [e for e in entries if not e[3]]
nightly_list = [e for e in entries if e[3]]
if len(nightly_list) > max_nightlies:
    nightly_list = nightly_list[:max_nightlies]
entries = nightly_list + stable
entries.sort(key=sort_key, reverse=True)

lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    f'<solar-updates base="{base}">',
]
for t, vn, vc, n, a in entries:
    lines.append(
        f'  <release tag="{t}" versionName="{vn}" versionCode="{vc}" '
        f'nightly="{"true" if n else "false"}" apk="{a}"/>'
    )
lines.append("</solar-updates>")
lines.append("")
with open(xml_path, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines))
print(f"wrote updates.xml ({len(entries)} releases) latest={entries[0][0] if entries else '-'}")
PY
}

release_apk_url() {
  local tag="$1"
  echo "https://github.com/${SOURCE_REPO}/releases/download/${tag}/app-release.apk"
}

add_release() {
  local apk="$1" tag="$2" version_name="$3" version_code="$4" nightly="$5"
  local apk_ref attempt

  if [[ "$OTA_LINK_RELEASES" == "1" ]]; then
    apk_ref="$(release_apk_url "$tag")"
    echo "== OTA catalog: link APK to solar release (no binary copy) =="
    echo "   $apk_ref"
  else
    [[ -f "$apk" ]] || { echo "Missing APK: $apk" >&2; exit 1; }
    apk_ref="solar-${tag}.apk"
  fi

  attempt=1
  while [[ "$attempt" -le "$PUSH_RETRIES" ]]; do
    rm -rf "$WORK/repo"
    clone_update_repo "$WORK/repo"
    if [[ "$OTA_LINK_RELEASES" != "1" ]]; then
      [[ -f "$apk" ]] || { echo "Missing APK: $apk" >&2; exit 1; }
      cp "$apk" "$WORK/repo/solar-${tag}.apk"
    fi
    copy_artist_separator_catalog "$WORK/repo"
    upsert_release_xml "$WORK/repo" "$tag" "$version_name" "$version_code" "$nightly" "$apk_ref"
    cd "$WORK/repo"
    git add updates.xml artist-separators.csv
    if [[ "$OTA_LINK_RELEASES" != "1" ]]; then
      git add "solar-${tag}.apk" 2>/dev/null || true
    fi
    if git diff --staged --quiet; then
      echo "No OTA catalog changes (already up to date for ${tag})"
      return 0
    fi
    set +e
    push_update_repo "$WORK/repo" "OTA: ${tag} (${version_name})."
    local rc=$?
    set -e
    if [[ "$rc" -eq 0 ]]; then
      return 0
    fi
    if [[ "$rc" -eq 42 ]]; then
      echo "Re-applying catalog after concurrent update (attempt ${attempt})"
    else
      echo "push failed rc=${rc} (attempt ${attempt})"
    fi
    attempt=$((attempt + 1))
    sleep "$attempt"
  done
  echo "::error::OTA catalog publish failed for tag ${tag}" >&2
  exit 1
}

apk_name_for_tag() {
  echo "solar-${1}.apk"
}

push_catalog() {
  echo "== Push artist-separators.csv to github.com/${UPDATE_REPO} =="
  clone_update_repo "$WORK/repo"
  copy_artist_separator_catalog "$WORK/repo"
  cd "$WORK/repo"
  git add artist-separators.csv
  if git diff --staged --quiet; then
    echo "No catalog changes to push"
    return 0
  fi
  push_update_repo "$WORK/repo" "Update artist separator exceptions catalog."
}

reset_catalog() {
  echo "== Reset OTA catalog in github.com/${UPDATE_REPO} =="
  clone_update_repo "$WORK/repo"
  rm -f "$WORK/repo"/solar-*.apk 2>/dev/null || true
  cat > "$WORK/repo/updates.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<solar-updates base="${PAGES_BASE}">
</solar-updates>
EOF
  cd "$WORK/repo"
  git add updates.xml
  git add -u solar-*.apk 2>/dev/null || true
  push_update_repo "$WORK/repo" "Reset OTA catalog."
}

sync_from_releases() {
  echo "== Sync catalog from github.com/${SOURCE_REPO} releases (URL links, no APK copy) =="
  clone_update_repo "$WORK/repo"
  auth_curl "https://api.github.com/repos/${SOURCE_REPO}/releases?per_page=100" \
    > "$WORK/releases.json"
  python3 - "$WORK/repo" "$WORK/releases.json" "$PAGES_BASE" "$SOURCE_REPO" <<'PY'
import json, os, re, sys
from datetime import datetime, timezone

dest, json_path, base, source_repo = sys.argv[1:5]
if not base.endswith("/"):
    base += "/"
with open(json_path, encoding="utf-8") as handle:
    data = json.load(handle)
if isinstance(data, dict):
    raise SystemExit(data.get("message", "releases API error"))

TS_RE = re.compile(r"^(?:nightly-)?(\d{8}-\d{4})$")
LEGACY_NIGHTLY_RE = re.compile(r"^nightly-(\d+)$")

def version_code_for_tag(t: str) -> int:
    m = TS_RE.match(t)
    if m:
        body = m.group(1)
        y, mo, d = int(body[0:4]), int(body[4:6]), int(body[6:8])
        hh, mm = int(body[9:11]), int(body[11:13])
        dt = datetime(y, mo, d, hh, mm, tzinfo=timezone.utc)
        epoch = datetime(2020, 1, 1, tzinfo=timezone.utc)
        return int((dt - epoch).total_seconds() // 60)
    m = LEGACY_NIGHTLY_RE.match(t)
    if m:
        return int(m.group(1))
    return 0

entries = []
for rel in data:
    tag = (rel.get("tag_name") or "").strip()
    if not tag:
        continue
    has_apk = any(a.get("name") == "app-release.apk" for a in (rel.get("assets") or []))
    if not has_apk:
        continue
    nightly = tag.startswith("nightly-")
    vn = tag
    vc = version_code_for_tag(tag)
    apk = f"https://github.com/{source_repo}/releases/download/{tag}/app-release.apk"
    entries.append((tag, vn, vc, nightly, apk))

def sort_key(item):
    t, vn, vc, n, _a = item
    if n or TS_RE.match(t):
        return (0 if n else 1, vc)
    return (2, vc)

entries.sort(key=sort_key, reverse=True)
max_nightlies = int(os.environ.get("SOLAR_OTA_MAX_NIGHTLIES", "12"))
stable = [e for e in entries if not e[3]]
nightly = [e for e in entries if e[3]][:max_nightlies]
entries = nightly + stable
entries.sort(key=sort_key, reverse=True)

lines = ['<?xml version="1.0" encoding="utf-8"?>', f'<solar-updates base="{base}">']
for t, vn, vc, n, a in entries:
    lines.append(
        f'  <release tag="{t}" versionName="{vn}" versionCode="{vc}" '
        f'nightly="{"true" if n else "false"}" apk="{a}"/>'
    )
lines += ["</solar-updates>", ""]
open(os.path.join(dest, "updates.xml"), "w", encoding="utf-8").write("\n".join(lines))
print(f"synced {len(entries)} release link(s) into updates.xml")
PY
  copy_artist_separator_catalog "$WORK/repo"
  cd "$WORK/repo"
  git add updates.xml artist-separators.csv
  push_update_repo "$WORK/repo" "Sync OTA catalog links from ${SOURCE_REPO} releases."
}

usage() {
  echo "Usage: $0 sync-from-releases" >&2
  echo "       $0 reset" >&2
  echo "       $0 push-catalog" >&2
  echo "       $0 add --tag TAG --version-name NAME --version-code N [--nightly] [--apk PATH]" >&2
  echo "  SOLAR_OTA_LINK_RELEASES=1 (default): catalog points at solar release APKs" >&2
  echo "  SOLAR_OTA_LINK_RELEASES=0: copy APK binary into solar-update (legacy)" >&2
  exit 1
}

case "${1:-}" in
  sync-from-releases)
    sync_from_releases
    ;;
  reset)
    reset_catalog
    ;;
  push-catalog)
    push_catalog
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
    [[ -n "$TAG" ]] || usage
    [[ -n "$VERSION_NAME" ]] || VERSION_NAME="$TAG"
    [[ -n "$VERSION_CODE" ]] || VERSION_CODE="0"
    # APK path optional when linking to releases (CI still may pass it for validation).
    if [[ "$OTA_LINK_RELEASES" != "1" ]]; then
      [[ -n "$APK" && -f "$APK" ]] || usage
    fi
    add_release "${APK:-}" "$TAG" "$VERSION_NAME" "$VERSION_CODE" "$NIGHTLY"
    ;;
  *)
    usage
    ;;
esac
