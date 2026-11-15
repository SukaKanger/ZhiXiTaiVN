# SPDX-License-Identifier: MIT
# 生成"低配构建脚本自检"用的桩程序（shim）与执行脚本。
# 目的只有一个：在没有 javac/aapt2/d8 的机器上，也能验证 build_apk.sh 的
# 低配降级分支（内存探测、javac 重试、d8→dx 回退、zipalign 缺失跳过）真的会走。
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FB = os.path.join(HERE, "fakebin")
os.makedirs(FB, exist_ok=True)

SH = "#!/system/bin/sh\n"

shims = {
    "aapt2": SH + """
out=""; prev=""
for a in "$@"; do
  [ "$prev" = "-o" ] && out="$a"
  prev="$a"
done
[ -n "$out" ] || exit 1
printf 'PK\\003\\004stub' > "$out"
exit 0
""",
    "javac": SH + """
echo "javac ${_JAVA_OPTIONS:-}" >> "${JAVAC_TRACE:-/data/local/tmp/javac.trace}"
d=""; prev=""
for a in "$@"; do
  [ "$prev" = "-d" ] && d="$a"
  prev="$a"
done
if [ ! -f "${JAVAC_MARK:-/data/local/tmp/javac.mark}" ]; then
  : > "${JAVAC_MARK:-/data/local/tmp/javac.mark}"
  echo "Error: java.lang.OutOfMemoryError: Java heap space (simulated)" >&2
  exit 1
fi
mkdir -p "$d" && : > "$d/Stub.class"
exit 0
""",
    "d8": SH + """
echo "d8: unsupported class file version (simulated)" >&2
exit 1
""",
    "dx": SH + """
out=""
for a in "$@"; do
  case "$a" in --output=*) out="${a#--output=}" ;; esac
done
[ -n "$out" ] || out="classes.dex"
mkdir -p "$(dirname "$out")"
printf 'dex\\n' > "$out"
echo "dx: ok (fallback)"
exit 0
""",
    "apksigner": SH + """
[ "$1" = "verify" ] && exit 0
out=""; prev=""; last=""
for a in "$@"; do
  [ "$prev" = "--out" ] && out="$a"
  prev="$a"; last="$a"
done
cp "$last" "$out" 2>/dev/null || : > "$out"
printf '\\nsigned\\n' >> "$out"
echo "apksigner: signed -> $out"
exit 0
""",
    "keytool": SH + """
ks=""; prev=""
for a in "$@"; do
  [ "$prev" = "-keystore" ] && ks="$a"
  prev="$a"
done
[ -n "$ks" ] || exit 1
mkdir -p "$(dirname "$ks")"
printf 'keystore\\n' > "$ks"
exit 0
""",
    "zip": SH + """
last=""
for a in "$@"; do last="$a"; done
printf 'PK' >> "$last"
echo "zip: appended -> $last"
exit 0
""",
}

for name, body in shims.items():
    p = os.path.join(FB, name)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    os.chmod(p, 0o755)

runner = SH + """
# 低配分支自检：用桩程序把 build_apk.sh 跑到底，检查每条降级路是否真的走通。
set -u
JOB=/data/local/tmp/litejob
FAKEBIN=/data/local/tmp/fakebin
rm -rf "$JOB"
mkdir -p "$JOB/android/res" "$JOB/android/src/com/x" \\
         "$JOB/android/assets/engine" "$JOB/android/assets/game"
printf '<manifest package="com.webgal.selftest" xmlns:android="http://schemas.android.com/apk/res/android"/>' \\
  > "$JOB/android/AndroidManifest.xml"
printf 'class X{}\\n' > "$JOB/android/src/com/x/X.java"
printf 'APP_NAME=低配自检\\nPKG=com.webgal.selftest\\nVERSION_NAME=1.0.0\\nVERSION_CODE=1\\nMIN_SDK=21\\nTARGET_SDK=34\\n' \\
  > "$JOB/job.env"
cp /data/local/tmp/build_apk.sh "$JOB/build_apk.sh"
rm -f /data/local/tmp/javac.mark /data/local/tmp/javac.trace
mkdir -p /data/local/tmp/home/.webgal-station
: > /data/local/tmp/home/.webgal-station/android.jar

echo "==== build_apk.sh 低配分支自检 ===="
PATH="$FAKEBIN:$PATH" HOME=/data/local/tmp/home sh "$JOB/build_apk.sh"
RC=$?
echo "==== 断言（rc=$RC）===="
# 注意：Android 的 toybox grep 对中文 pattern 不稳，断言一律用 ASCII / 数值
TRACE=/data/local/tmp/javac.trace
N=$(awk 'END{print NR}' "$TRACE" 2>/dev/null)
[ "${N:-0}" -ge 2 ] && echo "PASS javac 失败后降堆重试（调用 2 次）" || echo "FAIL javac 重试分支（调用 $N 次）"
FIRST=$(sed -n '1p' "$TRACE" 2>/dev/null | sed 's/.*-Xmx\\([0-9]*\\)m.*/\\1/')
MEM_MB=$(awk '/^MemTotal:/{print int($2/1024)}' /proc/meminfo)
EXP=$(( MEM_MB / 4 ))
[ "$EXP" -lt 256 ] && EXP=256
if [ "$FIRST" = "$EXP" ]; then
  echo "PASS 堆上限按 RAM/4 计算（$FIRST MB，RAM ${MEM_MB} MB）"
else
  echo "FAIL 堆上限 $FIRST MB != 期望 $EXP MB（RAM ${MEM_MB} MB）"
fi
grep -q 'dx: ok' "$JOB/build.log" && echo "PASS d8 失败后回退 dx" || echo "FAIL d8->dx 回退"
[ -f "$JOB/work/dex/classes.dex" ] && echo "PASS classes.dex 由 dx 产出" || echo "FAIL classes.dex 缺失"
[ ! -f "$JOB/work/aligned.apk" ] && echo "PASS zipalign 缺失时跳过（无 aligned.apk）" || echo "FAIL zipalign 分支"
grep -q '"state":"success"' "$JOB/status.json" && echo "PASS status.json 成功态" || echo "FAIL status.json"
echo "---- javac 调用记录（每次的堆上限）----"
sed 's/.*-Xmx/-Xmx/' "$TRACE" 2>/dev/null | cut -c1-40
echo "---- status.json ----"
cat "$JOB/status.json" 2>/dev/null
echo "---- 体积 ----"
du -h "$JOB/out"/*.apk 2>/dev/null
"""

p = os.path.join(HERE, "run-selftest.sh")
with open(p, "w", encoding="utf-8", newline="\n") as f:
    f.write(runner)
os.chmod(p, 0o755)
print("fakebin + run-selftest.sh 已生成：", HERE)
