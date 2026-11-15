/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件由 tools/pc/version.ps1 生成，别手改；改 VERSION.json 再跑一次。
 */
package com.zhixitai.vn;

public final class V {
    /** 内部版本号：2618*10000 + build，安卓用它判断能不能覆盖安装 */
    public static final int CODE = 26180050;
    /** 外部版本名：<base>-<stage>[.build]，代际不进名字 */
    public static final String NAME = "1.1.7-beta.50";
    /** 对外主版本（游戏包的名字也跟这个走） */
    public static final String BASE = "1.1.7";
    /** 当前后缀 */
    public static final String STAGE = "beta";
    /** 号段：26 年 / 18 代际（只进 versionCode） */
    public static final String TAG = "2618";
    public static final int BUILD = 50;
    public static final String STAMP = "2026-11-14";

    private V() {
    }
}