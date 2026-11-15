# SPDX-License-Identifier: MIT
# 打「低配测试包」：样本工程 + 端上脚本 + 手册 + 一页速查，一个 zip 推到手机。
import os, zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TOP = "WebGAL低配测试包"
OUT = os.path.join(ROOT, "dist", "WebGAL低配测试包.zip")

QUICK = """WebGAL 低配测试包 · 速查
================================

这个包里有什么
  lite-sample/        6 个文件、6.1 KB 的最小 WebGAL 工程（L1 样本）
  kit/                Termux 端脚本（build_apk.sh / probe.sh / setup.sh / run.sh）
  LOW-END-TEST.md     真机实测手册：判据、决策树、记录表
  lite APK 两个       （不在 zip 里，单独交付：工作站 6.56 MB、样本 0.79 MB）

顺序别搞反
  1. 装 低配测试样本-1.0.0-lite.apk  → 跑通，证明这台机器的 WebView 能渲染 WebGAL
  2. 装 WebGAL工作站-1.0.0-lite.apk  → 环境页「探测设备」，看 verdict
  3. 把 lite-sample/ 拷进手机任意目录 → 工程页选它 → 预览
  4. Termux 装依赖 → 部署工具链 → 打包台开始构建
  5. 把产物装回手机 → 这一步过了才算真过了

注意事项
  * adb install 必须带 --no-incremental（APK 名含中文时增量安装会报 Invalid name）
  * Termux 只执行自己家目录里的脚本，跳板固定在 ~/.webgal-station/run.sh
  * 低配模式剔掉了 36 MB 中文字体，中文靠系统字体回退，字形和标准版不同但必须清晰可读

细节看 LOW-END-TEST.md。测完把数字填进第六节的表，别填形容词。
"""


def mode_for(name):
    return 0o755 if name.endswith(".sh") else 0o644


files = []
sample = os.path.join(ROOT, "samples", "lite-sample")
for r, _, fs in os.walk(sample):
    for f in fs:
        p = os.path.join(r, f)
        arc = os.path.join(TOP, "lite-sample",
                           os.path.relpath(p, sample).replace(os.sep, "/"))
        files.append((p, arc))

kit = os.path.join(ROOT, "src", "assets", "kit")
for f in ("build_apk.sh", "probe.sh", "setup.sh", "run.sh", "README-build.txt"):
    p = os.path.join(kit, f)
    if os.path.isfile(p):
        files.append((p, os.path.join(TOP, "kit", f)))

files.append((os.path.join(ROOT, "docs", "LOW-END-TEST.md"), os.path.join(TOP, "LOW-END-TEST.md")))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    zi = zipfile.ZipInfo(os.path.join(TOP, "00-速查.txt"))
    zi.external_attr = 0o644 << 16
    z.writestr(zi, QUICK.encode("utf-8"))
    for p, arc in files:
        info = zipfile.ZipInfo(arc)
        info.external_attr = mode_for(arc) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        with open(p, "rb") as f:
            z.writestr(info, f.read())

print("已生成 %s（%.1f KB，%d 个文件）" % (
    OUT, os.path.getsize(OUT) / 1024.0, len(files) + 1))
