# 织戏台 · ZhiXiTai VN

> 手机上写视觉小说，手机上打出 APK。一个包、断网可用、素材自己导入、工程不上传。

**织戏台（ZhiXiTai VN）** 把 WebGAL 引擎、剧本编辑器、终端和一套 Android 打包工具链
塞进同一台手机。装一个 APK，里面带着 Termux、OpenJDK 21、aapt2、aapt/zipalign、d8、
apksigner、zip/unzip 和 `android.jar`；释放一次，断网也能把工程打成可安装的 APK。

- 包名：`com.zhixitai.vn`（启动 Activity：`.StationActivity`）
- 当前验收版本：`1.1.7-beta.50`（versionCode `26180050`）
- 完全体 APK：294.56 MB（308,864,335 B，含 WebGAL 4.6.4 引擎、内置示例工程、Termux 与 54 个离线 deb）
- minSdk 21 / targetSdk 34
- 引擎：WebGAL 4.6.4（MPL-2.0，非官方构建）

> 这是第三方工具，与 OpenWebGAL、Termux、Google 均无隶属、无背书关系。
> 「WebGAL」是上游项目的名称，本项目的产品名是「织戏台 / ZhiXiTai VN」。

## 现在能干什么

- **工作台**：工程库、场景列表、预览；左右两栏可收成细条，打包台可拖宽/居左/居中/居右
- **素材导入（重点）**：编辑器里选分类（背景/立绘/音乐/语音/视频/动画/其他），一次最多
  导入 80 个文件，落到工程的 `game/<分类>/`；重名自动加 `-2`，MIME 缺扩展名会自动补
- **素材重命名（新）**：每个素材 chip 旁边的 `✎` 打开原生改名框，只改文件名、保留扩展名；
  点素材文件名仍然复制名字，两个动作分开
- **参数问号（新）**：打包台按钮行最左的 `?` 打开逐项说明，解释应用名、包名、版本名/号、
  方向、图标、来路角标、签名方式、口令、生成构建工程、开始构建
- **产物路径（新）**：构建成功卡直接显示完整 APK 绝对路径，并给「复制 APK 路径」按钮；
  toast 同步提示，文件管理器路径写死为 `下载/WebGALStation/build/<构建目录>/out/`
- **工程导入**：支持「导入文件夹」和「导入 ZIP」；ZIP 走应用内扫描 `/sdcard/Download`
  与构建根，绕开国产 ROM 文件选择器进不去真实下载目录的问题
- **剧本编辑**：直接改 `.txt` 剧本，带语法提示与错误定位
- **八步引导**：从新建工程到出包，首启自动开，顶栏「引导」随时重看
- **不会就问**：右下浮窗问答，可自填 API Key，不填也能查本地手册
- **环境页**：权限、Termux、离线工具链、自检，一页看完
- **端上打包**：`aapt2 → javac → d8 → zipalign → apksigner` 全在设备本地跑
- **离线优先**：`setup.sh` 检测到 `offline/install_offline.sh` 就直接跑离线 deb，
  完全不碰 `pkg` 网络源，不需要 VPN

## 真机验收（2026-11-14）

设备：vivo V2219A / Android 13 / API 33 / 横屏 2388×1080。

| 项目 | 结果 |
|---|---|
| 素材导入 | 选 `p3.png` → toast「已导入 1 个素材到 background」；落盘 `projects/tutorial/game/background/p3.png`（115,711 B） |
| 素材重命名 | 在导入工程 `p1794633280232/tutorial/game/background` 点 `✎`，输入 `bg2.webp`，确认后落盘；扩展名未变，随后用 adb 把 `bg2.webp` 改回 `bg.webp`，工程仍可读 |
| 参数问号 | 点打包台 `?` 弹 `#paramHelpDlg`，逐项说明可滚动阅读；点遮罩可关闭 |
| 产物路径 | beta.50 端上构建 `com.zhixitai.demo50`，成功卡显示完整 APK 路径；点「复制 APK 路径」出 toast，路径指向 `.../build/p1794633280232-20261114-141746-822/out/新手引导_·_从这里开始-com_zhixitai_demo50.apk` |
| ZIP 工程导入 | 应用内扫描列出 `zz-tutorial.zip 5 MB`，点选后工程数 2→3；新工程 3 场景 / 37 文件 / 5.7 MB，含 `p3.png` |
| 离线包完整性 | 54 个 deb 全量释放；`install_offline.sh` 跑完 `✔ 离线 deb 54 个`；`command -v zip unzip` 返回真实路径 |
| 端上出包 | 打包台选工程、填包名 `com.zhixitai.demo` → `生成构建工程` → `开始构建`；Termux 19 秒产出 28 MB APK |
| 产物安装 | `adb install` 通过，启动显示 WebGAL 标题页，点屏进入主菜单与场景，素材正常渲染 |
| 断网 | 全程未挂 VPN、未执行 `pkg install`；离线 deb 来自 APK 内置资产 |

