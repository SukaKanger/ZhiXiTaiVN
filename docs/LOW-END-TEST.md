# 低端机实测手册

这份文档只回答一个问题：**一台中低端手机，能不能在自己身上把一个 WebGAL 工程打成 APK。**

工作站的主链路是在 Android 15 / API 35 模拟器（1971 MB RAM / 4 核）里验证的，结论是「能」。但那台机器是干净环境、没有后台进程争内存。低端真机的变量多得多——后台微信、系统省电策略、Termux 被 LMK 掐、存储被占满。这份手册就是把这些变量逐条钉死的清单。

---

## 一、结论先行：两档样本

| 档位 | 装什么 | 目的 | 已验证 |
|---|---|---|---|
| **L1 轻量** | `低配测试样本-1.0.0-lite.apk`（0.79 MB） | 只验证「引擎 + 本地服务 + 签名链」在这台机器上活着 | ✅ 模拟器 |
| **L2 真实** | `WebGAL工作站-1.0.0-lite.apk`（6.56 MB） | 验证「端上打包台 + Termux + javac」这条重链路 | ⚠ 组装件齐，Termux 端未实测 |

L1 是必过的底线：它连 Termux 都不需要，纯粹证明这台机器的 WebView 能渲染 WebGAL。如果 L1 都过不了，后面不用试了，直接走 PC 通道。

L2 才是真正的「低配创作者能不能自己打包」。它的风险点不是 APK 大小，是 **javac 的内存** 和 **Termux 的 ABI/权限**。

---

## 二、接入步骤（真机上手 20 分钟）

### 0. 先记参数（没记就别开测）

```bash
adb devices                       # 确认序列号，别装错机器
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell cat /proc/meminfo | head -3
```

把 `MemTotal` 和 `ro.product.cpu.abi` 记进第六节的表格。这两个值直接决定后面走哪条路径。

### 1. 装 L1（必做）

```bash
adb install -r --no-incremental "dist\低配测试样本-1.0.0-lite.apk"
adb shell am start -n com.zhixitai.vn.g450006694/.GameActivity
```

**必须带 `--no-incremental`**。APK 文件名带中文时，增量安装会报 `Invalid name`，这是实测踩过的坑。

预期：标题页 → 点屏 → 选语言 → 开始游戏 → 看到中文对话。中文靠系统字体回退渲染，字形会和标准版不同，但必须**清晰可读**，不是糊成一团方块。

### 2. 装 L2 工作站

```bash
adb install -r --no-incremental "dist\WebGAL工作站-1.0.0-lite.apk"
adb shell am start -n com.zhixitai.vn/.StationActivity
```

首启会弹免责门，先过门。然后「环境」页点 **探测设备**（跑 `probe.sh`），它会写出 `probe.json` 并给一个判决：

| verdict | 含义 | 下一步 |
|---|---|---|
| `ok` | 工具齐、RAM ≥ 3 GB | L1、L2 都试，按第三节判据打分 |
| `lite-only` | 工具齐、RAM 2–3 GB | 只打 L1 级别工程，L2 大概率被打断 |
| `pc-only` | RAM < 2 GB | 别在手机上硬扛，走「生成构建工程 → 拷电脑」 |
| `blocked` | 缺工具 / 存储 < 300 MB | 按提示装依赖或清空间，重跑探测 |

判决里的 `javaXmxMB` 是脚本会给 javac 的堆上限（RAM 的 1/3，2 GB 以下压到 1/4，夹在 256–1024 MB）。这个值不是拍脑袋写的，是拿来防 LMK 杀进程的。

### 3. 导入 L1 工程，预览

把 `samples/lite-sample/`（6 个文件、6.1 KB）拷进手机，任意目录都行：

```bash
adb push "samples\lite-sample" /sdcard/Download/lite-sample
```

「工程」页 → 选目录 → 预览。这一关看的是：**同一个工程，在手机工作站里的预览表现，和 L1 独立 APK 里的表现是否一致**。不一致就说明工作站那层封装有问题，不是引擎的问题。

