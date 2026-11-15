#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# probe.sh —— 设备画像 + 工具链自检 + 打包可行性判决。
# 结果写成同目录的 probe.json，工作站界面（环境页）读它；也能在 Termux 里手跑。
#
# 输出的 verdict 有四种：
#   ok        工具齐、内存够，按正常流程打（L2 真实工程也扛得住）
#   lite-only 工具齐但内存偏小：只建议打 L1 极小样本，真实工程容易被打断
#   pc-only   内存太小，端上打包不现实，走「生成构建工程 → 拷到电脑」通道
#   blocked   工具/空间缺件，先把依赖装上

set -u

HERE="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
OUT="$HERE/probe.json"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"

check() {
  # check <cmd...> → 打印可执行路径，找不到返回空
  local c
  for c in "$@"; do
    if command -v "$c" >/dev/null 2>&1; then
      command -v "$c"
      return 0
    fi
  done
  return 1
}

prop() { getprop "$1" 2>/dev/null | tr -d '\r\n'; }

num() {
  # 只保留数字，取不到就回 0
  printf '%s' "${1:-}" | tr -cd '0-9' | head -c 12
}

j() {
  # 把值里的 " 和 \ 去掉，避免写出坏 JSON
  local v
  v=$(printf '%s' "${2:-}" | tr -d '"\\\r\n')
  printf '"%s":"%s"' "$1" "$v"
}

JAVA=$(check javac) || JAVA=""
KEYTOOL=$(check keytool) || KEYTOOL=""
AAPT2=$(check aapt2) || AAPT2=""
AAPT=$(check aapt) || AAPT=""
D8=$(check d8) || D8=""
DX=$(check dx) || DX=""
APKSIGNER=$(check apksigner) || APKSIGNER=""
ZIPALIGN=$(check zipalign) || ZIPALIGN=""
ZIP=$(check zip) || ZIP=""

# 光「命令存在」不够：真机上踩过两回「文件在、但二进制跑不起来」——
# aapt2 缺 libc++ 符号、curl 缺 openssl 符号。这类残废工具会让构建在第①步就死，
# 但目录检查一律显示 ok。这里真跑一次，把结果分开记。
TOOL_RUN="ok"
AAPT2_BROKEN=""
CURL_BROKEN=""
if [ -n "$AAPT2" ]; then
  if ! aapt2 version >/dev/null 2>&1; then
    TOOL_RUN="broken"; AAPT2_BROKEN="aapt2"
  fi
fi
if [ -n "$AAPT" ] && [ -z "$AAPT2" ]; then
  if ! aapt version >/dev/null 2>&1; then
    TOOL_RUN="broken"; AAPT2_BROKEN="aapt"
  fi
fi
if command -v curl >/dev/null 2>&1; then
  if ! curl --version >/dev/null 2>&1; then
    CURL_BROKEN="curl"
  fi
fi

JAR=""
for cand in \
  "$HOME_DIR/.webgal-station/android.jar" \
  "$PREFIX_DIR/share/android.jar" \
  "$HOME_DIR/android.jar"; do
  [ -f "$cand" ] && { JAR="$cand"; break; }
done
if [ -z "$JAR" ]; then
  for cand in "$PREFIX_DIR"/opt/android-sdk/platforms/*/android.jar; do
    [ -f "$cand" ] && { JAR="$cand"; break; }
  done
fi

BUILDAPKS="missing"
if [ -d "$HOME_DIR/buildAPKs" ] || [ -d "$HOME_DIR/BuildAPKs" ]; then
  BUILDAPKS="ok"
fi

STORAGE="missing"
[ -d /sdcard/Download ] && STORAGE="ok"

BOOT="missing"
[ -x "$HOME_DIR/.webgal-station/run.sh" ] && BOOT="ok"

ALLOWEXT="missing"
grep -q '^allow-external-apps=true' "$HOME_DIR/.termux/termux.properties" 2>/dev/null && ALLOWEXT="ok"

# 注意：Android 上的 sed 不认 \s，一律用 awk 按字段取
MEM_KB=$(num "$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null | head -n1)")
AVAIL_KB=$(num "$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null | head -n1)")
[ -n "$MEM_KB" ] && [ "$MEM_KB" -gt 0 ] || MEM_KB=0
RAM_MB=$(( MEM_KB / 1024 ))
AVAIL_MB=$(( AVAIL_KB / 1024 ))

CPUS=$(num "$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo 2>/dev/null || echo 1)")
[ -n "$CPUS" ] && [ "$CPUS" -gt 0 ] || CPUS=1

# df 在 Android 上只按 1K 块输出，没有 -m
FREE_KB=$(num "$(df /sdcard 2>/dev/null | awk 'NR==2{print $4}')")
FREE_MB=$(( FREE_KB / 1024 ))

ABI=$(prop ro.product.cpu.abi)
SDK=$(prop ro.build.version.sdk)
MODEL=$(prop ro.product.model)
WEBVIEW=$(prop ro.build.version.webview)
if [ -z "$WEBVIEW" ]; then
  WEBVIEW=$(dumpsys package com.google.android.webview 2>/dev/null \
    | sed -n 's/.*versionName=\([0-9.]*\).*/\1/p' | head -n1)
fi
[ -n "$WEBVIEW" ] || WEBVIEW="unknown"

