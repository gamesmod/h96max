#!/usr/bin/env python3
"""Merge IPTV playlists into one checked list for the "ТВ-каналы" tile (apps/iptv).

  python3 -I tools/iptv-merge.py [--sources tools/iptv-sources.txt] [--out overlay/.../iptv.m3u]

Rules (user, 2026-10-27): no duplicates, every channel has a working logo, every stream answers from this network.
- sources: "group=NAME" filters a category; "set-group=NAME" assigns one; order = priority.
- logo: tvg-logo must be http(s) and return an image (or any 200 body for servers without content-type).
- stream: HTTP 200; HLS is followed master -> first variant -> first segment (all must answer 200).
- duplicates: same stream URL, or the same normalized name (case, HD/FHD/4K/SD/50fps/orig/[..]/(..) dropped);
  the first working entry in source order wins. Adult channels are included in the main playlist by default;
  --exclude-adult removes them. Детские категории и явно помеченные детские каналы всегда исключаются.
Downloads are only parsed, never executed. Network: VLC user agent, short timeouts, 48 parallel checks.
"""
import argparse, concurrent.futures as cf, re, shlex, shutil, subprocess, sys, urllib.parse, urllib.request

UA = "VLC/3.0.20 LibVLC/3.0.20"
ADULT = re.compile(r"18\+|xxx|adult|эрот|взросл|для взрослых|playboy|brazzers|hustler|private|redlight|blue hustler|секс|sex", re.I)
ADULT_NAME_TAG = re.compile(r"18\+|xxx|adult|эрот|взросл|porn|порн|playboy|brazzers|hustler|private|redlight|blue hustler", re.I)
CHILD_CHANNEL = re.compile(r"\b(?:kids?|children?|cartoons?|animation|boomerang|nick(?:elodeon)?|junior|baby|disney)\b|cartoonito|дет(?:ские|ский|ям)|мульт(?:фильм|иман|ик)|теленян|мультиланд|дисней", re.I)
QUAL = re.compile(r"(?<![\w])(?:hd|fhd|uhd|4k|sd|hq|orig(?:inal)?|50\s?fps|60\s?fps|\d{3,4}p|h\.?265|hevc|backup|резерв)(?![\w])", re.I)
RESOLUTION = re.compile(r"(?<!\d)(\d{3,4})\s*p?(?!\d)", re.I)
SENSITIVE = {"token", "auth", "password", "passwd", "username", "user", "api_key", "apikey", "access_token", "signature", "sig"}

def safe_url(url):
    try:
        parts = urllib.parse.urlsplit(url)
        return not (parts.username or parts.password or any(
            key.lower() in SENSITIVE for key, _ in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)))
    except ValueError:
        return False

def safe_vlc_option(option):
    """Допускать только необходимые VLC-поля без явных учётных данных."""
    key, sep, value = option.partition("=")
    if not sep or not value or "\r" in value or "\n" in value:
        return False
    key = key.lower()
    if key == "#extvlcopt:http-referrer":
        try:
            parts = urllib.parse.urlsplit(value)
            return parts.scheme in ("http", "https") and bool(parts.hostname) and safe_url(value)
        except ValueError:
            return False
    if key == "#extvlcopt:http-user-agent":
        return not re.search(r"(?i)(?:password|passwd|token|authorization|cookie|api[_-]?key|username)\s*[:=]", value)
    return False

def public_entries(entries):
    """Не проверять и не включать адреса с учётными данными или токенами."""
    return [entry for entry in entries if safe_url(entry["url"]) and
            safe_url(entry["attrs"].get("tvg-logo", "")) and
            all(safe_vlc_option(option) for option in entry.get("opts", []))]

def fetch(url, limit=262144, timeout=8, rng=False):
    h = {"User-Agent": UA}
    if rng: h["Range"] = "bytes=0-65535"
    r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)
    return r.status, r.headers.get("Content-Type", ""), r.read(limit), r.geturl()

def parse(text, only_group=None):
    out, cur, opts = [], None, []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            name = line.rsplit(",", 1)[-1].strip() if "," in line else ""
            cur, opts = {"attrs": attrs, "name": name}, []
        elif line.startswith("#EXTVLCOPT") and cur is not None:
            opts.append(line)
        elif line.startswith("#EXTGRP") and cur is not None:
            cur["attrs"].setdefault("group-title", line.split(":", 1)[1].strip())
        elif line and not line.startswith("#") and cur is not None:
            cur["url"], cur["opts"] = line, opts
            if not only_group or cur["attrs"].get("group-title", "") == only_group:
                cur["attrs"]["group-title"] = normalize_group(cur["attrs"].get("group-title", ""), cur["name"])
                out.append(cur)
            cur = None
    return out

