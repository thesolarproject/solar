# Solar build — armeabi-v7a only (Innioasis Y1/Y2 MTK SoCs are ARMv7).
# android-17 target keeps bionic linkage compatible with both API 17 (Y1) and API 19 (Y2).
APP_ABI := armeabi-v7a
APP_PLATFORM := android-17
APP_BUILD_SCRIPT := Android-solar.mk
APP_OPTIM := release
