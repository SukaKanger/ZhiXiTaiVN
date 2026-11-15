# 开源发布前的许可审计

日期：2026-11-14　版本：1.1.7-beta.50　审计人：作者本人（非法律意见）

本文件只做一件事：把「源码能开源」和「完全体 APK 能公开分发」分开看。
**源码 MIT 开源没问题；把 294 MB 完全体 APK 直接挂 GitHub Release，仍有
`android.jar` 与调试签名两个坑。**

## 1. `android.jar` 再分发（最高风险，仍未清零）

- 事实：完全体 APK 的 `assets/offline/android.jar`（26,361,808 B）来自 Android SDK
  `platforms/android-34/android.jar`，由 `make-offline-bundle.ps1` 复制进包。
- 风险：Android SDK 条款对 SDK 组件的再分发有约束；它不像 AOSP 源码那样无条件
  Apache-2.0。把它塞进 APK 公开下载，属于「再分发 SDK 组件」。
- 已做的隔离：仓库 `.gitignore` 挡住 `build/`、`dist/`、`assets/offline/`，**源码仓库
  不提交 `android.jar`、Termux APK、54 个 deb**。GitHub 上公开的是源码与构建脚本，
  由使用者用自己的 Android SDK 生成完全体。
- 公开 Release 前仍建议三选一：
  1. 不把完全体 APK 作为 GitHub Release 附件，只发源码 + 校验和/构建说明；
  2. 把 `android.jar` 换成 AOSP 自建的 `android.jar`
     （<https://android.googlesource.com/platform/prebuilts/sdk/>），并在 NOTICE 注明；
  3. 发布「不含 android.jar」的构建，首次运行时让用户从自己的 SDK 选择该文件。
- 现状：`dist/` 里的 beta.50 完全体仍使用本机 SDK 的 `android.jar`，适合自己测试、
  真机验收和线下分发；**不要把它当作已经通过法律审查的 Release 资产**。

## 2. Termux（GPL-3.0）

- 事实：完全体 APK 内置 `assets/offline/termux.apk` = 官方 0.118.3，未改签名、未改代码。
- 义务：分发 GPLv3 二进制须提供对应源码；保留 GPL 全文；不得附加额外限制。
- 处置：`LICENSES/GPL-3.0.txt` 已附；`THIRD-PARTY-NOTICES.md` 写明源码地址与
  3 年有效获取承诺。Termux 以独立应用安装、独立进程运行，不与本项目链接。
- 待办：Release 说明里再写一次源码链接，避免只靠仓库文件。

## 3. 54 个 deb（GPL/LGPL/Apache/MIT/BSD 混合）

- 事实：`build/offline/debs/` 54 个包（含 `aapt`、`zip`、`unzip`），来自 Termux 仓库，
  未修改。
- 义务：保留各包 `copyright`；GPL/LGPL 组件提供源码获取途径。
- 处置：`LICENSES/DEBS.md` 已列包名+版本；包内自带完整版权文本；源码指向
  termux-packages 对应版本。
- 注意：`aapt_16.0.0.4-4_aarch64.deb` 同时提供 `aapt` 与 `zipalign`，解决端上
  「zipalign 缺失、产物未对齐」的问题；`zip`/`unzip` 解决 `tools.sh` 的缺工具告警。
- 风险：部分包（如 `openjdk-21`）体积大、许可证文本多，若严格合规应把每个包的
  copyright 抽出来单独附上。当前做法是「包内自带 + 清单指路」，属于可接受但不完美。

## 4. WebGAL 引擎（MPL-2.0）

- 事实：`src/assets/engine/**` 是 4.6.4 发行构建，未改源码，只删了重复的 `.gz`。
- 义务：保留 MPL-2.0 全文与来源；修改 MPL 文件须公开修改版源码。
- 额外条款：WebGAL 官网许可页要求，用 WebGAL 开发的作品发布时保留 WebGAL 相关
  版权声明，作品版权归创作者。本项目保留引擎标题页与“Powered by WebGAL Framework”
  标识；模板/文档提醒玩家不要抹除。
- 处置：`LICENSES/MPL-2.0.txt` 已附；`src/assets/engine/LICENSE.txt` 已放一份指针；
  NOTICE 已写明上游 tag。
- 注意：引擎发行包里**没有**自带 LICENSE 文件，这是上游打包习惯；我们在仓库侧补齐。

