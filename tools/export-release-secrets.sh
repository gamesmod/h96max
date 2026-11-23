#!/usr/bin/env bash
# Архив секретов выпуска для release.yml и обратный импорт в GitHub Actions.
#
#   Экспорт (по умолчанию): прежние ключи из secrets/ + SHA-256 (и при желании URL) архива входов CI
#   → зашифрованный архив вне репозитория, например ~/h96max-release-secrets-20261123-0830.tar.gz.enc
#   Импорт (--apply FILE): расшифровать, проверить ключи и задать секреты подписи через `gh secret set` (stdin).
#
# Внутри архива (каталог h96max-release-secrets/):
#   secrets/tvbox-key.pem, secrets/tvbox-cert.pem, secrets/ota-key.pem, secrets/ota-cert.pem
#       — имена, под которыми release.yml восстанавливает ключи; содержимое = значения секретов
#       TVBOX_SIGNING_KEY, TVBOX_SIGNING_CERT, OTA_SIGNING_KEY, OTA_SIGNING_CERT
#   (устаревшие RELEASE_INPUTS_* больше не требуются: сток в Git LFS)
#   MANIFEST.txt — дата, коммит, версия, отпечатки сертификатов, инструкция импорта
#
# Шифрование: openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt, пароль из скрытого ввода (дважды) или
# из переменной RELEASE_SECRETS_PASSPHRASE; в аргументы команд пароль и ключи не попадают.
# Новые ключи не создаются; содержимое ключей не печатается.
set -euo pipefail
umask 077

REPO_SLUG="gamesmod/h96max"
R=$(cd "$(dirname "$0")/.." && pwd)
MODE=export
SECRETS_DIR="${SECRETS_DIR:-$R/secrets}"
INPUTS_ARCHIVE="${ARCHIVE:-}"
INPUTS_SHA=""
ARCHIVE_URL="${RELEASE_INPUTS_ARCHIVE_URL:-}"
ASK_URL=0
VERIFY_URL=0
OUTPUT=""
ENCRYPT=1
APPLY_FILE=""
RERUN=0
DISPATCH_REF=""
DRY_RUN=0
GIT_NAME=""
GIT_EMAIL=""
ITER=200000
# Отпечатки прежних ключей проекта (COMPONENTS.md, «Наши подписи»).
TVBOX_CERT_SHA256="f52a109a77d33ff95bc97760c4b85358b678384f805035ebe0ef9d692e26f388"
OTA_CERT_SHA256="906ea1f4a463757434aade51e7f469e7fd49030812e1db40cf74b55c2a7843c0"
BUNDLE=h96max-release-secrets