def normalize_group(group, channel_name=""):
    """Свести варианты категорий к единому русскому названию."""
    # Явную маркировку 18+ в названии не теряем из-за общей темы источника.
    # Слова «секс/sex» отдельно не используем: они дают ложные совпадения на названиях сериалов.
    if channel_name and ADULT_NAME_TAG.search(channel_name):
        return "Для взрослых"
    value = re.sub(r"[^a-zа-яё0-9+]+", " ", (group or "").lower()).strip()
    value = value.replace("ё", "е")
    if not value:
        value = ""
    rules = (
        (r"18\+|xxx|adult|эрот|взросл|playboy|brazzers|hustler|private|redlight|sex|секс", "Для взрослых"),
        (r"kid|child|cartoon|дет|мульт", "Детские"),
        (r"football|futbol|soccer|футбол", "Спорт · Футбол"),
        (r"hockey|хоккей", "Спорт · Хоккей"),
        (r"tennis|теннис", "Спорт · Теннис"),
        (r"basketball|баскетбол", "Спорт · Баскетбол"),
        (r"racing|motorsport|автоспорт|гонк", "Спорт · Автоспорт"),
        (r"series|serial|сериал", "Фильмы · Сериалы"),
        (r"movie|film|cinema|кино|фильм", "Фильмы"),
        (r"world|international|мир|международ", "Новости · Мир"),
        (r"business|эконом|финанс", "Новости · Экономика"),
        (r"news|новост|информац", "Новости"),
        (r"music|музык", "Музыка"),
        (r"science|науч", "Познавательные · Наука"),
        (r"nature|wildlife|природ|животн", "Познавательные · Природа"),
        (r"history|истори", "Познавательные · История"),
        (r"document|education|познав|документ|образоват", "Познавательные"),
        (r"religious|religion|spiritual|церк|религи|духов", "Религиозные"),
        (r"auto|automotive|авто|автомоб", "Авто"),
        (r"cooking|food|кулин|рецепт", "Кулинария"),
        (r"outdoor|hunting|fishing|охот|рыбалк", "Отдых · Природа"),
        (r"travel|туризм|путешеств", "Путешествия"),
        (r"legislative|government|parliament|государств|парламент", "Государственные"),
        (r"culture|культур", "Культура"),
        (r"comedy|humor|юмор|комед", "Юмор"),
        (r"radio|радио", "Радио"),
        (r"entertain|развлек", "Развлекательные"),
        (r"sport|спорт", "Спорт"),
        (r"general|undefined|основн|общие", "Общие"),
    )
    for pattern, title in rules:
        if value and re.search(pattern, value):
            # Общая категория «Спорт» уточняется по названию канала, если оно явно задаёт дисциплину.
            if title == "Спорт" and channel_name:
                sport_name_rules = (
                    (r"football|futbol|soccer|футбол", "Спорт · Футбол"),
                    (r"hockey|хоккей", "Спорт · Хоккей"),
                    (r"tennis|теннис", "Спорт · Теннис"),
                    (r"basketball|баскетбол", "Спорт · Баскетбол"),
                    (r"racing|motorsport|автоспорт|гонк", "Спорт · Автоспорт"),
                )
                name = channel_name.lower().replace("ё", "е")
                for name_pattern, subtype in sport_name_rules:
                    if re.search(name_pattern, name):
                        return subtype
            return title

    # Региональные группы получают русское название и флаг страны; топонимы областей и штатов сохраняются.
    regions = (
        ("🇷🇺 Россия", "Московская область", r"московская область|moscow oblast|moscow region"),
        ("🇷🇺 Россия", "Москва", r"москва|moscow|moskva"),
        ("🇷🇺 Россия", "Санкт-Петербург", r"санкт петербург|st petersburg|saint petersburg|spb"),
        ("🇷🇺 Россия", "Ленинградская область", r"ленинградская область|leningrad oblast|leningrad region"),
        ("🇷🇺 Россия", "Татарстан", r"татарстан|tatarstan"),
        ("🇷🇺 Россия", "Краснодарский край", r"краснодарский край|krasnodar krai|krasnodar"),
        ("🇷🇺 Россия", "Свердловская область", r"свердловская область|sverdlovsk oblast|yekaterinburg"),
        ("🇷🇺 Россия", "Новосибирская область", r"новосибирская область|novosibirsk oblast|novosibirsk"),
        ("🇷🇺 Россия", "Башкортостан", r"башкортостан|bashkortostan|bashkiria"),
        ("🇷🇺 Россия", "Чечня", r"чечня|chechnya"),
        ("🇺🇸 США", "Алабама", r"alabama"), ("🇺🇸 США", "Аляска", r"alaska"),
        ("🇺🇸 США", "Аризона", r"arizona"), ("🇺🇸 США", "Арканзас", r"arkansas"),
        ("🇺🇸 США", "Калифорния", r"california"), ("🇺🇸 США", "Колорадо", r"colorado"),
        ("🇺🇸 США", "Коннектикут", r"connecticut"), ("🇺🇸 США", "Делавэр", r"delaware"),
        ("🇺🇸 США", "Флорида", r"florida"), ("🇺🇸 США", "Джорджия", r"georgia"),
        ("🇺🇸 США", "Гавайи", r"hawaii"), ("🇺🇸 США", "Айдахо", r"idaho"),
        ("🇺🇸 США", "Иллинойс", r"illinois"), ("🇺🇸 США", "Индиана", r"indiana"),
        ("🇺🇸 США", "Айова", r"iowa"), ("🇺🇸 США", "Канзас", r"kansas"),
        ("🇺🇸 США", "Кентукки", r"kentucky"), ("🇺🇸 США", "Луизиана", r"louisiana"),
        ("🇺🇸 США", "Мэн", r"maine"), ("🇺🇸 США", "Мэриленд", r"maryland"),
        ("🇺🇸 США", "Массачусетс", r"massachusetts"), ("🇺🇸 США", "Мичиган", r"michigan"),
        ("🇺🇸 США", "Миннесота", r"minnesota"), ("🇺🇸 США", "Миссисипи", r"mississippi"),
        ("🇺🇸 США", "Миссури", r"missouri"), ("🇺🇸 США", "Монтана", r"montana"),
        ("🇺🇸 США", "Небраска", r"nebraska"), ("🇺🇸 США", "Невада", r"nevada"),
        ("🇺🇸 США", "Нью-Гэмпшир", r"new hampshire"), ("🇺🇸 США", "Нью-Джерси", r"new jersey"),
        ("🇺🇸 США", "Нью-Мексико", r"new mexico"), ("🇺🇸 США", "Нью-Йорк", r"new york"),
        ("🇺🇸 США", "Северная Каролина", r"north carolina"), ("🇺🇸 США", "Северная Дакота", r"north dakota"),
        ("🇺🇸 США", "Огайо", r"ohio"), ("🇺🇸 США", "Оклахома", r"oklahoma"),
        ("🇺🇸 США", "Орегон", r"oregon"), ("🇺🇸 США", "Пенсильвания", r"pennsylvania"),
        ("🇺🇸 США", "Род-Айленд", r"rhode island"), ("🇺🇸 США", "Южная Каролина", r"south carolina"),
        ("🇺🇸 США", "Южная Дакота", r"south dakota"), ("🇺🇸 США", "Теннесси", r"tennessee"),
        ("🇺🇸 США", "Техас", r"texas"), ("🇺🇸 США", "Юта", r"utah"),
        ("🇺🇸 США", "Вермонт", r"vermont"), ("🇺🇸 США", "Вирджиния", r"virginia"),
        ("🇺🇸 США", "Вашингтон", r"washington"), ("🇺🇸 США", "Западная Вирджиния", r"west virginia"),
        ("🇺🇸 США", "Висконсин", r"wisconsin"), ("🇺🇸 США", "Вайоминг", r"wyoming"),
        ("🇨🇦 Канада", "Британская Колумбия", r"british columbia"),
        ("🇨🇦 Канада", "Онтарио", r"ontario"), ("🇨🇦 Канада", "Квебек", r"quebec|квебек"),
    )
    if value == "georgia":
        return "🇬🇪 Грузия"
    for country, region, pattern in regions:
        if re.search(r"(?:^| )(" + pattern + r")(?: |$)", value):
            return country + " · " + region

    countries = (
        (r"^(?:ru|russia|russian|россия|российские|русские)$", "🇷🇺 Россия"),
        (r"^(?:by|belarus|belarussian|беларусь|белоруссия|беларускіе)$", "🇧🇾 Беларусь"),
        (r"^(?:ua|ukraine|ukrainian|украина|українські)$", "🇺🇦 Украина"),
        (r"^(?:kz|kazakhstan|казахстан|қазақстан)$", "🇰🇿 Казахстан"),
        (r"^(?:us|usa|united states|america|сша|соединенные штаты)$", "🇺🇸 США"),
        (r"^(?:gb|uk|united kingdom|great britain|британия|великобритания)$", "🇬🇧 Великобритания"),
        (r"^(?:ca|canada|канада)$", "🇨🇦 Канада"),
        (r"^(?:de|germany|german|германия)$", "🇩🇪 Германия"),
        (r"^(?:fr|france|франция)$", "🇫🇷 Франция"),
        (r"^(?:tr|turkey|turkiye|турция|türk)$", "🇹🇷 Турция"),
        (r"^(?:kg|kyrgyzstan|кыргызстан|киргизия)$", "🇰🇬 Кыргызстан"),
        (r"^(?:uz|uzbekistan|узбекистан)$", "🇺🇿 Узбекистан"),
        (r"^(?:am|armenia|армения)$", "🇦🇲 Армения"),
        (r"^(?:az|azerbaijan|азербайджан)$", "🇦🇿 Азербайджан"),
        (r"^(?:ge|georgia|грузия)$", "🇬🇪 Грузия"),
        (r"^(?:md|moldova|moldovenească|молдова|молдавия)$", "🇲🇩 Молдова"),
        (r"^(?:ro|romania|румыния)$", "🇷🇴 Румыния"),
        (r"^(?:il|israel|израиль|ישראלי)$", "🇮🇱 Израиль"),
        (r"^(?:in|india|индия)$", "🇮🇳 Индия"),
        (r"^(?:pl|poland|польша)$", "🇵🇱 Польша"),
        (r"^(?:se|sweden|швеция)$", "🇸🇪 Швеция"),
        (r"^(?:it|italy|италия)$", "🇮🇹 Италия"),
        (r"^(?:uz|uzbekistan|узбекистан)$", "🇺🇿 Узбекистан"),
        (r"^(?:lv|latvia|латвия)$", "🇱🇻 Латвия"),
        (r"^(?:lt|lithuania|литва)$", "🇱🇹 Литва"),
        (r"^(?:az|azerbaijan|азербайджан|azərbaycan)$", "🇦🇿 Азербайджан"),
        (r"^(?:ae|uae|united arab emirates|оаэ)$", "🇦🇪 ОАЭ"),
        (r"^(?:br|brazil|бразилия)$", "🇧🇷 Бразилия"),
        (r"^(?:pt|portugal|португалия)$", "🇵🇹 Португалия"),
        (r"^(?:tj|tajikistan|таджикистан)$", "🇹🇯 Таджикистан"),
        (r"^(?:ee|estonia|эстония)$", "🇪🇪 Эстония"),
        (r"^(?:cz|czechia|czech republic|чехия)$", "🇨🇿 Чехия"),
        (r"^(?:bg|bulgaria|болгария)$", "🇧🇬 Болгария"),
        (r"^(?:dk|denmark|дания)$", "🇩🇰 Дания"),
        (r"^(?:no|norway|норвегия)$", "🇳🇴 Норвегия"),
        (r"^(?:sk|slovakia|словакия)$", "🇸🇰 Словакия"),
        (r"^(?:kr|south korea|korea|южная корея|корея)$", "🇰🇷 Южная Корея"),
        (r"^(?:au|australia|австралия)$", "🇦🇺 Австралия"),
        (r"^(?:eu|europe|европа)$", "🌍 Европа"),
    )
    for pattern, title in countries:
        # Некоторые списки записывают сразу два языка, например «Germany | Германия».
        alias_pattern = pattern.removeprefix("^").removesuffix("$")
        if re.search(r"(?<!\w)(?:" + alias_pattern + r")(?!\w)", value):
            return title
    if value and re.search(r"regional|local|регион|местн", value):
        return "Региональные"
    # У источников с пустой/непонятной группой осторожно выводим тему из названия.
    title = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", (channel_name or "").lower())
    inferred = (
        (r"18\+|xxx|adult|эрот|взросл|porn|sex|секс", "Для взрослых"),
        (r"football|futbol|soccer|футбол", "Спорт · Футбол"),
        (r"hockey|хоккей", "Спорт · Хоккей"),
        (r"sport|sports|спорт|матч", "Спорт"),
        (r"news|новост|24\s*час", "Новости"),
        (r"music|музык|radio|радио|мтв|mtv", "Музыка"),
        (r"movie|film|cinema|кино|фильм|serial|сериал", "Фильмы"),
        (r"documentary|science|nature|history|познав|документ|науч|природ|истори", "Познавательные"),
        (r"kids?|cartoon|junior|baby|дет|мульт", "Детские"),
        (r"religious|religion|church|церк|религи", "Религиозные"),
        (r"cooking|food|кулин|рецепт", "Кулинария"),
        (r"auto|авто", "Авто"),
    )
    for pattern, category in inferred:
        if re.search(pattern, title):
            return category
    return "Общие" if not value else "Прочее"

