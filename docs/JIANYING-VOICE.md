# 剪映出「曼波讲故事」——五期旁白的正确做法

## 结论先说

「曼波讲故事」是**剪映（CapCut 国内版）文本朗读 →「解说」分类里的内置音色**，不是能单独下载的模型。
B 站有人专门做过「耳熟但无名的配音」考据，把「幽默解说 → 剪映『曼波讲故事』」钉死在剪映里；抖音上大量「白嫖曼波配音」教程也是同一路径。

所以：

- GitHub / HuggingFace 上搜不到它的权重。能搜到的「曼波」是社区另做的近似货（Fish Audio 的 `曼波`、NiceVoice 的「曼波讲故事」、Skywork 的 Mambo TTS≈Edge TTS `zh-CN-XiaoyiNeural` 升 8% 音高），**都不是那个味儿**，别浪费时间去试。
- 剪映现在把不少音色塞进了 VIP；「曼波讲故事」在部分版本是 VIP、部分版本免费。真要卡住，社区的老办法是**剪映 6.0.1 绿色便携版**，或直接去缓存目录把 wav 捡走（见下）。

## 手动路径（3 分钟，最稳）

1. 剪映专业版 → 开始创作 → 建一个空草稿。
2. 顶部「文本」→ 点「默认文本」，把该段旁白粘进去（文本源已备好，见下表）。
3. 选中这段文本 → **文本朗读** → 分类切到「**解说**」→ 选「**曼波讲故事**」→ 生成。
4. 生成完不用导出视频。剪映把朗读音频缓存在：
   ```
   C:\Users\Administrator\AppData\Local\JianyingPro\User Data\Projects\com.lveditor.draft\<草稿名>\textReading\
   ```
   里面的 `.wav` 就是原声，直接拷出来。
5. 每期两段（A/B），共 10 段，按下面命名丢进 `build\video\jy\`。

## 文本源（已生成，直接复制粘贴）

| 文件 | 字数 | 说明 |
|---|---|---|
| `build\video\jy\01A.txt` | 193 | 第 1 期前半（片头后到 `split`） |
| `build\video\jy\01B.txt` | 110 | 第 1 期后半 |
| `build\video\jy\02A.txt` | 186 | |
| `build\video\jy\02B.txt` | 91 | |
| `build\video\jy\03A.txt` | 202 | |
| `build\video\jy\03B.txt` | 158 | |
| `build\video\jy\04A.txt` | 245 | |
| `build\video\jy\04B.txt` | 198 | |
| `build\video\jy\05A.txt` | 241 | |
| `build\video\jy\05B.txt` | 150 | |

命名规则：`01A.wav / 01B.wav … 05B.wav`（`.mp3` 也认）。

## 语速：按 170 字/分自动校准

用户口径是「第一版太快、第二版太慢，取中间值」。v1 原速约 200+ 字/分，v2 被压到 150 字/分，中间值落在 **170 字/分**。

`tools\pc\make-tutorial-videos-v3.ps1` 会：

1. 依次读 `01A/01B … 05B`，按字数算出理想时长 `字数/170*60`；
2. 用 `atempo = 实际时长 / 理想时长` 微调，限幅 `0.85 ~ 1.12`（防止变机器人）；
3. 接上 v2 的片头 hook（26s，逐期 26/26/26/26/27s），保留 0.3s 停顿缝合；
4. 片头两镜吃掉 hook 的 55%/45%，其余镜头按字幕字数分，前两镜不叠字幕；
5. 输出 `build\video\big\0x-*.mp4`。

一条命令重出五期：

```powershell
& "C:\Users\Administrator\Doubao\chats\2026-11-13\new-chat\WebGAL-Mobile-Workstation\tools\pc\make-tutorial-videos-v3.ps1"
```

脚本跑完会打印每期的 `时长 / 体积 / atempo`。目标：每期 1–3 分钟、170 字/分。

## 为什么不是我用 GUI 直接点

剪映专业版 11.39-ALPHA 在用户主桌面上被「积分包 6 折 / AI Ultra / Seedance2.0 Mini」推广弹窗反复盖住，且同屏还压着游戏窗口；`Alt+Tab` 会切到用户的游戏里（实测切到了「来电：爸、妈」那屏），继续盲点有误触风险，所以停手。等桌面干净、剪映单独最大化时再点，是 5 个草稿 × 2 段的事。
