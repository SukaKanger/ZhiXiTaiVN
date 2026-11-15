#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# install_offline.sh —— 完全离线装工具链。不联网，不 pkg install，全靠随包的 .deb。
#
#   bash /sdcard/Download/WebGALStation/offline/install_offline.sh
#
# 做的事：
#   1. dpkg 解开随包的 .deb，再统一 configure（顺序由 dpkg 自己排）
#   2. 把随包的 android.jar 放到 ~/.webgal-station/
#   3. 写跳板 run.sh、打开 allow-external-apps
#   4. 钉工具链路径，落一个 .tools-installed 标记给工作站读

set -u

SD="${STATION_SD:-/sdcard/Download/WebGALStation}"
OFF="$SD/offline"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
STATION="$HOME_DIR/.webgal-station"

say() { printf '\n\033[1;33m▸ %s\033[0m\n' "$*"; }
ok()  { printf '  \033[0;32m✔\033[0m %s\n' "$*"; }
bad() { printf '  \033[0;31m✘\033[0m %s\n' "$*"; }

say "0. 环境检查"
if [ ! -d "$PREFIX_DIR" ] || ! command -v dpkg >/dev/null 2>&1; then
  bad "这不像 Termux（没有 dpkg）。请在 Termux 里运行本脚本。"
  exit 1
fi
ok "Termux 前缀 $PREFIX_DIR"
if [ ! -r "$SD" ]; then
  if command -v termux-setup-storage >/dev/null 2>&1; then
    echo "  正在申请存储权限，弹窗请点「允许」…"
    termux-setup-storage >/dev/null 2>&1 || true
    i=0
    while [ $i -lt 30 ] && [ ! -r "$SD" ]; do sleep 1; i=$((i+1)); done
  fi
fi
if [ ! -d "$OFF/debs" ]; then
  bad "离线包不在：$OFF/debs。先在「环境」页点「释放离线工具包」，再跑本脚本。"
  exit 1
fi
COUNT=$(ls "$OFF/debs"/*.deb 2>/dev/null | wc -l)
ok "离线 deb $COUNT 个"
[ -f "$OFF/android.jar" ] || bad "android.jar 不在，编译会失败"

say "1. 解开 .deb（只解不配，先让文件全落地）"
cd "$OFF/debs" || exit 1
dpkg --unpack ./*.deb 2>&1 | tail -n 20
ok "解包结束（报 already installed 是正常的，重复跑就这样）"

say "2. 统一 configure（依赖顺序由 dpkg 排）"
dpkg --configure -a 2>&1 | tail -n 30
ok "configure 结束"

say "3. 关键命令能不能真跑"
MISS=""
for c in javac keytool d8 apksigner aapt2 aapt zipalign zip unzip; do
  if command -v "$c" >/dev/null 2>&1; then
    ok "$c → $(command -v "$c")"
  else
    bad "$c 缺失"
    MISS="$MISS $c"
  fi
done
if command -v d8 >/dev/null 2>&1 && ! d8 --version >/dev/null 2>&1; then
  bad "d8 跑不起来（多半缺 java），先查 java -version"
fi
if command -v aapt2 >/dev/null 2>&1 && ! aapt2 version >/dev/null 2>&1; then
  bad "aapt2 跑不起来（缺 libc++ 符号）"
fi
[ -n "$MISS" ] && bad "还缺：$MISS（先看上面 dpkg 的报错原文）"

say "4. android.jar 与跳板"
mkdir -p "$STATION"
if [ -f "$OFF/android.jar" ]; then
  cp "$OFF/android.jar" "$STATION/android.jar"
  ok "$(du -h "$STATION/android.jar" | cut -f1) $STATION/android.jar"
fi
if [ -f "$SD/kit/run.sh" ]; then
  cp "$SD/kit/run.sh" "$STATION/run.sh"
  chmod +x "$STATION/run.sh"
  ok "$STATION/run.sh"
else
  bad "kit/run.sh 不在，先在工作站点一次「部署脚本」"
fi

say "5. 打开 allow-external-apps"
mkdir -p "$HOME_DIR/.termux"
if grep -q '^allow-external-apps=true' "$HOME_DIR/.termux/termux.properties" 2>/dev/null; then
  ok "已经开着"
else
  echo 'allow-external-apps=true' >> "$HOME_DIR/.termux/termux.properties"
  ok "已写入 termux.properties"
fi
if command -v termux-reload-settings >/dev/null 2>&1; then
  termux-reload-settings >/dev/null 2>&1 && ok "已重载 Termux 设置"
fi

say "6. 钉工具链路径"
if [ -f "$SD/kit/tools.sh" ]; then
  bash "$SD/kit/tools.sh" || true
fi

touch "$OFF/.tools-installed"
say "完成"
cat <<'TAIL'

  回工作站 →「环境」→「运行自检」，全绿就能断网打包。
  报错就把上面最后 30 行原样发我，别截一半。
TAIL
