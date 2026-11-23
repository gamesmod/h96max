package org.tvbox.voicebridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * Одна ограниченная сессия микрофона. Без audio focus, wake word и полноэкранного UI.
 * startForeground вызывается до любых early-exit: иначе Android 12 убивает FGS после
 * startForegroundService из MainActivity. Повтор WEB_SEARCH до ready игнорируется;
 * после ready — отмена (как повторное нажатие кнопки).
 */
public final class ListeningService extends Service {
    private static final String CHANNEL = "voice_control";
    private static final int NOTIFICATION = 1;
    private static final int FGS_TYPE_MICROPHONE = 128; // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private static final ComponentName DICIO = new ComponentName("org.stypox.dicio",
            "org.stypox.dicio.io.input.stt_service.SttService");
    private final Handler handler = new Handler();
    private final Runnable timeout = new Runnable() {
        @Override public void run() { end("Время ожидания команды истекло"); }
    };
    private final Runnable recordingCheck = new Runnable() {
        @Override public void run() {
            if (!active || ready) return;
            List<?> configurations = recordingConfigurations();
            if (configurations != null) {
                for (Object configuration : configurations) {
                    try {
                        int source = ((Number) configuration.getClass()
                                .getMethod("getClientAudioSource").invoke(configuration)).intValue();
                        if (source == 6 /* MediaRecorder.AudioSource.VOICE_RECOGNITION */) {
                            markReady();
                            return;
                        }
                    } catch (Exception ignored) { }
                }
            }
            handler.postDelayed(this, 250);
        }
    };
    private SpeechRecognizer recognizer;
    private TextView indicator;
    private boolean active;
    private boolean ready;
    private boolean foreground;
    private long generation;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Обязательный FGS-вход до любых return: MainActivity уже вызвала startForegroundService.
        ensureForeground();
        if (intent != null && "STOP".equals(intent.getAction())) {
            end(null);
            return START_NOT_STICKY;
        }
        if (active) {
            // Повтор до ready — дубль системного WEB_SEARCH, не рвём bind Dicio.
            if (!ready) return START_NOT_STICKY;
            end(null); // повторное нажатие после ready — отмена
            return START_NOT_STICKY;
        }
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                end("Нет разрешения микрофона у голосового пульта");
                return START_NOT_STICKY;
            }
            getPackageManager().getServiceInfo(DICIO, 0);
            List<?> existingRecordings = recordingConfigurations();
            if (existingRecordings != null && !existingRecordings.isEmpty()) {
                end("Микрофон уже используется другим приложением");
                return START_NOT_STICKY;
            }
            active = true;
            generation++;
            showIndicator();
            recognizer = SpeechRecognizer.createSpeechRecognizer(this, DICIO);
            recognizer.setRecognitionListener(new Listener(generation));
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            handler.postDelayed(timeout, 30000); // холодный старт модели; после ready — 15 с
            recognizer.startListening(request);
            handler.postDelayed(recordingCheck, 250);
        } catch (PackageManager.NameNotFoundException e) {
            end("Установите Dicio и русскую модель распознавания");
        } catch (RuntimeException e) {
            end("Не удалось включить распознавание");
        }
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        if (foreground) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
            return;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                getClass().getMethod("startForeground", int.class, Notification.class, int.class)
                        .invoke(this, NOTIFICATION, n, FGS_TYPE_MICROPHONE);
                foreground = true;
                return;
            } catch (Exception ignored) { }
        }
        startForeground(NOTIFICATION, n);
        foreground = true;
    }

    // Android 24+ anonymized recording configurations without recording audio here.
    // Dicio шлёт speech-callback после слов; для индикатора «готов» этого мало.
    private List<?> recordingConfigurations() {
        if (Build.VERSION.SDK_INT < 24) return null;
        try {
            Object audio = getSystemService(Context.AUDIO_SERVICE);
            return (List<?>) audio.getClass().getMethod("getActiveRecordingConfigurations").invoke(audio);
        } catch (Exception e) { return null; }
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> type = Class.forName("android.app.NotificationChannel");
                Object channel = type.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Голосовой пульт", 2);
                type.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class)
                        .invoke(channel, null, null);
                getSystemService(NotificationManager.class).getClass()
                        .getMethod("createNotificationChannel", type).invoke(getSystemService(NotificationManager.class), channel);
            } catch (Exception e) { throw new IllegalStateException("Notification channel unavailable", e); }
        }
        PendingIntent cancel = PendingIntent.getService(this, 0,
                new Intent(this, ListeningService.class).setAction("STOP"),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */);
        Notification.Builder builder = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) {
            try { builder.getClass().getMethod("setChannelId", String.class).invoke(builder, CHANNEL); }
            catch (Exception e) { throw new IllegalStateException("Notification channel unavailable", e); }
        }
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(ready ? "Слушаю команду…" : "Готовлю микрофон…")
                .setContentText("Повторное нажатие — отмена")
                .setContentIntent(cancel).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void showIndicator() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Готовлю микрофон…", Toast.LENGTH_SHORT).show();
            return;
        }
        TextView view = new TextView(this);
        view.setText("●  Готовлю микрофон…");
        view.setTextSize(18);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(0xE6203038);
        view.setPadding(20, 10, 20, 10);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? 2038 /* TYPE_APPLICATION_OVERLAY */ : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.x = 24; params.y = 24;
        params.alpha = 0.7f; // ниже порога untrusted-overlay touch obscuring на Android 12
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, params);
            indicator = view;
        } catch (RuntimeException e) {
            Toast.makeText(this, "Готовлю микрофон…", Toast.LENGTH_SHORT).show();
        }
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(recordingCheck);
        handler.postDelayed(timeout, 15000);
        if (indicator != null) indicator.setText("●  Слушаю…");
        else Toast.makeText(this, "Слушаю команду…", Toast.LENGTH_SHORT).show();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
    }

    private void release() {
        active = false;
        ready = false;
        generation++; // поздние callback отменённой сессии не трогают следующую
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(recordingCheck);
        SpeechRecognizer previous = recognizer;
        recognizer = null;
        if (previous != null) {
            try { previous.cancel(); } catch (RuntimeException ignored) { }
            try { previous.destroy(); } catch (RuntimeException ignored) { }
        }
        if (indicator != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(indicator); }
            catch (RuntimeException ignored) { }
            indicator = null;
        }
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
    }

    private void end(String message) {
        release();
        if (message != null) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    @Override public void onDestroy() { release(); super.onDestroy(); }

    private final class Listener implements RecognitionListener {
        private final long session;
        Listener(long session) { this.session = session; }
        private boolean current() { return active && session == generation; }
        @Override public void onReadyForSpeech(Bundle p) { if (current()) markReady(); }
        // Dicio v4.2: beginning/partial есть, readyForSpeech часто нет.
        @Override public void onBeginningOfSpeech() { if (current()) markReady(); }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int type, Bundle p) { }
        @Override public void onPartialResults(Bundle results) { if (current()) markReady(); }
        @Override public void onError(int error) {
            if (!current()) return;
            String message = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    ? "Разрешите микрофон Dicio и голосовому пульту"
                    : error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? "Микрофон занят"
                    : "Не удалось распознать команду";
            end(message);
        }
        @Override public void onResults(Bundle results) {
            if (!current()) return;
            ArrayList<String> phrases = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            VoiceCommand command = VoiceCommand.parse(phrases == null || phrases.isEmpty() ? null : phrases.get(0));
            release(); // отпускаем mic до переключения приложения / media-команды
            String message;
            try { message = CommandExecutor.execute(getApplicationContext(), command); }
            catch (RuntimeException e) { message = "Не удалось выполнить команду"; }
            end(message);
        }
    }
}
