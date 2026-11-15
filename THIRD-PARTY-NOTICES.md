# THIRD-PARTY-NOTICES

本文件列出本项目**随附**与**调用**的第三方组件。分发本项目的 APK（尤其是含
`assets/offline/` 的完全体）时，请连同 `LICENSES/` 与本文件一起提供。

## 一、随附（redistributed）

### 1. WebGAL 引擎 4.6.4

- 许可证：MPL-2.0（全文 `LICENSES/MPL-2.0.txt`）
- 版权：OpenWebGAL 及其贡献者
- 来源：<https://github.com/OpenWebGAL/WebGAL>（tag / release 4.6.4）
- 位置：`src/assets/engine/**` → APK `assets/engine/**`
- 修改：未修改引擎源码；仅剔除与源文件内容重复的 `*.gz` 预压缩副本以减小体积
- 额外条款：上游要求用 WebGAL 开发的作品发布时保留 WebGAL 相关版权声明，作品版权
  归创作者；本项目保留引擎标题页与“Powered by WebGAL Framework”标识
- 义务：保留 MPL-2.0 全文与本声明；若修改引擎文件，须以 MPL-2.0 公开修改后的源码

### 2. Termux 0.118.3（完全体 APK 内置安装包）

- 许可证：GPL-3.0（全文 `LICENSES/GPL-3.0.txt`）
- 版权：Termux 项目及其贡献者
- 来源：<https://github.com/termux/termux-app>（v0.118.3）、F-Droid
- 位置：APK 资产 `assets/offline/termux.apk`（113,880,067 B，未修改官方签名包）
- 分发方式：仅作为安装包资产存放，由用户确认后交给系统安装器；运行在独立应用进程
- 义务：分发二进制须提供对应源码。源码获取地址见上；本项目自首次分发之日起
  3 年内有效，可向仓库 Issue 索取源码副本或获取链接

### 3. Termux 软件包（完全体随附 54 个 deb）

- 许可证：各包自带（GPL-2.0/3.0、LGPL、Apache-2.0、MIT、BSD 等）
- 来源：Termux 软件仓库，详见 <https://github.com/termux/termux-packages>
- 位置：APK 资产 `assets/offline/debs/*.deb`
- 清单：包名、版本见 `LICENSES/DEBS.md`
- 版权文本：每个 deb 内 `/usr/share/doc/<包名>/copyright`
- 义务：分发未修改的二进制包，保留包内版权文件；GPL/LGPL 组件的源码可按上述
  仓库对应版本获取，本项目提供同等获取途径（3 年有效）

主要组件：`aapt2`、`aapt`（附 `zipalign`）、`d8`、`apksigner`、`android-tools`、
`openjdk-21`、`openjdk-21-source`、`libc++`、`openssl`、`zlib`、`libxml2`、`libpng`、
`zip`、`unzip` 等。

### 4. Android 平台 `android.jar`（android-34）

- 许可证：Apache-2.0（AOSP 平台代码）+ Android SDK 条款
- 来源：Android SDK `platforms/android-34/android.jar`（26,361,808 B）
- 位置：APK 资产 `assets/offline/android.jar`
- 用途：端上 `javac -bootclasspath android.jar` 编译工程
- 注意：Android SDK 条款对 SDK 组件的再分发有约束。**若你计划公开分发完全体
  APK，请先阅读 `docs/LICENSE-AUDIT.md` 第 1 节**；仓库本身不提交该文件，
  由构建脚本在打包时从本机 SDK 复制。

### 5. 引擎内的字体与示例素材

WebGAL 4.6.4 发行包自带三款中文字体：OPPO Sans、Resource Han Rounded CN、
Source Han Serif CN，随引擎一并分发。字体版权不属于本项目，也不被 MPL-2.0 覆盖。
其中 Resource Han Rounded 与 Source Han Serif 为 SIL OFL 1.1（全文
`LICENSES/OFL-1.1.txt`）；OPPO Sans 适用 OPPO 官方免费商用条款（见
`LICENSES/OPPO-Sans.md`）。版权声明、文件哈希与处置方式见 `LICENSES/FONTS.md`。
低配构建（`-LiteEngine`）会剔除这三套字体。

引擎自带的图标、示例图音随引擎按 MPL-2.0 分发；若其中含独立第三方素材，
以引擎发行包内声明为准。

本项目内置示例工程（`src/assets/demo-game/`）的剧本为自写；其中沿用 WebGAL 官方
示例的图片/音乐（如 `WebGAL_New_Enter_Image.webp`、`s_Title.mp3`）版权归上游，
随引擎按 MPL-2.0 与上游条款分发，不在本项目 MIT 覆盖范围内。若上游对示例素材另有
单独条款，以上游为准；收到权利人通知可删除替换。

## 二、调用但不随附（called, not redistributed）

| 组件 | 许可证 | 用法 |
|---|---|---|
| OpenJDK 21（PC / Termux） | GPL-2.0 with Classpath Exception | 命令行 `javac`/`keytool` |
| Android SDK build-tools 36.x | Apache-2.0 + SDK 条款 | 命令行 `aapt2`/`d8`/`zipalign`/`apksigner` |
| BuildAPKs | GPL-3.0 | 仅参考思路，`build_apk.sh` 为自写，未复制代码 |
| ffmpeg（仅开发机视频管线） | LGPL/GPL（视构建） | 生成教程视频，不进 APK |

## 三、聚合体边界

Termux、OpenJDK、build-tools 与本项目之间是**独立程序、独立进程、命令行或
Intent 调用**，无链接、无静态合并、无同进程调用。GPL 组件义务不延伸到本项目的
MIT 代码，也不延伸到用户生成的游戏 APK。

## 四、用户产物

用户打包出的 APK 中，除 WebGAL 引擎（MPL-2.0）与用户自带内容外，其余版权由
用户自行确定。本项目不对用户产物主张任何权利。

## 五、商标

「WebGAL」「Termux」「Android」为其各自权利人的名称/商标。本项目为第三方工具，
与上述项目无隶属、无背书关系，不以官方名义分发。
