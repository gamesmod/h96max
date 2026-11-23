package org.tvbox.voicebridge;

import java.util.Locale;

/** A deliberately small command vocabulary; never evaluates text as shell or an intent URI. */
public final class VoiceCommand {
    public enum Kind { OPEN, PLAY, PAUSE, NEXT, PREVIOUS, LOUDER, QUIETER, MUTE, UNMUTE, HOME, TIME, UNKNOWN }
    public final Kind kind;
    public final String app;

    private VoiceCommand(Kind kind, String app) { this.kind = kind; this.app = app; }

    public static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    public static VoiceCommand parse(String text) {
        String n = normalize(text);
        Kind k;
        switch (n) {
            case "пауза": case "поставь на паузу": case "останови воспроизведение": k = Kind.PAUSE; break;
            case "продолжи": case "продолжить": case "продолжи воспроизведение": k = Kind.PLAY; break;
            case "следующий трек": case "следующее видео": k = Kind.NEXT; break;
            case "предыдущий трек": case "предыдущее видео": k = Kind.PREVIOUS; break;
            case "громче": case "погромче": case "сделай громче": k = Kind.LOUDER; break;
            case "тише": case "потише": case "сделай тише": k = Kind.QUIETER; break;
            case "выключи звук": case "без звука": k = Kind.MUTE; break;
            case "включи звук": case "верни звук": k = Kind.UNMUTE; break;
            case "домой": case "на главный экран": k = Kind.HOME; break;
            case "который час": case "сколько времени": k = Kind.TIME; break;
            default:
                for (String prefix : new String[]{"открой ", "запусти "}) {
                    if (n.startsWith(prefix) && n.length() > prefix.length()) {
                        return new VoiceCommand(Kind.OPEN, n.substring(prefix.length()));
                    }
                }
                k = Kind.UNKNOWN;
        }
        return new VoiceCommand(k, "");
    }
}
