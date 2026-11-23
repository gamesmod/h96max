package org.tvbox.bootlog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final String DIR = "/sdcard/tvbox/";
    static final String[] BENIGN = {
        "clk_hevc_cabac", "hevc cabac", "shared_video_hevc_cabac", "probe sub driver failed",
        "VNDK APEX", "Can't open /vendor/apex", "Can't open /system_ext/apex", "Can't open /product/apex",
        "rollback-indicator", "setxattr failed", "/metadata/gsi", "/metadata/apex", "missing rockchip,grf",
        "failed to get vop", "rknandbase", "tx_delay", "rx_delay", "clk_mac_", "rga:", "rga2",
        "FrameworkListener", "Read-only property was already set", "up_eth", "rk_store_keybox", "displayd",
        "bplus_helper", "Control message: Could not find", "iep:", "Failed to get clk 'ref'",
        "Couldn't update frequency", "power_model", "Failed to migrate sessions", "dhd_conf_set_chiprev", "[dhd]", "[dhd-wlan0]", "dhd_open", "Dongle Host Driver",
        "SchedTune", "hclk_vio", "rk pwm remotectl", "wificountrycode", "audit", "selinux",
        "hdmicec", "hwcomposer", "HWComposer", "MaliEGL", "UserRestrictionsUtils", "FrameEvents",
        "TaskPersister", "DisplayDeviceConfig", "SensorManager", "ScreenService", "BpTransactionCompleted",
        "LEGACY_SCAN", "dhd_conf_set_country", "Country code", "WLC_E_LINK", "wl_iw_event", "wl_ext_iapsta_event", "wl_notify_connect_status", "wl_cfg80211_disconnect", "init.usb.configfs.rc", "sys.usb.controller"
    };

    static final String[] CRITICAL = {
        "FATAL EXCEPTION", "ANR in ", "has died", "Fatal signal", "CANNOT LINK", "crashed", "AndroidRuntime",
        "Process crashed", "died; restarting", "tombstone"
    };

    static final String[][] STAGES = {
        {"x:kernel", "🐧 Ядро Linux"},
        {"p:ro.boottime.init", "⚙️ init: разделы, SELinux"},
        {"p:ro.boottime.vold", "💾 Хранилище (vold)"},
        {"p:ro.boottime.surfaceflinger", "🖼  Графика (SurfaceFlinger)"},
        {"p:ro.boottime.zygote", "☕ Zygote: среда Java"},
        {"e:boot_progress_preload_end", "📚 Классы предзагружены"},
        {"e:boot_progress_system_run", "🧠 Системный сервер"},
        {"e:boot_progress_pms_ready", "📦 Приложения проверены"},
        {"e:boot_progress_ams_ready", "🚀 Службы готовы"},
        {"e:boot_progress_enable_screen", "🖥  Включение экрана"},
        {"x:wifi", "📶 Wi‑Fi подключён"},
        {"x:done", "✅ Загрузка завершена"},
    };

    static boolean benign(String s) {
        for (String b : BENIGN) if (s.contains(b)) return true;
        return s.trim().isEmpty();
    }

    static int level(String line, boolean logcat) {
        if (logcat) {
            if (line.contains(" F ")) return 2;
            for (String c : CRITICAL) if (line.contains(c)) return 3;
            return line.contains(" E ") ? 5 : 6;
        }
        if (line.length() > 2 && line.charAt(0) == '<' && line.charAt(2) == '>') return line.charAt(1) - '0';
        return 6;
    }

    static List<String> read(String path) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        } catch (Exception e) {
            out.add("<3>не удалось прочитать " + path + ": " + e.getMessage());
        }
        return out;
    }

    static String prop(String k) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, k);
        } catch (Throwable t) { return ""; }
    }

    SpannableStringBuilder sb = new SpannableStringBuilder();

    void add(String text, int color, boolean bold) {
        int s = sb.length();
        sb.append(text).append('\n');
        sb.setSpan(new ForegroundColorSpan(color), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    int colorFor(int lvl) {
        return lvl <= 3 ? 0xFFFF6B6B : lvl == 4 ? 0xFFFFD54F : 0xFFCFD8DC;
    }

    void section(String title, List<String> lines, boolean logcat) {
        int errors = 0, first = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (level(l, logcat) <= 3 && !benign(l)) { errors++; if (first < 0) first = i; }
        }
        add("━━ " + title + " ━━", 0xFF4FC3F7, true);
        if (logcat) {
            int notice = 0;
            for (String l : lines) if (level(l, true) == 5) notice++;
            if (notice > 0) add("ℹ️ прочих записей уровня E: " + notice + " (вендорские службы и отсутствующие датчики, на работу не влияют)", 0xFF9AA0A6, false);
        }
        if (errors == 0) {
            add("✅ ошибок нет (вендорский шум отфильтрован)", 0xFF81C784, true);
        } else {
            add("❌ ошибок: " + errors + ". Первая — с 10 строками до неё:", 0xFFFF6B6B, true);
            for (int i = Math.max(0, first - 10); i <= first; i++) {
                String l = lines.get(i);
                add((i == first ? "▶ " : "  ") + l, i == first ? 0xFFFF5252 : colorFor(level(l, logcat)), i == first);
            }
            add("", Color.WHITE, false);
            add("Все ошибки:", 0xFFFF6B6B, true);
            int shown = 0;
            for (String l : lines) if (level(l, logcat) <= 3 && !benign(l) && shown++ < 150) add("• " + l, 0xFFFF8A80, false);
            if (shown > 150) add("… и ещё " + (shown - 150), 0xFFFF8A80, false);
        }
        add("", Color.WHITE, false);
    }

    String memorySummary() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = 0, free = 0, avail = 0;
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) total = Long.parseLong(l.replaceAll("[^0-9]", ""));
                else if (l.startsWith("MemFree:")) free = Long.parseLong(l.replaceAll("[^0-9]", ""));
                else if (l.startsWith("MemAvailable:")) avail = Long.parseLong(l.replaceAll("[^0-9]", ""));
            }
            long used = total - (avail > 0 ? avail : free);
            return String.format("ОЗУ: %d / %d МБ занято", used / 1024, total / 1024);
        } catch (Exception e) { return "ОЗУ: нет данных"; }
    }

    String temperature() {
        String[] paths = {"/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp"};
        for (String p : paths) {
            try (BufferedReader r = new BufferedReader(new FileReader(p))) {
                String v = r.readLine();
                if (v == null) continue;
                long t = Long.parseLong(v.trim());
                if (t > 1000) t /= 1000;
                return String.format("Температура: %d,0 °C", t);
            } catch (Exception e) { }
        }
        return "Температура: нет данных";
    }

    String buildDevicePanelText() {
        StringBuilder p = new StringBuilder();
        String rel = prop("ro.tvbox.release");
        if (rel.isEmpty()) rel = "?";
        String media = prop("ro.boot.boot_devices").contains("ff500000") ? "с карты SD" : "с внутренней памяти";
        p.append("panadonik · Android 12 · выпуск ").append(rel).append(" · ").append(media).append("\n\n");
        p.append("УСТРОЙСТВО И РЕСУРСЫ\n");
        p.append("CPU: Rockchip RK3318 · 4 × Cortex-A53\n");
        p.append("GPU: Mali-450 MP\n");
        p.append("Вывод: 1920 × 1080\n");
        p.append(memorySummary()).append('\n');
        p.append(temperature()).append("\n\n");
        p.append("Этапы загрузки\n");
        List<String> summary = read(DIR + "startup-summary.txt");
        boolean hasStages = false;
        for (String line : summary) {
            if (line.contains("ЭТАПЫ") || line.matches(".*[0-9]+\\.[0-9]+.*с.*")) {
                hasStages = true;
                p.append(line).append('\n');
            }
        }
        if (!hasStages) {
            for (String[] s : STAGES) p.append("▫️ ").append(s[1]).append('\n');
        }
        return p.toString();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent().getBooleanExtra("startup", false)) {
            showStartupSummary();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(24, 16, 24, 16);

        ScrollView logScroll = new ScrollView(this);
        final TextView logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(13);
        logView.setTextColor(0xFFB0BEC5);
        logView.setPadding(12, 8, 12, 8);
        logView.setText("Загрузка журнала…");
        logScroll.addView(logView);
        LinearLayout.LayoutParams lpLog = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f);
        root.addView(logScroll, lpLog);

        ScrollView panelScroll = new ScrollView(this);
        final TextView panel = new TextView(this);
        panel.setTypeface(Typeface.MONOSPACE);
        panel.setTextSize(14);
        panel.setTextColor(0xFFE0E0E0);
        panel.setPadding(20, 12, 12, 12);
        panel.setBackgroundColor(0xFF1A2332);
        panel.setText("Загрузка…");
        panelScroll.addView(panel);
        LinearLayout.LayoutParams lpPanel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lpPanel.leftMargin = 16;
        root.addView(panelScroll, lpPanel);

        setContentView(root);
        logScroll.requestFocus();

        // dmesg/logcat/meminfo/thermal — диск и /proc вне main (slowMain при открытии журнала).
        new Thread(new Runnable() {
            public void run() {
                List<String> dmesg = read(DIR + "dmesg.txt");
                int from = Math.max(0, dmesg.size() - 80);
                final StringBuilder logs = new StringBuilder();
                for (int i = from; i < dmesg.size(); i++) {
                    String l = dmesg.get(i);
                    if (l.length() > 140) l = l.substring(0, 137) + "…";
                    logs.append(l).append('\n');
                }
                final String panelText = buildDevicePanelText();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        logView.setText(logs.toString());
                        panel.setText(panelText);
                    }
                });
            }
        }, "bootlog-load").start();
    }

    /** Short, non-animated startup report; the full diagnostic journal remains available from its tile. */
    void showStartupSummary() {
        final TextView tv = new TextView(this);
        tv.setText("Загрузка сводки…");
        tv.setTextSize(22);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(64, 48, 64, 48);
        tv.setLineSpacing(4, 1.0f);
        tv.setTextColor(Color.WHITE);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0D1117);
        sv.setFocusable(true);
        sv.addView(tv);
        setContentView(sv);
        sv.requestFocus();

        new Thread(new Runnable() {
            public void run() {
                final SpannableStringBuilder built = new SpannableStringBuilder();
                append(built, "PANADONIK  ·  RK3318", 0xFFE6EDF3, true);
                append(built, "СВОДКА ЗАГРУЗКИ УСТРОЙСТВА", 0xFF4FC3F7, true);
                append(built, "", Color.WHITE, false);
                List<String> rows = read(DIR + "startup-summary.txt");
                for (String line : rows) {
                    int color = line.contains("ОШИБКА") ? 0xFFFF6B6B
                            : line.contains("ПРОЦЕССОР") || line.contains("ГРАФИКА") || line.contains("ПАМЯТЬ")
                            ? 0xFF81C784 : line.contains("ЭТАПЫ") ? 0xFF4FC3F7 : 0xFFCFD8DC;
                    append(built, line, color, line.contains("ЭТАПЫ") || line.startsWith("Снимок:"));
                }
                append(built, "", Color.WHITE, false);
                append(built, "Через 10 секунд откроется главный экран.\nНажмите OK или Назад, чтобы продолжить сейчас.", 0xFF90A4AE, false);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        sb = built;
                        tv.setText(sb);
                    }
                });
            }
        }, "bootlog-startup").start();

        new Handler().postDelayed(new Runnable() {
            public void run() { openHome(); }
        }, 10_000);
    }

    static void append(SpannableStringBuilder target, String text, int color, boolean bold) {
        int s = target.length();
        target.append(text).append('\n');
        target.setSpan(new ForegroundColorSpan(color), s, target.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) target.setSpan(new StyleSpan(Typeface.BOLD), s, target.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    void openHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (getIntent().getBooleanExtra("startup", false)
                && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_BACK)) {
            openHome();
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
