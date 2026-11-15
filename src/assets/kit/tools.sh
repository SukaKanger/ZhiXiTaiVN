#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# tools.sh —— 把端上打包要用的工具「钉」下来，之后每次构建都走固定路径，
# 不再靠 PATH 碰运气，也不用重复联网。
#
# 用法：
#   bash tools.sh           检测并写 toolchain.env（缺的会列出来）
#   bash tools.sh --status  只打印 JSON 状态，给界面读
#   bash tools.sh --install 缺什么就用 pkg/apt 补什么（需要联网，只跑一次）
#
# 产物：$HOME/.webgal-station/toolchain.env
#   TOOL_AAPT2 / TOOL_AAPT / TOOL_D8 / TOOL_DX / TOOL_ZIPALIGN /
#   TOOL_APKSIGNER / TOOL_JAVAC / TOOL_KEYTOOL / TOOL_ANDROID_JAR / TOOL_ZIP

set -u
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
TOOLS_DIR="$HOME_DIR/.webgal-station/tools"
ENV_FILE="$HOME_DIR/.webgal-station/toolchain.env"
mkdir -p "$TOOLS_DIR/bin" "$TOOLS_DIR/lib" 2>/dev/null || true
export PATH="$TOOLS_DIR/bin:$PREFIX_DIR/bin:/system/bin:${PATH:-}"

STATUS_ONLY=0
DO_INSTALL=0
for a in ${1:-}; do
  case "$a" in
    --status) STATUS_ONLY=1 ;;
    --install) DO_INSTALL=1 ;;
  esac
done

which_tool() {
  local c
  for c in "$@"; do
    if command -v "$c" >/dev/null 2>&1; then
      command -v "$c"
      return 0
    fi
  done
  return 1
}

find_android_jar() {
  local cand
  for cand in \
    "$TOOLS_DIR/lib/android.jar" \
    "$HOME_DIR/.webgal-station/android.jar" \
    "$PREFIX_DIR/share/android.jar" \
    "$HOME_DIR/android.jar" \
    "$PREFIX_DIR"/opt/android-sdk/platforms/*/android.jar; do
    [ -f "$cand" ] && { printf '%s\n' "$cand"; return 0; }
  done
  return 1
}

AAPT2=$(which_tool aapt2) || AAPT2=""
AAPT=$(which_tool aapt) || AAPT=""
D8=$(which_tool d8) || D8=""
DX=$(which_tool dx) || DX=""
ZIPALIGN=$(which_tool zipalign) || ZIPALIGN=""
APKSIGNER=$(which_tool apksigner) || APKSIGNER=""
JAVAC=$(which_tool javac) || JAVAC=""
KEYTOOL=$(which_tool keytool) || KEYTOOL=""
ZIP=$(which_tool zip) || ZIP=""
ANDROID_JAR=$(find_android_jar) || ANDROID_JAR=""

json_esc() { printf '%s' "$1" | tr -d '"\\' ; }

if [ "$STATUS_ONLY" = "1" ]; then
  printf '{"ok":1'
  printf ',"aapt2":"%s"' "$(json_esc "$AAPT2")"
  printf ',"aapt":"%s"' "$(json_esc "$AAPT")"
  printf ',"d8":"%s"' "$(json_esc "$D8")"
  printf ',"dx":"%s"' "$(json_esc "$DX")"
  printf ',"zipalign":"%s"' "$(json_esc "$ZIPALIGN")"
  printf ',"apksigner":"%s"' "$(json_esc "$APKSIGNER")"
  printf ',"javac":"%s"' "$(json_esc "$JAVAC")"
  printf ',"keytool":"%s"' "$(json_esc "$KEYTOOL")"
  printf ',"androidJar":"%s"' "$(json_esc "$ANDROID_JAR")"
  printf ',"toolsDir":"%s"' "$(json_esc "$TOOLS_DIR")"
  printf '}\n'
  exit 0
fi

echo "工具链检测"
echo "  目录  $TOOLS_DIR"
echo "  aapt2      ${AAPT2:-缺}"
echo "  aapt       ${AAPT:-缺}"
echo "  d8         ${D8:-缺}"
echo "  dx         ${DX:-缺}"
echo "  zipalign   ${ZIPALIGN:-缺}"
echo "  apksigner  ${APKSIGNER:-缺}"
echo "  javac      ${JAVAC:-缺}"
echo "  keytool    ${KEYTOOL:-缺}"
echo "  android.jar ${ANDROID_JAR:-缺}"

need_install=""
[ -z "$JAVAC" ] && need_install="$need_install openjdk-17"
[ -z "$AAPT2" ] && [ -z "$AAPT" ] && need_install="$need_install aapt"
[ -z "$D8" ] && [ -z "$DX" ] && need_install="$need_install d8"
[ -z "$APKSIGNER" ] && need_install="$need_install apksigner"
[ -z "$ZIP" ] && need_install="$need_install zip"

if [ "$DO_INSTALL" = "1" ] && [ -n "$need_install" ]; then
  echo "补装：$need_install"
  # Termux 的 apt 对整包升级很敏感（真机实测 pkg upgrade 会把包管理器搞崩），
  # 这里只装缺的那几个，且不 -u。
  pkg install -y $need_install || apt install -y $need_install || true
  AAPT2=$(which_tool aapt2) || true
  AAPT=$(which_tool aapt) || true
  D8=$(which_tool d8) || true
  DX=$(which_tool dx) || true
  APKSIGNER=$(which_tool apksigner) || true
  JAVAC=$(which_tool javac) || true
  KEYTOOL=$(which_tool keytool) || true
  ZIP=$(which_tool zip) || true
fi

if [ -z "$ANDROID_JAR" ] && [ -n "$JAVAC" ]; then
  # 有些设备装了 openjdk 却没有 android.jar：尝试从已装的 aapt/android-sdk 里捞
  for cand in "$PREFIX_DIR"/opt/android-sdk/platforms/*/android.jar; do
    [ -f "$cand" ] && { ANDROID_JAR="$cand"; break; }
  done
fi

{
  echo "# WebGAL 移动工作站 · 工具链路径（由 tools.sh 生成，删除后可重新生成）"
  echo "TOOL_AAPT2='${AAPT2}'"
  echo "TOOL_AAPT='${AAPT}'"
  echo "TOOL_D8='${D8}'"
  echo "TOOL_DX='${DX}'"
  echo "TOOL_ZIPALIGN='${ZIPALIGN}'"
  echo "TOOL_APKSIGNER='${APKSIGNER}'"
  echo "TOOL_JAVAC='${JAVAC}'"
  echo "TOOL_KEYTOOL='${KEYTOOL}'"
  echo "TOOL_ZIP='${ZIP}'"
  echo "TOOL_ANDROID_JAR='${ANDROID_JAR}'"
} > "$ENV_FILE"

echo ""
echo "已写入 $ENV_FILE"
[ -n "$need_install" ] && echo "还缺工具：$need_install —— 再跑一次 bash tools.sh --install"
exit 0
