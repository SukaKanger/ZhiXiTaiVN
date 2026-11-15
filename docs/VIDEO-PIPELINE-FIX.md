# 视频管线修复记录 · 1.1.7-beta.39

日期：2026-11-14　范围：教学视频（四期）出片链路　不涉及 APK 源码与重打包

## 一、结论

旧片整批作废，**不只是素材旧**。出片器本身有三个硬 bug，任何一个都能让"换套新截图重跑一遍"继续翻车：

1. SRT 时间码函数用 `[int]` 当截断用，而 PowerShell 的 `[int]` 是**四舍五入** —— 分钟提前进位，出现 `00:01:60,990` 这种非法值，甚至起始时间大于结束时间。
2. 截图直出，**不裁手机状态栏**（顶部 90px 的时间/电量/Wi-Fi 全程在画面里）。
3. 用 `scale=1920:1080:force_original_aspect_ratio=decrease,pad=...` 把 2388×1080 的真机截图塞进 16:9，上下各留约 106px 纯黑边，截图变成"黑框里一条窄带"。

另外两个数值问题（不是 bug，是设计错）：按「170 字/分」正比分配画面停留，一句话被拉到 5–8 秒，全片 133–158 秒，直接越过 120 秒上限；`atempo` 还被硬夹在 0.85–1.12 去迁就旁白，画面和口播互相将就。

修复后的唯一入口：

```
tools\pc\video\crop-statusbar.ps1   抓完图先裁状态栏（可批处理）
tools\pc\video\build.ps1            按 plan 出片（画面 + 烧字幕 + SRT + 旁白稿）
tools\pc\video\verify.ps1           硬校验，任一项不过就 exit 1
tools\pc\video\tap.ps1              按文字点按钮，坐标取 uiautomator dump 中心
```

四个旧出片器（`make-tutorial-videos.ps1` / `-v2` / `-v3` / `make-silent-videos.ps1`）已在首行加硬拦截：一跑就抛
`已废弃：请用 tools\pc\video\build.ps1`，防止谁顺手一跑又出一批带状态栏、时间码错乱的废片。考古需要用就先删那行 `throw`。

## 二、缺陷表（现象 → 根因 → 证据 → 修法）

| # | 现象 | 根因 | 证据（可复核） | 修法 |
|---|---|---|---|---|
| 1 | SRT 时间码错乱：第 6 条 `00:01:56,290 --> 00:01:03,460`，第 10 条 `00:01:23,320 --> 00:02:30,490` | `tools\pc\make-silent-videos.ps1` 第 34–38 行 `SrtTime()` 里 `$h/$m/$sec` 三处用 `[int]` 取整；`[int]` 是银行家舍入不是截断 | 实测：`93.49s` 输出 `00:02:33,490`（应为 `00:01:33,490`）；`59.99s` 输出 `00:01:60,990`（非法） | `build.ps1` 的 `SrtTime()` 全部改成 `[math]::Floor`；`verify.ps1` 校验 start<end、单调、首尾相接 |
| 2 | 画面顶部完整露出手机状态栏（时间、电量、Wi-Fi） | 抓完 `adb screencap` 直接进片，没有裁 | 抽帧：`build\video\big\05-AI帮你写剧本-端上打成APK.mp4` 第 40s 帧有 `04:38` 状态栏；`dub\01` 第 70s 帧有 `23:00` | `build.ps1` 识别 2388×1080 原图后自动 `crop=2388:990:0:90`；`crop-statusbar.ps1` 可先批量裁；竖屏素材直接抛错 |
| 3 | 截图缩在 16:9 中间，上下黑边约 106px，字幕条又贴最底 | `Shot()` 用 `force_original_aspect_ratio=decrease` + `pad` | 2388×1080 按宽 1920 缩完高 868，上下各 106 黑边 | 改成：应用窗口 1840 宽居中 + 1px `#2b2b36` 边框，上下留白分别承担期号条与字幕条 |
| 4 | 全片 133.47s / 158.4s，超出 45–120s | 按 170 字/分 分配画面停留 | `ffprobe` 实测 `dub\01` 133.47s、`big\05` 158.4s | 改 4.6 字/秒；单镜 2.2–6.5s；全片 45–120s 硬校验，压不进就报错让你删镜头 |
| 5 | 画面里混着旧版本 UI | plan 复用历史截图 | 抽帧版本读数：`1.0.0-beta.2601`（第 70s）、`1.1.6-beta.29`（第 12s）、`1.1.7-beta.39` 才是本次素材 | `build.ps1` 拒绝任何 `build\video\` 下的路径；`verify.ps1` 复核每张素材的尺寸/来源 |
| 6 | 字幕压住界面、字号 44 居中 | `drawtext ... fontsize=44 ... x=(w-text_w)/2:y=h-text_h-54` | 抽帧可见字幕条压在最底、与内容区重叠 | 字号 40、左下 `x=52`、最多两行、按标点断行，落在窗口下沿以下的留白区 |
| 7 | 含 `'`/`,`/`%`/`:` 的字幕可能被 drawtext 吃掉或显示成字面量 `\u2019` | `Esc()` 手工转义，`"'"` 被替换成字面量 `\u2019` | `make-silent-videos.ps1` 第 18 行 | 改 `textfile=` + `expansion=none`，文案原样写进 UTF-8 文件，不再手工转义 |
| 8 | 自动点按钮经常"点了没反应" | 坐标靠截图目测，普遍偏高 50–60px；离屏节点 `bounds=[0,0][0,0]` 会点到 (0,0) | `docs\BUG-ENV-CLICK.md` | `tap.ps1` 用 `uiautomator dump` 的 bounds 中心；`bounds` 宽高为 0 一律跳过；一次命中多个就拒绝乱点 |
| 9 | 片头卡模板可能残留资助信息 | 旧卡是位图，改一次要重画一次 | `make-title-cards.ps1` 里固定文案 | `build.ps1` 用 System.Drawing 重画片头/尾卡，画面里只有 `github.com/SukaKanger` |

