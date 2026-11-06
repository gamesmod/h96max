#!/usr/bin/env python3
"""Merge IPTV playlists into one checked list for the "ТВ-каналы" tile (apps/iptv).

  python3 -I tools/iptv-merge.py [--sources tools/iptv-sources.txt] [--out overlay/.../iptv.m3u] [--keep-adult]

Rules (user, 2026-10-27): no duplicates, every channel has a working logo, every stream answers from this network.
- sources: "group=NAME" filters a category; "set-group=NAME" assigns one; order = priority.
- logo: tvg-logo must be http(s) and return an image (or any 200 body for servers without content-type).
- stream: HTTP 200; HLS is followed master -> first variant -> first segment (all must answer 200).
- duplicates: same stream URL, or the same normalized name (case, HD/FHD/4K/SD/50fps/orig/[..]/(..) dropped);
  the first working entry in source order wins. Adult channels are included in the main playlist by default;
  --exclude-adult removes them.
Downloads are only parsed, never executed. Network: VLC user agent, short timeouts, 48 parallel checks.
"""
import argparse, concurrent.futures as cf, re, sys, urllib.parse, urllib.request

UA = "VLC/3.0.20 LibVLC/3.0.20"
ADULT = re.compile(r"18\+|xxx|adult|эрот|взросл|для взрослых|playboy|brazzers|hustler|private|redlight|blue hustler|секс|sex", re.I)
QUAL = re.compile(r"(?<![\w])(?:hd|fhd|uhd|4k|sd|hq|orig(?:inal)?|50\s?fps|60\s?fps|\d{3,4}p|h\.?265|hevc|backup|резерв)(?![\w])", re.I)

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
                cur["attrs"]["group-title"] = normalize_group(cur["attrs"].get("group-title", ""))
                out.append(cur)
            cur = None
    return out

def normalize_group(group):
    """Свести варианты категорий к единому русскому названию."""
    value = re.sub(r"[^a-zа-яё0-9+]+", " ", (group or "").lower()).strip()
    value = value.replace("ё", "е")
    if not value:
        return "Прочее"
    rules = (
        (r"18\+|xxx|adult|эрот|взросл|playboy|brazzers|hustler|private|redlight|sex|секс", "Для взрослых"),
        (r"sport|спорт", "Спорт"),
        (r"news|новост|информац", "Новости"),
        (r"movie|film|cinema|кино|фильм", "Фильмы"),
        (r"kid|child|cartoon|дет|мульт", "Детские"),
        (r"music|музык", "Музыка"),
        (r"document|science|education|познав|документ|науч|образоват", "Познавательные"),
        (r"radio|радио", "Радио"),
        (r"entertain|развлек", "Развлекательные"),
    )
    for pattern, title in rules:
        if re.search(pattern, value):
            return title

    # Региональные группы получают русское название и флаг страны; топонимы областей и штатов сохраняются.
    regions = (
        ("🇷🇺 Россия", "Москва", r"москва|moscow|moskva"),
        ("🇷🇺 Россия", "Московская область", r"московская область|moscow oblast|moscow region"),
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
        (r"^(?:by|belarus|belarussian|беларусь|белоруссия)$", "🇧🇾 Беларусь"),
        (r"^(?:ua|ukraine|ukrainian|украина)$", "🇺🇦 Украина"),
        (r"^(?:kz|kazakhstan|казахстан)$", "🇰🇿 Казахстан"),
        (r"^(?:us|usa|united states|america|сша|соединенные штаты)$", "🇺🇸 США"),
        (r"^(?:gb|uk|united kingdom|great britain|британия|великобритания)$", "🇬🇧 Великобритания"),
        (r"^(?:ca|canada|канада)$", "🇨🇦 Канада"),
        (r"^(?:de|germany|german|германия)$", "🇩🇪 Германия"),
        (r"^(?:fr|france|франция)$", "🇫🇷 Франция"),
        (r"^(?:tr|turkey|turkiye|турция)$", "🇹🇷 Турция"),
        (r"^(?:kg|kyrgyzstan|кыргызстан|киргизия)$", "🇰🇬 Кыргызстан"),
        (r"^(?:uz|uzbekistan|узбекистан)$", "🇺🇿 Узбекистан"),
        (r"^(?:am|armenia|армения)$", "🇦🇲 Армения"),
        (r"^(?:az|azerbaijan|азербайджан)$", "🇦🇿 Азербайджан"),
        (r"^(?:ge|georgia|грузия)$", "🇬🇪 Грузия"),
        (r"^(?:md|moldova|молдова)$", "🇲🇩 Молдова"),
    )
    for pattern, title in countries:
        if re.fullmatch(pattern, value):
            return title
    if re.search(r"regional|local|регион|местн", value):
        return "Региональные"
    return "Прочее"

