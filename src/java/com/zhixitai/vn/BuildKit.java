/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * BuildKit —— 把一个工程 + 引擎拼装成「可以直接交给 aapt2/javac/d8/apksigner」的构建工程。
 *
 * 产物目录结构（/sdcard/Download/WebGALStation/build/<buildId>/）：
 *   android/AndroidManifest.xml
 *   android/res/mipmap-XXX/ic_launcher.png
 *   android/src/<包名路径>/GameActivity.java, LocalServer.java
 *   android/assets/engine/*        ← 引擎（原样）
 *   android/assets/game/*          ← 你的剧本与素材
 *   android/assets/legal/*         ← 来路与许可证，分发时不能丢
 *   build_apk.sh                   ← 端上构建脚本（Termux 跑它）
 *   build.json / build.log / status.json
 *
 * 拼装逻辑放在 APK 里而不是脚本里，是因为读 assets 只有 Java 侧方便；
 * 脚本只负责编译打包，不碰素材搬运。
 */
public class BuildKit {

    public static final String TARGET_SDK = "34";
    public static final String MIN_SDK = "21";

    public static class Options {
        public String appName = "WebGAL 游戏";
        public String pkg = "com.webgal.game.demo";
        public String versionName = V.NAME;
        public int versionCode = V.CODE;
        /** landscape | portrait | auto */
        public String orientation = "landscape";
        /** 自定义图标图片绝对路径，null 表示用工作站默认图标 */
        public String iconPath;
        /** 是否在游戏启动时显示 3 秒来路角标 */
        public boolean badge = true;
        /** 签名模式：debug（内置调试签名）| custom（玩家自己的 keystore）| new（现场生成） */
        public String signMode = "debug";
        /** keystore 绝对路径（custom / new 时有效） */
        public String ksPath;
        public String ksAlias = "webgal";
        public String ksStorePass = "android";
        public String ksKeyPass = "";
    }

    public interface Progress {
        void onProgress(String stage, int percent, String detail);
    }