### 4. 端上打包（Termux）

Termux 侧依赖（一次装好）：

```bash
pkg update && pkg upgrade -y
pkg install openjdk-17 aapt d8 apksigner zipalign zip -y
```

然后按 `README.md` 的 Termux 三步走（`allow-external-apps=true`、`termux-reload-settings`、环境页点「部署工具链」）。跳板脚本必须落在 `~/.webgal-station/run.sh`——Termux 只肯执行自己家目录里的脚本。

回打包台：选工程 → 填应用名/包名 → **开始构建** → 盯「终端」页日志。

### 5. 把产物装回来

```bash
adb pull /storage/emulated/0/Android/data/com.zhixitai.vn/files/station/build/<任务>/out/<应用名>.apk
adb install -r --no-incremental <应用名>.apk
```

装回来这一步是判据的一部分：**能在手机上打出来 ≠ 打得对**。签名不对、targetSdk 写错、resources.arsc 被压坏，都只在这一步暴露。

---

## 三、通过判据

低端机的验收不是「没崩」，是「可用」。按下表打勾，任何一项不过都记进表格。

| # | 项目 | 判据 | 怎么测 |
|---|---|---|---|
| 1 | L1 装机 | 安装成功，无 `INSTALL_FAILED_*` | `adb install` 返回 Success |
| 2 | L1 冷启动 | ≤ 8 s 出标题页（2–3 GB 机放宽到 12 s） | 掐表，从 `am start` 到标题可见 |
| 3 | L1 中文渲染 | 对话文字清晰可读，无豆腐块 | 截图肉眼判 |
| 4 | 工作站冷启动 | ≤ 10 s 进五区界面 | 掐表 |
| 5 | probe 判决 | 有 `verdict` 字段，值与 RAM 相符 | 看 `probe.json` |
| 6 | 预览首屏 | ≤ 15 s 出引擎标题页，日志有 `GET /` | 看终端页 |
| 7 | **L1 端上打包** | 成功，产物 ≤ 2 MB，耗时 ≤ 90 s | 看 `status.json` |
| 8 | **产物可安装** | 装回手机能启动、能看到来路角标 | `adb install` + 截图 |
| 9 | 打包期间不被杀 | 构建全程 Termux/工作站未被 LMK 干掉 | 结束后 `dumpsys activity processes | grep termux` |
| 10 | L2 真实工程（可选） | 成功，产物 ≤ 30 MB，耗时 ≤ 6 min | 同上 |

第 7、8 两条是**核心判据**：低配创作者的价值全部落在这两条上。第 9 条不算通过项，但它是「为什么失败」的关键线索——被杀和报错的处理方式完全不同。

---

## 四、失败了怎么办（决策树）

先看 `build.log` 最后 20 行和 `status.json` 的 `reason`，再按症状对号：

```
构建没跑起来
├─ status.reason 含「javac 不在」      → pkg install openjdk-17
├─ 含「aapt2 / aapt 都没有」          → pkg install aapt
├─ 含「d8 与 dx 都没有」              → pkg install d8（或 dx）
├─ 含「apksigner 不在」               → pkg install apksigner
├─ 含「找不到 android.jar」           → 重跑 setup.sh，或放到 ~/.webgal-station/android.jar
└─ 含「Termux 拒绝执行」              → allow-external-apps=true + termux-reload-settings

跑起来了但中途死
├─ 日志末尾是 "Killed" / 进程无声消失
│   ├─ RAM < 2 GB                   → 只打 L1；或关掉所有后台再试
│   ├─ RAM 2–3 GB                   → 关后台 + 关省电优化，重试一次
│   └─ 仍失败                        → 走 PC 通道，别硬扛
├─ javac 报 OutOfMemoryError
│   └─ 脚本已自动把堆砍半重试一次；还失败就是工程太大 → 换 L1
├─ d8 报 unsupported class version
│   └─ 脚本已自动回退 dx；dx 也不在就 pkg install dx
└─ aapt2 link 失败
    ├─ 报图标缺失                    → 检查 res/mipmap-*/ic_launcher.png
    └─ 报 manifest 解析错            → 包名/版本占位符没替换干净

产物出来了但装不上
├─ INSTALL_FAILED_INVALID_APK       → 签名不完整，确认用的是 apksigner 而不是 jarsigner
├─ INSTALL_FAILED_OLDER_SDK         → 产物 minSdk 高于手机，改构建参数
├─ INSTALL_PARSE_FAILED_NO_CERTIFICATES → 没签上，看 apksigner 那步日志
└─ 增量安装报 Invalid name           → 换 --no-incremental
```

