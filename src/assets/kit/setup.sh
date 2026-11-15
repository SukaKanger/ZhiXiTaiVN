#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# setup.sh —— 一次性把 Termux 侧的环境铺好。只跑一次，跑完就不用管了。
#
#   bash /sdcard/Download/WebGALStation/kit/setup.sh
#
# 做的事：
#   1. 装依赖：openjdk-17 / aapt / apksigner / d8 / zipalign / zip
#   2. 抓一份 android.jar（编译要用）
#   3. 在 Termux 私有目录写跳板脚本 ~/.webgal-station/run.sh
#   4. 打开 allow-external-apps，让工作站能用 RUN_COMMAND 拉起构建

set -u

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
STATION="$HOME_DIR/.webgal-station"
JAR="$STATION/android.jar"

say() { printf '\n\033[1;33m▸ %s\033[0m\n' "$*"; }
ok()  { printf '  \033[0;32m✔\033[0m %s\n' "$*"; }
bad() { printf '  \033[0;31m✘\033[0m %s\n' "$*"; }

say "0. 环境检查"
if [ ! -d "$PREFIX_DIR" ] || ! command -v pkg >/dev/null 2>&1; then
  bad "这不像 Termux。请在 Termux 里运行本脚本。"
  exit 1
fi
ok "Termux 前缀 $PREFIX_DIR"

SD="${STATION_SD:-/sdcard/Download/WebGALStation}"
OFF="$SD/offline"
# 玩家最烦的一步：没存储权限就卡死。这里自动拉起授权，最多等 30 秒，不再让人自己猜。
if [ ! -r "$SD" ]; then
  if command -v termux-setup-storage >/dev/null 2>&1; then
    echo "  正在申请存储权限，弹窗请点「允许」…"
    termux-setup-storage >/dev/null 2>&1 || true
    i=0
    while [ $i -lt 30 ] && [ ! -r "$SD" ]; do sleep 1; i=$((i+1)); done
  fi
fi
if [ ! -r "$SD" ]; then
  bad "/sdcard 还是读不到。去系统设置给 Termux 存储权限后再跑一次。"
  exit 1
fi
ok "共享存储可读"

# 完全体：包里已经带了 54 个 deb 和 android.jar。优先走离线安装，绝不碰网络源，
# 否则国内不挂 VPN 就会卡在 pkg update 几分钟甚至直接失败。
if [ -f "$OFF/install_offline.sh" ] && [ -d "$OFF/debs" ]; then
  say "检测到随包离线工具链，直接离线安装（不联网，不跑 pkg）"
  exec bash "$OFF/install_offline.sh"
fi

say "1. 安装依赖（缺什么装什么，装不上就跳过）"
for p in openjdk-17 aapt aapt2 apksigner d8 dx zipalign zip unzip curl; do
  if pkg install -y "$p" >/dev/null 2>&1; then
    ok "pkg install $p"
  else
    if command -v "$p" >/dev/null 2>&1; then
      ok "$p 已存在"
    else
      bad "跳过 $p（源里没有，或用别的包名提供）"
    fi
  fi
done

say "1.5 二进制能不能真跑起来"
# 真机踩过：F-Droid 的 Termux 自带 bootstrap 偏旧，库里装的 aapt2 / curl 需要更新的
# libc++ / openssl 符号，目录里明明有文件，一执行就 "CANNOT LINK EXECUTABLE"。
# 这类残废工具会让构建死在第①步，所以装完依赖必须真跑一遍。
if command -v aapt2 >/dev/null 2>&1 && ! aapt2 version >/dev/null 2>&1; then
  bad "aapt2 跑不起来（缺 libc++ 符号），补库"
  if apt install -y libc++ >/dev/null 2>&1; then
    ok "已升级 libc++"
  else
    bad "libc++ 装不上，手动执行：apt install -y libc++"
  fi
  if aapt2 version >/dev/null 2>&1; then
    ok "aapt2 现在能跑了"
  else
    bad "aapt2 仍然跑不起来，再试：apt install --reinstall -y aapt2"
  fi
else
  [ -n "$(command -v aapt2)" ] && ok "aapt2 可执行"
fi
if command -v curl >/dev/null 2>&1 && ! curl --version >/dev/null 2>&1; then
  bad "curl 跑不起来（缺 SSL 符号），补 openssl"
  apt install -y openssl >/dev/null 2>&1 && ok "已升级 openssl" || bad "openssl 装不上"
fi

say "2. 检查关键命令"
MISSING=""
for c in javac keytool apksigner; do
  if command -v "$c" >/dev/null 2>&1; then ok "$c → $(command -v "$c")"; else bad "$c 缺失"; MISSING="$MISSING $c"; fi