## 玩家怎么用（完全体）

1. 安装 `织戏台-ZhiXiTaiVN-*-full.apk`，按系统提示允许「安装未知应用」
2. 首启给「所有文件访问」权限（用于读写 `/sdcard/Download/WebGALStation`）
3. 环境页点 **「一键离线配齐（不联网）」**：APK 会把约 267 MB 的 Termux 安装包、
   54 个 deb、`android.jar`、安装脚本释放到共享存储；已装过就直接复用
4. 按提示打开 Termux，**长按屏幕 → Paste → 回车**，跑一次
   `bash /sdcard/Download/WebGALStation/offline/install_offline.sh`
   - 这是 Termux 的安全模型决定的：外部应用不能直接在 Termux 里执行命令，必须由玩家
     粘贴一次。**不需要联网、不需要 `pkg`、不需要 VPN，也不用等软件源。**
   - 脚本会自动申请 Termux 存储权限，最多等 30 秒；装完打印 ✔ 清单
5. 回环境页点「运行自检」，全绿即可打包；工作台选工程 → 打包台填包名 → 点 `?` 看参数说明 →
   `生成构建工程` → `开始构建`
   - 成功卡会显示完整 APK 路径；点「复制 APK 路径」直接进剪贴板
   - 文件管理器找包：`下载/WebGALStation/build/<这次构建的目录>/out/<你的应用名>-<包名>.apk`
   - 想改名素材：编辑器素材 chip 旁点 `✎`，只改文件名，扩展名自动保留

完全体自带 Termux 与 54 个离线 deb（含 `aapt`/`zipalign`、`zip`/`unzip`）。
国产 ROM 会冻结后台 Termux，构建时别把它划掉；被冻结就把 Termux 放前台再点一次。

## 从源码构建

### 手机端（主路径）

完全体 APK 自带工具链，玩家/开发者都不需要 PC。仓库里的 `src/assets/kit/build_apk.sh`
是 Termux 侧打包脚本，`tools/pc/` 只是作者自用的 PC 构建脚本。

### PC 端（可选，未做跨平台适配）

`docs/BUILD.md` 记录作者本机的 Windows PowerShell 流程：JDK 21、Android SDK
（build-tools 36.x、platform android-34）。**PC 脚本没有适配 macOS/Linux，也不承诺
Windows 以外的环境可用**；Android 产物本身与宿主系统无关。

```powershell
# 完全体（含离线 Termux + deb + android.jar，约 294 MB）
tools\pc\build-station.ps1 -FullOffline

# 低配（不含离线包/字体，体积小）
tools\pc\build-station.ps1 -LiteEngine
```

## 目录

```
src/
  java/com/zhixitai/vn/   原生层：Activity、Api 桥、本地 HTTP 服务、Termux 集成、素材/ZIP 导入
  assets/ui/              工作站界面（HTML/CSS/JS，无框架）
  assets/engine/          WebGAL 4.6.4 引擎（MPL-2.0，见 LICENSES/）
  assets/kit/             Termux 侧脚本：setup.sh / build_apk.sh / probe.sh 等
  assets/templates/       新建工程模板
  res/                    图标与资源
templates/                工程模板源
docs/                     架构、构建、踩坑、许可审计
LICENSES/                 各许可证全文与第三方清单
tools/                    构建与打包脚本（作者自用，MIT）
```

## 许可证（重要）

| 层 | 许可证 | 位置 |
|---|---|---|
| 本项目原创代码 | **MIT** | `src/java/com/zhixitai/vn/**`、`src/assets/ui/**`、`src/assets/kit/**`、`tools/**`、`docs/**` |
| WebGAL 引擎 | **MPL-2.0** | `src/assets/engine/**` |
| 完全体随附的 Termux | **GPL-3.0** | APK 资产 `assets/offline/termux.apk` |
| 完全体随附的 54 个 deb | 各自许可证（GPL/LGPL/Apache/MIT/BSD…） | APK 资产 `assets/offline/debs/**` |
| Android 平台 `android.jar` | Apache-2.0（AOSP）+ SDK 条款 | APK 资产 `assets/offline/android.jar`；**仓库不提交** |
| 引擎内置字体 | OFL 1.1（两款）+ OPPO 免费商用条款（一款） | `LICENSES/FONTS.md`、`LICENSES/OFL-1.1.txt`、`LICENSES/OPPO-Sans.md` |

