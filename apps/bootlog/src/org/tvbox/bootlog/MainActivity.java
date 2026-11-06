package org.tvbox.bootlog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
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

    static boolean benign(String s) {
        for (String b : BENIGN) if (s.contains(b)) return true;
        return s.trim().isEmpty();
    }

    /** level: 0-3 error, 4 warn, else info. dmesg -r lines start with <N>; logcat-errors are all errors. */
    static int level(String line, boolean logcat) {
        // logcat-errors.txt holds every E line; most are vendor HAL chatter on a working box. An error here is a
        // real failure (crash, ANR, dead service, unlinkable binary); other E lines count as "notice" (5).
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent().getBooleanExtra("startup", false)) {
            showStartupSummary();
            return;
        }
        List<String> dmesg = read(DIR + "dmesg.txt");
        List<String> logcat = read(DIR + "logcat-errors.txt");
        List<String> upd = read(DIR + "updated.txt");
        add("Журнал загрузки — panadonik", Color.WHITE, true);
        add("Снят: " + (upd.isEmpty() ? "?" : upd.get(0)) + "   (▲▼ — прокрутка, Назад — выход)", 0xFF90A4AE, false);
        add("", Color.WHITE, false);
        List<String> summary = read(DIR + "startup-summary.txt");
        add("━━ Сводка устройства и ресурсов ━━", 0xFF4FC3F7, true);
        for (String line : summary) {
            int color = line.contains("ПРОЦЕССОР") || line.contains("ГРАФИКА") || line.contains("ПАМЯТЬ")
                    ? 0xFF81C784 : 0xFFCFD8DC;
            add(line, color, line.startsWith("Снимок:") || line.contains("ЭТАПЫ ЗАПУСКА"));
        }
        add("", Color.WHITE, false);
        section("Ядро и init (dmesg)", dmesg, false);
        section("Android (logcat, уровень E)", logcat, true);
        int from = Math.max(0, dmesg.size() - 400);
        add("━━ Журнал ядра: последние " + (dmesg.size() - from) + " строк из " + dmesg.size() + " ━━", 0xFF4FC3F7, true);
        for (int i = from; i < dmesg.size(); i++) { String l = dmesg.get(i); add(l, colorFor(level(l, false)), false); }

        TextView tv = new TextView(this);
        tv.setText(sb);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(48, 32, 48, 32);
        tv.setTextColor(Color.WHITE);
        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0D1117);
        sv.setFocusable(true);
        sv.addView(tv);
        setContentView(sv);
        sv.requestFocus();
    }

    /** Short, non-animated startup report; the full diagnostic journal remains available from its tile. */
    void showStartupSummary() {
        add("PANADONIK  ·  RK3318", 0xFFE6EDF3, true);
        add("СВОДКА ЗАГРУЗКИ УСТРОЙСТВА", 0xFF4FC3F7, true);
        add("", Color.WHITE, false);
        List<String> rows = read(DIR + "startup-summary.txt");
        for (String line : rows) {
            int color = line.contains("ОШИБКА") ? 0xFFFF6B6B
                    : line.contains("ПРОЦЕССОР") || line.contains("ГРАФИКА") || line.contains("ПАМЯТЬ")
                    ? 0xFF81C784 : line.contains("ЭТАПЫ") ? 0xFF4FC3F7 : 0xFFCFD8DC;
            add(line, color, line.contains("ЭТАПЫ") || line.startsWith("Снимок:"));
        }
        add("", Color.WHITE, false);
        add("Через 10 секунд откроется главный экран.\nНажмите OK или Назад, чтобы продолжить сейчас.", 0xFF90A4AE, false);

        TextView tv = new TextView(this);
        tv.setText(sb);
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
        new Handler().postDelayed(new Runnable() {
            public void run() { openHome(); }
        }, 10_000);
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
        ScrollView sv = (ScrollView) findViewById(android.R.id.content).getRootView().findViewById(android.R.id.content);
        return super.onKeyDown(code, e);
    }
}
