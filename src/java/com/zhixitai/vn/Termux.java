/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.util.Log;

/**
 * Termux 桥。
 *
 * 手机端构建靠 Termux 里的工具链（aapt2/javac/d8/apksigner）。
 * 本应用没有 Termux 的私有目录写权限，所以不能直接把脚本塞进去执行；
 * 改成"一次性部署 + 长期调用"：
 *
 *   1. 用户在工作站里点「部署 Termux 环境」，把 kit 里的脚本释放到共享存储；
 *   2. 用户在 Termux 里执行一次 setup.sh，它会
 *        · 装依赖（openjdk-17 / aapt / apksigner / d8 之类）
 *        · 下载 android.jar
 *        · 把 ~/.webgal-station/run.sh 这个跳板写进 Termux 私有目录
 *        · 打开 allow-external-apps 开关
 *   3. 之后工作站用 RUN_COMMAND 让 run.sh 去跑共享存储里的 build_apk.sh。
 *
 * 跳板放在 Termux 私有目录是硬性要求：RUN_COMMAND 只执行自己家目录里的脚本。
 */
public class Termux {

    public static final String PKG = "com.termux";
    public static final String HOME = "/data/data/com.termux/files/home";
    public static final String BOOTSTRAP_DIR = HOME + "/.webgal-station";
    public static final String BOOTSTRAP = BOOTSTRAP_DIR + "/run.sh";

    /**
     * Termux 自己声明的危险权限。装完本应用默认是 denied（真机实测），
     * 不申请就发 RUN_COMMAND 会静默失败，用户只看到「没能自动拉起 Termux」。
     */
    public static final String RUN_PERM = "com.termux.permission.RUN_COMMAND";

    public static boolean hasRunPermission(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) {
                return true;
            }
            return ctx.checkSelfPermission(RUN_PERM) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String version(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(PKG, 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** 直接打开 Termux 应用，让用户手动粘命令。 */
    public static boolean launchApp(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
            if (i == null) {
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 让 Termux 跑共享存储里的某个脚本（经跳板转交，因为 RUN_COMMAND 只认自家目录）。
     *
     * 真机实测（vivo V2219A / Android 13 / MT6855V）：Termux 一旦落到后台，整组进程会被
     * 冻进 freezer:/frozen + cpuset:/background，javac 会停在 0% CPU 一动不动；同一份脚本
     * 在 Termux 前台时 24~29 秒就能出一个 6.4MB 的 APK。所以这里仍然用后台会话（它比前台
     * 会话更抗会话关闭），但调用方发完请求后必须把 Termux 顶到前台（Termux.launchApp），
     * 否则构建会被系统冻住。跳板用 setsid 脱离，Termux 关了也不影响已经起的构建。
     */
    public static boolean runScript(Context ctx, String scriptPath) {
        try {
            Intent i = new Intent();
            i.setClassName(PKG, "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH", BOOTSTRAP);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{scriptPath});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            // 先把两个必踩的坑拦住，否则 startService 一声不响地把 Intent 丢给 Termux 的
            // URL 输入框（真机实测：命令文本出现在「输入网址」里，进度条永远不动）。
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            if (pm.resolveService(i, 0) == null) {
                Log.w(Util.TAG, "Termux 的 RUN_COMMAND 服务解析不到（版本不支持 / 未装）");
                return false;
            }
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(PKG, 0);
            if (ai != null && (ai.flags & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0) {
                Log.w(Util.TAG, "Termux 处于 stopped 状态（被 force-stop 过），需要先手动打开一次");
                return false;
            }
            ctx.startService(i);
            Log.i(Util.TAG, "RUN_COMMAND sent: " + scriptPath);
            return true;
        } catch (Exception e) {
            Log.w(Util.TAG, "RUN_COMMAND failed", e);
            return false;
        }
    }

    public static String manualCommand(String buildDir) {
        return "bash " + buildDir + "/build_apk.sh";
    }

    public static String setupCommand(String kitDir) {
        return "bash " + kitDir + "/setup.sh";
    }
}
