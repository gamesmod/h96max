package org.tvbox.voicebridge;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Системная голосовая кнопка шлёт WEB_SEARCH/VOICE_ASSIST сюда.
 * Сразу стартуем ListeningService и закрываем trampoline — UI Dicio не показываем.
 * Не держим Activity дольше onCreate: FGS обязан вызвать startForeground сам.
 */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешите микрофон приложению «Голосовой пульт» в настройках",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Intent service = new Intent(this, ListeningService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                getClass().getMethod("startForegroundService", Intent.class).invoke(this, service);
            } else {
                startService(service);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось включить голосовой пульт", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }
}
