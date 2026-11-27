package org.tvbox.panadonikproxy;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Фоновый сервис автопрокси: загрузка источников, проверка, выбор, ротация при буферизации. */
public final class ProxyService extends Service {
    static volatile String summary = "Служба остановлена";
    static volatile boolean running = false;
    static volatile int found = 0, checked = 0, accepted = 0, rejected = 0;
    static volatile String selected = "Нет";
    private static volatile String media = "";
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newFixedThreadPool(16);
    private final Gateway gateway = new Gateway();
    private final ProxyPool pool = new ProxyPool();
    private Gateway.Upstream active;
    private long lastRefresh = 0;
    private int failures = 0;
    private volatile boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            gateway.start();
            running = true;
            summary = "Шлюз запущен; поиск прокси";
        } catch (Exception e) {
            summary = "Не удалось открыть локальный порт";
            stopSelf();
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tick, 0, 20, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "refresh".equals(intent.getAction()) && !stopping) {
            lastRefresh = 0;
        }
        return START_NOT_STICKY;
    }

    private void tick() {
        if (stopping) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if (active != null) {
                ProxyChecker.Result r = ProxyChecker.probe(active);
                if ("REACHABLE".equals(r.status)) {
                    failures = 0;
                    summary = "YouTube: " + r.status + " · " + r.ms + " мс";
                } else if (++failures >= 2) {
                    active = null;
                    gateway.select(null);
                    selected = "Нет";
                    summary = "Прокси недоступен; поиск резерва";
                }
            }
            if (active == null) {
                if (now - lastRefresh < 300000 && !pool.candidates(now).isEmpty()) return;
                SourceManager.Snapshot snap = SourceManager.collect(this);
                lastRefresh = now;
                found = snap.proxies.size();
                List<Gateway.Upstream> pending = snap.proxies;
                Collections.shuffle(pending);
                int budget = 16;
                int cursor = 0;
                checked = 0;
                accepted = 0;
                rejected = 0;
                ProxyState state = new ProxyState(this);
                if (!state.initialComplete()) state.begin(found, pending.size());
                while (cursor < pending.size() && budget > 0) {
                    int end = Math.min(cursor + 8, pending.size());
                    List<Gateway.Upstream> batch = pending.subList(cursor, end);
                    List<Future<ProxyChecker.Result>> jobs = new java.util.ArrayList<>();
                    for (Gateway.Upstream p : batch) jobs.add(workers.submit(() -> ProxyChecker.probe(p)));
                    for (int i = 0; i < batch.size(); i++) {
                        Gateway.Upstream p = batch.get(i);
                        ProxyChecker.Result r;
                        try { r = jobs.get(i).get(25000, TimeUnit.MILLISECONDS); }
                        catch (Exception e) { r = new ProxyChecker.Result("UNREACHABLE", 0, 0); }
                        checked++;
                        if ("REACHABLE".equals(r.status)) {
                            pool.offer(p, now, r.ms);
                            accepted++;
                        } else {
                            rejected++;
                        }
                        if (!state.initialComplete()) state.progress(checked, accepted);
                    }
                    budget -= batch.size();
                    cursor = end;
                }
                if (!state.initialComplete() && found >= 5 && cursor >= pending.size()) state.complete(checked, accepted);
                Gateway.Upstream next = pool.choose(now);
                if (next != null) {
                    active = next;
                    gateway.select(next);
                    selected = next.toString();
                    summary = "Выбран: " + selected;
                } else {
                    summary = "Нет прокси с подтверждённой доступностью";
                }
            }
        } catch (Exception e) {
            summary = "Ошибка цикла: " + e.getMessage();
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        scheduler.shutdownNow();
        workers.shutdownNow();
        gateway.close();
        running = false;
        selected = "Нет";
        summary = "Служба остановлена";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static boolean rotationConfigured(Context ctx) { return new ProxyState(ctx).rotate(); }
    public static void setRotation(Context ctx, boolean value) { new ProxyState(ctx).setRotate(value); }
    public static boolean enabled(Context ctx) { return new ProxyState(ctx).enabled(); }
    public static void setEnabled(Context ctx, boolean value) { new ProxyState(ctx).setEnabled(value); }
    public static void refresh() { /* триггер через Intent с action=refresh */ }
    public static String status(Context ctx) { return new ProxyState(ctx).summary(); }
}