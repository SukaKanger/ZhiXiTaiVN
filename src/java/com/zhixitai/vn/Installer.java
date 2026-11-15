/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * APK 安装器。
 * 不走 FileProvider 那套（framework 里没有），直接用 PackageInstaller 会话写流，
 * 系统装完会给个状态广播，把结果回给界面。
 */
public class Installer {

    public static final String ACTION_RESULT = "com.zhixitai.vn.INSTALL_RESULT";

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return ctx.getPackageManager().canRequestPackageInstalls();
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    public static void openInstallPermission(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(Util.TAG, "open install settings failed", e);
        }
    }

    private static BroadcastReceiver receiver;
    private static Context receiverCtx;

    public static void install(final Context ctx, final File apk, final Callback cb) {
        if (!apk.isFile()) {
            cb.onResult(false, "APK 不存在：" + apk.getAbsolutePath());
            return;
        }
        if (!canInstall(ctx)) {
            openInstallPermission(ctx);
            cb.onResult(false, "还没允许安装未知来源应用，已帮你打开设置页，开完再点一次安装。");
            return;
        }
        int sessionId = -1;
        try {
            PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            OutputStream out = null;
            InputStream in = null;
            try {
                out = session.openWrite("base.apk", 0, apk.length());
                in = new FileInputStream(apk);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                Util.close(in);
                Util.close(out);
            }
            session.close();

            registerResultReceiver(ctx, cb);

            Intent status = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            session.commit(pending.getIntentSender());
            cb.onResult(true, "安装会话已提交，等系统弹确认框。");
        } catch (Exception e) {
            Log.w(Util.TAG, "install failed", e);
            // 会话建了但没走完，不放弃的话会一直挂着占磁盘
            try {
                ctx.getPackageManager().getPackageInstaller().abandonSession(sessionId);
            } catch (Exception ignored) {
            }            cb.onResult(false, "安装失败：" + e.getMessage());
        }
    }

    /** Activity 销毁时调一次，别让静态接收器攥着旧 Context。 */
    public static void unregister() {
        if (receiver != null && receiverCtx != null) {
            try {
                receiverCtx.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
        }
        receiver = null;
        receiverCtx = null;
    }

    private static void registerResultReceiver(Context ctx, final Callback cb) {        // 上一个会话的接收器连同 Activity 一起留在静态字段里，先摘干净再挂新的
        unregister();
        receiverCtx = ctx.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    cb.onResult(true, "装好了，去桌面找图标。");
                } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            context.startActivity(confirm);
                        } catch (Exception e) {
                            cb.onResult(false, "拉起安装确认页失败：" + e.getMessage());
                        }
                    }
                } else {
                    cb.onResult(false, "安装被拒（status=" + status + "）：" + (message == null ? "" : message));
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
    }
}
