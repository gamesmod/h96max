package org.tvbox.installer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "Установка": install the image from the SD card to the internal eMMC and manage it, via the tvboxd root helper. */
public class MainActivity extends Activity {
    static final int BG = 0xFF101418, PANEL = 0xFF1B2128, ACCENT = 0xFF4FC3F7, DANGER = 0xFFE53935, TEXT = 0xFFE8EAED, DIM = 0xFF9AA0A6;

    TextView status, title, logView;
    ProgressBar bar;
    LinearLayout menu, work;
    ScrollView logScroll;
    volatile boolean running;
    boolean sdMode;
    final Map<String, String> st = new LinkedHashMap<>();

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
        title = text("Установка и обновление", 26, TEXT, true);
        title.setPadding(0, dp(16), 0, dp(12));
        left.addView(title);
        status = text("Получение состояния…", 16, DIM, false);
        status.setLineSpacing(0, 1.25f);
        left.addView(status);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(32), 0, 0, 0);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        right.addView(menu);

        work = new LinearLayout(this);
        work.setOrientation(LinearLayout.VERTICAL);
        work.setVisibility(View.GONE);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        work.addView(bar, new LinearLayout.LayoutParams(-1, dp(18)));
        logScroll = new ScrollView(this);
        logView = text("", 14, TEXT, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = dp(16);
        work.addView(logScroll, lp);
        right.addView(work, new LinearLayout.LayoutParams(-1, -1));
        root.addView(right, new LinearLayout.LayoutParams(0, -1, 1.3f));

        setContentView(root);
        refresh();
    }

    /** SD boot: install/backup/restore of the eMMC plus updates; eMMC boot: updates only. */
    void buildMenu(boolean sd) {
        menu.removeAllViews();
        item("Обновить состояние", "Откуда загружена система, что на внутренней памяти", false, new Runnable() { public void run() { refresh(); } });
        item("Проверить обновления", "Флешка, SD-карта, адреса в сети", false, new Runnable() { public void run() { checkUpdates(); } });
        if (sd) {
            item("Установить на внутреннюю память", "Записать систему с SD-карты на eMMC и сверить", true, new Runnable() { public void run() {
                confirmInstall(); } });
            item("Проверить внутреннюю память", "Сверить eMMC с образом на SD-карте", false, new Runnable() { public void run() { op("VERIFY", "Проверка"); } });
            item("Резервная копия eMMC", "Сохранить всю внутреннюю память на SD-карту (~6 ГБ)", false, new Runnable() { public void run() { op("BACKUP", "Резервная копия"); } });
            item("Восстановить из резервной копии", "Вернуть внутреннюю память к сохранённому состоянию", true, new Runnable() { public void run() {
                confirm("Восстановить внутреннюю память?",
                    "Внутренняя память будет полностью перезаписана резервной копией с SD-карты (" + val("BACKUP") + ").", "RESTORE"); } });
        }
        item("Источники обновлений", "Адреса в локальной сети и интернете", false, new Runnable() { public void run() { sources(); } });
        item("Журнал последней операции", "Что делалось и чем закончилось", false, new Runnable() { public void run() { op("LOG", "Журнал"); } });
        menu.getChildAt(0).requestFocus();
    }

    // ------------------------------------------------------------------ updates

    void checkUpdates() {
        final List<String[]> found = new ArrayList<>();
        final Map<String, String> notes = new LinkedHashMap<>();
        final StringBuilder unreachable = new StringBuilder();
        status.setText("Поиск обновлений…");
        call("UPDATE_CHECK", new Sink() {
            public void line(String k, String v) {
                if (k.equals("UPD")) found.add(v.split("\t"));
                else if (k.equals("NOTES")) { String[] p = v.split("\t", 2); if (p.length == 2) notes.put(p[0], p[1]); }
                else if (k.equals("UNREACHABLE")) unreachable.append("• ").append(v.replace("\t", ": ")).append("\n");
            }
            public void end(boolean ok, String msg) {
                refresh();
                if (found.isEmpty()) {
                    alert("Обновления", "Обновлений не найдено.\n\nПоложите tvbox-update-ВЕРСИЯ.zip в корень флешки или SD-карты, либо добавьте адрес в «Источники обновлений»."
                        + (unreachable.length() > 0 ? "\n\nНедоступны:\n" + unreachable : ""));
                    return;
                }
                final String[] labels = new String[found.size()];
                for (int i = 0; i < labels.length; i++) {
                    String[] u = found.get(i);
                    labels[i] = "Выпуск " + u[1] + ("new".equals(u[4]) ? " (новее)" : " (не новее текущего " + val("RELEASE") + ")") + " — " + u[2] + ", " + u[3];
                }
                new AlertDialog.Builder(MainActivity.this).setTitle("Найденные обновления (текущий выпуск " + val("RELEASE") + ")")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { chooseMode(found.get(w), notes.get(found.get(w)[0])); }
                    }).setNegativeButton("Закрыть", null).show();
            }
        });
    }

    void chooseMode(final String[] u, String note) {
        new AlertDialog.Builder(this).setTitle("Выпуск " + u[1])
            .setMessage((note != null ? note + "\n\n" : "") + "Источник: " + u[2] + ", " + u[3] + "\n\n"
                + "Приставка перезагрузится, recovery проверит подпись пакета и установит его, затем система запустится снова.")
            .setNegativeButton("Отмена", null)
            .setNeutralButton("С очисткой данных", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    confirmCmd("Обновить с очисткой?", "Будут удалены все приложения, настройки, торренты и файлы пользователя. Система запустится как новая.",
                        "UPDATE_INSTALL " + u[0] + " wipe", "Обновление с очисткой");
                }
            })
            // "Сохранить данные" is switched off for now (user, 2026-10-28): OTA through recovery is in the backlog
            .show();
    }

    void sources() {
        final List<String> urls = new ArrayList<>();
        call("URLS", new Sink() {
            public void line(String k, String v) { if (k.equals("URL")) urls.add(v); }
            public void end(boolean ok, String msg) {
                final String[] items = new String[urls.size() + 1];
                for (int i = 0; i < urls.size(); i++) items[i] = urls.get(i) + "  (удалить)";
                items[urls.size()] = "+ Добавить адрес…";
                new AlertDialog.Builder(MainActivity.this).setTitle("Источники обновлений")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (w == urls.size()) addSource();
                            else call("URL_DEL " + urls.get(w), new Sink() {
                                public void line(String k, String v) { }
                                public void end(boolean ok, String msg) { sources(); }
                            });
                        }
                    }).setNegativeButton("Закрыть", null).show();
            }
        });
    }

    void addSource() {
        final EditText e = new EditText(this);
        e.setText("http://");
        e.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Адрес папки с обновлениями")
            .setMessage("В папке должен лежать latest.txt (его создаёт сборка выпуска).")
            .setView(e).setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    call("URL_ADD " + e.getText().toString().trim(), new Sink() {
                        public void line(String k, String v) { }
                        public void end(boolean ok, String msg) { sources(); }
                    });
                }
            }).show();
    }

    void alert(String head, String body) {
        new AlertDialog.Builder(this).setTitle(head).setMessage(body).setPositiveButton("OK", null).show();
    }

    void confirmCmd(String head, String body, final String cmd, final String name) {
        new AlertDialog.Builder(this).setTitle(head)
            .setMessage(body + "\n\nНе выключайте приставку до окончания.")
            .setNegativeButton("Нет", null)
            .setPositiveButton("Да, обновить", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { op(cmd, name); }
            }).show();
    }

    // ------------------------------------------------------------------ UI pieces

    void item(String name, String hint, boolean danger, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(danger ? 0xFF5A1F1F : 0xFF1E3A4A, danger ? DANGER : ACCENT));
        bg.addState(new int[]{}, rounded(PANEL, PANEL));
        row.setBackground(bg);
        row.addView(text(name, 20, danger ? 0xFFFF8A80 : TEXT, true));
        row.addView(text(hint, 14, DIM, false));
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!running) action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        menu.addView(row, lp);
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

    String val(String k) { String v = st.get(k); return v == null ? "?" : v; }

    void confirm(String head, String body, final String cmd) {
        new AlertDialog.Builder(this)
            .setTitle(head).setMessage(body)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Точно?")
                        .setMessage("Это действие нельзя отменить. Не выключайте приставку до окончания.")
                        .setNegativeButton("Нет", null)
                        .setPositiveButton("Да, выполнить", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w2) { op(cmd, head); }
                        }).show();
                }
            }).show();
    }

    /** Подтверждает чистую установку и по выбору сохраняет Wi‑Fi-сети на SD до первого запуска eMMC. */
    void confirmInstall() {
        final CheckBox keepWifi = new CheckBox(this);
        keepWifi.setText("Сохранить настройки Wi‑Fi на SD-карте");
        keepWifi.setTextColor(TEXT);
        keepWifi.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView message = text("Всё содержимое внутренней памяти (сейчас: " + val("EMMC_CONTENT")
            + ") будет заменено системой с SD-карты.\n\n"
            + "При выборе сохранения сеть будет перенесена с SD после первого запуска системы с eMMC."
            + " Оставьте карту вставленной до завершения первого запуска.\n\n"
            + "SD-карта останется загрузочной. Чтобы запустить приставку с внутренней памяти — выключите её и выньте карту.", 16, TEXT, false);
        content.addView(message);
        content.addView(keepWifi);
        new AlertDialog.Builder(this).setTitle("Установить на внутреннюю память?").setView(content)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    final String cmd = keepWifi.isChecked() ? "INSTALL_WIFI" : "INSTALL";
                    new AlertDialog.Builder(MainActivity.this).setTitle("Точно?")
                        .setMessage("Внутренняя память будет полностью перезаписана. Не выключайте приставку до окончания.")
                        .setNegativeButton("Нет", null)
                        .setPositiveButton("Да, выполнить", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w2) { op(cmd, "Установка на внутреннюю память"); }
                        }).show();
                }
            }).show();
    }

    // ------------------------------------------------------------------ talking to tvboxd

    void refresh() {
        call("STATUS", new Sink() {
            public void line(String k, String v) { st.put(k, v); }
            public void end(boolean ok, String msg) {
                if (!ok) { status.setText("Служба tvboxd недоступна: " + msg); return; }
                boolean sd = "sd".equals(val("BOOT"));
                if (menu.getChildCount() == 0 || sdMode != sd) { sdMode = sd; buildMenu(sd); }
                status.setText(
                    "Загрузка: " + ("sd".equals(val("BOOT")) ? "с SD-карты" : "с внутренней памяти") + "\n"
                    + "Выпуск системы: " + val("RELEASE") + "\n"
                    + (sd ? "Внутренняя память: " + val("EMMC_CONTENT") + "\n"
                        + "Образ для установки: " + val("IMAGE") + "\n"
                        + "Резервная копия на SD: " + val("BACKUP") + "\n" : "")
                    + "Свободно: " + val("SD_FREE_MB") + " МБ"
                    + ("-".equals(val("BUSY")) ? "" : "\nВыполняется: " + val("BUSY")));
            }
        });
    }

    void op(String cmd, String name) {
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        title.setText(name);
        logView.setText("");
        bar.setProgress(0);
        bar.setVisibility("LOG".equals(cmd) ? View.GONE : View.VISIBLE);
        menu.setVisibility(View.GONE);
        work.setVisibility(View.VISIBLE);
        call(cmd, new Sink() {
            public void line(String k, String v) {
                if (k.equals("PROGRESS")) {
                    int sp = v.indexOf(' ');
                    try { bar.setProgress(Integer.parseInt(sp > 0 ? v.substring(0, sp) : v)); } catch (Exception ignored) { }
                } else if (k.equals("LOG")) {
                    logView.append(v + "\n");
                    logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); } });
                }
            }
            public void end(boolean ok, String msg) {
                running = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (!"LOG".equals(cmd)) bar.setProgress(ok ? 100 : bar.getProgress());
                logView.append("\n" + (ok ? "✓ " : "✗ ") + msg + "\n\nНажмите «Назад», чтобы вернуться в меню.");
                logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); } });
                refresh();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (work.getVisibility() == View.VISIBLE) {
            if (running) {
                new AlertDialog.Builder(this).setTitle("Операция выполняется")
                    .setMessage("Она продолжится в фоне. Не выключайте приставку. Ход можно посмотреть в «Журнал последней операции».")
                    .setPositiveButton("Понятно", null).show();
                return;
            }
            work.setVisibility(View.GONE);
            menu.setVisibility(View.VISIBLE);
            title.setText("Установка и обновление");
            if (menu.getChildCount() > 0) menu.getChildAt(0).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    interface Sink {
        void line(String key, String value);
        void end(boolean ok, String msg);
    }

    /** One command per connection; lines are delivered on the UI thread. */
    void call(final String cmd, final Sink sink) {
        new Thread(new Runnable() {
            public void run() {
                String endMsg = "нет ответа";
                boolean ok = false;
                try {
                    LocalSocket s = new LocalSocket();
                    s.connect(new LocalSocketAddress("tvboxd"));
                    PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                    w.println(cmd);
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    for (String l; (l = r.readLine()) != null; ) {
                        int sp = l.indexOf(' ');
                        final String k = sp > 0 ? l.substring(0, sp) : l, v = sp > 0 ? l.substring(sp + 1) : "";
                        if (k.equals("DONE")) {
                            ok = v.startsWith("ok");
                            endMsg = v.indexOf(' ') > 0 ? v.substring(v.indexOf(' ') + 1) : v;
                            break;
                        }
                        runOnUiThread(new Runnable() { public void run() { sink.line(k, v); } });
                    }
                    s.close();
                } catch (Exception e) {
                    endMsg = e.getMessage();
                }
                final boolean fOk = ok;
                final String fMsg = endMsg;
                runOnUiThread(new Runnable() { public void run() { sink.end(fOk, fMsg); } });
            }
        }).start();
    }
}
