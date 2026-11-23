#!/bin/bash
# Извлечение вендорских разделов/файлов для сборки (аналог LineageOS Wiki:
# https://wiki.lineageos.org/extracting_blobs_from_zips_manually ).
#
# Для H96 Max / Panadonik основной вход — Rockchip update.img (RKFW/RKAF).
# Дополнительно поддерживаются zip с payload.bin, block-based OTA (*.transfer.list)
# и уже распакованные LP-образы (Git LFS: dl/a12/Image/lp).
#
# Для сборки предпочтите обёртку tools/prepare-build-inputs.sh (проверка LFS,
# либо --update/--zip с очисткой LP). Этот скрипт — низкоуровневое извлечение.
#
# Примеры:
#   tools/extract-vendor-blobs.sh --update /path/to/update.img
#   tools/extract-vendor-blobs.sh --zip /path/to/rom.zip --dump build/system_dump
#   tools/extract-vendor-blobs.sh --from-images dl/a12/Image/lp --dump build/system_dump
#   tools/extract-vendor-blobs.sh --from-images dl/a12/Image/lp --list vendor
#
# Результат для git-сборки: разделы в dl/a12/Image (+ lp/*.img). Сырой super.img
# и полный файловый dump в репозиторий не коммитятся (.gitignore).
set -euo pipefail

R=$(cd "$(dirname "$0")/.." && pwd)
UPDATE=${UPDATE:-"$R/dl/a12"}
DUMP=""
SOURCE=""
SOURCE_KIND=""
PARTITIONS=(system system_ext vendor vendor_dlkm odm odm_dlkm product)
LIST_ONLY=0
KEEP_WORK=0
WORK=""

usage() {
    awk 'NR==1 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$0"
    exit "${1:-0}"
}

