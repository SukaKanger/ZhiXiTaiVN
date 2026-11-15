# SPDX-License-Identifier: MIT
# 生成"低配测试样本"：极小的 WebGAL 工程 + 自绘素材（不依赖任何第三方库/素材授权）
import os, struct, zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lite-sample")
BG = os.path.join(OUT, "background")
SC = os.path.join(OUT, "scene")
for d in (BG, SC):
    os.makedirs(d, exist_ok=True)


def png(path, w, h, fn):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(fn(x, y, w, h))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    body = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(body)


def bg(x, y, w, h):
    t = y / float(h)
    r = int(16 + 30 * t)
    g = int(18 + 24 * t)
    b = int(22 + 40 * t)
    if (x // 24 + y // 24) % 2 == 0:
        r, g, b = r + 8, g + 8, b + 8
    return r, g, b


def title(x, y, w, h):
    t = x / float(w)
    r = int(255 - 60 * t)
    g = int(176 - 40 * t)
    b = int(32 + 20 * t)
    d = ((x - w * 0.5) ** 2 + (y - h * 0.45) ** 2) ** 0.5
    if d < h * 0.22:
        r, g, b = 20, 22, 24
    return r, g, b


png(os.path.join(BG, "lite_bg.png"), 480, 270, bg)
png(os.path.join(BG, "lite_title.png"), 480, 270, title)

config = (
    "Game_name:低配测试样本;\n"
    "Game_key:lite_sample;\n"
    "Title_img:lite_title.png;\n"
    "Enable_Appreciation:false;\n"
    "Enable_Continue:false;\n"
    "Enable_flowchart:false;\n"
)
with open(os.path.join(OUT, "config.txt"), "w", encoding="utf-8", newline="\n") as f:
    f.write(config)

scenes = {
    "start.txt": (
        "intro:低配测试样本|轻到能塞进任何一台旧手机;\n"
        "changeBg:lite_bg.png -next;\n"
        ":这是一个总共几十 KB 的最小工程。;\n"
        ":能用肉眼看到这行字，说明引擎在低端机上也渲染出来了。;\n"
        ":引擎、本地服务、资源读取 —— 一步都不少，只是素材被我砍到骨头。;\n"
        "choose:再看一眼构造:second.txt|到此为止:end.txt;\n"
    ),
    "second.txt": (
        "setVar:files=3 个剧本文件;\n"
        "setVar:images=2 张自绘背景;\n"
        ":整个工程就是 {files} + {images}。;\n"
        ":没有配音、没有立绘、没有 13 MB 的中文字体依赖。;\n"
        ":体积可以忽略，但打包链路一步不少：本地 HTTP 服务、引擎、aapt2、javac、d8、签名。;\n"
        "choose:回到开头:start.txt|结束:end.txt;\n"
    ),
    "end.txt": (
        "changeBg:lite_title.png -next;\n"
        ":测试到此结束。;\n"
        ":如果打包成功，你手里应该有一个几 MB 的 APK —— 不是 27 MB 那种。;\n"
        "end;\n"
    ),
}
for name, text in scenes.items():
    with open(os.path.join(SC, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)

total = 0
count = 0
for r, _, fs in os.walk(OUT):
    for n in fs:
        p = os.path.join(r, n)
        total += os.path.getsize(p)
        count += 1
print("生成完成：%d 个文件，共 %.1f KB" % (count, total / 1024.0))
