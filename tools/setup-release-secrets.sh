#!/usr/bin/env bash
# Настройка git/gh и секретов GitHub Actions для .github/workflows/release.yml.
#
# Секреты (формат — ровно то, что ждёт release.yml):
#   TVBOX_SIGNING_KEY   содержимое secrets/tvbox-key.pem  (PEM закрытого ключа без пароля, как есть)
#   TVBOX_SIGNING_CERT  содержимое secrets/tvbox-cert.pem (PEM X.509, как есть)
#   OTA_SIGNING_KEY     содержимое secrets/ota-key.pem    (PEM закрытого ключа без пароля, как есть)
#   OTA_SIGNING_CERT    содержимое secrets/ota-cert.pem   (PEM X.509, как есть)
#   RELEASE_INPUTS_ARCHIVE_URL  HTTPS-адрес приватного архива входов (.tar.zst из tools/package-ci-inputs.sh),
#                               который скачивается обычным `curl -L` без авторизации
#   RELEASE_INPUTS_SHA256       64 hex-символа SHA-256 этого архива
# CI пишет ключи обратно в secrets/*.pem через printf, поэтому base64/pk8 не нужны.
#
# Ключи никогда не генерируются, не печатаются и не попадают в аргументы команд: в `gh secret set` они
# передаются через stdin из файла. Сертификаты сверяются с отпечатками из COMPONENTS.md.
set -euo pipefail
umask 077

REPO_SLUG="gamesmod/h96max"
R=$(cd "$(dirname "$0")/.." && pwd)
SECRETS_DIR="$R/secrets"
ARCHIVE=""
PACKAGE_ARCHIVE=""
ARCHIVE_URL="${RELEASE_INPUTS_ARCHIVE_URL:-}"
VERIFY_URL=0
KEYS_ONLY=0
SKIP_GIT=0
SET_REMOTE=0
GIT_NAME=""
GIT_EMAIL=""
DRY_RUN=0
RERUN=0
DISPATCH_REF=""
# Отпечатки прежних ключей проекта (COMPONENTS.md, «Наши подписи»). Подмена ключа ломает обновления.
TVBOX_CERT_SHA256="f52a109a77d33ff95bc97760c4b85358b678384f805035ebe0ef9d692e26f388"
OTA_CERT_SHA256="906ea1f4a463757434aade51e7f469e7fd49030812e1db40cf74b55c2a7843c0"

usage() {
    cat <<'USAGE'
Использование: tools/setup-release-secrets.sh [параметры]

Git и gh:
  --repo OWNER/NAME        репозиторий GitHub (по умолчанию gamesmod/h96max)
  --git-name NAME          записать user.name в .git/config этого клона
  --git-email EMAIL        записать user.email в .git/config этого клона
  --set-remote             перенастроить origin на https://github.com/OWNER/NAME.git
  --skip-git               не трогать git/gh-настройки (только проверить вход gh)

Ключи и архив входов:
  --secrets-dir DIR        каталог с tvbox-key.pem, tvbox-cert.pem, ota-key.pem, ota-cert.pem (по умолчанию ./secrets)
  --archive FILE           готовый приватный архив входов (.tar.zst); SHA-256 считается локально
  --package-archive FILE   сначала создать архив через tools/package-ci-inputs.sh FILE (файл не должен существовать)
  --archive-url URL        HTTPS-адрес, по которому CI скачает этот архив без авторизации.
                           Лучше передать через переменную окружения RELEASE_INPUTS_ARCHIVE_URL или ввести по запросу,
                           чтобы адрес не попал в историю shell.
  --verify-url             скачать архив по URL и сверить SHA-256 до записи секретов
  --keys-only              задать только четыре секрета ключей/сертификатов

Запуск сборки (только по явному флагу):
  --rerun                  перезапустить последний неуспешный запуск release.yml на candidate/<версия>
  --dispatch REF           запустить release.yml вручную (workflow_dispatch) на ветке REF

  --dry-run                всё проверить, но ничего не менять и не записывать секреты
  -h, --help               эта справка
USAGE
}

die() { echo "Ошибка: $*" >&2; exit 1; }
note() { echo "• $*"; }
run() { if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] $*"; else "$@"; fi; }

