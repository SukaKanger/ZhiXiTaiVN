# GitHub 开源发布说明（v1.1.7-beta.50）

这份文件是「照抄就能发」的 Release 文案与检查表。仓库公开的是**源码**；
完全体 APK 是本地测试包，是否作为 Release 附件由发布者按 `LICENSE-AUDIT.md` 自行决定。

## 一、仓库信息

- 仓库名建议：`zhixitai-vn`
- 描述建议：`手机上写视觉小说、手机上打 APK 的 WebGAL 移动工作台；离线 Termux 工具链，不联网、不挂 VPN。`
- 主分支：`main`
- 标签：`v1.1.7-beta.50`
- 许可证：MIT（仅覆盖原创代码，第三方组件见 `NOTICE.md`）

## 二、Release 文案（可直接粘贴）

```markdown
# 织戏台 · ZhiXiTai VN v1.1.7-beta.50

手机上写视觉小说，手机上打出 APK。一个包、断网可用、素材自己导入、工程不上传。

## 这个版本能干什么

- 工作台：工程库、场景列表、内嵌预览，左右栏可收，打包台可拖宽/居左/居中/居右
- 素材导入：背景/立绘/音乐/语音/视频/动画/其他 7 类，一次最多 80 个，落到 `game/<分类>/`
- 素材重命名：素材 chip 旁 `✎`，只改文件名，扩展名不动
- 参数问号：打包台 `?` 逐项解释应用名、包名、版本名/号、方向、图标、来路角标、签名、口令、构建按钮
- 产物路径：成功卡显示完整 APK 绝对路径，支持一键复制；文件管理器路径同步写清
- 工程导入：文件夹导入 + 应用内 ZIP 扫描（绕开国产 ROM 文件选择器）
- 剧本编辑：直接改 `.txt`，带语法提示与错误定位
- 八步引导、不会就问、环境页自检
- 端上打包：`aapt2 → javac → d8 → zipalign → apksigner` 全在设备本地跑
- 离线优先：`setup.sh` 检测到随包 `offline/install_offline.sh` 就直接跑 54 个 deb，
  不碰 `pkg` 网络源，不需要 VPN

## 真机验收（vivo V2219A / Android 13 / API 33）

- 素材导入：`p3.png` → `projects/tutorial/game/background/p3.png`（115,711 B）
- 素材重命名：`✎` 改名成功，扩展名保留，文件落盘
- 参数问号：`?` 弹窗可开、可滚、可点遮罩关
- 产物路径：beta.50 端上构建 `com.zhixitai.demo50`，成功卡显示完整路径；「复制 APK 路径」出 toast
- ZIP 导入：应用内扫描 `zz-tutorial.zip`，工程数 2→3，3 场景 / 37 文件 / 5.7 MB
- 端上出包：Termux 19 秒产出 28 MB APK；安装后 WebGAL 标题页、主菜单、场景与素材正常
- 全程未挂 VPN、未执行 `pkg install`

## 安装（完全体）

1. 安装 `织戏台-ZhiXiTaiVN-1.1.7-beta.50-full.apk`
2. 首启给「所有文件访问」权限
3. 环境页点「一键离线配齐（不联网）」
4. 按提示打开 Termux，长按 → Paste → 回车，执行：
   `bash /sdcard/Download/WebGALStation/offline/install_offline.sh`
5. 回环境页「运行自检」，全绿后去工作台打包

首次释放约 267 MB，手机留够 1 GB。Termux 被国产 ROM 冻结时把 Termux 放前台再构建。

## 许可（重要）

- 本项目原创代码：MIT
- WebGAL 引擎 4.6.4：MPL-2.0（非官方构建）；上游额外条款要求用其开发的作品发布时
  保留 WebGAL 相关版权声明，作品版权归创作者；本项目保留引擎标题页与 WebGAL 标识
- 随附 Termux 0.118.3：GPL-3.0，源码 <https://github.com/termux/termux-app>
- 随附 54 个 Termux deb：各自许可证，清单见 `LICENSES/DEBS.md`，源码
  <https://github.com/termux/termux-packages>
- 引擎字体：两款 SIL OFL 1.1 + 一款 OPPO 免费商用条款，见 `LICENSES/FONTS.md`
- `android.jar`：Apache-2.0（AOSP）+ Android SDK 条款；**本 Release 若附完全体 APK，
  请先阅读 `docs/LICENSE-AUDIT.md` 第 1 节**

完整第三方声明见 `THIRD-PARTY-NOTICES.md`。本项目与 OpenWebGAL、Termux、Google
无隶属、无背书关系。

## 已知限制

- beta.50 完全体是**调试签名**，只能自测/线下分发，不能上架；正式发行请切自己的 keystore
- 完全体内含 Termux/deb，不适合直接上架应用商店
- 首次配置需要玩家在 Termux 粘贴一次命令（Termux 安全模型决定，不是网络问题）
- PC 构建脚本只在作者 Windows PowerShell 验证过；Android 产物与宿主系统无关
```