## 三、修复后的硬指标

| 项目 | 值 |
|---|---|
| 画幅 | 1920×1080，16:9 |
| 帧率 / 编码 | 30fps / h264 / yuv420p |
| 音轨 | 无（静音底片，配音另配） |
| 时长 | 45–120 秒，超界直接报错 |
| 版面 | 应用窗口 1840×762 居中，1px 边框，`#0b0b0d` 底 |
| 字幕 | 40px 微软雅黑、左下起排、≤2 行、≤26 字/行、黑底 72% 透明 |
| 素材 | 真机 beta.39 截图，2388×1080（自动裁 90px 状态栏）或 2388×990 |
| 片头卡 | 品牌行 → 主张两行 → `支持手机打包 APK` → 期号 → GitHub（3.2s） |
| 尾卡 | `github.com/SukaKanger` + 详情看主页（3.0s） |
| 品牌 | 画面只出现 GitHub；爱发电只进发布文案/置顶评论 |

## 四、怎么用（四期共用）

**1. 抓图**（真机横屏，`R:\soft\android202422\sdk_home\platform-tools\adb.exe`，设备 `10ACCU1XH4003XK`）

```powershell
adb -s 10ACCU1XH4003XK exec-out screencap -p > build\video4\shots\raw\01-xx.png
.\tools\pc\video\crop-statusbar.ps1 -InDir build\video4\shots\raw -OutDir build\video4\shots
```

点按钮别目测，用 `tap.ps1 -Text "引导" -DumpOnly` 先看命中的 bounds，再去掉 `-DumpOnly` 真点。

**2. 写 plan**（`build\video4\plans\NN.json`）

```json
{ "id": "01", "title": "工作台与两栏收起", "no": "第 1 期",
  "shots": [
    { "img": "build\\video4\\shots\\01-01.png", "sub": "左边工程库，每个工程写着场景数、文件数、体积" }
  ] }
```

片头卡和尾卡**不用手写**，`build.ps1` 自动补。口令句 ≤52 字（两行上限），超了会报错让你拆句。

**3. 出片**

```powershell
.\tools\pc\video\build.ps1 -Plan build\video4\plans\01.json
```

产出：`build\video4\out\01-工作台与两栏收起-静音底片.mp4` + `.srt` + `-旁白.txt`。

**4. 校验（必须过）**

```powershell
.\tools\pc\video\verify.ps1 -Mp4 <片> -Srt <srt> -Plan build\video4\plans\01.json
```

