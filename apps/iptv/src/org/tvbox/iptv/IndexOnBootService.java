package org.tvbox.iptv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Однажды индексирует локальный M3U при загрузке, показывает ход и итог в уведомлении. */
public class IndexOnBootService extends Service {
    static final int NOTIFICATION_ID = 4318;
    static final String CHANNEL_ID = "iptv_index";
    volatile boolean stopped;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final File playlist = new File(MainActivity.BUNDLED);
        final long size = playlist.length(), modified = playlist.lastModified();
        final android.content.SharedPreferences prefs = getSharedPreferences("iptv_index", MODE_PRIVATE);
        if (!playlist.isFile() || size == 0) { stopSelf(startId); return START_NOT_STICKY; }
        if (prefs.getBoolean("complete", false) && prefs.getLong("size", -1) == size &&
                prefs.getLong("modified", -1) == modified) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Подготавливаю список ТВ-каналов", 0, 1, 0));
        prefs.edit().putBoolean("running", true).putBoolean("shown", false).putInt("done", 0).apply();
        new Thread(new Runnable() {
            public void run() { index(playlist, size, modified, prefs); }
        }, "iptv-index").start();
        return START_NOT_STICKY;
    }

    void index(File playlist, long size, long modified, android.content.SharedPreferences prefs) {
        long started = android.os.SystemClock.elapsedRealtime();
        int total = 0, done = 0, channels = 0;
        Set<String> groups = new HashSet<>(), names = new HashSet<>();
        Map<String, List<String[]>> index = new LinkedHashMap<>();
        try {
            // Сначала считаем строки, чтобы показывать реальный процент и ETA, а не фиктивный спиннер.
            BufferedReader r = new BufferedReader(new FileReader(playlist));
            while (r.readLine() != null) total++;
            r.close();
            prefs.edit().putInt("total", Math.max(total, 1)).apply();
            r = new BufferedReader(new FileReader(playlist));
            String line, name = null, group = "Без группы", logo = "", channelId = "", tvgName = "";
            while (!stopped && (line = r.readLine()) != null) {
                done++;
                if (line.startsWith("#EXTINF")) {
                    int comma = line.lastIndexOf(',');
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "?";
                    group = MainActivity.canonicalGroup(attr(line, "group-title"));
                    logo = attr(line, "tvg-logo");
                    channelId = attr(line, "tvg-id");
                    tvgName = attr(line, "tvg-name");
                } else if (name != null && !line.trim().isEmpty() && !line.startsWith("#")) {
                    List<String[]> entries = index.get(group);
                    if (entries == null) { entries = new ArrayList<>(); index.put(group, entries); }
                    entries.add(new String[]{line.trim(), name, logo, channelId, tvgName, group,
                            MainActivity.quality(name + " " + tvgName)});
                    channels++;
                    groups.add(group);
                    names.add(normalize(name));
                    name = null;
                }
                if (done % 100 == 0 || done == total) {
                    long elapsed = Math.max(1, android.os.SystemClock.elapsedRealtime() - started);
                    int left = (int) Math.ceil((double) elapsed * Math.max(0, total - done) / done / 1000.0);
                    prefs.edit().putInt("done", done).putInt("left_seconds", left).apply();
                    updateNotification(done, Math.max(total, 1), left);
                }
            }
            r.close();
            if (!stopped) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                // Записываем готовый разбор атомарно, чтобы приложение не разбирало M3U повторно при каждом запуске.
                File cache = new File(getFilesDir(), "iptv-index.ser");
                File temp = new File(getFilesDir(), "iptv-index.tmp");
                ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(temp));
                out.writeObject(index);
                out.close();
                if (cache.exists() && !cache.delete()) throw new java.io.IOException("не удалось удалить старый кэш индекса");
                if (!temp.renameTo(cache)) throw new java.io.IOException("не удалось сохранить кэш индекса");
                prefs.edit().putBoolean("complete", true).putBoolean("running", false)
                        .putInt("schema", MainActivity.INDEX_SCHEMA).putLong("size", size)
                        .putLong("modified", modified).putInt("channels", channels).putInt("groups", groups.size())
                        .putInt("duplicates", channels - names.size()).putInt("done", total).putInt("left_seconds", 0)
                        .putLong("elapsed_ms", elapsed).apply();
                updateNotification(total, Math.max(total, 1), 0);
            }
        } catch (Exception e) {
            String error = e.getClass().getSimpleName();
            prefs.edit().putBoolean("running", false).putBoolean("complete", false).putString("error", error).apply();
            updateFailureNotification(error);
        }
        // Оставляем итоговое уведомление, чтобы пользователь видел, что подготовка закончилась.
        stopForeground(false);
        stopSelf();
    }

    static String attr(String line, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\\\"([^\\\"]*)\\\"").matcher(line);
        return m.find() ? m.group(1) : "";
    }

    static String normalize(String name) {
        String n = Normalizer.normalize(name.toLowerCase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFD)
                .replaceAll("(?i)\\[(?:hd|fhd|uhd|4k|sd|hq|\\d{3,4}p|50\\s?fps|60\\s?fps)\\]|\\((?:hd|fhd|uhd|4k|sd|hq|\\d{3,4}p|50\\s?fps|60\\s?fps)\\)", " ")
                .replaceAll("(?i)(?<![\\p{L}\\p{N}])(?:hd|fhd|uhd|4k|sd|hq|\\d{3,4}p|50\\s?fps|60\\s?fps|hevc|h\\.?265|backup|резерв)(?![\\p{L}\\p{N}])", " ")
                .replaceAll("\\p{M}", "");
        return n.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    Notification notification(String text, int progress, int total, int left) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL_ID, "Подготовка ТВ-каналов", 2);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                manager.getClass().getMethod("createNotificationChannel", channelClass).invoke(manager, channel);
            }
        } catch (Exception ignored) { }
        Notification.Builder builder = new Notification.Builder(this);
        try { builder.getClass().getMethod("setChannelId", String.class).invoke(builder, CHANNEL_ID); }
        catch (Exception ignored) { }
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(text).setContentText(progress >= total ? "Готово" :
                        Math.round(100f * progress / Math.max(total, 1)) + "% • осталось около " + left + " сек.")
                .setProgress(Math.max(total, 1), progress, false).setOngoing(progress < total).build();
    }

    void updateNotification(int progress, int total, int left) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(progress >= total ? "Список ТВ-каналов готов" :
                "Индексирую список ТВ-каналов", progress, total, left));
    }

    void updateFailureNotification(String reason) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = new Notification.Builder(this);
        try { builder.getClass().getMethod("setChannelId", String.class).invoke(builder, CHANNEL_ID); }
        catch (Exception ignored) { }
        manager.notify(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Индексация списка не завершена")
                .setContentText("Ошибка " + reason + "; список откроется без кэша")
                .setOngoing(false).build());
    }

    @Override public void onDestroy() { stopped = true; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