## 三、上传步骤

### 3.1 只发源码（最稳）

1. 解压 `织戏台-开源仓库-1.1.7-beta.50.zip`
2. 在 GitHub 新建空仓库，不要勾选自动生成 README/LICENSE
3. 把解压内容推到 `main`
4. 打标签 `v1.1.7-beta.50`，把上面的 Release 文案粘进去
5. Release 附件只放源码 zip 与 `SHA256SUMS.txt`

### 3.2 同时发完全体 APK（先读审计）

1. 确认你接受 `android.jar` 再分发风险，或按 `docs/LICENSE-AUDIT.md` 第 1 节替换
2. 在 Release 附件里加 `织戏台-ZhiXiTaiVN-1.1.7-beta.50-full.apk`
3. Release 标题/正文明确写「本地测试包，调试签名，不是应用商店正式版」
4. 同时附 `LICENSES/`、`THIRD-PARTY-NOTICES.md` 与 Termux/deb 源码链接
5. 不要把它说成「官方 WebGAL 手机版」

## 四、发布前检查表

- [x] `README.md` 版本号 `1.1.7-beta.50` / versionCode `26180050`
- [x] 完全体大小 308,864,335 B（294.56 MB）
- [x] `NOTICE.md`、`THIRD-PARTY-NOTICES.md` 与 54 deb / 三款字体一致
- [x] `LICENSES/DEBS.md` 54 包清单（含 `aapt`、`zip`、`unzip`）
- [x] `LICENSES/FONTS.md`、`LICENSES/OFL-1.1.txt`、`LICENSES/OPPO-Sans.md`
- [x] `.gitignore` 排除 `build/`、`dist/*.apk`、`src/assets/offline/`、`*.deb`、`termux.apk`、`android.jar`
- [x] 源码中无 API Key、口令、个人路径（`src/java`、`src/assets/ui`、`src/assets/kit` 已扫）
- [ ] Release 说明附 Termux / deb 源码链接与 3 年有效承诺
- [ ] 正式发行前换非调试 keystore，并离线备份 `.jks` 与口令
- [ ] 若要公开完全体，先处理 `android.jar` 再分发问题
- [ ] 建议开启 Issues，作为 GPL 源码索取入口

## 五、GPL 源码索取承诺

本项目自首次分发完全体 APK 之日起 **3 年内**，通过仓库 Issue 提供 Termux 0.118.3
与随附 54 个 deb 的对应源码获取地址或副本。Termux 与各 deb 均未修改二进制，
源码以 Termux 官方仓库对应版本为准：

- <https://github.com/termux/termux-app>
- <https://github.com/termux/termux-packages>

## 六、免责

本项目按「现状」提供，不附带任何担保。发布者应自行确认 `android.jar`、字体、
Termux/deb 的再分发条件；本文件不是法律意见。
