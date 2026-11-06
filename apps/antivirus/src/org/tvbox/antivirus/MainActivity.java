package org.tvbox.antivirus;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/** "Антивирус": Hypatia status, manual scan, realtime protection switch and signature updates (via tvboxd). */
public class MainActivity extends Activity {
    static final int BG = 0xFF101418, PANEL = 0xFF1B2128, ACCENT = 0xFF66BB6A, TEXT = 0xFFE8EAED, DIM = 0xFF9AA0A6;
    static final String HYPATIA = "org.maintainteam.hypatia";

    TextView status, realtimeLabel;
    LinearLayout menu;
    final Map<String, String> st = new HashMap<>();
    volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(36), dp(48), dp(36));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon);
        left.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView title = text("Антивирус", 26, TEXT, true);
        title.setPadding(0, dp(16), 0, dp(12));
        left.addView(title);
        status = text("Получение состояния…", 16, DIM, false);
        status.setLineSpacing(0, 1.25f);
        left.addView(status);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(32), 0, 0, 0);
        item("Проверить сейчас", "Запустить проверку в Hypatia (базы подгружаются автоматически)", new Runnable() { public void run() { scanNow(); } });
        realtimeLabel = item("Защита в реальном времени", "Проверять каждый новый файл (загрузки, торренты)", new Runnable() { public void run() { toggleRealtime(); } });
        item("Обновить базы", "Скачать свежие подписанные базы сигнатур", new Runnable() { public void run() { updateDb(); } });
        item("Настройки Hypatia", "Дополнительные базы, исключения, журнал проверок", new Runnable() { public void run() { openHypatia(); } });
        root.addView(menu, new LinearLayout.LayoutParams(0, -1, 1.3f));

        setContentView(root);
        menu.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    void refresh() {
        call("AV_STATUS", new Done() { public void done(boolean ok, String msg) { show(ok ? null : msg); } });
    }

    void show(String error) {
        if (error != null) { status.setText("Служба tvboxd недоступна: " + error); return; }
        if (!"1".equals(st.get("AV_INSTALLED"))) { status.setText("Hypatia не установлена."); return; }
        boolean rt = "1".equals(st.get("AV_REALTIME"));
        status.setText("Сканер: Hypatia (открытый, бесплатный)\n"
            + "Базы сигнатур: " + st.get("AV_DB") + ("нет".equals(st.get("AV_DB")) ? "" : " (" + st.get("AV_DB_MB") + " МБ)") + "\n"
            + "Защита в реальном времени: " + (rt ? "включена" : "выключена") + "\n\n"
            + "Антивирус работает только по запросу: проверка, обновление баз и защита включаются здесь.");
        realtimeLabel.setText("Защита в реальном времени: " + (rt ? "вкл" : "выкл"));
    }

    void toggleRealtime() {
        final boolean on = !"1".equals(st.get("AV_REALTIME"));
        call("AV_REALTIME " + (on ? "on" : "off"), new Done() {
            public void done(boolean ok, String msg) { show(ok ? null : msg); toast(msg); }
        });
    }

    void updateDb() {
        status.setText("Обновление баз…");
        call("HYPATIA_UPDATE", new Done() { public void done(boolean ok, String msg) { toast((ok ? "" : "Ошибка: ") + msg); refresh(); } });
    }

    /** Fetch the databases first if there are none yet, then let tvboxd start the scan in Hypatia (AV_SCAN). */
    void scanNow() {
        if (!"нет".equals(st.get("AV_DB"))) { startScan(); return; }
        status.setText("Загрузка баз сигнатур перед первой проверкой…");
        call("HYPATIA_UPDATE", new Done() {
            public void done(boolean ok, String msg) {
                if (!ok) { toast("Ошибка: " + msg); refresh(); return; }
                startScan();
            }
        });
    }

    void startScan() {
        status.setText("Запуск проверки в Hypatia…");
        call("AV_SCAN", new Done() {
            public void done(boolean ok, String msg) { if (!ok) { toast("Ошибка: " + msg); openHypatia(); } }
        });
    }

    void openHypatia() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(HYPATIA, "us.spotco.malwarescanner.MainActivity"));
            startActivity(i);
        } catch (Exception e) {
            toast("Hypatia не установлена");
        }
    }

    // ------------------------------------------------------------------ UI pieces

    TextView item(String name, String hint, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(0xFF1E3A26, ACCENT));
        bg.addState(new int[]{}, rounded(PANEL, PANEL));
        row.setBackground(bg);
        TextView t = text(name, 20, TEXT, true);
        row.addView(t);
        row.addView(text(hint, 14, DIM, false));
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!busy) action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        menu.addView(row, lp);
        return t;
    }

    GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(2), stroke);
        return g;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ tvboxd

    interface Done { void done(boolean ok, String msg); }

    /** One command per connection: KEY value lines go into st, DONE ok|fail msg ends it (UI thread callback). */
    void call(final String cmd, final Done cb) {
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "нет ответа";
                try {
                    LocalSocket s = new LocalSocket();
                    s.connect(new LocalSocketAddress("tvboxd"));
                    PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                    w.println(cmd);
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    for (String l; (l = r.readLine()) != null; ) {
                        int sp = l.indexOf(' ');
                        String k = sp > 0 ? l.substring(0, sp) : l, v = sp > 0 ? l.substring(sp + 1) : "";
                        if (k.equals("DONE")) { ok = v.startsWith("ok"); msg = v.indexOf(' ') > 0 ? v.substring(v.indexOf(' ') + 1) : v; break; }
                        synchronized (st) { st.put(k, v); }
                    }
                    s.close();
                } catch (Exception e) {
                    msg = e.getMessage();
                }
                final boolean fOk = ok;
                final String fMsg = msg;
                runOnUiThread(new Runnable() { public void run() { busy = false; cb.done(fOk, fMsg); } });
            }
        }).start();
    }
}
