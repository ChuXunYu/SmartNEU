package android.content;

import java.util.HashMap;
import java.util.Map;

/** 桌面自测用的最小 Context 替身。 */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> map = new HashMap<>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return map.computeIfAbsent(name, k -> new SharedPreferences());
    }
}
