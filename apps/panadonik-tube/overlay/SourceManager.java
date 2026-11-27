package org.tvbox.panadonikproxy;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

final class SourceManager {
    static final class Snapshot {
        final List<Gateway.Upstream> proxies; final String states;
        Snapshot(List<Gateway.Upstream> p,String s){proxies=p;states=s;}
    }
    private static byte[] read(InputStream in,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("too large");out.write(b,0,n);}
        return out.toByteArray();
    }
    private static String fetch(String url)throws IOException{
        URL u=new URL(url);if(!u.getProtocol().equals("https"))throw new IOException("HTTPS required");
        HttpURLConnection c=(HttpURLConnection)u.openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
        try{
            if(c.getResponseCode()!=200)throw new IOException("source unavailable");
            try(InputStream in=c.getInputStream()){return new String(read(in,2097152),"UTF-8");}
        }finally{c.disconnect();}
    }
    static List<Gateway.Upstream> parse(String raw,String protocol,String format)throws JSONException{
        List<String> lines=new ArrayList<>();
        if(format.equals("geonode")){
            JSONArray arr=new JSONObject(raw).getJSONArray("data");
            for(int i=0;i<arr.length();i++){
                JSONObject item=arr.getJSONObject(i);JSONArray protocols=item.optJSONArray("protocols");
                if(protocols==null)continue;
                for(int j=0;j<protocols.length();j++)if(protocol.equals(protocols.optString(j))){
                    lines.add(item.getString("ip")+":"+item.getInt("port"));break;
                }
            }
        }else if(format.equals("txt"))lines=Arrays.asList(raw.split("\\r?\\n"));
        else throw new JSONException("unknown format");
        List<Gateway.Upstream> out=new ArrayList<>();
        for(String text:lines){
            String s=text.trim();if(s.contains("://")){
                if(!s.startsWith(protocol+"://"))continue;s=s.substring(protocol.length()+3);
            }
            int at=s.lastIndexOf(':');if(at<1)continue;
            try{out.add(new Gateway.Upstream(protocol,s.substring(0,at),Integer.parseInt(s.substring(at+1))));}
            catch(IllegalArgumentException ignored){}
        }
        return out;
    }
    static Snapshot collect(Context context)throws Exception{
        File override=new File(context.getFilesDir(),"sources.json");
        String config;
        try(InputStream in=override.isFile()?new FileInputStream(override):context.getAssets().open("sources.json")){
            config=new String(read(in,262144),"UTF-8");
        }
        JSONArray sources=new JSONArray(config);
        if(sources.length()>32)throw new IOException("too many sources");
        ExecutorService pool=Executors.newFixedThreadPool(8);
        Map<String,Gateway.Upstream> unique=new LinkedHashMap<>();
        StringBuilder states=new StringBuilder();
        List<Future<Snapshot>> jobs=new ArrayList<>();
        try{
            for(int i=0;i<sources.length();i++){
                JSONObject source=sources.getJSONObject(i);
                for(String protocol:new String[]{"socks5","http"})jobs.add(pool.submit(()->load(context,source,protocol)));
            }
            for(Future<Snapshot> job:jobs){
                try{
                    Snapshot result=job.get();
                    for(Gateway.Upstream p:result.proxies)unique.put(p.toString(),p);
                    states.append(result.states).append('\n');
                }catch(ExecutionException e){states.append("Ошибка источника\n");}
            }
        }finally{pool.shutdownNow();}
        return new Snapshot(new ArrayList<>(unique.values()),states.toString());
    }
    private static Snapshot load(Context context,JSONObject source,String protocol)throws Exception{
        String id=source.getString("id"),format=source.getString("format");
        if(!id.matches("[a-z0-9_-]{1,40}"))throw new IOException("invalid source id");
        File cache=new File(context.getCacheDir(),id+"-"+protocol+".txt");
        JSONArray urls=source.getJSONArray("urls");
        for(int i=0;i<Math.min(urls.length(),4);i++){
            try{
                String raw=fetch(urls.getString(i).replace("{protocol}",protocol));
                List<Gateway.Upstream> list=parse(raw,protocol,format);
                if(list.isEmpty())continue;
                android.util.AtomicFile file=new android.util.AtomicFile(cache);
                FileOutputStream out=file.startWrite();
                try{out.write(raw.getBytes("UTF-8"));file.finishWrite(out);}catch(IOException e){file.failWrite(out);}
                return new Snapshot(list,id+" "+protocol+": "+list.size());
            }catch(Exception ignored){}
        }
        if(System.currentTimeMillis()-cache.lastModified()>3600000L)return new Snapshot(Collections.emptyList(),id+" "+protocol+": недоступен");
        try(InputStream in=new android.util.AtomicFile(cache).openRead()){
            List<Gateway.Upstream> list=parse(new String(read(in,2097152),"UTF-8"),protocol,format);
            return new Snapshot(list,id+" "+protocol+": кеш "+list.size());
        }catch(Exception e){return new Snapshot(Collections.emptyList(),id+" "+protocol+": недоступен");}
    }
}
