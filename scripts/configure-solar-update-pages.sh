#!/usr/bin/env bash
# solar-update OTA hosts APKs on main — Pages must deploy from branch, not Actions deploy-pages.
# ponytail: workflow-based Pages stuck in deployment_queued forever while git push already updated main.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

PAT="${SOLAR_GITHUB_PAT:-${SOLAR_UPDATES_PAT:-}}"
UPDATE_REPO="${SOLAR_UPDATE_REPO:-thesolarproject/solar-update}"

if [[ -z "$PAT" ]]; then
  echo "::error::SOLAR_GITHUB_PAT required to configure ${UPDATE_REPO} Pages." >&2
  exit 1
fi

echo "== Configure github.com/${UPDATE_REPO} Pages: legacy deploy from main =="
HTTP_CODE="$(curl -sS -o /tmp/solar-pages.json -w "%{http_code}" \
  -X PUT \
  -H "Authorization: token ${PAT}" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${UPDATE_REPO}/pages" \
  -d '{"build_type":"legacy","source":{"branch":"main","path":"/"}}')"
if [[ "$HTTP_CODE" != "200" && "$HTTP_CODE" != "201" ]]; then
  echo "::error::Pages API returned HTTP ${HTTP_CODE} for ${UPDATE_REPO} — PAT needs admin:repo_hook or Pages admin on solar-update." >&2
  cat /tmp/solar-pages.json >&2 || true
  exit 1
fi
python3 - /tmp/solar-pages.json <<'PY'
import json, sys
pages = json.load(open(sys.argv[1], encoding="utf-8"))
build = pages.get("build_type", "")
branch = (pages.get("source") or {}).get("branch", "")
print(f"  Pages build_type={build} branch={branch} url={pages.get('html_url', '')}")
if build != "legacy" or branch != "main":
    raise SystemExit(f"expected legacy/main Pages, got {build}/{branch}")
PY

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" "$WORK/repo"
cd "$WORK/repo"
git config user.name "thesolarproject"
git config user.email "anonymous@local"

REMOVED=0
if [[ -d .github/workflows ]]; then
  for wf in .github/workflows/*.yml .github/workflows/*.yaml; do
    [[ -f "$wf" ]] || continue
    if grep -qE 'deploy-pages|upload-pages-artifact|github-pages' "$wf" 2>/dev/null; then
      echo "  removing workflow $(basename "$wf") (conflicts with branch Pages)"
      git rm -f "$wf"
      REMOVED=1
    fi
  done
fi

if [[ "$REMOVED" -eq 1 ]]; then
  git commit -m "Remove Actions Pages deploy — OTA serves from main branch root."
  git push "https://x-access-token:${PAT}@github.com/${UPDATE_REPO}.git" HEAD:main
  echo "  removed deploy-pages workflow(s)"
else
  echo "  no deploy-pages workflow present"
fi

echo "solar-update Pages configured (legacy main root)"