TR = str.maketrans({"а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ж": "zh", "з": "z", "и": "i", "й": "y",
                    "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
                    "ф": "f", "х": "h", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sch", "ъ": "", "ы": "y", "ь": "", "э": "e",
                    "ю": "yu", "я": "ya", "і": "i", "ї": "yi", "є": "e"})

def norm(name):
    """Duplicate key: lower case, no [..]/(..)/quality tags, Cyrillic transliterated (iptv-org names are Latin),
    common spelling variants folded (kh/h, iy/y, ts/c), spaces dropped."""
    # Удаляем обозначения качества до скобок, чтобы «Канал (HD)» и «Канал 1080p» совпадали.
    n = name.lower().replace("ё", "е")
    n = re.sub(r"\[[^\]]*\]|\([^)]*\)", lambda m: " " if QUAL.search(m.group(0)) else m.group(0), n)
    n = QUAL.sub(" ", n).translate(TR)
    n = re.sub(r"[^\w+]+", "", n)
    for a, b in (("kh", "h"), ("iy", "y"), ("yy", "y"), ("ij", "y"), ("ts", "c"), ("tv", "tv")):
        n = n.replace(a, b)
    return n

def dedupe(entries):
    """Keep the first entry per normalized name and per tvg-id (entries are already in priority order)."""
    seen, out = set(), []
    for e in entries:
        keys = {"n:" + norm(e["name"])}
        if e["attrs"].get("tvg-id"): keys.add("i:" + e["attrs"]["tvg-id"].lower())
        if keys & seen: continue
        seen |= keys; out.append(e)
    return out

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

def stream_ok(url):
    try:
        st, ct, body, final = fetch(url)
        if st not in (200, 206): return False
        if b"#EXTM3U" not in body[:1024]:
            return "mpegurl" not in ct.lower() and len(body) > 0          # plain TS/other stream
        lines = [l.strip() for l in body.decode("utf-8", "replace").splitlines()]
        if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):       # master -> first variant
            v = next(l for i, l in enumerate(lines) if i and lines[i - 1].startswith("#EXT-X-STREAM-INF") and l and not l.startswith("#"))
            st, ct, body, final = fetch(urllib.parse.urljoin(final, v))
            if st not in (200, 206) or b"#EXTM3U" not in body[:1024]: return False
            lines = [l.strip() for l in body.decode("utf-8", "replace").splitlines()]
        seg = next((l for l in lines if l and not l.startswith("#")), None)
        if not seg: return False
        st, _, data, _ = fetch(urllib.parse.urljoin(final, seg), limit=65536, rng=True)
        return st in (200, 206) and len(data) > 0
    except Exception:
        return False

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", default="tools/iptv-sources.txt")
    ap.add_argument("--out", default="overlay/system/etc/tvbox/iptv.m3u")
    ap.add_argument("--exclude-adult", action="store_true", help="исключить каналы 18+ из общего списка")
    ap.add_argument("--dedupe-file", help="only re-run duplicate removal on an already checked playlist (no network)")
    a = ap.parse_args()
    if a.dedupe_file:
        ent = parse(open(a.dedupe_file, encoding="utf-8").read())
        out = dedupe(ent)
        write(out, a.dedupe_file)
        print(f"dedupe: {len(ent)} -> {len(out)} channels", file=sys.stderr)
        return
    entries = []
    for line in open(a.sources, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"): continue
        url, *opt = line.split()
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
    if a.exclude_adult:
        entries = [e for e in entries if not ADULT.search(e["name"] + " " + e["attrs"].get("group-title", ""))]
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
        stream_res = dict(zip(urls, ex.map(stream_ok, urls)))
    out = []
    for key, v in cand.items():
        best = next((e for e in v if logo_res[e["attrs"]["tvg-logo"]] and stream_res.get(e["url"])), None)
        if best: out.append(best)
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
