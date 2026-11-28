package org.tvbox.panadonikproxy;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.security.SecureRandom;

/** CONNECT only; TLS is end-to-end. New tunnels use new upstream; existing tunnels are not migrated. */
public final class Gateway implements Closeable {
    public static final int PORT = 18765;
    private final String token=createToken();
    private static String createToken(){
        byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);StringBuilder out=new StringBuilder();
        for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
    public String token(){return token;}
    public static final class Upstream {
        public final String protocol, ip;
        public final int port;
        public Upstream(String protocol, String ip, int port) {
            this(protocol,ip,port,false);
        }
        private Upstream(String protocol,String ip,int port,boolean testLoopback){
            if (!(protocol.equals("http") || protocol.equals("https") || protocol.equals("socks4") || protocol.equals("socks5")) || (!publicV4(ip)&&!(testLoopback&&ip.equals("127.0.0.1"))) || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid upstream");
            this.protocol=protocol; this.ip=ip; this.port=port;
        }
        static Upstream forTest(String protocol,String ip,int port){return new Upstream(protocol,ip,port,true);}
        @Override public String toString() { return protocol+"://"+ip+":"+port; }
    }
    // Intentionally IPv4 only; never resolve source-provided hostnames or private IPs.
    public static boolean publicV4(String ip) {
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts=ip.split("\\.");
        int[] x=new int[4];
        for(int i=0;i<4;i++){x[i]=Integer.parseInt(parts[i]);if(x[i]>255 || !parts[i].equals(""+x[i]))return false;}
        int a=x[0], b=x[1], c=x[2];
        return a>0 && a<224 && a!=10 && a!=127 &&
            !(a==100 && b>=64 && b<=127) && !(a==169 && b==254) &&
            !(a==172 && b>=16 && b<=31) && !(a==192 && (b==168 || (b==0 && (c==0 || c==2)))) &&
            !(a==198 && (b==18 || b==19 || (b==51 && c==100))) && !(a==203 && b==0 && c==113) &&
            !(a==192 && b==88 && c==99);
    }
    static boolean permitted(String host) {
        if(!host.matches("[a-z0-9.-]{1,253}"))return false;
        for(String domain:new String[]{"youtube.com","youtubei.googleapis.com","googlevideo.com","ytimg.com","youtube-nocookie.com","youtu.be"})
            if(host.equals(domain)||host.endsWith("."+domain))return true;
        return false;
    }
    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<4096;i++){
            int c=in.read(); if(c<0) throw new EOFException();
            if(c==10) return out.toString("US-ASCII").replace("\r","");
            out.write(c);
        }
        throw new IOException("header line too long");
    }
    static String headers(InputStream in) throws IOException {
        String authorization=null;
        for(int i=0;i<64;i++){
            String value=line(in);if(value.isEmpty())return authorization;
            int at=value.indexOf(':');
            if(at>0&&value.substring(0,at).trim().equalsIgnoreCase("Proxy-Authorization"))authorization=value.substring(at+1).trim();
        }
        throw new IOException("too many headers");
    }
    static void write(OutputStream out,String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));out.flush();
    }
    static int byteOf(InputStream in) throws IOException {int b=in.read();if(b<0)throw new EOFException();return b;}
    static void skip(InputStream in,int n)throws IOException{for(int i=0;i<n;i++)byteOf(in);}
    public static Socket connect(Upstream upstream,String host,int timeout)throws IOException{
        if(!permitted(host))throw new IOException("destination denied");
        Socket socket=new Socket(Proxy.NO_PROXY);
        try{
            socket.connect(new InetSocketAddress(upstream.ip,upstream.port),timeout);
            socket.setSoTimeout(timeout);
            InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
            if(upstream.protocol.equals("http")||upstream.protocol.equals("https")){
                write(out,"CONNECT "+host+":443 HTTP/1.1\r\nHost: "+host+":443\r\n\r\n");
                if(!line(in).matches("HTTP/1\\.[01] 200(?: .*)?"))throw new IOException("CONNECT rejected");
                headers(in);
            }else if(upstream.protocol.equals("socks5")){
                out.write(new byte[]{5,1,0});out.flush();
                if(byteOf(in)!=5||byteOf(in)!=0)throw new IOException("SOCKS authentication rejected");
                byte[] name=host.getBytes(StandardCharsets.US_ASCII);
                out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);out.write(new byte[]{1,(byte)187});out.flush();
                if(byteOf(in)!=5||byteOf(in)!=0||byteOf(in)!=0)throw new IOException("SOCKS connect rejected");
                int type=byteOf(in);
                if(type==1)skip(in,4);else if(type==4)skip(in,16);else if(type==3)skip(in,byteOf(in));else throw new IOException("SOCKS address");
                skip(in,2);
            }else{
                byte[] name=host.getBytes(StandardCharsets.US_ASCII);
                out.write(new byte[]{4,1,1,(byte)187,0,0,0,1,0});
                out.write(name);out.write(0);out.flush();
                if(byteOf(in)!=0||byteOf(in)!=90)throw new IOException("SOCKS4 connect rejected");
                skip(in,6);
            }
            return socket;
        }catch(IOException|RuntimeException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }
    private static Socket connectDirect(String host,int timeout)throws IOException{
        if(!permitted(host))throw new IOException("destination denied");
        Socket socket=new Socket(Proxy.NO_PROXY);
        try{socket.connect(new InetSocketAddress(host,443),timeout);return socket;}
        catch(IOException|RuntimeException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }
    private final AtomicReference<Upstream> active=new AtomicReference<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(12);
    private final ExecutorService returns=Executors.newFixedThreadPool(12);
    private final Semaphore slots=new Semaphore(12);
    private final java.util.Set<Socket> sockets=java.util.Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    private ServerSocket server;
    public synchronized void select(Upstream upstream){
        Upstream previous=active.getAndSet(upstream);
        if(previous!=null && previous!=upstream){
            for(Socket s:sockets)try{s.close();}catch(IOException ignored){}
        }
        notifyAll();
    }
    public synchronized Upstream awaitActive() throws IOException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(active.get()==null){
            long left=deadline-System.nanoTime();
            if(left<=0)return null;
            try{wait(Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
        }
        return active.get();
    }
    public int port(){return server.getLocalPort();}
    public void start()throws IOException{
        server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0),12);
        Thread accept=new Thread(()->{
            while(!server.isClosed()){
                try{
                    Socket client=server.accept();
                    if(!slots.tryAcquire()){client.close();continue;}
                    sockets.add(client);
                    try{workers.execute(()->{try{handle(client);}finally{sockets.remove(client);slots.release();}});}
                    catch(RejectedExecutionException e){sockets.remove(client);slots.release();client.close();}
                }catch(IOException e){break;}
            }
        },"proxy-accept");accept.setDaemon(true);accept.start();
    }
    private void handle(Socket client){
        try(Socket c=client){
            c.setSoTimeout(15000);
            String[] request=line(c.getInputStream()).split(" ");
            String authorization=headers(c.getInputStream());
            if(!authorized(token,authorization)){
                write(c.getOutputStream(),"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Panadonik\r\nConnection: close\r\n\r\n");return;
            }
            if(request.length!=3||!request[0].equals("CONNECT")||!request[1].endsWith(":443")){
                write(c.getOutputStream(),"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");return;
            }
            String host=request[1].substring(0,request[1].length()-4).toLowerCase(java.util.Locale.ROOT);
            Upstream selected=active.get();
            if(!permitted(host)){write(c.getOutputStream(),"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");return;}
            Socket remote;
            try{remote=selected==null?connectDirect(host,5000):connect(selected,host,5000);}
            catch(IOException e){write(c.getOutputStream(),"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");return;}
            sockets.add(remote);
            try(Socket r=remote){
                if(selected!=null&&active.get()!=selected)return;
                c.setSoTimeout(120000);r.setSoTimeout(120000);
                write(c.getOutputStream(),"HTTP/1.1 200 Connection established\r\n\r\n");
                Future<?> f=returns.submit(()->copy(r,c));
                copy(c,r);
                try{f.get(2,TimeUnit.SECONDS);}catch(Exception e){f.cancel(true);}
            }finally{sockets.remove(remote);}
        }catch(IOException|RuntimeException ignored){}
    }
    private static boolean constantTimeEquals(String expected,String actual){
        if(actual==null)return false;int diff=expected.length()^actual.length();
        for(int i=0;i<Math.max(expected.length(),actual.length());i++){
            char a=i<expected.length()?expected.charAt(i):0,b=i<actual.length()?actual.charAt(i):0;diff|=a^b;
        }
        return diff==0;
    }
    static boolean authorized(String token,String actual){return constantTimeEquals("Panadonik "+token,actual);}
    private static void copy(Socket from,Socket to){
        try{
            byte[] bytes=new byte[16384];int n;
            InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();
            while((n=in.read(bytes))!=-1){out.write(bytes,0,n);out.flush();}
            to.shutdownOutput();
        }catch(IOException e){try{from.close();}catch(IOException ignored){}try{to.close();}catch(IOException ignored){}}
    }
    @Override public void close(){
        if(server!=null)try{server.close();}catch(IOException ignored){}
        for(Socket s:sockets)try{s.close();}catch(IOException ignored){}
        workers.shutdownNow();returns.shutdownNow();
    }
}
