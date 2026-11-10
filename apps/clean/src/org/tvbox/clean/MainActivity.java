package org.tvbox.clean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * «Производительность и диагностика»: метрики, журнал загрузки и подробный локальный журнал событий по запросу.
 * Метрики опрашиваются только пока открыт экран; CSV-история хранится семь дней с шагом одна минута.
 */
public class MainActivity extends Activity {
    static final int PERIOD_MS = 2000, KEEP = 3600 * 1000 / PERIOD_MS;
    static final long HISTORY_PERIOD_MS = 60_000L, HISTORY_KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    static final int BG = 0xFF101418, CARD = 0xFF1A2026, GREEN = 0xFF66BB6A, YELLOW = 0xFFFFCA28, RED = 0xFFEF5350;

    final Handler ui = new Handler();
    Chart temp, cpu, gpu, mem;
    TextView uptime, procs;
    TextView debugLogInfo;
    Button debugLogButton;
    volatile boolean running;
    long lastHistoryWrite, lastHistoryPrune;
    int exportRequest;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(48, 32, 48, 32);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Производительность и диагностика", 32, Color.WHITE);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        uptime = text("", 20, 0xFF90A4AE);
        head.addView(uptime);
        root.addView(head);

        temp = new Chart(this, "🌡 Температура", "°C", 30, 100);
        cpu = new Chart(this, "⚙ Процессор", "%", 0, 100);
        gpu = new Chart(this, "🎮 Графика", "%", 0, 100);
        mem = new Chart(this, "🧠 Память", "%", 0, 100);
        root.addView(row(temp, cpu), new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(row(gpu, mem), new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout historyRow = new LinearLayout(this);
        historyRow.addView(button("История 7 дней", new View.OnClickListener() {
            public void onClick(View v) { showHistory(); }
        }));
        root.addView(historyRow);

        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setGravity(Gravity.CENTER_VERTICAL);
        diagnostics.setPadding(0, 12, 0, 0);
        debugLogInfo = text("Подробный системный журнал выключен", 18, 0xFFB0BEC5);
        diagnostics.addView(debugLogInfo, new LinearLayout.LayoutParams(0, -2, 1));
        diagnostics.addView(button("Журнал загрузки", new View.OnClickListener() {
            public void onClick(View v) { openBootLog(); }
        }));
        debugLogButton = button("Включить подробный журнал", new View.OnClickListener() {
            public void onClick(View v) { toggleDebugLog(); }
        });
        diagnostics.addView(debugLogButton);
        root.addView(diagnostics);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, 16, 0, 0);
        procs = text("", 18, 0xFFB0BEC5);
        bottom.addView(procs, new LinearLayout.LayoutParams(0, -2, 1));
        Button free = button("🧹  Освободить ресурсы", new View.OnClickListener() {
            public void onClick(View v) { free(); }
        });
        bottom.addView(free);
        bottom.addView(button("Закрыть", new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }));
        root.addView(bottom);
        setContentView(root);
        free.requestFocus();
        refreshDebugLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        new Thread(new Runnable() {
            public void run() {
                while (running) {
                    final Map<String, List<String>> m = call("PERF_NOW");
                    ui.post(new Runnable() { public void run() { show(m); } });
                    try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
                }
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
    }

    void show(Map<String, List<String>> m) {
        if (m.containsKey("ERROR")) { uptime.setText("служба tvboxd недоступна: " + one(m, "ERROR")); return; }
        float t = f(one(m, "TEMP"));
        temp.add(t, String.format("%.1f °C", t), t < 75 ? GREEN : t < 85 ? YELLOW : RED, "");
        float c = f(one(m, "CPU"));
        cpu.add(c, Math.round(c) + " %", level(c), one(m, "CPU_MHZ") + " МГц · ядра " + one(m, "CORES").replace(",", " / ") + " % · нагрузка " + one(m, "LOAD"));
        float g = f(one(m, "GPU"));
        gpu.add(g, Math.round(g) + " %", level(g), "");
        String[] mm = one(m, "MEM_MB").split(" "), sw = one(m, "SWAP_MB").split(" ");
        float used = f(mm[0]), total = mm.length > 1 ? f(mm[1]) : 0;
        float pct = total > 0 ? used * 100 / total : 0;
        mem.add(pct, String.format("%.1f / %.1f ГБ", used / 1024, total / 1024), level(pct),
                "своп " + (sw.length > 1 ? sw[0] + " / " + sw[1] + " МБ" : "—"));
        storeHistory(m, t, c, g, used, total);
        long up = (long) f(one(m, "UPTIME"));
        uptime.setText(String.format("работает %d ч %02d мин · история с открытия: %s", up / 3600, up / 60 % 60, temp.span()));
        StringBuilder p = new StringBuilder("Больше всего памяти:  ");
        List<String> list = m.get("PROC");
        if (list != null) for (int i = 0; i < list.size(); i++) {
            String[] x = list.get(i).split(" ", 2);
            p.append(i > 0 ? "  ·  " : "").append(x.length > 1 ? x[1] : "?").append(' ').append(x[0]).append(" МБ");
        }
        procs.setText(p);
    }

    /** Записывать только одну строку в минуту и удалять всё старше недели; опрос останавливается при закрытии экрана. */
    void storeHistory(Map<String, List<String>> m, float t, float c, float g, float used, float total) {
        long now = System.currentTimeMillis();
        File file = historyFile();
        if (lastHistoryWrite == 0) {
            ensureHistoryHeader(file);
            lastHistoryWrite = lastHistoryTimestamp(file);
        }
        if (now - lastHistoryWrite >= HISTORY_PERIOD_MS) {
            try {
                if (!file.exists()) file.getParentFile().mkdirs();
                boolean empty = file.length() == 0;
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"));
                if (empty) out.write("epoch,temp_c,cpu_pct,gpu_pct,ram_used_mb,ram_total_mb,cpu_mhz\n");
                out.write(String.format(Locale.US, "%d,%.2f,%.2f,%.2f,%.0f,%.0f,%s\n", now / 1000,
                        t, c, g, used, total, one(m, "CPU_MHZ")));
                out.close();
                lastHistoryWrite = now;
            } catch (Exception e) { android.util.Log.w("tvbox-perf", "Не удалось сохранить историю показателей", e); }
        }
        if (now - lastHistoryPrune > 6L * 60 * 60 * 1000) {
            pruneHistory(file, now / 1000 - HISTORY_KEEP_MS / 1000);
            lastHistoryPrune = now;
        }
    }

    File historyFile() { return new File(getFilesDir(), "performance-history.csv"); }

    /** Старую историю без строки заголовка привести к обычному CSV при первом открытии после обновления. */
    void ensureHistoryHeader(File file) {
        if (!file.isFile() || file.length() == 0) return;
        File tmp = new File(file.getParentFile(), "performance-history.tmp");
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String first = in.readLine();
            if (first == null || first.startsWith("epoch,")) { in.close(); return; }
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            out.write("epoch,temp_c,cpu_pct,gpu_pct,ram_used_mb,ram_total_mb,cpu_mhz\n");
            out.write(first); out.write('\n');
            String line;
            while ((line = in.readLine()) != null) { out.write(line); out.write('\n'); }
            in.close(); out.close();
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (Exception e) { tmp.delete(); android.util.Log.w("tvbox-perf", "Не удалось добавить заголовок истории", e); }
    }

    long lastHistoryTimestamp(File file) {
        long last = 0;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                int comma = line.indexOf(',');
                if (comma > 0) try { last = Math.max(last, Long.parseLong(line.substring(0, comma)) * 1000); }
                catch (NumberFormatException ignored) { }
            }
            in.close();
        } catch (Exception ignored) { }
        return last;
    }

