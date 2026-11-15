/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.text.InputType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 工作站主界面：一个 WebView 装 UI，一个 LocalServer 装引擎与工程。
 * 原生只做 UI 干不了的事：文件、存储权限、Termux、安装、攒构建工程。
 */
public class StationActivity extends Activity {

    public static final int REQ_IMPORT = 1001;
    public static final int REQ_ICON = 1002;
    public static final int REQ_STORAGE = 1003;
    public static final int REQ_TERMUX_RUN = 1004;
    public static final int REQ_KEYSTORE = 1005;
    public static final int REQ_ASSETS = 1006;
    public static final int REQ_PROJECT_ZIP = 1007;

    private WebView web;
    private LocalServer server;
    private Store store;
    private int port = -1;
    private boolean previewing = false;
    private File pendingIconFile;
    /** 素材导入：一次多选，原生层按分类落到 game/<dir>/。 */
    private String pendingAssetPid;
    private String pendingAssetCat;
    private Thread assetImportThread;
    /** ZIP 工程导入：系统文件夹选择器被 ROM 拦时的兜底通道。 */
    private Thread zipImportThread;
    /** 等 RUN_COMMAND 权限下来后要跑的脚本；授权回来立刻补发，用户不用再点一次。 */
    private String pendingRunScript;
    /** 构建重入闸：连点「开始构建」只放行第一次，脚本侧另有 flock 兜底。 */
    private volatile boolean buildRunning = false;
    /** 后台线程句柄，onDestroy 时中断，避免对已销毁的界面回调。 */
    private Thread bootstrapThread;
    private Thread prepareThread;
    private Thread importThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF0A0B0C));

        store = new Store(this);
        store.setProjectsDir(new File(getBuildRoot(), "projects"));
        server = new LocalServer();
        server.mount("/", new LocalServer.AssetSource(getAssets(), "engine"));
        server.mount("/ui/", new LocalServer.AssetSource(getAssets(), "ui"));
        server.mount("/legal/", new LocalServer.AssetSource(getAssets(), "legal"));

        try {
            port = server.start();
        } catch (IOException e) {
            Log.e(Util.TAG, "server start failed", e);
            showFatal("本地服务起不来：" + e.getMessage());
            return;
        }

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSupportZoom(false);
        // 调试通道只在可调试构建里开：正式分发包开着 chrome://inspect，
        // 同设备任何浏览器都能附加进来读界面内容。
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        web.setBackgroundColor(0xFF0A0B0C);
        web.setWebChromeClient(new WebChromeClient() {
            // WebView 默认不实现 alert/confirm/prompt，界面上点了没反应。自己补上。
            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final android.webkit.JsResult result) {
                final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(StationActivity.this);
                b.setTitle("工作站").setMessage(message)
                        .setPositiveButton("知道了", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            public void onCancel(android.content.DialogInterface d) {
                                result.confirm();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final android.webkit.JsResult result) {
                final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(StationActivity.this);
                b.setTitle("确认一下").setMessage(message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            public void onCancel(android.content.DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      final android.webkit.JsPromptResult result) {
                final android.widget.EditText input = new android.widget.EditText(StationActivity.this);
                input.setSingleLine(true);
                if (defaultValue != null) {
                    input.setText(defaultValue);
                    input.setSelection(defaultValue.length());
                }
                int pad = (int) (getResources().getDisplayMetrics().density * 16);
                input.setPadding(pad, pad, pad, pad);
                final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(StationActivity.this);
                b.setTitle("工作站").setMessage(message).setView(input)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            public void onCancel(android.content.DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) {
                    return true;
                }
                if (url.startsWith("http://127.0.0.1:")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(Util.TAG, "WebView 错误 " + errorCode + " " + description + " " + failingUrl);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                Log.w(Util.TAG, "HTTP " + errorResponse.getStatusCode() + " " + request.getUrl());
            }
        });
        web.addJavascriptInterface(new Api(this), "Station");
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + port + "/ui/index.html");

        requestLegacyStorage();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    deployKit();
                    ensureDemo();
                    ensureTutorial();
                    cleanupOldBuilds();
                    emit("ready", new JSONObject().put("ok", 1));
                } catch (Exception e) {
                    Log.w(Util.TAG, "bootstrap failed", e);
                }
            }
        }, "station-bootstrap");
        bootstrapThread = t;
        t.start();
    }

    /**
     * 构建目录只留最近 7 天的：低配机反复试打包，Download 里几十个 6 MB 的中间产物
     * 越堆越慢（FUSE 上调小文件本来就卡），用户自己又不会去清。
     */
    private void cleanupOldBuilds() {
        File dir = new File(getBuildRoot(), "build");
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long keepMs = 7L * 24 * 60 * 60 * 1000;
        int removed = 0;
        for (int i = 0; i < kids.length; i++) {
            File k = kids[i];
            if (k.isDirectory() && now - k.lastModified() > keepMs) {
                Util.deleteTree(k);
                removed++;
            }
        }
        if (removed > 0) {
            Log.i(Util.TAG, "清掉 " + removed + " 个 7 天前的构建目录");
        }
    }

    private void showFatal(String message) {
        TextView tv = new TextView(this);
        tv.setText("WebGAL 移动工作站启动失败\n\n" + message);
        tv.setTextColor(0xFFFF5B4D);
        tv.setBackgroundColor(0xFF0A0B0C);
        tv.setPadding(56, 56, 56, 56);
        setContentView(tv);
    }

    // ---------------------------------------------------------------- 存储根

    public boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean writable(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File probe = new File(dir, ".probe");
        try {
            FileOutputStream out = new FileOutputStream(probe);
            out.write(1);
            out.close();
            return probe.delete();
        } catch (Exception e) {
            return false;
        }
    }

    /** 共享存储可写就用 /sdcard/Download/WebGALStation（Termux 读得到），否则退到应用私有外部目录。 */
    public File getBuildRoot() {
        File shared = new File(Environment.getExternalStorageDirectory(), "Download/WebGALStation");
        if (hasAllFiles() && writable(shared)) {
            return shared;
        }
        File alt = new File(getExternalFilesDir(null), "station");
        writable(alt);
        return alt;
    }

    public boolean termuxCanReadBuildRoot() {
        return getBuildRoot().getAbsolutePath().startsWith("/storage/emulated/0");
    }

    public void requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
        } else {
            requestLegacyStorage();
        }
    }

    private void requestLegacyStorage() {
        if (Build.VERSION.SDK_INT < 30
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    /**
     * 拿回「执行 Termux 命令」权限后的补发。用户在弹窗点允许，构建就自动开始，
     * 不需要再回打包台点一次——真机上这一下最容易让人以为程序坏了。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_TERMUX_RUN) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        String script = pendingRunScript;
        pendingRunScript = null;
        JSONObject r = new JSONObject();
        try {
            if (!granted) {
                r.put("ok", 0);
                r.put("message", "权限没给。要么再点一次「开始构建」重新授权，要么把命令复制到 Termux 里手动跑。");
            } else {
                boolean sent = script != null && Termux.runScript(this, script);
                r.put("ok", sent ? 1 : 0);
                r.put("mode", sent ? "termux" : "manual");
                r.put("command", script == null ? "" : "bash " + script);
                r.put("message", sent ? "权限拿到了，已交给 Termux，构建中。"
                        : "权限有了，但 Termux 没接住；把命令复制过去手动跑一次即可。");
            }
        } catch (Exception ignored) {
        }
        emit(granted ? "buildSent" : "buildDenied", r);
    }

    // ---------------------------------------------------------------- 内置资源释放

    /** 把 kit 脚本释放到共享存储，让 Termux 能看见。 */
    public File deployKit() throws IOException {
        File kitDir = new File(getBuildRoot(), "kit");
        if (!kitDir.exists() && !kitDir.mkdirs()) {
            throw new IOException("无法创建 kit 目录");
        }
        Util.copyAssetTree(getAssets(), "kit", kitDir, null);
        return kitDir;
    }

    /** 首次运行把内置示例工程搬进工程库，省得用户对着空列表发呆。 */
    private void ensureDemo() throws IOException {
        if (store.find("demo") != null) {
            return;
        }
        File dir = new File(new File(getBuildRoot(), "projects"), "demo");
        File gameDir = new File(dir, "game");
        if (!new File(gameDir, "config.txt").isFile()) {
            Util.copyAssetTree(getAssets(), "demo-game", gameDir, null);
        }
        Store.Project p = new Store.Project();
        p.id = "demo";
        p.name = "内置示例 · WebGAL 移动工作站";
        p.dir = dir.getAbsolutePath();
        p.gameDir = gameDir.getAbsolutePath();
        p.gameName = Util.readGameName(gameDir);
        p.source = "demo";
        p.bytes = Util.dirSize(dir);
        p.files = Util.countFiles(dir);
        p.scenes = Util.listScenes(gameDir).size();
        p.createdAt = System.currentTimeMillis();
        store.add(p);
    }

    /**
     * 新手引导工程：首启就躺在工程库里，点「预览」直接进。
     *
     * 它不是一份说明书文本，而是一个真 WebGAL 工程（config.txt + 3 个场景 + 真素材），
     * 跟着走完能知道三栏各干什么、怎么改剧本、怎么打包。用户删了还能用「重建引导」叫回来。
     */
    private void ensureTutorial() throws IOException {
        File dir = new File(new File(getBuildRoot(), "projects"), "tutorial");
        File gameDir = new File(dir, "game");
        if (!new File(gameDir, "config.txt").isFile()) {
            // 素材直接借内置示例的（同一套 bg/figure/bgm/animation），
            // 引导工程只多带 4 个 txt，不然 APK 里同 5.6 MB 素材会存两份
            Util.copyAssetTree(getAssets(), "demo-game", gameDir, null);
            Util.copyAssetTree(getAssets(), "tutorial-game", gameDir, null);
            // demo 的 4 个场景是给示例工程讲故事用的，引导工程用不上，留着只会让人挑错文件
            File sceneDir = new File(gameDir, "scene");
            File[] scenes = sceneDir.listFiles();
            if (scenes != null) {
                for (File f : scenes) {
                    String n = f.getName();
                    if (!("start.txt".equals(n) || n.startsWith("guide_"))) {
                        f.delete();
                    }
                }
            }
        }
        if (store.find("tutorial") != null) {
            return;   // 记录在、目录也在，什么都不用补
        }
        Store.Project p = new Store.Project();
        p.id = "tutorial";
        p.name = "新手引导 · 从这里开始";
        p.dir = dir.getAbsolutePath();
        p.gameDir = gameDir.getAbsolutePath();
        p.gameName = Util.readGameName(gameDir);
        p.source = "tutorial";
        p.bytes = Util.dirSize(dir);
        p.files = Util.countFiles(dir);
        p.scenes = Util.listScenes(gameDir).size();
        p.createdAt = System.currentTimeMillis();
        store.add(p);
    }

    /** 「重建引导」：删了工程也把记录一起清掉，再走一遍 ensureTutorial。 */
    public String apiInstallTutorial() {
        try {
            if (store.find("tutorial") != null) {
                store.remove("tutorial");
            }
            File dir = new File(new File(getBuildRoot(), "projects"), "tutorial");
            if (dir.exists()) {
                deleteTree(dir);
            }
            ensureTutorial();
            return new JSONObject().put("ok", 1).put("id", "tutorial")
                    .put("name", "新手引导 · 从这里开始").toString();
        } catch (Exception e) {
            return errJson("引导工程重建失败：" + e.getMessage());
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    // ---------------------------------------------------------------- JS 桥用到的动作

    public String apiEnv() {
        JSONObject r = new JSONObject();
        try {
            File root = getBuildRoot();
            r.put("ok", 1);
            r.put("sdk", Build.VERSION.SDK_INT);
            r.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            r.put("pkg", getPackageName());
            r.put("version", BuildConfigVersion.VERSION);
            r.put("versionBase", V.BASE + "-" + V.STAGE);
            r.put("versionStartCode", 2601);
            r.put("versionCode", V.CODE);
            r.put("build", V.BUILD);
            r.put("engine", engineVersion());
            r.put("termux", Termux.installed(this));
            r.put("termuxVersion", Termux.version(this) == null ? "" : Termux.version(this));
            r.put("allFiles", hasAllFiles());
            r.put("buildRoot", root.getAbsolutePath());
            r.put("shared", termuxCanReadBuildRoot());
            r.put("kitDir", new File(root, "kit").getAbsolutePath());
            r.put("setupCommand", Termux.setupCommand(new File(root, "kit").getAbsolutePath()));
            r.put("disclaimer", store.disclaimerAccepted());
            r.put("bootstrap", Termux.BOOTSTRAP);
        } catch (Exception e) {
            return errJson("环境读取失败：" + e.getMessage());
        }
        return r.toString();
    }

    /**
     * 首启体检：只回答「现在缺什么」，怎么补由界面决定。
     * 不在这里直接弹系统页——用户没点按钮就被拽去设置页，十个里有九个当场卸载。
     */
    public String apiOnboard() {
        JSONObject r = new JSONObject();
        try {
            File root = getBuildRoot();
            r.put("ok", 1);
            r.put("allFiles", hasAllFiles());
            r.put("termux", Termux.installed(this));
            r.put("termuxVersion", Termux.version(this) == null ? "" : Termux.version(this));
            r.put("termuxRun", Termux.hasRunPermission(this));
            r.put("canInstall", Installer.canInstall(this));
            File apk = findTermuxApk();
            r.put("termuxApk", apk == null ? "" : apk.getAbsolutePath());
            r.put("termuxApkSize", apk == null ? "" : Util.human(apk.length()));
            r.put("buildRoot", root.getAbsolutePath());
            r.put("shared", termuxCanReadBuildRoot());
            r.put("kitDeployed", new File(root, "kit/tools.sh").isFile());
            r.put("sdk", Build.VERSION.SDK_INT);
            r.put("version", V.NAME);
            r.put("versionBase", V.BASE + "-" + V.STAGE);
            // 游戏包的起始版本号也由原生下发，前端别再写死 2601
            r.put("versionStartCode", 2601);
            r.put("versionCode", V.CODE);
        } catch (Exception e) {
            return errJson("首启体检失败：" + e.getMessage());
        }
        return r.toString();
    }

    /**
     * 找一个本机可装的 Termux 安装包。APK 里不塞 108 MB 的 Termux——
     * 谁想把 Termux 一起分发，把 termux.apk 丢到下面任一路径，这里就能装。
     */
    private File findTermuxApk() {
        File sd = Environment.getExternalStorageDirectory();
        File[] cands = new File[]{
                new File(sd, "Download/WebGALStation/termux.apk"),
                new File(sd, "Download/WebGALStation/kit/termux.apk"),
                new File(sd, "Download/termux.apk"),
                new File(getBuildRoot(), "termux.apk"),
        };
        for (int i = 0; i < cands.length; i++) {
            File f = cands[i];
            if (f.isFile() && f.length() > 20L * 1024 * 1024) {
                return f;
            }
        }
        File priv = new File(getExternalFilesDir(null), "termux.apk");
        if (priv.isFile() && priv.length() > 20L * 1024 * 1024) {
            return priv;
        }
        return null;
    }

    /** 首启里一键装 Termux（用本机已有的安装包，经系统安装会话）。 */
    public String apiInstallTermux() {
        JSONObject r = new JSONObject();
        try {
            if (Termux.installed(this)) {
                r.put("ok", 1);
                r.put("already", 1);
                r.put("message", "Termux 已经装了（" + Termux.version(this) + "），别重复装。");
                return r.toString();
            }
            final File apk = findTermuxApk();
            if (apk == null) {
                // 完全体：APK 里就带着 Termux。108 MB 抽取要几秒，扔后台，别卡 JS。
                String[] off = getAssets().list("offline");
                if (off != null && off.length > 0) {
                    if (!Installer.canInstall(this)) {
                        return errJson("还没允许本应用安装应用，先给「安装未知应用」权限");
                    }
                    final File dst = new File(getExternalFilesDir(null), "termux.apk");
                    if (dst.isFile() && dst.length() > 20L * 1024 * 1024) {
                        return startTermuxInstall(dst);
                    }
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            JSONObject o = new JSONObject();
                            try {
                                boolean copied = Util.copyAsset(getAssets(), "offline/termux.apk", dst);
                                if (!copied || dst.length() < 20L * 1024 * 1024) {
                                    o.put("ok", 0);
                                    o.put("message", "APK 里的 Termux 释放失败");
                                    emit("termuxInstall", o);
                                    return;
                                }
                                Installer.install(StationActivity.this, dst, new Installer.Callback() {
                                    @Override
                                    public void onResult(boolean ok, String message) {
                                        JSONObject x = new JSONObject();
                                        try {
                                            x.put("ok", ok ? 1 : 0);
                                            x.put("message", message);
                                        } catch (Exception ignored) {
                                        }
                                        emit("termuxInstall", x);
                                    }
                                });
                            } catch (Exception e) {
                                try {
                                    o.put("ok", 0);
                                    o.put("message", "释放 Termux 失败：" + e.getMessage());
                                } catch (Exception ignored) {
                                }
                                emit("termuxInstall", o);
                            }
                        }
                    }, "termux-extract").start();
                    r.put("ok", 1);
                    r.put("sending", 1);
                    r.put("message", "正在从 APK 里释放 Termux（108 MB），释放完会弹系统安装器…");
                    return r.toString();
                }
                r.put("ok", 0);
                r.put("needDownload", 1);
                r.put("message", "本机没找到 Termux 安装包。把 termux.apk 放到 "
                        + "Download/WebGALStation/ 下再点一次，或去 F-Droid 装。");
                return r.toString();
            }
            return startTermuxInstall(apk);
        } catch (Exception e) {
            return errJson("拉起 Termux 安装失败：" + e.getMessage());
        }
    }

    private String startTermuxInstall(final File apk) {
        JSONObject r = new JSONObject();
        try {
            if (!Installer.canInstall(this)) {
                return errJson("还没允许本应用安装应用，先给「安装未知应用」权限");
            }
            Installer.install(this, apk, new Installer.Callback() {
                @Override
                public void onResult(boolean ok, final String message) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("ok", ok ? 1 : 0);
                        o.put("message", message);
                    } catch (Exception ignored) {
                    }
                    emit("termuxInstall", o);
                }
            });
            r.put("ok", 1);
            r.put("sending", 1);
            r.put("message", "正在把 Termux 交给系统安装器…");
        } catch (Exception e) {
            return errJson("拉起 Termux 安装失败：" + e.getMessage());
        }
        return r.toString();
    }

    /** 完全体（-full 包）才有的离线工具链：Termux + 54 个 deb + android.jar。 */
    public String apiOffline() {
        JSONObject r = new JSONObject();
        try {
            String[] debs = getAssets().list("offline/debs");
            int n = debs == null ? 0 : debs.length;
            File off = new File(getBuildRoot(), "offline");
            r.put("ok", 1);
            r.put("bundle", n > 0 ? 1 : 0);
            r.put("debCount", n);
            r.put("extracted", new File(off, "debs").isDirectory() ? 1 : 0);
            r.put("toolsReady", new File(off, ".tools-installed").isFile() ? 1 : 0);
            r.put("dir", off.getAbsolutePath());
            r.put("command", "bash " + off.getAbsolutePath() + "/install_offline.sh");
            r.put("shared", termuxCanReadBuildRoot());
        } catch (Exception e) {
            return errJson("离线包状态读取失败：" + e.getMessage());
        }
        return r.toString();
    }

    /**
     * 把 assets/offline 释放到共享存储。250 MB 走 FUSE 很慢，必须放到后台线程，
     * 否则 WebView 的 JS 调用会把这整段卡成 ANR。
     */
    public String apiOfflineInstall() {
        JSONObject r = new JSONObject();
        try {
            final String[] debs = getAssets().list("offline/debs");
            if (debs == null || debs.length == 0) {
                return errJson("这一版不含离线工具链（是 lite 包）。换 -full 包再来。");
            }
            final int expected = debs.length;
            final File off = new File(getBuildRoot(), "offline");
            deployKit();

            // 已经释放过就直接回命令，别每次重拷 160 MB —— 真机上这段 FUSE 写入能卡好几分钟。
            File marker = new File(off, ".bundle-ready");
            File debDir = new File(off, "debs");
            int have = 0;
            File[] existed = debDir.listFiles();
            if (existed != null) {
                for (File f : existed) {
                    if (f.isFile() && f.getName().endsWith(".deb")) have++;
                }
            }
            boolean ready = marker.isFile() && have >= expected
                    && new File(off, "install_offline.sh").isFile()
                    && new File(off, "android.jar").isFile();
            if (ready) {
                r.put("ok", 1);
                r.put("sending", 0);
                r.put("cached", 1);
                r.put("debCount", have);
                r.put("command", "bash " + off.getAbsolutePath() + "/install_offline.sh");
                r.put("message", "离线包已在位，直接去 Termux 跑命令。");
                return r.toString();
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    JSONObject o = new JSONObject();
                    try {
                        File debDir = new File(off, "debs");
                        if (!debDir.isDirectory()) debDir.mkdirs();
                        int copied = 0;
                        // 只拷工具链本体；termux.apk 早就从 assets 单独抽出去装过了，不再占 113 MB。
                        String[] tops = getAssets().list("offline");
                        if (tops != null) {
                            for (String name : tops) {
                                if (name.equals("debs") || name.equals("termux.apk")) continue;
                                Util.copyAsset(getAssets(), "offline/" + name, new File(off, name));
                                copied++;
                            }
                        }
                        for (int i = 0; i < debs.length; i++) {
                            String name = debs[i];
                            if (Util.copyAsset(getAssets(), "offline/debs/" + name, new File(debDir, name))) {
                                copied++;
                            }
                            if (i % 10 == 0) {
                                emitProgress("释放离线包", Math.min(99, (i + 1) * 100 / expected),
                                        (i + 1) + " / " + expected + " 个 deb");
                            }
                        }
                        Util.writeText(new File(off, ".bundle-ready"), expected + "\n");
                        o.put("ok", 1);
                        o.put("files", copied);
                        o.put("debCount", expected);
                        o.put("command", "bash " + off.getAbsolutePath() + "/install_offline.sh");
                        o.put("message", "离线包已释放（" + expected + " 个 deb），去 Termux 跑一次，全程不联网。");
                    } catch (Exception e) {
                        try {
                            o.put("ok", 0);
                            o.put("message", "释放失败：" + e.getMessage());
                        } catch (Exception ignored) {
                        }
                    }
                    emit("offlineReady", o);
                }
            }, "offline-extract").start();
            r.put("ok", 1);
            r.put("sending", 1);
            r.put("debCount", expected);
            r.put("message", "正在释放离线包（约 160 MB，只释放一次），完了会自动把命令塞进剪贴板并打开 Termux。");
        } catch (Exception e) {
            return errJson("释放离线包失败：" + e.getMessage());
        }
        return r.toString();
    }

    private String engineVersion() {
        String raw = Util.readAsset(getAssets(), "engine/webgal-engine.json");
        if (raw == null) {
            return "?";
        }
        try {
            return new JSONObject(raw).optString("version", "?");
        } catch (Exception e) {
            return "?";
        }
    }

    public String apiProjects() {
        JSONObject r = new JSONObject();
        try {
            List<Store.Project> list = store.list();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                arr.put(list.get(i).toUiJson());
            }
            r.put("ok", 1);
            r.put("items", arr);
            // 清单坏过一回就从磁盘重建，前端要能告诉用户「工程是从目录里捞回来的」
            r.put("recovered", store.wasRecovered() ? 1 : 0);
        } catch (Exception e) {
            return errJson("工程列表读取失败");
        }
        return r.toString();
    }

    /** 新建一个空白 WebGAL 工程：config + 一句开场白 + 资源目录，引擎直接能跑。 */
    public String apiCreateProject(String name, String starter) {
        String title = name == null ? "" : name.trim();
        if (title.isEmpty()) {
            title = "未命名工程";
        }
        try {
            File projectsDir = new File(getBuildRoot(), "projects");
            if (!projectsDir.exists() && !projectsDir.mkdirs()) {
                throw new IOException("无法创建工程库目录");
            }
            String base = slug(title);
            String id = base;
            File dir = new File(projectsDir, id);
            int n = 2;
            while (dir.exists()) {
                id = base + "-" + n;
                dir = new File(projectsDir, id);
                n++;
            }
            File gameDir = new File(dir, "game");
            File sceneDir = new File(gameDir, "scene");
            if (!sceneDir.exists() && !sceneDir.mkdirs()) {
                throw new IOException("无法创建工程目录");
            }
            for (String sub : new String[]{"background", "bgm", "figure", "animation"}) {
                File d = new File(gameDir, sub);
                if (!d.exists()) {
                    d.mkdirs();
                }
            }
            // 动画表从内置示例搬一份，省得引擎少文件报错
            Util.copyAssetTree(getAssets(), "demo-game/animation", new File(gameDir, "animation"), null);

            String cfg = "Game_name:" + title + ";\n"
                    + "Game_key:" + id + ";\n"
                    + "Enable_Appreciation:true;\n"
                    + "Enable_Continue:true;\n"
                    + "Enable_flowchart:false;\n";
            Util.writeText(new File(gameDir, "config.txt"), cfg);

            String line = starter == null ? "" : starter.trim();
            if (line.isEmpty()) {
                line = "这是你的第一句台词，直接改这一行就能开始。";
            }
            String scene = "旁白:" + line + ";\n";
            Util.writeText(new File(sceneDir, "start.txt"), scene);

            Store.Project p = new Store.Project();
            p.id = id;
            p.name = title;
            p.dir = dir.getAbsolutePath();
            p.gameDir = gameDir.getAbsolutePath();
            p.gameName = Util.readGameName(gameDir);
            p.source = "new";
            p.bytes = Util.dirSize(dir);
            p.files = Util.countFiles(dir);
            p.scenes = Util.listScenes(gameDir).size();
            p.createdAt = System.currentTimeMillis();
            store.add(p);

            JSONObject r = new JSONObject();
            r.put("ok", 1);
            r.put("id", id);
            r.put("project", p.toUiJson());
            return r.toString();
        } catch (Exception e) {
            return errJson("新建工程失败：" + e.getMessage());
        }
    }

    /** 工程目录名只留 ASCII 安全字符；中文名走时间戳，反正列表里显示的是 title。 */
    private static String slug(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        String s = sb.toString();
        if (s.length() > 24) {
            s = s.substring(0, 24);
        }
        if (s.isEmpty() || s.equals("_")) {
            return "game-" + (System.currentTimeMillis() / 1000L);
        }
        return s;
    }

    public void apiPickProject() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            emitError("打不开文件选择器：" + e.getMessage());
        }
    }

    /**
     * 素材导入（图片/音频/视频多选）。
     * 这是原版缺得最狠的一块：素材栏只能看内置示例的文件，玩家自己的背景和立绘根本进不来。
     */
    public void apiPickAssets(String pid, String cat) {
        if (pid == null || pid.isEmpty()) {
            emitError("先在工程库选一个工程，再导入素材");
            return;
        }
        pendingAssetPid = pid;
        pendingAssetCat = normalizeAssetCat(cat);
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_ASSETS);
        } catch (Exception e) {
            emitError("打不开文件选择器：" + e.getMessage());
        }
    }

    /** 系统文件夹选择器在部分 ROM 上会被限制（Android 11 禁选根目录/Download），ZIP 是兜底。 */
    public void apiPickProjectZip() {
        // 国产 ROM 的 DocumentsUI 会把“下载”指到一个空目录，真正的 /sdcard/Download
        // 反而点不进去。这里先用应用自己的“所有文件访问”权限扫一遍常见目录，
        // 扫到 ZIP 就直接弹应用内列表，绕开系统文件选择器；扫不到才退回系统选择器。
        final List<File> zips = new ArrayList<File>();
        try {
            collectZips(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 2, zips);
            File root = getBuildRoot();
            if (root != null) collectZips(root, 2, zips);
        } catch (Exception ignored) {
        }
        if (!zips.isEmpty()) {
            Collections.sort(zips, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            String[] labels = new String[zips.size()];
            for (int i = 0; i < zips.size(); i++) {
                File f = zips.get(i);
                labels[i] = f.getName() + "   " + (f.length() / 1024 / 1024) + " MB";
            }
            new AlertDialog.Builder(this)
                    .setTitle("选一个工程 ZIP")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            startZipImport(Uri.fromFile(zips.get(which)));
                        }
                    })
                    .setNeutralButton("系统选择器", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            openZipPickerIntent();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        openZipPickerIntent();
    }

    /** 递归收 .zip，深度有限，别把整张卡翻一遍。 */
    private void collectZips(File dir, int depth, List<File> out) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && depth > 0) collectZips(f, depth - 1, out);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip") && f.length() > 1000) {
                if (!out.contains(f)) out.add(f);
            }
        }
    }

    private void openZipPickerIntent() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // 不能只筛 application/zip：部分 ROM 的文件选择器会把 zip 归到“大型文件/其他”，
            // 直接筛 MIME 反而一个都不显示。放开 */*，导入时再校验 ZIP 结构。
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_PROJECT_ZIP);
        } catch (Exception e) {
            emitError("打不开文件选择器：" + e.getMessage());
        }
    }

    private static String normalizeAssetCat(String cat) {
        if (cat == null) return "background";
        String c = cat.trim().toLowerCase(Locale.ROOT);
        if (c.equals("background") || c.equals("figure") || c.equals("bgm")
                || c.equals("vocal") || c.equals("video") || c.equals("animation")
                || c.equals("other")) {
            return c;
        }
        return "background";
    }

    private void startAssetImport(final List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            emitError("没选到文件");
            return;
        }
        if (assetImportThread != null && assetImportThread.isAlive()) {
            emitError("上一批素材还在复制，等它跑完再选");
            return;
        }
        final String pid = pendingAssetPid;
        final String cat = pendingAssetCat;
        final Store.Project p = store.find(pid);
        if (p == null) {
            emitError("工程不存在了，回工作台刷新一下");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject r = new JSONObject();
                int okCount = 0;
                String lastErr = "";
                try {
                    File gameDir = new File(p.gameDir);
                    File dest = new File(gameDir, cat);
                    if (!dest.isDirectory() && !dest.mkdirs()) {
                        throw new IOException("建不了素材目录：" + dest.getAbsolutePath());
                    }
                    for (int i = 0; i < uris.size() && i < 80; i++) {
                        Uri u = uris.get(i);
                        InputStream in = null;
                        OutputStream out = null;
                        try {
                            String name = displayName(u);
                            name = sanitizeAssetName(name, u);
                            File child = uniqueChild(dest, name);
                            in = getContentResolver().openInputStream(u);
                            if (in == null) {
                                lastErr = "读不到 " + name;
                                continue;
                            }
                            out = new FileOutputStream(child);
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                            }
                            okCount++;
                            if (okCount % 3 == 0) {
                                emitProgress("导入素材", Math.min(99, okCount * 100 / uris.size()),
                                        okCount + " / " + uris.size());
                            }
                        } catch (Exception e) {
                            lastErr = e.getMessage();
                            Log.w(Util.TAG, "asset copy failed", e);
                        } finally {
                            Util.close(in);
                            Util.close(out);
                        }
                    }
                    if (okCount > 0) {
                        p.bytes = Util.dirSize(new File(p.dir));
                        p.files = Util.countFiles(new File(p.dir));
                    }
                    r.put("ok", okCount > 0 ? 1 : 0);
                    r.put("count", okCount);
                    r.put("total", uris.size());
                    r.put("category", cat);
                    r.put("project", p.id);
                    r.put("message", okCount > 0
                            ? ("已导入 " + okCount + " 个素材到 " + cat)
                            : ("一个都没导入：" + lastErr));
                } catch (Exception e) {
                    try {
                        r.put("ok", 0);
                        r.put("count", okCount);
                        r.put("category", cat);
                        r.put("message", "导入素材失败：" + e.getMessage());
                    } catch (Exception ignored) {
                    }
                }
                emit("assetsImported", r);
            }
        }, "asset-import");
        assetImportThread = t;
        t.start();
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String s = c.getString(idx);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {
        } finally {
            Util.close(c);
        }
        String last = uri.getLastPathSegment();
        return last == null ? "asset-" + System.currentTimeMillis() : last;
    }

    private String sanitizeAssetName(String name, Uri uri) {
        String n = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        n = n.replaceAll("[\\\\:*?\"<>|]", "_");
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "asset-" + System.currentTimeMillis();
        }
        if (!n.contains(".")) {
            String mime = null;
            try {
                mime = getContentResolver().getType(uri);
            } catch (Exception ignored) {
            }
            String ext = ".png";
            if (mime != null) {
                if (mime.startsWith("audio/mpeg")) ext = ".mp3";
                else if (mime.startsWith("audio/ogg")) ext = ".ogg";
                else if (mime.startsWith("audio/wav") || mime.startsWith("audio/x-wav")) ext = ".wav";
                else if (mime.startsWith("audio/")) ext = ".mp3";
                else if (mime.startsWith("video/mp4")) ext = ".mp4";
                else if (mime.startsWith("video/")) ext = ".mp4";
                else if (mime.equals("image/jpeg")) ext = ".jpg";
                else if (mime.equals("image/webp")) ext = ".webp";
                else if (mime.equals("image/gif")) ext = ".gif";
            }
            n = n + ext;
        }
        if (n.length() > 80) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 80 - ext.length()) + ext;
        }
        return n;
    }

    private static File uniqueChild(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File x = new File(dir, base + "-" + i + ext);
            if (!x.exists()) return x;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    public String apiDeleteProject(String id) {
        try {
            Store.Project p = store.find(id);
            if (p != null) {
                File dir = new File(p.dir);
                File projectsDir = new File(getBuildRoot(), "projects");
                String base = projectsDir.getAbsolutePath();
                if (dir.getAbsolutePath().startsWith(base)) {
                    Util.deleteTree(dir);
                }
                store.remove(id);
            }
            return okJson();
        } catch (Exception e) {
            return errJson("删除失败：" + e.getMessage());
        }
    }

    /**
     * 工作台里的「预览舞台」：只换端口、返回一个 URL，让界面把它塞进内嵌 iframe。
     * 不再把整个 WebView 导航到引擎——那样一进预览就变成游戏画面，想回工作台只能重启 app。
     */
    public String apiStage(String id) {
        JSONObject r = new JSONObject();
        try {
            Store.Project p = store.find(id);
            if (p == null) {
                return errJson("找不到这个工程");
            }
            File gameDir = new File(p.gameDir);
            if (!new File(gameDir, "config.txt").isFile()) {
                return errJson("工程里没有 config.txt，先检查是不是导错了目录");
            }
            server.mount("/game/", new LocalServer.FileSource(gameDir));
            int newPort = server.restart();
            port = newPort;
            previewing = true;
            r.put("ok", 1);
            r.put("url", "http://127.0.0.1:" + newPort + "/");
            r.put("project", p.name == null ? "" : p.name);
            r.put("gameDir", gameDir.getAbsolutePath());
            Log.d(Util.TAG, "stage url http://127.0.0.1:" + newPort + "/ 工程=" + gameDir);
        } catch (Exception e) {
            return errJson("预览启动失败：" + e.getMessage());
        }
        return r.toString();
    }

    // ---------------------------------------------------------------- 工作场景（编辑器）

    private File gameDirOf(String id) throws IOException {
        Store.Project p = store.find(id);
        if (p == null) {
            throw new IOException("找不到这个工程");
        }
        File gameDir = new File(p.gameDir);
        if (!new File(gameDir, "config.txt").isFile()) {
            throw new IOException("工程里没有 config.txt，先检查是不是导错了目录");
        }
        return gameDir;
    }

    /** 编辑器一次性把工程摸清：场景列表、资源目录、配置原文。 */
    public String apiWorkspace(String id) {
        JSONObject r = new JSONObject();
        try {
            File gameDir = gameDirOf(id);
            JSONArray scenes = new JSONArray();
            File sceneDir = new File(gameDir, "scene");
            File[] files = sceneDir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : files) {
                    if (!f.isFile() || !f.getName().endsWith(".txt")) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("size", f.length());
                    o.put("lines", countLines(f));
                    scenes.put(o);
                }
            }
            JSONObject assets = new JSONObject();
            assets.put("background", listNames(new File(gameDir, "background")));
            assets.put("bgm", listNames(new File(gameDir, "bgm")));
            assets.put("figure", listNames(new File(gameDir, "figure")));
            assets.put("animation", listNames(new File(gameDir, "animation")));
            assets.put("vocal", listNames(new File(gameDir, "vocal")));
            assets.put("video", listNames(new File(gameDir, "video")));
            assets.put("other", listNames(new File(gameDir, "other")));
            r.put("ok", 1);
            r.put("scenes", scenes);
            r.put("assets", assets);
            r.put("config", Util.readText(new File(gameDir, "config.txt")));
            r.put("gameName", Util.readGameName(gameDir));
        } catch (Exception e) {
            return errJson("读取工程失败：" + e.getMessage());
        }
        return r.toString();
    }

    /**
     * 给素材改名。玩家点素材旁边的小铅笔，原生弹一个输入框；
     * 不改扩展名（除非玩家自己写），不让填路径，不和现有文件重名。
     */
    public String apiRenameAsset(final String id, final String cat, final String oldName) {
        try {
            final File dir = new File(gameDirOf(id), normalizeAssetCat(cat));
            final File old = new File(dir, oldName == null ? "" : oldName);
            if (!old.isFile()) {
                return errJson("素材不存在：" + (oldName == null ? "" : oldName));
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final EditText input = new EditText(StationActivity.this);
                    input.setSingleLine(true);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setText(old.getName());
                    input.setSelectAllOnFocus(true);
                    new AlertDialog.Builder(StationActivity.this)
                            .setTitle("重命名素材")
                            .setMessage("只改文件名，扩展名不变；不能带 / 或 ..")
                            .setView(input)
                            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    doRenameAsset(id, cat, old, input.getText().toString());
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
            JSONObject r = new JSONObject();
            r.put("ok", 1);
            r.put("pending", 1);
            return r.toString();
        } catch (Exception e) {
            return errJson("重命名失败：" + e.getMessage());
        }
    }

    private void doRenameAsset(String id, String cat, File old, String raw) {
        JSONObject o = new JSONObject();
        try {
            String name = raw == null ? "" : raw.trim();
            name = name.replace('/', '_').replace('\\', '_').replaceAll("[\\\\:*?\"<>|]", "_");
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                throw new IOException("名字不能为空");
            }
            String oldName = old.getName();
            int dot = oldName.lastIndexOf('.');
            String ext = dot > 0 ? oldName.substring(dot) : "";
            if (!name.contains(".") && !ext.isEmpty()) {
                name = name + ext;
            }
            if (name.length() > 80) {
                int d = name.lastIndexOf('.');
                String e = d > 0 ? name.substring(d) : "";
                name = name.substring(0, 80 - e.length()) + e;
            }
            File dest = new File(old.getParentFile(), name);
            if (dest.getAbsolutePath().equals(old.getAbsolutePath())) {
                o.put("ok", 1);
                o.put("same", 1);
                o.put("message", "名字没变");
            } else if (dest.exists()) {
                o.put("ok", 0);
                o.put("message", "已经有同名文件了：" + name);
            } else if (!old.renameTo(dest)) {
                o.put("ok", 0);
                o.put("message", "系统不让改，可能文件被占用");
            } else {
                o.put("ok", 1);
                o.put("old", oldName);
                o.put("name", name);
                o.put("cat", normalizeAssetCat(cat));
                o.put("pid", id);
                o.put("message", "已改名：" + oldName + " → " + name);
            }
        } catch (Exception e) {
            try {
                o.put("ok", 0);
                o.put("message", "改名失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        emit("assetRenamed", o);
    }

    private static int countLines(File f) {
        String t = Util.readText(f);
        if (t == null || t.isEmpty()) {
            return 1;
        }
        int n = 1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static JSONArray listNames(File dir) {
        JSONArray arr = new JSONArray();
        File[] fs = dir.listFiles();
        if (fs == null) {
            return arr;
        }
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        int n = 0;
        for (File f : fs) {
            if (f.isFile() && n < 400) {
                try {
                    arr.put(f.getName());
                    n++;
                } catch (Exception ignored) {
                }
            }
        }
        return arr;
    }

    /** rel 一律相对 game 目录，例如 scene/start.txt、config.txt。 */
    private File resolveInGame(File gameDir, String rel) throws IOException {
        String clean = rel == null ? "" : rel.replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.contains("../") || clean.equals("..")) {
            throw new IOException("路径不合法");
        }
        File f = new File(gameDir, clean);
        String base = gameDir.getCanonicalPath();
        String target = f.getCanonicalPath();
        if (!target.equals(base) && !target.startsWith(base + File.separator)) {
            throw new IOException("路径越界");
        }
        return f;
    }

    public String apiReadText(String id, String rel) {
        JSONObject r = new JSONObject();
        File f;
        try {
            File gameDir = gameDirOf(id);
            f = resolveInGame(gameDir, rel);
        } catch (Exception e) {
            return errJson("读文件失败：" + e.getMessage());
        }
        if (!f.isFile()) {
            return errJson("文件不存在：" + rel);
        }
        String text = Util.readText(f);
        if (text == null) {
            // 文件在那儿却读不出来：别回 ok=1 的 null，前端会当成「空剧本」继续渲染
            return errJson("文件读不出来（可能被占用或权限丢失）：" + rel);
        }
        try {
            r.put("ok", 1);
            r.put("rel", rel);
            r.put("text", text);
        } catch (Exception e) {
            return errJson("读文件失败：" + e.getMessage());
        }
        return r.toString();
    }

    public String apiWriteText(String id, String rel, String text) {
        try {
            File gameDir = gameDirOf(id);
            File f = resolveInGame(gameDir, rel);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("建不出目录");
            }
            // 写盘失败必须回 ok:0——这是剧本保存的唯一入口，静默成功等于丢稿
            if (!Util.writeText(f, text == null ? "" : text)) {
                Log.w(Util.TAG, "writeText failed: " + f.getAbsolutePath());
                return errJson("写入失败：存储空间不足、权限被收回，或文件被别的程序占用");
            }
            return okJson();
        } catch (Exception e) {
            return errJson("写文件失败：" + e.getMessage());
        }
    }

    public String apiNewScene(String id, String name) {
        try {
            File gameDir = gameDirOf(id);
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) {
                n = "untitled";
            }
            if (!n.endsWith(".txt")) {
                n = n + ".txt";
            }
            if (n.contains("/") || n.contains("\\") || n.contains("..")) {
                return errJson("场景名不能带路径");
            }
            File f = new File(new File(gameDir, "scene"), n);
            if (f.exists()) {
                return errJson("已经有同名场景了");
            }
            if (!Util.writeText(f, "旁白:新场景，从这里开始写。;\n")) {
                return errJson("写不进场景文件：存储空间不足或权限被收回");
            }
            refreshProjectStats(id);
            JSONObject r = new JSONObject();
            r.put("ok", 1);
            r.put("name", n);
            return r.toString();
        } catch (Exception e) {
            return errJson("新建场景失败：" + e.getMessage());
        }
    }

    public String apiDeleteScene(String id, String name) {
        try {
            File gameDir = gameDirOf(id);
            if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
                return errJson("场景名不合法");
            }
            File f = new File(new File(gameDir, "scene"), name);
            if (f.isFile()) {
                f.delete();
            }
            refreshProjectStats(id);
            return okJson();
        } catch (Exception e) {
            return errJson("删除场景失败：" + e.getMessage());
        }
    }

    /** 改完文件后把列表里的容量/场景数对齐，免得面板上数字骗人。 */
    private void refreshProjectStats(String id) {
        Store.Project p = store.find(id);
        if (p == null) {
            return;
        }
        p.bytes = Util.dirSize(new File(p.dir));
        p.files = Util.countFiles(new File(p.dir));
        p.scenes = Util.listScenes(new File(p.gameDir)).size();
        store.add(p);
    }

    public void apiPreview(String id) {
        Store.Project p = store.find(id);
        if (p == null) {
            emitError("找不到这个工程");
            return;
        }
        File gameDir = new File(p.gameDir);
        if (!new File(gameDir, "config.txt").isFile()) {
            emitError("工程里没有 config.txt，先检查是不是导错了目录");
            return;
        }
        try {
            server.mount("/game/", new LocalServer.FileSource(gameDir));
            final int newPort = server.restart();
            port = newPort;
            previewing = true;
            // JS 桥跑在 WebView 自己的线程上，loadUrl 必须回主线程，否则会被 WebView 直接拒绝
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    applyImmersive(true);
                    web.loadUrl("http://127.0.0.1:" + newPort + "/");
                    Log.d(Util.TAG, "预览载入 http://127.0.0.1:" + newPort + "/ 工程=" + gameDir);
                }
            });
        } catch (Exception e) {
            emitError("预览启动失败：" + e.getMessage());
        }
    }

    public void apiExitPreview() {
        previewing = false;
        final int p = port;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                applyImmersive(false);
                web.loadUrl("http://127.0.0.1:" + p + "/ui/index.html");
            }
        });
    }

    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    public void apiPrepareBuild(final String projectId, final String optionsJson) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Store.Project p = store.find(projectId);
                    if (p == null) {
                        emitError("工程不存在");
                        return;
                    }
                    BuildKit.Options opt = parseOptions(optionsJson, p);
                    // 同一个工程重复打包时，versionCode 不能原地踏步——安卓会拿
                    // 「已安装相同版本」把安装拦掉。这里按上次构建号自动 +1。
                    if (p.lastVersionCode > 0 && opt.versionCode <= p.lastVersionCode) {
                        opt.versionCode = p.lastVersionCode + 1;
                        Log.i(Util.TAG, "版本号自动递增到 " + opt.versionCode);
                    }
                    // versionName 也得跟着动。vivo / 小米的拦截页显示的是 versionName，
                    // 只改 code 不改名字，玩家照样看到「已安装相同版本」。
                    // 名字规则跟着 V.BASE / V.STAGE 走：1.1.6-beta.<build>，代际 2618 只进 code。
                    // 不按 code 大小开条件了：第一个包（2601）以前会被判成「没到 2601」跳过改名，
                    // 于是前端默认值 1.1.5-beta 原样进 job.env，用户在拦截页看到的是旧号。
                    // 名字只认 V.BASE，前端给什么都不算数——这是唯一口径。
                    String verBase = opt.versionName == null ? "" : opt.versionName.trim();
                    // 把上一轮攒上去的 .2 / .2.3 尾巴收掉，只留 主.次.批 + 后缀
                    verBase = verBase.replaceFirst("^(\\d+\\.\\d+\\.\\d+)(-[a-zA-Z]+)?(\\.[0-9]{1,4})+$", "$1$2");
                    if (!verBase.matches("^[0-9]+\\.\\d+\\.\\d+.*") || !verBase.startsWith(V.BASE)) {
                        verBase = V.BASE + "-" + V.STAGE;
                    }
                    // 游戏包的 code 从 2600 起，工作站的 code 是 26180000+build，
                    // 两种基数都要能换算成同一个对外序号
                    int buildNo = opt.versionCode >= 26180000
                            ? opt.versionCode % 10000 : opt.versionCode - 2600;
                    opt.versionName = verBase + "." + buildNo;
                    store.setLastVersionCode(p.id, opt.versionCode);
                    emitProgress("准备中", 1, "检查工程");
                    // 每次准备都把内置脚本重刷一遍：升级安装后旧脚本会赖在共享存储里，
                    // 不刷就会出现"代码改了、端上还跑旧脚本"的鬼故事。
                    deployKit();
                    File base = BuildKit.prepare(StationActivity.this, p, opt, getBuildRoot(),
                            new BuildKit.Progress() {
                                @Override
                                public void onProgress(String stage, int percent, String detail) {
                                    emitProgress(stage, percent, detail);
                                }
                            });
                    JSONObject r = new JSONObject();
                    r.put("ok", 1);
                    r.put("buildId", base.getName());
                    r.put("path", base.getAbsolutePath());
                    r.put("size", Util.human(Util.dirSize(new File(base, "android"))));
                    r.put("pkg", opt.pkg);
                    r.put("appName", opt.appName);
                    r.put("versionName", opt.versionName);
                    r.put("versionCode", opt.versionCode);
                    emit("prepared", r);
                } catch (Exception e) {
                    Log.w(Util.TAG, "prepare failed", e);
                    emitError("拼装构建工程失败：" + e.getMessage());
                }
            }
        }, "prepare-build");
        prepareThread = t;
        t.start();
    }

    private BuildKit.Options parseOptions(String json, Store.Project p) {
        BuildKit.Options opt = new BuildKit.Options();
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            opt.appName = o.optString("appName", defaultAppName(p));
            opt.pkg = o.optString("pkg", defaultPkg(p));
            opt.versionName = o.optString("versionName", V.NAME);
            opt.versionCode = o.optInt("versionCode", V.CODE);
            opt.orientation = o.optString("orientation", "landscape");
            opt.badge = o.optBoolean("badge", true);
            // 签名：默认走内置调试签名，玩家想自己测发布就切 custom / new
            opt.signMode = o.optString("signMode", "debug");
            String ks = o.optString("ksPath", "");
            if (!ks.isEmpty() && !new File(ks).isAbsolute()) {
                ks = new File(getBuildRoot(), ks).getAbsolutePath();
            }
            opt.ksPath = ks;
            String alias = o.optString("ksAlias", "webgal");
            opt.ksAlias = alias.isEmpty() ? "webgal" : alias;
            String sp = o.optString("ksStorePass", "android");
            opt.ksStorePass = sp.isEmpty() ? "android" : sp;
            String kp = o.optString("ksKeyPass", "");
            opt.ksKeyPass = kp.isEmpty() ? opt.ksStorePass : kp;
            String icon = o.optString("iconPath", "");
            if (!icon.isEmpty() && new File(icon).isFile()) {
                File kitIcons = new File(getBuildRoot(), "icons");
                if (!kitIcons.exists()) {
                    kitIcons.mkdirs();
                }
                File copy = new File(kitIcons, "icon-" + System.currentTimeMillis() + ".img");
                InputStream in = new FileInputStream(icon);
                OutputStream out = new FileOutputStream(copy);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    Util.close(in);
                    Util.close(out);
                }
                opt.iconPath = copy.getAbsolutePath();
                // 选图时的 picked-*.img 已经拷进构建目录，原件留着只会堆垃圾
                File[] olds = kitIcons.listFiles();
                if (olds != null) {
                    for (int i = 0; i < olds.length; i++) {
                        if (olds[i].getName().startsWith("picked-")) {
                            olds[i].delete();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 以前这里只 Log.w 然后拿半份默认值继续跑，用户填的应用名/签名悄悄没了还没提示
            Log.w(Util.TAG, "parse options failed", e);
            emitError("构建参数解析失败，读不出来的字段先用默认值顶上：" + e.getMessage());
        }
        if (!Util.validPackage(opt.pkg)) {
            opt.pkg = defaultPkg(p);
        }
        return opt;
    }

    private String defaultAppName(Store.Project p) {
        if (p.gameName != null && !p.gameName.isEmpty()) {
            return p.gameName;
        }
        return p.name == null || p.name.isEmpty() ? "WebGAL 游戏" : p.name;
    }

    private String defaultPkg(Store.Project p) {
        // 中文名不能直接进包名：取可用的 ASCII 前缀，再加一个稳定的数字后缀。
        // 别用 hashCode，模拟器和真机不一定给同一个值。
        String name = defaultAppName(p);
        StringBuilder ascii = new StringBuilder();
        long sum = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            char lower = Character.toLowerCase(c);
            if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')) {
                if (ascii.length() < 12) {
                    ascii.append(lower);
                }
            }
            sum = (sum * 31 + (int) c) % 1000000007L;
        }
        String prefix = ascii.length() == 0 ? "game" : ascii.toString();
        if (!Character.isLetter(prefix.charAt(0))) {
            prefix = "g" + prefix;
        }
        return "com.zhixitai.vn." + prefix + sum;
    }

    /**
     * buildId 来自桥参数，必须和工程文件一样做 canonical 校验：
     * 预览 iframe 里的脚本也能调桥，不校验就等于把整个构建根目录敞开。
     */
    private File resolveBuildDir(String buildId) throws IOException {
        String id = buildId == null ? "" : buildId.trim();
        if (id.isEmpty() || id.equals("..") || id.startsWith(".")
                || id.contains("/") || id.contains("\\")) {
            throw new IOException("构建号不合法");
        }
        File root = new File(getBuildRoot(), "build");
        File base = new File(root, id);
        String rb = root.getCanonicalPath();
        String tb = base.getCanonicalPath();
        if (!tb.equals(rb) && !tb.startsWith(rb + File.separator)) {
            throw new IOException("构建目录越界");
        }
        return base;
    }

    public String apiBuildState(String buildId) {
        JSONObject r = new JSONObject();
        try {
            File base = resolveBuildDir(buildId);
            if (!base.isDirectory()) {
                return errJson("构建目录不存在");
            }
            String status = Util.readText(new File(base, "status.json"));
            String log = Util.readText(new File(base, "build.log"));
            r.put("ok", 1);
            r.put("state", "prepared");
            r.put("log", tail(log, 6000));
            r.put("apk", "");
            r.put("size", "");
            r.put("seconds", 0);
            if (status != null) {
                try {
                    JSONObject s = new JSONObject(status);
                    r.put("state", s.optString("state", "prepared"));
                    r.put("apk", s.optString("apk", ""));
                    r.put("size", s.optString("size", ""));
                    r.put("seconds", s.optInt("seconds", 0));
                    if (s.has("reason")) {
                        r.put("reason", s.optString("reason", ""));
                    }
                    if (s.has("warn")) {
                        r.put("warn", s.optString("warn", ""));
                    }
                } catch (Exception badJson) {
                    // 状态文件本身写坏了：把原文尾巴当理由透出去，别只回一句「读取失败」
                    Log.w(Util.TAG, "status.json 解析失败", badJson);
                    r.put("reason", "构建状态文件写坏了：" + tail(status, 400));
                }
            }
            String apk = r.optString("apk", "");
            if (apk.isEmpty()) {
                File guess = new File(base, "out");
                File[] files = guess.listFiles();
                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        if (files[i].getName().endsWith(".apk")) {
                            r.put("apk", files[i].getAbsolutePath());
                            r.put("size", Util.human(files[i].length()));
                        }
                    }
                }
            }
            // 构建结束后把重入闸放开，否则用户第二次点「开始构建」永远被挡
            String st = r.optString("state", "prepared");
            if ("success".equals(st) || "failed".equals(st)) {
                buildRunning = false;
            }
        } catch (Exception e) {
            return errJson("读取构建状态失败：" + e.getMessage());
        }
        return r.toString();
    }

    public String apiStartBuild(String buildId) {
        JSONObject r = new JSONObject();
        // 用户手抖连点两次，两次 RUN_COMMAND 会写同一份 build.log / status.json，
        // 现场直接变成一团乱账；这里先挡一道，脚本侧另有 flock。
        if (buildRunning) {
            return errJson("上一个构建还在跑，等它结束再来");
        }
        try {
            File base = resolveBuildDir(buildId);
            File script = new File(base, "build_apk.sh");
            if (!script.isFile()) {
                return errJson("构建脚本不存在，先点「生成构建工程」");
            }
            String command = "bash " + script.getAbsolutePath();
            boolean termux = Termux.installed(this);
            boolean sent = false;
            if (termux && !Termux.hasRunPermission(this)) {
                // 首次构建必踩：权限没给，RUN_COMMAND 会静默失败。弹一次系统授权框，
                // 用户点「允许」后由 onRequestPermissionsResult 自动补发，不用再点第二次。
                pendingRunScript = script.getAbsolutePath();
                r.put("ok", 1);
                r.put("mode", "permission");
                r.put("command", command);
                r.put("message", "第一次构建要允许「执行 Termux 命令」权限，弹窗点允许，马上自动开始。");
                r.put("termuxInstalled", termux);
                r.put("buildRoot", getBuildRoot().getAbsolutePath());
                r.put("readable", termuxCanReadBuildRoot());
                // 权限弹窗必须在主线程发起，桥线程上直接 request 在部分 ROM 上静默没反应
                final StationActivity self = this;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        self.requestPermissions(new String[]{Termux.RUN_PERM}, REQ_TERMUX_RUN);
                    }
                });
                return r.toString();
            }
            if (termux) {
                sent = Termux.runScript(this, script.getAbsolutePath());
            }
            if (sent) {
                buildRunning = true;
                // 国产 ROM 会把后台 Termux 冻住（freezer:/frozen），构建会假死。
                // 直接把 Termux 顶到前台，让用户在它自己的终端里看见构建日志。
                Termux.launchApp(this);
            }
            r.put("ok", 1);
            r.put("mode", sent ? "termux" : "manual");
            r.put("command", command);
            r.put("message", sent
                    ? "已交给 Termux，别把它切后台（国产 ROM 会冻住后台进程），约 20~40 秒；跑完切回来看产物。"
                    : "");
            r.put("termuxInstalled", termux);
            r.put("buildRoot", getBuildRoot().getAbsolutePath());
            r.put("readable", termuxCanReadBuildRoot());
            if (!sent) {
                Util.writeText(new File(base, "status.json"), "{\"state\":\"prepared\"}");
            }
        } catch (Exception e) {
            return errJson("启动构建失败：" + e.getMessage());
        }
        return r.toString();
    }

    public void apiInstall(String apkPath) {
        if (!insideBuildOut(apkPath)) {
            try {
                emit("install", new JSONObject().put("ok", 0)
                        .put("message", "只能安装本工作站自己打出来的包（构建目录之外的一律拒绝）"));
            } catch (Exception ignored) {
            }
            return;
        }
        Installer.install(this, new File(apkPath), new Installer.Callback() {
            @Override
            public void onResult(boolean ok, String message) {
                try {
                    emit("install", new JSONObject().put("ok", ok ? 1 : 0).put("message", message));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 安装入口只认 buildRoot 下面的产物，别让桥把任意路径丢给系统安装器。 */
    private boolean insideBuildOut(String apkPath) {
        try {
            File f = new File(apkPath == null ? "" : apkPath).getCanonicalFile();
            String root = getBuildRoot().getCanonicalPath();
            return f.isFile() && f.getName().endsWith(".apk")
                    && (f.getPath().startsWith(root + File.separator + "out" + File.separator)
                    || f.getPath().contains(File.separator + "build" + File.separator));
        } catch (Exception e) {
            Log.w(Util.TAG, "install path check failed", e);
            return false;
        }
    }

    public String apiExportBuild(String buildId) {
        JSONObject r = new JSONObject();
        try {
            File base = resolveBuildDir(buildId);
            if (!base.isDirectory()) {
                return errJson("构建目录不存在");
            }
            File outDir = new File(getBuildRoot(), "out");
            if (!outDir.exists()) {
                outDir.mkdirs();
            }
            File zip = new File(outDir, buildId + ".zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
            try {
                zipDir(base, base.getName(), zos);
            } finally {
                zos.close();
            }
            r.put("ok", 1);
            r.put("path", zip.getAbsolutePath());
            r.put("size", Util.human(zip.length()));
        } catch (Exception e) {
            return errJson("导出失败：" + e.getMessage());
        }
        return r.toString();
    }

    private void zipDir(File dir, String prefix, ZipOutputStream zos) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        byte[] buf = new byte[16384];
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            String entryName = prefix + "/" + f.getName();
            if (f.isDirectory()) {
                zipDir(f, entryName, zos);
            } else {
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                FileInputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                } finally {
                    Util.close(in);
                }
                zos.closeEntry();
            }
        }
    }

    public void apiRunProbe() {
        try {
            File kitDir = deployKit();
            File probe = new File(kitDir, "probe.sh");
            boolean termux = Termux.installed(this);
            if (termux && !Termux.hasRunPermission(this)) {
                pendingRunScript = probe.getAbsolutePath();
                final StationActivity self = this;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        self.requestPermissions(new String[]{Termux.RUN_PERM}, REQ_TERMUX_RUN);
                    }
                });
                JSONObject r0 = new JSONObject();
                r0.put("ok", 1);
                r0.put("pending", 1);
                r0.put("command", "bash " + probe.getAbsolutePath());
                r0.put("termuxInstalled", true);
                r0.put("message", "自检要先允许「执行 Termux 命令」，弹窗点允许就自动开跑。");
                emit("probeSent", r0);
                return;
            }
            boolean sent = termux && Termux.runScript(this, probe.getAbsolutePath());
            JSONObject r = new JSONObject();
            r.put("ok", sent ? 1 : 0);
            r.put("command", "bash " + probe.getAbsolutePath());
            r.put("termuxInstalled", Termux.installed(this));
            emit("probeSent", r);
        } catch (Exception e) {
            emitError("自检请求发送失败：" + e.getMessage());
        }
    }

    public String apiReadProbe() {
        JSONObject r = new JSONObject();
        try {
            File probe = new File(new File(getBuildRoot(), "kit"), "probe.json");
            String raw = Util.readText(probe);
            if (raw == null) {
                r.put("ok", 0);
                r.put("message", "还没有自检结果，点「运行自检」让 Termux 探一遍。");
                return r.toString();
            }
            JSONObject p = new JSONObject(raw);
            p.put("ok", 1);
            return p.toString();
        } catch (Exception e) {
            return errJson("自检结果读取失败");
        }
    }

    public String apiDeployKit() {
        JSONObject r = new JSONObject();
        try {
            File kitDir = deployKit();
            r.put("ok", 1);
            r.put("path", kitDir.getAbsolutePath());
            r.put("setup", "bash " + kitDir.getAbsolutePath() + "/setup.sh");
            r.put("readable", termuxCanReadBuildRoot());
        } catch (Exception e) {
            return errJson("释放脚本失败：" + e.getMessage());
        }
        return r.toString();
    }

    public void apiPickIcon() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_ICON);
        } catch (Exception e) {
            emitError("打不开图片选择器：" + e.getMessage());
        }
    }

    /**
     * 让玩家选自己的签名文件（.keystore / .jks）。选完复制到 <构建根>/keys/，
     * 打包时用这份副本签名，产物就能拿去正式发布/升级。
     */
    public void apiPickKeystore() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_KEYSTORE);
        } catch (Exception e) {
            emitError("打不开文件选择器：" + e.getMessage());
        }
    }

    public void apiOpenTermux() {        if (!Termux.launchApp(this)) {
            emitError("没装 Termux。先去 F-Droid 装一个，再回来部署环境。");
        }
    }

    public void apiOpenUrl(String url) {
        // 只放这三类；file:// / intent:// 之类一律不接，桥不该成为任意跳转器
        if (url == null) {
            return;
        }
        String low = url.trim().toLowerCase(Locale.ROOT);
        if (!(low.startsWith("http://") || low.startsWith("https://") || low.startsWith("mailto:"))) {
            Log.w(Util.TAG, "openUrl rejected: " + url);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.w(Util.TAG, "openUrl failed", e);
        }
    }

    public String apiCopy(String text) {
        JSONObject r = new JSONObject();
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("webgal-station", text));
            r.put("ok", 1);
        } catch (Exception e) {
            try {
                r.put("ok", 0);
                r.put("message", "复制失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return r.toString();
    }

    public void apiToggleOrientation() {
        final int target = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setRequestedOrientation(target);
            }
        });
    }

    public String apiLoadPrefs() {
        return store.getString("form", "{}");
    }

    public void apiSavePrefs(String json) {
        if (json == null) {
            return;
        }
        // 上限放宽到 8 KB（SP 单条存几 KB 没问题），超了也不再静默丢掉
        if (json.length() > 8192) {
            Log.w(Util.TAG, "prefs too long: " + json.length());
            emitError("草稿太长（超过 8 KB），这次没存上；把备注/签名说明删短一点再试");
            return;
        }
        store.putString("form", json);
    }

    /* ============ 不会就问：用户自带 API ============ */

    /**
     * 前端传 {"base","key","model","prompt","id"}。
     * 只允许 https；本机回环（127.0.0.1 / localhost / ::1）例外，方便自建代理。
     * 同步方法只受理，结果用 emit("aiReply", {...}) 回推，别在桥线程上等网络。
     */
    public String apiAskAI(String json) {
        JSONObject r = new JSONObject();
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String base = o.optString("base", "").trim();
            String key = o.optString("key", "").trim();
            String model = o.optString("model", "").trim();
            String prompt = o.optString("prompt", "").trim();
            final String id = o.optString("id", "");
            final String ctx = shorten(o.optString("ctx", "").trim(), 3600);
            if (base.isEmpty() || model.isEmpty()) {
                r.put("ok", 0).put("message", "还没配接口和模型。先在「不会就问」里填地址、模型名、Key，再点保存。");
                return r.toString();
            }
            if (prompt.isEmpty()) {
                r.put("ok", 0).put("message", "问题还是空的。");
                return r.toString();
            }
            String urlText = base.endsWith("/chat/completions") ? base
                    : (base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions");
            java.net.URL url = new java.net.URL(urlText);
            String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
            String host = url.getHost() == null ? "" : url.getHost();
            boolean loop = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
            if (!"https".equals(scheme) && !(loop && "http".equals(scheme))) {
                r.put("ok", 0).put("message", "只允许 https 接口；本机回环可以用 http。明文 http 会被系统的网络安全策略拦掉。");
                return r.toString();
            }
            final String fUrl = urlText;
            final String fKey = key;
            final String fModel = model;
            final String fPrompt = prompt;
            final String fCtx = ctx;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    JSONObject out = new JSONObject();
                    int code = 0;
                    String text = null;
                    String err = null;
                    java.net.HttpURLConnection c = null;
                    try {
                        JSONObject body = new JSONObject();
                        body.put("model", fModel);
                        body.put("temperature", 0.3);
                        JSONArray msgs = new JSONArray();
                        msgs.put(new JSONObject().put("role", "system")
                                .put("content", "你是「WebGAL 移动工作站」的答疑助手。用户是在手机上做文字冒险游戏的人。"
                                        + "先在心里强制推理：判断用户卡在哪一步、涉及哪个页签/按钮、有没有已知限制，再下结论；"
                                        + "只输出结论和能照做的步骤，不输出推理过程。用简体中文，250 字以内，先步骤后原因。"
                                        + "只讲这个软件里真实存在的功能，别编造按钮；不确定就直说不确定，并让用户去「环境」页核实。"
                                        + "回答末尾附一句：以上为 AI 生成，可能有误，请以实际界面为准。\n\n"
                                        + "【软件用法与逻辑参考】\n" + fCtx));
                        msgs.put(new JSONObject().put("role", "user").put("content", fPrompt));
                        body.put("messages", msgs);
                        byte[] payload = body.toString().getBytes("UTF-8");
                        c = (java.net.HttpURLConnection) new java.net.URL(fUrl).openConnection();
                        c.setRequestMethod("POST");
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(60000);
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        c.setRequestProperty("Accept", "application/json");
                        if (!fKey.isEmpty()) {
                            c.setRequestProperty("Authorization", "Bearer " + fKey);
                        }
                        OutputStream os = c.getOutputStream();
                        os.write(payload);
                        os.flush();
                        os.close();
                        code = c.getResponseCode();
                        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                        StringBuilder sb = new StringBuilder();
                        if (is != null) {
                            java.io.BufferedReader br = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(is, "UTF-8"));
                            String line;
                            while ((line = br.readLine()) != null) {
                                sb.append(line);
                            }
                            br.close();
                        }
                        String raw = sb.toString();
                        if (code >= 200 && code < 300) {
                            JSONObject j = new JSONObject(raw);
                            JSONArray choices = j.optJSONArray("choices");
                            if (choices != null && choices.length() > 0) {
                                JSONObject m0 = choices.getJSONObject(0).optJSONObject("message");
                                if (m0 != null) {
                                    text = m0.optString("content", "");
                                }
                            }
                            if (text == null) {
                                err = "接口回了 200，但没找到 choices[0].message.content";
                            }
                        } else {
                            err = "接口回了 HTTP " + code + "：" + shorten(raw, 200);
                        }
                    } catch (Exception e) {
                        err = e.getClass().getSimpleName() + "：" + (e.getMessage() == null ? "没拿到错误详情" : e.getMessage());
                    } finally {
                        if (c != null) {
                            try {
                                c.disconnect();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    try {
                        if (err == null && text != null) {
                            out.put("ok", 1).put("text", text.trim());
                        } else {
                            out.put("ok", 0).put("message", err == null ? "没拿到回答" : err);
                        }
                        out.put("id", id);
                    } catch (Exception ignored) {
                    }
                    emit("aiReply", out);
                }
            }).start();
            r.put("ok", 1).put("pending", 1).put("id", id);
        } catch (Exception e) {
            try {
                r.put("ok", 0).put("message", "请求没发出去：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return r.toString();
    }

    /** 错误回显要短，别把整页 HTML 塞进界面 */
    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    public void apiAcceptDisclaimer() {
        store.acceptDisclaimer();
    }

    // ---------------------------------------------------------------- 回调

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_IMPORT) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            startImport(uri);
        } else if (requestCode == REQ_ASSETS) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<Uri>();
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            startAssetImport(uris);
        } else if (requestCode == REQ_PROJECT_ZIP) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            startZipImport(uri);
        } else if (requestCode == REQ_ICON) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                File dir = new File(getBuildRoot(), "icons");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                pendingIconFile = new File(dir, "picked-" + System.currentTimeMillis() + ".img");
                InputStream in = getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(pendingIconFile);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    Util.close(in);
                    Util.close(out);
                }
                emit("icon", new JSONObject().put("path", pendingIconFile.getAbsolutePath()).put("name", "已选择"));
            } catch (Exception e) {
                emitError("读取图片失败：" + e.getMessage());
            }
        } else if (requestCode == REQ_KEYSTORE) {
            // 玩家自己的签名文件：复制进工作站私有键目录，之后打包只认这份副本。
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                File dir = new File(getBuildRoot(), "keys");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String name = "";
                android.database.Cursor c = null;
                try {
                    c = getContentResolver().query(uri, null, null, null, null);
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            name = c.getString(idx);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    Util.close(c);
                }
                if (name == null || name.isEmpty() || !name.endsWith(".keystore") && !name.endsWith(".jks")) {
                    name = "user-" + System.currentTimeMillis() + ".keystore";
                }
                File dest = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                InputStream in = getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(dest);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    Util.close(in);
                    Util.close(out);
                }
                emit("keystore", new JSONObject().put("path", dest.getAbsolutePath()).put("name", dest.getName()));
            } catch (Exception e) {
                emitError("读取签名文件失败：" + e.getMessage());
            }
        }
    }

    private void startImport(final Uri treeUri) {
        if (importThread != null && importThread.isAlive()) {
            emitError("上一个导入还没完，等它跑完再选下一个");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File dest = null;
                try {
                    File projectsDir = new File(getBuildRoot(), "projects");
                    if (!projectsDir.exists()) {
                        projectsDir.mkdirs();
                    }
                    String id = "p" + System.currentTimeMillis();
                    dest = new File(projectsDir, id);
                    if (!dest.mkdirs()) {
                        emitError("无法创建工程目录");
                        return;
                    }
                    emit("importStart", new JSONObject().put("id", id));
                    String rootDocId;
                    try {
                        rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    } catch (Exception e) {
                        // 少数第三方文件管理器不回 tree id，退回 URI 最后一段，不然整条导入直接死在这。
                        String p = treeUri.getLastPathSegment();
                        int colon = p == null ? -1 : p.indexOf(':');
                        rootDocId = colon >= 0 ? p.substring(colon + 1) : p;
                        if (rootDocId == null) throw new IOException("拿不到目录 ID");
                    }
                    final int[] count = {0};
                    copyDocumentTree(treeUri, rootDocId, dest, count);
                    File gameDir = detectGameDir(dest);
                    if (gameDir == null) {
                        Util.deleteTree(dest);
                        emitError("这个文件夹里没找到 game/config.txt。请选择 WebGAL 工程根目录（含 game 子目录），或者直接选 game 目录。");
                        return;
                    }
                    Store.Project p = new Store.Project();
                    p.id = id;
                    p.name = dest.getName();
                    p.dir = dest.getAbsolutePath();
                    p.gameDir = gameDir.getAbsolutePath();
                    p.gameName = Util.readGameName(gameDir);
                    if (p.gameName != null && !p.gameName.isEmpty()) {
                        p.name = p.gameName;
                    }
                    p.source = "import";
                    p.bytes = Util.dirSize(dest);
                    p.files = Util.countFiles(dest);
                    p.scenes = Util.listScenes(gameDir).size();
                    p.createdAt = System.currentTimeMillis();
                    store.add(p);
                    JSONObject r = new JSONObject();
                    r.put("ok", 1);
                    r.put("project", p.toUiJson());
                    r.put("count", count[0]);
                    emit("imported", r);
                } catch (Exception e) {
                    Log.w(Util.TAG, "import failed", e);
                    // 复制到一半炸掉：留下的是半截工程目录，用户永远不会自己去删
                    if (dest != null) {
                        Util.deleteTree(dest);
                    }
                    emitError("导入失败：" + e.getMessage());
                }
            }
        }, "import-project");
        importThread = t;
        t.start();
    }

    /** 从 ZIP 导入工程。文件夹选择器被 ROM 限制时，这条路能救场。 */
    private void startZipImport(final Uri zipUri) {
        if (zipImportThread != null && zipImportThread.isAlive()) {
            emitError("上一个导入还没完，等它跑完再选");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File dest = null;
                java.util.zip.ZipInputStream zin = null;
                try {
                    File projectsDir = new File(getBuildRoot(), "projects");
                    if (!projectsDir.exists()) projectsDir.mkdirs();
                    String id = "p" + System.currentTimeMillis();
                    dest = new File(projectsDir, id);
                    if (!dest.mkdirs()) {
                        emitError("无法创建工程目录");
                        return;
                    }
                    emit("importStart", new JSONObject().put("id", id)
                            .put("name", displayName(zipUri)));
                    InputStream raw = getContentResolver().openInputStream(zipUri);
                    if (raw == null) throw new IOException("读不到 ZIP");
                    zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw));
                    String base = dest.getCanonicalPath();
                    int count = 0;
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        String name = e.getName().replace('\\', '/');
                        if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) {
                            zin.closeEntry();
                            continue;
                        }
                        File out = new File(dest, name);
                        String canon = out.getCanonicalPath();
                        if (!canon.equals(base) && !canon.startsWith(base + File.separator)) {
                            throw new IOException("ZIP 里有越界路径：" + name);
                        }
                        if (e.isDirectory()) {
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) parent.mkdirs();
                            OutputStream os = new FileOutputStream(out);
                            try {
                                byte[] buf = new byte[16384];
                                int n;
                                while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                            } finally {
                                Util.close(os);
                            }
                            count++;
                            if (count % 30 == 0) {
                                emitProgress("解压工程", Math.min(95, 10 + count / 8), count + " 个文件");
                            }
                        }
                        zin.closeEntry();
                    }
                    File gameDir = detectGameDir(dest);
                    if (gameDir == null) {
                        Util.deleteTree(dest);
                        emitError("ZIP 里没找到 game/config.txt。压缩包要包含 game 目录，或直接把 game 目录打进去。");
                        return;
                    }
                    Store.Project p = new Store.Project();
                    p.id = id;
                    p.name = dest.getName();
                    p.dir = dest.getAbsolutePath();
                    p.gameDir = gameDir.getAbsolutePath();
                    p.gameName = Util.readGameName(gameDir);
                    if (p.gameName != null && !p.gameName.isEmpty()) p.name = p.gameName;
                    p.source = "import";
                    p.bytes = Util.dirSize(dest);
                    p.files = Util.countFiles(dest);
                    p.scenes = Util.listScenes(gameDir).size();
                    p.createdAt = System.currentTimeMillis();
                    store.add(p);
                    JSONObject r = new JSONObject();
                    r.put("ok", 1);
                    r.put("project", p.toUiJson());
                    r.put("count", count);
                    emit("imported", r);
                } catch (Exception e) {
                    Log.w(Util.TAG, "zip import failed", e);
                    if (dest != null) Util.deleteTree(dest);
                    emitError("ZIP 导入失败：" + e.getMessage());
                } finally {
                    Util.close(zin);
                }
            }
        }, "zip-import");
        zipImportThread = t;
        t.start();
    }

    /** 用 DocumentsContract 递归复制，不依赖 support 库。 */
    private void copyDocumentTree(Uri treeUri, String docId, File dest, int[] counter) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null) {
                    continue;
                }
                if (name.contains("/")) {
                    name = name.replace("/", "_");
                }
                File child = new File(dest, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!child.exists()) {
                        child.mkdirs();
                    }
                    copyDocumentTree(treeUri, id, child, counter);
                } else {
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    InputStream in = getContentResolver().openInputStream(fileUri);
                    if (in == null) {
                        continue;
                    }
                    OutputStream out = new FileOutputStream(child);
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    } finally {
                        Util.close(in);
                        Util.close(out);
                    }
                    counter[0]++;
                    if (counter[0] % 30 == 0) {
                        emitProgress("复制工程文件", Math.min(99, counter[0] / 5), counter[0] + " 个文件");
                    }
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private File detectGameDir(File dir) {
        if (new File(dir, "game/config.txt").isFile()) {
            return new File(dir, "game");
        }
        if (new File(dir, "config.txt").isFile()) {
            return dir;
        }
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                if (kids[i].isDirectory()) {
                    if (new File(kids[i], "config.txt").isFile()) {
                        return kids[i];
                    }
                    if (new File(new File(kids[i], "game"), "config.txt").isFile()) {
                        return new File(kids[i], "game");
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 与 UI 通信

    public void emit(final String type, final JSONObject payload) {
        final String script = "window.__stationEmit&&window.__stationEmit(" + JSONObject.quote(type)
                + "," + JSONObject.quote(payload == null ? "{}" : payload.toString()) + ");";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript(script, null);
                }
            }
        });
    }

    public void emitError(String message) {
        try {
            emit("error", new JSONObject().put("ok", 0).put("message", message));
        } catch (Exception ignored) {
        }
    }

    public void emitProgress(String stage, int percent, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("stage", stage == null ? "" : stage);
            o.put("percent", percent);
            o.put("detail", detail == null ? "" : detail);
            emit("progress", o);
        } catch (Exception ignored) {
        }
    }

    private static String okJson() {
        return "{\"ok\":1}";
    }

    private static String errJson(String message) {
        try {
            return new JSONObject().put("ok", 0).put("message", message).toString();
        } catch (Exception e) {
            return "{\"ok\":0,\"message\":\"unknown\"}";
        }
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(text.length() - max);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onBackPressed() {
        if (previewing) {
            apiExitPreview();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        // 切后台就把 WebView 挂起：前端 1.2 秒一次的轮询和引擎音频没必要在后台烧电
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        // 三条裸线程持有 Activity，不中断的话销毁后还在跑 IO
        interruptQuietly(bootstrapThread);
        interruptQuietly(prepareThread);
        interruptQuietly(importThread);
        Installer.unregister();
        if (server != null) {
            server.stop();
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private static void interruptQuietly(Thread t) {
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /** 版本号由 tools/pc/version.ps1 生成到 V.java，这里只做转发。 */
    public static final class BuildConfigVersion {
        public static final String VERSION = V.NAME;
        public static final int CODE = V.CODE;
    }
}
