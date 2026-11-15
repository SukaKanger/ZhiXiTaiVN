# 架构说明

写给以后要改这个东西的人。重点不是「有哪些类」，而是**为什么这么设计**，以及哪几处一改就炸。

---

## 1. 三个执行面

```
┌──────────────────────── 工作站 APK (com.zhixitai.vn) ────────────────────────┐
│                                                                                │
│   StationActivity ── WebView ── assets/ui/*（工作站界面，五区）                  │
│         │                │                                                      │
│         │                └── http://127.0.0.1:<随机端口>/  ──┐                   │
│         │                                                    │                   │
│         ├── LocalServer ─────────────────────────────────────┘                   │
│         │      /        → assets/engine（WebGAL 4.6.4）                          │
│         │      /game/   → 工程目录副本                                           │
│         │      /ui/     → assets/ui                                              │
│         │      /legal/  → assets/legal                                           │
│         │                                                                       │
│         ├── Api (JS 桥，@JavascriptInterface)  ←→  ui/app.js 的 Station.*          │
│         ├── Store      工程库 + 首启标记 + 表单草稿                               │
│         ├── BuildKit   拼装一份可编译的安卓工程                                   │
│         ├── Termux     通过 RUN_COMMAND 拉起 Termux 里的脚本                       │
│         └── Installer  PackageInstaller 会话装 APK                               │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
                                   │ 共享存储
                                   ▼
        station/kit/{setup.sh,probe.sh,build_apk.sh,run.sh}  ──RUN_COMMAND──▶ Termux
                                                                              │
                                                                              ▼
                                                          aapt2 → javac → d8
                                                          → zip → zipalign
                                                          → apksigner
```

第三个面是 PC：`tools/pc/*.ps1` 走**同样的五步**、**同样的模板**，只是用 Windows 上的 SDK。
它的作用一是出差打版，二是端上链路出问题时的对照组。

---

## 2. 为什么必须有本地 HTTP

WebView 的 `file://` 源是「不透明 origin」：`fetch()` 一律拒绝，ServiceWorker 无法注册。
WebGAL 的读盘全部走 fetch/XHR，所以 `file://` 必然白屏。

换成 `http://127.0.0.1:port` 后：origin 合法、fetch 正常、ServiceWorker 可选、
相对路径 `./game/xxx` 天然映射到 `/game/xxx`。

### 2.1 端口为什么要每次换

WebView 的 HTTP 缓存、DOM Storage、ServiceWorker 都以 **origin**（含端口）为作用域。
同一个端口连续预览两个工程，前一个工程的 `localStorage`（存档、设置）会被后一个读到，
ServiceWorker 甚至可能返回旧的 JS。所以 `restart()` 每次都 `new ServerSocket(0, ...)` 拿新端口。
代价是每换一次预览，存档/设置都归零 —— 对「预览」这个动作来说，这正是想要的。

### 2.2 两个 Source 实现

| 实现 | 用途 | 关键点 |
|---|---|---|
| `AssetSource` | APK 内 `assets/` | `openFd()` 拿不到长度（assets 被压缩时会抛），退化成「读全流数字节」；结果按路径缓存进 `lenCache`，不然每次请求 13MB 字体都要从头读一遍 |
| `FileSource` | 共享存储里的工程 | `getCanonicalPath()` 前缀校验，挡 `..` 穿越 |

`/` 挂载的匹配是按 **prefix 长度降序** 遍历的，所以 `/game/`、`/ui/` 先命中，
其余落到根挂载 —— `assets/index-x.js` 这种引擎内部路径自然就通了。

Range 请求有实现（206 + `Content-Range`），因为音视频和字体偶尔会带 `Range`。
所有响应带 `Cache-Control: no-store`，配合换端口双保险。

---

## 3. 一份宿主代码，两个产物

产物（游戏 APK）里没有工作站代码，只有 `GameActivity` + `LocalServer`。
它们和工作站里跑的是**同一份源文件**：

- `templates/game/java/GameActivity.java` 首行 `package __PKG__;`，构建时替换
- `src/java/com/zhixitai/vn/LocalServer.java` 首行 `package com.zhixitai.vn;`，构建时替换

模板放在 `templates/`（唯一来源），构建前由脚本同步进 `src/assets/templates/`，
工作站运行时从 assets 读。这样装机版和源码版不会漂移。

`GameActivity` 除了 WebView + LocalServer，还做了一件合规上的事：
启动时读 `assets/legal/badge.txt`，左下角显示 4.2 秒来路角标
（「WebGAL 4.6.4（MPL-2.0 非官方构建）· 由 WebGAL 移动工作站打包」），深色底衬，白底场景也看得清。

---

## 4. 构建管线（同一套五步）

