#!/system/bin/sh
# 2026-07-20 — On-device recovery: drop outside-Solar context helpers; install Solar only.
# Layman: remove the floating menu apps; keep Solar music app.
# Reversal: re-enable bridge via install-xposed-adb / enable-xposed-module-adb.
set -e

REMOTE=/data/local/tmp/solar-test.apk
PKG=com.solar.launcher

echo "recover: remount"
mount -o remount,rw /system 2>/dev/null || true

echo "recover: remove helper/bridge system apks"
rm -f /system/app/SolarHomeHelper.apk \
  /system/app/SolarGlobalContextModal.apk \
  /system/app/SolarGlobalContext.apk \
  /system/app/SolarContextBridgeY1.apk \
  /system/app/SolarContextBridgeY2.apk \
  /system/app/SolarNotPipeBridge.apk \
  /system/app/io.github.gohoski.notpipe.apk \
  /system/app/PowerMenuTest.apk \
  /system/app/SolarVersions.apk
sync

echo "recover: uninstall helper packages"
for p in \
  com.solar.launcher.homehelper \
  com.solar.launcher.globalcontext \
  com.solar.inputlab \
  com.solar.launcher.xposed.notpipe \
  io.github.gohoski.notpipe \
  com.solar.launcher.xposed.powermenu \
  com.solar.launcher.xposed.bridge.y1 \
  com.solar.launcher.xposed.bridge.y2
do
  pm path "$p" >/dev/null 2>&1 && pm uninstall "$p" || true
done

echo "recover: seed xposed without context bridge"
mkdir -p /data/data/de.robv.android.xposed.installer/conf \
  /data/data/de.robv.android.xposed.installer/shared_prefs

MODEL="$(getprop ro.product.model)"
if [ "$MODEL" = "Y2" ]; then
  cat >/data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="com.solar.launcher.xposed.themefont" value="1" />
    <int name="com.solar.launcher.xposed.rockbox.ime" value="1" />
    <int name="com.solar.launcher.xposed.rockbox.compat" value="1" />
</map>
EOF
  cat >/data/data/de.robv.android.xposed.installer/conf/modules.list <<'EOF'
/system/app/SolarThemeFont.apk
EOF
else
  cat >/data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="com.solar.launcher.xposed.themefont" value="1" />
    <int name="com.solar.launcher.xposed.rockbox.ime" value="1" />
</map>
EOF
  cat >/data/data/de.robv.android.xposed.installer/conf/modules.list <<'EOF'
/system/app/SolarThemeFont.apk
EOF
fi
chmod 664 /data/data/de.robv.android.xposed.installer/shared_prefs/enabled_modules.xml \
  /data/data/de.robv.android.xposed.installer/conf/modules.list 2>/dev/null || true

echo "recover: install Solar"
if [ -f /system/app/com.solar.launcher.apk ] && [ -f "$REMOTE" ]; then
  cp -f "$REMOTE" /system/app/com.solar.launcher.apk
  chmod 644 /system/app/com.solar.launcher.apk
  sync
  echo "recover: system apk overwritten"
fi
if [ -f "$REMOTE" ]; then
  pm install -r -d -t "$REMOTE" || pm install -r -d -t "$REMOTE"
fi

echo "recover: pm clear Solar"
pm clear "$PKG" || true

echo "recover: wipe sibling stems (keep tracks)"
for r in /storage/sdcard0 /storage/sdcard1 /sdcard /storage/emulated/0; do
  [ -d "$r" ] || continue
  find "$r" -type d \( -name .instrumentals -o -name .acapellas \) 2>/dev/null | while read d; do
    rm -rf "$d"
  done
done

echo "RECOVER_OK model=$MODEL"
