package org.tvbox.smarttubeproxy;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** CONNECT only; TLS is end-to-end. New tunnels use new upstream; existing tunnels are not migrated. */
public final class Gateway implements Closeable {
    public static final int PORT = 18765;
    public static final class Upstream {
        public final String protocol, ip;
        public final int port;
        public Upstream(String protocol, String ip, int port) {
            if (!(protocol.equals("http") || protocol.equals("socks5")) || !publicV4(ip) || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid upstream");
            this.protocol=protocol; this.ip=ip; this.port=port;
        }
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
    static void headers(InputStream in) throws IOException {
        for(int i=0;i<64;i++)if(line(in).isEmpty())return;
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
            if(upstream.protocol.equals("http")){
                write(out,"CONNECT "+host+":443 HTTP/1.1\r\nHost: "+host+":443\r\n\r\n");
                if(!line(in).matches("HTTP/1\\.[01] 200(?: .*)?"))throw new IOException("CONNECT rejected");
                headers(in);
            }else{
                out.write(new byte[]{5,1,0});out.flush();
                if(byteOf(in)!=5||byteOf(in)!=0)throw new IOException("SOCKS authentication rejected");
                byte[] name=host.getBytes(StandardCharsets.US_ASCII);
                out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);out.write(new byte[]{1,(byte)187});out.flush();
                if(byteOf(in)!=5||byteOf(in)!=0||byteOf(in)!=0)throw new IOException("SOCKS connect rejected");
                int type=byteOf(in);
                if(type==1)skip(in,4);else if(type==4)skip(in,16);else if(type==3)skip(in,byteOf(in));else throw new IOException("SOCKS address");
                skip(in,2);
            }
            return socket;
        }catch(IOException|RuntimeException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }
    private final AtomicReference<Upstream> active=new AtomicReference<>();
    private final ExecutorService workers=Executors.newFixedThreadPool(12);
    private final ExecutorService returns=Executors.newFixedThreadPool(12);
    private final Semaphore slots=new Semaphore(12);
    private final java.util.Set<Socket> sockets=java.util.Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    private ServerSocket server;
    public void select(Upstream upstream){active.set(upstream);}
    public void start()throws IOException{
        server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",PORT),12);
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
            headers(c.getInputStream());
            if(request.length!=3||!request[0].equals("CONNECT")||!request[1].endsWith(":443")){
                write(c.getOutputStream(),"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");return;
            }
            String host=request[1].substring(0,request[1].length()-4).toLowerCase(java.util.Locale.ROOT);
            Upstream selected=active.get();
            if(!permitted(host)||selected==null){
                write(c.getOutputStream(),"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n");return;
            }
            Socket remote;
            try{remote=connect(selected,host,5000);}
            catch(IOException e){write(c.getOutputStream(),"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");return;}
            sockets.add(remote);
            try(Socket r=remote){
                c.setSoTimeout(120000);r.setSoTimeout(120000);
                write(c.getOutputStream(),"HTTP/1.1 200 Connection established\r\n\r\n");
                Future<?> f=returns.submit(()->copy(r,c));
                copy(c,r);
                try{f.get(2,TimeUnit.SECONDS);}catch(Exception e){f.cancel(true);}
            }finally{sockets.remove(remote);}
        }catch(IOException|RuntimeException ignored){}
    }
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
