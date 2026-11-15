# 踩坑记录 · WebGAL 移动工作站（1.0.0-beta.2601）

> 这份文档只记「真机 / 真构建 / 真装包」时实际踩到的坑，以及最后怎么绕过去的。
> 凡是没实测过的猜测，一律标注「未验证」，别当成结论用。
> 环境基准：vivo V2219A（联发科 MT6855V，Android 13 / SDK 33，arm64-v8a，
> 1080×2388，8 核，11.6 GB RAM），Termux 走 F-Droid 版 versionCode 1002。

---

## 一、端上打包（Termux + BuildAPKs）

### 1. 一键构建间歇卡死在 javac —— 根因是「Termux 被冻」，不是构建脚本

**现象**：工作站里点「开始构建」，状态一直卡在 `javac`；`ps` 看 javac 存在但 CPU 0%，
13 个线程全是 S 态，等 5 分钟也不动。同一个脚本在 Termux 交互 shell 里跑 11 秒完事。

**抓现场**：
```sh
cat /proc/<pid>/cgroup
# 4:freezer:/frozen
# 3:cpuset:/background
# 2:cpu:/background
```
国产 ROM 把整个 Termux 进程组丢进 background cpuset 并冻结（freezer）。`aapt2` 因为跑得快，
冻结前就结束了，所以表象像「只有 javac 卡」——这是最坑的地方，误判方向会去怀疑 javac 参数。

**排除实验**（都做过）：
- `env -u LD_PRELOAD` 跑 javac → 正常，排除 termux-exec 预载缺失。
- `setsid nohup` 手工脱离会话 → 仍会卡，说明单纯脱离进程组不够。
- 交互 shell 里跑 → 正常，说明脚本本身没问题。

**最终修法**（三条一起上才稳）：
1. `RUN_COMMAND` 发出去之后立刻 `Termux.launchApp(this)`，把 Termux 顶到前台（前台进程不会被冻）。
2. `run.sh` 里用 `setsid nohup ... </dev/null >>runner.log 2>&1 &` 脱离会话，日志自己写文件。
3. 中间产物搬进 Termux 私有目录 `$TMPDIR/build-<job>/work`，别在 `/sdcard` 上反复读写。

修复后连续三次构建成功：**21s / 13s / 14s**（其中一次点完立刻切回工作站，仍然跑完）。

**给用户的提示**：环境页写了「别把 Termux 丢后台」；构建卡住时先看 Termux 是否在前台。

### 2. `pkg upgrade -y` 直接把 Termux 干崩

升级基础包时 libc++ / openssl 这类依赖会被换掉一半，中途崩就再也起不来。
**改成**：只 `apt install -y libc++ openssl`，按需补，不整体升级。`setup.sh` 的「1.5 二进制能不能真跑起来」
自愈段就是干这个的——先 `aapt2 version` 试跑，报符号缺失才装 libc++/openssl，装完再试。

### 3. `curl` 报符号缺失 = Termux 预编译二进制跑不动

不是网络问题，是 libc++ / openssl 版本对不上。判据在 `probe.sh` 里：`toolRun` + `curlBroken` 字段。
两样都红就直接判 `blocked`，别让用户在那儿傻等构建。

### 4. toybox 和 GNU coreutils 不是一个东西

真机上踩到的：
- `sed` 不认 `\s`（`grep -E '\s'` 也别指望），用 `[[:space:]]`。
- `df` 没有 `-m`，只能 `df -k` 自己算。
- `getconf _NPROCESSORS_ONLN` 返回 1，核数得从 `/proc/cpuinfo` 数 `processor` 行。

脚本里凡是依赖这些的地方都改写过了；以后加脚本先在真机上 `sh -n` 再跑。

### 5. `/Android/data/<pkg>/files` 别的应用读不到

Android 11+ 的分区存储：没有 `MANAGE_EXTERNAL_STORAGE` 时构建根目录会落到
`/Android/data/<pkg>/files`，Termux 完全看不见，于是「构建工程生成成功但 Termux 找不到文件」。
**解决**：引导用户去「申请所有文件访问」，构建根固定到
`/storage/emulated/0/Download/WebGALStation`。
> 未验证：真机上这次是用 `appops set ... MANAGE_EXTERNAL_STORAGE allow` 直接授的，
> UI 按钮跳系统设置那条路还没在干净设备上走过一遍。

### 6. RUN_COMMAND 的环境和交互 shell 不一样

实测 RUN_COMMAND 起的进程缺 `LD_PRELOAD / SHELL / HISTCONTROL / OLDPWD`。
一开始怀疑这就是 javac 卡死的原因，后来证明不是（见第 1 条），但**写脚本别依赖这些变量**这条经验保留。

---

## 二、装包 / 验包

### 7. `adb install` 增量安装中文文件名报 `Invalid name`

产物叫 `WebGAL_移动工作站.apk`，走增量安装直接 `Invalid name`。
**必须** `adb install -r --no-incremental <apk>`。

### 8. vivo 装包要点「继续安装」，坐标不能目测

