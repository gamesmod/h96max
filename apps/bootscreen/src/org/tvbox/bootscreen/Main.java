package org.tvbox.bootscreen;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Boot screen for rk3318-android: replaces the boot animation with a live boot log.
 * Runs via app_process as the "bootanim" service (root), draws /dev/kmsg + logcat
 * into a top-most SurfaceFlinger layer and exits when service.bootanim.exit=1
 * (WindowManager waits for the bootanim service to stop before showing the UI).
 * Right column: boot stages in Russian (ro.boottime.* + boot_progress_* events) with their uptime.
 */
public class Main {
    static final int MAX = 400;
    static final ArrayList<String> lines = new ArrayList<>();   // first char = level digit, "9" = translated milestone
    static final ArrayList<String> errors = new ArrayList<>();
    static volatile boolean dirty = true;

    /** vendor noise that is logged as an error on every good boot (same list as the "Журнал загрузки" app) */
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
        "LEGACY_SCAN", "dhd_conf_set_country", "Country code", "WLC_E_LINK", "wl_iw_event", "wl_ext_iapsta_event",
        "wl_notify_connect_status", "wl_cfg80211_disconnect", "init.usb.configfs.rc", "sys.usb.controller", "SyncWorker"
    };

    /** debug-level chatter that would only flood the screen */
    static final String[] FLOOD = {
        "/libprocessgroup", "BufferQueue", "SurfaceFlinger", "Choreographer", "OpenGLRenderer", "gralloc", "mali",
        "hwc_", "HwcComposer", "Layer", "RenderEngine", "ziparchive", "nativeloader", "ServiceManager: Waiting",
        "linker", "AudioFlinger", "audio_hw", "StrictMode", "chatty", "ConnectivityService", "NetworkMonitor"
    };

    /** well-known messages shown in Russian (and highlighted) instead of the raw line */
    static final String[][] RU = {
        {"Booting Linux", "🐧 Запуск ядра Linux"},
        {"init first stage started", "⚙️ init: первая стадия"},
        {"Switching root to", "⚙️ init: переход в корень системы"},
        {"init second stage started", "⚙️ init: вторая стадия, SELinux и службы"},
        {"EXT4-fs (mmcblk", "💾 Подключён раздел ext4"},
        {"EXT4-fs (dm-", "💾 Подключён раздел ext4"},
        {"Entered the Android system server", "🧠 Запущен системный сервер Android"},
        {"Starting phase 1000", "🚀 Все системные службы запущены"},
        {"System now ready", "🚀 Система готова к работе"},
        {"wlan0: link becomes ready", "📶 Wi‑Fi: связь установлена"},
        {"CTRL-EVENT-CONNECTED", "📶 Wi‑Fi подключён"},
        {"Bluetooth enabled", "🔵 Bluetooth включён"},
    };

    static final String[][] STAGES = {   // key, title; key: "p:"+boottime property, "e:"+event, "x:" special
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
    static final double[] stageAt = new double[STAGES.length];   // seconds since kernel start, 0 = not yet

    static boolean contains(String s, String[] list) {
        for (String b : list) if (s.contains(b)) return true;
        return false;
    }

    static String prop(String k) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, k);
        } catch (Throwable t) { return ""; }
    }

    static double uptime() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/uptime")))) {
            return Double.parseDouble(r.readLine().split(" ")[0]);
        } catch (Exception e) { return 0; }
    }

    /** Возвращает первую строку sysfs; недоступное значение показывается явно. */
    static String readOne(String path) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path)))) {
            return r.readLine().trim();
        } catch (Exception e) { return "нет данных"; }
    }

    /** Короткая сводка ОЗУ без запуска тяжёлых диагностических команд. */
    static String memorySummary() {
        long total = 0, free = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) total = Long.parseLong(l.replaceAll("[^0-9]", ""));
                if (l.startsWith("MemAvailable:")) free = Long.parseLong(l.replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) { return "ОЗУ: нет данных"; }
        return total == 0 ? "ОЗУ: нет данных" : String.format("ОЗУ: %d / %d МБ занято", (total - free) / 1024, total / 1024);
    }

    /** Показывает первое доступное показание термодатчика. */
    static String temperature() {
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            try {
                double c = Double.parseDouble(readOne(z.getPath() + "/temp"));
                if (c > 0 && c < 200) return String.format("Температура: %.1f °C", c > 1000 ? c / 1000 : c);
            } catch (Exception e) { }
        }
        return "Температура: нет данных";
    }

    static void stage(String key, double at) {
        for (int i = 0; i < STAGES.length; i++)
            if (STAGES[i][0].equals(key) && stageAt[i] == 0) { stageAt[i] = Math.max(at, 0.001); dirty = true; }
    }

    static void add(int lvl, String s) {
        if (s.isEmpty()) return;
        for (String[] t : RU)
            if (s.contains(t[0])) { s = t[1] + "   · " + s.trim(); lvl = 9; break; }
        synchronized (lines) {
            lines.add(lvl + s);
            if (lines.size() > MAX) lines.remove(0);
            if (lvl <= 3 && !contains(s, BENIGN)) { errors.add(s); if (errors.size() > 4) errors.remove(0); }
            dirty = true;
        }
    }

    /** /dev/kmsg record: "6,1234,5678901,-;text" */
    static void kmsg() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/dev/kmsg")), 1 << 16)) {
            String l;
            while ((l = r.readLine()) != null) {
                int semi = l.indexOf(';');
                if (semi < 0) continue;            // continuation line
                String[] h = l.substring(0, semi).split(",");
                int lvl = 6;
                long us = 0;
                try { lvl = Integer.parseInt(h[0]) & 7; us = Long.parseLong(h[2]); } catch (Exception e) { }
                if (us > 0) stage("x:kernel", 0.001);
                add(lvl, String.format("🐧 [%5d.%03d] %s", us / 1000000, us / 1000 % 1000, l.substring(semi + 1)));
            }
        } catch (Throwable t) { add(3, "kmsg: " + t); }
    }

    /** logcat -v brief at debug level: "D/Tag(  123): text" */
    static void logcat() {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                Process p = new ProcessBuilder("/system/bin/logcat", "-v", "brief", "-b", "main,system,crash", "*:D")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 1 << 16);
                String l;
                while ((l = r.readLine()) != null) {
                    int lvl = 6;
                    if (l.length() > 1 && l.charAt(1) == '/') {
                        char c = l.charAt(0);
                        lvl = c == 'F' ? 2 : c == 'E' ? 3 : c == 'W' ? 4 : c == 'D' || c == 'V' ? 7 : 6;
                    }
                    if (lvl == 7 && contains(l, FLOOD)) continue;
                    add(lvl, "🤖 " + l);
                }
            } catch (Throwable t) { }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    /** events buffer: "I/boot_progress_pms_ready(  456): 23456" (value = uptime in ms) */
    static void events() {
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                Process p = new ProcessBuilder("/system/bin/logcat", "-v", "brief", "-b", "events", "boot_progress_*:I", "*:S")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String l;
                while ((l = r.readLine()) != null) {
                    int a = l.indexOf('/'), b = l.indexOf('('), c = l.lastIndexOf(':');
                    if (a < 0 || b < a || c < b) continue;
                    String tag = l.substring(a + 1, b).trim();
                    try { stage("e:" + tag, Long.parseLong(l.substring(c + 1).trim()) / 1000.0); } catch (Exception e) { }
                }
            } catch (Throwable t) { }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    /** properties and sysfs, polled with the frame */
    static void pollStages() {
        for (String[] s : STAGES)
            if (s[0].startsWith("p:")) {
                String v = prop(s[0].substring(2));   // ns since boot
                if (!v.isEmpty()) try { stage(s[0], Long.parseLong(v) / 1e9); } catch (Exception e) { }
            }
        String op = "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/sys/class/net/wlan0/operstate")))) {
            op = r.readLine();
        } catch (Exception e) { }
        if ("up".equals(op)) stage("x:wifi", uptime());
        if ("1".equals(prop("sys.boot_completed"))) stage("x:done", uptime());
    }

    static int[] displaySize() {
        int[] wh = {1920, 1080};
        try {   // A12 hidden API: SurfaceControl.getDynamicDisplayInfo(getInternalDisplayToken())
            Object tok = SurfaceControl.class.getMethod("getInternalDisplayToken").invoke(null);
            Object info = SurfaceControl.class.getMethod("getDynamicDisplayInfo", android.os.IBinder.class).invoke(null, tok);
            Object[] modes = (Object[]) info.getClass().getField("supportedDisplayModes").get(info);
            int active = info.getClass().getField("activeDisplayModeId").getInt(info);
            for (Object m : modes)
                if (m.getClass().getField("id").getInt(m) == active) {
                    wh[0] = m.getClass().getField("width").getInt(m);
                    wh[1] = m.getClass().getField("height").getInt(m);
                }
        } catch (Throwable t) { add(4, "display size: " + t); }
        return wh;
    }

    static String clip(String t, Paint p, float w) {
        if (p.measureText(t) <= w) return t;
        int n = p.breakText(t, true, w, null);
        return t.substring(0, Math.max(0, n));
    }

    public static void main(String[] args) throws Exception {
        Thread k = new Thread(new Runnable() { public void run() { kmsg(); } }); k.setDaemon(true); k.start();
        Thread lc = new Thread(new Runnable() { public void run() { logcat(); } }); lc.setDaemon(true); lc.start();
        Thread ev = new Thread(new Runnable() { public void run() { events(); } }); ev.setDaemon(true); ev.start();

        // outside zygote nothing has loaded the system fonts (hwui aborts on a null default typeface)
        try { Typeface.class.getMethod("loadPreinstalledSystemFontMap").invoke(null); } catch (Throwable t) { add(4, "fonts: " + t); }
        Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        int[] wh = displaySize();
        int W = wh[0], H = wh[1];
        SurfaceControl sc = new SurfaceControl.Builder().setName("tvbox-bootscreen")
                .setBufferSize(W, H).setFormat(PixelFormat.OPAQUE).build();
        new SurfaceControl.Transaction().setLayer(sc, 0x40000000).show(sc).apply();
        Surface surface = new Surface(sc);

        float scale = H / 1080f;
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(mono);
        text.setTextSize(18 * scale);
        Paint.FontMetrics fm = text.getFontMetrics();
        float lh = (fm.descent - fm.ascent) * 1.05f;
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        title.setTextSize(30 * scale);
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setTypeface(Typeface.SANS_SERIF);
        st.setTextSize(20 * scale);
        Paint.FontMetrics sfm = st.getFontMetrics();
        float slh = (sfm.descent - sfm.ascent) * 1.35f;
        Paint bar = new Paint();
        float pad = 24 * scale, top = 70 * scale;
        float panelW = 610 * scale, logW = W - panelW - 3 * pad;
        String rel = prop("ro.tvbox.release");
        String head = "📺 panadonik · Android 12" + (rel.isEmpty() ? "" : " · выпуск " + rel)
                + " · " + (prop("ro.boot.boot_devices").contains("ff500000") ? "💳 с карты SD" : "🗄 с внутренней памяти");

        // "test N": run N seconds over a running system (service.bootanim.exit is already 1 then)
        long testUntil = args.length > 1 && args[0].equals("test") ? System.currentTimeMillis() + Long.parseLong(args[1]) * 1000 : 0;
        long lastPoll = 0;
        while (testUntil > 0 ? System.currentTimeMillis() < testUntil : !"1".equals(prop("service.bootanim.exit"))) {
            if (System.currentTimeMillis() - lastPoll > 500) { lastPoll = System.currentTimeMillis(); pollStages(); dirty = true; }
            if (dirty) {
                dirty = false;
                ArrayList<String> snap, errs;
                synchronized (lines) { snap = new ArrayList<>(lines); errs = new ArrayList<>(errors); }
                double up = uptime();
                Canvas c = surface.lockCanvas(null);
                c.drawColor(0xFF000000);
                bar.setColor(0xFF0D2818);
                c.drawRect(0, 0, W, top - 12 * scale, bar);
                title.setColor(0xFF39FF14);
                c.drawText(head + " · ⏱ " + (int) up + " с", pad, 44 * scale, title);

                // Правая панель показывает краткие аппаратные метрики и затем ход загрузки.
                float px = W - panelW - pad;
                bar.setColor(0xFF0B1620);
                c.drawRect(px - pad / 2, top - 4 * scale, W, H, bar);
                st.setColor(0xFF80D8FF);
                c.drawText("УСТРОЙСТВО И РЕСУРСЫ", px, top + slh * 0.8f, st);
                float sy = top + slh * 1.9f;
                st.setColor(0xFFC5E1A5);
                c.drawText("CPU: Rockchip RK3318 · 4 × Cortex-A53", px, sy, st); sy += slh;
                c.drawText("GPU: Mali-450 MP", px, sy, st); sy += slh;
                c.drawText("Вывод: " + W + " × " + H, px, sy, st); sy += slh;
                c.drawText(memorySummary(), px, sy, st); sy += slh;
                c.drawText(temperature(), px, sy, st); sy += slh * 1.5f;
                st.setColor(0xFF80D8FF);
                c.drawText("Этапы загрузки", px, sy, st);
                sy += slh * 1.2f;
                boolean current = false;
                for (int i = 0; i < STAGES.length; i++) {
                    boolean done = stageAt[i] > 0;
                    String mark = done ? "✅" : !current ? "⏳" : "▫️";
                    if (!done) current = true;
                    st.setColor(done ? 0xFFC5E1A5 : mark.equals("⏳") ? 0xFFFFE082 : 0xFF607D8B);
                    c.drawText(mark + " " + STAGES[i][1], px, sy, st);
                    if (done && i > 0) {
                        String t = String.format("%.1f с", stageAt[i]);
                        c.drawText(t, W - pad - st.measureText(t), sy, st);
                    }
                    sy += slh;
                }

                // log
                int errRows = errs.size();
                float bottom = H - pad - (errRows > 0 ? (errRows + 1) * lh + 8 * scale : 0);
                int rows = (int) ((bottom - top) / lh);
                int from = Math.max(0, snap.size() - rows);
                float y = top - fm.ascent;
                for (int i = from; i < snap.size(); i++) {
                    String l = snap.get(i);
                    int lvl = l.charAt(0) - '0';
                    String t = l.substring(1);
                    boolean live = i == snap.size() - 1;
                    if (live) t = String.format("СЕЙЧАС · %.1f с  |  %s", up, t);
                    if (lvl <= 3) t = "❌ " + t; else if (lvl == 4) t = "⚠️ " + t;
                    text.setColor(live ? 0xFF80D8FF : lvl == 9 ? 0xFF80D8FF : lvl <= 3 ? 0xFFFF5252 : lvl == 4 ? 0xFFFFD54F
                            : lvl == 7 ? 0xFF78909C : t.startsWith("🤖") ? 0xFF8BC34A : 0xFFB0BEC5);
                    c.drawText(clip(t, text, logW), pad, y, text);
                    y += lh;
                }
                if (errRows > 0) {
                    float ey = H - pad - (errRows + 1) * lh;
                    bar.setColor(0xFF2A0A0A);
                    c.drawRect(0, ey - 6 * scale, px - pad / 2, H, bar);
                    text.setColor(0xFFFF8A80);
                    c.drawText("🚨 Последние ошибки:", pad, ey - fm.ascent, text);
                    for (int i = 0; i < errRows; i++)
                        c.drawText(clip(errs.get(i), text, logW), pad, ey + (i + 1) * lh - fm.ascent, text);
                }
                surface.unlockCanvasAndPost(c);
            }
            Thread.sleep(150);
        }
        new SurfaceControl.Transaction().hide(sc).apply();
        surface.release();
        sc.release();
        System.exit(0);
    }
}
