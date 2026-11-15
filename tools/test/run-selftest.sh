#!/system/bin/sh

# 低配分支自检：用桩程序把 build_apk.sh 跑到底，检查每条降级路是否真的走通。
set -u
JOB=/data/local/tmp/litejob
FAKEBIN=/data/local/tmp/fakebin
rm -rf "$JOB"
mkdir -p "$JOB/android/res" "$JOB/android/src/com/x" \
         "$JOB/android/assets/engine" "$JOB/android/assets/game"
printf '<manifest package="com.webgal.selftest" xmlns:android="http://schemas.android.com/apk/res/android"/>' \
  > "$JOB/android/AndroidManifest.xml"
printf 'class X{}\n' > "$JOB/android/src/com/x/X.java"
printf 'APP_NAME=低配自检\nPKG=com.webgal.selftest\nVERSION_NAME=1.0.0\nVERSION_CODE=1\nMIN_SDK=21\nTARGET_SDK=34\n' \
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
FIRST=$(sed -n '1p' "$TRACE" 2>/dev/null | sed 's/.*-Xmx\([0-9]*\)m.*/\1/')
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