安装页真实可点区域（`uiautomator dump` 的 bounds）：`继续安装 [444,2206][636,2263]`，
中心 **(540,2234)**。截图目测会差上百像素，之前误点过旁边的东西。

### 9. 点击坐标一律取 `uiautomator dump` 的 bounds

这一轮被坑了不止一次：
- 目测点「横屏」结果点到「竖屏」；
- 目测关「来路角标」结果点歪。
- WebView 节点有时返回 `bounds="[0,0][0,0]"`，得先滚动
  （`input swipe 540 1900 540 700 350` 两次）再 dump 才拿得到真 bounds。

`tools/test/dump-ui.py` 就是干这个的，别再用截图数格子。

### 10. Termux 首次启动会弹通知权限框

真实 bounds `[156,1956][924,2094]`，中心 **(540,2025)**。不点掉的话 `RUN_COMMAND` 前台服务行为会怪。

### 11. `window.confirm` 在 WebView 里没有默认实现

不挂 `onJsConfirm` 就是「点了没反应」，看起来像按钮坏了。UI 里所有破坏性操作改成自绘确认条。

---

## 三、WebView / 引擎

### 12. WebView 用 `file://` 跑 WebGAL 必失败

CORS + `fetch` + ES module 一起崩。所以内置了一个纯 `com.sun.net.httpserver` 的本地服务：
引擎挂 `/`、工程挂 `/game/`、界面挂 `/ui/`、许可挂 `/legal/`，全走 `127.0.0.1` 回环。

### 13. 预览不要整页跳转

第一版点「预览」把整个 WebView 导航到 `/game/`，工作台就没了，用户以为「一进去就是游戏」。
现在改成中间栏 `#stageFrame` 内嵌 iframe，`apiStage(id)` 只挂载 `/game/` 并 `server.restart()`，
返回 `{"ok":1,"url":...}`，由前端写 iframe。工作台始终在。

### 14. WebView 的 CSS 视口比想象的小，20:9 手机会掉进「竖屏」分支

vivo 横屏后 `mAppBounds` 是 `Rect(90,0-2388,1080)`，密度 480 → **视口只有约 766×328 CSS px**。
第一版媒体查询写 `max-width: 820px` 就堆叠成单列，看着像「三栏布局没生效」。
**改成**：三栏在 `≥640px` 就成立，左右栏用
`--rail: clamp(204px, 27vw, 288px)` / `--rail2: clamp(280px, 33vw, 384px)`，
并在 `max-width:900px` 下把右栏表单拆成单列、顶栏砍掉副信息。
桌面无头浏览器按 766×328 渲染验证过。

### 15. `[hidden]` 会被自己的 `display:flex` 顶掉

`.stage__empty` 写了 `display:flex`，`hidden` 属性就压不住了，占位层和 iframe 叠在一起。
加一条 `[hidden] { display: none !important; }` 了事。

### 16. 老 prefs 会把页面搞成白板

旧版把当前 tab 存成 `projects` / `build`，新版页签名换成 `workbench`。升级安装后
`switchTab('projects')` 把所有 panel 的 `is-active` 去掉，又找不到 `#tab-projects`，
结果**顶栏正常、内容整片空白**。修法：`switchTab` 加白名单，不认识的一律回 `workbench`。

> 这个坑很难从截图看出来（看着像 CSS 没加载），最后是 `uiautomator dump` 只 dump 到顶栏才定位到。

---

## 四、PC 构建链路

### 17. `.NET ZipArchive` 重写 zip 会把 `resources.arsc` 压坏

重新压缩后 APK 装不上 / 解析失败。要改 APK 内容时用 `aapt2` 或干脆重打，别用 ZipArchive 原地改。

### 18. aapt2 的 `--version-code` 与清单要一致

工作站自己的版本号统一为内部号 **2601**：`versionCode=2601`、`versionName=1.0.0-beta.2601`。
清单、UI 表单默认值、构建脚本输出名三处必须一起改，漏一处就会出现「装上去显示旧版本」。

---

## 五、许可 / 合规（不是 bug，但一样会翻车）

### 19. 只在「用户须知」里写一句分层许可，等于没写

要落到文件级：
- MPL 文件保留原文件头，修改版继续以 MPL-2.0 提供；
- GPLv3 打包工具（Termux / BuildAPKs）写成「独立进程、命令行调用、不随附不链接」，把聚合体边界写死；
- 自研文件头统一 `SPDX-License-Identifier: MIT`，MIT 文件里不掺 MPL 代码。

`src/assets/legal/` 下四份文件（NOTICE / MPL-2.0 / LICENSE-MIT / DISCLAIMER）是分发时必须带上的；
构建脚本已经把 `DISCLAIMER.txt` 加进「缺了就不许构建」的自检列表。

### 20. 免责声明写成一段话没用，得写成「谁负责什么」

`DISCLAIMER.txt` 13 节，重点是这几条：非官方、许可分层、按现状提供、内容责任归创作者、
分发义务、密钥自负、数据留在本地、发布前自查清单、责任限制。
首启门 + 关于页入口 + 「读完整免责声明」三个入口都能读到同一份原文。

---

## 六、还没解决的（写在这里，别当已修）

