package org.tvbox.panadonikproxy;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Loopback-only mock HTTP CONNECT proxy; never contacts an external proxy. */
public final class GatewayIntegrationTest {
    private static void require(boolean value)throws Exception{if(!value)throw new AssertionError();}
    private static final class Mock implements Closeable {
        final ServerSocket server;
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        Mock()throws IOException{server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));worker.execute(()->{while(!server.isClosed())try{handle(server.accept());}catch(IOException e){break;}});}
        void handle(Socket socket){try(Socket s=socket){
            Gateway.line(s.getInputStream());Gateway.headers(s.getInputStream());
            Gateway.write(s.getOutputStream(),"HTTP/1.1 200 OK\r\n\r\n");
            byte[] data=new byte[4];new DataInputStream(s.getInputStream()).readFully(data);s.getOutputStream().write(data);s.getOutputStream().flush();
        }catch(IOException ignored){}}
        public void close()throws IOException{server.close();worker.shutdownNow();}
    }
    private static Socket connect(Gateway gateway,boolean auth)throws Exception{
        Socket client=new Socket("127.0.0.1",gateway.port());
        String header=auth?"Proxy-Authorization: Panadonik "+gateway.token()+"\r\n":"";
        Gateway.write(client.getOutputStream(),"CONNECT www.youtube.com:443 HTTP/1.1\r\nHost: www.youtube.com:443\r\n"+header+"\r\n");
        return client;
    }
    public static void main(String[] args)throws Exception{
        Gateway gateway=new Gateway();gateway.start();
        try(Mock first=new Mock();Mock second=new Mock()){
            gateway.select(Gateway.Upstream.forTest("http","127.0.0.1",first.server.getLocalPort()));
            try(Socket rejected=connect(gateway,false)){require(Gateway.line(rejected.getInputStream()).contains("407"));}
            try(Socket client=connect(gateway,true)){
                require(Gateway.line(client.getInputStream()).contains("200"));Gateway.headers(client.getInputStream());
                client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
                byte[] result=new byte[4];new DataInputStream(client.getInputStream()).readFully(result);
                require("PING".equals(new String(result,StandardCharsets.US_ASCII)));
            }
            gateway.select(Gateway.Upstream.forTest("http","127.0.0.1",second.server.getLocalPort()));
            try(Socket client=connect(gateway,true)){require(Gateway.line(client.getInputStream()).contains("200"));}
        }finally{gateway.close();}
        System.out.println("Gateway auth, tunnel and failover: PASS");
    }
}
