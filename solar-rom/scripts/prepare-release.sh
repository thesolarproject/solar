#!/usr/bin/env bash
# Emit release tag and metadata for CI — version is sequential from latest git release tag.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/solar-repo.sh"
GRADLE="$REPO_ROOT/app/build.gradle"
RESOLVE="$SCRIPT_DIR/resolve-release-version.py"

die() {
    echo "error: $*" >&2
    exit 1
}

apply_gradle_version() {
    local name="$1" code="$2"
    python3 - "$GRADLE" "$name" "$code" <<'PY'
import re, sys
path, name, code = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
text = re.sub(r'versionName\s+"[^"]+"', f'versionName "{name}"', text, count=1)
text = re.sub(r'versionCode\s+\d+', f"versionCode {code}", text, count=1)
open(path, "w", encoding="utf-8").write(text)
PY
}

[ -f "$GRADLE" ] || die "missing $GRADLE"
[ -f "$RESOLVE" ] || die "missing $RESOLVE"

BRANCH="${GITHUB_REF_NAME:-$(git -C "$REPO_ROOT" branch --show-current 2>/dev/null || echo "")}"
SHORT_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

[ "$BRANCH" = "nightly" ] || [ "$BRANCH" = "main" ] || die "releases only from main or nightly (branch: ${BRANCH:-unknown})"

# ponytail: same commit on main/nightly gets identical YYYYMMDD-HHMM (only nightly- prefix differs).
if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
    SOURCE_DATE_EPOCH="$(date -u +%s)"
    export SOURCE_DATE_EPOCH
fi

echo "== Resolve release version from git tags (branch: $BRANCH) =="
eval "$(python3 "$RESOLVE" "$BRANCH" "$GRADLE")"
[ -n "${channel:-}" ] || die "resolve-release-version produced no channel"
[ -n "${version_name:-}" ] || die "resolve-release-version produced no version_name"
[ -n "${version_code:-}" ] || die "resolve-release-version produced no version_code"
[ -n "${tag:-}" ] || die "resolve-release-version produced no tag"

echo "  tag=$tag version_name=$version_name version_code=$version_code"

apply_gradle_version "$version_name" "$version_code"
echo "  patched $GRADLE"

python3 - <<PY
import json
import os

meta = {
    "tag": "${tag}",
    "channel": "${channel}",
    "version_name": "${version_name}",
    "version_code": int("${version_code}"),
    "short_sha": "${SHORT_SHA}",
    "github_repo": "${SOLAR_GITHUB_REPO}",
}
with open("release.json", "w", encoding="utf-8") as handle:
    json.dump(meta, handle, indent=2)
print(json.dumps(meta))

github_output = os.environ.get("GITHUB_OUTPUT")
if github_output:
    with open(github_output, "a", encoding="utf-8") as handle:
        for key in ("tag", "channel", "version_name", "version_code", "short_sha"):
            handle.write(f"{key}={meta[key]}\n")
PY
