package org.tvbox.panadonikproxy;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable, idempotent first-validation state. Contains counters only, never proxy addresses. */
final class ProxyState {
    private static final String FILE = "panadonik_proxy_state_v1";
    private final SharedPreferences prefs;
    ProxyState(Context context) { prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }
    boolean enabled() { return prefs.getBoolean("enabled", true); }
    boolean rotate() { return prefs.getBoolean("rotate", true); }
    void setEnabled(boolean value) { prefs.edit().putBoolean("enabled", value).apply(); }
    void setRotate(boolean value) { prefs.edit().putBoolean("rotate", value).apply(); }
    boolean initialComplete() { return prefs.getBoolean("initial_complete", false); }
    void begin(int sources, int found) {
        prefs.edit().putBoolean("initial_running", true).putBoolean("initial_complete", false)
            .putInt("sources", sources).putInt("found", found).putInt("checked", 0)
            .putInt("accepted", 0).putInt("rejected", 0).putLong("started_at", System.currentTimeMillis()).apply();
    }
    void progress(int checked, int accepted) {
        prefs.edit().putInt("checked", checked).putInt("accepted", accepted)
            .putInt("rejected", Math.max(0, checked - accepted)).apply();
    }
    void complete(int checked, int accepted) {
        prefs.edit().putBoolean("initial_running", false).putBoolean("initial_complete", true)
            .putInt("checked", checked).putInt("accepted", accepted)
            .putInt("rejected", Math.max(0, checked - accepted))
            .putLong("validated_at", System.currentTimeMillis()).apply();
    }
    String summary() {
        String phase = initialComplete() ? "первичная проверка завершена" : "первичная проверка выполняется";
        return phase + "; источников " + prefs.getInt("sources", 0) + ", найдено " + prefs.getInt("found", 0) +
            ", проверено " + prefs.getInt("checked", 0) + ", оставлено " + prefs.getInt("accepted", 0) +
            ", отброшено " + prefs.getInt("rejected", 0);
    }
}