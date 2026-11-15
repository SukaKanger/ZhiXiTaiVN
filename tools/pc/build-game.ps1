# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# build-game.ps1 —— 用和端上完全相同的那套模板，把任意 WebGAL 工程打成 APK。
#
#   .\tools\pc\build-game.ps1 -GameDir "D:\WebGAL\game" -AppName "我的游戏"
#
# 这条通道的意义：出差/换机器时不用等 Termux；也能用来验证端上链路是否正常。

param(
    [Parameter(Mandatory = $true)][string]$GameDir,
    [string]$EngineDir = '',
    [string]$AppName = '',
    [string]$Pkg = '',
    [string]$VersionName = '1.0.0',
    [int]$VersionCode = 1,
    [ValidateSet('landscape', 'portrait', 'auto')][string]$Orientation = 'landscape',
    [string]$Icon = '',
    [switch]$NoBadge,
    # 低配模式：引擎里三套中文字体（36 MB）不打包，交给系统字体回退
    [switch]$LiteEngine,
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Root = '',
    [string]$Out = ''
)

. (Join-Path $PSScriptRoot 'lib.ps1')

if (-not $Root) {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
}
if (-not $EngineDir) {
    $EngineDir = Join-Path $Root 'src\assets\engine'
}

if (-not (Test-Path -LiteralPath (Join-Path $GameDir 'config.txt'))) {
    throw "GameDir 里没有 config.txt：$GameDir（应该选 WebGAL 的 game 目录）"
}
if (-not (Test-Path -LiteralPath (Join-Path $EngineDir 'index.html'))) {
    throw "引擎目录不对：$EngineDir（应有 index.html）"
}

if (-not $AppName) {
    $AppName = 'WebGAL 游戏'
    $cfg = Get-Content -LiteralPath (Join-Path $GameDir 'config.txt') -Encoding utf8 -TotalCount 20
    foreach ($line in $cfg) {
        if ($line -match '^\s*Game_name\s*:\s*(.+?)\s*;?\s*$') { $AppName = $Matches[1]; break }
    }
}

function New-PkgSlug {
    param([string]$Name)
    $ascii = ($Name.ToLowerInvariant() -replace '[^a-z0-9]', '')
    if ($ascii.Length -eq 0) {
        # 全中文名：用字符码算一个稳定后缀，别用 GetHashCode（.NET 每次进程都不同）
        $sum = 0
        foreach ($ch in $Name.ToCharArray()) {
            $sum = ($sum * 31 + [int]$ch) % 1000000007
        }
        $ascii = 'g' + $sum
    }
    if ($ascii.Length -gt 20) { $ascii = $ascii.Substring(0, 20) }
    if ($ascii -notmatch '^[a-z]') { $ascii = 'g' + $ascii }
    return $ascii
}

$tk = Resolve-Toolkit -Sdk $Sdk -JavaHome $JavaHome

