package org.tvbox.panadonikproxy;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;

/** TLS with default trust and HTTPS hostname verification. No cookies or credentials. */
final class ProxyChecker {
    static final class Result {
        final String status; final double mbps; final long ms;
        Result(String status,double mbps,long ms){this.status=status;this.mbps=mbps;this.ms=ms;}
        boolean usable(){return status.equals("WORKING")||status.equals("STABLE");}
    }
    static boolean validMedia(String value){
        try{
            URL u=new URL(value);
            return u.getProtocol().equals("https") && u.getHost().endsWith(".googlevideo.com") &&
                u.getPath().equals("/videoplayback") && u.getQuery()!=null && u.getUserInfo()==null &&
                u.getRef()==null && (u.getPort()==-1||u.getPort()==443) && !value.matches("(?s).*[\\r\\n ].*");
        }catch(Exception e){return false;}
    }
    static Result probe(Gateway.Upstream p){
        long sum=0;
        try{
            for(String host:new String[]{"www.youtube.com","youtubei.googleapis.com","redirector.googlevideo.com"}){
                Result r=request(p,new URL("https://"+host+"/"),false,5000,0);
                if(!r.status.equals("REACHABLE"))return r;
                sum+=r.ms;
            }
            return new Result("REACHABLE",0,sum/3);
        }catch(Exception e){return new Result("UNREACHABLE",0,0);}
    }
    static Result check(Gateway.Upstream p,String media,int timeout,double min){
        try{
            for(String host:new String[]{"www.youtube.com","youtubei.googleapis.com","i.ytimg.com"}){
                Result r=request(p,new URL("https://"+host+"/"),false,timeout,min);
                if(!r.status.equals("REACHABLE"))return r;
            }
            if(!validMedia(media))return new Result("UNKNOWN",0,0);
            Result r=request(p,new URL(media),true,timeout,min);
            if(r.usable()){
                Result again=request(p,new URL(media),true,timeout,min);
                if(again.usable())return new Result("STABLE",Math.min(r.mbps,again.mbps),Math.max(r.ms,again.ms));
            }
            return r;
        }catch(Exception e){return new Result("UNREACHABLE",0,0);}
    }
    private static Result request(Gateway.Upstream p,URL u,boolean media,int timeout,double min){
        long start=System.nanoTime();
        try(Socket raw=Gateway.connect(p,u.getHost(),5000)){
            raw.setSoTimeout(timeout);
            SSLSocketFactory factory=(SSLSocketFactory)SSLSocketFactory.getDefault();
            try(SSLSocket tls=(SSLSocket)factory.createSocket(raw,u.getHost(),443,true)){
                SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);
                tls.startHandshake();
                Gateway.write(tls.getOutputStream(),(media?"GET ":"HEAD ")+u.getFile()+" HTTP/1.1\r\nHost: "+u.getHost()+
                    "\r\nConnection: close\r\nAccept-Encoding: identity\r\n"+(media?"Range: bytes=0-262143\r\n":"")+"\r\n");
                InputStream in=tls.getInputStream();
                String statusLine=Gateway.line(in);
                if(!statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))return new Result("PROTOCOL_ERROR",0,0);
                int status=Integer.parseInt(statusLine.substring(9,12));
                Map<String,String> headers=new HashMap<>();
                boolean ended=false;
                for(int i=0;i<64;i++){
                    String line=Gateway.line(in);
                    if(line.isEmpty()){ended=true;break;}
                    int at=line.indexOf(':');
                    if(at>0)headers.put(line.substring(0,at).toLowerCase(Locale.ROOT),line.substring(at+1).trim());
                }
                if(!ended)return new Result("PROTOCOL_ERROR",0,0);
                long latency=(System.nanoTime()-start)/1000000;
                if(!media){
                    boolean ok=u.getHost().equals("www.youtube.com") ? status>=200&&status<400 :
                        (status>=200&&status<400)||status==400||status==404||status==405;
                    return new Result(ok?"REACHABLE":"YOUTUBE_BLOCKED",0,latency);
                }
                String mime=headers.containsKey("content-type")?headers.get("content-type"):"";
                String range=headers.containsKey("content-range")?headers.get("content-range"):"";
                if(status!=206||!(mime.startsWith("video/")||mime.startsWith("audio/"))||
                    !range.matches("bytes 0-[0-9]+/[0-9]+")||headers.containsKey("transfer-encoding"))
                    return new Result("CDN_BLOCKED",0,latency);
                int length=Integer.parseInt(headers.get("content-length"));
                if(length<65536||length>262144)return new Result("CDN_BLOCKED",0,latency);
                byte[] buf=new byte[8192];int total=0;
                long bodyStart=System.nanoTime(), deadline=bodyStart+timeout*1000000L;
                while(total<length && System.nanoTime()<deadline){
                    int n=in.read(buf,0,Math.min(buf.length,length-total));if(n<0)break;total+=n;
                }
                double mbps=total*8000.0/Math.max(1,System.nanoTime()-bodyStart);
                return new Result(total!=length?"CDN_BLOCKED":mbps<min?"SLOW":"WORKING",mbps,latency);
            }
        }catch(SSLException e){return new Result("TLS_ERROR",0,0);}
        catch(SocketTimeoutException|ConnectException e){return new Result("UNREACHABLE",0,0);}
        catch(IOException|RuntimeException e){return new Result("PROTOCOL_ERROR",0,0);}
    }
}
