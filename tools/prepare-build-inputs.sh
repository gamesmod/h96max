#!/bin/bash
# Готовит стоковые входы A12 для build-sd.sh / build-release.sh.
# Канонический путь — как LineageOS Wiki (extract blobs) + очистка LP:
#   tools/extract-vendor-blobs.sh → (опционально) prepare-ci-partitions → dl/a12
#
# По умолчанию только проверяет уже подготовленные входы (Git LFS после smudge).
# Из сырого стока:
#   tools/prepare-build-inputs.sh --update /path/to/update.img
#   TVBOX_UPDATE_IMG=/path/to/update.img tools/prepare-build-inputs.sh
#
# Флаги:
#   --check-only   только проверка (для build-sd.sh); без извлечения
#   --update FILE  извлечь Rockchip update.img, очистить LP, положить в dl/a12
#   --zip FILE     то же для OTA zip (payload / block-based)
#   --no-clean     после извлечения не гонять prepare-ci-partitions (сырые LP)
set -euo pipefail

R=$(cd "$(dirname "$0")/.." && pwd); cd "$R"
OUT=$R/dl/a12
UPDATE_IMG=${TVBOX_UPDATE_IMG:-}
ZIP=""
CHECK_ONLY=0
DO_CLEAN=1
PARTITIONS=(system system_ext vendor vendor_dlkm odm odm_dlkm product)
BOOT_IMAGES=(boot.img recovery.img uboot.img trust.img dtbo.img vbmeta.img baseparameter.img)

die() { echo "prepare-build-inputs: $*" >&2; exit 1; }
log() { echo "[prepare-inputs] $*"; }

is_lfs_pointer() {
    local f=$1 size
    [[ -f "$f" ]] || return 1
    size=$(wc -c <"$f" | tr -d ' ')
    [[ "$size" -lt 1024 ]] || return 1
    grep -q 'git-lfs.github.com/spec/v1' "$f" 2>/dev/null
}

require_stock_file() {
    local f=$1
    [[ -f "$f" && -s "$f" ]] || die "нет стокового входа: $f (git lfs pull или --update update.img)"
    if is_lfs_pointer "$f"; then
        die "LFS pointer не smudged: $f (git lfs pull)"
    fi
}

ensure_stock_layout() {
    local f p
    require_stock_file "$OUT/MiniLoaderAll.bin"
    for f in "${BOOT_IMAGES[@]}"; do
        require_stock_file "$OUT/Image/$f"
    done
    for p in "${PARTITIONS[@]}"; do
        require_stock_file "$OUT/Image/lp/$p.img"
    done
    # CI и релизный путь не принимают сырой super рядом с очищенными LP.
    if [[ -e "$OUT/Image/super.img" ]]; then
        die "dl/a12/Image/super.img не должен оставаться во входах сборки (удалите или извлеките с --update/--zip)"
    fi
    log "стоковые входы OK: $OUT"
}

install_cleaned_lp() {
    local raw_lp=$1
    local cleaned
    cleaned=$(mktemp -d "${TMPDIR:-/tmp}/h96max-clean-lp.XXXXXXXX")
    log "очистка LP → временный каталог (prepare-ci-partitions)"
    python3 -I "$R/tools/prepare-ci-partitions.py" "$raw_lp" "$cleaned"
    mkdir -p "$OUT/Image/lp"
    local p
    for p in "${PARTITIONS[@]}"; do
        [[ -f "$cleaned/$p.img" ]] || die "после очистки нет $p.img"
        cp -f "$cleaned/$p.img" "$OUT/Image/lp/$p.img"
    done
    [[ -f "$cleaned/cleanup-report.json" ]] && cp -f "$cleaned/cleanup-report.json" "$OUT/Image/lp/cleanup-report.json"
    rm -rf "$cleaned"
}

stage_from_extract() {
    local staged=$1
    [[ -d "$staged/Image" ]] || die "extract не создал $staged/Image"
    mkdir -p "$OUT/Image/lp"
    if [[ -f "$staged/MiniLoaderAll.bin" ]]; then
        cp -f "$staged/MiniLoaderAll.bin" "$OUT/MiniLoaderAll.bin"
    fi
    local f
    for f in "${BOOT_IMAGES[@]}"; do
        [[ -f "$staged/Image/$f" ]] || die "в извлечённом образе нет Image/$f"
        cp -f "$staged/Image/$f" "$OUT/Image/$f"
    done
    if [[ "$DO_CLEAN" -eq 1 ]]; then
        local raw_lp
        raw_lp=$(mktemp -d "${TMPDIR:-/tmp}/h96max-raw-lp.XXXXXXXX")
        local p
        for p in "${PARTITIONS[@]}"; do
            [[ -f "$staged/Image/lp/$p.img" ]] || die "в извлечённом образе нет lp/$p.img"
            cp -f "$staged/Image/lp/$p.img" "$raw_lp/$p.img"
        done
        install_cleaned_lp "$raw_lp"
        rm -rf "$raw_lp"
    else
        local p
        for p in "${PARTITIONS[@]}"; do
            [[ -f "$staged/Image/lp/$p.img" ]] || die "в извлечённом образе нет lp/$p.img"
            cp -f "$staged/Image/lp/$p.img" "$OUT/Image/lp/$p.img"
        done
        log "LP скопированы без prepare-ci-partitions (--no-clean)"
    fi
    # super только для lpunpack; во входах сборки не держим
    rm -f "$OUT/Image/super.img" "$staged/Image/super.img"
}

extract_and_stage() {
    local kind=$1
    local src=$2
    local staged
    staged=$(mktemp -d "${TMPDIR:-/tmp}/h96max-extract-stage.XXXXXXXX")
    log "извлечение ($kind): $src"
    case "$kind" in
        update)
            bash "$R/tools/extract-vendor-blobs.sh" --update "$src" --out "$staged" ;;
        zip)
            bash "$R/tools/extract-vendor-blobs.sh" --zip "$src" --out "$staged" ;;
        *)
            die "внутренняя ошибка kind=$kind" ;;
    esac
    stage_from_extract "$staged"
    rm -rf "$staged"
    ensure_stock_layout
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check-only) CHECK_ONLY=1; shift ;;
        --no-clean) DO_CLEAN=0; shift ;;
        --update)
            [[ $# -ge 2 ]] || die "--update требует путь"
            UPDATE_IMG=$2; shift 2 ;;
        --zip)
            [[ $# -ge 2 ]] || die "--zip требует путь"
            ZIP=$2; shift 2 ;;
        -h|--help)
            awk 'NR==1 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0 ;;
        *)
            die "неизвестный аргумент: $1" ;;
    esac
done

if [[ "$CHECK_ONLY" -eq 1 ]]; then
    [[ -z "$UPDATE_IMG" && -z "$ZIP" ]] || die "--check-only несовместим с --update/--zip"
    ensure_stock_layout
    exit 0
fi

if [[ -n "$UPDATE_IMG" && -n "$ZIP" ]]; then
    die "укажите только один источник: --update или --zip"
fi

if [[ -n "$UPDATE_IMG" ]]; then
    [[ -f "$UPDATE_IMG" ]] || die "нет файла: $UPDATE_IMG"
    extract_and_stage update "$UPDATE_IMG"
elif [[ -n "$ZIP" ]]; then
    [[ -f "$ZIP" ]] || die "нет файла: $ZIP"
    extract_and_stage zip "$ZIP"
else
    ensure_stock_layout
fi
