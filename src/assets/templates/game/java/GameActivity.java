/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
/*
 * GameActivity —— 打包出来的游戏 APK 的宿主。
 *
 * 结构：一个全屏 WebView + 一个本地 HTTP 服务。
 * 引擎文件在 assets/engine，剧本文档在 assets/game，
 * 服务把 "/" 挂到引擎、把 "/game/" 挂到剧本目录，
 * 这样引擎里写死的 ./game/xxx 相对路径不用改一行。
 *
 * 这份文件由 WebGAL 移动工作站生成，包名占位符 __PKG__ 在构建时替换。
 */
package __PKG__;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

public class GameActivity extends Activity {

    private WebView web;
    private LocalServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        server = new LocalServer();
        int port;
        try {
            server.mount("/", new LocalServer.AssetSource(getAssets(), "engine"));
            server.mount("/game/", new LocalServer.AssetSource(getAssets(), "game"));
            port = server.start();
        } catch (IOException e) {
            showError("本地服务启动失败：" + e.getMessage());
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF000000);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !(url != null && url.startsWith("http://127.0.0.1:"));
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        String badge = readAssetText("legal/badge.txt");
        if (badge != null && !badge.trim().isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(badge.trim());
            tv.setTextSize(10f);
            tv.setTextColor(0xFFEFEDE8);
            tv.setPadding(28, 12, 28, 12);
            // 深色底衬：不然遇到白色标题页，字就消失了
            android.graphics.drawable.GradientDrawable pill =
                    new android.graphics.drawable.GradientDrawable();
            pill.setColor(0xD9000000);
            pill.setCornerRadius(18f);
            tv.setBackground(pill);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = 28;
            lp.bottomMargin = 28;
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.LEFT;
            root.addView(tv, lp);
            tv.animate().alpha(0f).setStartDelay(4200).setDuration(900).start();
        }

        setContentView(root);
        web.loadUrl("http://127.0.0.1:" + port + "/");
    }

    private String readAssetText(String path) {
        InputStream in = null;
        try {
            in = getAssets().open(path);
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void showError(String message) {
        TextView tv = new TextView(this);
        tv.setText("WebGAL 启动失败\n\n" + message);
        tv.setTextColor(0xFFFF5B4D);
        tv.setBackgroundColor(0xFF0B0D0E);
        tv.setPadding(48, 48, 48, 48);
        tv.setTextSize(14f);
        setContentView(tv);
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) {
            server.stop();
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