    void pruneHistory(File file, long cutoff) {
        if (!file.isFile()) return;
        File tmp = new File(file.getParentFile(), "performance-history.tmp");
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) {
                int comma = line.indexOf(',');
                if (comma < 0) { if (line.startsWith("epoch,")) out.write(line + "\n"); continue; }
                try { if (Long.parseLong(line.substring(0, comma)) >= cutoff) out.write(line + "\n"); }
                catch (NumberFormatException ignored) { }
            }
            in.close(); out.close();
            if (tmp.renameTo(file)) return;
            file.delete(); tmp.renameTo(file);
        } catch (Exception e) { tmp.delete(); android.util.Log.w("tvbox-perf", "Не удалось очистить старую историю", e); }
    }

    /** Показать агрегаты по дням и дать сохранить исходный CSV для последующего анализа. */
    void showHistory() {
        TreeMap<String, HistoryDay> days = new TreeMap<>();
        File file = historyFile();
        int samples = 0;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String line;
            while ((line = in.readLine()) != null) {
                String[] x = line.split(",");
                if (x.length != 7 || x[0].equals("epoch")) continue;
                try {
                    long epoch = Long.parseLong(x[0]);
                    HistoryDay day = days.get(dayFormat.format(new java.util.Date(epoch * 1000)));
                    if (day == null) { day = new HistoryDay(); days.put(dayFormat.format(new java.util.Date(epoch * 1000)), day); }
                    day.add(Float.parseFloat(x[1]), Float.parseFloat(x[2]), Float.parseFloat(x[3]), Float.parseFloat(x[4]));
                    samples++;
                } catch (NumberFormatException ignored) { }
            }
            in.close();
        } catch (Exception ignored) { }
        StringBuilder report = new StringBuilder("Опрос идёт раз в минуту только пока открыта плитка. Хранится до 7 дней; записей: ").append(samples).append("\n\n");
        if (days.isEmpty()) report.append("История пока пуста. Оставьте экран открытым хотя бы на минуту.");
        else for (Map.Entry<String, HistoryDay> e : days.entrySet()) {
            HistoryDay d = e.getValue();
            report.append(e.getKey()).append(" · ").append(d.n).append(" зам.\n")
                    .append(String.format(Locale.getDefault(), "  CPU ср./пик %.0f/%.0f%% · GPU %.0f/%.0f%% · температура ср./пик %.1f/%.1f°C · ОЗУ ср. %.0f МБ\n\n",
                            d.cpu / d.n, d.cpuMax, d.gpu / d.n, d.gpuMax, d.temp / d.n, d.tempMax, d.ram / d.n));
        }
        TextView body = text(report.toString(), 20, 0xFFCFD8DC);
        body.setPadding(32, 24, 32, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("История производительности")
                .setView(scroll).setNegativeButton("Закрыть", null)
                .setPositiveButton("Экспорт CSV", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { exportHistory(); }
                }).show();
    }

    void exportHistory() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "panadonik-performance-history.csv");
        try { startActivityForResult(intent, 17); }
        catch (Exception e) { Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show(); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 17 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(historyFile()), "UTF-8"));
            OutputStreamWriter out = new OutputStreamWriter(getContentResolver().openOutputStream(uri), "UTF-8");
            String first = in.readLine();
            if (first == null || !first.startsWith("epoch,")) {
                out.write("epoch,temp_c,cpu_pct,gpu_pct,ram_used_mb,ram_total_mb,cpu_mhz\n");
            }
            if (first != null) { out.write(first); out.write('\n'); }
            String line;
            while ((line = in.readLine()) != null) { out.write(line); out.write('\n'); }
            in.close(); out.close();
            Toast.makeText(this, "CSV сохранён", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Ошибка экспорта CSV: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    static class HistoryDay {
        int n; float temp, tempMax, cpu, cpuMax, gpu, gpuMax, ram;
        void add(float t, float c, float g, float r) {
            if (n++ == 0) { tempMax = t; cpuMax = c; gpuMax = g; }
            temp += t; cpu += c; gpu += g; ram += r;
            tempMax = Math.max(tempMax, t); cpuMax = Math.max(cpuMax, c); gpuMax = Math.max(gpuMax, g);
        }
    }

    void free() {
        Toast.makeText(this, "Освобождаю…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                Map<String, List<String>> m = call("PERF_FREE");
                final String msg = m.containsKey("ERROR") ? "Не получилось: " + one(m, "ERROR")
                        : "Освобождено: память " + one(m, "FREED_MEM_MB") + " МБ, кэш приложений " + one(m, "FREED_DISK_MB") + " МБ";
                ui.post(new Runnable() { public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); } });
            }
        }).start();
    }

    /** Открыть журнал загрузки из экрана диагностики, не занимая отдельную плитку лаунчера. */
    void openBootLog() {
        Intent i = new Intent();
        i.setClassName("org.tvbox.bootlog", "org.tvbox.bootlog.MainActivity");
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "Журнал загрузки не установлен", Toast.LENGTH_LONG).show(); }
    }

    /** Запросить состояние необязательного подробного системного журнала. */
    void refreshDebugLog() {
        new Thread(new Runnable() { public void run() {
            final Map<String, List<String>> m = call("PERF_SYSLOG_STATUS");
            ui.post(new Runnable() { public void run() {
                if (debugLogInfo == null || debugLogButton == null) return;
                boolean active = "1".equals(one(m, "SYSLOG_ACTIVE"));
                debugLogInfo.setText(m.containsKey("ERROR") ? "Служба диагностики недоступна"
                        : active ? "Подробный системный журнал включён · запись в /data/vendor/tvbox" : "Подробный системный журнал выключен");
                debugLogButton.setText(active ? "Выключить подробный журнал" : "Включить подробный журнал");
            } });
        } }).start();
    }

    /** Глубокий сбор запускается только по явному нажатию и ограничен ротацией файлов на приставке. */
    void toggleDebugLog() {
        debugLogButton.setEnabled(false);
        new Thread(new Runnable() { public void run() {
            Map<String, List<String>> status = call("PERF_SYSLOG_STATUS");
            String command = "1".equals(one(status, "SYSLOG_ACTIVE")) ? "PERF_SYSLOG_OFF" : "PERF_SYSLOG_ON";
            final Map<String, List<String>> result = call(command);
            ui.post(new Runnable() { public void run() {
                debugLogButton.setEnabled(true);
                if (result.containsKey("ERROR")) Toast.makeText(MainActivity.this, "Не удалось изменить журнал: " + one(result, "ERROR"), Toast.LENGTH_LONG).show();
                refreshDebugLog();
            } });
        } }).start();
    }

    /** One tvboxd command; "KEY value" lines grouped by key, a failure under ERROR. */
    static Map<String, List<String>> call(String cmd) {
        Map<String, List<String>> m = new HashMap<>();
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress("tvboxd"));
            new PrintWriter(s.getOutputStream(), true).println(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            for (String l; (l = r.readLine()) != null; ) {
                int sp = l.indexOf(' ');
                String k = sp > 0 ? l.substring(0, sp) : l, v = sp > 0 ? l.substring(sp + 1) : "";
                if (k.equals("DONE") && v.startsWith("fail")) k = "ERROR";
                if (!m.containsKey(k)) m.put(k, new ArrayList<String>());
                m.get(k).add(v);
            }
            s.close();
        } catch (Exception e) {
            m.put("ERROR", new ArrayList<String>());
            m.get("ERROR").add(String.valueOf(e.getMessage()));
        }
        return m;
    }

    static String one(Map<String, List<String>> m, String k) {
        List<String> l = m.get(k);
        return l == null || l.isEmpty() ? "" : l.get(0);
    }

    static float f(String s) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return 0; }
    }

    static int level(float pct) { return pct < 60 ? GREEN : pct < 85 ? YELLOW : RED; }

    LinearLayout row(View a, View b) {
        LinearLayout l = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(8, 12, 8, 4);
        l.addView(a, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -1, 1);
        lp2.setMargins(8, 12, 8, 4);
        l.addView(b, lp2);
        return l;
    }

    TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(22);
        btn.setTextColor(Color.WHITE);
        btn.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(0xFF2E7D32));
        bg.addState(new int[]{android.R.attr.state_pressed}, round(0xFF1B5E20));
        bg.addState(new int[]{}, round(0xFF263238));
        btn.setBackground(bg);
        btn.setOnClickListener(l);
        btn.setPadding(36, 0, 36, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, 96);
        lp.setMargins(16, 0, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    static GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(20);
        return d;
    }

    /** A card: title, current value, detail line and the history since the screen was opened. */
    static class Chart extends View {
        final String title, unit;
        final float min, max;
        final float[] data = new float[KEEP];
        int n, color = GREEN;
        String value = "…", detail = "";
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path line = new Path(), fill = new Path();

        Chart(Context c, String title, String unit, float min, float max) {
            super(c);
            this.title = title; this.unit = unit; this.min = min; this.max = max;
        }

        void add(float v, String value, int color, String detail) {
            if (n == KEEP) System.arraycopy(data, 1, data, 0, --n);
            data[n++] = v;
            this.value = value; this.color = color; this.detail = detail;
            invalidate();
        }

        String span() {
            long s = (long) n * PERIOD_MS / 1000;
            return s < 60 ? s + " с" : s / 60 + " мин";
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(CARD);
            c.drawRoundRect(0, 0, w, h, 20, 20, p);
            p.setColor(0xFFB0BEC5);
            p.setTextSize(26);
            c.drawText(title, 24, 40, p);
            p.setColor(color);
            p.setTextSize(44);
            p.setFakeBoldText(true);
            float vw = p.measureText(value);
            c.drawText(value, w - vw - 24, 50, p);
            p.setFakeBoldText(false);
            p.setColor(0xFF78909C);
            p.setTextSize(20);
            c.drawText(detail, 24, 74, p);
            // graph area
            float gl = 70, gt = 92, gr = w - 20, gb = h - 18;
            p.setTextSize(16);
            for (int i = 0; i <= 4; i++) {
                float y = gb - (gb - gt) * i / 4;
                p.setColor(0xFF263238);
                c.drawLine(gl, y, gr, y, p);
                p.setColor(0xFF607D8B);
                c.drawText(Math.round(min + (max - min) * i / 4) + unit, 16, y + 6, p);
            }
            if (n < 2) return;
            line.reset(); fill.reset();
            for (int i = 0; i < n; i++) {
                float x = gl + (gr - gl) * i / (n - 1);
                float y = gb - (gb - gt) * (Math.max(min, Math.min(max, data[i])) - min) / (max - min);
                if (i == 0) { line.moveTo(x, y); fill.moveTo(x, gb); }
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            fill.lineTo(gr, gb);
            fill.close();
            p.setColor((color & 0x00FFFFFF) | 0x33000000);
            c.drawPath(fill, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(color);
            c.drawPath(line, p);
        }
    }
}
