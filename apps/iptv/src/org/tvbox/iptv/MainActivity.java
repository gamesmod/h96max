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
 * Список групп и каналов M3U с запуском воспроизведения во VLC.
 * Основной плейлист входит в образ, пользовательский файл из каталога приложения имеет приоритет.
 * Сетевой список используется только как запасной источник; логотипы кэшируются в памяти и cacheDir.
 */
public class MainActivity extends Activity {
    static final String BUNDLED = "/system/etc/tvbox/iptv.m3u";
    static final String USER_PLAYLIST = "iptv.m3u";
    static final String PLAYLIST = "https://ngrch.github.io/iptv/ru.m3u";
    static final String VLC = "org.videolan.vlc";
    static final int INDEX_SCHEMA = 4;

    ListView lv;
    final Map<String, List<String[]>> groups = new LinkedHashMap<>();
    List<String> groupNames = new ArrayList<>();
    String cur;          // Открытая группа; null означает список групп.
    int groupPos;        // Позиция группы для восстановления при возврате.
    final LruCache<String, Bitmap> logos = new LruCache<>(200);
    final ExecutorService loader = Executors.newFixedThreadPool(4);
    final Handler indexUi = new Handler();
    ProgressDialog indexDialog;
    final Runnable indexPoll = new Runnable() {
        public void run() {
            android.content.SharedPreferences p = getSharedPreferences("iptv_index", MODE_PRIVATE);
            if (!p.getBoolean("running", false)) {
                File bundled = playlistFile(MainActivity.this);
                if (!indexCurrent(bundled, p) && p.getString("error", "").isEmpty()) {
                    indexUi.postDelayed(this, 250);
                    return;
                }
                if (indexDialog != null && indexDialog.isShowing()) indexDialog.dismiss();
                int count = p.getInt("channels", 0);
                if (groups.isEmpty()) {
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
        File bundled = playlistFile(this);
        if (loadCachedIndex(bundled)) {
            showGroups();
            showBootIndexStatus();
            return;
        }
        ensureIndexService();
        showBootIndexStatus();
        android.content.SharedPreferences indexState = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (!indexCurrent(bundled, indexState) && indexState.getString("error", "").isEmpty()) {
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
        if (!p.getBoolean("complete", false) || p.getInt("schema", 0) != INDEX_SCHEMA ||
                p.getLong("size", -1) != playlist.length() ||
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
        if (p.getBoolean("running", false) || !indexCurrent(playlistFile(this), p)) {
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

    boolean indexCurrent(File playlist, android.content.SharedPreferences p) {
        return p.getBoolean("complete", false) && p.getInt("schema", 0) == INDEX_SCHEMA &&
                p.getLong("size", -1) == playlist.length() &&
                p.getLong("modified", -1) == playlist.lastModified() &&
                new File(getFilesDir(), "iptv-index.ser").isFile();
    }

    void ensureIndexService() {
        File playlist = playlistFile(this);
        android.content.SharedPreferences p = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (p.getBoolean("complete", false) && p.getInt("schema", 0) == INDEX_SCHEMA &&
                p.getLong("size", -1) == playlist.length() &&
                p.getLong("modified", -1) == playlist.lastModified() &&
                new File(getFilesDir(), "iptv-index.ser").isFile()) return;
        Intent service = new Intent(this, IndexOnBootService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try { android.content.Context.class.getMethod("startForegroundService", Intent.class).invoke(this, service); }
            catch (Exception e) { startService(service); }
        } else startService(service);
    }

    /** Выбирает пользовательский плейлист, если он установлен, иначе берёт плейлист из образа. */
    static File playlistFile(android.content.Context context) {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File userPlaylist = new File(external, USER_PLAYLIST);
            if (userPlaylist.isFile() && userPlaylist.length() > 0) return userPlaylist;
        }
        return new File(BUNDLED);
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
        String s = canonicalGroup(group);
        if (s.contains(" · ")) return "📍";
        if (s.matches("^[\\p{So}]+ .*")) return "📺";
        if (s.equals("Для взрослых")) return "🔞";
        if (s.equals("Спорт")) return "⚽";
        if (s.equals("Новости")) return "📰";
        if (s.equals("Фильмы")) return "🎬";
        if (s.equals("Детские")) return "🧸";
        if (s.equals("Музыка")) return "🎵";
        if (s.equals("Познавательные")) return "🔭";
        if (s.equals("Радио")) return "📻";
        if (s.equals("Региональные")) return "📍";
        if (s.equals("Развлекательные")) return "🎭";
        if (s.equals("Россия")) return "🇷🇺";
        if (s.equals("Беларусь")) return "🇧🇾";
        if (s.equals("Украина")) return "🇺🇦";
        if (s.equals("Казахстан")) return "🇰🇿";
        return "📺";
    }

    static String canonicalGroup(String group) {
        // Итоговый плейлист уже нормализован: сохраняем подкатегории и подписи с флагами.
        if (group != null && (group.contains(" · ") || group.matches(".*\\p{So}.*"))) return group.trim();
        String s = group == null ? "" : group.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^a-zа-я0-9+]+", " ").trim();
        if (s.length() == 0) return "Прочее";
        if (s.matches(".*(18\\+|xxx|adult|эрот|взросл|playboy|brazzers|hustler|private|redlight|sex|секс).*")) return "Для взрослых";
        if (s.matches(".*(sport|спорт).*")) return "Спорт";
        if (s.matches(".*(news|новост|информац).*")) return "Новости";
        if (s.matches(".*(kid|child|cartoon|дет|мульт).*")) return "Детские";
        if (s.matches(".*(movie|film|cinema|кино|фильм).*")) return "Фильмы";
        if (s.matches(".*(music|музык).*")) return "Музыка";
        if (s.matches(".*(document|science|education|познав|документ|науч|образоват).*")) return "Познавательные";
        if (s.matches(".*(radio|радио).*")) return "Радио";
        if (s.matches(".*(moscow oblast|moscow region|московская область).*")) return "🇷🇺 Россия · Московская область";
        if (s.matches(".*(moscow|moskva|москва).*")) return "🇷🇺 Россия · Москва";
        if (s.matches(".*(st petersburg|saint petersburg|spb|санкт петербург).*")) return "🇷🇺 Россия · Санкт-Петербург";
        if (s.matches(".*(leningrad oblast|ленинградская область).*")) return "🇷🇺 Россия · Ленинградская область";
        if (s.matches(".*(tatarstan|татарстан).*")) return "🇷🇺 Россия · Татарстан";
        if (s.matches(".*(krasnodar krai|краснодарский край|krasnodar).*")) return "🇷🇺 Россия · Краснодарский край";
        if (s.matches(".*(sverdlovsk oblast|yekaterinburg|свердловская область).*")) return "🇷🇺 Россия · Свердловская область";
        if (s.matches(".*(novosibirsk oblast|новосибирская область).*")) return "🇷🇺 Россия · Новосибирская область";
        if (s.matches(".*(bashkortostan|bashkiria|башкортостан).*")) return "🇷🇺 Россия · Башкортостан";
        if (s.matches(".*(california).*")) return "🇺🇸 США · Калифорния";
        if (s.matches(".*(new york).*")) return "🇺🇸 США · Нью-Йорк";
        if (s.matches(".*(texas).*")) return "🇺🇸 США · Техас";
        if (s.matches(".*(florida).*")) return "🇺🇸 США · Флорида";
        if (s.matches(".*(washington).*")) return "🇺🇸 США · Вашингтон";
        if (s.matches(".*(british columbia|британская колумбия).*")) return "🇨🇦 Канада · Британская Колумбия";
        if (s.matches(".*(ontario|онтарио).*")) return "🇨🇦 Канада · Онтарио";
        if (s.matches(".*(quebec|квебек).*")) return "🇨🇦 Канада · Квебек";
        if (s.matches(".*(regional|local|регион|местн).*")) return "Региональные";
        if (s.matches(".*(entertain|развлек).*")) return "Развлекательные";
        if (s.matches("(ru|russia|russian|россия|российские|русские)")) return "🇷🇺 Россия";
        if (s.matches("(by|belarus|belarussian|беларусь|белоруссия)")) return "🇧🇾 Беларусь";
        if (s.matches("(ua|ukraine|ukrainian|украина)")) return "🇺🇦 Украина";
        if (s.matches("(kz|kazakhstan|казахстан)")) return "🇰🇿 Казахстан";
        if (s.matches("(us|usa|united states|america|сша|соединенные штаты)")) return "🇺🇸 США";
        if (s.matches("(gb|uk|united kingdom|great britain|британия|великобритания)")) return "🇬🇧 Великобритания";
        if (s.matches("(ca|canada|канада)")) return "🇨🇦 Канада";
        if (s.matches("(de|germany|german|германия)")) return "🇩🇪 Германия";
        if (s.matches("(fr|france|франция)")) return "🇫🇷 Франция";
        if (s.matches("(tr|turkey|turkiye|турция)")) return "🇹🇷 Турция";
        if (s.matches("(kg|kyrgyzstan|кыргызстан|киргизия)")) return "🇰🇬 Кыргызстан";
        if (s.matches("(uz|uzbekistan|узбекистан)")) return "🇺🇿 Узбекистан";
        if (s.matches("(am|armenia|армения)")) return "🇦🇲 Армения";
        if (s.matches("(az|azerbaijan|азербайджан)")) return "🇦🇿 Азербайджан";
        if (s.matches("(ge|georgia|грузия)")) return "🇬🇪 Грузия";
        if (s.matches("(md|moldova|молдова)")) return "🇲🇩 Молдова";
        return "Прочее";
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
                group = canonicalGroup(m3uAttr(line, "group-title"));
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

    // Выбираем строку после раскладки нового адаптера, чтобы первое нажатие OK сразу сработало.
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
