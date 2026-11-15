# SPDX-License-Identifier: MIT
# 从 F-Droid 官方仓库取 Termux APK（签名与 F-Droid 一致，避免应用商店魔改包）。
# 网络不稳时用 Range 断点续传，别指望一次读完 113 MB。
# 用法：python fetch-termux.py <输出路径>
import json, os, sys, time, urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join("dist", "_termux.apk")
API = "https://f-droid.org/api/v1/packages/com.termux"
UA = {"User-Agent": "WebGAL-Station-Setup/1.0"}


def meta():
    with urllib.request.urlopen(urllib.request.Request(API, headers=UA), timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


code = meta().get("suggestedVersionCode")
if not code:
    print("API 没给出版本号")
    sys.exit(1)

url = "https://f-droid.org/repo/com.termux_%s.apk" % code
print("F-Droid 推荐版本 versionCode=%s" % code)
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)

total = None
for attempt in range(1, 9):
    have = os.path.getsize(OUT) if os.path.exists(OUT) else 0
    headers = dict(UA)
    if have:
        headers["Range"] = "bytes=%d-" % have
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=60) as r:
            if total is None:
                cr = r.headers.get("Content-Range")
                total = int(cr.split("/")[-1]) if cr else int(r.headers.get("Content-Length", 0))
                if have and cr:
                    total = int(cr.split("/")[-1])
                print("总大小 %.1f MB" % (total / 1024.0 / 1024.0))
            mode = "ab" if have and r.status == 206 else "wb"
            if mode == "wb":
                have = 0
            with open(OUT, mode) as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
    except Exception as e:
        print("第 %d 次中断：%s" % (attempt, e))
    size = os.path.getsize(OUT) if os.path.exists(OUT) else 0
    if total and size >= total:
        break
    time.sleep(2)

size = os.path.getsize(OUT) if os.path.exists(OUT) else 0
print("已保存 %s（%.1f / %.1f MB）" % (OUT, size / 1048576.0, (total or 0) / 1048576.0))
sys.exit(0 if total and size >= total else 1)
