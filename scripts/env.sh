# Source before building Solar:  source scripts/env.sh
export JAVA_HOME="${JAVA_HOME:-/home/deck/Documents/Rocksayr/tools/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-/home/deck/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# AOSP test platform keys (device ro.build.tags=test-keys). Override if yours live elsewhere.
export SOLAR_PLATFORM_KEY_PK8="${SOLAR_PLATFORM_KEY_PK8:-/home/deck/Documents/Rocksayr/rockbox/android/platform.pk8}"
export SOLAR_PLATFORM_KEY_PEM="${SOLAR_PLATFORM_KEY_PEM:-/home/deck/Documents/Rocksayr/rockbox/android/platform.x509.pem}"