| 步 | 工具 | 输入 → 输出 | 坑 |
|---|---|---|---|
| 1 | `aapt2 compile` + `link` | `res/` + `assets/` → `base.apk` | `-A` 指向 assets；`--auto-add-overlay`；`-I android.jar` 必须有 |
| 2 | `javac` | `src/**/*.java` → `classes/` | `-source 8 -target 8 -bootclasspath android.jar -encoding UTF-8`；`android.jar` 挑 `platforms/*` 里**真带 jar** 的那份（本机 `android-34` 目录里没有 jar） |
| 3 | `d8` | `classes/` → `classes.dex` | `--lib android.jar --min-api 21` |
| 4 | 塞 dex | `classes.dex` 追加进 `base.apk` | **绝不能用 .NET / 普通 zip 重写整包**：会把 `resources.arsc` 压掉，targetSdk≥30 直接装不上。PC 侧用 python `zipfile(mode='a', ZIP_STORED)` 只追加，端上优先 `zip`，没有 `zip` 再退 `jar uf` |
| 5 | `zipalign` / `apksigner` | 对齐 + 签名 | `zipalign -p 4`；`apksigner --min-sdk-version 21`；签完必须 `verify` |

校验点（每次构建都验）：`resources.arsc` 必须是 STORED（未压缩）、`classes.dex` 必须存在、
`aapt2 dump badging` 里包名/SDK/label/launchable 必须对。

---

## 5. 许可边界怎么落到代码里

| 层 | 文件级动作 |
|---|---|
| **MPL-2.0 引擎** | `assets/engine/` 原样，只删 `*.gz`；不往里面塞任何自研代码；`NOTICE.txt` 列改动清单 + 上游源码获取地址 |
| **GPL-3.0 工具** | 不进 APK、不链接、不合并。工作站只做一件事：`RUN_COMMAND` 让 Termux 以独立进程执行脚本。`NOTICE.txt` 明写「聚合体」边界 + 工具名/版本/来源 |
| **Apache-2.0 工具** | `aapt2/d8/apksigner` 由 SDK 提供，只在命令行被调用 |
| **MIT 原创** | 每个 `.java` / `.sh` / `.ps1` 文件头 `SPDX-License-Identifier: MIT`；`LICENSE-MIT.txt` 全程随附（含产物内） |

三条硬线：
1. 自研代码不写进 `assets/engine/`；
2. `assets/legal/` 三个全文文件在**任何**分发路径上都不许少（PC 脚本和 `BuildKit` 都会主动校验/复制）；
3. 产物默认带 `badge.txt`，界面上也有一整张组件级对照表，不是一句「本项目使用 MPL 代码」。

---

## 6. 改哪里会炸（真实踩过的坑）

| 症状 | 真因 | 修法 |
|---|---|---|
| 点了预览只转屏、画面不动，日志里连请求都没有 | JS 桥线程上调了 `web.loadUrl()`，WebView 要求主线程；异常被 `catch(Exception)` 吞掉 | UI 操作全部 `runOnUiThread` |
| 端口在监听（`/proc/net/tcp` 有 0A），请求永远挂起 | `restart()` 关旧 socket 后旧 accept 线程可能对 `null` 字段调 `accept()` → NPE 静默打死线程 | accept 循环持有本代 `ServerSocket` 引用，且 `ss == serverSocket` 才继续 |
| javac 报「需要 class, interface 或 enum」 | 注释里写了 `mipmap-*/`，`*/` 提前闭合了块注释 | 别在块注释里写 `*/` 序列 |
| 装不上包，报 `resources.arsc` 相关 | 用 .NET 重写 zip 把 `resources.arsc` 压了 | 只追加、不重写；追加用 STORED |
| `window.confirm()` 永远返回 false | 没实现 `WebChromeClient.onJsConfirm` | 全 UI 禁用原生 confirm，删除改二次点击 |
| 包名全是乱码后缀（`webgalp79p52...`） | 中文名逐字转 hex | 取 ASCII 前缀 + 字符码累加后缀；不要用 `GetHashCode()`（.NET 每进程随机） |
| `adb install` 一直不结束 | vivo 的安装拦截页在等「继续安装」 | 后台跑 install + `input tap 540 2210` + 查 `lastUpdateTime` |
| javac 报 `找不到符号 metafactory` | 端上源码用了 lambda，`android.jar` 里没有 `LambdaMetafactory` | 改匿名内部类，模板源码同样禁用 lambda |

---

## 6.5 第三批：工作场景（编辑器）与内置工具箱

### 6.5.1 工作场景

界面在 `src/assets/ui/` 里是第 2 个 panel（`#tab-editor`），布局 `左场景列表 + 右脚本编辑`：

```
Store.Project.gameDir
└─ scene/*.txt          ← 列表直接 listFiles，读写走 apiReadText / apiWriteText
└─ background/ bgm/ figure/ animation/   ← 只读一览，给"能插什么"一个参照
└─ config.txt           ← 读出来放 ws.config
```

原生接口（都在 `Api.java` 里 `@JavascriptInterface` 暴露）：

