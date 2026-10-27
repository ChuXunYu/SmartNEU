package android.content;

import java.util.HashMap;
import java.util.Map;

public class SharedPreferences {
    public final Map<String, String> data = new HashMap<>();

    public String getString(String k, String def) {
        return data.getOrDefault(k, def);
    }

    public Editor edit() {
        return new Editor(this);
    }

    public static class Editor {
        private final SharedPreferences p;
        private final Map<String, String> buf = new HashMap<>();

        Editor(SharedPreferences p) { this.p = p; }

        public Editor putString(String k, String v) { buf.put(k, v); return this; }

        public void apply() { p.data.putAll(buf); }
    }
}
