package org.tvbox.panadonikproxy;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

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
        }else if(format.equals("json")){
            Object root=new JSONTokener(raw).nextValue();
            JSONArray arr=root instanceof JSONArray?(JSONArray)root:
                ((JSONObject)root).optJSONArray("proxies")!=null?((JSONObject)root).getJSONArray("proxies"):
                ((JSONObject)root).getJSONArray("data");
            for(int i=0;i<arr.length();i++){
                Object value=arr.get(i);
                if(value instanceof String){lines.add((String)value);continue;}
                JSONObject item=(JSONObject)value;
                String type=item.optString("protocol",item.optString("type",protocol)).toLowerCase(Locale.ROOT);
                JSONArray types=item.optJSONArray("protocols");
                boolean matches=type.equals(protocol);
                if(types!=null)for(int j=0;j<types.length();j++)matches|=protocol.equals(types.optString(j).toLowerCase(Locale.ROOT));
                if(matches)lines.add(item.optString("ip",item.optString("host"))+":"+item.optInt("port"));
            }
        }else if(format.equals("csv")){
            String[] rows=raw.split("\\r?\\n");
            if(rows.length>1){
                List<String> header=csv(rows[0]);
                int ip=column(header,"ip","host"),port=column(header,"port"),type=column(header,"protocol","type","ptype"),endpoint=column(header,"proxy");
                for(int i=1;i<rows.length;i++){
                    List<String> row=csv(rows[i]);
                    if(type>=0&&type<row.size()&&!protocol.equals(row.get(type).trim().toLowerCase(Locale.ROOT)))continue;
                    if(endpoint>=0&&endpoint<row.size()){lines.add(row.get(endpoint).trim());continue;}
                    if(ip<0||port<0||ip>=row.size()||port>=row.size())continue;
                    lines.add(row.get(ip).trim()+":"+row.get(port).trim());
                }
            }
        }else if(format.equals("html")){
            Matcher row=Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(raw);
            Pattern cell=Pattern.compile("(?is)<t[dh][^>]*>\\s*([^<]+?)\\s*</t[dh]>");
            while(row.find()){
                Matcher c=cell.matcher(row.group(1));List<String> cells=new ArrayList<>();
                while(c.find())cells.add(c.group(1).replace("&nbsp;","").trim());
                if(cells.size()>=2&&cells.get(0).matches("[0-9.]+")&&cells.get(1).matches("[0-9]+"))lines.add(cells.get(0)+":"+cells.get(1));
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
    private static List<String> csv(String line){
        List<String> out=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){
            char ch=line.charAt(i);
            if(ch=='\"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='\"'){value.append(ch);i++;}else quoted=!quoted;}
            else if(ch==','&&!quoted){out.add(value.toString());value.setLength(0);}else value.append(ch);
        }
        out.add(value.toString());return out;
    }
    private static int column(List<String> header,String... names){
        for(int i=0;i<header.size();i++)for(String name:names)if(name.equals(header.get(i).trim().toLowerCase(Locale.ROOT)))return i;
        return -1;
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
                if(!source.optBoolean("enabled",true))continue;
                JSONArray allowed=source.optJSONArray("protocols");
                for(String protocol:new String[]{"http","https","socks4","socks5"}){
                    boolean supported=allowed==null;
                    if(allowed!=null)for(int j=0;j<allowed.length();j++)supported|=protocol.equals(allowed.optString(j));
                    if(supported)jobs.add(pool.submit(()->load(context,source,protocol)));
                }
            }
            for(Future<Snapshot> job:jobs){
                try{
                    Snapshot result=job.get();
                    for(Gateway.Upstream p:result.proxies)unique.put(p.toString(),p);
                    states.append(result.states).append('\n');
                }catch(ExecutionException e){states.append("Ошибка источника\n");}
            }
            File manual=new File(context.getFilesDir(),"manual-proxies.txt");
            if(manual.isFile()){
                String raw;try(InputStream in=new FileInputStream(manual)){raw=new String(read(in,262144),"UTF-8");}
                for(String protocol:new String[]{"http","https","socks4","socks5"})
                    for(Gateway.Upstream p:parse(raw,protocol,"txt"))unique.put(p.toString(),p);
                states.append("manual локальный список\n");
            }
            Set<String> deny=policy(context,"proxy-denylist.txt"),allow=policy(context,"proxy-allowlist.txt");
            unique.entrySet().removeIf(e->deny.contains(e.getKey())||(!allow.isEmpty()&&!allow.contains(e.getKey())));
        }finally{pool.shutdownNow();}
        return new Snapshot(new ArrayList<>(unique.values()),states.toString());
    }
    private static Set<String> policy(Context context,String name)throws IOException{
        File file=new File(context.getFilesDir(),name);Set<String> values=new HashSet<>();
        if(!file.isFile())return values;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"))){
            String line;while((line=reader.readLine())!=null){line=line.trim();if(!line.isEmpty()&&!line.startsWith("#"))values.add(line);}
        }
        return values;
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
                if(list.size()>128)list=new ArrayList<>(list.subList(0,128));
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