TR = str.maketrans({"а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ж": "zh", "з": "z", "и": "i", "й": "y",
                    "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
                    "ф": "f", "х": "h", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sch", "ъ": "", "ы": "y", "ь": "", "э": "e",
                    "ю": "yu", "я": "ya", "і": "i", "ї": "yi", "є": "e"})

def norm(name):
    """Duplicate key: lower case, no [..]/(..)/quality tags, Cyrillic transliterated (iptv-org names are Latin),
    common spelling variants folded (kh/h, iy/y, ts/c), spaces dropped."""
    # Удаляем обозначения качества до скобок, чтобы «Канал (HD)» и «Канал 1080p» совпадали.
    n = name.lower().replace("ё", "е")
    # Сдвиги времени и копии в скобках относятся к тому же каналу; плюс в бренде сохраняется.
    n = re.sub(r"\s*\(\s*\d+\s*\)\s*$|\s+[+-]\s*\d+\s*$", " ", n)
    n = re.sub(r"\[[^\]]*\]|\([^)]*\)", lambda m: " " if QUAL.search(m.group(0)) else m.group(0), n)
    n = QUAL.sub(" ", n).translate(TR)
    n = re.sub(r"[^\w+]+", "", n)
    for a, b in (("kh", "h"), ("iy", "y"), ("yy", "y"), ("ij", "y"), ("ts", "c"), ("tv", "tv")):
        n = n.replace(a, b)
    return n