| 方法 | 作用 |
|---|---|
| `workspace(id)` | 一次拿场景列表（名字/大小/行数）+ 资源目录 + config 原文 |
| `installTutorial()` | 清掉 `projects/tutorial` 再铺一遍内置引导工程，返回 `{ok,id,name}` |
| `readText(id, rel)` | 读 `game/` 下任意文本，路径做 canonical 越界校验 |
| `writeText(id, rel, text)` | 写回，父目录不存在自动建 |
| `newScene(id, name)` | 建 `scene/<name>.txt`，带一句占位旁白 |
| `deleteScene(id, name)` | 删场景并刷新工程统计 |

约束：`rel` 一律相对 `game/`，禁止 `..`；写完后 `refreshProjectStats` 把列表里的
文件数/场景数/体积同步一遍，避免面板数字和磁盘打架。

### 6.5.2 真·Terre 前端为什么没直接塞进来

`D:\WebGAL\release\public\` 是纯 Web 产物（`index-01972026.js` 12.34 MB + `index-38f10566.css`），
**不依赖 Electron IPC**，全部走本地 REST：

```
GET  /api/osinfo, /api/test, /api/userData/status
GET  /api/manageGame/gameList, /getGameConfig/{game}, /readGameAssets/{game}
POST /api/manageGame/createGame, /createNewScene, /editScene, /editTextFile, ...
…… 共 45 条（assets / manageGame / manageTemplate / userData 四组）
```

所以真要「一模一样」，路线是：`public/` 整包进 `assets/editor/` → `LocalServer.mount("/editor/")`
→ 在 Java 里复刻这 45 个端点（前端是 `axios` + JSON body，少数 FormData）。
按最小可启动集先做 `osinfo / test / userData/status / gameList / getGameConfig /
readGameAssets / editScene / editTextFile / createNewScene / createGame / templateList`，
其余按报错逐个补。这条路可行但工作量大，单轮收不掉，所以本轮先交付同构工作场景。

### 6.5.3 内置工具箱（toolchain 钉路径）

`assets/kit/tools.sh`：

```
bash tools.sh            检测 + 写 $HOME/.webgal-station/toolchain.env（路径固化）
bash tools.sh --status   打印 JSON（给界面读）
bash tools.sh --install  缺谁装谁（pkg install，只装缺的，不做 pkg upgrade）
```

`build_apk.sh` 开头先 source 这份 env，再退回 `PATH`；env 不存在时自动跑一次 `tools.sh`。
`setup.sh` 第 7 步也会跑它。这样端上重复构建不用再靠 PATH 碰运气，
但**不是**把 JDK/d8 打进 APK——`javac`/`d8`/`apksigner` 都是 Java 程序，Android 侧没有 JVM，
真正执行仍要 Termux 这个壳；「内置」的部分是脚本 + 路径固化 + 只缺才装。

---

## 6.6 怎么验（第三批）


---

## 7. 怎么验（照着做一遍）

1. `.\tools\pc\build-station.ps1` → `dist\WebGAL工作站-1.0.0.apk`
2. `adb install -r --no-incremental`（**别用增量安装**：APK 名里有中文时 incremental 会报 `Invalid name`）
3. 起模拟器：`emulator -avd <name> -no-window -no-audio -gpu swiftshader_indirect`，
   有 AVD 目录环境变量时先 `ANDROID_AVD_HOME=<...>\avd`
4. `am start -n com.zhixitai.vn/.StationActivity`，点「同意」→ 工程页 → 预览
5. `adb logcat -s LocalServer` 看请求流水：`/`、`/assets/*.js`、`/game/scene/*.txt` 都该出现
6. 打包页「生成构建工程」→ `adb pull` 那份工程 → 用 PC 管线编译 → 安装 → 看角标

坐标点击不稳时，别猜：截图为 1080×2340 原尺寸，用像素扫描（找琥珀色 `#FFB020` 边框）定位按钮行，
一次就能把 `y` 卡准。

## 八、首启引导这套东西怎么串的

三段，按顺序来，别叠：

1. `#gate` 免责门（只有首次）
2. `#onboard` 环境体检（`apiOnboard()` 返回 allFiles/termux/termuxRun/kitDeployed/sdk/version）
3. `#guide` 五步引导（`GUIDE_STEPS` 里五项，每项 `{tab, sel, title, text}`）

触发链：`maybeShowGate()` → 同意后 80ms `maybeShowOnboarding()` → 体检关掉后 200ms `maybeShowGuide()`；
`init` 里另有一条兜底（门和体检都隐藏、且 900ms 后仍没人弹就补引导），
用 `prefs.guideDone` 记「已经走过」，顶栏 `#btnGuide` 可各自重看。

`positionGuide(sel)` 的放置顺序是「右 → 下 → 上 → 左 → 兜底压目标」，目标必须用
`document.querySelector` 真验一次——引导写过 `sel: '.dock'` 这种不存在的选择器，
结果就是卡片飘在屏幕中央、洞是 0×0（见 PITFALLS 41）。

引导工程 `projects/tutorial` 由 `ensureTutorial()` 铺：先拷 `demo-game` 拿素材，
再覆盖 `tutorial-game` 的 config 和 3 个场景，最后删掉 demo 用不上的 4 个场景。
`source = tutorial` 在 `apiProjects` 里原样透出，前端据此打「引导」标签。

