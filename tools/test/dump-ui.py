# SPDX-License-Identifier: MIT
# 读 uiautomator dump 出来的 XML，把可点击节点的文字与中心点列出来，
# 省得靠肉眼估算坐标（这台真机横竖屏切换太容易点歪）。
import re, sys, struct

if len(sys.argv) > 1 and sys.argv[1].endswith(".png"):
    data = open(sys.argv[1], "rb").read(32)
    print("PNG 尺寸:", struct.unpack(">II", data[16:24]))
    sys.exit(0)

xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
print("节点数:", xml.count("<node"))
nodes = re.findall(r'<node[^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?clickable="([^"]*)"[^>]*?bounds="([^"]*)"', xml)
for text, cls, clickable, bounds in nodes:
    if not text.strip():
        continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    print("%-24s %-28s clickable=%s center=(%d,%d)" % (
        text.strip()[:24], cls.split(".")[-1], clickable, (x1 + x2) // 2, (y1 + y2) // 2))
