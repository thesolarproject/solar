#!/usr/bin/env bash
# Build Solar context bridge Xposed modules — separate Y1 and Y2 APKs (API 17 / 19).
# Output:
#   solar-rom/vendor/xposed/solar-context-bridge/SolarContextBridgeY1.apk
#   solar-rom/vendor/xposed/solar-context-bridge/SolarContextBridgeY2.apk
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$SCRIPT_DIR/../vendor/xposed/solar-context-bridge"
VENDOR_XPOSED="$SCRIPT_DIR/../vendor/xposed"

die() { echo "build-context-bridge-apk: $*" >&2; exit 1; }

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT=""
for v in 35.0.0 34.0.0 36.0.0 28.0.3; do
    [ -x "$ANDROID_HOME/build-tools/$v/aapt" ] && BT="$ANDROID_HOME/build-tools/$v" && break
done
[ -n "$BT" ] || die "no usable build-tools with aapt under $ANDROID_HOME/build-tools"

PLATFORM=""
for p in android-19 android-17 android-34 android-35; do
    [ -f "$ANDROID_HOME/platforms/$p/android.jar" ] && PLATFORM="$ANDROID_HOME/platforms/$p/android.jar" && break
done
[ -n "$PLATFORM" ] || die "install Android platform 17, 19, 34, or 35 via sdkmanager"

JAVAC=$(command -v javac) || die "missing javac"
D8="$BT/d8"
if [ ! -x "$D8" ] && [ -f "$BT/d8.bat" ]; then
    D8="$BT/d8.bat"
fi
APKSIGNER="$BT/apksigner"
if [ ! -x "$APKSIGNER" ] && [ -f "$BT/apksigner.bat" ]; then
    APKSIGNER="$BT/apksigner.bat"
fi

# Compile stubs — vendored XposedBridge.jar is dex-only; stubs match device API signatures.
gen_stubs() {
    local stubdir="$1"
    mkdir -p "$stubdir/de/robv/android/xposed/callbacks"
    cat > "$stubdir/de/robv/android/xposed/XC_MethodHook.java" <<'EOF'
package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Throwable throwable;
        public void setResult(Object result) {}
        public Object getResult() { return null; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { throwable = t; }
        public boolean hasThrowable() { return throwable != null; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
EOF
    cat > "$stubdir/de/robv/android/xposed/XC_MethodReplacement.java" <<'EOF'
package de.robv.android.xposed;
public abstract class XC_MethodReplacement extends XC_MethodHook {
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
EOF
    cat > "$stubdir/de/robv/android/xposed/XposedBridge.java" <<'EOF'
package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
    public static void log(String text) {}
    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {}
    public static void hookMethod(Member hookMethod, XC_MethodHook callback) {}
}
EOF
    cat > "$stubdir/de/robv/android/xposed/XposedHelpers.java" <<'EOF'
package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader cl) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... args) {}
    public static void findAndHookMethod(String className, ClassLoader cl, String methodName, Object... args) {}
    public static void setAdditionalInstanceField(Object obj, String key, Object value) {}
    public static Object getAdditionalInstanceField(Object obj, String key) { return null; }
}
EOF
    cat > "$stubdir/de/robv/android/xposed/callbacks/XC_LoadPackage.java" <<'EOF'
package de.robv.android.xposed.callbacks;
public abstract class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
    }
}
EOF
    cat > "$stubdir/de/robv/android/xposed/IXposedHookLoadPackage.java" <<'EOF'
package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
EOF
    mkdir -p "$stubdir/out"
    mapfile -t STUB_JAVA < <(find "$stubdir" -name '*.java' ! -path '*/out/*')
    "$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLATFORM" \
        -d "$stubdir/out" \
        "${STUB_JAVA[@]}" \
        || die "Xposed stub javac failed"
    jar cf "$stubdir/stubs.jar" -C "$stubdir/out" .
}

STUB_ROOT="$MOD_DIR/build/xposed-stubs"
gen_stubs "$STUB_ROOT"
XPOSED_CP="$STUB_ROOT/stubs.jar"

build_one() {
    local variant="$1" manifest="$2" xposed_init="$3" out_apk="$4"
    local build="$MOD_DIR/build-$variant"
    rm -rf "$build"
    mkdir -p "$build/classes" "$build/bin" "$build/assets"
    cp "$manifest" "$build/AndroidManifest.xml"
    cp "$xposed_init" "$build/assets/xposed_init"

    echo "==> aapt package ($variant)"
    "$BT/aapt" package -f -M "$build/AndroidManifest.xml" -A "$build/assets" -I "$PLATFORM" -F "$build/resources.ap_"

    echo "==> javac ($variant)"
    mapfile -t JAVA_FILES < <(find "$MOD_DIR/src" "$SCRIPT_DIR/../vendor/global-input-policy" "$SCRIPT_DIR/../vendor/solar-home-policy" -name '*.java')
    "$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLATFORM" \
        -classpath "$XPOSED_CP" \
        -d "$build/classes" "${JAVA_FILES[@]}" 2>"$build/javac.log" \
        || { cat "$build/javac.log"; die "javac failed ($variant)"; }

    jar cf "$build/classes.jar" -C "$build/classes" .
    if [ -x "$D8" ] || [ -f "$D8" ]; then
        "$D8" --min-api 17 --lib "$PLATFORM" --classpath "$XPOSED_CP" \
            --output "$build/bin" "$build/classes.jar" 2>"$build/d8.log" \
            || { cat "$build/d8.log"; die "d8 failed ($variant)"; }
    else
        die "missing d8 in $BT"
    fi
    [ -f "$build/bin/classes.dex" ] || die "classes.dex not produced ($variant)"

    cp "$build/resources.ap_" "$build/unsigned.apk"
    (cd "$build/bin" && "$BT/aapt" add "$build/unsigned.apk" classes.dex >/dev/null)

    KEYSTORE="${SOLAR_PLATFORM_KEYSTORE:-$MOD_DIR/build/debug.keystore}"
    if [ ! -f "$KEYSTORE" ]; then
        keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
    fi
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
        --out "$out_apk" "$build/unsigned.apk" 2>/dev/null \
        || "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
            --out "$out_apk" "$build/unsigned.apk"
    echo "==> $out_apk ($(wc -c < "$out_apk") bytes)"
}

build_one y1 "$MOD_DIR/AndroidManifest.y1.xml" "$MOD_DIR/assets/xposed_init.y1" "$MOD_DIR/SolarContextBridgeY1.apk"
build_one y2 "$MOD_DIR/AndroidManifest.y2.xml" "$MOD_DIR/assets/xposed_init.y2" "$MOD_DIR/SolarContextBridgeY2.apk"

# Legacy alias for scripts still referencing SolarContextBridge.apk (Y2 primary).
cp -f "$MOD_DIR/SolarContextBridgeY2.apk" "$MOD_DIR/SolarContextBridge.apk"
