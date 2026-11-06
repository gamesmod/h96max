package org.tvbox.iptv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Запускает лёгкую однократную индексацию плейлиста после старта Android. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent service = new Intent(context, IndexOnBootService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            try { Context.class.getMethod("startForegroundService", Intent.class).invoke(context, service); }
            catch (Exception e) { context.startService(service); }
        } else context.startService(service);
    }
}
