#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# build_apk.sh —— 在 Termux 里把一个已经拼装好的构建工程打成签名 APK。
#
# 输入：本脚本所在目录
#   android/AndroidManifest.xml
#   android/res/                 图标等资源
#   android/src/<pkg>/*.java     宿主 Activity + LocalServer
#   android/assets/engine        引擎
#   android/assets/game          剧本
#   android/assets/legal         来路与许可证
#   job.env                      应用名、包名、版本
#
# 输出：
#   out/<应用名>.apk             签名后的产物
#   status.json                  给工作站界面读的状态
#   build.log                    全量日志
#
# 链路等价于 BuildAPKs 的做法：aapt(2) 打资源 → javac 编译 → d8/dx 出 dex →
# zipalign 对齐 → apksigner 签名。全部是外部命令，不链接任何 GPL 代码。

set -u
# 管道里任何一段失败都要能被看见（日志走 tee，不 pipefail 的话 tee 成功就吞了上游错误）
set -o pipefail 2>/dev/null || true

# ---------------------------------------------------------------- 0. 会话环境兜底
# RUN_COMMAND 拉起的后台会话只带一份最小环境。真机实测（vivo V2219A / Android 13）：
# 同一份脚本在交互 shell 里 11 秒跑完，被 RUN_COMMAND 拉起来却整段挂在 javac 上
# （CPU 0%、13 个线程全睡眠、几分钟不动）。这里把关键变量钉死、stdin 关掉，
# 把「会话环境」这个不确定性消掉，让端上打包不依赖 Termux 会话是什么姿势。
exec </dev/null
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
export HOME="$HOME_DIR" PREFIX="$PREFIX_DIR"
export PATH="$PREFIX_DIR/bin:/system/bin:/system/xbin:${PATH:-}"
TMPDIR="$HOME_DIR/.webgal-station/tmp"
mkdir -p "$TMPDIR" 2>/dev/null || true
[ -w "$TMPDIR" ] || TMPDIR="$PREFIX_DIR/tmp"
export TMPDIR
export JAVA_TMPDIR_OPT="-Djava.io.tmpdir=$TMPDIR"

HERE="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
ANDROID="$HERE/android"
LOG="$HERE/build.log"
STATUS="$HERE/status.json"
# 中间产物（.class / .dex / 临时 apk）一律落在 Termux 私有目录。
# 真机实测：javac 直接往 /storage（FUSE）里写几十个小 .class 文件时会静默卡死
# （CPU 0%、线程全睡），换成"私有目录编译 + 成品回拷"就正常了。
TMPROOT="$TMPDIR/build-$(basename "$HERE" 2>/dev/null)"
WORK="$TMPROOT/work"
OUT="$HERE/out"
T0=$(date +%s)

mkdir -p "$WORK" "$OUT" "$TMPROOT"

# 同一构建工程被点了两次：两次 RUN_COMMAND 会并行跑，共享 WORK / LOG / STATUS，
# 日志被第二个截断、状态互相覆盖，产物说不清是哪次的。先抢锁。
#
# 锁文件不能放 $HERE（那在 /storage 的 FUSE 上）：FUSE 不支持 POSIX 文件锁，
# exec 9>"$LOCK" 拿不到 fd，flock 直接失败，会被误判成「已经有构建在跑」，
# 结果第一次点就红字（vivo Y77 实测）。锁放 Termux 私有目录，顺带把 pid 文件也挪进去。
PIDFILE="$HERE/.build.pid"
LOCKDIR="$HOME/.webgal-station/locks"
mkdir -p "$LOCKDIR" 2>/dev/null || LOCKDIR="$TMPROOT"
LOCK="$LOCKDIR/$(basename "$HERE").lock"
if command -v flock >/dev/null 2>&1 && exec 9>"$LOCK" 2>/dev/null; then
  if ! flock -n 9 2>/dev/null; then
    printf '{"state":"failed","reason":"另一个构建还在跑，等它结束再来","seconds":0}\n' > "$STATUS" 2>/dev/null || true
    printf '%s\n' "✘ 另一个构建还在跑，等它结束再来" 
    exit 1
  fi
