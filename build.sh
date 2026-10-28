#!/usr/bin/env bash
# 构建探针扩展 jar（Windows + 借用游戏自带 JDK 13）
set -euo pipefail
ROOT="/d/Game/铁锈战争/Rusted Warfare1.15"
APP="$ROOT/app"
BIN="/c/Program Files/Microsoft/jdk-17.0.6.10-hotspot/bin"
HERE="$(cd "$(dirname "$0")" && pwd)"
W() { cygpath -w "$1"; }

API=$(ls "$APP"/rwpp-core-api-*.jar)
DESK=$(ls "$APP"/rwpp-core-desktop-*.jar)
KOTLIN=$(ls "$APP"/kotlin-stdlib-2.2*.jar | head -1)
KOIN=$(ls "$APP"/koin-core-jvm-*.jar | head -1)
APPDESK=$(ls "$APP"/rwpp-desktop-*.jar | head -1)
COROUTINES=$(ls "$APP"/kotlinx-coroutines-core-jvm-*.jar | head -1)
CP="$(W "$API");$(W "$DESK");$(W "$APPDESK");$(W "$KOTLIN");$(W "$KOIN");$(W "$COROUTINES")"

rm -rf "$HERE/build"
mkdir -p "$HERE/build"

"$BIN/javac.exe" -encoding UTF-8 --release 17 -cp "$CP" -d "$(W "$HERE/build")" "$(W "$HERE/src/probe/ProbeLauncher.java")"

cp "$HERE/info.toml" "$HERE/build/info.toml"
rm -f "$HERE/probe.jar"
"$BIN/jar.exe" --create --file "$(W "$HERE/probe.jar")" -C "$(W "$HERE/build")" .
echo "built: $HERE/probe.jar"
"$BIN/jar.exe" --list --file "$(W "$HERE/probe.jar")"