- `MANAGE_EXTERNAL_STORAGE` 的 UI 引导路径未在干净设备验证（见第 5 条）。
- `probe.sh` 的 `webview` 字段在真机取不到（`getprop ro.build.version.webview` 为空），显示 `unknown`。
- 低配模式剔掉三套中文字体（36.1 MB）后靠系统字体回退，个别艺术字排版在低端机上会偏。
- 端上构建只在 vivo V2219A + Termux 1002 上跑通过；别的 ROM 的前台冻结策略可能更狠。

---

## 七、第三批改造（新建项目 / 工作场景 / 内置工具箱）踩到的坑

### 21. WebView 默认不实现 alert / confirm / prompt

`android.webkit.WebView` 只挂 `new WebChromeClient()` 时，`window.confirm` 直接返回 false、
`window.prompt` 直接返回 null、`window.alert` 什么都不显示。表现是「点了删除没反应」「新建场景输不进名字」。
必须自己实现 `onJsAlert / onJsConfirm / onJsPrompt`，用 `AlertDialog` + `EditText` 兜住。
真机实测：补上之后「工作场景 → 删除场景」的确认框、「新建场景」的输入框都能正常弹。

### 22. `-source 8` + `android.jar` 没有 LambdaMetafactory，端上源码禁用 lambda

```
找不到符号: 方法 metafactory(...)
位置: 接口 LambdaMetafactory
```

`android.jar` 的 bootclasspath 里没有 `java.lang.invoke.LambdaMetafactory`，只要写 `(d, w) -> ...`
就编译失败，全部改匿名内部类（`DialogInterface.OnClickListener`）才行。
这条对端上构建同样生效：构建工程的模板源码也别用 lambda / 方法引用。

### 23. 弹窗按钮被输入法盖住 → 关键按钮必须放头部

新建工程弹窗第一版是「标题 + 输入框 + 底部按钮」，真机上点输入框弹键盘，底部「创建」被顶到屏幕外，摸不到。
改法：弹窗顶部再放一份 `取消 / 创建`（`#newCancel2` / `#newOk2`），底部保留一份；
弹窗从垂直居中改成 `align-items:flex-start; padding-top:6vh`；输入框绑 `keydown Enter → 创建`。

### 24. `生成构建工程` 不刷新 kit，改了脚本端上还跑旧的

`deployKit()` 原来只在「运行自检 / 打开工具箱」时调用，`apiPrepareBuild` 直接读 kit 里的旧脚本。
升级安装后的现象就是：代码明明改了、构建日志还是老样子（这里连踩两轮）。
修法：`apiPrepareBuild` 里先 `deployKit()` 再拼装，每次准备都把 `assets/kit/*` 重刷到共享存储。

### 25. `tools.sh` 不在构建目录里，`$HERE/tools.sh` 必然找不到

`BuildKit.prepare` 只把 `build_apk.sh` 和 `README-build.txt` 拷进 `build/<job>/`，
`tools.sh` 留在 `WebGALStation/kit/`。脚本里写 `$HERE/tools.sh` 永远不成立，于是每次都打「还没钉路径」。
修法：候选路径写 `$HERE/tools.sh`、`$HERE/../../kit/tools.sh`、`$HERE/../kit/tools.sh` 三个。
真机日志（`build/MyGame-20261114-000130/build.log`）：
`工具链  首次构建，先跑 tools.sh 钉路径（.../build/MyGame-20261114-000130/../../kit/tools.sh）`
→ `已写入 /data/data/com.termux/files/home/.webgal-station/toolchain.env` → 14s 出包。

### 26. 真机装包要手动点「继续安装」，`adb install` 会一直挂着

vivo 的 `PackageInterceptActivity` 会拦一道，`adb install` 不点确认就一直等（看起来像任务卡死）。
可靠流程：后台 `Start-Process adb install ...` → 截图看到「继续安装」→ `input tap 540 2210`
（1080×2388 竖屏下该按钮中心）→ 再用 `dumpsys package ... | grep lastUpdateTime` 确认真换了版本。
只看 `versionCode` 没用：同版本号升级看不出来。

### 27. 坐标一律取 `uiautomator dump` 的 `bounds`

WebView 里按钮的 `bounds` 是准的（`btnNew [114,381][294,453]`），靠截图目测会差几十像素、点空。
注意有些节点会给成 `[x1,y1][x2,y1]`（高度 0），取中心点得自己算 `(x1+x2)/2, (y1+y2)/2`。

### 28. 「和 WebGAL 一模一样的工作场景」是产品活，不是内嵌一个 HTML

