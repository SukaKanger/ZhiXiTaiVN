/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件为原创代码，许可证见随附 LICENSE-MIT.txt
 */
package com.zhixitai.vn;

import android.webkit.JavascriptInterface;

/**
 * JS 桥。
 * 约定：同步方法一律返回 JSON 字符串（{"ok":1,...} 或 {"ok":0,"message":...}），
 * 耗时动作不返回结果，只发一个"已受理"，真正结果用 StationActivity.emit 推给前端。
 */
public class Api {

    private final StationActivity act;

    public Api(StationActivity act) {
        this.act = act;
    }

    @JavascriptInterface
    public String env() {
        return act.apiEnv();
    }

    @JavascriptInterface
    public String onboard() {
        return act.apiOnboard();
    }

    @JavascriptInterface
    public String installTermux() {
        return act.apiInstallTermux();
    }

    @JavascriptInterface
    public String offline() {
        return act.apiOffline();
    }

    @JavascriptInterface
    public String offlineInstall() {
        return act.apiOfflineInstall();
    }

    @JavascriptInterface
    public String projects() {
        return act.apiProjects();
    }

    @JavascriptInterface
    public String installTutorial() {
        return act.apiInstallTutorial();
    }

    @JavascriptInterface
    public void pickProject() {
        act.apiPickProject();
    }

    @JavascriptInterface
    public void pickProjectZip() {
        act.apiPickProjectZip();
    }

    @JavascriptInterface
    public void pickAssets(String projectId, String category) {
        act.apiPickAssets(projectId, category);
    }

    @JavascriptInterface
    public String deleteProject(String id) {
        return act.apiDeleteProject(id);
    }

    @JavascriptInterface
    public String stage(String id) {
        return act.apiStage(id);
    }

    @JavascriptInterface
    public String createProject(String name, String starter) {
        return act.apiCreateProject(name, starter);
    }

    @JavascriptInterface
    public String workspace(String id) {
        return act.apiWorkspace(id);
    }

    @JavascriptInterface
    public String readText(String id, String rel) {
        return act.apiReadText(id, rel);
    }

    @JavascriptInterface
    public String writeText(String id, String rel, String text) {
        return act.apiWriteText(id, rel, text);
    }

    @JavascriptInterface
    public String newScene(String id, String name) {
        return act.apiNewScene(id, name);
    }

    @JavascriptInterface
    public String deleteScene(String id, String name) {
        return act.apiDeleteScene(id, name);
    }

    @JavascriptInterface
    public void preview(String id) {
        act.apiPreview(id);
    }

    @JavascriptInterface
    public void pickKeystore() {
        act.apiPickKeystore();
    }

    @JavascriptInterface
    public void exitPreview() {
        act.apiExitPreview();
    }

    @JavascriptInterface
    public void prepareBuild(String projectId, String optionsJson) {
        act.apiPrepareBuild(projectId, optionsJson);
    }

    @JavascriptInterface
    public String buildState(String buildId) {
        return act.apiBuildState(buildId);
    }

    @JavascriptInterface
    public String startBuild(String buildId) {
        return act.apiStartBuild(buildId);
    }

    @JavascriptInterface
    public void install(String apkPath) {
        act.apiInstall(apkPath);
    }

    @JavascriptInterface
    public String exportBuild(String buildId) {
        return act.apiExportBuild(buildId);
    }

    @JavascriptInterface
    public void runProbe() {
        act.apiRunProbe();
    }

    @JavascriptInterface
    public String readProbe() {
        return act.apiReadProbe();
    }

    @JavascriptInterface
    public String deployKit() {
        return act.apiDeployKit();
    }

    @JavascriptInterface
    public void pickIcon() {
        act.apiPickIcon();
    }

    @JavascriptInterface
    public void openTermux() {
        act.apiOpenTermux();
    }

    @JavascriptInterface
    public void openUrl(String url) {
        act.apiOpenUrl(url);
    }

    @JavascriptInterface
    public String copy(String text) {
        return act.apiCopy(text);
    }

    @JavascriptInterface
    public String renameAsset(String pid, String cat, String name) {
        return act.apiRenameAsset(pid, cat, name);
    }

    @JavascriptInterface
    public void requestAllFiles() {
        act.requestAllFiles();
    }

    @JavascriptInterface
    public void toggleOrientation() {
        act.apiToggleOrientation();
    }

    @JavascriptInterface
    public String loadPrefs() {
        return act.apiLoadPrefs();
    }

    /** 不会就问：{"base","key","model","prompt","id"}，结果走 aiReply 事件回推 */
    @JavascriptInterface
    public String askAI(String json) {
        return act.apiAskAI(json);
    }

    @JavascriptInterface
    public void savePrefs(String json) {
        act.apiSavePrefs(json);
    }

    @JavascriptInterface
    public void acceptDisclaimer() {
        act.apiAcceptDisclaimer();
    }

    @JavascriptInterface
    public String version() {
        return StationActivity.BuildConfigVersion.VERSION;
    }
}
