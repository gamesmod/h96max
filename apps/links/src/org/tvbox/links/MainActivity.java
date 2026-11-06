package org.tvbox.links;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import io.nayuki.qrcodegen.QrCode;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** One full-size QR at a time, with explicit service and network address selection. */
public class MainActivity extends Activity {
    static final String[][] SERVICES = {
        {"TorrServer", "8090", ""},
        {"VLC — удалённый доступ", "8080", "org.videolan.vlc"},
        {"Total Commander", "", "com.ghisler.android.TotalCommander"}
    };
    final List<String[]> addresses = new ArrayList<>(), services = new ArrayList<>();
    Spinner network, service;
    ImageView image;
    TextView url, hint;
    Button launch;
    volatile int scanId;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(24), dp(12), dp(24), dp(12));
        root.addView(text("Ссылки на приставку", 28, Color.WHITE));
        hint = text("Выберите сеть телефона и службу. QR открывает адрес в браузере.", 17, 0xFFB0BEC5);
        root.addView(hint);
        LinearLayout selectors = new LinearLayout(this);
        network = new Spinner(this); service = new Spinner(this);
        selectors.addView(network, new LinearLayout.LayoutParams(0, dp(56), 1));
        selectors.addView(service, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(selectors, new LinearLayout.LayoutParams(-1, -2));
        launch = new Button(this);
        launch.setText("Запустить приложение на приставке");
        launch.setVisibility(View.GONE);
        root.addView(launch, new LinearLayout.LayoutParams(-1, dp(52)));
        launch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { launchSelectedApp(); }
        });
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER);
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        url = text("", 26, 0xFF4FC3F7);
        url.setTextIsSelectable(true);
        root.addView(url);
        TextView help = text("Телефон должен видеть выбранную сеть. Для TorrServer укажите этот адрес как сервер. Total Commander сам покажет QR для Wi‑Fi Transfer.", 16, 0xFFB0BEC5);
        root.addView(help);
        setContentView(root);
        AdapterView.OnItemSelectedListener change = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int p, long id) { refresh(); }
            public void onNothingSelected(AdapterView<?> a) { }
        };
        network.setOnItemSelectedListener(change); service.setOnItemSelectedListener(change);
        image.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int a, int b, int c, int d, int e, int f, int g, int h) {
                if(c-a != g-e || d-b != h-f) refresh();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        final int id = ++scanId;
        new Thread(new Runnable() { public void run() {
            final List<String[]> found = findAddresses();
            runOnUiThread(new Runnable() { public void run() {
                if (isFinishing() || scanId != id) return;
                addresses.clear(); addresses.addAll(found);
                services.clear(); Collections.addAll(services, SERVICES);
                List<String> names = new ArrayList<>();
                for(String[] a:addresses) names.add(a[0]+" · "+a[1]);
                network.setAdapter(adapter(names));
                names = new ArrayList<>(); for(String[] s:services) names.add(s[0]);
                service.setAdapter(adapter(names));
                if(addresses.isEmpty()) hint.setText("Нет доступного IPv4-адреса. Подключите приставку к сети.");
                refresh(); network.requestFocus();
            } });
        } }).start();
    }

    @Override protected void onPause() { ++scanId; super.onPause(); }

    ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); return a;
    }

    void refresh() {
        int n=network.getSelectedItemPosition(), s=service.getSelectedItemPosition();
        if(n<0 || n>=addresses.size() || s<0 || s>=services.size()) return;
        final String host=addresses.get(n)[1], port=services.get(s)[1];
        final String pkg=services.get(s)[2];
        launch.setVisibility(pkg.isEmpty() ? View.GONE : View.VISIBLE);
        launch.setText("Запустить " + (pkg.equals("org.videolan.vlc") ? "VLC" : "Total Commander") + " на приставке");
        if (port.isEmpty()) {
            url.setText("Откройте в Total Commander → WiFi Transfer");
            image.setImageDrawable(null);
            hint.setText("Плагин Total Commander запустит передачу и покажет актуальный QR-код.");
            return;
        }
        final String link="http://"+host+":"+port+"/";
        url.setText(link);
        int size=Math.min(image.getWidth(),image.getHeight())-dp(16);
        if(size>0) {
            Bitmap bitmap=qr(link,size);
            // DENSITY_NONE prevents a second scale at non-mdpi TV density.
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            android.graphics.drawable.BitmapDrawable d=new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);
            d.setFilterBitmap(false); d.setAntiAlias(false);
            image.setImageDrawable(d);
        }
        final int id=++scanId;
        new Thread(new Runnable() { public void run() {
            final boolean up=listening(host,Integer.parseInt(port));
            runOnUiThread(new Runnable() { public void run() {
                if(isFinishing() || scanId!=id) return;
                hint.setText(up ? "Служба доступна по выбранному адресу. Наведите камеру на QR-код."
                        : "Служба по этому адресу не отвечает. Проверьте её запуск или выберите другой интерфейс.");
            } });
        } }).start();
    }

    /** Запускает выбранное локальное приложение, не подменяя его сетевой адрес ссылкой из QR. */
    void launchSelectedApp() {
        int s = service.getSelectedItemPosition();
        if (s < 0 || s >= services.size()) return;
        String pkg = services.get(s)[2];
        if (pkg.isEmpty()) return;
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) throw new IllegalStateException("no launch activity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            hint.setText(pkg.equals("org.videolan.vlc")
                    ? "VLC запущен на приставке. QR работает, если удалённый HTTP-интерфейс VLC доступен по порту 8080."
                    : "Total Commander запущен. Откройте в нём WiFi Transfer — плагин покажет QR с действующим адресом.");
        } catch (Exception e) {
            Toast.makeText(this, "Приложение не установлено: " + pkg, Toast.LENGTH_LONG).show();
        }
    }

    static Bitmap qr(String text, int pixels) {
        QrCode q=QrCode.encodeText(text,QrCode.Ecc.MEDIUM);
        int border=4, n=q.size+border*2, scale=Math.max(1,pixels/n);
        Bitmap bitmap=Bitmap.createBitmap(n*scale,n*scale,Bitmap.Config.ARGB_8888);
        int[] data=new int[n*scale*n*scale]; java.util.Arrays.fill(data,Color.WHITE);
        for(int y=0;y<q.size;y++) for(int x=0;x<q.size;x++) if(q.getModule(x,y))
            for(int dy=0;dy<scale;dy++) java.util.Arrays.fill(data,((y+border)*scale+dy)*n*scale+(x+border)*scale,
                    ((y+border)*scale+dy)*n*scale+(x+border+1)*scale,Color.BLACK);
        bitmap.setPixels(data,0,n*scale,0,0,n*scale,n*scale); return bitmap;
    }

    static boolean listening(String host,int port) {
        try(Socket socket=new Socket()) { socket.connect(new InetSocketAddress(host,port),800); return true; }
        catch(Exception e) { return false; }
    }

    static List<String[]> findAddresses() {
        List<String[]> out=new ArrayList<>();
        try {
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for(InetAddress a:Collections.list(ni.getInetAddresses()))
                    if(a instanceof Inet4Address && !a.isLinkLocalAddress()) out.add(new String[]{ni.getName(),a.getHostAddress()});
            }
            Collections.sort(out,new Comparator<String[]>() {
                public int compare(String[] a,String[] b) {
                    int wa=a[0].startsWith("wlan")?0:a[0].startsWith("eth")?1:2;
                    int wb=b[0].startsWith("wlan")?0:b[0].startsWith("eth")?1:2;
                    return wa!=wb?wa-wb:(a[0]+a[1]).compareTo(b[0]+b[1]);
                }
            });
        } catch(Exception ignored) { }
        return out;
    }

    TextView text(String s,int size,int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER); t.setTypeface(Typeface.DEFAULT); return t;
    }
    int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
}
