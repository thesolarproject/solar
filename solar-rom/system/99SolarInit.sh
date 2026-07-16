#!/system/bin/sh
# Solar Y1 boot prep — library folders on SD + TLS sanity + modular APK bootstrap.
# Installed to /system/etc/init.d/ by build-rom.sh and clean_install_system.sh.

SD=/storage/sdcard0
i=0
while [ "$i" -lt 30 ]; do
    [ -d "$SD" ] && break
    sleep 1
    i=$((i + 1))
done

if [ -d "$SD" ]; then
    for d in Music Podcasts Audiobooks Themes; do
        [ -d "$SD/$d" ] || mkdir -p "$SD/$d"
    done
    chmod 755 "$SD/Music" "$SD/Podcasts" "$SD/Audiobooks" "$SD/Themes" 2>/dev/null
fi

if [ ! -f /system/lib/libconscrypt_jni.so ]; then
    log -p w -t SolarInit "missing /system/lib/libconscrypt_jni.so — OkHttp/Reach TLS needs clean_install or Solar ROM"
fi
if [ ! -f /system/etc/security/cacerts/6187b673.0 ]; then
    log -p w -t SolarInit "missing ISRG X1 cacert — podcast MediaPlayer HTTPS needs modern cacerts on /system"
fi

# Modular satellite APKs (keyboard IME + quick-menu overlay)
if [ -f /system/app/com.solar.keyboard.apk ]; then
    pm grant com.solar.keyboard android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
    settings put secure enabled_input_methods com.solar.keyboard/.SolarInputMethodService 2>/dev/null
    settings put secure default_input_method com.solar.keyboard/.SolarInputMethodService 2>/dev/null
fi
if [ -f /system/app/com.solar.quickmenu.apk ]; then
    pm grant com.solar.quickmenu android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null
fi