def resolution(entry):
    """Вернуть минимальную известную вертикальную развёртку потока в пикселях."""
    values = []
    for value in (entry.get("name", ""), entry.get("attrs", {}).get("tvg-name", ""), entry.get("url", "")):
        values.extend(int(m.group(1)) for m in RESOLUTION.finditer(value) if int(m.group(1)) >= 240)
    for opt in entry.get("opts", []):
        values.extend(int(m.group(1)) for m in RESOLUTION.finditer(opt) if int(m.group(1)) >= 240)
    return max(values, default=0)

def hls_variant_resolution(text):
    """Извлечь наибольшую RESOLUTION из master-playlist HLS."""
    values = [int(m.group(1)) for m in re.finditer(r"RESOLUTION\s*=\s*\d+x(\d+)", text, re.I)]
    return max(values, default=0)

def probe_segment_resolution(data):
    """Прочитать разрешение из ограниченного буфера MPEG-TS через ffprobe."""
    probe = shutil.which("ffprobe")
    if not probe or not data:
        return 0
    try:
        result = subprocess.run(
            [probe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
             "-of", "csv=s=x:p=0", "-i", "pipe:0"],
            input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8, check=False)
        match = re.search(rb"\d+x(\d+)", result.stdout)
        return int(match.group(1)) if result.returncode == 0 and match else 0
    except (OSError, subprocess.TimeoutExpired):
        return 0

