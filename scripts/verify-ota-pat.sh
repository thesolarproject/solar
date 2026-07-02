#!/usr/bin/env bash
# Fail fast when SOLAR_GITHUB_PAT cannot push to thesolarproject/solar-update.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

PAT="${SOLAR_GITHUB_PAT:-${SOLAR_UPDATES_PAT:-}}"
UPDATE_REPO="${SOLAR_UPDATE_REPO:-thesolarproject/solar-update}"

if [[ -z "$PAT" ]]; then
  echo "::error::SOLAR_GITHUB_PAT secret is missing — add a PAT with Contents read/write on ${UPDATE_REPO}." >&2
  exit 1
fi

# ponytail: ls-remote only proves read — expired or read-only tokens pass then fail after a full build.
HTTP_CODE="$(curl -sS -o /tmp/solar-ota-repo.json -w "%{http_code}" \
  -H "Authorization: token ${PAT}" \
  "https://api.github.com/repos/${UPDATE_REPO}")"
if [[ "$HTTP_CODE" != "200" ]]; then
  echo "::error::SOLAR_GITHUB_PAT cannot access github.com/${UPDATE_REPO} (HTTP ${HTTP_CODE}) — rotate the repo secret." >&2
  exit 128
fi

python3 - /tmp/solar-ota-repo.json <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    repo = json.load(handle)
perms = repo.get("permissions") or {}
if perms.get("push") is False:
    raise SystemExit("SOLAR_GITHUB_PAT has no push permission on solar-update (API permissions.push=false)")
PY

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
if ! git clone --depth 1 "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" "$WORK/repo" >/dev/null 2>&1; then
  echo "::error::SOLAR_GITHUB_PAT cannot clone github.com/${UPDATE_REPO}." >&2
  exit 128
fi

cd "$WORK/repo"
git config user.name "thesolarproject"
git config user.email "anonymous@local"
MARKER=".ci-ota-pat-write-check"
echo "$(date -u +%s)" > "$MARKER"
git add "$MARKER"
git commit -m "CI PAT write preflight [skip ci]" >/dev/null
if ! git push "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" HEAD:main >/dev/null 2>&1; then
  echo "::error::SOLAR_GITHUB_PAT cannot push to github.com/${UPDATE_REPO} — needs Contents write on solar-update." >&2
  exit 128
fi
git rm -f "$MARKER" >/dev/null
git commit -m "CI PAT write preflight cleanup [skip ci]" >/dev/null
git push "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" HEAD:main >/dev/null

echo "solar-update PAT preflight OK (read + push verified)"
