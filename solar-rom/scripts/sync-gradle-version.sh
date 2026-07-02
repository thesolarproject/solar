#!/usr/bin/env bash
# Stamp app/build.gradle versionName + versionCode from git tags before release/ROM builds.
# ponytail: keeps About/OTA version aligned with published tag (nightly-YYYYMMDD-HHMM + matching code).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GRADLE="$REPO_ROOT/app/build.gradle"
RESOLVE="$SCRIPT_DIR/resolve-release-version.py"

[ -f "$GRADLE" ] || { echo "sync-gradle-version: missing $GRADLE" >&2; exit 1; }
[ -f "$RESOLVE" ] || { echo "sync-gradle-version: missing $RESOLVE" >&2; exit 1; }

BRANCH="${SOLAR_RELEASE_CHANNEL:-${GITHUB_REF_NAME:-}}"
if [ -z "$BRANCH" ]; then
    BRANCH="$(git -C "$REPO_ROOT" branch --show-current 2>/dev/null || echo "")"
fi
if [ "$BRANCH" = "nightly" ] || [ "$BRANCH" = "main" ]; then
    :
elif [ -n "${SOLAR_FORCE_STABLE_VERSION:-}" ]; then
    BRANCH="main"
else
    BRANCH="nightly"
fi

if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
    export SOURCE_DATE_EPOCH="$(date -u +%s)"
fi

eval "$(python3 "$RESOLVE" "$BRANCH" "$GRADLE")"
python3 - "$GRADLE" "$version_name" "$version_code" <<'PY'
import re, sys
path, name, code = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
text = re.sub(r'versionName\s+"[^"]+"', f'versionName "{name}"', text, count=1)
text = re.sub(r'versionCode\s+\d+', f"versionCode {code}", text, count=1)
open(path, "w", encoding="utf-8").write(text)
PY

echo "sync-gradle-version: $version_name ($version_code) branch=$BRANCH"