while [ $# -gt 0 ]; do
    case "$1" in
        --repo) REPO_SLUG=${2:?}; shift 2;;
        --git-name) GIT_NAME=${2:?}; shift 2;;
        --git-email) GIT_EMAIL=${2:?}; shift 2;;
        --set-remote) SET_REMOTE=1; shift;;
        --skip-git) SKIP_GIT=1; shift;;
        --secrets-dir) SECRETS_DIR=${2:?}; shift 2;;
        --archive) ARCHIVE=${2:?}; shift 2;;
        --package-archive) PACKAGE_ARCHIVE=${2:?}; shift 2;;
        --archive-url) ARCHIVE_URL=${2:?}; shift 2;;
        --verify-url) VERIFY_URL=1; shift;;
        --keys-only) KEYS_ONLY=1; shift;;
        --rerun) RERUN=1; shift;;
        --dispatch) DISPATCH_REF=${2:?}; shift 2;;
        --dry-run) DRY_RUN=1; shift;;
        -h|--help) usage; exit 0;;
        *) usage >&2; die "неизвестный параметр: $1";;
    esac
done
[[ "$REPO_SLUG" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "--repo должен иметь вид OWNER/NAME"
[ -z "$ARCHIVE" ] || [ -z "$PACKAGE_ARCHIVE" ] || die "укажите только один из --archive и --package-archive"
# Относительные пути — от каталога запуска, до перехода в корень репозитория.
abspath() { case "$1" in ""|/*) printf '%s' "$1";; *) printf '%s/%s' "$PWD" "$1";; esac; }
SECRETS_DIR=$(abspath "$SECRETS_DIR"); ARCHIVE=$(abspath "$ARCHIVE"); PACKAGE_ARCHIVE=$(abspath "$PACKAGE_ARCHIVE")

# ---------- 1. Инструменты ----------
need() { command -v "$1" >/dev/null 2>&1 || die "не найдена команда $1 ($2)"; }
need git "https://git-scm.com"
need gh "https://cli.github.com"
need openssl "нужна для проверки ключей и сертификатов"
need curl "нужна для --verify-url"
if command -v sha256sum >/dev/null 2>&1; then sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else die "не найдена sha256sum или shasum"; fi
sha256_stdin() { if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
VERSION=$(sed -n 's/^ro.tvbox.release=//p' "$R/build-sd.sh" | head -n 1)
[ -n "$VERSION" ] || die "не удалось прочитать ro.tvbox.release из build-sd.sh"
note "Репозиторий $REPO_SLUG, версия прошивки $VERSION$([ "$DRY_RUN" = 1 ] && echo ', режим --dry-run')"

cd "$R"
# ---------- 2. Ключи и сертификаты (без вывода содержимого) ----------
check_pair() {  # имя, ключ, сертификат, ожидаемый отпечаток
    local name=$1 key=$2 cert=$3 want=$4 fp kp cpub
    [ -f "$key" ] && [ -s "$key" ] || die "нет $key — восстановите прежний ключ проекта; новые ключи не создаются"
    [ -f "$cert" ] && [ -s "$cert" ] || die "нет $cert — восстановите прежний сертификат проекта"
    grep -q -- '-----BEGIN .*PRIVATE KEY-----' "$key" || die "$key не PEM закрытого ключа"
    grep -q -- '-----BEGIN ENCRYPTED PRIVATE KEY-----' "$key" && die "$key зашифрован паролем; CI ожидает ключ без пароля"
    grep -q -- '-----BEGIN CERTIFICATE-----' "$cert" || die "$cert не PEM-сертификат"
    openssl pkey -in "$key" -passin pass: -noout >/dev/null 2>&1 || die "openssl не читает $key без пароля"
    fp=$(openssl x509 -in "$cert" -outform DER 2>/dev/null | sha256_stdin)
    [ "$fp" = "$want" ] || die "$name: SHA-256 сертификата $fp не совпадает с $want из COMPONENTS.md"
    kp=$(openssl pkey -in "$key" -passin pass: -pubout -outform DER 2>/dev/null | sha256_stdin)
    cpub=$(openssl x509 -in "$cert" -noout -pubkey 2>/dev/null | openssl pkey -pubin -outform DER 2>/dev/null | sha256_stdin)
    [ -n "$kp" ] && [ "$kp" = "$cpub" ] || die "$name: ключ $key не соответствует сертификату $cert"
    if [ -n "$(find "$key" -perm -004 2>/dev/null)" ]; then echo "  внимание: $key доступен на чтение всем (chmod 600)" >&2; fi
    note "$name: ключ и сертификат совпадают, отпечаток $fp"
}
check_pair "APK (tvbox)" "$SECRETS_DIR/tvbox-key.pem" "$SECRETS_DIR/tvbox-cert.pem" "$TVBOX_CERT_SHA256"
check_pair "OTA" "$SECRETS_DIR/ota-key.pem" "$SECRETS_DIR/ota-cert.pem" "$OTA_CERT_SHA256"

# ---------- 3. Архив входов ----------
SHA=""
if [ "$KEYS_ONLY" = 0 ]; then
    if [ -n "$PACKAGE_ARCHIVE" ]; then
        if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] tools/package-ci-inputs.sh $PACKAGE_ARCHIVE"; ARCHIVE=""
        else "$R/tools/package-ci-inputs.sh" "$PACKAGE_ARCHIVE"; ARCHIVE=$PACKAGE_ARCHIVE; fi
    fi
    if [ -n "$ARCHIVE" ]; then
        [ -f "$ARCHIVE" ] && [ -s "$ARCHIVE" ] || die "архив $ARCHIVE не найден"
        if tar --zstd -tf "$ARCHIVE" > "${TMPDIR:-/tmp}/release-inputs-list.$$" 2>/dev/null; then
            for f in dl/a12/Image/lp/system.img dl/a12/Image/lp/vendor.img dl/a12/MiniLoaderAll.bin \
                     dl/apk/com.tvlauncher.apk dl/webview/webview-arm64.apk dl/torrserver/TorrServer-android-arm64 \
                     a9fw/fw_bcm4334b1_ag.bin stock/vfd/vfdsrv overlay-wifi/TvboxWifiOverlay.apk; do
                grep -qx -e "$f" -e "./$f" "${TMPDIR:-/tmp}/release-inputs-list.$$" \
                    || { rm -f "${TMPDIR:-/tmp}/release-inputs-list.$$"; die "в архиве нет $f, который проверяет release.yml"; }
            done
            rm -f "${TMPDIR:-/tmp}/release-inputs-list.$$"
            note "архив: обязательные файлы для release.yml на месте"
        else
            rm -f "${TMPDIR:-/tmp}/release-inputs-list.$$"
            echo "  внимание: tar --zstd не смог прочитать оглавление; состав архива не проверен" >&2
        fi
        note "считаю SHA-256 архива (может занять минуту)"
        SHA=$(sha256 "$ARCHIVE")
        note "SHA-256 архива: $SHA"
    elif [ "$DRY_RUN" = 0 ]; then
        die "нужен --archive FILE или --package-archive FILE (или --keys-only)"
    fi
    if [ -z "$ARCHIVE_URL" ] && [ -t 0 ] && [ "$DRY_RUN" = 0 ]; then
        printf 'HTTPS-адрес архива для CI (ввод скрыт): ' >&2; IFS= read -r -s ARCHIVE_URL; echo >&2
    fi
    [ -n "$ARCHIVE_URL" ] || [ "$DRY_RUN" = 1 ] || die "нужен адрес архива: --archive-url, RELEASE_INPUTS_ARCHIVE_URL или ввод по запросу"
    if [ -n "$ARCHIVE_URL" ]; then
        case "$ARCHIVE_URL" in https://*) ;; *) die "адрес архива должен начинаться с https://";; esac
        case "$ARCHIVE_URL" in *github.com/$REPO_SLUG/*|*github.com/${REPO_SLUG}.git*)
            die "архив нельзя публиковать в публичном репозитории $REPO_SLUG (AGENTS.md: архив не публиковать)";; esac
        if [ "$VERIFY_URL" = 1 ] && [ -n "$SHA" ]; then
            note "скачиваю архив по адресу и сверяю SHA-256"
            GOT=$(curl --fail --location --retry 3 --silent --show-error "$ARCHIVE_URL" | sha256_stdin) \
                || die "архив по адресу не скачивается без авторизации — CI его тоже не скачает"
            [ "$GOT" = "$SHA" ] || die "SHA-256 по адресу ($GOT) не совпадает с локальным архивом"
            note "адрес отдаёт тот же архив"
        fi
    fi
fi

# ---------- 4. Git и gh ----------
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "$R не является клоном git"
if [ "$SKIP_GIT" = 0 ]; then
    [ -z "$GIT_NAME" ] || run git config --local user.name "$GIT_NAME"
    [ -z "$GIT_EMAIL" ] || run git config --local user.email "$GIT_EMAIL"
    git config user.name >/dev/null || echo "  внимание: user.name не задан (--git-name)" >&2
    git config user.email >/dev/null || echo "  внимание: user.email не задан (--git-email)" >&2
    WANT="https://github.com/$REPO_SLUG.git"
    CUR=$(git remote get-url origin 2>/dev/null || true)
    if [ -z "$CUR" ]; then
        run git remote add origin "$WANT"
    elif ! printf '%s' "$CUR" | grep -Eqi "github\.com[:/]$REPO_SLUG(\.git)?/?$"; then
        if [ "$SET_REMOTE" = 1 ]; then run git remote set-url origin "$WANT"
        else echo "  внимание: origin=$CUR не указывает на $REPO_SLUG (--set-remote исправит)" >&2; fi
    fi
fi
if ! gh auth status --hostname github.com >/dev/null 2>&1; then
    [ "$DRY_RUN" = 0 ] || die "gh не авторизован: выполните gh auth login или запустите без --dry-run"
    note "Вход в GitHub CLI (браузер или код устройства); нужны права repo и workflow"
    gh auth login --hostname github.com --git-protocol https --web --scopes repo,workflow
fi
if [ "$SKIP_GIT" = 0 ]; then
    run gh auth setup-git --hostname github.com
    run git fetch --quiet origin
fi
ADMIN=$(gh api "repos/$REPO_SLUG" --jq '.permissions.admin' 2>/dev/null || echo "")
[ "$ADMIN" = "true" ] || die "у текущего входа gh нет прав администратора $REPO_SLUG (нужны для Actions secrets)"
note "gh: вход есть, права администратора $REPO_SLUG подтверждены"

# ---------- 5. Запись секретов ----------
set_file() {  # имя, файл → stdin gh, содержимое не печатается
    if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] gh secret set $1 -R $REPO_SLUG < $2"; return; fi
    gh secret set "$1" -R "$REPO_SLUG" < "$2" >/dev/null
    note "секрет $1 записан"
}
set_value() {  # имя; значение берётся из переменной окружения VALUE и передаётся через stdin
    if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] gh secret set $1 -R $REPO_SLUG (значение через stdin)"; return; fi
    printf '%s' "$VALUE" | gh secret set "$1" -R "$REPO_SLUG" >/dev/null
    note "секрет $1 записан"
}
set_file TVBOX_SIGNING_KEY "$SECRETS_DIR/tvbox-key.pem"
set_file TVBOX_SIGNING_CERT "$SECRETS_DIR/tvbox-cert.pem"
set_file OTA_SIGNING_KEY "$SECRETS_DIR/ota-key.pem"
set_file OTA_SIGNING_CERT "$SECRETS_DIR/ota-cert.pem"
if [ "$KEYS_ONLY" = 0 ]; then
    VALUE=$ARCHIVE_URL set_value RELEASE_INPUTS_ARCHIVE_URL
    VALUE=$SHA set_value RELEASE_INPUTS_SHA256
fi
[ "$DRY_RUN" = 1 ] || gh secret list -R "$REPO_SLUG"

# ---------- 6. Запуск сборки (только по флагу) ----------
if [ "$RERUN" = 1 ]; then
    ID=$(gh run list -R "$REPO_SLUG" --workflow release.yml --branch "candidate/$VERSION" --limit 1 \
        --json databaseId,conclusion --jq '.[] | select(.conclusion != "success") | .databaseId')
    [ -n "$ID" ] || die "нет неуспешного запуска release.yml на candidate/$VERSION для перезапуска"
    run gh run rerun "$ID" -R "$REPO_SLUG"
    note "перезапущен https://github.com/$REPO_SLUG/actions/runs/$ID"
fi
if [ -n "$DISPATCH_REF" ]; then
    run gh workflow run release.yml -R "$REPO_SLUG" --ref "$DISPATCH_REF"
    note "release.yml запущен вручную на $DISPATCH_REF: gh run list -R $REPO_SLUG --workflow release.yml"
fi
note "готово"
