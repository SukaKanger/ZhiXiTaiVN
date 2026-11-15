WebGAL 移动工作站 · 构建工程说明
================================================================

这个目录是工作站拼装出来的一份「可以编译的安卓工程」：

  android/AndroidManifest.xml      清单（包名、版本、方向、图标引用）
  android/res/mipmap-*             各密度图标
  android/src/<包名>/*.java        GameActivity（WebView 壳）+ LocalServer（本地 HTTP 服务）
  android/assets/engine/           WebGAL 4.6.4 引擎（原样，MPL-2.0）
  android/assets/game/             你的剧本与素材
  android/assets/legal/            来路与许可证，分发时不能删

怎么构建
----------------------------------------------------------------
手机端（推荐）：
  在 Termux 里执行
      bash <这个目录>/build_apk.sh
  产物落在 out/<应用名>.apk。

PC 端：
  工作站里点「导出到 PC」，会把这个目录打成 zip。
  解压后在有 Android SDK 的机器上跑仓库里的 tools/pc/build-game.ps1，
  或者手工按 build_apk.sh 里的五步来：aapt2 → javac → d8 → zipalign → apksigner。

构建脚本干了什么
----------------------------------------------------------------
  ① aapt2 compile/link  把资源与 assets 打包，产出 base.apk
  ② javac               编译 GameActivity 与 LocalServer
  ③ d8                  把 class 转成 classes.dex
  ④ zip                 把 classes.dex 塞进 base.apk
  ⑤ zipalign            4 字节对齐（可选）
  ⑥ apksigner           用本地调试密钥签名，并校验

许可提醒（别删这一段）
----------------------------------------------------------------
  · 引擎是 WebGAL 4.6.4，MPL-2.0，原样随附，只删了 *.gz 预压缩副本。
  · 你的壳与本地服务代码是本工作站原创，MIT。
  · Termux / BuildAPKs 是 GPL-3.0 独立程序，仅以命令行方式被调用，不随附、不链接。
  · 产物里的 assets/legal/ 必须保留：NOTICE.txt、MPL-2.0.txt、LICENSE-MIT.txt。
  · 调试签名只适合自己装机测试；正式发布请换成你自己的密钥。