---

## 五、真机实测结论（vivo V2219A，Android 13，MT6855V）

这台是 2026-11-13 接进来的第一台真机，从 Termux 装工具链到端上出 APK 全链路跑通了。

**能跑通的部分（有产物、有日志）**

1. **工具链装得上、跑得动。** Termux（F-Droid versionCode=1002）→ 清华镜像源 → `setup.sh` 装 openjdk-17 / aapt / aapt2 / apksigner / d8 / dx / zipalign / zip / unzip / curl，全绿。`probe.sh` 在这台机器上给的是 `verdict: ok`（`javaXmxMB=1024`、`cpuCores=8`）。
2. **端上真能打出 APK。** `aapt2 → javac → d8 → zipalign → apksigner` 全链在本机跑完，产物 6.4 MB，`apksigner verify` 通过，落盘 `build/<job>/out/WebGAL_移动工作站.apk`。
3. **工作站一键触发能跑。** 「生成构建工程 → 开始构建」这套按钮路径实测连过三次：**21 s、13 s、14 s**（其中一次是点完立刻切回工作站、构建照跑完的）。加速靠的是低配引擎（剔掉 36 MB 中文字体）+ 中间产物放 Termux 私有 `$TMPDIR` 而不是共享存储 FUSE。
4. **L1 样本装机跑通。** `低配测试样本-1.0.0-lite.apk`（0.79 MB）装上即开，中文字靠系统字体回退，标题/对话都清楚。
5. **首次构建的执行权限能自助拿到。** 未授 `com.termux.permission.RUN_COMMAND` 时，点「开始构建」会弹系统框「要允许 WebGAL 工作站 execute arbitrary commands within Termux environment…」，点允许后**自动开跑**，不用再点第二次。

**踩到并修掉的坑（这台机器特有，值得记住）**

1. **后台冻死：一键构建会卡在 javac，CPU 0%。** 现象是 `aapt2` 秒过、`javac` 一动不动几分钟，13 个线程全睡眠；同一份脚本在 Termux 前台手打 11~25 s 就跑完。抓现场看到进程落在 `4:freezer:/frozen`、`3:cpuset:/background`、`2:cpu:/background`——**Termux 一退到后台，整组进程被 ROM 冻进 freezer**，重活直接停摆。
   修法：`RUN_COMMAND` 发完请求后**立刻把 Termux 顶到前台**（`Termux.launchApp`），跳板用 `setsid nohup` 脱离会话自己写日志。这一条是端上能不能"点一下就出包"的关键，不是锦上添花。
2. **`pkg upgrade -y` 会把工具链弄成残废。** bootstrap 太旧，升级后 `curl` 缺 `SSL_set_quic_tls_early_data_enabled`、`aapt2` 缺 `_ZNSt6__ndk113__hash_memoryEPKvm` 符号，构建第①步即死。修法：`apt install -y libc++`（libc++ 27c→30）后 `aapt2 version` 恢复正常。`setup.sh` 已加「二进制能不能真跑起来」自检段，`probe.sh` 加了 `toolRun`/`curlBroken` 字段。
3. **共享存储不能当工作区。** `MANAGE_EXTERNAL_STORAGE` 没授时构建目录落在 `/Android/data/<pkg>/files/`，Termux 在 Android 13 读不到别家应用的私有外置目录，跳板判「脚本不存在」直接退出。修法：引导授权「所有文件访问」，构建根改到 `/storage/emulated/0/Download/WebGALStation/`。
4. **核数检测在真机回 1。** `getconf _NPROCESSORS_ONLN` 在 toybox 下不灵，改成 `getconf || nproc || grep -c processor /proc/cpuinfo` 兜底后正确显示 8 核。
5. **点击坐标不能拿截图目测。** 目测会差上百像素，点歪了会误触别的控件（这台机器上就被误点成「竖屏」和关掉「来路角标」）。必须 `uiautomator dump` 读 `bounds` 再点。

