package org.tvbox.iptv;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Channel list for an M3U playlist: groups -> channels (with logos) -> play in VLC.
 * The playlist comes with the image (/system/etc/tvbox/iptv.m3u, merged and checked by tools/iptv-merge.py);
 * the network playlist is only a fallback, cached in the files dir. Logos are cached in memory and in cacheDir.
 */
public class MainActivity extends Activity {
    static final String BUNDLED = "/system/etc/tvbox/iptv.m3u";
    static final String PLAYLIST = "https://ngrch.github.io/iptv/ru.m3u";
    static final String VLC = "org.videolan.vlc";

    ListView lv;
    final Map<String, List<String[]>> groups = new LinkedHashMap<>();
    List<String> groupNames = new ArrayList<>();
    String cur;          // open group, null = group list
    int groupPos;        // selection to restore when going back
    final LruCache<String, Bitmap> logos = new LruCache<>(200);
    final ExecutorService loader = Executors.newFixedThreadPool(4);
    final Handler indexUi = new Handler();
    ProgressDialog indexDialog;
    final Runnable indexPoll = new Runnable() {
        public void run() {
            android.content.SharedPreferences p = getSharedPreferences("iptv_index", MODE_PRIVATE);
            if (!p.getBoolean("running", false)) {
                if (!p.getBoolean("complete", false) && p.getString("error", "").isEmpty()) {
                    indexUi.postDelayed(this, 250);
                    return;
                }
                if (indexDialog != null && indexDialog.isShowing()) indexDialog.dismiss();
                int count = p.getInt("channels", 0);
                if (groups.isEmpty()) {
                    File bundled = new File(BUNDLED);
                    if (!loadCachedIndex(bundled) && bundled.length() > 0) parse(read(bundled));
                    showGroups();
                }
                if (count > 0 && !p.getBoolean("shown", false)) {
                    Toast.makeText(MainActivity.this, "Индексация завершена: " + count + " каналов, " +
                            p.getInt("groups", 0) + " категорий", Toast.LENGTH_LONG).show();
                    p.edit().putBoolean("shown", true).apply();
                } else if (count == 0 && !p.getString("error", "").isEmpty() && !p.getBoolean("shown", false)) {
                    Toast.makeText(MainActivity.this, "Индексация не удалась; открыл список без кэша", Toast.LENGTH_LONG).show();
                    p.edit().putBoolean("shown", true).apply();
                }
                return;
            }
            int total = p.getInt("total", 1), done = p.getInt("done", 0), left = p.getInt("left_seconds", 0);
            if (indexDialog != null) {
                indexDialog.setMax(Math.max(1, total));
                indexDialog.setProgress(done);
                indexDialog.setMessage("Индексирую каналы при запуске приставки…\n" + done + " / " + total +
                        " строк\nОсталось примерно " + left + " сек.");
            }
            indexUi.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lv = new ListView(this);
        lv.setPadding(48, 24, 48, 24);
        setContentView(lv);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (cur == null) {
                    groupPos = pos;
                    showChannels(groupNames.get(pos));
                } else {
                    setTitle("Открываю «" + groups.get(cur).get(pos)[1] + "» в VLC…");
                    Toast.makeText(MainActivity.this, "Запускается внешний плеер VLC. Назад — к списку каналов.", Toast.LENGTH_SHORT).show();
                    playGroup(groups.get(cur), pos);
                }
            }
        });
        File bundled = new File(BUNDLED);
        if (loadCachedIndex(bundled)) {
            showGroups();
            showBootIndexStatus();
            return;
        }
        ensureIndexService();
        showBootIndexStatus();
        android.content.SharedPreferences indexState = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (!indexState.getBoolean("complete", false)) {
            setTitle("Подготовка списка ТВ-каналов…");
            return;
        }
        if (bundled.length() > 0) {
            parse(read(bundled));
            showGroups();
            return;
        }
        File cache = new File(getFilesDir(), "playlist.m3u");
        if (cache.length() > 0) {
            parse(read(cache));
            showGroups();
        } else {
            setTitle("Загрузка…");
        }
        refresh(cache);
    }

    @SuppressWarnings("unchecked")
    boolean loadCachedIndex(File playlist) {
        android.content.SharedPreferences p = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (!p.getBoolean("complete", false) || p.getLong("size", -1) != playlist.length() ||
                p.getLong("modified", -1) != playlist.lastModified()) return false;
        File cache = new File(getFilesDir(), "iptv-index.ser");
        try {
            ObjectInputStream in = new ObjectInputStream(new FileInputStream(cache));
            Map<String, List<String[]>> saved = (Map<String, List<String[]>>) in.readObject();
            in.close();
            groups.clear();
            groups.putAll(saved);
            groupNames = new ArrayList<>(groups.keySet());
            return true;
        } catch (Exception ignored) {
            cache.delete();
            p.edit().putBoolean("complete", false).apply();
            return false;
        }
    }

    void showBootIndexStatus() {
        android.content.SharedPreferences p = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (p.getBoolean("running", false) || !p.getBoolean("complete", false)) {
            indexDialog = new ProgressDialog(this);
            indexDialog.setTitle("Подготовка ТВ-каналов");
            indexDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            indexDialog.setCancelable(false);
            indexDialog.setCanceledOnTouchOutside(false);
            indexDialog.show();
            indexPoll.run();
        } else if (p.getInt("channels", 0) > 0 && !p.getBoolean("shown", false)) {
            indexPoll.run();
        }
    }

    void ensureIndexService() {
        File playlist = new File(BUNDLED);
        android.content.SharedPreferences p = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (p.getBoolean("running", false)) return;
        if (p.getBoolean("complete", false) && p.getLong("size", -1) == playlist.length() &&
                p.getLong("modified", -1) == playlist.lastModified() &&
                new File(getFilesDir(), "iptv-index.ser").isFile()) return;
        Intent service = new Intent(this, IndexOnBootService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try { android.content.Context.class.getMethod("startForegroundService", Intent.class).invoke(this, service); }
            catch (Exception e) { startService(service); }
        } else startService(service);
    }

    static String m3uAttr(String line, String key) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(key) + "=\\\"([^\\\"]*)\\\"").matcher(line);
        return m.find() ? m.group(1) : "";
    }

    static String quality(String text) {
        Matcher m = Pattern.compile("(?i)(4k|uhd|fhd|hd|sd|\\d{3,4}p)").matcher(text);
        return m.find() ? m.group(1).toUpperCase() : "";
    }

    static String groupIcon(String group) {
        String s = group.toLowerCase();
        if (s.contains("спорт") || s.contains("sport")) return "⚽";
        if (s.contains("кино") || s.contains("фильм") || s.contains("movie")) return "🎬";
        if (s.contains("дет") || s.contains("kid")) return "🧸";
        if (s.contains("музык") || s.contains("music")) return "🎵";
        if (s.contains("новост") || s.contains("news")) return "📰";
        if (s.contains("познав") || s.contains("документ") || s.contains("science")) return "🔭";
        if (s.contains("взросл") || s.contains("adult") || s.contains("18+")) return "🔞";
        if (s.contains("радио") || s.contains("radio")) return "📻";
        if (s.contains("регион") || s.contains("regional")) return "📍";
        if (s.contains("страна") || s.contains("country")) return "🌍";
        return "📺";
    }

    void refresh(final File cache) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(PLAYLIST).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    final String text = new String(readAll(c.getInputStream()), "UTF-8");
                    if (!text.contains("#EXTINF")) return;
                    FileOutputStream o = new FileOutputStream(cache);
                    o.write(text.getBytes("UTF-8"));
                    o.close();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            boolean first = groups.isEmpty();
                            parse(text);
                            if (first || cur == null) showGroups();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (groups.isEmpty())
                                Toast.makeText(MainActivity.this, "Плейлист недоступен: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    void parse(String text) {
        groups.clear();
        String name = null, group = "Без группы", logo = "", channelId = "", tvgName = "";
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 ? line.substring(comma + 1).trim() : "?";
                group = m3uAttr(line, "group-title");
                if (group.length() == 0) group = "Без группы";
                logo = m3uAttr(line, "tvg-logo");
                channelId = m3uAttr(line, "tvg-id");
                tvgName = m3uAttr(line, "tvg-name");
            } else if (name != null && line.length() > 0 && !line.startsWith("#")) {
                List<String[]> l = groups.get(group);
                if (l == null) groups.put(group, l = new ArrayList<>());
                // Сохраняем краткие метаданные отдельно, чтобы показать их в карточке канала.
                l.add(new String[]{line, name, logo, channelId, tvgName, group, quality(name + " " + tvgName)});
                name = null;
            }
        }
        groupNames = new ArrayList<>(groups.keySet());
    }

    void showGroups() {
        cur = null;
        setTitle("ТВ-каналы");
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return groupNames.size(); }
            public Object getItem(int i) { return groupNames.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup parent) {
                LinearLayout row = (LinearLayout) v;
                if (row == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(16, 12, 16, 12);
                    TextView icon = new TextView(MainActivity.this);
                    icon.setTextSize(30);
                    icon.setGravity(Gravity.CENTER);
                    row.addView(icon, new LinearLayout.LayoutParams(64, 64));
                    LinearLayout text = new LinearLayout(MainActivity.this);
                    text.setOrientation(LinearLayout.VERTICAL);
                    text.setPadding(20, 0, 0, 0);
                    TextView title = new TextView(MainActivity.this);
                    title.setTextSize(22);
                    title.setTextColor(Color.WHITE);
                    text.addView(title);
                    TextView count = new TextView(MainActivity.this);
                    count.setTextSize(15);
                    count.setTextColor(0xffb8c5d6);
                    text.addView(count);
                    row.addView(text);
                }
                String group = groupNames.get(i);
                ((TextView) row.getChildAt(0)).setText(groupIcon(group));
                LinearLayout text = (LinearLayout) row.getChildAt(1);
                ((TextView) text.getChildAt(0)).setText(group);
                ((TextView) text.getChildAt(1)).setText(groups.get(group).size() + " каналов  •  OK — открыть список");
                return row;
            }
        });
        focus(groupPos);
    }

    void showChannels(String g) {
        cur = g;
        setTitle(g);
        List<String> items = new ArrayList<>();
        final List<String[]> chans = groups.get(g);
        lv.setAdapter(new BaseAdapter() {
            public int getCount() { return chans.size(); }
            public Object getItem(int i) { return chans.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup parent) { return channelRow(chans.get(i), v); }
        });
        focus(0);
    }

    View channelRow(String[] ch, View v) {
        LinearLayout row = (LinearLayout) v;
        if (row == null) {
            row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(16, 10, 16, 10);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(iv, new LinearLayout.LayoutParams(120, 68));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(28, 0, 0, 0);
            TextView tv = new TextView(this);
            tv.setTextSize(21);
            tv.setTextColor(Color.WHITE);
            details.addView(tv);
            TextView sub = new TextView(this);
            sub.setTextSize(14);
            sub.setTextColor(0xffb8c5d6);
            details.addView(sub);
            row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        ImageView iv = (ImageView) row.getChildAt(0);
        LinearLayout details = (LinearLayout) row.getChildAt(1);
        ((TextView) details.getChildAt(0)).setText(ch[1]);
        String info = ch[5] + "  •  " + (ch[6].length() > 0 ? ch[6] : "прямой эфир");
        if (ch[3].length() > 0) info += "  •  EPG: " + ch[3];
        ((TextView) details.getChildAt(1)).setText(info + "  •  OK — VLC");
        iv.setTag(ch[2]);
        Bitmap b = logos.get(ch[2]);
        iv.setImageBitmap(b);
        if (b == null && ch[2].startsWith("http")) loadLogo(ch[2], iv);
        return row;
    }

    void loadLogo(final String url, final ImageView iv) {
        loader.execute(new Runnable() {
            public void run() {
                File f = new File(getCacheDir(), "logo-" + Integer.toHexString(url.hashCode()));
                Bitmap b = null;
                try {
                    if (f.length() == 0) {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(10000);
                        byte[] data = readAll(c.getInputStream());
                        FileOutputStream o = new FileOutputStream(f);
                        o.write(data);
                        o.close();
                    }
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), opt);
                    opt.inSampleSize = Math.max(1, Math.min(opt.outWidth / 240, opt.outHeight / 136));
                    opt.inJustDecodeBounds = false;
                    b = BitmapFactory.decodeFile(f.getPath(), opt);
                } catch (Exception ignored) { }
                if (b == null) return;
                logos.put(url, b);
                final Bitmap bm = b;
                runOnUiThread(new Runnable() {
                    public void run() { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); }
                });
            }
        });
    }

    // select a row only after the new adapter is laid out, so the first OK press already acts on it
    void focus(final int pos) {
        lv.post(new Runnable() {
            public void run() {
                lv.requestFocus();
                lv.setSelection(pos);
            }
        });
    }

    /**
     * Передаём VLC только выбранный поток: чтение плейлиста выполняется при старте системы, VLC не индексирует
     * сотни соседних адресов при каждом выборе канала.
     */
    void playGroup(List<String[]> chans, int pos) {
        String[] ch = chans.get(pos);
        play(ch[0], ch[1]);
    }

    void play(String url, String name) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.putExtra("title", name);
        i.setPackage(VLC);
        try {
            startActivity(i);
        } catch (Exception e) {
            i.setPackage(null);
            try {
                startActivity(i);
            } catch (Exception e2) {
                Toast.makeText(this, "Нет плеера: " + e2.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (cur != null) showGroups();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (lv != null) setTitle(cur == null ? "ТВ-каналы" : cur);
    }

    static String read(File f) {
        try {
            return new String(readAll(new FileInputStream(f)), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
