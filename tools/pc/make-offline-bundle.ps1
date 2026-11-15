# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# make-offline-bundle.ps1 —— 把 Termux、54 个 deb、android.jar 拼成「完全体」要用的
# assets/offline 目录。只负责摆料，不负责塞进 APK（那是 build-station.ps1 -FullOffline）。

param(
    [string]$Root = '',
    [string]$AndroidJar = 'R:\soft\android202422\sdk_home\platforms\android-34-2\android.jar',
    [string]$TermuxApk = ''
)

if (-not $Root) {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
}

$debDir = Join-Path $Root 'build\offline\debs'
$out = Join-Path $Root 'build\offline\assets\offline'
$outDebs = Join-Path $out 'debs'

if (-not (Test-Path -LiteralPath $debDir)) { throw "deb 目录不在：$debDir" }
$debs = Get-ChildItem -LiteralPath $debDir -Filter *.deb
if ($debs.Count -lt 50) { throw "deb 只有 $($debs.Count) 个，闭包不完整" }

if (-not $TermuxApk) {
    $cand = Join-Path $Root 'dist\_termux.apk'
    if (Test-Path -LiteralPath $cand) { $TermuxApk = $cand }
}
if (-not $TermuxApk -or -not (Test-Path -LiteralPath $TermuxApk)) { throw 'Termux 安装包不在（dist\_termux.apk）' }
if (-not (Test-Path -LiteralPath $AndroidJar)) { throw "android.jar 不在：$AndroidJar" }

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDebs | Out-Null

foreach ($f in $debs) {
    Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $outDebs $f.Name)
}
Copy-Item -LiteralPath $TermuxApk -Destination (Join-Path $out 'termux.apk')
Copy-Item -LiteralPath $AndroidJar -Destination (Join-Path $out 'android.jar')
$sh = Join-Path $PSScriptRoot 'offline\install_offline.sh'
Copy-Item -LiteralPath $sh -Destination (Join-Path $out 'install_offline.sh')

$manifest = [ordered]@{
    name      = '织戏台离线工具链'
    built     = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    debCount  = $debs.Count
    debBytes  = ($debs | Measure-Object Length -Sum).Sum
    termux    = 'Termux 0.118.3 (com.termux)'
    androidJar = [IO.Path]::GetFileName($AndroidJar)
    entry     = 'install_offline.sh'
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $out 'manifest.json') -Encoding utf8

$total = (Get-ChildItem -LiteralPath $out -Recurse -File | Measure-Object Length -Sum).Sum
"离线素材就位：$out"
"  deb $($debs.Count) 个 / Termux $([math]::Round((Get-Item -LiteralPath $TermuxApk).Length/1MB,1)) MB / jar $([math]::Round((Get-Item -LiteralPath $AndroidJar).Length/1MB,1)) MB"
"  合计 $([math]::Round($total/1MB,1)) MB"