Terre 编辑器前端（`D:\WebGAL\release\public\`，`index-01972026.js` 12.34 MB）是纯 Web 产物，
走本地 REST（`axios.post("/api/manageGame/...")`，共 45 条 NestJS 风格路由），不依赖 Electron IPC。
要做到"一模一样"，得把这 45 个端点按请求/响应体逐个在 Java 里复刻，那是另一个工程量，这一轮收不掉。
当前落地的是**同构工作场景**：场景列表 / 脚本直读直写（`game/scene/*.txt`）/ 资源目录一览 /
指令快捷插入 / 一键预览，信息架构对齐 Terre 的「工程 → 场景 → 文本」主线。
真·Terre 前端的接法记在 `docs/ARCHITECTURE.md`（挂 `/editor/` + `/api/*` 桥），后续按端点分批补。

### 29. 截图坐标不是现场坐标：面板一滚，你点的就是输入框

打包台是 `overflow:auto` 的滚动容器，滚到「生成构建工程」那一帧截图、过几秒再点，
中间只要有一次 `am start` / 重装 / 手动滚动，内容就回顶了。于是 `(1550,806)` 落进了 `#fPkg`，
界面上还多出一个「?」——按了七八次都"没反应"，其实按的是输入框。
结论：**别拿旧截图算坐标**。要么点之前立刻截一张现算，要么走 CDP 的 `document.elementFromPoint`。

### 30. 落点自证：`setWebContentsDebuggingEnabled(true)` + `adb forward` + `/json`

`StationActivity` 里开一行 `WebView.setWebContentsDebuggingEnabled(true)`，然后：

```powershell
adb shell cat /proc/net/unix | grep webview_devtools_remote   # 拿到 socket 名
adb forward tcp:9222 localabstract:webview_devtools_remote_27874
(Invoke-WebRequest http://127.0.0.1:9222/json).Content        # 里面带 screenX/screenY/width/height
```

`/json` 的 `description` 会告诉你 WebView 在屏幕上只占 `2298×984 @(90,96)`（横屏有刘海留白），
配合 `innerWidth/innerHeight`（本机 `766×328`、`dpr=3`）就能把物理像素换成 CSS 像素，
再 `elementFromPoint` 问出落点。`tools/test/probe-hit.js`、`cdp-eval.js` 就是这套的落地脚本。
顺带说：这条路也能直接 `document.querySelector('#btnPrepare').click()`，做逻辑回归比点屏幕稳。

### 31. `am force-stop com.termux` 之后，RUN_COMMAND 永远发不到

强停把包打进 stopped state，隐式 `Intent` 一律被丢。表现就是状态卡在
`{"state":"prepared"}`、`runner.log` 根本不生成、界面停在「等待 Termux 回写日志」。
我在装包前为了不让 Termux 抢焦点强停过它，然后就踩了这一脚。
修法：`monkey -p com.termux -c android.intent.category.LAUNCHER 1` 拉一次前台（或让用户点「打开 Termux」），
再重发构建，14s 出包。UI 侧补了 25 秒看门狗：没日志就把人往「打开 Termux」按钮上推。

### 32. vivo 的「重新安装」= 卸载重装：数据、权限、授权全清

`adb install -r` 碰到同名同版本时，拦截页给的是「直接打开 / 重新安装 / 取消」，
点「重新安装」是真删数据：免责门重弹、prefs 归零、`MANAGE_EXTERNAL_STORAGE` 回到默认、
Termux 的「允许执行命令」也要再点一次。看到"首启又弹一遍"别怀疑代码，先怀疑这个按钮。

### 33. 权限能不能给，以 `appops` 为准，别信自己手点的开关

「管理所有文件」页的开关在 1080 宽渲染下大约 `(977,476)`，但 `input tap 977 476` 打不中
（实际要 `appops set com.zhixitai.vn MANAGE_EXTERNAL_STORAGE allow` 才稳）。
点完必须核：`appops get com.zhixitai.vn MANAGE_EXTERNAL_STORAGE` 要能回 `allow`；
回 `Default mode: default` 就是没给上。`deny` 可以脚本化收回，调试设备干净授权路径也走这条。

### 34. 首启体检只在「同意免责」回调里调，老用户永远看不见

`maybeShowOnboard()` 一开始只挂在免责门 accept 后面。已经同意过的用户进 `init` 时门是隐藏的，
体检一次都不会跑（真机第一版就是这样，用户骂"说好的首启提示呢"）。
现在 `init` 里判 `#gate` 是否已隐藏，隐藏也补一次延迟调用；并加 `obDone/obSkipped` 持久化，授权完不再骚扰。

### 35. 首启「③ 工具链落地」几乎一开始就是绿的，不是显示错

`StationActivity` 的 `onCreate` 会直接 `deployKit()`（第 213 行那条），所以体检弹层里
脚本目录往往已经躺好了。别以为体检没生效——它真读过盘。

### 36. 换布局会把舞台挤成一条缝，得自己回收宽度

把手放到 150–720 之后，玩家把两条栏都拉宽再切「居左」，中间舞台只剩十几像素，字竖着排。
修法是 `fitWidths()`：舞台至少留 26% 宽（下限 180 CSS px），留不出来就按比例压两条栏，
换布局后 60ms 回收一次，转屏 `resize` 再回收一次。能塞下就一分不动，玩家拖多宽都算数。

### 37. `-source 8` 下别指望 `String.replaceFirst` 之外的花活

版本名去尾巴那步我用 `replaceFirst("(2601)(\\.\\d{1,4})+$", "$1")` 图省事，
在 `-source 8` / android.jar 下没问题（正则走 `java.util.regex`），但**别用 lambda / `computeIfAbsent`**——
之前为了这个把整份代码改成匿名内部类，别回头又踩。

### 38. 只抬 versionCode 不抬 versionName 等于白改

vivo / 小米的安装拦截页读的是 `versionName`，不是 `versionCode`。早先 `apiPrepareBuild` 只把
数字 +1，拦截页照样显示「已安装相同版本 1.0.0-beta.2601」，用户以为没生效。
现在两条一起抬：先用 `replaceFirst` 把上一轮攒的 `.N` 尾巴收干净，再按基数换算 build 号
（`code >= 26010000 ? code % 10000 : code - 2600`）追加，得到 `1.0.0-beta.2601.4`。
中途跌过一版：写成 `opt.versionCode % 10000`，游戏 code=2603 得出 `1.0.0-beta.2601.2603`。

### 39. 轮询会把 `S.build.state` 改回去，看门狗不能拿它当条件

第一版看门狗写 `if (S.build.state !== 'running') return;`，结果 1.2 秒一次的轮询把 `running`
改写成 `prepared`，25 秒后回调一进来就 return，永远不触发（真机 30 秒后提示还是默认文案）。
现在只认「有没有真日志」+ `S.build.stalled` 标志，`showResult()` 里把 stalled 放在最高优先级分支，
`pollBuild` 收到 success/failed 时清掉，免得上一轮的「卡住了」贴在新一次构建上。

### 40. Termux 被 force-stop 过时，RUN_COMMAND 会把命令当 URL 塞进「输入网址」

真机截图实证：点「开始构建」后，Termux 前台弹的是 `Termux:Floating` 的「输入网址」框，里面躺着
`bash /storage/emulated/0/Download/WebGALStation/bu…` —— Intent 里的命令串被当 data 交给了 activity，
构建自然一动不动，但界面还显示「已交给 Termux」。
修法在 `Termux.runScript`：`resolveService` 判一次服务在不在，再查 `ApplicationInfo.FLAG_STOPPED`，
任一不过就直接返回 false，走 manual 分支把这行命令复制给用户，并提示「先点打开 Termux」。
真机复测：强停后点构建 → toast 说出实话；`monkey -p com.termux -c …LAUNCHER 1` 唤醒后再点 →
14 秒出包（`{"state":"success","size":"6.4M","seconds":14}`）。

### 41. 引导这活儿，三个地方能一次性翻车

1. **卡片盖住洞**。第一版把卡片一律算成「目标下方 14px」，工程库是整列，卡片正好压在要指的那张卡上。
   改成先算右边有没有 `cw+14` 的空间，再算下、上、左，四条都不成立才压在目标身上。
2. **`sel: '.dock'` 是个不存在的元素**。打包台的容器叫 `#tab-workbench .rail--right`，
   目标找不到就走「居中兜底」，卡片飘在屏幕中间、洞是 0×0——看起来像「引导没生效」。
   写完引导一定要拿 `document.querySelector` 逐条验，别信自己记的 class 名。
3. **引导工程别整份复制素材**。一开始把 `demo-game` 整个拷成 `tutorial-game`，APK 从 6.5 MB 涨到 12.2 MB
   （同一套 bg/figure/bgm 存了两份）。现在 `tutorial-game` 只带 `config.txt` + 3 个场景 txt，
   注入时先拷 `demo-game` 铺素材，再覆盖脚本，最后把 demo 那 4 个用不上的场景删掉（不然工程里躺着
   `pipeline.txt`、`end_scene.txt`，用户点开是别的故事）。

真机结果：首启弹「1 / 5 · 工程库」→ 5 步切完，`prefs.guideDone` 落盘，重启不再弹；
引导工程卡片标「引导」、场景 3、预览能进 `PRESS THE SCREEN TO START`；删了还能「重建新手引导」叫回来。

### 42. 版本号这件事，名字里挂代际就等于白升级

用户装到第 15 次，屏幕上还是 `1.0.0-beta.2601.15` —— 数字在动，但前缀一动不动，看上去像根本没迭代。
原来那套 `1.0.0-beta.2601.N` 把「年 / 代际 / 主版本 / 后缀 / build」全塞进名字里，一年一代际就等于一年不变脸。
现在拆开：**代际 2618 只进 `versionCode`，名字只留 `1.1.5-beta.17`**，批次到了就抬一次主版本（1.1.5 → 1.1.6）。

这个规则有 **三份实现**，少改一份就会出现「名字和 code 各说各话」：

1. `tools/pc/version.ps1` —— 生成 `V.java` 与 `AndroidManifest.xml`
2. `tools/pc/build-station.ps1` —— 自己又算一遍 `$versionName`，用来拼 APK 文件名（第一版漏了这里，产物叫
   `1.1.5-beta.2618.16`，代际又漏回名字里）
3. `StationActivity.apiPrepareBuild` —— 端上给**游戏包**算版本号，原来硬编码 `2601`、默认名 `1.0.0-beta.2601`、
   阈值 `26010000`；换成 `V.BASE / V.STAGE / 2618` 之后，游戏包名字才跟工作站同一个口径

顺带一句：端上 `apiEnv` 必须把 `versionBase`（`1.1.5-beta`）吐给前端，否则打包台表单会拿前端写死的旧字符串
当默认值，又变回 `1.0.0-beta.2601`。

### 43. 评审里挑出来的「静默成功」：写盘失败也算成功，桥发事件没人接

这轮把 A0（体验视角）和 A1（代码视角）两份评审当清单，一条条回填。最要命的不是架构丑，是**错了还报成功**：

1. `apiWriteText` 不看 `Util.writeText` 的布尔值 —— 存储满了、权限被收回，稿子没落盘，界面照样「保存成功」。现在判布尔，失败回 `ok:0` + 人话原因。
2. `apiReadText` 读不出来时回 `ok:1, text:null` —— 前端把它当空剧本渲染。现在「文件不存在」和「IO 读失败」分开报。
3. `apiSavePrefs` 超过 4096 字符直接不写、不提示。上限提到 8 KB，超了回错误让用户知道。
4. Java 发 `termuxInstall` 事件，前端 switch 里根本没这个 case —— 一键装 Termux 的成败用户完全看不到。前端补 case（本条由 UI 侧改）。
5. `Store.load()` 解析失败就清空内存，磁盘上工程还在、用户以为全丢了。现在先把坏串备份到 `projects_backup`，再从 `projects/` 目录扫一遍重建，界面能显示「已从目录恢复」。
6. `buildId` / 安装 APK 路径以前直接拼进 `new File(...)` —— 预览页脚本能传 `../../`。现在 `resolveBuildDir()` 做 canonical 前缀校验，安装入口只认构建目录下的产物。
7. 构建脚本 `write_status` 不转义引号/反斜杠，路径里带个 `"` 就把 status.json 写成非法 JSON，失败理由跟着一起消失。加了 `json_escape`。
8. 连点「开始构建」：Java 侧 `buildRunning` 闸 + 脚本侧 `flock`，两道都有才叫防住。
9. `apksigner verify` 失败只记日志、照样标 success。现在写进 `warn` 字段，界面能在成功卡上标「签名校验没过，可能装不上」。

顺带一提：这几条里 1、2、4 都属于「用户以为成了其实没成」，比崩掉更难查——崩了至少知道崩了。
### 44. `grid-area` 没带父级前缀，编辑器页面被工作台的布局规则撕开

这轮改 UI 时，`style.css` 里这几条本来只该管工作台三栏：

```
.rail--left { grid-area: railLeft; }
.stage { grid-area: stage; }
.rail--right { grid-area: dock; }
#splitter { grid-area: splitR; }
```

但编辑器页 `.panel--ed` 里也有 `.rail--left`（就是左侧场景列表那一栏），它自己只定义了两列 `grid-template-columns`、**没有** `grid-template-areas`。给一个不存在名字的 `grid-area`，CSS Grid 会当成隐式命名区域，另开轨道——结果真机 766×328 下编辑页被排成「一条 170px 的窄柱 + 右边一整块」，`textarea` 被压成一条缝，脚本只能看到两三行。

修法：把选择器加上父级作用域。

```
.panel--wb .rail--left { grid-area: railLeft; }
.panel--wb .stage     { grid-area: stage; }
.panel--wb .rail--right { grid-area: dock; }
```

教训：**同一个 class 被两种网格共用时，命名区域的选择器必须带父级**。改完最好真机看一眼，缩略图分辨率下这种错位很容易被当成「样式丑一点」划过去。
### 45. 锁文件放在 /storage 上，flock 永远抢不到——第一次构建就报「另一个构建还在跑」

给构建脚本加防重入锁，代码是这样写的：

```
LOCK="$HERE/.build.lock"     # $HERE 在 /storage/emulated/0/Download/...
exec 9>"$LOCK" 2>/dev/null || true
if ! flock -n 9 2>/dev/null; then 失败退出; fi
```

在 PC 上没错，在手机上是错的：`/storage` 是 FUSE（sdcardfs 那一层），**不支持 POSIX 文件锁**。`exec 9>"$LOCK"` 打不开 fd，`flock` 直接报错，被当成「锁被占用」，于是第一次点「开始构建」就红字「另一个构建还在跑，等它结束再来」，日志里连一行构建步骤都没有。vivo Y77 上就是这么翻的。

改法：锁和 pid 文件放 Termux 私有目录（ext4，真支持锁），FUSE 上只留产物和日志。

```
PIDFILE="$HERE/.build.pid"
LOCKDIR="$HOME/.webgal-station/locks"
mkdir -p "$LOCKDIR" 2>/dev/null || LOCKDIR="$TMPROOT"
LOCK="$LOCKDIR/$(basename "$HERE").lock"
if command -v flock >/dev/null 2>&1 && exec 9>"$LOCK" 2>/dev/null; then
  flock -n 9 || 失败退出
else
  走 pid 文件兜底
fi
```

顺手说明：`exec 9>... || true` 这种写法本身也有问题——fd 没开成功时后面的 `flock -n 9` 必然失败，而失败信息看起来像「有人抢着用」。以后凡是「开 fd + 锁」的组合，都要把 fd 打开失败单独当成「走兜底」处理，不要让它冒充「锁被占」。

改完在 Y77 上重跑：14 秒出包，`status.json` 是 `success`，产物 `新手引导_·_从这里开始-com_example_mygame.apk` 6.4 MB，签名校验通过，`job.env` 已删。
### 46. 端上打出来的游戏包，versionName 还是上一代的号

第一次在 Y77 上把包打出来、装上、看版本，`versionName` 是 **1.1.5-beta.2**——工作站本身已经是 1.1.6-beta.23。查 `job.env` 才看到 `VERSION_NAME='1.1.5-beta'`。

两处叠加：

1. 前端 `fVersionName` 的兜底值是写死的 `'1.1.5'`（A0 那轮把它改成 `S.env.versionBase || '1.1.5'`，兜底没跟着换代）；
2. Java 的重命名条件写成 `if (opt.versionCode > 2601)`，第一个游戏包的 code 正好是 2601，条件不成立，名字原样打包。

改法：

- 前端兜底改成当前代数（`1.1.6`），这属于「每次换代都要一起搜一遍」的常量；
- Java 侧不再拿 code 大小当开关，名字一律以 `V.BASE` 为准，且前端传进来的一律要被 `startsWith(V.BASE)` 校验，对不上就覆盖成 `V.BASE + "-" + V.STAGE`：

```
String verBase = opt.versionName == null ? "" : opt.versionName.trim();
verBase = verBase.replaceFirst("^(\\d+\\.\\d+\\.\\d+)(-[a-zA-Z]+)?(\\.[0-9]{1,4})+$", "$1$2");
if (!verBase.matches("^[0-9]+\\.\\d+\\.\\d+.*") || !verBase.startsWith(V.BASE)) {
    verBase = V.BASE + "-" + V.STAGE;
}
opt.versionName = verBase + "." + buildNo;
```

还有一个变量名坑：这段代码和后面的 `File base = BuildKit.prepare(...)` 在同一个方法作用域里，新加的 `String base` 会撞名，`javac` 报「已在 run() 中定义了变量 base」，改叫 `verBase` 才过。

改完再打：`versionCode=2604 versionName=1.1.6-beta.4`，装机、启动、中文菜单、能进标题，全对。

### 47. 打出来的游戏，标题菜单是德语

新装、清过数据、第一次打开，菜单是 `STARTEN / WEITERLESEN / OPTIONEN / LADEN / EXTRA / BEENDEN`。

不是我们配错了语言，是 WebGAL 4.6.4 的行为：引擎只读 `localStorage.lang` 里的**数字枚举**（`zhCn=0 / en=1 / jp=2 / fr=3 / de=4 / zhTw=5 / ptBr=6 / ko=7`），没有这个键时 i18n 落到 `de`。桌面浏览器里玩家点过一次语言选择就没事，但 APK 首启没人去点。

改法：`src/assets/engine/index.html` 里在引擎脚本之前补一句，只在键缺失时写 `0`：

```html
<script>
  try {
    if (window.localStorage.getItem('lang') === null) {
      window.localStorage.setItem('lang', '0');
    }
  } catch (e) {}
</script>
```

不能无条件写：玩家换过语言后键已存在，再覆盖就是抢用户设置。这是对上游引擎的唯一一处改动，按 MPL-2.0 在 README 第九节明示。
## 48. 真机点按坐标 ≠ 截图目测坐标：一律信 a11y 的 bounds

Y77 横屏（ROTATION_90）下，截图 2388×1080 但 WebView 根 FrameLayout 从 x=90 起，a11y 的 `bounds` 才是 `adb shell input tap` 认的坐标。按截图目测中点去点，偏移能有 100~200px，症状是「点了没反应」或「点下一步却退到上一步」。

做法：`uiautomator dump` 抓 `text="下一步"` 那条的 `bounds`，取中点再 tap；浮层（启动页/免责/浮窗）有时整棵不进 a11y 树，此时先用能进树的同级按钮校准一次比例，别硬猜。

## 49. vivo 的 uiautomator dump 会间歇性失败

同一条命令，前一次 46KB 正常，后一次只有 1.7~2.7KB（stderr 里是 `vivo.adapter.cpuinfo` 读 `/sys/board_info/user_cpu_freq` 被拒）。这不是页面没渲染，是 dump 服务自己崩了。

做法：失败就隔 2s 重 dump，最多十余次；仍失败就换招（截图目测 + 先校准比例），别把「dump 里没有」当成「界面上没有」。

## 50. PowerShell 的 `.Replace` 用 `r`n` 去匹配 LF 文件＝静默失败

app.js 是 LF，写补丁时用带 CRLF 的字符串去 `Replace`，一次都没命中，`init()` 里 `bindBoot/bindRailCollapse/startAutoSave` 三行根本没进文件——启动页成了挡死全部点击的死遮罩，还查了半天 JS 逻辑。

做法：改完必须 `Select-String` 回读一遍确认行号出现；匹配串只取单行锚点（如 `"  bindAsk();"`）。

## 51. 首启免责卡片比 20:9 视口高，按钮在屏幕外

`#gate .gate__box` 内容长，1080p 横屏一屏放不下，「我已阅读并同意」被挤到视口外，不看就点不到。真机上是先往上滑一下卡片才露出来。

待办：给 `.gate__box` 加 `max-height:86vh; overflow:auto`，并把按钮做成 sticky 底部，别让新用户第一屏就卡死。

## 52. 免责关掉后链条断了：不弹启动页、也不弹引导

`bindGate()` 的 accept 只 `setTimeout(maybeShowOnboard, 80)`；体检三样都齐时 `maybeShowOnboard` 直接 return，后面就没人接「启动页 → 引导」了。已补 `afterGate()`：体检需要就停在体检，否则按 `bootSkip` 走启动页或直接进引导。

## 53. 教学视频素材要跟版本对齐

第 2 期早期用的是 beta.24/25 的引导截图，那会儿顶栏还有「不会就问」标签、第 8 步还是整页问答。重做时把第 1~3 步换成 beta.30 实拍，第 8 步换成浮球浮窗实拍；老图只保留 4~7 步（这几步 UI 没变）。写教程前先核对截图里的版本号，别把旧 UI 当现在讲。
---

## #54 TTS 同一段文本，两次生成差 8 秒

	ext_to_audio_plus 不传 duration 时语速是漂的：同一段旁白第一次 14.6s、第二次 22.4s，
拼出来的视频要么话赶话、要么空半分钟。控制语速只能靠 duration（1–120s），
传 26 就精确出 26s。要「曼波讲故事」那种慢速，就按目标字数算好时长再传，
别指望提示词里写「慢一点」——它不听话。

## #55 PowerShell 里 `  ` 会把文件名吃掉

"ull.wav" 中的 `  ` 被解析成换页符，产出  1ull.wav，ffmpeg 报文件不存在。
凡是拼文件名，一律 "final.wav"，别在变量后面直接跟反引号转义。

## #56 教程里用错截图，比没图更糟

beta.25 时代的「不会就问」整页 UI（sk-*.png）和现行浮窗 UI 不是一个东西，
其中 sk-fill.png、sk-test1.png 其实是 vivo 词库下载页——放进去等于教错。
换 UI 就得重截：28-dock-open.png（浮窗+配置+「收」球）、28-now2.png、32-brand.png。

## #57 给镜头批量换图，数组长度对不上就出空 img

or(=0; -lt .Count;++){ [+2].img=[] } 里如果 $m 比镜头少，
越界那次赋 $null，JSON 里就是空字符串，合成时报 uild\. 不存在（路径拼成了目录）。
换图前后各查一遍 shots | ? { -not .img }，数量对不上就停。

## #58 -NoBump 只补素材，别顺手升版本

改免责、改文案、换截图这类不进行为的东西，用 uild-station.ps1 -LiteEngine -NoBump
冻结版本号重打；否则版本号一路涨，装机时你也分不清哪个包对应哪次改动。
## #59 剪映「曼波讲故事」不是可下载的模型

它是剪映专业版「文本朗读 → 解说」里的内置音色。GitHub/HuggingFace 上的「曼波」是社区近似货（Fish Audio 曼波、NiceVoice、Skywork Mambo TTS≈Edge TTS 小艺+8%音高），音色对不上。要拿原声只有两条：剪映里生成，或去缓存捡 wav。

## #60 剪映朗读音频的隐藏缓存

生成后不导出也能拿到原声：
`C:\Users\<用户>\AppData\Local\JianyingPro\User Data\Projects\com.lveditor.draft\<草稿名>\textReading\*.wav`
省掉「导出音频」那一步，也避开导出面板的会员拦截。

## #61 桌面拥挤时不要盲点 GUI

剪映 11.39-ALPHA 在主桌面被积分包/AI Ultra/Seedance 弹窗反复覆盖，且同屏有用户正在跑的游戏窗口；`Alt+Tab` 会切入游戏（实测切到「来电：爸，妈」画面），alt+tab 循环无法保证落到目标窗口。要么等桌面干净，要么走「生成文本 + 脚本重拼」的路。

## #62 atempo 目标：170 字/分

v1 原速太快、v2（atempo 0.88≈150 字/分）太慢。中间值取 170 字/分，`make-tutorial-videos-v3.ps1` 按每段字数自动算 atempo，限幅 0.85–1.12。
## #63 「点了没反应」先怀疑自己的坐标

phenomenon：环境页按钮（装本机 Termux / 复制安装命令 / 释放离线工具包）点了没 toast、没状态变化，看着像透明层拦截。
真相：凭截图目测 y 坐标普遍偏高 50–60 px，落在按钮上方留白；改用 uiautomator dump 的 bounds 取中心后一次命中（例：释放按钮 bounds=[159,327][462,438]，点 (310,382) 立刻弹 toast）。
另：uiautomator dump 对离屏节点返回 [0,0][0,0]，脚本若取中心会点左上角，必须跳过零尺寸节点、先滚到可见。
教训：说「按钮坏了」之前，先用 dump 坐标复测一次。
