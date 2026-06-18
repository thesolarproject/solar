#!/usr/bin/env bash
# Report likely hardcoded user-visible strings (not yet in strings.xml).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "== Hardcoded android:text in layouts (excluding @string) =="
rg 'android:text="[^@]' app/src/main/res/layout --glob '*.xml' || true

echo ""
echo "== createListButton / createSettingsRow with string literals in MainActivity =="
rg 'createListButton\("|createSettingsRow\("[^R]' app/src/main/java/com/solar/launcher/MainActivity.java || true

echo ""
echo "== Toast.makeText with string literals =="
rg 'Toast\.makeText\([^,]+,\s+"' app/src/main/java || true

echo ""
echo "== AlertDialog setTitle/setMessage with literals =="
rg 'setTitle\("|setMessage\("' app/src/main/java/com/solar/launcher/MainActivity.java || true

echo ""
echo "== values-ko entries still identical to English (likely untranslated) =="
python3 << 'PY'
import re
from pathlib import Path

EXEMPT = {
    "app_name", "language_english", "home_menu_soulseek", "settings_soulseek",
    "home_screen_move_grip", "common_position_format", "common_path_music",
    "webserver_ip_placeholder", "webserver_ip_format", "dialog_radius_dp",
    "soulseek_download_detail", "browser_up", "browser_install_apk",
    "datetime_apply", "datetime_cancel", "browser_folders", "browser_artists",
    "browser_albums", "browser_all_songs", "soulseek_play", "soulseek_cancel_download",
}

def parse(path):
    text = Path(path).read_text(encoding="utf-8")
    d = {}
    for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', text, re.DOTALL):
        d[m.group(1)] = m.group(2).replace("\n", "\\n")
    return d

en = parse("app/src/main/res/values/strings.xml")
ko = parse("app/src/main/res/values-ko/strings.xml")
same = [k for k in en if k in ko and en[k] == ko[k] and k not in EXEMPT]
if not same:
    print("(none)")
else:
    for k in sorted(same):
        print(f"  {k}")
PY

echo ""
echo "Done. Fix findings by moving text to values/strings.xml and using getString(R.string.*)."
