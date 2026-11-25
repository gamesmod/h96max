package org.tvbox.iptv;

import java.util.LinkedHashMap;
import java.util.Map;

/** Distinct, stable badges for IPTV categories. Country groups use their own leading flag. */
final class CategoryIcons {
    private static final Map<String, String> ICONS = new LinkedHashMap<String, String>();
    static {
        ICONS.put("Для взрослых", "🔞");
        ICONS.put("Авто", "🚗");
        ICONS.put("Государственные", "🏛️");
        ICONS.put("Кулинария", "🍳");
        ICONS.put("Культура", "🏺");
        ICONS.put("Музыка", "🎵");
        ICONS.put("Новости", "📰");
        ICONS.put("Новости · Экономика", "📈");
        ICONS.put("Общие", "🏠");
        ICONS.put("Отдых · Природа", "🌿");
        ICONS.put("Познавательные", "🔭");
        ICONS.put("Познавательные · Наука", "🧪");
        ICONS.put("Прочее", "🧩");
        ICONS.put("Путешествия", "🧳");
        ICONS.put("Развлекательные", "🎭");
        ICONS.put("Региональные", "📍");
        ICONS.put("Религиозные", "🕊️");
        ICONS.put("Спорт", "🏆");
        ICONS.put("Спорт · Автоспорт", "🏎️");
        ICONS.put("Спорт · Теннис", "🎾");
        ICONS.put("Спорт · Футбол", "⚽");
        ICONS.put("Спорт · Хоккей", "🏒");
        ICONS.put("Фильмы", "🎬");
        ICONS.put("Фильмы · Сериалы", "🎞️");
        ICONS.put("Юмор", "😂");
        ICONS.put("Детские", "🧸");
        ICONS.put("Радио", "📻");
    }

    private CategoryIcons() { }

    static String icon(String category) {
        String group = category == null ? "" : category.trim();
        String flag = leadingFlag(group);
        if (!flag.isEmpty()) return flag;
        String known = ICONS.get(group);
        if (known != null) return known;
        // Unknown categories still receive a stable category-specific badge instead of a shared TV glyph.
        return "◆" + String.format(java.util.Locale.ROOT, "%08X", group.hashCode());
    }

    private static String leadingFlag(String text) {
        if (text.isEmpty()) return "";
        int first = text.codePointAt(0);
        int secondAt = Character.charCount(first);
        if (first < 0x1F1E6 || first > 0x1F1FF || secondAt >= text.length()) return "";
        int second = text.codePointAt(secondAt);
        if (second < 0x1F1E6 || second > 0x1F1FF) return "";
        return text.substring(0, secondAt + Character.charCount(second));
    }
}
