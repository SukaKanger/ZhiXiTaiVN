#!/data/data/com.termux/files/usr/bin/bash
# 工作站跳板：接收一个共享存储里的脚本路径，脱离 Termux 会话跑它。
#
# 这个文件由 setup.sh 拷进 ~/.webgal-station/run.sh。工作站用 RUN_COMMAND 让
# Termux 执行它（RUN_COMMAND 只认 Termux 私有目录里的脚本，所以必须有一层跳板）。
#
# 为什么用 setsid + nohup 脱离：真机（vivo V2219A / Android 13，MT6855）上，
# Termux 一退到后台，进程组就落在 freezer:/frozen + cpuset:/background，
# 前台会话里跑的活会被冻住。脱离会话后自己写自己的日志，配合工作站把 Termux
# 顶到前台，就是实测能 24~29 秒出包的形态；Termux 关掉也不拖累已经起的构建。
if [ $# -lt 1 ]; then
  echo "usage: run.sh <script> [args...]"
  exit 2
fi
SCRIPT="$1"; shift
[ -f "$SCRIPT" ] || { echo "no such script: $SCRIPT"; exit 3; }
DIR=$(cd "$(dirname "$SCRIPT")" 2>/dev/null && pwd)
{ echo "== argv: $SCRIPT $*"; echo "== cwd: $(pwd)"; id; env | sort; } >"$DIR/runner-env.txt" 2>&1
setsid nohup bash "$SCRIPT" "$@" </dev/null >>"$DIR/runner.log" 2>&1 &
echo "started pid $!"
exit 0