**第四批（2601.2 → 2601.10）在这台机器上验过的东西**

| 项 | 实测方式 | 结果 |
|---|---|---|
| 工作站版本递增 | 连装 26010002→26010003→…→26010009→26010010，每次 `dumpsys package` 核对 | 没有一次弹「已安装相同版本」；`lastUpdateTime` 每次都在变 |
| 游戏包版本递增 | 同一工程连点「生成构建工程」 | code 2604→2605→2606→2607，name 同步 `1.0.0-beta.2601.4/5/6/7`，界面 toast「版本抬到 …」 |
| 首启体检 + 自动拉权限 | 卸载重装后走一遍 | 免责门 → 700ms 后自动跳 vivo「管理所有文件」系统页 → 授权回来三条全绿，重启不再弹 |
| Termux 集成 | 环境页读 Termux 版本 | 0.118.3；未装时显示「未安装」并给 F-Droid 指引 |
| 双把手拉伸 | 拖右把手、拖左把手 | 两条栏都能拉，宽度进 prefs；窄了就自动降字号、双列拆单列 |
| 三布局 | 顶栏胶囊切居右/居左/居中 | 三种都出图；居中后舞台通栏、下方两栏 |
| 双主题 | 切亮色 | 黑白蓝生效，切回深色是纯黑白灰 |
| 宽度回收 | 两条栏都拉宽后切居左 | `fitWidths()` 自动压两栏，舞台保住 26% 宽，没再出现竖排单字 |
| 看门狗 | `am force-stop com.termux` 后点构建 | 30 秒准点出「Termux 没回话…」，进度条停在「卡住了 · Termux 没回写日志」 |
| Termux 强停时的发送 | 同上，点「开始构建」 | 不再把命令当 URL 塞进「输入网址」；改为复制命令 + 提示先「打开 Termux」；唤醒后再点 14 秒出包 |
| 「打开 Termux」按钮 | 卡住面板上点它 | 前台切到 `com.termux/.app.TermuxActivity`，不是 Floating 的网址框 |

**仍然没被证实的**

- **更低的机器没测过。** 这台 RAM 11.6 GB（`MemTotal 11882148 kB`），按 RAM/3 夹 1024 MB 上限取到 `javaXmxMB=1024`。3 GB / 4 GB 机器上这条断言、以及 L2（带配音立绘）工程能不能在 LMK 压力下活下来，都还没有数据。
- **`zipalign` 缺失时的产物没装机验证。** 这台装了 zipalign，走的是正常对齐路径。
- **低配模式的字体回退只验证了「能显示」。** `OPPOSans` / `ResourceHanRoundedCN` / `SourceHanSerifCN` 被剔掉后 CSS `@font-face` 会 404、回退系统字体，字形字重行高都会变。**这是设计取舍，不是 bug**——省的是 36 MB 体积和对应内存峰值。

---

## 六、实测记录表

每次测一台机器就复制一行。空着比编造强。