if (-not $Pkg) {
    $Pkg = 'com.zhixitai.vn.' + (New-PkgSlug $AppName)
}
if ($Pkg -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') {
    throw "包名不合法：$Pkg"
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$slug = New-PkgSlug $AppName
$base = Join-Path $Root "build\$slug-$stamp"
$android = Join-Path $base 'android'
$assetsOut = Join-Path $android 'assets'
$srcOut = Join-Path (Join-Path $android 'src') ($Pkg -replace '\.', '\')
$resOut = Join-Path $android 'res'

New-Item -ItemType Directory -Force -Path $assetsOut, $srcOut, $resOut | Out-Null

Write-Host "WebGAL 移动工作站 · 打游戏 APK" -ForegroundColor Yellow
Write-Host "  工程   $GameDir"
Write-Host "  引擎   $EngineDir"
Write-Host "  应用   $AppName"
Write-Host "  包名   $Pkg"
Write-Host ""

# ---- 引擎（去掉 .gz 预压缩副本；低配模式再去掉三套中文字体）
Write-Host "  复制引擎 …"
$engineOut = Join-Path $assetsOut 'engine'
$exclude = @('.gz')
if ($LiteEngine) {
    $exclude += @('.ttf', '.otf', '.woff', '.woff2')
    Write-Host "  低配模式：不打包中文字体，靠系统字体回退"
}
Copy-TreeFiltered -Source $EngineDir -Destination $engineOut -ExcludeExtension $exclude
foreach ($f in @('index.html', 'manifest.json', 'webgal-engine.json', 'webgal-serviceworker.js')) {
    $p = Join-Path $EngineDir $f
    if (Test-Path -LiteralPath $p) {
        Copy-Item -LiteralPath $p -Destination $engineOut -Force
    }
}

# ---- 剧本与素材
Write-Host "  复制工程素材 …"
Copy-TreeFiltered -Source $GameDir -Destination (Join-Path $assetsOut 'game')

# ---- 来路与许可证（MPL-2.0 要求，不能丢）
$legalOut = Join-Path $assetsOut 'legal'
New-Item -ItemType Directory -Force -Path $legalOut | Out-Null
foreach ($f in @('NOTICE.txt', 'MPL-2.0.txt', 'LICENSE-MIT.txt')) {
    Copy-Item -LiteralPath (Join-Path $Root "src\assets\legal\$f") -Destination $legalOut -Force
}
if (-not $NoBadge) {
    $badge = 'WebGAL 4.6.4（MPL-2.0 非官方构建）· 由 WebGAL 移动工作站打包'
    [IO.File]::WriteAllText((Join-Path $legalOut 'badge.txt'), $badge, (New-Object Text.UTF8Encoding $false))
}

# ---- 清单
$orientationValue = switch ($Orientation) {
    'portrait' { 'portrait' }
    'auto' { 'fullSensor' }
    default { 'landscape' }
}
$manifestTpl = Get-Content -LiteralPath (Join-Path $Root 'templates\game\AndroidManifest.xml') -Raw -Encoding utf8
$manifest = $manifestTpl.
    Replace('__PKG__', $Pkg).
    Replace('__VERSION_CODE__', "$VersionCode").
    Replace('__VERSION_NAME__', [System.Security.SecurityElement]::Escape($VersionName)).
    Replace('__TARGET_SDK__', '34').
    Replace('__APP_NAME__', [System.Security.SecurityElement]::Escape($AppName)).
    Replace('__ORIENTATION__', $orientationValue)
[IO.File]::WriteAllText((Join-Path $android 'AndroidManifest.xml'), $manifest, (New-Object Text.UTF8Encoding $false))

# ---- Java
$activity = Get-Content -LiteralPath (Join-Path $Root 'templates\game\java\GameActivity.java') -Raw -Encoding utf8
$activity = $activity.Replace('package __PKG__;', "package $Pkg;")
[IO.File]::WriteAllText((Join-Path $srcOut 'GameActivity.java'), $activity, (New-Object Text.UTF8Encoding $false))

$server = Get-Content -LiteralPath (Join-Path $Root 'src\java\com\webgal\station\LocalServer.java') -Raw -Encoding utf8
$server = $server.Replace('package com.zhixitai.vn;', "package $Pkg;")
[IO.File]::WriteAllText((Join-Path $srcOut 'LocalServer.java'), $server, (New-Object Text.UTF8Encoding $false))

# ---- 图标
$sizes = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
if ($Icon -and (Test-Path -LiteralPath $Icon)) {
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $Icon).Path)
    foreach ($k in $sizes.Keys) {
        $dir = Join-Path $resOut "mipmap-$k"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $bmp = New-Object System.Drawing.Bitmap($sizes[$k], $sizes[$k])
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($img, 0, 0, $sizes[$k], $sizes[$k])
        $g.Dispose()
        $bmp.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    }
    $img.Dispose()
    Write-Host "  图标   用 $Icon"
} else {
    foreach ($k in $sizes.Keys) {
        $dir = Join-Path $resOut "mipmap-$k"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Copy-Item -LiteralPath (Join-Path $Root "src\res\mipmap-$k\ic_launcher.png") `
            -Destination (Join-Path $dir 'ic_launcher.png') -Force
    }
    Write-Host "  图标   工作站默认"
}

# ---- 构建
if (-not $Out) {
    $safe = ($AppName -replace '[\\/:*?"<>|]', '_')
    $suffix = if ($LiteEngine) { '-lite' } else { '' }
    $Out = Join-Path $Root "dist\$safe-$VersionName$suffix.apk"
}
$ks = New-DebugKeystore -Toolkit $tk -Path (Join-Path $Root 'build\debug.keystore')
$work = Join-Path $base 'work'

$final = Build-ApkProject -Toolkit $tk `
    -Manifest (Join-Path $android 'AndroidManifest.xml') `
    -ResDir $resOut `
    -AssetsDir $assetsOut `
    -SrcDir (Join-Path $android 'src') `
    -WorkDir $work `
    -OutputApk $Out -Keystore $ks -MinSdk 21 -TargetSdk 34

$size = [math]::Round((Get-Item -LiteralPath $final).Length / 1MB, 2)
Write-Host ""
Write-Host "✔ 产出 $final" -ForegroundColor Green
Write-Host "  体积 $size MB"
Write-Host "  中间工程 $android（可整体拷去 Termux 用 build_apk.sh 复建）"
Write-Host "  提醒：产物含 MPL-2.0 引擎，分发时 assets/legal/ 必须保留"