def quality_ok(entry):
    """Не считать подпись в названии доказательством измеренного разрешения."""
    return entry.get("verified_resolution", 0) >= 1080

def label_verified_quality(entry, height):
    """Показать измеренное качество в имени, удалив конфликтующие исходные метки."""
    name = QUAL.sub(" ", entry.get("name", ""))
    name = re.sub(r"\(\s*\)|\[\s*\]", " ", name)
    name = re.sub(r"\s+", " ", name).strip()
    label = "4K" if height >= 2160 else f"{height}p"
    entry["name"] = f"{name} ({label})"

def dedupe(entries):
    """Keep the first entry per normalized name and per tvg-id (entries are already in priority order)."""
    seen, out = set(), []
    for e in entries:
        keys = {"n:" + norm(e["name"]), "u:" + e.get("url", "")}
        if e["attrs"].get("tvg-id"): keys.add("i:" + e["attrs"]["tvg-id"].lower())
        if keys & seen: continue
        seen |= keys; out.append(e)
    return out

def is_child_channel(entry):
    """Исключить детскую категорию и каналы, явно помеченные как детские."""
    group = entry.get("attrs", {}).get("group-title", "")
    name = entry.get("name", "")
    category = normalize_group(group)
    if category == "Для взрослых":
        return False
    return category == "Детские" or bool(CHILD_CHANNEL.search(group + " " + name))

