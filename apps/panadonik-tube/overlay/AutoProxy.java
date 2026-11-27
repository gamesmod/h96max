package org.tvbox.panadonikproxy;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Process-local automatic proxy selection. No credentials or media URLs are collected. */
public final class AutoProxy {
    private static final String TAG="PanadonikProxy";
    private static final ProxyPool pool=new ProxyPool();
    private static final Gateway gateway=new Gateway();
    private static final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService workers=Executors.newFixedThreadPool(16);
    private static volatile boolean enabled;
    private static Context context;
    private static long refreshed,lastRotate;
    private static List<Gateway.Upstream> pending=new ArrayList<>();
    private static int cursor;
    private static int sourceCount;
    private static long now(){return SystemClock.elapsedRealtime();}
    public static boolean enabled(){return enabled;}
    public static synchronized void start(Context ctx){
        if(enabled)return;
        context=ctx.getApplicationContext();
        int port=1;
        try{gateway.start();port=gateway.port();}catch(Exception e){Log.e(TAG,"Local gateway unavailable");}
        final Proxy local=new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port));
        // Prevent stale manual proxy system properties overriding the per-host selector.
        for(String key:new String[]{"http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort",
                "socksProxyHost","socksProxyPort","http.proxyUser","http.proxyPassword",
                "https.proxyUser","https.proxyPassword","socksProxyUser","socksProxyPassword"})
            System.clearProperty(key);
        ProxySelector.setDefault(new ProxySelector(){
            @Override public List<Proxy> select(URI uri){
                String host=uri.getHost();
                return Collections.singletonList(host!=null&&Gateway.permitted(host.toLowerCase(Locale.ROOT))?local:Proxy.NO_PROXY);
            }
            @Override public void connectFailed(URI uri,SocketAddress sa,java.io.IOException error){}
        });
        enabled=true;
        clock.scheduleWithFixedDelay(AutoProxy::tick,0,20,TimeUnit.SECONDS);
    }
    private static synchronized void applyChoice(){
        gateway.select(pool.choose(now()));
    }
    public static synchronized boolean rotateOnBuffering(){
        if(!enabled)return false;
        long time=now();
        Gateway.Upstream old=pool.current();
        if(old==null||(lastRotate!=0&&time-lastRotate<15000))return false;
        lastRotate=time;
        Gateway.Upstream next=pool.fail(old,time);
        gateway.select(next);
        Log.i(TAG,"Buffering: route rotated, available="+pool.size(time));
        return next!=null;
    }
    static int successfulSources(String states){
        Set<String> ids=new HashSet<>();
        for(String line:states.split("\n")){
            String[] parts=line.split(" ");
            if(parts.length==3 && parts[2].matches("[1-9][0-9]*"))ids.add(parts[0]);
            if(parts.length==4 && parts[2].equals("кеш") && parts[3].matches("[1-9][0-9]*"))ids.add(parts[0]);
        }
        return ids.size();
    }
    private static void tick(){
        try{
            long time=now();
            if(refreshed==0 || time-refreshed>300000 || (cursor>=pending.size()&&pool.size(time)<2&&time-refreshed>30000)){
                SourceManager.Snapshot snapshot=SourceManager.collect(context);
                sourceCount=successfulSources(snapshot.states);
                refreshed=now();
                pending=sourceCount>=5?snapshot.proxies:new ArrayList<>();
                Collections.shuffle(pending);cursor=0;
                Log.i(TAG,"Sources="+sourceCount+", unique candidates="+pending.size());
            }
            // Revalidate active and reserves periodically. No untested endpoint enters the pool.
            List<Gateway.Upstream> recheck=pool.candidates(now());
            if(!recheck.isEmpty())checkBatch(recheck,true);
            int budget=32;
            while(cursor<pending.size()&&pool.size(now())<6&&budget>0){
                int end=Math.min(cursor+16,pending.size());
                checkBatch(new ArrayList<>(pending.subList(cursor,end)),false);
                budget-=end-cursor;cursor=end;
            }
            applyChoice();
            Log.i(TAG,"Reachable routes="+pool.size(now())+"; source count="+sourceCount);
        }catch(Exception e){Log.w(TAG,"Proxy scan failed");}
    }
    private static void checkBatch(List<Gateway.Upstream> list,boolean recheck)throws Exception{
        List<Future<ProxyChecker.Result>> futures=new ArrayList<>();
        for(Gateway.Upstream p:list)futures.add(workers.submit(()->ProxyChecker.probe(p)));
        long deadline=now()+25000;
        for(int i=0;i<list.size();i++){
            Gateway.Upstream p=list.get(i);
            try{
                ProxyChecker.Result r=futures.get(i).get(Math.max(1,deadline-now()),TimeUnit.MILLISECONDS);
                if("REACHABLE".equals(r.status))pool.offer(p,now(),r.ms);
                else if(recheck)pool.fail(p,now());
            }catch(ExecutionException|TimeoutException e){
                futures.get(i).cancel(true);
                if(recheck)pool.fail(p,now());
            }
            applyChoice();
        }
    }
}
