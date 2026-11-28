package org.tvbox.panadonikproxy;
public final class ProxyPoolTest {
    private static void require(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        ProxyPool p=new ProxyPool();
        Gateway.Upstream a=new Gateway.Upstream("socks5","8.8.8.8",1080);
        Gateway.Upstream b=new Gateway.Upstream("http","1.1.1.1",8080);
        Gateway.Upstream c=new Gateway.Upstream("socks4","9.9.9.9",1080);
        long now=1000;
        require(p.choose(now)==null);
        p.offer(b,now,200);p.offer(a,now,100);
        require(p.choose(now)==a);
        require(p.fail(a,now+1)==b);
        p.offer(a,now+2,1);
        require(p.size(now+2)==1);
        require(p.fail(b,now+3)==null);
        require(p.choose(now+4)==null);
        p.offer(a,now+ProxyPool.COOLDOWN+2,30);
        require(p.choose(now+ProxyPool.COOLDOWN+2)==a);
        require(p.choose(now+ProxyPool.COOLDOWN+2+ProxyPool.TTL)==null);
        require(!Gateway.publicV4("127.0.0.1"));
        require(!Gateway.publicV4("10.0.0.1"));
        require(!Gateway.publicV4("192.168.1.1"));
        require(Gateway.permitted("rr1.googlevideo.com"));
        require(!Gateway.permitted("googlevideo.com.evil.example"));
        require(c.protocol.equals("socks4"));
        require(Gateway.authorized("token","Panadonik token"));
        require(!Gateway.authorized("token","Panadonik wrong"));
        require(!Gateway.authorized("token",null));
        System.out.println("ProxyPool failover, cooldown, expiry and allowlist: PASS");
    }
}