else
  if [ -f "$PIDFILE" ]; then
    oldpid="$(cat "$PIDFILE" 2>/dev/null)"
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
      printf '{"state":"failed","reason":"另一个构建还在跑(pid %s)","seconds":0}\n' "$oldpid" > "$STATUS" 2>/dev/null || true
      printf '%s\n' "✘ 另一个构建还在跑（pid $oldpid）"
      exit 1
    fi
  fi
  printf '%s\n' "$$" > "$PIDFILE" 2>/dev/null || true
fi

# 失败时清掉 Termux 私有中间目录：低配机上反复试构建会把 FUSE 里的 tmp 堆成山
cleanup_on_fail() {
  rc=$?
  if [ "$rc" -ne 0 ]; then
    rm -rf "$WORK" 2>/dev/null || true
  fi
  return "$rc"
}
trap cleanup_on_fail EXIT

: > "$LOG"

log() { printf '%s\n' "$*" 2>/dev/null | tee -a "$LOG"; }
hr() { log "------------------------------------------------------------"; }

# status.json 是 Java 侧用 JSONObject 解析的。路径里带引号/反斜杠/换行
# 以前会直接把这份文件写成非法 JSON，失败理由跟着一起消失。
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r' | awk 'BEGIN{ORS=""} {if (NR>1) printf "\\n"; printf "%s", $0}'
}

write_status() {
  # $1 state, $2 apk path, $3 size text, $4 reason, $5 warn(可选)
  local state="$1" apk="${2:-}" size="${3:-}" reason="${4:-}" warn="${5:-}"
  local secs=$(( $(date +%s) - T0 ))
  {
    printf '{"state":"%s"' "$(json_escape "$state")"
    [ -n "$apk" ] && printf ',"apk":"%s"' "$(json_escape "$apk")"
    [ -n "$size" ] && printf ',"size":"%s"' "$(json_escape "$size")"
    [ -n "$reason" ] && printf ',"reason":"%s"' "$(json_escape "$reason")"
    [ -n "$warn" ] && printf ',"warn":"%s"' "$(json_escape "$warn")"
    printf ',"seconds":%s}\n' "$secs"
  } > "$STATUS"
}

fail() {
  log ""
  log "✘ 构建失败：$*"
  log "  提示：把上面的报错原文发给工作站作者，或检查 Termux 依赖是否装齐。"
  rm -f "$HERE/job.env" "$PIDFILE" 2>/dev/null || true
  write_status failed "" "" "$*"
  exit 1
}

need() {
  # need <显示名> <命令> [备用命令...]
  local label="$1"; shift
  local c
  for c in "$@"; do
    if command -v "$c" >/dev/null 2>&1; then
      command -v "$c"
      return 0
    fi
  done
  return 1
}

hr
log "WebGAL 移动工作站 · 端上构建"
log "时间   $(date '+%Y-%m-%d %H:%M:%S')"
log "目录   $HERE"
write_status running

# ---------------------------------------------------------------- 0. 读参数
if [ -f "$HERE/job.env" ]; then
  # shellcheck disable=SC1090
  . "$HERE/job.env"
fi
APP_NAME="${APP_NAME:-WebGAL 游戏}"
PKG="${PKG:-com.webgal.game.demo}"
VERSION_NAME="${VERSION_NAME:-1.1.5-beta}"
VERSION_CODE="${VERSION_CODE:-1}"
# versionCode 非法时（空、带字母、负数）以前是原样塞进清单，等到 aapt 报一串天书才失败
case "$VERSION_CODE" in
  ''|*[!0-9]*) fail "versionCode 必须是正整数，现在是「$VERSION_CODE」" ;;
esac
[ "$VERSION_CODE" -ge 1 ] 2>/dev/null || fail "versionCode 必须 ≥ 1，现在是「$VERSION_CODE」"
log "应用   $APP_NAME"
log "包名   $PKG"
log "版本   $VERSION_NAME ($VERSION_CODE)"

[ -f "$ANDROID/AndroidManifest.xml" ] || fail "缺 AndroidManifest.xml，先在打包台生成构建工程"
[ -d "$ANDROID/assets/engine" ] || fail "缺 assets/engine，构建工程不完整"
[ -d "$ANDROID/assets/game" ] || fail "缺 assets/game，构建工程不完整"
[ -d "$ANDROID/src" ] || fail "缺 src，构建工程不完整"

