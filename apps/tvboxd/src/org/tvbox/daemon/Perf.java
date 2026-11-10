package org.tvbox.daemon;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Metrics for the "Производительность" tile, read only on request while the tile is open (apps cannot read
 * /proc/stat on Android 12). No background sampling: the app polls PERF_NOW every 2 s and keeps the history itself.
 * Commands: PERF_NOW, PERF_FREE.
 */
class Perf {
    static final String TEMP = "/sys/class/thermal/thermal_zone0/temp";
    static final String GPU = "/sys/class/devfreq/ff300000.gpu/load";
    static final String CPU_FREQ = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq";
    static final File ZRAM_CONFIG = new File(Main.STATE, "zram-mb");
    static long[][] lastStat;
    static long lastAt;
    static long lastTopAt;
    static List<String> lastTop;

    /** CPU load in % since the previous call (or over 300 ms on the first / a stale one): total first, then per core. */
    static synchronized int[] cpuLoad() {
        if (lastStat == null || System.currentTimeMillis() - lastAt > 10000) { lastStat = readStat(); Main.sleep(300); }
        long[][] st = readStat();
        int[] pct = new int[st.length];
        for (int i = 0; i < st.length && i < lastStat.length; i++) {
            long busy = st[i][0] - lastStat[i][0], all = st[i][1] - lastStat[i][1];
            pct[i] = all > 0 ? (int) (busy * 100 / all) : 0;
        }
        lastStat = st;
        lastAt = System.currentTimeMillis();
        return pct;
    }

    /** Per line of /proc/stat (cpu, cpu0..): {busy, total} jiffies. */
    static long[][] readStat() {
        List<long[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            for (String l; (l = r.readLine()) != null && l.startsWith("cpu"); ) {
                String[] p = l.trim().split("\\s+");
                long all = 0, idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                for (int i = 1; i < p.length; i++) all += Long.parseLong(p[i]);
                rows.add(new long[]{all - idle, all});
            }
        } catch (Exception ignored) { }
        return rows.toArray(new long[0][]);
    }

    /** {MemTotal, MemAvailable, SwapTotal, SwapFree} in kB. */
    static long[] meminfo() {
        long[] v = new long[4];
        String[] keys = {"MemTotal:", "MemAvailable:", "SwapTotal:", "SwapFree:"};
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            for (String l; (l = r.readLine()) != null; )
                for (int i = 0; i < 4; i++) if (l.startsWith(keys[i])) v[i] = num(l);
        } catch (Exception ignored) { }
        return v;
    }

    /** devfreq "load" reads "37@200000000Hz". */
    static int gpuLoad() {
        String s = Main.read(GPU).trim();
        int at = s.indexOf('@');
        return at > 0 ? (int) num(s.substring(0, at)) : 0;
    }

    static long num(String s) {
        String d = s.replaceAll("[^0-9]", "");
        try { return d.isEmpty() ? 0 : Long.parseLong(d); } catch (Exception e) { return 0; }
    }

    static void now(Main.Op op) {
        int[] c = cpuLoad();
        long[] m = meminfo();
        op.kv("TEMP", String.valueOf(num(Main.read(TEMP)) / 100 / 10.0));
        op.kv("CPU", String.valueOf(c.length > 0 ? c[0] : 0));
        StringBuilder cs = new StringBuilder();
        for (int i = 1; i < c.length; i++) cs.append(cs.length() > 0 ? "," : "").append(c[i]);
        op.kv("CORES", cs.toString());
        op.kv("CPU_MHZ", String.valueOf(num(Main.read(CPU_FREQ)) / 1000));
        op.kv("GPU", String.valueOf(gpuLoad()));
        op.kv("MEM_MB", (m[0] - m[1]) / 1024 + " " + m[0] / 1024);
        op.kv("SWAP_MB", (m[2] - m[3]) / 1024 + " " + m[2] / 1024);
        zramStatus(op);
        op.kv("LOAD", Main.read("/proc/loadavg").trim().split(" ")[0]);
        op.kv("UPTIME", Main.read("/proc/uptime").trim().split("[ .]")[0]);
        for (String p : topProcesses(6)) op.kv("PROC", p);
    }

    static void zramStatus(Main.Op op) {
        long max = meminfo()[0] / 1024;
        String saved = Main.read(ZRAM_CONFIG.getPath()).trim();
        long configured = saved.matches("[0-9]{1,6}") ? Long.parseLong(saved) : 0;
        op.kv("ZRAM_MAX_MB", String.valueOf(max));
        op.kv("ZRAM_CONFIG_MB", String.valueOf(Math.min(configured, max)));
        op.kv("ZRAM_ACTIVE_MB", String.valueOf(num(Main.read("/sys/block/zram0/disksize")) >> 20));
        String[] stats = Main.read("/sys/block/zram0/mm_stat").trim().split("\\s+");
        if (stats.length >= 3) {
            op.kv("ZRAM_DATA_MB", String.valueOf(num(stats[0]) >> 20));
            op.kv("ZRAM_RAM_MB", String.valueOf(num(stats[2]) >> 20));
        }
    }

    /** Настройка сжатой подкачки отключена по требованию пользователя. */
    static synchronized void setZram(Main.Op op, String value) {
        op.done(false, "сжатая подкачка отключена");
    }

    /** "rssMB name" of the biggest processes by resident memory. */
    static synchronized List<String> topProcesses(int n) {
        long now = System.currentTimeMillis();
        // Чтение /proc для тысячи процессов дорого; свежести списка раз в 10 секунд достаточно для графика.
        if (lastTop != null && now - lastTopAt < 10000) {
            return new ArrayList<>(lastTop.subList(0, Math.min(n, lastTop.size())));
        }
        List<long[]> rss = new ArrayList<>();
        List<String> names = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.getName().matches("[0-9]+")) continue;
            long kb = 0;
            String name = null;
            try (BufferedReader r = new BufferedReader(new FileReader(new File(d, "status")))) {
                for (String l; (l = r.readLine()) != null; ) {
                    if (l.startsWith("Name:")) name = l.substring(5).trim();
                    else if (l.startsWith("VmRSS:")) { kb = num(l); break; }
                }
            } catch (Exception ignored) { continue; }
            String cmd = Main.read(d.getPath() + "/cmdline").split("\u0000")[0].trim();
            if (!cmd.isEmpty() && !cmd.startsWith("/")) name = cmd;
            if (kb > 0 && name != null) { rss.add(new long[]{kb, names.size()}); names.add(name); }
        }
        Collections.sort(rss, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) { return Long.compare(b[0], a[0]); }
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(20, rss.size()); i++) out.add(rss.get(i)[0] / 1024 + " " + names.get((int) rss.get(i)[1]));
        lastTop = out;
        lastTopAt = now;
        return new ArrayList<>(out.subList(0, Math.min(n, out.size())));
    }

    /** Stops cached background apps (am kill-all leaves visible and service processes alone) and trims app caches. */
    static void free(Main.Op op) {
        long memBefore = meminfo()[1], diskBefore = new File("/data").getUsableSpace();
        Main.sh("am kill-all");
        Main.sh("pm trim-caches 999G");
        Main.sleep(1500);
        long memAfter = meminfo()[1], diskAfter = new File("/data").getUsableSpace();
        op.kv("FREED_MEM_MB", String.valueOf(Math.max(0, memAfter - memBefore) / 1024));
        op.kv("FREED_DISK_MB", String.valueOf(Math.max(0, diskAfter - diskBefore) >> 20));
        op.done(true, "ресурсы освобождены");
    }
}