def parse_source_line(line):
    """Разбирает строку источника с поддержкой пробелов в кавычках у параметров групп."""
    parts = shlex.split(line)
    return (parts[0], parts[1:]) if parts else None

def playlist_sort_key(entry):
    """Ключ сортировки: 18+ первой, затем категории и каналы по названию."""
    group = entry["attrs"].get("group-title", "") or "яяя"
    return (0 if group == "Для взрослых" else 1, group.casefold(), entry["name"].casefold())

def logo_ok(url):
    try:
        st, ct, body, _ = fetch(url, limit=4096)
        return st == 200 and (ct.startswith("image/") or body[:4] in (b"\x89PNG", b"GIF8", b"RIFF") or body[:2] == b"\xff\xd8" or b"<svg" in body[:200])
    except Exception:
        return False

def stream_resolution(url):
    """Проверить поток и вернуть подтверждённое разрешение; 0 означает неизвестно/ниже порога."""
    try:
        st, ct, body, final = fetch(url)
        if st not in (200, 206): return 0
        if b"#EXTM3U" not in body[:1024]:
            # Имя URL может обещать 1080p, но принимать поток можно только по измеренному кадру.
            return probe_segment_resolution(body)
        lines = [l.strip() for l in body.decode("utf-8", "replace").splitlines()]
        if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):       # master -> лучший описанный вариант
            variants = []
            for i, line in enumerate(lines):
                if not line.startswith("#EXT-X-STREAM-INF"):
                    continue
                uri = next((item for item in lines[i + 1:] if item and not item.startswith("#")), None)
                if not uri: continue
                height = hls_variant_resolution(line)
                bandwidth = int(re.search(r"BANDWIDTH=(\d+)", line).group(1)) if re.search(r"BANDWIDTH=(\d+)", line) else 0
                if height > 0: variants.append((height, bandwidth, uri))
            if not variants: return 0
            height, _, v = max(variants)
            st, ct, body, final = fetch(urllib.parse.urljoin(final, v))
            if st not in (200, 206) or b"#EXTM3U" not in body[:1024]: return 0
            lines = [l.strip() for l in body.decode("utf-8", "replace").splitlines()]
        else:
            height = 0
        if not height:
            height = hls_variant_resolution("\n".join(lines))
        seg = next((l for l in lines if l and not l.startswith("#")), None)
        if not seg: return 0
        st, _, data, _ = fetch(urllib.parse.urljoin(final, seg), limit=65536, rng=True)
        if st not in (200, 206) or not data: return 0
        # Метаданные HLS — только подсказка; неизвестное по сегменту качество не проходит порог.
        return probe_segment_resolution(data)
    except Exception:
        return 0