done
if command -v aapt2 >/dev/null 2>&1; then ok "aapt2 → $(command -v aapt2)"; \
elif command -v aapt >/dev/null 2>&1; then ok "aapt → $(command -v aapt)（v1，够用）"; \
else bad "aapt2 / aapt 都缺"; MISSING="$MISSING aapt"; fi
if command -v d8 >/dev/null 2>&1; then ok "d8 → $(command -v d8)"; \
elif command -v dx >/dev/null 2>&1; then ok "dx → $(command -v dx)"; \
else bad "d8 / dx 都缺"; MISSING="$MISSING dexer"; fi

if [ -n "$MISSING" ]; then
  bad "还缺：$MISSING"
  echo "  多半是源没更新，试试 pkg update && pkg upgrade 后再跑一次。"
fi

say "3. 准备 android.jar"
mkdir -p "$STATION"
if [ -f "$JAR" ]; then
  ok "已存在 $JAR"
else
  # 下载来的 jar 会被直接拿去编译，先确认它是个真的 android.jar（zip 结构 + 关键类），
  # 并把哈希记进 toolchain.env：以后同一台机再下到不一样的包就能立刻发现被换了。
  jar_sane() {
    [ -s "$1" ] || return 1
    command -v unzip >/dev/null 2>&1 || return 0
    unzip -l "$1" 2>/dev/null | grep -q 'android/os/Build.class' || return 1
    return 0
  }
  try_fetch() {
    command -v curl >/dev/null 2>&1 || return 1
    printf '  抓取 %s\n' "$1"
    curl -fL --retry 2 --connect-timeout 20 -o "$JAR.part" "$1" >/dev/null 2>&1 || return 1
    jar_sane "$JAR.part" || { bad "抓下来的不是有效的 android.jar，丢弃"; rm -f "$JAR.part"; return 1; }
    mv "$JAR.part" "$JAR"
    return 0
  }
  if try_fetch "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar" \
  || try_fetch "https://github.com/Sable/android-platforms/raw/master/android-30/android.jar"; then
    ok "android.jar 就位（API 30 stub，编译够用）"
  else
    bad "下载失败。手动解决：随便找一个 android.jar，放到 $JAR"
    echo "  例如从 Android SDK 的 platforms/android-34/android.jar 拷过来。"
  fi
fi
[ -f "$JAR" ] && ok "$(du -h "$JAR" | cut -f1) $JAR"
# 把 jar 的指纹记下来，下次换了包能对一眼（没有 sha256sum 就跳过，不影响构建）
if [ -f "$JAR" ] && command -v sha256sum >/dev/null 2>&1; then
  JAR_SHA="$(sha256sum "$JAR" 2>/dev/null | cut -d' ' -f1)"
  [ -n "$JAR_SHA" ] && printf '%s\n' "# android.jar sha256=$JAR_SHA" >> "${HOME_DIR}/.webgal-station/toolchain.env" 2>/dev/null
fi

say "4. 写跳板脚本（RUN_COMMAND 只能执行 Termux 私有目录里的脚本）"
# 跳板正文单独放在 kit/run.sh：这样改它不用改 setup.sh，也方便以后热更新。
HERE_KIT=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
if [ -f "$HERE_KIT/run.sh" ]; then
  cp "$HERE_KIT/run.sh" "$STATION/run.sh"
else
  bad "kit/run.sh 不见了，跳板写不了"
fi
chmod +x "$STATION/run.sh"
ok "$STATION/run.sh"
chmod +x "$STATION/run.sh"
ok "$STATION/run.sh"

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
else
  echo "  提示：没有 termux-reload-settings，请手动关掉 Termux 再打开，让设置生效。"
fi

say "6. 可选：BuildAPKs"
if [ -d "$HOME_DIR/buildAPKs" ] || [ -d "$HOME_DIR/BuildAPKs" ]; then
  ok "检测到 BuildAPKs 已安装（本工作站默认走等价链路，不改它的工程布局）"
else
  echo "  没装也不影响构建。要装：pkg install git && git clone https://github.com/BuildAPKs/buildAPKs"
fi

say "7. 钉住工具链路径"
if [ -f "$(dirname "$0")/tools.sh" ]; then
  bash "$(dirname "$0")/tools.sh" || true
  ok "工具链路径写在 ~/.webgal-station/toolchain.env，之后构建直接复用，不再每次找路径"
else
  echo "  没找到 tools.sh，跳过（构建时会退回按 PATH 找）"
fi

say "完成"
cat <<'TAIL'

  回工作站，点「环境」→「运行自检」，全绿就能打包。
  如果自检还红，把缺失项的名字告诉我：
    缺 aapt / apksigner → pkg install aapt apksigner
    缺 android.jar     → 拷一个到 ~/.webgal-station/android.jar
    RUN_COMMAND 没动静 → 关掉 Termux 重开一次（让 allow-external-apps 生效）

  许可证提醒：Termux 与 BuildAPKs 都是 GPL-3.0 独立程序，
  本工作站只以命令行方式调用它们，不随附、不链接。
  详见工作站「关于」页与 NOTICE.txt。

TAIL
