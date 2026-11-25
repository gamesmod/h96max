package org.tvbox.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Файлы (root)": the home-screen file manager of Panadonik. It browses and changes files as root through
 * tvboxd (Root.java), while the Android UI remains sandboxed, and opens files in other apps through
 * Provider. Remote: OK opens, MENU (or long OK) shows actions, BACK goes up a folder.
 */
public class MainActivity extends Activity {
    static final String HOME = Environment.getExternalStorageDirectory().getAbsolutePath();

    private final List<Root.Entry> entries = new ArrayList<Root.Entry>();
    private final SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
    private TextView title, pathView, status;
    private ListView list;
    private Adapter adapter;
    private String path = HOME;
    private boolean root, picking;
    private String clipPath;
    private boolean clipMove;
    private final List<String> focusStack = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF101418);
        box.setPadding(dp(32), dp(20), dp(32), dp(12));
        title = text(30, Color.WHITE);
        box.addView(title);
        pathView = text(20, 0xFF90CAF9);
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        pathView.setPadding(0, dp(6), 0, dp(10));
        box.addView(pathView);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(0xFF263238));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setItemsCanFocus(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { activate(pos); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { actions(pos); return true; }
        });
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        status = text(18, 0xFFB0BEC5);
        status.setPadding(0, dp(8), 0, 0);
        box.addView(status);
        setContentView(box);

        Intent i = getIntent();
        picking = Intent.ACTION_GET_CONTENT.equals(i.getAction()) || Intent.ACTION_PICK.equals(i.getAction());
        Uri data = i.getData();
        if (Intent.ACTION_VIEW.equals(i.getAction()) && data != null && "file".equals(data.getScheme()) && data.getPath() != null) path = data.getPath();
        title.setText(picking ? "Файлы · выбор файла" : "Файлы");
        new Thread(new Runnable() {
            public void run() {
                root = Root.available();
                runOnUiThread(new Runnable() { public void run() { load(path, null); } });
            }
        }).start();
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ------------------------------------------------------------------ listing

    /** List dir on a worker thread; focus the entry named focus (the folder we came from) when it is there. */
    private void load(final String dir, final String focus) {
        status.setText("Загрузка…");
        new Thread(new Runnable() {
            public void run() {
                final Root.Result r = root ? Root.list(dir) : Root.listPlain(dir);
                Collections.sort(r.entries, new Comparator<Root.Entry>() {
                    public int compare(Root.Entry a, Root.Entry b) {
                        if (a.dir != b.dir) return a.dir ? -1 : 1;
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!r.ok && !dir.equals(path)) { toast("Не открыть: " + r.message); status.setText(r.message); return; }
                        path = dir;
                        entries.clear();
                        if (!path.equals("/")) entries.add(new Root.Entry("..", true, false, 0, 0));
                        entries.addAll(r.entries);
                        adapter.notifyDataSetChanged();
                        title.setText((picking ? "Файлы · выбор файла" : "Файлы") + (root ? "  ·  root" : "  ·  без root"));
                        pathView.setText(path);
                        status.setText(r.ok ? r.entries.size() + " объектов   ·   OK — открыть, MENU — действия, BACK — назад" : "Ошибка: " + r.message);
                        int sel = 0;
                        if (focus != null) for (int k = 0; k < entries.size(); k++) if (entries.get(k).name.equals(focus)) sel = k;
                        list.setSelection(sel);
                        list.requestFocus();
                    }
                });
            }
        }).start();
    }

    private String child(String name) { return path.equals("/") ? "/" + name : path + "/" + name; }

    private static String parent(String p) {
        if (p.equals("/")) return null;
        String q = p.substring(0, p.lastIndexOf('/'));
        return q.isEmpty() ? "/" : q;
    }

    private void up() {
        String p = parent(path);
        if (p == null) { finish(); return; }
        load(p, path.substring(path.lastIndexOf('/') + 1));
    }

    private void activate(int pos) {
        if (pos < 0 || pos >= entries.size()) return;
        Root.Entry e = entries.get(pos);
        if (e.name.equals("..")) { up(); return; }
        if (e.dir) { load(child(e.name), null); return; }
        if (picking) { pick(child(e.name)); return; }
        open(child(e.name), false);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_BACK) { up(); return true; }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) { actions(list.getSelectedItemPosition()); return true; }
        return super.onKeyDown(code, ev);
    }

    // ------------------------------------------------------------------ opening

    private void open(String file, boolean chooser) {
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(Provider.uriFor(file), Provider.mime(file));
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(chooser ? Intent.createChooser(v, "Открыть с помощью") : v);
        } catch (ActivityNotFoundException ex) {
            if (!chooser) open(file, true); else toast("Нет приложения для этого файла");
        }
    }

    private void pick(String file) {
        Intent r = new Intent();
        r.setDataAndType(Provider.uriFor(file), Provider.mime(file));
        r.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, r);
        finish();
    }

    // ------------------------------------------------------------------ actions

    private void actions(int pos) {
        final Root.Entry e = pos >= 0 && pos < entries.size() && !entries.get(pos).name.equals("..") ? entries.get(pos) : null;
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        if (e != null) {
            final String p = child(e.name);
            if (!e.dir) add(labels, acts, "Открыть с помощью…", new Runnable() { public void run() { open(p, true); } });
            if (root) {
                add(labels, acts, "Копировать", new Runnable() { public void run() { clipPath = p; clipMove = false; toast("Скопировано: " + e.name); } });
                add(labels, acts, "Вырезать", new Runnable() { public void run() { clipPath = p; clipMove = true; toast("Вырезано: " + e.name); } });
                add(labels, acts, "Переименовать", new Runnable() { public void run() { rename(p, e.name); } });
                add(labels, acts, "Удалить", new Runnable() { public void run() { confirmDelete(p, e.name); } });
            }
        }
        if (root && clipPath != null) add(labels, acts, (clipMove ? "Переместить сюда: " : "Вставить сюда: ") + new File(clipPath).getName(), new Runnable() { public void run() { paste(); } });
        if (root) add(labels, acts, "Новая папка", new Runnable() { public void run() { mkdir(); } });
        add(labels, acts, "Перейти: внутренняя память", new Runnable() { public void run() { load(HOME, null); } });
        add(labels, acts, "Перейти: накопители (USB, SD)", new Runnable() { public void run() { load(root ? "/mnt/media_rw" : "/storage", null); } });
        if (root) add(labels, acts, "Перейти: корень системы /", new Runnable() { public void run() { load("/", null); } });
        add(labels, acts, "Обновить", new Runnable() { public void run() { load(path, null); } });
        new AlertDialog.Builder(this)
                .setTitle(e != null ? e.name : path)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { acts.get(which).run(); }
                })
                .show();
    }

    private static void add(List<String> labels, List<Runnable> acts, String label, Runnable r) { labels.add(label); acts.add(r); }

    private void command(final String cmd, final String focus) {
        status.setText("Выполняется…");
        new Thread(new Runnable() {
            public void run() {
                final Root.Result r = Root.run(cmd);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast((r.ok ? "" : "Ошибка: ") + r.message);
                        load(path, focus);
                    }
                });
            }
        }).start();
    }

    private void paste() {
        String src = clipPath;
        boolean move = clipMove;
        if (move) clipPath = null;
        command((move ? "FS_MOVE " : "FS_COPY ") + src + "\t" + path, new File(src).getName());
    }

    private void confirmDelete(final String p, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить «" + name + "»?")
                .setMessage("Удаление выполняется с правами root, папки удаляются вместе с содержимым.")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { command("FS_DELETE " + p, null); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void rename(final String p, String name) {
        askName("Новое имя", name, new Named() {
            public void named(String n) { command("FS_RENAME " + p + "\t" + child(n), n); }
        });
    }

    private void mkdir() {
        askName("Новая папка", "", new Named() {
            public void named(String n) { command("FS_MKDIR " + child(n), n); }
        });
    }

    private interface Named { void named(String name); }

    private void askName(String t, String initial, final Named cb) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText(initial);
        in.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(t)
                .setView(in)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = in.getText().toString().trim();
                        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.contains("/") || n.contains("\t")) { toast("Недопустимое имя"); return; }
                        cb.named(n);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ rows

    private static String size(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f КБ", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f МБ", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f ГБ", b / 1073741824.0);
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() { return entries.size(); }
        public Object getItem(int i) { return entries.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            TextView name, info;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_selected}, round(0xFF2E7D32));
                bg.addState(new int[]{android.R.attr.state_pressed}, round(0xFF1B5E20));
                bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
                row.setBackground(bg);
                name = text(22, Color.WHITE);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                info = text(18, 0xFFB0BEC5);
                info.setGravity(Gravity.END);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                name = (TextView) row.getChildAt(0);
                info = (TextView) row.getChildAt(1);
            }
            Root.Entry e = entries.get(i);
            if (e.name.equals("..")) { name.setText("⬑  .."); info.setText("назад"); return row; }
            name.setText((e.dir ? "▣  " : "▤  ") + e.name + (e.link ? "  →" : ""));
            String when = e.mtime > 0 ? date.format(new Date(e.mtime)) : "";
            info.setText(e.dir ? when : size(e.size) + "   " + when);
            return row;
        }
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        return d;
    }
}
