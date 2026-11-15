# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# version.ps1 —— 版本号的唯一开关。
#
#   .\tools\pc\version.ps1             只看一眼当前版本（不写盘）
#   .\tools\pc\version.ps1 -Bump       内部 build +1，然后把所有引用位置刷一遍
#   .\tools\pc\version.ps1 -Sync       不 +1，只按 VERSION.json 重新生成
#   .\tools\pc\version.ps1 -Set 12     直接把 build 设成 12（回滚/对齐用）
#
# 号段规则（用户定的）：26 是年，18 是内部代际，1.1.5 是对外主版本，beta 是当前后缀。
#   versionCode = (year*100 + gen) * 10000 + build   → 26180016
#   versionName = base-stage[.build]                 → 1.1.5-beta.16
# 内部代际只活在 code 里，名字里不再挂 2601/2618，免得拦截页看着像没升级。
# versionCode 每个 build 递增，安卓才不会甩一句「已安装相同版本」把安装拦掉。

param(
    [switch]$Bump,
    [switch]$Sync,
    [int]$Set = 0,
    [string]$Root = ''
)

$ErrorActionPreference = 'Stop'

if (-not $Root) {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
}

$verPath = Join-Path $Root 'VERSION.json'
$v = Get-Content -LiteralPath $verPath -Raw | ConvertFrom-Json

if ($Set -gt 0) {
    $v.build = $Set
    $Bump = $true
} elseif ($Bump) {
    $v.build = $v.build + 1
} elseif (-not $Sync) {
    # 只读模式
    $gen2 = ('{0:D2}' -f [int]$v.gen)
    $tag = "$($v.year)$gen2"
    $code = ([int]$v.year * 100 + [int]$v.gen) * 10000 + [int]$v.build
    $name = "$($v.base)-$($v.stage)"
    if ($v.build -gt 1) { $name = "$name.$($v.build)" }
    "versionCode = $code"
    "versionName = $name"
    "build       = $($v.build)（加 -Bump 才会 +1）"
    return
}

# ---- 计算
$tag = ('{0}{1:D2}' -f [int]$v.year, [int]$v.gen)
$code = ([int]$v.year * 100 + [int]$v.gen) * 10000 + [int]$v.build
$name = "$($v.base)-$($v.stage)"
if ($v.build -gt 1) { $name = "$name.$($v.build)" }
$stamp = Get-Date -Format 'yyyy-MM-dd'
$v.updated = $stamp

# ---- 写回 VERSION.json（保持字段顺序、2 空格缩进、UTF-8 无 BOM）
$json = @"
{
  "year": $($v.year),
  "gen": $($v.gen),
  "base": "$($v.base)",
  "stage": "$($v.stage)",
  "build": $($v.build),
  "updated": "$stamp",
  "_note": "内部版本号 = 年(26) + 代际(18) + build 自增。versionCode = 2618*10000+build，versionName = <base>-<stage>[.build]，代际只进 code、不进名字。改这里只改这一份，其余文件由 tools/pc/version.ps1 生成。"
}
"@
[IO.File]::WriteAllText($verPath, ($json -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))

# ---- 生成 V.java（包内唯一版本常量）
$vjava = @"
/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 WebGAL 移动工作站作者
 * 本文件由 tools/pc/version.ps1 生成，别手改；改 VERSION.json 再跑一次。
 */
package com.zhixitai.vn;

public final class V {
    /** 内部版本号：2618*10000 + build，安卓用它判断能不能覆盖安装 */
    public static final int CODE = $code;
    /** 外部版本名：<base>-<stage>[.build]，代际不进名字 */
    public static final String NAME = "$name";
    /** 对外主版本（游戏包的名字也跟这个走） */
    public static final String BASE = "$($v.base)";
    /** 当前后缀 */
    public static final String STAGE = "$($v.stage)";
    /** 号段：26 年 / 18 代际（只进 versionCode） */
    public static final String TAG = "$tag";
    public static final int BUILD = $($v.build);
    public static final String STAMP = "$stamp";

    private V() {
    }
}
"@
$vjavaPath = Join-Path $Root 'src\java\com\zhixitai\vn\V.java'
[IO.File]::WriteAllText($vjavaPath, ($vjava -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))

# ---- 写 AndroidManifest.xml 的两个属性（就地替换，不动别的行）
$mf = Join-Path $Root 'src\AndroidManifest.xml'
$mfText = [IO.File]::ReadAllText($mf)
$mfText = [regex]::Replace($mfText, 'android:versionCode="[^"]*"', "android:versionCode=`"$code`"")
$mfText = [regex]::Replace($mfText, 'android:versionName="[^"]*"', "android:versionName=`"$name`"")
[IO.File]::WriteAllText($mf, ($mfText -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))

"versionCode = $code"
"versionName = $name"
"build       = $($v.build)"
"已写  VERSION.json / V.java / AndroidManifest.xml"