def stream_ok(url):
    """Совместимая булева обёртка проверки доступности и порога 1080p."""
    return stream_resolution(url) >= 1080

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", default="tools/iptv-sources.txt")
    ap.add_argument("--out", default="overlay/system/etc/tvbox/iptv.m3u")
    ap.add_argument("--exclude-adult", action="store_true", help="исключить каналы 18+ из общего списка")
    ap.add_argument("--adult-allowlist", default="tools/iptv-adult-allowlist.txt",
                    help="EPG IDs взрослых каналов, проверенных на целевом устройстве")
    ap.add_argument("--dedupe-file", help="only re-run duplicate removal on an already checked playlist (no network)")
    a = ap.parse_args()
    if a.dedupe_file:
        ent = parse(open(a.dedupe_file, encoding="utf-8").read())
        filtered = [e for e in ent if not is_child_channel(e)]
        out = dedupe(filtered)
        write(out, a.dedupe_file)
        print(f"dedupe and child filter: {len(ent)} -> {len(out)} channels", file=sys.stderr)
        return
    entries = []
    with open(a.sources, encoding="utf-8") as source_file:
        source_lines = source_file.readlines()
    for line in source_lines:
        line = line.strip()
        if not line or line.startswith("#"): continue
        parsed = parse_source_line(line)
        if not parsed: continue
        url, opt = parsed
        grp = next((o[6:] for o in opt if o.startswith("group=")), None)
        set_grp = next((o[10:] for o in opt if o.startswith("set-group=")), None)
        try:
            text = fetch(url, limit=16 << 20, timeout=60)[2].decode("utf-8", "replace")
        except Exception as e:
            print(f"source FAIL {url}: {e}", file=sys.stderr); continue
        got = parse(text, grp)
        if set_grp:
            for e in got: e["attrs"]["group-title"] = set_grp
        print(f"source {len(got):5d} {url}", file=sys.stderr)
        entries += got
    total = len(entries)
    entries = [e for e in entries if e["name"] and e["attrs"].get("tvg-logo", "").startswith("http")]
    before_sensitive = len(entries)
    entries = public_entries(entries)
    print(f"исключено записей с чувствительными URL: {before_sensitive - len(entries)}", file=sys.stderr)
    before_children = len(entries)
    entries = [e for e in entries if not is_child_channel(e)]
    print(f"детские каналы исключены: {before_children - len(entries)}", file=sys.stderr)
    if a.exclude_adult:
        entries = [e for e in entries if not ADULT.search(e["name"] + " " + e["attrs"].get("group-title", ""))]
    else:
        with open(a.adult_allowlist, encoding="utf-8") as f:
            adult_ids = {line.strip() for line in f if line.strip() and not line.lstrip().startswith("#")}
        before_adult = sum(e["attrs"].get("group-title") == "Для взрослых" for e in entries)
        entries = [e for e in entries if e["attrs"].get("group-title") != "Для взрослых" or
                   e["attrs"].get("tvg-id", "").strip() in adult_ids]
        kept_adult = sum(e["attrs"].get("group-title") == "Для взрослых" for e in entries)
        print(f"взрослые каналы по allowlist устройства: {before_adult} -> {kept_adult}", file=sys.stderr)
    print(f"entries {total}, with name+logo{', excluding adult' if a.exclude_adult else ', adult included'}: {len(entries)}", file=sys.stderr)
    seen_url, cand = set(), {}
    for e in entries:                                   # exact-URL duplicates, then group by name
        if e["url"] in seen_url: continue
        seen_url.add(e["url"]); cand.setdefault(norm(e["name"]), []).append(e)
    logos = {e["attrs"]["tvg-logo"] for v in cand.values() for e in v}
    with cf.ThreadPoolExecutor(48) as ex:
        logo_res = dict(zip(logos, ex.map(logo_ok, logos)))
        urls = [e["url"] for v in cand.values() for e in v if logo_res[e["attrs"]["tvg-logo"]]]
        print(f"channels {len(cand)}, logos ok {sum(logo_res.values())}/{len(logos)}, streams to check {len(urls)}", file=sys.stderr)
        stream_res = dict(zip(urls, ex.map(stream_resolution, urls)))
    out = []
    for key, v in cand.items():
        best = next((e for e in sorted(v, key=lambda x: max(resolution(x), stream_res.get(x["url"], 0)), reverse=True)
                     if stream_res.get(e["url"], 0) >= 1080 and logo_res[e["attrs"]["tvg-logo"]]), None)
        if best:
            label_verified_quality(best, stream_res[best["url"]])
            out.append(best)
    out = dedupe(out)
    # Категорию 18+ ставим первой, остальные группы и каналы упорядочиваем по-русски.
    out.sort(key=playlist_sort_key)
    write(out, a.out)
    print(f"written {len(out)} channels -> {a.out}", file=sys.stderr)

def write(out, path):
    with open(path, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for e in out:
            at = e["attrs"]
            keep = " ".join(f'{k}="{at[k]}"' for k in ("tvg-id", "tvg-name", "tvg-logo", "group-title") if at.get(k))
            f.write(f"#EXTINF:-1 {keep},{e['name']}\n")
            for o in e["opts"]: f.write(o + "\n")
            f.write(e["url"] + "\n")

if __name__ == "__main__":
    main()