    private static final int[] ICON_SIZES = {48, 72, 96, 144, 192};
    private static final String[] ICON_QUALS = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};

    public static File prepare(Context ctx, Store.Project project, Options opt, File buildRoot, Progress cb)
            throws IOException {
        AssetManager am = ctx.getAssets();

        File projectGameDir = new File(project.gameDir);
        if (!projectGameDir.isDirectory()) {
            throw new IOException("工程目录不存在：" + project.gameDir);
        }
        if (!new File(projectGameDir, "config.txt").isFile()) {
            throw new IOException("工程根目录里没有 config.txt，引擎会直接白屏。请确认导入的是 WebGAL 的 game 目录。");
        }
        if (!Util.validPackage(opt.pkg)) {
            throw new IOException("包名不合法：" + opt.pkg);
        }

        // 毫秒也带上：手机上双击「生成构建工程」很常见，秒级时间戳会撞到同一个目录，
        // 两份构建混在一份 status.json / build.log 里，前端看到的就是乱账。
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File base = new File(new File(buildRoot, "build"), project.id + "-" + stamp);
        File androidDir = new File(base, "android");
        File assetsDir = new File(androidDir, "assets");
        File engineDir = new File(assetsDir, "engine");
        File gameDir = new File(assetsDir, "game");
        File legalDir = new File(assetsDir, "legal");
        File resDir = new File(androidDir, "res");
        File srcDir = new File(new File(androidDir, "src"), opt.pkg.replace('.', File.separatorChar));

        if (!androidDir.exists() && !androidDir.mkdirs()) {
            throw new IOException("无法创建构建目录：" + androidDir);
        }

        // 1) 引擎
        report(cb, "复制引擎", 2, "WebGAL 4.6.4 → assets/engine");
        int engineFiles = Util.copyAssetTree(am, "engine", engineDir, null);
        report(cb, "复制引擎", 20, engineFiles + " 个文件就位");

        // 2) 剧本与素材
        final int[] gameCount = {0};
        Util.copyTree(projectGameDir, gameDir, new Util.FileVisitor() {
            @Override
            public void onFile(File file) {
                gameCount[0]++;
                if (gameCount[0] % 40 == 0) {
                    report(cb, "复制工程素材", 20 + Math.min(50, 20 + gameCount[0] / 20),
                            gameCount[0] + " 个文件");
                }
            }
        });
        report(cb, "复制工程素材", 72, gameCount[0] + " 个文件就位");

        // 3) 来路与许可证（MPL-2.0 要求随分发物一起走）
        if (!legalDir.exists() && !legalDir.mkdirs()) {
            throw new IOException("无法创建 legal 目录");
        }
        Util.copyAsset(am, "legal/NOTICE.txt", new File(legalDir, "NOTICE.txt"));
        Util.copyAsset(am, "legal/MPL-2.0.txt", new File(legalDir, "MPL-2.0.txt"));
        // MIT 也要求随附许可全文，别只写在须知里一句话
        Util.copyAsset(am, "legal/LICENSE-MIT.txt", new File(legalDir, "LICENSE-MIT.txt"));
        if (opt.badge) {
            Util.writeText(new File(legalDir, "badge.txt"),
                    "WebGAL 4.6.4（MPL-2.0 非官方构建）· 由 WebGAL 移动工作站打包");
        }

        // 4) 清单
        String manifest = Util.readAsset(am, "templates/game/AndroidManifest.xml");
        if (manifest == null) {
            throw new IOException("模板缺失：templates/game/AndroidManifest.xml");
        }
        String orientation = "landscape";
        if ("portrait".equals(opt.orientation)) {
            orientation = "portrait";
        } else if ("auto".equals(opt.orientation)) {
            orientation = "fullSensor";
        }
        manifest = manifest.replace("__PKG__", opt.pkg)
                .replace("__VERSION_CODE__", String.valueOf(opt.versionCode))
                .replace("__VERSION_NAME__", Util.escapeXml(opt.versionName))
                .replace("__TARGET_SDK__", TARGET_SDK)
                .replace("__APP_NAME__", Util.escapeXml(opt.appName))
                .replace("__ORIENTATION__", orientation);
        Util.writeText(new File(androidDir, "AndroidManifest.xml"), manifest);
        report(cb, "写入清单", 76, opt.pkg + " / " + opt.versionName);

        // 5) Java 源码
        if (!srcDir.exists() && !srcDir.mkdirs()) {
            throw new IOException("无法创建源码目录：" + srcDir);
        }
        String activity = Util.readAsset(am, "templates/game/java/GameActivity.java");
        if (activity == null) {
            throw new IOException("模板缺失：templates/game/java/GameActivity.java");
        }
        activity = activity.replace("package __PKG__;", "package " + opt.pkg + ";");
        Util.writeText(new File(srcDir, "GameActivity.java"), activity);

        String localServer = Util.readAsset(am, "templates/common/LocalServer.java");
        if (localServer == null) {
            throw new IOException("模板缺失：templates/common/LocalServer.java");
        }
        localServer = localServer.replace("package com.zhixitai.vn;", "package " + opt.pkg + ";");
        Util.writeText(new File(srcDir, "LocalServer.java"), localServer);
        report(cb, "写入源码", 80, "GameActivity + LocalServer");

        // 6) 图标
        writeIcons(am, resDir, opt.iconPath);
        report(cb, "生成图标", 86, opt.iconPath == null ? "工作站默认图标" : new File(opt.iconPath).getName());

        // 7) 构建脚本
        Util.copyAsset(am, "kit/build_apk.sh", new File(base, "build_apk.sh"));
        Util.copyAsset(am, "kit/README-build.txt", new File(base, "README-build.txt"));

        JSONObject job = new JSONObject();
        try {
            job.put("buildId", base.getName());
            job.put("projectId", project.id);
            job.put("projectName", project.name);
            job.put("gameName", project.gameName == null ? "" : project.gameName);
            job.put("appName", opt.appName);
            job.put("pkg", opt.pkg);
            job.put("versionName", opt.versionName);
            job.put("versionCode", opt.versionCode);
            job.put("orientation", orientation);
            job.put("targetSdk", TARGET_SDK);
            job.put("minSdk", MIN_SDK);
            job.put("createdAt", System.currentTimeMillis());
            job.put("station", V.NAME);
            job.put("engine", "4.6.4");
        } catch (Exception ignored) {
        }
        String jobJson;
        try {
            jobJson = job.toString(2);
        } catch (Exception e) {
            jobJson = job.toString();
        }
        Util.writeText(new File(base, "build.json"), jobJson);
        // 脚本用 . 加载这个文件，比在 bash 里解 JSON 靠谱得多
        File jobEnv = new File(base, "job.env");
        Util.writeText(jobEnv,
                "APP_NAME=" + shellQuote(opt.appName) + "\n"
                        + "PKG=" + shellQuote(opt.pkg) + "\n"
                        + "VERSION_NAME=" + shellQuote(opt.versionName) + "\n"
                        + "VERSION_CODE=" + opt.versionCode + "\n"
                        + "ORIENTATION=" + shellQuote(orientation) + "\n"
                        + "TARGET_SDK=" + TARGET_SDK + "\n"
                        + "MIN_SDK=" + MIN_SDK + "\n"
                        + "PROJECT_NAME=" + shellQuote(project.name) + "\n"
                        + "SIGMODE=" + shellQuote(opt.signMode == null ? "debug" : opt.signMode) + "\n"
                        + "KS_PATH=" + shellQuote(opt.ksPath == null ? "" : opt.ksPath) + "\n"
                        + "KS_ALIAS=" + shellQuote(opt.ksAlias == null ? "webgal" : opt.ksAlias) + "\n"
                        + "KS_STORE_PASS=" + shellQuote(opt.ksStorePass == null ? "android" : opt.ksStorePass) + "\n"
                        + "KS_KEY_PASS=" + shellQuote(opt.ksKeyPass == null ? "" : opt.ksKeyPass) + "\n");
        // 口令是明文的：别让同机其他持有存储权限的应用顺手读走（脚本跑完会自己删这份）
        if (jobEnv.isFile()) {
            jobEnv.setReadable(false, false);
            jobEnv.setReadable(true, true);
            jobEnv.setWritable(false, false);
            jobEnv.setWritable(true, true);
        }
        Util.writeText(new File(base, "build.log"), "");
        Util.writeText(new File(base, "status.json"), "{\"state\":\"prepared\"}");
        report(cb, "构建工程就绪", 100, base.getAbsolutePath());

        Log.i(Util.TAG, "build prepared: " + base);
        return base;
    }

    /** 单引号包裹，内部单引号按 POSIX 规则转义，保证 bash 里 . 加载不会炸。 */
    private static String shellQuote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static void report(Progress cb, String stage, int percent, String detail) {
        if (cb != null) {
            cb.onProgress(stage, percent, detail);
        }
    }

    private static void writeIcons(AssetManager am, File resDir, String iconPath) throws IOException {
        if (iconPath != null && new File(iconPath).isFile()) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(iconPath, bounds);
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > 512) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeFile(iconPath, opts);
            if (src != null) {
                for (int i = 0; i < ICON_SIZES.length; i++) {
                    File dir = new File(resDir, "mipmap-" + ICON_QUALS[i]);
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("无法创建图标目录：" + dir);
                    }
                    Bitmap scaled = Bitmap.createScaledBitmap(src, ICON_SIZES[i], ICON_SIZES[i], true);
                    FileOutputStream out = new FileOutputStream(new File(dir, "ic_launcher.png"));
                    try {
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } finally {
                        Util.close(out);
                    }
                    if (scaled != src) {
                        scaled.recycle();
                    }
                }
                src.recycle();
                return;
            }
        }
        for (int i = 0; i < ICON_QUALS.length; i++) {
            File dir = new File(resDir, "mipmap-" + ICON_QUALS[i]);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建图标目录：" + dir);
            }
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = am.open("kit/icons/mipmap-" + ICON_QUALS[i] + "/ic_launcher.png");
                out = new FileOutputStream(new File(dir, "ic_launcher.png"));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                Util.close(in);
                Util.close(out);
            }
        }
    }
}