| 日期 | 机型 | RAM | 核 | ABI | Android | probe 判决 | L1 启动(s) | L1 打包(s) | L1 产物(MB) | 产物装机 | L2 打包(s) | 结论 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-13 | 模拟器 sdk_gphone64 | 1971 MB | 4 | x86_64 | API 35 | blocked（无 Termux 工具链） | 待测 | — | 0.79（PC 打出） | ✅ | — | 仅验证渲染链路 |
| 2026-11-13 | vivo V2219A | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok | 秒开 | 21 / 13 / 14 | 6.4（端上打出） | `apksigner verify` 通过；L1 样本装机 ✅ | — | **端上打包可行**，三次连过 |
| 2026-11-13 | vivo V2219A（1.0.0-beta.2601 三栏工作台） | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok | 工作台首屏秒开 | 16 / 15（新建 keystore 签名） | 6.4（端上打出） | versionCode=2601、versionName=1.0.0-beta.2601，签名者 d17f…（设备现生成） | 预览走 iframe，工作台不被顶掉 | **工作台式入口 + 玩家自管签名可行** |
| 2026-11-13 | vivo V2219A（第三批：新建项目 + 工作场景 + 工具箱） | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok | 工作台秒开，工作场景秒开 | 13 / 14 / 14（三次连过，含工具链钉路径那一次） | 0.78（MyGame 新工程，端上打出） | 新建工程 → 自动预览（`PRESS THE SCREEN TO START`）→ 编辑器改 `scene/start.txt` 落盘 → 构建成功 | 工具链 `tools.sh` 首次构建自动钉路径 | **低门槛创作闭环打通** |
| 2026-11-14 | vivo V2219A（第四批：版本递增 + 首启体检 + 双把手 + 三布局 + 双主题） | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok | 工作台秒开，体检弹层 3 条 | 13 / 14 / 14 / 14（含唤醒 Termux 后重发） | 6.4（端上打出，code 2604→2607） | 连装 26010002…26010010 十次全部成功；`appops set MANAGE_EXTERNAL_STORAGE allow` 后体检转绿 | 拖宽两栏后切布局，`fitWidths()` 回收，舞台保住 26% 宽 | **真机验收通过，含看门狗与 Termux 强停分支** |
| 2026-11-14 | vivo V2219A（第四批收尾 + 新手引导） | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok | 首启 5 步引导自动弹，切页正常 | 13 / 14 | 6.4（引导工程可选） | 连装 26010012→26010015 全部成功；引导工程卡片标「引导」、场景 3、预览可进标题画面 | 引导走完 `prefs.guideDone` 落盘，重启不再弹；删了能「重建新手引导」 | **首启不再是空白工作台** |
| 2026-11-14 | vivo V2219A（第五批：版本换号 1.1.5-beta + A0/A1 评审全量回填） | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok（换号后 HUD/环境页同口径） | 工作台秒开；剧本编辑页布局修正后左栏 170px、textarea 正常铺满 | 未重跑端上构建（脚本改动待下一批实测） | 6.62（PC 打出 beta.17 / 18 / 19） | 连装 26180017 → 26180018 → 26180019，`dumpsys` 三次版本号都对上 | 引导第 1 步卡片不再压住工程卡；环境页 ①②③ 步骤与「复制环境安装命令」文案已换 | **版本号换制落地**，`1.1.5-beta.N` 对外、`2618NNNN` 只进 versionCode |
| 2026-11-14 | vivo V2219A / Y77（第六批：端上构建真机闭环） | 11.6 GB | 8 | arm64-v8a | 13 (SDK 33) | ok | 工作台、编辑页、打包台三栏正常；包名空时「生成构建工程」正确置灰；构建完 UI 出「完成 · APK 已产出」 | **跑通三次：13~14 s 出包 6.4 MB，`status.json=success`** | 6.63（PC 打出 beta.23） | 连装 26180020 → 21 → 22 → 23，`dumpsys` 每次对得上 | 踩到 FUSE 不支持 flock（PITFALLS #45），锁挪 Termux 私有目录后通过；再抓到游戏包 versionName 还是 1.1.5（#46）与标题菜单德语（#47），两处都修完重测：端上产物 `2604 / 1.1.6-beta.4`，装机启动中文菜单、能进标题 | **端上链路「生成→构建→出包→装机→启动」全闭环，A1 脚本改动首次实证** |
| 　 | 　 | 　 | 　 | 　 | 　 | 　 | 　 | 　 | 　 | 　 | 　 | 　 |

