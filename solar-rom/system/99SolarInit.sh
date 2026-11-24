#!/system/bin/sh
# 2026-07-05 — ROM boot prep: IME defaults, Xposed init, Rockbox switch scripts, lib sync triggers.
# APK/ROM parity: Y1RomPrep mirrors switch/keymap staging for APK-only OTA installs with root.
# When changing: 99XposedInit.sh chain; sync-rockbox-libs.sh; disable-large-font-accessibility.sh; enable-gpu-performance.sh.
# Reversal: revert to stock init.d; Solar loses boot-time platform repair and Rockbox handoff seed.
# Solar Y1 boot prep — library folders on SD + launcher switch scripts + TLS sanity.

# Stuck overlay key gate breaks Rockbox/Solar back+OK (legacy persist + sys props).
setprop persist.solar.overlay.active 0 2>/dev/null
setprop sys.solar.overlay.active 0 2>/dev/null
# 2026-07-08 — Clear shell_visible so stuck BACK heal does not fire after cold boot.
setprop sys.solar.overlay.shell_visible 0 2>/dev/null
setprop sys.solar.ime.active 0 2>/dev/null
setprop sys.solar.ime.ui 0 2>/dev/null

# 2026-07-06 — Y1/Y2 fixed landscape 480×360; block apps from leaving portrait/rotated state.
settings put system accelerometer_rotation 0 2>/dev/null
settings put system user_rotation 0 2>/dev/null

# 2026-07-05 — Default Solar wheel IME + tier-3 accessibility fallback (ROM bake).
settings put secure enabled_input_methods com.solar.launcher/.SolarInputMethodService 2>/dev/null
settings put secure default_input_method com.solar.launcher/.SolarInputMethodService 2>/dev/null
settings put secure enabled_accessibility_services com.solar.launcher/.SolarImeAccessibilityService 2>/dev/null

# Accessibility “Large fonts” breaks 480×360 Solar/Rockbox layouts — reset every boot.
if [ -f /system/etc/solar/disable-large-font-accessibility.sh ]; then
    sh /system/etc/solar/disable-large-font-accessibility.sh
fi

# Developer-options graphics: force GPU + disable HW overlays for smoother Solar overlays on MTK.
if [ -f /system/etc/solar/enable-gpu-performance.sh ]; then
    sh /system/etc/solar/enable-gpu-performance.sh
fi

# SuperSU daemonsu — untrusted_app su -c needs the daemon when SELinux is enforcing (Y2).
if [ -x /system/xbin/daemonsu ]; then
    /system/xbin/daemonsu --auto-daemon &
fi

# Xposed runtime seed — some MTK Y1 boots run init.d late; run explicitly so zygote finds the jar.
if [ -x /system/etc/init.d/99XposedInit.sh ]; then
    sh /system/etc/init.d/99XposedInit.sh
fi

SD=/storage/sdcard0
# Y2 primary user SD is sdcard1; internal is sdcard0 — seed folders on the right volume (2026-07-06).
case "$(getprop ro.product.model 2>/dev/null)" in
    *[Yy]2*) SD=/storage/sdcard1 ;;
esac
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

# 2026-07-10 — Solar-only: launcher switch scripts are not seeded on boot.
setprop persist.solar.home.target solar
# 2026-07-15 — A5 ROM bakes A5-mtk.kl; pin family before keymap sync so Y1 wheel maps never win.
# 2026-07-16 — Pin family for Reach usernames / UI: A5 QVGA unique; 360p disambiguated by
#   SDK (4.4.x=y2, 4.2.x=y1) and SoC (mt6582/mt6572). Align with DeviceFeatures.detectFamily.
# Was: only A5 pinned; Y1/Y2 relied on runtime SDK alone; 360p short-circuit mislabeled Y2.
if [ -f /system/etc/solar/A5-mtk.kl ] && [ -f /system/etc/solar/A5.kl ]; then
    setprop persist.solar.device_family a5 2>/dev/null
