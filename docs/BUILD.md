# 从源码构建

> 仓库里的 `tools/pc/*.ps1` 是作者自用的 Windows PowerShell 工具，**只在 Windows
> 验证过**；macOS/Linux 需要自己改路径或按下面第 4 节手搓。Android 产物本身与宿主
> 系统无关。

## 1. 环境

| 项 | 版本 / 路径 |
|---|---|
| JDK | 21（`JAVA_HOME` 指向它） |
| Android SDK | build-tools 36.x、platform `android-34` |
| `ANDROID_HOME` | SDK 根目录 |
| PowerShell | 7（Windows） |

## 2. 一键打版（Windows）

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
$env:ANDROID_HOME="R:\soft\android202422\sdk_home"

# 1) 生成完全体离线资产（Termux + 54 deb + android.jar）
& .\tools\pc\make-offline-bundle.ps1

# 2) 打完全体（含引擎字体 + 全量离线工具链）
& .\tools\pc\build-station.ps1 -FullOffline

# 只打轻量包（不含 offline 资产）
& .\tools\pc\build-station.ps1 -LiteEngine
```

产物：`dist\织戏台-ZhiXiTaiVN-<版本>-full.apk`。

- `-LiteEngine`：剔除引擎自带三套中文字体（体积从 ~294 MB 降到 ~7 MB 量级）
- `-FullOffline`：把 `build\offline\assets\offline` 塞进 `assets/offline`
- `-NoBump`：冻结版本号重打（只改 UI/文案时用）

## 3. 版本号

`VERSION.json` 是唯一来源。`version.ps1` 生成 `AndroidManifest.xml` 与各处版本常量：

- `versionCode = 2618 * 10000 + build`
- `versionName = <base>-<stage>[.build]`

## 4. 手动流水线（任意平台）

```bash
# 0) 准备
SDK=/path/to/android-sdk
BT=$SDK/build-tools/36.1.0
AJ=$SDK/platforms/android-34/android.jar
OUT=build/manual

# 1) 资源编译
$BT/aapt2 compile --dir src/res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/base.apk \
  -I $AJ --manifest src/AndroidManifest.xml \
  -A src/assets --java $OUT/gen $OUT/res.zip

# 2) Java 编译
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath $AJ -classpath $AJ \
  -d $OUT/classes $OUT/gen/**/*.java src/java/**/*.java

# 3) dex
$BT/d8 --release --lib $AJ --output $OUT/dex $OUT/classes

# 4) 打包 + 对齐 + 签名
cp $OUT/base.apk $OUT/unsigned.apk
cd $OUT/dex && zip -q ../unsigned.apk classes.dex && cd ..
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
$BT/apksigner sign --ks debug.keystore --out dist/app.apk $OUT/aligned.apk
```

> 完全体还需要把 `assets/offline/` 放进 `-A` 目录，并保证 `assets/offline/termux.apk`、
> `debs/*.deb`、`android.jar` 齐全。生成脚本见 `tools/pc/make-offline-bundle.ps1`。

## 5. 真机调试

```bash
adb install -r --no-incremental dist/app.apk
adb shell appops set com.zhixitai.vn MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.zhixitai.vn com.termux.permission.RUN_COMMAND
```

首次启动会在环境页要求：所有文件访问、释放离线包、在 Termux 跑一次安装命令、
运行自检。

## 6. 许可提醒

打完全体包会把 Termux（GPL-3.0）与 54 个 deb 一起分发。对外发布前先读
[`LICENSE-AUDIT.md`](LICENSE-AUDIT.md)，尤其是 `android.jar` 再分发、字体许可与
调试签名三节。