注 1：模拟器那行不算真机结论。它的 1971 MB 恰好落在「2 GB 档」，所以堆上限按 RAM/4 = 492 MB 计算——这条规则本身在设备上验证过了。
注 2：V2219A 的耗时不含工程拼装（约 5~8 s），也不含系统授权弹窗等待。三次分别是「点完立刻切回工作站（21 s）」和「留在 Termux 看日志（13 s / 14 s）」——切回工作站也不影响跑完，因为构建已 `setsid` 脱离会话，且 Termux 刚被顶到前台没被冻。
注 3：这台 RAM 不算低配（11.6 GB），它的价值是证明「工具链能装、能跑、能出包、能装机」，不是证明「2 GB 也能跑」。
注 4：2601 版把入口改成三栏工作台（左工程 / 中 iframe 预览 / 右打包台），预览不再整页跳成游戏；签名段新增「新建 keystore」，端上 keytool 现生成、apksigner 校验通过，产物 versionCode=2601（此前被旧 prefs 拉回 1，已修）。
注 5：第三批真机路径（2026-11-13 晚，同一台 V2219A）：`新建项目` 输入名字 → 顶部「创建」→ `projects/MyGame/game/{config.txt,scene/start.txt,background,bgm,figure,animation}` 落地、工程卡片带 `NEW` 角标、中间舞台自动预览；`工作场景` 里改 `scene/start.txt` 保存后 `adb shell cat` 能读到新内容；`生成构建工程` → `开始构建` 14 s 出 `out/MyGame.apk`（800K，包名 `com.zhixitai.vn.mygame318402512`）。工具链钉路径那条日志：`已写入 …/toolchain.env`。

---

## 七、给低配创作者的实话

- **2 GB 机器不要指望端上打 20 MB 的工程。** javac 的堆已经压到 492 MB，再低就是 ClassNotFound 和 OOM 交替出现。这种机器正确的用法是：「工作站里写剧本、预览、导出构建工程，打包交给电脑」。
- **3 GB 机器可以打，但要关后台。** L1 级别（几十 KB 到 1 MB）稳；L2 级别（带配音、立绘）有被 LMK 打断的概率，测出来记表里比猜有用。
- **4 GB 以上、Android 11+、Termux 从 F-Droid 装的机器，是端上打包的舒适区。**
- **体积和保真度是跷跷板。** 低配模式砍掉 36 MB 字体换来 6.56 MB 的工作站和 0.79 MB 的产物，代价是排版变了。要按作者电脑上的样子出图，就用标准版（27.31 MB）。

## 第八批 · v2 界面（beta.26 → beta.31，vivo Y77 / 10ACCU1XH4003XK）

| # | 用例 | 期望 | 结果 |
|---|---|---|---|
| 1 | 冷启动 | 先出启动页，能列本机工程；点「直接进工作台」能关 | 通过（beta.28~31） |
| 2 | 首启免责 | 新装先出免责；同意后接启动页与引导 | 通过（beta.30 起，beta.29 及以前会断链） |
| 3 | 问答浮窗 | 点右下「问」出浮窗，再点球收起 | 通过（beta.28/29） |
| 4 | 配置持久化 | 填 base/model/key，杀进程重开仍在 | 通过（beta.26/28/30 实测） |
| 5 | 两栏收起 | 点两栏顶部「收起」，各缩成竖标签，舞台铺满 | 通过（beta.29 实拍，左右均可） |
| 6 | 收起还原 | 点细条能还原 | 部分通过：按钮稳，细条点击在部分布局下不灵，待修 |
| 7 | 8 步引导 | 自动弹出，走完 8 步，第 8 步指向浮球 | 第 1~3 步实测通过；4~8 步与 beta.24 一致，浮球目标待复验 |
| 8 | 自动保存 | 改剧本后等 5 分钟，自动落盘 + toast | 未跑满周期，代码已挂，待验 |
| 9 | AI 真实请求 | 公共兼容端点回中文答案 | 通过（beta.25 真机回显；beta.26+ 未重发） |

已知坑：uiautomator dump 在 vivo 上会间歇返回 1.7KB 残树；点按坐标要用 a11y bounds，别按截图目测。详见 PITFALLS #48~#53。