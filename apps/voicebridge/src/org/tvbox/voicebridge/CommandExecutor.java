package org.tvbox.voicebridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.view.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Executes a recognized command without opening any UI except an explicitly requested app. */
final class CommandExecutor {
    static String execute(Context context, VoiceCommand command) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        switch (command.kind) {
            case PAUSE: return media(audio, KeyEvent.KEYCODE_MEDIA_PAUSE);
            case PLAY: return media(audio, KeyEvent.KEYCODE_MEDIA_PLAY);
            case NEXT: return media(audio, KeyEvent.KEYCODE_MEDIA_NEXT);
            case PREVIOUS: return media(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case LOUDER: audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); return "Громче";
            case QUIETER: audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); return "Тише";
            case MUTE: audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); return "Звук выключен";
            case UNMUTE: audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); return "Звук включён";
            case HOME:
                context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return "Главный экран";
            case TIME: return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
            case OPEN: return open(context, command.app);
            default: return "Команда не распознана";
        }
    }

    private static String media(AudioManager audio, int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return "Команда плееру отправлена";
    }

    private static String open(Context context, String name) {
        String wanted = name;
        if (name.equals("влс") || name.equals("ви эл си")) wanted = "vlc";
        PackageManager pm = context.getPackageManager();
        Map<String, Intent> matches = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (wanted.equals(VoiceCommand.normalize(info.loadLabel(pm).toString()))) {
                    matches.put(info.activityInfo.packageName, new Intent(query)
                            .setClassName(info.activityInfo.packageName, info.activityInfo.name));
                }
            }
        }
        if (matches.isEmpty()) return "Приложение не найдено";
        if (matches.size() > 1) return "Несколько приложений с таким именем";
        context.startActivity(matches.values().iterator().next().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Открываю приложение";
    }
}