- MIT 只覆盖原创部分，**不会**因为同包分发就传染给 WebGAL 引擎或 Termux。
- MPL-2.0 是文件级 copyleft：改过引擎文件就必须继续以 MPL-2.0 提供那些文件。
- WebGAL 上游还有**额外条款**：用 WebGAL 开发的作品在发布时须保留 WebGAL 相关的
  版权声明，游戏本身的版权归你。工作站与生成的 APK 保留引擎标题页与 WebGAL 标识，
  不抹除上游版权；模板与说明也提醒玩家不要删。
- 内置示例工程的剧本由本项目自写；其中沿用 WebGAL 官方示例的图片/音乐
  （如 `WebGAL_New_Enter_Image.webp`、`s_Title.mp3`）版权归上游，随引擎按 MPL-2.0
  与上游条款分发，**不属于 MIT 覆盖范围**。
- Termux 是 GPL-3.0 独立程序，本工作站只通过 `RUN_COMMAND` Intent 调用它；全量
  APK 里分发的是**未修改的官方 Termux 0.118.3**，源码见
  <https://github.com/termux/termux-app>。
- 54 个 deb 的包名、版本与许可清单位于 [`LICENSES/DEBS.md`](LICENSES/DEBS.md)；
  每个包内 `/usr/share/doc/<pkg>/copyright` 是完整版权文本，源码见
  <https://github.com/termux/termux-packages>。
- 字体的版权声明、SHA-256 与处置方式见 [`LICENSES/FONTS.md`](LICENSES/FONTS.md)。
- 完整义务与来源声明见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 与
  [`docs/LICENSE-AUDIT.md`](docs/LICENSE-AUDIT.md)。

**你打的游戏归你。** 你的剧本、美术、音乐、配音、角色形象，版权与许可证由你定，
本项目不主张任何权利，也不要求你开源。

## 开源发布说明

**源码可以开源；完全体 APK 不要直接挂 GitHub Release。** 原因：

1. `android.jar` 来自 Android SDK，再分发受 SDK 条款约束。仓库 `.gitignore` 已挡住
   `build/`、`dist/`、`assets/offline/`，源码仓库不含该文件；发布 Release 前请按
   `docs/LICENSE-AUDIT.md` 第 1 节换成 AOSP 自建 `android.jar`，或只发源码。
2. beta.50 仍是调试签名，只能自测/线下分发；正式版请切「我的 keystore」并备份。
   同一个应用后续升级必须用同一把密钥，换密钥会签名冲突，只能卸载重装（数据会丢）。
3. Termux/deb 的 GPL 源码链接与「3 年有效」承诺要写进 Release 说明。

仓库自身的 `.gitignore` 已排除：`dist/`、`build/`、`assets/offline/`、APK、视频素材。
GitHub 上公开的是源码、脚本、文档与许可证；完全体由使用者用自己的 SDK 构建。
Release 文案、上传步骤与检查表见 [`docs/GITHUB-RELEASE.md`](docs/GITHUB-RELEASE.md)。

## 已知限制

- Termux 被国产 ROM 冻结会让构建卡在 `javac`，把 Termux 放前台或关电池优化
- `/sdcard` 是 noexec，脚本要先 `cp` 到 `$HOME` 再执行（工具链已自动处理）
- 首次释放离线包要写约 267 MB，手机存储留够 1 GB 再开工
- 首次配置需要玩家在 Termux 粘贴一次命令；这是 Termux 的安全限制，不是网络问题
- PC 构建脚本只在作者本机 Windows PowerShell 验证过；macOS/Linux 需要自己改路径
- 完全体 APK 不能上架应用商店（调试签名 + 内置 Termux/deb），适合直接分发

## 链接

- GitHub：<https://github.com/SukaKanger>
- 爱发电（支持作者）：<https://afdian.com/a/RoryMarshall>

## 免责

本项目按「现状」提供，不附带任何担保。用它打包、分发、上架产生的任何后果由使用者
自行承担；请确保你拥有工程内素材的合法权利。详见 [`NOTICE.md`](NOTICE.md)。
