package org.tvbox.clean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Производительность": temperature, CPU, GPU and RAM live with a history graph, top processes, and one button that
 * frees resources. Metrics come from tvboxd (PERF_NOW, root: apps cannot read /proc/stat) every 2 s, only while this
 * screen is open; the history lives in memory for as long as the screen is open (up to 1 h).
 */
public class MainActivity extends Activity {
    static final int PERIOD_MS = 2000, KEEP = 3600 * 1000 / PERIOD_MS;
    static final int BG = 0xFF101418, CARD = 0xFF1A2026, GREEN = 0xFF66BB6A, YELLOW = 0xFFFFCA28, RED = 0xFFEF5350;

    final Handler ui = new Handler();
    Chart temp, cpu, gpu, mem;
    TextView uptime, procs;
    TextView zramInfo;
    volatile boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(48, 32, 48, 32);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Производительность", 36, Color.WHITE);
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

        LinearLayout compression = new LinearLayout(this);
        compression.setGravity(Gravity.CENTER_VERTICAL);
        zramInfo = text("Сжатие памяти: загрузка…", 20, 0xFFB0BEC5);
        compression.addView(zramInfo, new LinearLayout.LayoutParams(0, -2, 1));
        compression.addView(button("Сжатие памяти…", new View.OnClickListener() {
            public void onClick(View v) { configureZram(); }
        }));
        root.addView(compression);

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
        if (m.containsKey("ZRAM_ACTIVE_MB")) {
            String active = one(m, "ZRAM_ACTIVE_MB"), configured = one(m, "ZRAM_CONFIG_MB");
            zramInfo.setText("zRAM: " + (active.equals("0") ? "выключено" : active + " МБ · в ОЗУ " + one(m, "ZRAM_RAM_MB") + " МБ")
                    + (active.equals(configured) ? "" : " · после перезагрузки: " + configured + " МБ"));
        }
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

    void configureZram() {
        new Thread(new Runnable() {
            public void run() {
                final Map<String, List<String>> m = call("PERF_ZRAM");
                ui.post(new Runnable() { public void run() {
                    if (isFinishing()) return;
                    if (m.containsKey("ERROR") || !m.containsKey("ZRAM_MAX_MB")) {
                        Toast.makeText(MainActivity.this, "Настройка недоступна: " + one(m, "ERROR"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    final int max = (int) f(one(m, "ZRAM_MAX_MB"));
                    LinearLayout content = new LinearLayout(MainActivity.this);
                    content.setOrientation(LinearLayout.VERTICAL);
                    content.setPadding(40, 24, 40, 24);
                    content.addView(text("Сжатая подкачка в ОЗУ (LZ4). 0 — выключено.\n"
                            + "Размер задаёт предел данных, а не резервирует всю память.\n"
                            + "Сжатие расходует процессор. Применение после перезагрузки.", 20, 0xFFB0BEC5));
                    final TextView value = text("", 28, Color.WHITE);
                    content.addView(value);
                    final SeekBar size = new SeekBar(MainActivity.this);
                    size.setMax(max);
                    size.setKeyProgressIncrement(128);
                    size.setProgress(Math.min(max, (int) f(one(m, "ZRAM_CONFIG_MB"))));
                    value.setText(size.getProgress() + " / " + max + " МБ");
                    size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar bar, int n, boolean user) { value.setText(n == 0 ? "Выключено · 0 / " + max + " МБ" : n + " / " + max + " МБ"); }
                        public void onStartTrackingTouch(SeekBar bar) { }
                        public void onStopTrackingTouch(SeekBar bar) { }
                    });
                    content.addView(size);
                    AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Сжатие памяти · объём zRAM").setView(content)
                            .setNegativeButton("Отмена", null)
                            .setNeutralButton("Выключить", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) { saveZram(0); }
                            })
                            .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) { saveZram(size.getProgress()); }
                            }).create();
                    dialog.show();
                    size.requestFocus();
                } });
            }
        }).start();
    }

    void saveZram(final int mb) {
        new Thread(new Runnable() { public void run() {
            final Map<String, List<String>> m = call("PERF_ZRAM_SET " + mb);
            ui.post(new Runnable() { public void run() {
                Toast.makeText(MainActivity.this, m.containsKey("ERROR") ? "Не сохранено: " + one(m, "ERROR")
                        : "Сохранено: " + mb + " МБ. Применится после перезагрузки.", Toast.LENGTH_LONG).show();
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