# 低配机给 javac 的堆上限：物理内存的 1/3，小于 2 GB 的机器压到 1/4，夹在 256~1024 MB
JAVA_XMX=$(( RAM_MB / 3 ))
[ "$RAM_MB" -lt 2048 ] && JAVA_XMX=$(( RAM_MB / 4 ))
[ "$JAVA_XMX" -lt 256 ] && JAVA_XMX=256
[ "$JAVA_XMX" -gt 1024 ] && JAVA_XMX=1024

TOOLS_OK=0
[ -n "$JAVA" ] && [ -n "$AAPT2$AAPT" ] && [ -n "$D8$DX" ] && [ -n "$APKSIGNER" ] && [ -n "$JAR" ] && TOOLS_OK=1
ALL_OK=$TOOLS_OK

# ---- 判决
if [ "$TOOLS_OK" -eq 0 ]; then
  VERDICT="blocked"
  VERDICT_TEXT="工具链不全：缺 $(
    [ -z "$JAVA" ] && printf 'javac '
    [ -z "$AAPT2$AAPT" ] && printf 'aapt '
    [ -z "$D8$DX" ] && printf 'd8/dx '
    [ -z "$APKSIGNER" ] && printf 'apksigner '
    [ -z "$JAR" ] && printf 'android.jar '
  )。先把环境页的依赖装齐。"
elif [ "$TOOL_RUN" = "broken" ]; then
  VERDICT="blocked"
  VERDICT_TEXT="${AAPT2_BROKEN} 装了但跑不起来（缺动态库符号）。Termux 里执行：apt install -y libc++${CURL_BROKEN:+ openssl}，然后重跑自检。"
elif [ "$FREE_MB" -gt 0 ] && [ "$FREE_MB" -lt 300 ]; then
  VERDICT="blocked"
  VERDICT_TEXT="可用存储只有 ${FREE_MB} MB，装一个 20 MB 的包 + 中间产物都不够，先清空间。"
elif [ "$RAM_MB" -lt 2048 ]; then
  VERDICT="pc-only"
  VERDICT_TEXT="RAM 只有 ${RAM_MB} MB，端上跑 javac 基本会被系统杀掉。建议「生成构建工程」后拷到电脑打。"
elif [ "$RAM_MB" -lt 3072 ]; then
  VERDICT="lite-only"
  VERDICT_TEXT="RAM ${RAM_MB} MB：够打 L1 极小样本（几十 KB 那种），打 20 MB 级工程有被中断的风险。"
else
  VERDICT="ok"
  VERDICT_TEXT="RAM ${RAM_MB} MB / ${CPUS} 核：端上打包应该没问题，L1、L2 都可以试。"
fi

{
  printf '{'
  j "java"      "$([ -n "$JAVA" ] && echo ok)"
  printf ','
  j "keytool"   "$([ -n "$KEYTOOL" ] && echo ok)"
  printf ','
  j "aapt2"     "$([ -n "$AAPT2" ] && echo ok)"
  printf ','
  j "aapt"      "$([ -n "$AAPT" ] && echo ok)"
  printf ','
  j "d8"        "$([ -n "$D8" ] && echo ok)"
  printf ','
  j "dx"        "$([ -n "$DX" ] && echo ok)"
  printf ','
  j "apksigner" "$([ -n "$APKSIGNER" ] && echo ok)"
  printf ','
  j "zipalign"  "$([ -n "$ZIPALIGN" ] && echo ok)"
  printf ','
  j "zip"       "$([ -n "$ZIP" ] && echo ok)"
  printf ','
  j "androidJar" "$([ -n "$JAR" ] && echo ok)"
  printf ','
  j "buildApks" "$BUILDAPKS"
  printf ','
  j "storage"   "$STORAGE"
  printf ','
  j "bootstrap" "$BOOT"
  printf ','
  j "allowExternal" "$ALLOWEXT"
  printf ','
  j "toolRun"   "$TOOL_RUN"
  printf ','
  j "curlBroken" "$CURL_BROKEN"
  printf ','
  j "javaPath"   "$JAVA"
  printf ','
  j "aaptPath"   "$AAPT2$AAPT"
  printf ','
  j "jarPath"    "$JAR"
  printf ','
  printf '"allOk":%s' "$ALL_OK"
  printf ','
  printf '"ramMB":%s' "$RAM_MB"
  printf ',"availMB":%s' "$AVAIL_MB"
  printf ',"freeMB":%s' "$FREE_MB"
  printf ',"cpuCores":%s' "$CPUS"
  printf ',"javaXmxMB":%s' "$JAVA_XMX"
  printf ','
  j "abi"      "$ABI"
  SDK_NUM=$(num "$SDK")
  [ -n "$SDK_NUM" ] || SDK_NUM=0
  printf ',"sdk":%s' "$SDK_NUM"
  printf ','
  j "model"    "$MODEL"
  printf ','
  j "webview"  "$WEBVIEW"
  printf ','
  j "verdict"  "$VERDICT"
  printf ','
  j "verdictText" "$VERDICT_TEXT"
  printf ',"ts":"%s"' "$(date '+%Y-%m-%d %H:%M:%S')"
  printf '}\n'
} > "$OUT"

echo "probe.json 已写入：$OUT"
echo "设备  ${MODEL:-未知} · Android API ${SDK:-?} · ${ABI:-?} · WebView ${WEBVIEW}"
echo "内存  ${RAM_MB} MB（可用 ${AVAIL_MB} MB）· ${CPUS} 核 · 存储剩 ${FREE_MB} MB"
echo "判决  ${VERDICT} — ${VERDICT_TEXT}"
echo "建议  javac 堆上限 ${JAVA_XMX} MB"
cat "$OUT"