die() { echo "extract-vendor-blobs: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "нужна команда: $1"; }
log() { echo "[extract] $*"; }

is_lfs_pointer() {
    local f=$1 size
    size=$(wc -c <"$f" | tr -d ' ')
    [[ "$size" -lt 1024 ]] || return 1
    grep -q 'git-lfs.github.com/spec/v1' "$f" 2>/dev/null
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --update)
            [[ $# -ge 2 ]] || die "--update требует путь"
            SOURCE=$2; SOURCE_KIND=update; shift 2 ;;
        --zip)
            [[ $# -ge 2 ]] || die "--zip требует путь"
            SOURCE=$2; SOURCE_KIND=zip; shift 2 ;;
        --from-images)
            [[ $# -ge 2 ]] || die "--from-images требует каталог с *.img"
            SOURCE=$2; SOURCE_KIND=images; shift 2 ;;
        --out)
            [[ $# -ge 2 ]] || die "--out требует каталог"
            OUT=$2; shift 2 ;;
        --dump)
            [[ $# -ge 2 ]] || die "--dump требует каталог"
            DUMP=$2; shift 2 ;;
        --list)
            LIST_ONLY=1; shift ;;
        --keep-work)
            KEEP_WORK=1; shift ;;
        -h|--help)
            usage 0 ;;
        *)
            die "неизвестный аргумент: $1 (см. --help)" ;;
    esac
done

[[ -n "$SOURCE_KIND" ]] || die "укажите --update, --zip или --from-images (см. --help)"
[[ -e "$SOURCE" ]] || die "нет источника: $SOURCE"

cleanup() {
    if [[ -n "$WORK" && -d "$WORK" && "$KEEP_WORK" -eq 0 ]]; then
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

make_work() {
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/h96max-extract.XXXXXXXX")
    log "work=$WORK"
}

# --- Rockchip update.img (RKFW + RKAF) ---
extract_update_img() {
    need python3
    local img=$1
    make_work
    log "Rockchip update.img → $OUT/Image"
    python3 -I "$R/tools/rkunpack.py" "$img" "$WORK/unpacked"
    mkdir -p "$OUT"
    # MiniLoader рядом с Image/, как ожидает Git LFS layout
    if [[ -f "$WORK/unpacked/MiniLoaderAll.bin" ]]; then
        mkdir -p "$OUT"
        cp -f "$WORK/unpacked/MiniLoaderAll.bin" "$OUT/MiniLoaderAll.bin"
    fi
    mkdir -p "$OUT/Image"
    # rkunpack кладёт разделы по path из RKAF (часто Image/…)
    if [[ -d "$WORK/unpacked/Image" ]]; then
        cp -a "$WORK/unpacked/Image/." "$OUT/Image/"
    else
        # плоская раскладка: копируем известные имена
        local f
        for f in boot.img recovery.img uboot.img trust.img dtbo.img vbmeta.img \
                 baseparameter.img misc.img parameter.txt; do
            [[ -f "$WORK/unpacked/$f" ]] && cp -f "$WORK/unpacked/$f" "$OUT/Image/$f"
        done
        [[ -f "$WORK/unpacked/super.img" ]] && cp -f "$WORK/unpacked/super.img" "$OUT/Image/super.img"
    fi
    if [[ -f "$OUT/Image/super.img" ]]; then
        log "lpunpack super.img → $OUT/Image/lp"
        mkdir -p "$OUT/Image/lp"
        python3 -I "$R/tools/lpunpack.py" "$OUT/Image/super.img" "$OUT/Image/lp"
    fi
    SOURCE=$OUT/Image/lp
    SOURCE_KIND=images
}

# --- LineageOS / AOSP zip ---
extract_zip() {
    need unzip
    local zip=$1
    make_work
    log "unzip $zip"
    unzip -q "$zip" -d "$WORK/zip"

    if [[ -f "$WORK/zip/payload.bin" ]]; then
        extract_payload "$WORK/zip/payload.bin"
    elif compgen -G "$WORK/zip/*.transfer.list" >/dev/null; then
        extract_block_based "$WORK/zip"
    elif [[ -d "$WORK/zip/system" ]]; then
        log "file-based OTA: файлы уже в system/"
        if [[ -n "$DUMP" ]]; then
            mkdir -p "$DUMP"
            cp -a "$WORK/zip/system" "$DUMP/"
            [[ -d "$WORK/zip/vendor" ]] && cp -a "$WORK/zip/vendor" "$DUMP/"
            [[ -d "$WORK/zip/product" ]] && cp -a "$WORK/zip/product" "$DUMP/"
            [[ -d "$WORK/zip/system_ext" ]] && cp -a "$WORK/zip/system_ext" "$DUMP/"
        fi
        SOURCE=$WORK/zip
        SOURCE_KIND=filetree
    else
        die "не удалось определить тип OTA в zip (нет payload.bin, *.transfer.list и system/)"
    fi
}

extract_payload() {
    local payload=$1
    local extractor=""
    for candidate in \
        "$R/android/prebuilts/extract-tools/linux-x86/bin/ota_extractor" \
        "$HOME/android/prebuilts/extract-tools/linux-x86/bin/ota_extractor" \
        "$(command -v ota_extractor || true)"; do
        [[ -n "$candidate" && -x "$candidate" ]] && extractor=$candidate && break
    done
    [[ -n "$extractor" ]] || die "для payload.bin нужен ota_extractor (см. wiki.lineageos.org/extracting_blobs_from_zips_manually)"
    log "payload.bin → .img через $extractor"
    (cd "$WORK" && "$extractor" --payload "$payload")
    mkdir -p "$OUT/Image/lp"
    local p
    for p in "${PARTITIONS[@]}"; do
        if [[ -f "$WORK/$p.img" ]]; then
            cp -f "$WORK/$p.img" "$OUT/Image/lp/$p.img"
        fi
    done
    for p in boot recovery vbmeta dtbo; do
        [[ -f "$WORK/$p.img" ]] && cp -f "$WORK/$p.img" "$OUT/Image/$p.img"
    done
    SOURCE=$OUT/Image/lp
    SOURCE_KIND=images
}

extract_block_based() {
    local root=$1
    need python3
    local sdat=""
    for candidate in \
        "$R/third_party/sdat2img/sdat2img.py" \
        "$HOME/android/system_dump/sdat2img/sdat2img.py" \
        "$(command -v sdat2img.py || true)"; do
        [[ -n "$candidate" && -f "$candidate" ]] && sdat=$candidate && break
    done
    [[ -n "$sdat" ]] || die "для block-based OTA нужен sdat2img.py (git clone https://github.com/xpirt/sdat2img.git)"

    mkdir -p "$OUT/Image/lp" "$WORK/img"
    local list base br dat img
    for list in "$root"/*.transfer.list; do
        base=$(basename "$list" .transfer.list)
        br="$root/${base}.new.dat.br"
        dat="$root/${base}.new.dat"
        img="$WORK/img/${base}.img"
        if [[ -f "$br" ]]; then
            need brotli
            log "brotli -d ${base}.new.dat.br"
            brotli --decompress --output="$dat" "$br"
        fi
        [[ -f "$dat" ]] || die "нет ${base}.new.dat для $list"
        log "sdat2img $base"
        python3 "$sdat" "$list" "$dat" "$img"
        case "$base" in
            system|system_ext|vendor|vendor_dlkm|odm|odm_dlkm|product)
                cp -f "$img" "$OUT/Image/lp/${base}.img" ;;
            boot|recovery|vbmeta|dtbo)
                mkdir -p "$OUT/Image"
                cp -f "$img" "$OUT/Image/${base}.img" ;;
            *)
                cp -f "$img" "$OUT/Image/${base}.img" ;;
        esac
    done
    SOURCE=$OUT/Image/lp
    SOURCE_KIND=images
}

# --- Файловый dump из ext4 через debugfs rdump (без mount/sudo) ---
dump_partition_image() {
    local img=$1
    local dest=$2
    need debugfs
    mkdir -p "$dest"
    # rdump может жаловаться на chown без root — файлы всё равно копируются
    debugfs -R "rdump / \"$dest\"" "$img" >/dev/null 2>"$WORK/rdump.err" || true
    if [[ ! -d "$dest" ]] || [[ -z "$(find "$dest" -mindepth 1 -maxdepth 1 2>/dev/null | head -1)" ]]; then
        cat "$WORK/rdump.err" >&2 || true
        die "не удалось извлечь файлы из $img"
    fi
}

dump_from_images() {
    local lp=$1
    [[ -d "$lp" ]] || die "нет каталога образов: $lp"
    [[ -n "$DUMP" ]] || die "--from-images с дампом требует --dump КАТАЛОГ"
    need debugfs
    [[ -n "$WORK" ]] || make_work
    mkdir -p "$DUMP"
    local p img target
    for p in "${PARTITIONS[@]}"; do
        img="$lp/$p.img"
        [[ -f "$img" && -s "$img" ]] || continue
        # пропускаем LFS-указатели (~100 байт текста)
        if is_lfs_pointer "$img"; then
            die "$img — Git LFS pointer; сначала: git lfs pull"
        fi
        target="$DUMP/$p"
        log "debugfs rdump $p.img → $target"
        rm -rf "$target"
        dump_partition_image "$img" "$target"
    done
    # удобные ссылки как после mount в wiki (system/vendor/…)
    if [[ -d "$DUMP/system" && ! -e "$DUMP/system/system" ]]; then
        : # уже корень раздела
    fi
    log "дамп готов: $DUMP"
    du -sh "$DUMP"/* 2>/dev/null || true
}

list_images() {
    local lp=$1
    local p img
    for p in "${PARTITIONS[@]}"; do
        img="$lp/$p.img"
        [[ -f "$img" ]] || continue
        if is_lfs_pointer "$img"; then
            printf '%-12s  LFS-pointer  %s\n' "$p" "$img"
            continue
        fi
        printf '%-12s  %10s  %s\n' "$p" "$(du -h "$img" | awk '{print $1}')" "$img"
        if [[ "$LIST_ONLY" -eq 1 ]]; then
            debugfs -R 'ls -l /' "$img" 2>/dev/null | head -25 || true
            echo
        fi
    done
}

# --- main ---
case "$SOURCE_KIND" in
    update) extract_update_img "$SOURCE" ;;
    zip)    extract_zip "$SOURCE" ;;
    images) ;;
    *) die "внутренняя ошибка: SOURCE_KIND=$SOURCE_KIND" ;;
esac

if [[ "$SOURCE_KIND" == images || "$SOURCE_KIND" == filetree ]]; then
    if [[ "$LIST_ONLY" -eq 1 ]]; then
        [[ "$SOURCE_KIND" == images ]] || die "--list только для образов"
        list_images "$SOURCE"
        exit 0
    fi
    if [[ -n "$DUMP" ]]; then
        if [[ "$SOURCE_KIND" == images ]]; then
            dump_from_images "$SOURCE"
        else
            log "файлы уже в $DUMP (file-based)"
        fi
    elif [[ "$SOURCE_KIND" == images ]]; then
        list_images "$SOURCE"
        log "разделы готовы для сборки (build-sd.sh читает dl/a12/Image/lp)."
        log "Для файлового дерева как в wiki Finishing: добавьте --dump build/system_dump"
    fi
fi

log "готово"
