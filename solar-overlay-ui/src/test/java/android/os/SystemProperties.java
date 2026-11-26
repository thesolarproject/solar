package android.os;

import java.util.HashMap;
import java.util.Map;

public class SystemProperties {
    private static final Map<String, String> props = new HashMap<>();

    public static String get(String key, String def) {
        return props.containsKey(key) ? props.get(key) : def;
    }

    public static void set(String key, String val) {
        if (val == null) {
            props.remove(key);
        } else {
            props.put(key, val);
        }
    }

    public static void clear() {
        props.clear();
    }
}