usage() {
    cat <<'USAGE'
Экспорт:  bash tools/export-release-secrets.sh [--archive INPUTS.tar.zst | --sha256 HEX] [--ask-url] [--output FILE]
Импорт:   bash tools/export-release-secrets.sh --apply FILE [--rerun | --dispatch REF]

Экспорт:
  --secrets-dir DIR   ключи tvbox-key.pem, tvbox-cert.pem, ota-key.pem, ota-cert.pem (по умолчанию ./secrets;
                      отдельные файлы — переменные TVBOX_KEY, TVBOX_CERT, OTA_KEY, OTA_CERT)
  --archive FILE      приватный архив входов CI; по умолчанию переменная ARCHIVE или самый новый *inputs*.tar.zst
                      в ., /tmp и ~ (SHA-256 считается из него)
  --sha256 HEX        SHA-256 архива входов, если самого архива на этом компьютере нет
  --ask-url           спросить HTTPS-адрес архива скрытым вводом (или переменная RELEASE_INPUTS_ARCHIVE_URL)
  --verify-url        скачать архив по адресу и сверить SHA-256
  --output FILE       куда записать архив (по умолчанию ~/h96max-release-secrets-ДАТА.tar.gz.enc);
                      внутри репозитория — только по пути, который игнорирует git
  --no-encrypt        записать архив БЕЗ шифрования (только осознанно, ключи будут в открытом виде)

Импорт:
  --apply FILE        расшифровать архив, проверить ключи и задать секреты GitHub Actions
  --repo OWNER/NAME   репозиторий (по умолчанию gamesmod/h96max)
  --git-name NAME, --git-email EMAIL   записать user.name/user.email в этот клон
  --rerun             перезапустить последний неуспешный release.yml на candidate/<версия>
  --dispatch REF      запустить release.yml вручную на ветке REF

Общие: --dry-run (проверить без записи), -h/--help.
Пароль: скрытый ввод или переменная RELEASE_SECRETS_PASSPHRASE.
USAGE
}
die() { echo "Ошибка: $*" >&2; exit 1; }
note() { echo "• $*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "не найдена команда $1"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --secrets-dir) SECRETS_DIR=${2:?}; shift 2;;
        --archive) INPUTS_ARCHIVE=${2:?}; shift 2;;
        --sha256) INPUTS_SHA=${2:?}; shift 2;;
        --ask-url) ASK_URL=1; shift;;
        --verify-url) VERIFY_URL=1; shift;;
        --output) OUTPUT=${2:?}; shift 2;;
        --no-encrypt) ENCRYPT=0; shift;;
        --apply|--import) MODE=apply; APPLY_FILE=${2:?}; shift 2;;
        --repo) REPO_SLUG=${2:?}; shift 2;;
        --git-name) GIT_NAME=${2:?}; shift 2;;
        --git-email) GIT_EMAIL=${2:?}; shift 2;;
        --rerun) RERUN=1; shift;;
        --dispatch) DISPATCH_REF=${2:?}; shift 2;;
        --dry-run) DRY_RUN=1; shift;;
        -h|--help) usage; exit 0;;
        *) usage >&2; die "неизвестный параметр: $1";;
    esac
