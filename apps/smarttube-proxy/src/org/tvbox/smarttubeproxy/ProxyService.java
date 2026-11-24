package org.tvbox.smarttubeproxy;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProxyService extends Service {
    static volatile String summary="Служба остановлена",sources="",media="";
    static volatile boolean running=false;
    static volatile int found=0,checked=0,working=0;
    static volatile String selected="Нет";
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers=Executors.newFixedThreadPool(16);
    private final Gateway gateway=new Gateway();
    private final List<Gateway.Upstream> pool=new ArrayList<>();
    private Gateway.Upstream active;
    private long lastRefresh=0;
    private int failures=0;
    private volatile boolean stopping=false;
    @Override public void onCreate(){
        super.onCreate();
        android.app.NotificationManager manager=(android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        // API 26 methods via reflection because project compiles against Android 23 jar.
        try{
            Class<?> cls=Class.forName("android.app.NotificationChannel");
            Object channel=cls.getConstructor(String.class,CharSequence.class,int.class).newInstance("proxy","Прокси SmartTube",2);
            manager.getClass().getMethod("createNotificationChannel",cls).invoke(manager,channel);
        }catch(Exception e){stopSelf();return;}
        Notification.Builder builder=new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Прокси SmartTube").setContentText("Локальный шлюз");
        try{builder.getClass().getMethod("setChannelId",String.class).invoke(builder,"proxy");}
        catch(Exception e){stopSelf();return;}
        startForeground(18765,builder.build());
        try{gateway.start();running=true;summary="Шлюз запущен; поиск прокси";}
        catch(Exception e){summary="Не удалось открыть локальный порт";stopSelf();return;}
        scheduler.scheduleWithFixedDelay(()->tick(false),0,1,TimeUnit.MINUTES);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && "refresh".equals(intent.getAction()) && !stopping)scheduler.execute(()->tick(true));
        return START_NOT_STICKY;
    }
    private void tick(boolean force){
        if(stopping)return;
        try{
            String url=media;
            if(active!=null){
                ProxyChecker.Result r=ProxyChecker.check(active,url,15000,10);
                if(r.usable()){failures=0;summary="YouTube: "+r.status+" · "+String.format(Locale.US,"%.1f Мбит/с",r.mbps)+" · "+r.ms+" мс";}
                else if(++failures>=2){active=null;gateway.select(null);selected="Нет";summary="Прокси недоступен; поиск резерва";}
            }
            if(active==null && ProxyChecker.validMedia(url)){
                for(Iterator<Gateway.Upstream> it=pool.iterator();it.hasNext();){
                    Gateway.Upstream candidate=it.next();it.remove();
                    ProxyChecker.Result r=ProxyChecker.check(candidate,url,15000,10);
                    if(r.usable()){select(candidate,r);break;}
                }
            }
            long now=System.currentTimeMillis();
            if(!force && now-lastRefresh<900000 && (active!=null||!pool.isEmpty()))return;
            if(!force && now-lastRefresh<900000)return;
            SourceManager.Snapshot snap=SourceManager.collect(this);
            lastRefresh=now;found=snap.proxies.size();sources=snap.states;checked=0;working=0;
            if(!ProxyChecker.validMedia(url)){summary="Списки загружены. Нужна действительная медиассылка YouTube.";return;}
            Collections.shuffle(snap.proxies);
            List<Gateway.Upstream> subset=snap.proxies.subList(0,Math.min(32,snap.proxies.size()));
            List<Future<ProxyChecker.Result>> jobs=new ArrayList<>();
            for(Gateway.Upstream p:subset)jobs.add(workers.submit(()->ProxyChecker.check(p,url,15000,10)));
            List<Map.Entry<Gateway.Upstream,ProxyChecker.Result>> good=new ArrayList<>();
            for(int i=0;i<jobs.size();i++){
                if(stopping)return;
                try{
                    ProxyChecker.Result r=jobs.get(i).get();checked++;
                    if(r.usable())good.add(new AbstractMap.SimpleImmutableEntry<>(subset.get(i),r));
                }catch(ExecutionException ignored){}
            }
            working=good.size();
            good.sort((a,b)->Double.compare(score(b),score(a)));
            pool.clear();
            for(Map.Entry<Gateway.Upstream,ProxyChecker.Result> e:good){
                if(active==null){select(e.getKey(),e.getValue());continue;}
                if(!e.getKey().toString().equals(active.toString())&&pool.size()<5)pool.add(e.getKey());
            }
            if(active==null)summary="Нет прокси с подтверждённой передачей медиаданных";
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){summary="Ошибка проверки; повтор при следующем цикле";}
    }
    private double score(Map.Entry<Gateway.Upstream,ProxyChecker.Result> e){
        ProxyChecker.Result r=e.getValue();return r.mbps-Math.min(10,r.ms/500.0)+(r.status.equals("STABLE")?5:0)+(e.getKey().protocol.equals("socks5")?1:0);
    }
    private void select(Gateway.Upstream p,ProxyChecker.Result r){
        if(stopping)return;
        active=p;gateway.select(p);selected=p.toString();failures=0;
        summary="YouTube: "+r.status+" · "+String.format(Locale.US,"%.1f Мбит/с",r.mbps)+" · "+r.ms+" мс";
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        stopping=true;scheduler.shutdownNow();workers.shutdownNow();gateway.close();running=false;selected="Нет";summary="Служба остановлена";super.onDestroy();
    }
}
