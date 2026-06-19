#!/system/bin/sh
# Solar Y1 boot prep — library folders on SD + TLS sanity (see scripts/stage-y1-system-prep.sh).
# Installed to /system/etc/init.d/ by build-rom.sh and clean_install_system.sh.

SD=/storage/sdcard0
i=0
while [ "$i" -lt 30 ]; do
    [ -d "$SD" ] && break
    sleep 1
    i=$((i + 1))
done

if [ -d "$SD" ]; then
    for d in Music Podcasts Themes; do
        [ -d "$SD/$d" ] || mkdir -p "$SD/$d"
    done
    chmod 755 "$SD/Music" "$SD/Podcasts" "$SD/Themes" 2>/dev/null
fi

# AVRCP shared state (Koensayr legacy path; BT stack mmaps as different uid)
mkdir -p /data/data/com.innioasis.y1/files 2>/dev/null
chmod 771 /data/data/com.innioasis.y1 /data/data/com.innioasis.y1/files 2>/dev/null

if [ -f /data/local/tmp/solar-ota-pending-reboot ]; then
    log -p w -t SolarInit "OTA reboot pending — finish update with a reboot"
fi

if [ ! -f /system/lib/libconscrypt_jni.so ]; then
    log -p w -t SolarInit "missing /system/lib/libconscrypt_jni.so — OkHttp/Reach TLS needs clean_install or Solar ROM"
fi
if [ ! -f /system/etc/security/cacerts/6187b673.0 ]; then
    log -p w -t SolarInit "missing ISRG X1 cacert — podcast MediaPlayer HTTPS needs modern cacerts on /system"
fi