# ---------------------------------------------------------------- 1. 找工具
hr
# 先认 tools.sh 钉下来的固定路径（内置工具链缓存），再退回 PATH。
TOOLCHAIN_ENV="$HOME_DIR/.webgal-station/toolchain.env"
TOOLS_SH=""
for cand in "$HERE/tools.sh" "$HERE/../../kit/tools.sh" "$HERE/../kit/tools.sh"; do
  [ -f "$cand" ] && { TOOLS_SH="$cand"; break; }
done
if [ ! -f "$TOOLCHAIN_ENV" ] && [ -n "$TOOLS_SH" ]; then
  # 第一次构建顺手把路径钉好，用户不用记得手动跑一次（输出进日志，失败好查）
  log "工具链  首次构建，先跑 tools.sh 钉路径（$TOOLS_SH）"
  bash "$TOOLS_SH" >>"$LOG" 2>&1 || log "⚠ 工具链  tools.sh 跑挂了（看上面那段输出），继续按 PATH 找；找不到 javac 就会在编译那步失败"
fi
if [ -f "$TOOLCHAIN_ENV" ]; then
  # shellcheck disable=SC1090
  . "$TOOLCHAIN_ENV"
  export PATH="$HOME_DIR/.webgal-station/tools/bin:$PATH"
  log "工具链  用已钉住的路径（$TOOLCHAIN_ENV）"
else
  log "工具链  还没钉路径，先按 PATH 找；建议有空跑一次 tools.sh"
fi

JAVAC="${TOOL_JAVAC:-$(need javac javac)}" || fail "javac 不在。Termux 里跑：pkg install openjdk-17"
KEYTOOL="${TOOL_KEYTOOL:-$(need keytool keytool)}" || fail "keytool 不在。Termux 里跑：pkg install openjdk-17"
log "javac      $JAVAC"

AAPT2="${TOOL_AAPT2:-$(need aapt2 aapt2)}" || true
AAPT="${TOOL_AAPT:-$(need aapt aapt)}" || true
if [ -z "$AAPT2" ] && [ -z "$AAPT" ]; then
  fail "aapt2 / aapt 都没有。Termux 里跑：pkg install aapt"
fi
log "aapt2      ${AAPT2:-（无，退化到 aapt v1）}"
log "aapt       ${AAPT:-（无）}"

D8="${TOOL_D8:-$(need d8 d8)}" || true
DX="${TOOL_DX:-$(need dx dx)}" || true
[ -n "$D8" ] || [ -n "$DX" ] || fail "d8 与 dx 都没有。Termux 里跑：pkg install d8 或 pkg install dx"
log "dexer      ${D8:-$DX}"

ZIPALIGN="${TOOL_ZIPALIGN:-$(need zipalign zipalign)}" || true
log "zipalign   ${ZIPALIGN:-（缺，跳过对齐）}"

APKSIGNER="${TOOL_APKSIGNER:-$(need apksigner apksigner)}" || fail "apksigner 不在。Termux 里跑：pkg install apksigner"
log "apksigner  $APKSIGNER"

ANDROID_JAR="${TOOL_ANDROID_JAR:-}"
for cand in \
  "$HOME_DIR/.webgal-station/android.jar" \
  "$PREFIX_DIR/share/android.jar" \
  "$HOME_DIR/android.jar" \
  "$PREFIX_DIR/opt/android-sdk/platforms/android-34/android.jar" \
  "$PREFIX_DIR/opt/android-sdk/platforms/android-33/android.jar"; do
  [ -f "$cand" ] && { ANDROID_JAR="$cand"; break; }
