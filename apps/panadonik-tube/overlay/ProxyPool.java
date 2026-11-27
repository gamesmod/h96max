package org.tvbox.panadonikproxy;
import java.util.*;

/** In-memory validated routes only. All timestamps use monotonic elapsed milliseconds. */
final class ProxyPool {
    static final long TTL=300000, COOLDOWN=120000;
    private static final class Entry {
        final Gateway.Upstream proxy; final long until, latency;
        Entry(Gateway.Upstream p,long now,long ms){proxy=p;until=now+TTL;latency=ms;}
    }
    private final Map<String,Entry> good=new HashMap<>();
    private final Map<String,Long> blocked=new HashMap<>();
    private Gateway.Upstream active;
    synchronized void offer(Gateway.Upstream p,long now,long latency){
        if(blocked.getOrDefault(p.toString(),0L)>now)return;
        good.put(p.toString(),new Entry(p,now,latency));
    }
    synchronized Gateway.Upstream current(){return active;}
    synchronized Gateway.Upstream choose(long now){
        good.values().removeIf(e->e.until<=now);
        blocked.values().removeIf(t->t<=now);
        if(active!=null && good.containsKey(active.toString()))return active;
        active=null;
        Entry best=null;
        for(Entry e:good.values())
            if(best==null||e.latency+(e.proxy.protocol.equals("http")?100:0)
                    <best.latency+(best.proxy.protocol.equals("http")?100:0))best=e;
        if(best!=null)active=best.proxy;
        return active;
    }
    synchronized Gateway.Upstream fail(Gateway.Upstream p,long now){
        if(p==null)return choose(now);
        good.remove(p.toString());blocked.put(p.toString(),now+COOLDOWN);
        if(active!=null&&active.toString().equals(p.toString()))active=null;
        return choose(now);
    }
    synchronized int size(long now){good.values().removeIf(e->e.until<=now);return good.size();}
    synchronized List<Gateway.Upstream> candidates(long now){
        size(now);List<Gateway.Upstream> r=new ArrayList<>();
        for(Entry e:good.values())r.add(e.proxy);return r;
    }
}
