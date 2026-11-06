package org.tvbox.power;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF0101418);
        root.setPadding(0, 24, 0, 24);

        TextView title = new TextView(this);
        title.setText("Питание");
        title.setTextSize(40);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        root.addView(title);

        Button off = button("⏻  Выключить", new View.OnClickListener() {
            public void onClick(View v) { shutdown(); }
        });
        // Draw the power glyph ourselves; the system font on this Android TV build lacks the ⏻ character.
        off.setText("Выключить");
        off.setCompoundDrawablesWithIntrinsicBounds(new PowerGlyph(), null, null, null);
        off.setCompoundDrawablePadding(dp(18));
        root.addView(off);
        root.addView(button("⟳  Перезагрузить", new View.OnClickListener() {
            public void onClick(View v) { reboot(); }
        }));
        root.addView(button("⟳  Перезагрузка в recovery", new View.OnClickListener() {
            public void onClick(View v) { rebootRecovery(); }
        }));
        if (bootedFromSd()) root.addView(button("⟳  Перезагрузить с внутренней памяти", new View.OnClickListener() {
            public void onClick(View v) { rebootEmmc(); }
        }));
        root.addView(button("☾  Спящий режим", new View.OnClickListener() {
            public void onClick(View v) { sleep(); }
        }));
        root.addView(button("Отмена", new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        }));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF101418);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        off.requestFocus();
    }

    private Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(28);
        btn.setTextColor(Color.WHITE);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(0xFF2E7D32));
        bg.addState(new int[]{android.R.attr.state_pressed}, round(0xFF1B5E20));
        bg.addState(new int[]{}, round(0xFF263238));
        btn.setBackground(bg);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(720, 120);
        lp.setMargins(0, 14, 0, 14);
        btn.setLayoutParams(lp);
        return btn;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(24);
        return d;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Material-style power symbol, independent of device fonts and icon packs. */
    private final class PowerGlyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PowerGlyph() {
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.centerX(), cy = b.top + b.height() * 0.60f, r = b.width() * 0.34f;
            RectF circle = new RectF(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(circle, 315, 270, false, paint);
            canvas.drawLine(cx, b.top + b.height() * 0.08f, cx, cy - r * 0.05f, paint);
        }
        @Override public int getIntrinsicWidth() { return dp(48); }
        @Override public int getIntrinsicHeight() { return dp(48); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void shutdown() {
        try {
            Intent i = new Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN");
            i.putExtra("android.intent.extra.KEY_CONFIRM", false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void reboot() {
        try {
            ((PowerManager) getSystemService(POWER_SERVICE)).reboot(null);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void rebootRecovery() {
        try {
            ((PowerManager) getSystemService(POWER_SERVICE)).reboot("recovery");
        } catch (Exception e) {
            fail(e);
        }
    }

    private static boolean bootedFromSd() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cmdline"))) {
            return r.readLine().contains("androidboot.boot_devices=ff500000");
        } catch (Exception e) { return false; }
    }

    /** One boot from the eMMC with the SD left inserted: tvboxd REBOOT_EMMC checks, prepares the SD and reboots. */
    private void rebootEmmc() {
        Toast.makeText(this, "Проверка внутренней памяти…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                String done = "DONE fail служба tvboxd недоступна";
                try {
                    LocalSocket s = new LocalSocket();
                    s.connect(new LocalSocketAddress("tvboxd"));
                    new PrintWriter(s.getOutputStream(), true).println("REBOOT_EMMC");
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    for (String l; (l = r.readLine()) != null; ) if (l.startsWith("DONE ")) done = l;
                    s.close();
                } catch (Exception e) { done = "DONE fail " + e.getMessage(); }
                final String msg = done.startsWith("DONE ok") ? "Перезагрузка с внутренней памяти…" : "Не получилось: " + done.substring(done.indexOf(' ', 5) + 1);
                runOnUiThread(new Runnable() {
                    public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
                });
            }
        }).start();
    }

    private void sleep() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            Method m = PowerManager.class.getMethod("goToSleep", long.class);
            m.invoke(pm, SystemClock.uptimeMillis());
            finish();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Exception e) {
        Toast.makeText(this, "Ошибка: " + e, Toast.LENGTH_LONG).show();
    }
}
