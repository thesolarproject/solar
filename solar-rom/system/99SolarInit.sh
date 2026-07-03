#!/system/bin/sh
# Solar Y1 boot prep — library folders on SD + launcher switch scripts + TLS sanity.

SD=/storage/sdcard0
i=0
while [ "$i" -lt 30 ]; do
    [ -d "$SD" ] && break
    sleep 1
    i=$((i + 1))
done

if [ -d "$SD" ]; then
    for d in Music Podcasts Themes JJ_Themes Videos Pictures "FM Recordings" RadioBuffer; do
        [ -d "$SD/$d" ] || mkdir -p "$SD/$d"
    done
    chmod 755 "$SD/Music" "$SD/Podcasts" "$SD/Themes" "$SD/JJ_Themes" \
        "$SD/Videos" "$SD/Pictures" "$SD/FM Recordings" "$SD/RadioBuffer" 2>/dev/null
fi

# Seed launcher switch scripts for unmodified Rockbox-y1 APK (expects /data/data/switch-to-stock.sh).
if [ -f /system/etc/solar/switch-to-stock.sh ]; then
    cp /system/etc/solar/switch-to-stock.sh /data/data/
    chmod 755 /data/data/switch-to-stock.sh
fi
if [ -f /system/etc/solar/switch-to-rockbox.sh ]; then
    cp /system/etc/solar/switch-to-rockbox.sh /data/data/
    chmod 755 /data/data/switch-to-rockbox.sh
fi
if [ -f /system/etc/solar/sync-rockbox-libs.sh ]; then
    sh /system/etc/solar/sync-rockbox-libs.sh
fi
if [ -f /system/etc/solar/sync-y1-keymap.sh ]; then
    sh /system/etc/solar/sync-y1-keymap.sh
fi
# ponytail: side keys live in mtk-kpd — mtk-tpd-kpd must mirror it (not Y1-Rockbox DPAD 21/22).
if [ -f /system/usr/keylayout/mtk-kpd.kl ]; then
    cp /system/usr/keylayout/mtk-kpd.kl /system/usr/keylayout/mtk-tpd-kpd.kl
    chmod 644 /system/usr/keylayout/mtk-tpd-kpd.kl
fi

# First boot after Solar ROM flash: disable Rockbox once (Solar is HOME until user switches).
if [ -f /system/etc/solar/disable-rockbox-for-solar.sh ]; then
    sh /system/etc/solar/disable-rockbox-for-solar.sh
fi

# AVRCP shared rendezvous file — written by Solar, read by Y1Bridge + MtkBt trampoline.
# Path is hardcoded in _trampolines.py (libextavrcp_jni.so binary patch) so it cannot
# move to com.solar.launcher's data dir without re-patching the native binary.
mkdir -p /data/data/com.innioasis.y1/files
chmod 711 /data/data/com.innioasis.y1 /data/data/com.innioasis.y1/files
[ -f /data/data/com.innioasis.y1/files/y1-track-info ] || \
    dd if=/dev/zero of=/data/data/com.innioasis.y1/files/y1-track-info bs=1 count=2213 2>/dev/null || \
    log -p w -t SolarInit "y1-track-info: dd failed (disk full?)"
chmod 666 /data/data/com.innioasis.y1/files/y1-track-info

if [ ! -f /system/lib/libconscrypt_jni.so ]; then
    log -p w -t SolarInit "missing /system/lib/libconscrypt_jni.so — OkHttp/Reach TLS needs clean_install or Solar ROM"
fi
if [ ! -f /system/etc/security/cacerts/6187b673.0 ]; then
    log -p w -t SolarInit "missing ISRG X1 cacert — podcast MediaPlayer HTTPS needs modern cacerts on /system"
fi