done
if [ -z "$ANDROID_JAR" ]; then
  for cand in "$PREFIX_DIR"/opt/android-sdk/platforms/*/android.jar; do
    [ -f "$cand" ] && { ANDROID_JAR="$cand"; break; }
  done
fi
[ -n "$ANDROID_JAR" ] || fail "找不到 android.jar。重跑 setup.sh，或把它放到 $HOME_DIR/.webgal-station/android.jar"
log "android.jar $ANDROID_JAR"

ZIP="${TOOL_ZIP:-$(need zip zip)}" || true
[ -n "$ZIP" ] || log "zip 缺，改用 jar 塞 classes.dex"

# ---------------------------------------------------------------- 1.5 低配自适应
# 中低端手机只有 2~4 GB RAM，JVM 默认会按物理内存的 1/4 取堆，javac + d8 同时
# 起来很容易被 LMK 直接杀掉。这里按可用内存压一个上限，并换到更省内存的串行 GC。
hr
MEM_KB=$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null | head -n1 | tr -cd '0-9')
[ -n "${MEM_KB:-}" ] || MEM_KB=0
MEM_MB=$(( MEM_KB / 1024 ))
if [ "$MEM_MB" -le 0 ]; then
  MEM_MB=2048
  log "读不到 /proc/meminfo，按 2 GB 设备处理"
fi
JAVA_XMX=$(( MEM_MB / 3 ))
[ "$MEM_MB" -lt 2048 ] && JAVA_XMX=$(( MEM_MB / 4 ))
[ "$JAVA_XMX" -lt 256 ] && JAVA_XMX=256
[ "$JAVA_XMX" -gt 1024 ] && JAVA_XMX=1024
export _JAVA_OPTIONS="-Xmx${JAVA_XMX}m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
# getconf 在部分 ROM 上直接返回空/1（真机实测就写成了「1 核」），逐级兜底。
CPUS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null \
  || grep -c '^processor' /proc/cpuinfo 2>/dev/null || echo 1)
CPUS=$(printf '%s' "${CPUS:-1}" | tr -cd '0-9')
[ -n "$CPUS" ] || CPUS=1
[ "$CPUS" -gt 0 ] 2>/dev/null || CPUS=1

log "设备   RAM ${MEM_MB} MB / ${CPUS} 核"
log "JVM    堆上限 ${JAVA_XMX} MB（_JAVA_OPTIONS），串行 GC"
if [ "$MEM_MB" -lt 2048 ]; then
  log "⚠  只有 ${MEM_MB} MB：javac 很可能被系统杀掉。先只打 L1 极小样本验证链路，"
  log "   真要打 20 MB 级别的工程，建议改用 PC 通道（打包台里切「生成工程」拿回电脑）。"
fi
AVAIL_KB=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null | head -n1 | tr -cd '0-9')
[ -n "${AVAIL_KB:-}" ] && log "可用   $(( AVAIL_KB / 1024 )) MB（构建开始前）"

# ---------------------------------------------------------------- 2. 资源
hr
log "① 编译资源"
RES_ZIP="$WORK/res.zip"
BASE_APK="$WORK/base.apk"
rm -f "$RES_ZIP" "$BASE_APK"

if [ -n "$AAPT2" ]; then
  [ -d "$ANDROID/res" ] || fail "缺 res 目录"
  "$AAPT2" compile --dir "$ANDROID/res" -o "$RES_ZIP" >>"$LOG" 2>&1 \
    || fail "aapt2 compile 失败（看日志）"
  "$AAPT2" link \
    -o "$BASE_APK" \
    -I "$ANDROID_JAR" \
    --manifest "$ANDROID/AndroidManifest.xml" \
    -R "$RES_ZIP" \
    -A "$ANDROID/assets" \
    --min-sdk-version "${MIN_SDK:-21}" \
    --target-sdk-version "${TARGET_SDK:-34}" \
    --auto-add-overlay >>"$LOG" 2>&1 \
    || fail "aapt2 link 失败（通常是清单写错或图标缺失）"
  log "   aapt2 link → $(du -h "$BASE_APK" 2>/dev/null | cut -f1)"
else
  "$AAPT" package -f -m \
    -M "$ANDROID/AndroidManifest.xml" \
    -S "$ANDROID/res" \
    -A "$ANDROID/assets" \
    -I "$ANDROID_JAR" \
    -J "$WORK/gen" \
    -F "$BASE_APK" >>"$LOG" 2>&1 \
    || fail "aapt package 失败（看日志）"
  log "   aapt v1 → $(du -h "$BASE_APK" 2>/dev/null | cut -f1)"
fi

# ---------------------------------------------------------------- 3. 编译
hr
log "② 编译 Java"
rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
SRC_LIST="$WORK/sources.txt"
find "$ANDROID/src" -name '*.java' > "$SRC_LIST"
[ -s "$SRC_LIST" ] || fail "没找到任何 .java 源码"
JAVAC_TIMEOUT="${JAVAC_TIMEOUT:-240}"
javac_once() {
  # timeout 是关键：真机上 javac 会在这个会话里静默卡死，没有超时就会一直挂着，
  # 用户看到的就是「构建中」永远不动。超时判失败，交给下面的降堆重试兜。
  _JAVA_OPTIONS="-Xmx${1}m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
    timeout "$JAVAC_TIMEOUT" "$JAVAC" -encoding UTF-8 -source 8 -target 8 \
    -J"$JAVA_TMPDIR_OPT" \
    -bootclasspath "$ANDROID_JAR" \
    -d "$WORK/classes" @"$SRC_LIST" >>"$LOG" 2>&1
}
javac_once "$JAVA_XMX"; JAVAC_RC=$?
if [ "$JAVAC_RC" -ne 0 ]; then
  if [ "$JAVAC_RC" -eq 124 ]; then
    log "   javac 超时（${JAVAC_TIMEOUT}s）：会话/文件系统卡住，堆砍到小值再试"
  else
    log "   javac 第一次没跑通（低配机上多是内存），堆砍到一半重试"
  fi
  HALF=$(( JAVA_XMX / 2 ))
  [ "$HALF" -lt 256 ] && HALF=256
  log "   重试堆上限 ${HALF} MB"
  rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
  javac_once "$HALF" || fail "javac 编译失败（看日志里的行号；若是 Killed 就是内存不够，若是 124 就是卡住）"
  JAVA_XMX=$HALF
fi
log "   class 数 $(find "$WORK/classes" -name '*.class' | wc -l | tr -d ' ')"

# ---------------------------------------------------------------- 4. dex
hr
log "③ 生成 classes.dex"
rm -rf "$WORK/dex" && mkdir -p "$WORK/dex"
if [ -n "$D8" ]; then
  # 类列表走 @argfile：几十个 class 直接铺在命令行里，路径带空格就断
  CLASS_LIST="$WORK/classes.txt"
  find "$WORK/classes" -name '*.class' > "$CLASS_LIST"
  _JAVA_OPTIONS="-Xmx${JAVA_XMX}m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
    timeout "${DEX_TIMEOUT:-300}" "$D8" --lib "$ANDROID_JAR" --min-api "${MIN_SDK:-21}" --output "$WORK/dex" \
    @"$CLASS_LIST" >>"$LOG" 2>&1 \
    || log "   d8 失败或超时，回退 dx 再试一次"
fi
if [ ! -f "$WORK/dex/classes.dex" ]; then
  [ -n "$DX" ] || fail "classes.dex 没生成，而 dx 也不在（pkg install dx）"
  rm -rf "$WORK/dex" && mkdir -p "$WORK/dex"
  _JAVA_OPTIONS="-Xmx${JAVA_XMX}m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
    timeout "${DEX_TIMEOUT:-300}" "$DX" --dex --output="$WORK/dex/classes.dex" "$WORK/classes" >>"$LOG" 2>&1 \
    || fail "d8 与 dx 都没跑通（d8 需要较新 JDK；dx 见日志）"
fi
[ -f "$WORK/dex/classes.dex" ] || fail "classes.dex 没生成"
log "   $(du -h "$WORK/dex/classes.dex" | cut -f1)"

# ---------------------------------------------------------------- 5. 塞进 APK
hr
log "④ 把 dex 写进 APK"
if [ -n "$ZIP" ]; then
  ( cd "$WORK/dex" && "$ZIP" -q -X "$BASE_APK" classes.dex ) >>"$LOG" 2>&1 \
    || fail "zip 写入失败"
else
  ( cd "$WORK/dex" && jar uf "$BASE_APK" classes.dex ) >>"$LOG" 2>&1 \
    || fail "jar 写入失败"
fi

# ---------------------------------------------------------------- 6. 对齐
SIGN_INPUT="$BASE_APK"
if [ -n "$ZIPALIGN" ]; then
  hr
  log "⑤ zipalign"
  "$ZIPALIGN" -f -p 4 "$BASE_APK" "$WORK/aligned.apk" >>"$LOG" 2>&1 \
    && SIGN_INPUT="$WORK/aligned.apk" \
    || log "   zipalign 失败，直接用未对齐的包继续（一般仍可安装）"
else
  log "⑤ 跳过 zipalign（没有该命令）"
fi

# ---------------------------------------------------------------- 7. 签名
hr
log "⑥ 签名"
# SIGMODE=debug 用内置调试密钥（能装能测，不能上架）
# SIGMODE=custom 用玩家自己的 keystore（KS_PATH 必须已存在）
# SIGMODE=new   现场生成一个玩家自己的 keystore（keytool 生成后照常签名）
SIGMODE="${SIGMODE:-debug}"
KS="${KS_PATH:-}"
[ -n "$KS" ] || KS="$HOME_DIR/.webgal-station/debug.keystore"
ALIAS="${KS_ALIAS:-webgal}"
STOREPASS="${KS_STORE_PASS:-android}"
KEYPASS="${KS_KEY_PASS:-$STOREPASS}"

case "$SIGMODE" in
  debug)
    KS="$HOME_DIR/.webgal-station/debug.keystore"
    ALIAS="webgal"; STOREPASS="android"; KEYPASS="android"
    log "   签名模式：内置调试签名（可自测，不可上架）"
    ;;
  custom)
    [ -f "$KS" ] || fail "签名模式选了自定义，但找不到 keystore：$KS"
    log "   签名模式：自定义 keystore  $(basename "$KS")  别名 $ALIAS"
    ;;
  new)
    log "   签名模式：新建 keystore  $(basename "$KS")  别名 $ALIAS"
    if [ -f "$KS" ]; then
      log "   同名 keystore 已存在，先验别名和口令对不对得上"
      "$KEYTOOL" -list -keystore "$KS" -alias "$ALIAS" -storepass "$STOREPASS" >>"$LOG" 2>&1 \
        || fail "同名 keystore 里没有别名「$ALIAS」或口令不对；换个别名/口令，或先删掉那个库"
    fi
    ;;
  *)
    fail "SIGMODE 不认识：$SIGMODE（只能是 debug / custom / new）"
    ;;
esac

mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
  log "   生成密钥（有效期 10000 天，口令保存在 keystore 本身，请自己备份）"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias "$ALIAS" -storepass "$STOREPASS" \
    -keypass "$KEYPASS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=WebGAL Mobile Workstation, O=Local Build, C=CN" >>"$LOG" 2>&1 \
    || fail "keytool 生成密钥失败（检查 alias / 口令是否含特殊字符）"
fi

SAFE_NAME=$(printf '%s' "$APP_NAME" | tr ' /:*?"<>|\\' '_________' | cut -c1-40)
[ -n "$SAFE_NAME" ] || SAFE_NAME="webgal-game"
# 同名应用重复打包以前会 rm 掉上一个产物，想对比两版就找不到了；包名尾缀区分
PKG_SUFFIX=$(printf '%s' "$PKG" | tr '.' '_' | cut -c1-24)
FINAL_APK="$OUT/$SAFE_NAME-$PKG_SUFFIX.apk"
rm -f "$FINAL_APK"

"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-pass "pass:$STOREPASS" \
  --key-pass "pass:$KEYPASS" \
  --min-sdk-version "${MIN_SDK:-21}" \
  --out "$FINAL_APK" \
  "$SIGN_INPUT" >>"$LOG" 2>&1 \
  || fail "apksigner 签名失败（口令错了？keystore 坏了？）"

VERIFY_WARN=""
if "$APKSIGNER" verify --min-sdk-version "${MIN_SDK:-21}" "$FINAL_APK" >>"$LOG" 2>&1; then
  log "   签名校验通过"
else
  VERIFY_WARN="签名校验没过，装上去可能被系统拒（口令/keystore 有问题）"
  log "   ✘ $VERIFY_WARN"
fi

SIZE_TEXT=$(du -h "$FINAL_APK" | cut -f1)
SECS=$(( $(date +%s) - T0 ))

hr
log "✔ 构建完成"
log "   产物  $FINAL_APK"
log "   体积  $SIZE_TEXT"
log "   耗时  ${SECS}s"
log ""
log "   分发前记得：保留 assets/legal/ 里的来路与许可文件；"
if [ "$SIGMODE" = "debug" ]; then
  log "   调试签名只适合自己测装，别拿去上架；要发布就换 custom / new 模式。"
else
  log "   用的是你自己的 keystore，密钥自己备份好——丢了就没法给同一个包升级。"
fi

# job.env 里有明文口令（custom / new 模式），构建读完立刻删掉，别让它躺在共享存储里
if [ -f "$HERE/job.env" ]; then
  rm -f "$HERE/job.env" 2>/dev/null || true
  log "   已清掉 job.env（里面有签名口令）"
fi
# pid 文件也顺手清掉，否则下次 no-flock 路径会误判「还在跑」
rm -f "$PIDFILE" 2>/dev/null || true

write_status success "$FINAL_APK" "$SIZE_TEXT" "" "$VERIFY_WARN"
exit 0
