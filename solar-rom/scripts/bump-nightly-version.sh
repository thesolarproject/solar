#!/usr/bin/env bash
# After a nightly release: bump app/build.gradle to the next nightly timestamp for local dev.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GRADLE="$REPO_ROOT/app/build.gradle"

[ -f "$GRADLE" ] || { echo "missing $GRADLE" >&2; exit 1; }

python3 - "$GRADLE" <<'PY'
import re
import sys
from datetime import datetime, timezone

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
name_m = re.search(r'versionName\s+"([^"]+)"', text)
code_m = re.search(r'versionCode\s+(\d+)', text)
if not name_m or not code_m:
    raise SystemExit("could not read versionName/versionCode")

name = name_m.group(1)
code = int(code_m.group(1))
if name.startswith("nightly-"):
    dt = datetime.now(timezone.utc)
    next_name = dt.strftime("nightly-%Y%m%d-%H%M")
    epoch = datetime(2020, 1, 1, tzinfo=timezone.utc)
    next_code = max(code, int((dt - epoch).total_seconds() // 60))
else:
    next_name = name
    next_code = code + 1

text = re.sub(r'versionName\s+"[^"]+"', f'versionName "{next_name}"', text, count=1)
text = re.sub(r'versionCode\s+\d+', f"versionCode {next_code}", text, count=1)
open(path, "w", encoding="utf-8").write(text)
print(f"bumped nightly version -> {next_name} ({next_code})")
PY