## 5. 引擎内置字体（已核对，风险降级）

| 字体 | 文件 | 许可证 | 结论 |
|---|---|---|---|
| OPPO Sans | `OPPOSans-R-*.ttf` 10.2 MB | OPPO 自定义免费商用条款（非 OFL） | 免费商用；禁止改编、单独售卖、单独提供下载渠道。原样随包，未单独售卖。见 `LICENSES/OPPO-Sans.md` |
| Resource Han Rounded CN | `ResourceHanRoundedCN-Regular-*.ttf` 14.7 MB | **SIL OFL 1.1** | 可随包分发；已附 OFL 全文与版权声明 |
| Source Han Serif CN | `SourceHanSerifCN-Regular-*.ttf` 13.0 MB | **SIL OFL 1.1** | 可随包分发；已附 OFL 全文与版权声明 |

- 处置：`LICENSES/OFL-1.1.txt` 已补；`LICENSES/FONTS.md` 记录版权、SHA-256 与义务。
- 仍需留意：OPPO 条款第 3 条「不向他方提供其他下载渠道」存在解释空间。若收到
  权利人通知，删除 `OPPOSans-R-*.ttf` 即可，界面自动回退；或用 `-LiteEngine`
  出不含字体的低配包。商业发行前建议直接换成 OFL 字体，或向 OPPO 书面确认。

## 6. 商标与名称

- 「WebGAL」是上游名称。README/NOTICE 已明确「第三方工具、非官方、无背书」。
- 产品名已改为「织戏台 / ZhiXiTai VN」，包名 `com.zhixitai.vn`，不再以 WebGAL 自称。
- 建议：GitHub 仓库描述里不要写「WebGAL 官方手机版」。

## 7. 示例工程与模板素材

- `src/assets/demo-game/` 与 `templates/` 的剧本/模板为自写；其中沿用 WebGAL 官方
  示例的图片/音乐（如 `WebGAL_New_Enter_Image.webp`、`s_Title.mp3`）版权归上游，
  随引擎按 MPL-2.0 与上游条款分发，不纳入本项目 MIT。
- 处置：已在 `NOTICE.md` 与 `THIRD-PARTY-NOTICES.md` 单独声明；若上游对示例素材另有
  单独条款，以上游为准。公开仓库前若仍有疑虑，可把 `demo-game` 换成自制素材，
  或从源码/APK 中移除以降低风险。

## 8. 签名

- 完全体 beta.50 仍是**调试密钥**，只能自测/线下分发，不能上架，也不能作为正式
  更新通道。
- 正式发行：在打包台切「我的 keystore」或「新建 keystore」，备份 `.jks` 与口令；
  同一应用的后续版本必须用同一把密钥，否则无法覆盖安装。
- Release 说明里不要提供「调试签名正式版」这种自相矛盾的产物。

## 9. GitHub 发布清单

- [x] `LICENSE` 为 MIT，包名/路径已更新
- [x] `NOTICE.md`、`THIRD-PARTY-NOTICES.md` 按「完全体确实随附 Termux/deb/android.jar」改写
- [x] `LICENSES/DEBS.md` 54 包清单（含 aapt/zip/unzip）
- [x] `LICENSES/FONTS.md` 三款字体许可核对 + SHA-256
- [x] `LICENSES/OFL-1.1.txt`、`LICENSES/OPPO-Sans.md`
- [x] `.gitignore` 挡住 `dist/`、`build/`、`assets/offline/`、视频素材
- [x] 源码仓库不含 `android.jar`、Termux APK、deb（由使用者本地构建）
- [ ] GitHub Release **不要**直接上传使用本机 SDK `android.jar` 的完全体；先按第 1 节处理
- [ ] Release 说明附 Termux / deb 源码链接与「3 年有效」承诺
- [ ] 若公开仓库，建议开启 Issues 作为源码索取入口
- [ ] 正式版换非调试 keystore，并备份

## 10. 结论

- **源码开源：可以。** MIT + MPL 边界清楚，不涉及 GPL 传染；字体许可已核对并附全文。
- **公开分发完全体 APK：有条件。** 最大变量仍是 `android.jar` 的 SDK 条款；Termux/deb
  的 GPL 义务靠「源码链接 + 3 年承诺 + 包内版权」兜底；调试签名不可上架。
- 本文件不是法律意见；真要做商业发行，花点钱找律师看一遍第 1、3、5 节。