fi
_CPU="$(cat /proc/cpuinfo 2>/dev/null | grep -i '^Hardware' | head -1 | sed 's/.*:[[:space:]]*//' | tr 'A-Z' 'a-z')"
_BOARD="$(getprop ro.product.board 2>/dev/null | tr 'A-Z' 'a-z')"
_SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
case "$_SDK" in ''|*[!0-9]*) _SDK=0 ;; esac
if [ -r /sys/class/graphics/fb0/virtual_size ]; then
    VS="$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null | tr ',' ' ')"
    set -- $VS
    _W="${1:-0}"; _H="${2:-0}"
    case "$_W" in ''|*[!0-9]*) _W=0 ;; esac
    case "$_H" in ''|*[!0-9]*) _H=0 ;; esac
    if [ "$_W" -gt 0 ] && [ "$_H" -gt 0 ]; then
        if [ "$_W" -le "$_H" ]; then _A=$_W; _B=$_H; else _A=$_H; _B=$_W; fi
        # 240×320 ±20 → A5 (beats stale y1 pin / model=Y1)
        if [ "$_A" -ge 220 ] && [ "$_A" -le 260 ] && [ "$_B" -ge 300 ] && [ "$_B" -le 340 ]; then
            setprop persist.solar.device_family a5 2>/dev/null
        fi
    fi
fi
# SoC pins when family still unset (or reinforce after A5-only paths).
case "$(getprop persist.solar.device_family 2>/dev/null)" in
    a5) ;;
    *)
        case "$_CPU$_BOARD" in
            *mt6582*) setprop persist.solar.device_family y2 2>/dev/null ;;
            *mt6572*) setprop persist.solar.device_family y1 2>/dev/null ;;
            *)
                # Shared 360p without SoC string: SDK 19+ → y2, SDK ≤17 → y1.
                if [ -r /sys/class/graphics/fb0/virtual_size ]; then
                    VS="$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null | tr ',' ' ')"
                    set -- $VS
                    _W="${1:-0}"; _H="${2:-0}"
                    case "$_W" in ''|*[!0-9]*) _W=0 ;; esac
                    case "$_H" in ''|*[!0-9]*) _H=0 ;; esac
                    if [ "$_W" -gt 0 ] && [ "$_H" -gt 0 ]; then
                        if [ "$_W" -le "$_H" ]; then _A=$_W; _B=$_H; else _A=$_H; _B=$_W; fi
                        if [ "$_A" -ge 340 ] && [ "$_A" -le 380 ] && [ "$_B" -ge 460 ] && [ "$_B" -le 500 ]; then
                            if [ "$_SDK" -ge 19 ]; then
                                setprop persist.solar.device_family y2 2>/dev/null
                            elif [ "$_SDK" -le 17 ]; then
                                setprop persist.solar.device_family y1 2>/dev/null
                            fi
                        fi
                    fi
                fi
                # Last resort: kitkat-class images without panel sysfs.
                case "$(getprop persist.solar.device_family 2>/dev/null)" in
                    y1|y2|a5) ;;
                    *)
                        if [ "$_SDK" -ge 19 ]; then
                            setprop persist.solar.device_family y2 2>/dev/null
                        elif [ "$_SDK" -le 17 ] && [ "$_SDK" -gt 0 ]; then
                            setprop persist.solar.device_family y1 2>/dev/null
                        fi
                        ;;
                esac
                ;;
        esac
        ;;
esac
if [ -f /system/etc/solar/sync-y1-keymap.sh ]; then
    sh /system/etc/solar/sync-y1-keymap.sh
fi

# First boot after Solar ROM flash: apply saved HOME (default Solar) without Android picker dialogs.
if [ -f /system/etc/solar/apply-preferred-home-boot.sh ]; then
    sh /system/etc/solar/apply-preferred-home-boot.sh
fi

# 2026-07-06 — Platform supervisor + rescue evdev tier (hold timing in GlobalInputPolicy + Xposed).
if [ -f /system/etc/solar/solar-platform-daemon.sh ]; then
    sh /system/etc/solar/solar-platform-daemon.sh &
elif [ -f /system/etc/solar/solar-rescue-daemon.sh ]; then
    sh /system/etc/solar/solar-rescue-daemon.sh &
fi

# AVRCP shared rendezvous file — written by Solar, read by Y1Bridge + MtkBt trampoline.
# Path is hardcoded in libextavrcp_jni.so binary patch; cannot move to com.solar.launcher.
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
