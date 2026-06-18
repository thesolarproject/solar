#!/usr/bin/env bash
# Emit release tag and metadata for CI from app/build.gradle + git.
# Writes release.json and, when GITHUB_OUTPUT is set: tag, version_name, version_code
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GRADLE="$REPO_ROOT/app/build.gradle"

die() {
    echo "error: $*" >&2
    exit 1
}

[ -f "$GRADLE" ] || die "missing $GRADLE"

VERSION_NAME="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$GRADLE" | head -1)"
SHORT_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

[ -n "$VERSION_NAME" ] || die "could not read versionName"
[ -n "$VERSION_CODE" ] || die "could not read versionCode"

TAG="v${VERSION_NAME}-${VERSION_CODE}+${SHORT_SHA}"

python3 - <<PY
import json
import os

meta = {
    "tag": "${TAG}",
    "version_name": "${VERSION_NAME}",
    "version_code": int("${VERSION_CODE}"),
    "short_sha": "${SHORT_SHA}",
}
with open("release.json", "w", encoding="utf-8") as handle:
    json.dump(meta, handle, indent=2)
print(json.dumps(meta))

github_output = os.environ.get("GITHUB_OUTPUT")
if github_output:
    with open(github_output, "a", encoding="utf-8") as handle:
        handle.write(f"tag={meta['tag']}\n")
        handle.write(f"version_name={meta['version_name']}\n")
        handle.write(f"version_code={meta['version_code']}\n")
        handle.write(f"short_sha={meta['short_sha']}\n")
PY
