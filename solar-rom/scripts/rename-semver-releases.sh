#!/usr/bin/env bash
# One-time: rename v0.x GitHub releases to main-branch timestamp tags (nightly body, no prefix).
# ponytail: pairs each v0.x with the nightly release published nearest the same time.
set -euo pipefail

if [ "${1:-}" != "--apply" ]; then
    echo "Dry run (pass --apply to rename tags and releases):" >&2
    DRY=1
else
    DRY=0
fi

# old_tag:new_tag (strip nightly- prefix from paired nightly release)
PAIRS=(
    "v0.1:20260627-0304"
    "v0.2:20260628-1014"
    "v0.3:20260628-1907"
    "v0.4:20260629-0057"
    "v0.5:20260629-0955"
    "v0.6:20260629-1444"
)

rename_one() {
    local old="$1" new="$2"
    local sha short title
    sha="$(gh api "repos/thesolarproject/solar/releases/tags/${old}" --jq .target_commitish)"
    short="$(printf '%.7s' "$sha")"
    title="Solar ${new} (${short})"
    echo "== ${old} -> ${new} @ ${short} =="
    if [ "$DRY" = 1 ]; then
        return 0
    fi
    git fetch origin "refs/tags/${old}:refs/tags/${old}" 2>/dev/null || true
    git tag -f "$new" "$sha"
    git push origin "refs/tags/${new}"
    gh release edit "$old" --tag "$new" --title "$title" --prerelease=false
    git push origin ":refs/tags/${old}" 2>/dev/null || true
}

for pair in "${PAIRS[@]}"; do
    old="${pair%%:*}"
    new="${pair#*:}"
    rename_one "$old" "$new"
done

if [ "$DRY" = 0 ]; then
    echo "Done. Re-sync OTA catalog: ./scripts/publish-ota-updates.sh sync-from-releases"
fi
