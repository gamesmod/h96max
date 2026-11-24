package org.tvbox.smarttubeproxy;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private TextView status;
    private EditText media;
    private final Handler handler=new Handler();
    private final Runnable update=new Runnable(){
        @Override public void run(){
            status.setText(ProxyService.summary+"\nАктивный: "+ProxyService.selected+
                "\nНайдено: "+ProxyService.found+"   Проверено: "+ProxyService.checked+"   Рабочих: "+ProxyService.working+
                "\n\nИсточники:\n"+ProxyService.sources);
            handler.postDelayed(this,1000);
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(36,24,36,24);
        scroll.addView(layout);setContentView(scroll);
        TextView title=new TextView(this);title.setText("Прокси SmartTube");title.setTextSize(28);layout.addView(title);
        TextView warning=new TextView(this);warning.setText("Публичные прокси ненадёжны. Используйте без входа в Google. TLS не отключается. В SmartTube настройте HTTP 127.0.0.1:18765. Для проверки нужна свежая медиассылка без учётных данных.");
        warning.setTextSize(18);layout.addView(warning);
        media=new EditText(this);media.setSingleLine(true);media.setHint("HTTPS медиассылка googlevideo.com");media.setText(ProxyService.media);
        media.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);layout.addView(media);
        Button start=new Button(this);start.setText("Запустить / обновить");layout.addView(start);
        start.setOnClickListener(v->{
            String url=media.getText().toString().trim();
            if(!url.isEmpty()&&!ProxyChecker.validMedia(url)){Toast.makeText(this,"Некорректная медиассылка",Toast.LENGTH_LONG).show();return;}
            ProxyService.media=url;
            startService(new Intent(this,ProxyService.class).setAction("refresh"));
        });
        Button stop=new Button(this);stop.setText("Остановить и отключить шлюз");layout.addView(stop);
        stop.setOnClickListener(v->{stopService(new Intent(this,ProxyService.class));ProxyService.media="";media.setText("");});
        status=new TextView(this);status.setTextSize(18);layout.addView(status);
        start.requestFocus();
    }
    @Override protected void onResume(){super.onResume();handler.post(update);}
    @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
}