done
[[ "$REPO_SLUG" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "--repo должен иметь вид OWNER/NAME"
abspath() { case "$1" in ""|/*) printf '%s' "$1";; *) printf '%s/%s' "$PWD" "$1";; esac; }
SECRETS_DIR=$(abspath "$SECRETS_DIR"); INPUTS_ARCHIVE=$(abspath "$INPUTS_ARCHIVE")
OUTPUT=$(abspath "$OUTPUT"); APPLY_FILE=$(abspath "$APPLY_FILE")

need openssl; need tar; need gzip
if command -v sha256sum >/dev/null 2>&1; then sha256_stdin() { sha256sum | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then sha256_stdin() { shasum -a 256 | cut -d' ' -f1; }
else die "не найдена sha256sum или shasum"; fi
VERSION=$(sed -n 's/^ro.tvbox.release=//p' "$R/build-sd.sh" 2>/dev/null | head -n 1 || true)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/h96max-secrets.XXXXXXXX")
trap 'rm -rf -- "$WORK"' EXIT

check_pair() {  # имя, ключ, сертификат, ожидаемый отпечаток; печатает только отпечаток
    local name=$1 key=$2 cert=$3 want=$4 fp kp cpub
    [ -f "$key" ] && [ -s "$key" ] || die "нет $key — нужен прежний ключ проекта; новые ключи не создаются"
    [ -f "$cert" ] && [ -s "$cert" ] || die "нет $cert — нужен прежний сертификат проекта"
    grep -q -- '-----BEGIN .*PRIVATE KEY-----' "$key" || die "$key не PEM закрытого ключа"
    if grep -q -- '-----BEGIN ENCRYPTED PRIVATE KEY-----' "$key"; then die "$key зашифрован паролем; CI ожидает ключ без пароля"; fi
    grep -q -- '-----BEGIN CERTIFICATE-----' "$cert" || die "$cert не PEM-сертификат"
    openssl pkey -in "$key" -passin pass: -noout >/dev/null 2>&1 || die "openssl не читает $key без пароля"
    fp=$(openssl x509 -in "$cert" -outform DER 2>/dev/null | sha256_stdin)
    [ "$fp" = "$want" ] || die "$name: SHA-256 сертификата $fp не совпадает с $want из COMPONENTS.md"
    kp=$(openssl pkey -in "$key" -passin pass: -pubout -outform DER 2>/dev/null | sha256_stdin)
    cpub=$(openssl x509 -in "$cert" -noout -pubkey 2>/dev/null | openssl pkey -pubin -outform DER 2>/dev/null | sha256_stdin)
    [ -n "$kp" ] && [ "$kp" = "$cpub" ] || die "$name: ключ не соответствует сертификату"
    note "$name: ключ и сертификат совпадают, отпечаток $fp"
}
check_url() {  # адрес, ожидаемый SHA-256
    case "$1" in https://*) ;; *) die "адрес архива должен начинаться с https://";; esac
    case "$1" in *github.com/$REPO_SLUG/*|*github.com/${REPO_SLUG}.git*)
        die "архив входов нельзя публиковать в публичном репозитории $REPO_SLUG";; esac
    if [ "$VERIFY_URL" = 1 ]; then
        need curl
        local got
        got=$(curl --fail --location --retry 3 --silent --show-error "$1" | sha256_stdin) \
            || die "архив по адресу не скачивается без авторизации — CI его тоже не скачает"
        [ "$got" = "$2" ] || die "SHA-256 по адресу ($got) не совпадает с $2"
        note "адрес отдаёт архив с тем же SHA-256"
    fi
}
passphrase() {  # $1=1 — запросить подтверждение
    if [ -n "${RELEASE_SECRETS_PASSPHRASE:-}" ]; then PASS=$RELEASE_SECRETS_PASSPHRASE; return; fi
    [ -t 0 ] || die "нет терминала для ввода пароля; задайте RELEASE_SECRETS_PASSPHRASE"
    local again
    printf 'Пароль архива: ' >&2; IFS= read -r -s PASS; echo >&2
    if [ "$1" = 1 ]; then
        [ "${#PASS}" -ge 12 ] || die "пароль короче 12 символов"
        printf 'Повторите пароль: ' >&2; IFS= read -r -s again; echo >&2
        [ "$PASS" = "$again" ] || die "пароли не совпадают"
    fi
}

# ===================== Экспорт =====================
if [ "$MODE" = export ]; then
    TK="${TVBOX_KEY:-$SECRETS_DIR/tvbox-key.pem}"; TC="${TVBOX_CERT:-$SECRETS_DIR/tvbox-cert.pem}"
    OK="${OTA_KEY:-$SECRETS_DIR/ota-key.pem}"; OC="${OTA_CERT:-$SECRETS_DIR/ota-cert.pem}"
    check_pair "APK (tvbox)" "$TK" "$TC" "$TVBOX_CERT_SHA256"
    check_pair "OTA" "$OK" "$OC" "$OTA_CERT_SHA256"

    # Приватный архив входов CI устарел (сток в Git LFS). Явные --archive/--sha256/--ask-url
    # сохраняются только для совместимости старых бандлов и в новый архив не обязательны.
    if [ -n "$INPUTS_ARCHIVE" ] && [ -z "$INPUTS_SHA" ]; then
        [ -f "$INPUTS_ARCHIVE" ] && [ -s "$INPUTS_ARCHIVE" ] || die "архив входов $INPUTS_ARCHIVE не найден"
        echo "  внимание: --archive устарел; SHA-256 посчитан, но release.yml его больше не использует" >&2
        INPUTS_SHA=$(sha256_stdin < "$INPUTS_ARCHIVE")
    fi
    if [ -n "$INPUTS_SHA" ]; then
        [[ "$INPUTS_SHA" =~ ^[0-9a-f]{64}$ ]] || die "SHA-256 должен состоять из 64 hex-символов в нижнем регистре"
    fi
    if [ -z "$ARCHIVE_URL" ] && [ "$ASK_URL" = 1 ]; then
        echo "  внимание: --ask-url устарел (Git LFS); пропускаю" >&2
    fi
    if [ -n "$ARCHIVE_URL" ]; then
        echo "  внимание: RELEASE_INPUTS_ARCHIVE_URL устарел и не нужен CI" >&2
        [ -n "$INPUTS_SHA" ] || die "адрес архива без его SHA-256 бесполезен"
        check_url "$ARCHIVE_URL" "$INPUTS_SHA"
    fi

    STAMP=$(date +%Y%m%d-%H%M)
    if [ -z "$OUTPUT" ]; then
        OUTPUT="$HOME/$BUNDLE-$STAMP.tar.gz"; [ "$ENCRYPT" = 0 ] || OUTPUT="$OUTPUT.enc"
    fi
    [ ! -e "$OUTPUT" ] && [ ! -L "$OUTPUT" ] || die "$OUTPUT уже существует; перезапись запрещена"
    OUTDIR=$(cd "$(dirname "$OUTPUT")" 2>/dev/null && pwd) || die "каталог для $OUTPUT не существует"
    if TOP=$(git -C "$OUTDIR" rev-parse --show-toplevel 2>/dev/null); then
        git -C "$OUTDIR" check-ignore -q "$OUTDIR/$(basename "$OUTPUT")" \
            || die "$OUTPUT внутри рабочего дерева git $TOP и не игнорируется — архив мог бы попасть в коммит"
    fi
    if [ "$ENCRYPT" = 0 ]; then
        echo "!!! ВНИМАНИЕ: --no-encrypt — закрытые ключи будут лежать в архиве в ОТКРЫТОМ виде." >&2
        echo "!!! Не публикуйте и не коммитьте $OUTPUT; храните его только на доверенном носителе." >&2
    fi

    B="$WORK/$BUNDLE"; mkdir -p "$B/secrets"
    cp "$TK" "$B/secrets/tvbox-key.pem"; cp "$TC" "$B/secrets/tvbox-cert.pem"
    cp "$OK" "$B/secrets/ota-key.pem"; cp "$OC" "$B/secrets/ota-cert.pem"
    [ -z "$INPUTS_SHA" ] || printf '%s' "$INPUTS_SHA" > "$B/RELEASE_INPUTS_SHA256"
    [ -z "$ARCHIVE_URL" ] || printf '%s' "$ARCHIVE_URL" > "$B/RELEASE_INPUTS_ARCHIVE_URL"
    COMMIT=$(git -C "$R" rev-parse HEAD 2>/dev/null || echo unknown)
    cat > "$B/MANIFEST.txt" <<MANIFEST
Panadonik / $REPO_SLUG — секреты GitHub Actions для release.yml
Создан: $(date '+%Y-%m-%d %H:%M %Z'); коммит: $COMMIT; версия прошивки: ${VERSION:-unknown}
Отпечатки SHA-256 сертификатов: tvbox $TVBOX_CERT_SHA256, OTA $OTA_CERT_SHA256

Файл → секрет:
  secrets/tvbox-key.pem      → TVBOX_SIGNING_KEY
  secrets/tvbox-cert.pem     → TVBOX_SIGNING_CERT
  secrets/ota-key.pem        → OTA_SIGNING_KEY
  secrets/ota-cert.pem       → OTA_SIGNING_CERT
Импорт из клона репозитория:
  bash tools/export-release-secrets.sh --apply ЭТОТ_АРХИВ [--rerun]
Импорт вручную из распакованного каталога (значения идут через stdin):
  for s in TVBOX_SIGNING_KEY:secrets/tvbox-key.pem TVBOX_SIGNING_CERT:secrets/tvbox-cert.pem \\
           OTA_SIGNING_KEY:secrets/ota-key.pem OTA_SIGNING_CERT:secrets/ota-cert.pem; do
      [ -f "\${s#*:}" ] && gh secret set "\${s%%:*}" -R $REPO_SLUG < "\${s#*:}"
  done
Расшифровка: openssl enc -d -aes-256-cbc -pbkdf2 -iter $ITER -in АРХИВ | tar -xzf -
MANIFEST

    if [ "$DRY_RUN" = 1 ]; then note "[dry-run] архив не записан: $OUTPUT"; exit 0; fi
    if [ "$ENCRYPT" = 1 ]; then
        passphrase 1
        tar -C "$WORK" -czf - "$BUNDLE" \
            | PASS="$PASS" openssl enc -aes-256-cbc -pbkdf2 -iter "$ITER" -salt -pass env:PASS > "$WORK/out"
    else
        tar -C "$WORK" -czf "$WORK/out" "$BUNDLE"
    fi
    (set -o noclobber; cat "$WORK/out" > "$OUTPUT")
    chmod 600 "$OUTPUT"
    note "архив секретов: $OUTPUT ($([ "$ENCRYPT" = 1 ] && echo "AES-256-CBC, PBKDF2 $ITER итераций" || echo 'БЕЗ шифрования'), режим 600)"
    note "импорт: bash tools/export-release-secrets.sh --apply $OUTPUT"
    exit 0
fi

# ===================== Импорт =====================
[ -f "$APPLY_FILE" ] && [ -s "$APPLY_FILE" ] || die "архив $APPLY_FILE не найден"
need gh
if [ "$(head -c 8 "$APPLY_FILE")" = "Salted__" ]; then
    passphrase 0
    PASS="$PASS" openssl enc -d -aes-256-cbc -pbkdf2 -iter "$ITER" -pass env:PASS -in "$APPLY_FILE" \
        > "$WORK/bundle.tar.gz" 2>/dev/null || die "не удалось расшифровать: неверный пароль или повреждённый архив"
else
    echo "  внимание: архив не зашифрован" >&2
    cp "$APPLY_FILE" "$WORK/bundle.tar.gz"
fi
tar -C "$WORK" -xzf "$WORK/bundle.tar.gz" "$BUNDLE" || die "в архиве нет каталога $BUNDLE"
B="$WORK/$BUNDLE"
check_pair "APK (tvbox)" "$B/secrets/tvbox-key.pem" "$B/secrets/tvbox-cert.pem" "$TVBOX_CERT_SHA256"
check_pair "OTA" "$B/secrets/ota-key.pem" "$B/secrets/ota-cert.pem" "$OTA_CERT_SHA256"
if git -C "$R" rev-parse --is-inside-work-tree >/dev/null 2>&1 && [ "$DRY_RUN" = 0 ]; then
    [ -z "$GIT_NAME" ] || git -C "$R" config --local user.name "$GIT_NAME"
    [ -z "$GIT_EMAIL" ] || git -C "$R" config --local user.email "$GIT_EMAIL"
fi
if ! gh auth status --hostname github.com >/dev/null 2>&1; then
    [ "$DRY_RUN" = 0 ] || die "gh не авторизован: выполните gh auth login"
    gh auth login --hostname github.com --git-protocol https --web --scopes repo,workflow
fi
[ "$DRY_RUN" = 1 ] || gh auth setup-git --hostname github.com
[ "$(gh api "repos/$REPO_SLUG" --jq '.permissions.admin' 2>/dev/null || true)" = "true" ] \
    || die "у текущего входа gh нет прав администратора $REPO_SLUG"

for pair in TVBOX_SIGNING_KEY:secrets/tvbox-key.pem TVBOX_SIGNING_CERT:secrets/tvbox-cert.pem \
            OTA_SIGNING_KEY:secrets/ota-key.pem OTA_SIGNING_CERT:secrets/ota-cert.pem; do
    name=${pair%%:*}; file="$B/${pair#*:}"
    if [ ! -f "$file" ]; then echo "  внимание: $name нет в архиве — секрет не изменён" >&2; continue; fi
    if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] gh secret set $name -R $REPO_SLUG (через stdin)"; continue; fi
    gh secret set "$name" -R "$REPO_SLUG" < "$file" >/dev/null
    note "секрет $name записан"
done
[ "$DRY_RUN" = 1 ] || gh secret list -R "$REPO_SLUG"

if [ "$RERUN" = 1 ]; then
    ID=$(gh run list -R "$REPO_SLUG" --workflow release.yml --branch "candidate/$VERSION" --limit 1 \
        --json databaseId,conclusion --jq '.[] | select(.conclusion != "success") | .databaseId')
    [ -n "$ID" ] || die "нет неуспешного запуска release.yml на candidate/$VERSION"
    [ "$DRY_RUN" = 1 ] || gh run rerun "$ID" -R "$REPO_SLUG"
    note "перезапуск: https://github.com/$REPO_SLUG/actions/runs/$ID"
fi
if [ -n "$DISPATCH_REF" ]; then
    [ "$DRY_RUN" = 1 ] || gh workflow run release.yml -R "$REPO_SLUG" --ref "$DISPATCH_REF"
    note "release.yml запущен на $DISPATCH_REF"
fi
note "готово"
