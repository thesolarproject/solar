#!/system/bin/sh
# ponytail: keep Generic.kl / Stock.kl / Rockbox.kl identical — wheel 126/127 for Solar + Rockbox.
SRC=/system/etc/solar/Y1-Rockbox.kl
[ -f "$SRC" ] || exit 0
mount -o remount,rw /system 2>/dev/null || true
for f in Generic.kl Stock.kl Rockbox.kl Y1-Rockbox.kl; do
    cp "$SRC" "/system/usr/keylayout/$f"
    chmod 644 "/system/usr/keylayout/$f"
done
