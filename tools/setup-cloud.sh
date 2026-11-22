#!/bin/bash
set -euo pipefail
# Debian 13 host; user-local JDK, verified by signed APT metadata.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
base=/workspace/setup-h96max
mkdir -p "$base/apt/lists/partial" "$base/debs" "$base/jdk"
cat > "$base/apt.conf" <<'EOF'
Dir::Etc::parts "-";
Dir::Etc::main "-";
Dir::Etc::sourcelist "/workspace/setup-h96max/sources.list";
Dir::Etc::sourceparts "-";
Dir::State::lists "/workspace/setup-h96max/apt/lists";
Dir::Cache "/workspace/setup-h96max/apt/cache";
APT::Get::List-Cleanup "false";
EOF
cat > "$base/sources.list" <<'EOF'
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://deb.debian.org/debian trixie main
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://security.debian.org/debian-security trixie-security main
EOF
export APT_CONFIG="$base/apt.conf"
/usr/bin/apt-get update
cd "$base/debs"
/usr/bin/apt-get download openjdk-21-jdk-headless openjdk-21-jre-headless
for package in ./*.deb; do dpkg-deb -x "$package" "$base/jdk"; done
unset APT_CONFIG
export PATH="$base/jdk/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH"
javac -version
java -version
cd "$ROOT"
bash -n build-sd.sh tools/*.sh apps/*/build.sh overlay/vendor/bin/*.sh
python3 -m json.tool obtainium-apps.json >/dev/null
python3 -B -m unittest discover -s tests -v > /tmp/h96max-tests.log 2>&1
tail -n 5 /tmp/h96max-tests.log
python3 tools/analyze-release-logs.py /tmp/h96max-tests.log --source 'автоматические тесты' --output /tmp/h96max-test-analysis.md --fail-on-errors
git diff --check
