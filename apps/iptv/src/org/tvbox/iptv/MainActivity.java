package org.tvbox.iptv;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.LruCache;
import android.util.Log;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    static final String TAG = "TvboxIptv";
    static final String EXTRA_DEBUG_SCAN_ADULT = "org.tvbox.iptv.DEBUG_SCAN_ADULT";
    static final String EXTRA_USE_USER_PLAYLIST = "org.tvbox.iptv.USE_USER_PLAYLIST";
    static final String EXTRA_NATIVE_PLAYER_TEST = "tvbox_iptv_native_player_test";
    static final int INDEX_SCHEMA = 5;

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
        if (getIntent().hasExtra(EXTRA_USE_USER_PLAYLIST)) {
            boolean enabled = getIntent().getBooleanExtra(EXTRA_USE_USER_PLAYLIST, false);
            getSharedPreferences("iptv_settings", MODE_PRIVATE).edit().putBoolean("use_user_playlist", enabled).apply();
            iptvLog("playlist_override", "enabled=" + enabled);
        }
        if (getIntent().getBooleanExtra(EXTRA_DEBUG_SCAN_ADULT, false)) {
            setTitle("Диагностика IPTV: Для взрослых");
            startAdultCategoryProbe();
            return;
        }
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

    /** Берёт список из образа; пользовательский список используется только по явному переключателю. */
    static File playlistFile(android.content.Context context) {
        android.content.SharedPreferences settings = context.getSharedPreferences("iptv_settings", MODE_PRIVATE);
        File external = context.getExternalFilesDir(null);
        if (settings.getBoolean("use_user_playlist", false) && external != null) {
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

    /** Передаём VLC выбранную категорию, чтобы PAGE вверх/вниз переключали соседние каналы. */
    void playGroup(List<String[]> chans, int pos) {
        if (chans == null || chans.isEmpty() || pos < 0 || pos >= chans.size()) return;
        String[] ch = chans.get(pos);
        if (!nativePlayerTestEnabled() && playVlcPlaylist(chans, pos, ch[1])) return;
        play(ch[0], ch[1], ch[3]);
    }

    /** Открывает в VLC временный плейлист только текущей категории, начиная с выбранного канала. */
    boolean playVlcPlaylist(List<String[]> chans, int pos, String title) {
        File playlist = new File(getCacheDir(), "iptv-" + UUID.randomUUID().toString().replace("-", "") + ".m3u");
        int written = 0;
        try {
            OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(playlist), StandardCharsets.UTF_8);
            try {
                out.write("#EXTM3U\n");
                for (int offset = 0; offset < chans.size(); offset++) {
                    String[] channel = chans.get((pos + offset) % chans.size());
                    String url = channel[0] == null ? "" : channel[0].trim();
                    if (!isPlayableUrl(url)) continue;
                    out.write("#EXTINF:-1");
                    writeM3uAttribute(out, "tvg-id", valueAt(channel, 3));
                    writeM3uAttribute(out, "tvg-name", valueAt(channel, 4));
                    writeM3uAttribute(out, "tvg-logo", valueAt(channel, 2));
                    writeM3uAttribute(out, "group-title", valueAt(channel, 5));
                    out.write("," + m3uText(valueAt(channel, 1)) + "\n" + url + "\n");
                    written++;
                }
            } finally {
                out.close();
            }
            if (written == 0) throw new IOException("в категории нет доступных URL");
            Uri uri = Uri.parse("content://org.tvbox.iptv.playlists/" + playlist.getName());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/x-mpegURL");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra("title", title);
            intent.setPackage(VLC);
            startActivity(intent);
            iptvLog("playback_handoff", "target=vlc category_playlist channels=" + written);
            return true;
        } catch (Exception e) {
            playlist.delete();
            iptvLog("playback_playlist_failed", "error=" + e.getClass().getSimpleName());
            return false;
        }
    }

    boolean isPlayableUrl(String url) {
        try {
            String scheme = Uri.parse(url).getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            return "http".equals(scheme) || "https".equals(scheme) || "rtsp".equals(scheme)
                    || "rtmp".equals(scheme) || "rtmps".equals(scheme) || "udp".equals(scheme)
                    || "rtp".equals(scheme) || "mms".equals(scheme);
        } catch (Exception ignored) { return false; }
    }

    String valueAt(String[] channel, int index) {
        return channel != null && channel.length > index ? channel[index] : "";
    }

    void writeM3uAttribute(OutputStreamWriter out, String key, String value) throws IOException {
        String safe = m3uText(value).replace("\"", "");
        if (!safe.isEmpty()) out.write(" " + key + "=\"" + safe + "\"");
    }

    String m3uText(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").replace("\u0000", "").trim();
    }

    void play(String url, String name, String channelId) {
        if (iptvDebugEnabled()) {
            iptvLog("playback_selected", "epg_id=" + safeLogValue(channelId) + " scheme=" + safeScheme(url));
        }
        if (nativePlayerTestEnabled()) {
            Intent nativePlayer = new Intent(this, NativePlaybackActivity.class);
            nativePlayer.setData(Uri.parse(url));
            nativePlayer.putExtra("epg_id", safeLogValue(channelId));
            nativePlayer.putExtra("title", name);
            startActivity(nativePlayer);
            iptvLog("playback_handoff", "target=android_mediaplayer scheme=" + safeScheme(url));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.putExtra("title", name);
        i.setPackage(VLC);
        try {
            startActivity(i);
            iptvLog("playback_handoff", "target=vlc scheme=" + safeScheme(url));
        } catch (Exception e) {
            iptvLog("playback_handoff_failed", "target=vlc scheme=" + safeScheme(url) + " error=" + e.getClass().getSimpleName());
            i.setPackage(null);
            try {
                startActivity(i);
                iptvLog("playback_fallback", "target=implicit scheme=" + safeScheme(url));
            } catch (Exception e2) {
                iptvLog("playback_failed", "target=implicit scheme=" + safeScheme(url) + " error=" + e2.getClass().getSimpleName());
                Toast.makeText(this, "Нет плеера: " + e2.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    boolean nativePlayerTestEnabled() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(), EXTRA_NATIVE_PLAYER_TEST, 0) == 1;
        } catch (Exception ignored) { return false; }
    }

    static String safeScheme(String url) {
        try {
            String scheme = Uri.parse(url).getScheme();
            return scheme == null ? "missing" : scheme.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) { return "invalid"; }
    }

    static String safeLogValue(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,80}") ? value : "unknown";
    }

    boolean iptvDebugEnabled() {
        try {
            if (android.provider.Settings.Global.getInt(getContentResolver(), "tvbox_iptv_debug", 0) == 1)
                return true;
        } catch (Exception ignored) { }
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String enabled = (String) properties.getMethod("get", String.class, String.class)
                    .invoke(null, "persist.tvbox.iptv.debug", "0");
            return "1".equals(enabled);
        } catch (Exception ignored) { return false; }
    }

    void startAdultCategoryProbe() {
        if (!iptvDebugEnabled()) {
            setTitle("Сначала включите отладку IPTV");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                List<String[]> channels = adultStreams(playlistFile(MainActivity.this));
                iptvLog("category_probe_start", "category=adult count=" + channels.size());
                Map<Integer, Integer> statuses = new LinkedHashMap<>();
                int failed = 0;
                boolean timedOut = false;
                for (String[] channel : channels) {
                    ProbeResult result = probeStreamBounded(channel[0], 8000);
                    if (result.error.isEmpty()) {
                        Integer count = statuses.get(result.status);
                        statuses.put(result.status, count == null ? 1 : count + 1);
                    } else failed++;
                    iptvLog(result.error.isEmpty() ? "category_probe_result" : "category_probe_error",
                            "epg_id=" + safeLogValue(channel[1]) + " " + result.asLogDetail());
                    if ("Timeout".equals(result.error)) { timedOut = true; break; }
                    try { Thread.sleep(500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                int checked = failed;
                for (Integer count : statuses.values()) checked += count;
                StringBuilder summary = new StringBuilder("category=adult checked=")
                        .append(checked).append(" failed=").append(failed)
                        .append(" timed_out=").append(timedOut);
                for (Map.Entry<Integer, Integer> status : statuses.entrySet())
                    summary.append(" http_").append(status.getKey()).append('=').append(status.getValue());
                iptvLog("category_probe_complete", summary.toString());
            }
        }, "iptv-adult-category-probe").start();
    }

    static List<String[]> adultStreams(File playlist) {
        List<String[]> out = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new java.io.FileReader(playlist));
            String line, channelId = "", group = "";
            boolean pending = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    group = canonicalGroup(m3uAttr(line, "group-title"));
                    channelId = m3uAttr(line, "tvg-id");
                    pending = true;
                } else if (pending && !line.isEmpty() && !line.startsWith("#")) {
                    if ("Для взрослых".equals(group)) out.add(new String[]{line, channelId});
                    pending = false;
                }
            }
            reader.close();
        } catch (Exception ignored) { }
        return out;
    }

    static ProbeResult probeStream(String url) {
        HttpURLConnection connection = null;
        try {
            String scheme = safeScheme(url);
            if (!"http".equals(scheme) && !"https".equals(scheme))
                return new ProbeResult(-1, "", scheme, "UnsupportedScheme");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20");
            connection.setRequestProperty("Range", "bytes=0-0");
            int status = connection.getResponseCode();
            String type = connection.getContentType();
            if (type != null) type = type.split(";", 2)[0].replaceAll("[^A-Za-z0-9!#$&^_.+/-]", "");
            return new ProbeResult(status, type, safeScheme(connection.getURL().toString()), "");
        } catch (Exception e) {
            return new ProbeResult(-1, "", safeScheme(url), e.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Ограничивает полное время запроса, включая DNS-поиск, который не входит в тайм-аут URLConnection. */
    static ProbeResult probeStreamBounded(final String url, long timeoutMillis) {
        final ProbeResult[] result = new ProbeResult[1];
        Thread worker = new Thread(new Runnable() {
            @Override public void run() { result[0] = probeStream(url); }
        }, "iptv-probe-request");
        worker.setDaemon(true);
        worker.start();
        try { worker.join(timeoutMillis); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(-1, "", safeScheme(url), "Interrupted");
        }
        if (worker.isAlive()) {
            worker.interrupt();
            return new ProbeResult(-1, "", safeScheme(url), "Timeout");
        }
        return result[0] == null ? new ProbeResult(-1, "", safeScheme(url), "ProbeFailed") : result[0];
    }

    static final class ProbeResult {
        final int status;
        final String contentType, finalScheme, error;
        ProbeResult(int status, String contentType, String finalScheme, String error) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.finalScheme = finalScheme == null ? "unknown" : finalScheme;
            this.error = error == null ? "unknown" : error;
        }
        String asLogDetail() {
            return "status=" + (status < 0 ? "unknown" : status) + " content_type=" +
                    (contentType.isEmpty() ? "unknown" : contentType) + " final_scheme=" + finalScheme +
                    (error.isEmpty() ? "" : " error=" + error);
        }
    }

    void iptvLog(String event, String detail) {
        iptvLogStatic(this, event, detail);
    }

    static void iptvLogStatic(Context context, String event, String detail) {
        try {
            if (android.provider.Settings.Global.getInt(context.getContentResolver(), "tvbox_iptv_debug", 0) == 1)
                Log.d(TAG, "event=" + event + " " + detail);
        } catch (Exception ignored) { }
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