14 项检查，任一 FAIL 就 `exit 1`，同时抽 `head/mid/tail` 三帧到 `_check\` 供人眼复核（看有没有状态栏、字幕压没压 UI）。

## 五、本次实跑证据

冒烟片（8 镜，`build\video4\plans\_smoke-test.json`）：

```
99-管线冒烟测试  总时长 45s  字幕 8 条  0.73 MB
verify: 14 项 PASS / 0 FAIL
抽帧：build\video4\_smoke\_check\{...-mid.png, s1.png, tail.png}
```

另用别的子代理写的 `build\video4\plans\01.json` 做了一次跨人联调（证明 plan 格式能直接吃）：

```
01-工作台与两栏收起  总时长 45s  字幕 8 条  0.77 MB
verify: 14 项 PASS / 0 FAIL
输出：build\video4\_proof\
```

注意：这条 01 样片**只验证机制，内容不合规**（素材还是环境页 + 禁止内容），文件名带 `_proof` 前缀，不许当成品发布。

SrtTime 新旧对比（同一组输入）：

| 秒 | 旧版输出 | 新版输出 |
|---|---|---|
| 26.0 | `00:00:26,000` | `00:00:26,000` |
| 59.99 | `00:01:60,990`（非法） | `00:00:59,990` |
| 93.49 | `00:02:33,490`（错） | `00:01:33,490` |
| 116.29 | `00:02:56,290`（错） | `00:01:56,290` |
| 150.49 | `00:03:30,490`（错） | `00:02:30,490` |
| 3599.9 | `01:60:60,900`（非法） | `00:59:59,900` |

## 六、素材缺口（必须重抓，别拿现有 b39 图凑）

对 `build\b39-*.png` 做了逐张核验（见 `build\video4\plans\01-audit.md`）：

- 25 张 b39 图里，24 张横屏 2388×1080、1 张竖屏（`b39-termux7.png`，禁用），**全部带顶部状态栏**（出片时会自动裁，但原图仍留底）。
- 内容只覆盖：环境自检、Termux、离线包释放、权限授予、终端命令行。**没有一张停在工作台页签**——工程库 / 预览舞台 / 打包台三栏同框、两栏收起与展开、拖竖缝、预览控件、八步引导、不会就问、端上打包，这些在 b39 素材里全部缺失。
- 更麻烦的是：这批图里大量画面**本身就是本次禁止的内容**（装包、Termux、离线包释放），就算裁了状态栏也不能进片。
- 还有同名不同代的干扰项：`build\b41-*`（beta.41）、`b37/b38`（旧 beta）、`build\video\frame1.png`（beta.30 品牌 `WEBGAL`）、`frame2.png`（beta.25）。除 b39 以外，一律不许当证据。

**所以第 1–4 期都要重新抓图**，抓的时候照这个顺序：

```powershell
# 1) 先 dump，确认按钮真实坐标（别目测）
.\tools\pc\video\tap.ps1 -Text "工作台" -DumpOnly
# 2) 确认命中唯一节点后再真点
.\tools\pc\video\tap.ps1 -Text "工作台"
# 3) 抓图（横屏、应用在前台）
adb -s 10ACCU1XH4003XK exec-out screencap -p > build\video4\shots\raw\01-work.png
# 4) 裁状态栏
.\tools\pc\video\crop-statusbar.ps1 -InDir build\video4\shots\raw -OutDir build\video4\shots
```

每张图进片前只认两条：**版本读数必须是 1.1.7-beta.39**（画面左上角胶囊），**顶部已经看不到时间/电量**。

## 七、边界与没做的事

- 没碰 APK 源码、没重打包、没改 `src\`。
- 没有在真机上执行 tap（设备正被其它子代理并发抓图，避免串状态）；`tap.ps1 -DumpOnly` 的读路径可用，真点路径等你独占设备时再验。
- 旧素材**没有删除**：`build\video\{big,dub,v2,jy,tmp}`、`plan5.json`、旧 `ep*.wav` 全部原地保留但作废，`build.ps1` 会拒绝引用；确认不再需要时再自己清。
- 四期成片与发布文案由视频子代理产出，本文件只负责把管线修到"换素材就能出合规片"。
