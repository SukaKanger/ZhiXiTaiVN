# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# build-station.ps1 —— 在 PC 上把工作站本身打成签名 APK。
#
#   .\tools\pc\build-station.ps1
#   .\tools\pc\build-station.ps1 -Sdk R:\soft\android202422\sdk_home -Out .\dist\custom.apk

param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Root = '',
    [string]$Out = '',
    # 低配模式：剔掉引擎里 36 MB 的三套中文字体，靠系统字体回退。
    # 体积从 27 MB 掉到几 MB，预览时内存压力也小得多。
    [switch]$LiteEngine,
    # 完全体：把 build\offline\assets\offline（Termux + 54 个 deb + android.jar）塞进 assets。
    # 先跑 tools/pc/make-offline-bundle.ps1 摆料，再带这个开关构建。
    [switch]$FullOffline,
    # 默认每次构建内部 build +1（版本号不撞车，覆盖安装不挨拦）。
    # 想复现某次产物、又不想动版本号时加这个。
    [switch]$NoBump
)

. (Join-Path $PSScriptRoot 'lib.ps1')

if (-not $Root) {
    $Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
}

# ---- 版本号：单一来源 VERSION.json → V.java / AndroidManifest.xml
$verArgs = @{ Root = $Root }
if ($NoBump) { $verArgs['Sync'] = $true } else { $verArgs['Bump'] = $true }
$verOut = & (Join-Path $PSScriptRoot 'version.ps1') @verArgs
$verJson = Get-Content -LiteralPath (Join-Path $Root 'VERSION.json') -Raw | ConvertFrom-Json
$tag = ('{0}{1:D2}' -f [int]$verJson.year, [int]$verJson.gen)
# 名字里不挂代际：1.1.5-beta.16。代际 2618 只活在 versionCode 里，
# 不然拦截页上永远显示同一个「2601 系」的号，看着像没升级。
$versionName = "$($verJson.base)-$($verJson.stage)"
if ([int]$verJson.build -gt 1) { $versionName = "$versionName.$($verJson.build)" }
$versionCode = ([int]$verJson.year * 100 + [int]$verJson.gen) * 10000 + [int]$verJson.build

if (-not $Out) {
    $Out = Join-Path $Root "dist\织戏台-ZhiXiTaiVN-$versionName.apk"
}
if ($LiteEngine) {
    $Out = [IO.Path]::ChangeExtension($Out, $null).TrimEnd('.') + '-lite.apk'
}
if ($FullOffline) {
    $Out = Join-Path $Root "dist\织戏台-ZhiXiTaiVN-$versionName-full.apk"
}

Write-Host "织戏台 · ZhiXiTai VN · PC 构建" -ForegroundColor Yellow
Write-Host "  仓库   $Root"
Write-Host "  版本   $versionName（内部 $versionCode，build $($verJson.build)）"

$tk = Resolve-Toolkit -Sdk $Sdk -JavaHome $JavaHome
Write-Host "  SDK    $Sdk"
Write-Host "  JDK    $JavaHome"
Write-Host "  tools  build-tools $($tk.BuildTools) / $(Split-Path -Leaf $tk.AndroidJar)"
Write-Host ""

# ---- 把模板同步进 assets，保证单一来源（改模板只改 templates/ 与 src/java）
$tplSrc = Join-Path $Root 'templates\game'
$tplDst = Join-Path $Root 'src\assets\templates\game'
New-Item -ItemType Directory -Force -Path $tplDst | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $tplDst 'java') | Out-Null
Copy-Item -LiteralPath (Join-Path $tplSrc 'AndroidManifest.xml') -Destination $tplDst -Force
Copy-Item -LiteralPath (Join-Path $tplSrc 'java\GameActivity.java') -Destination (Join-Path $tplDst 'java') -Force

$commonDst = Join-Path $Root 'src\assets\templates\common'
New-Item -ItemType Directory -Force -Path $commonDst | Out-Null
Copy-Item -LiteralPath (Join-Path $Root 'src\java\com\zhixitai\vn\LocalServer.java') `
    -Destination (Join-Path $commonDst 'LocalServer.java') -Force
Write-Host "  模板已同步到 assets/templates"

# ---- 自检：关键资源在位
$engineDir = Join-Path $Root 'src\assets\engine'
if (-not (Test-Path -LiteralPath (Join-Path $engineDir 'index.html'))) {
    throw "引擎资源不在位：$engineDir（应有 index.html / assets / icons）"
}
$legalDir = Join-Path $Root 'src\assets\legal'
foreach ($f in @('NOTICE.txt', 'MPL-2.0.txt', 'LICENSE-MIT.txt', 'DISCLAIMER.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $legalDir $f))) {
        throw "缺少许可文件：$f —— 分部物不合规，先补齐再构建"
    }
}

$assetsDir = Join-Path $Root 'src\assets'
if ($LiteEngine) {
    # 单独铺一份 assets，只把字体剔掉，别动源目录
    $staged = Join-Path $Root 'build\station-lite-assets'
    Remove-Item -LiteralPath $staged -Recurse -Force -ErrorAction SilentlyContinue
    Copy-TreeFiltered -Source (Join-Path $Root 'src\assets') -Destination $staged `
        -ExcludeExtension @('.ttf', '.otf', '.woff', '.woff2')
    $assetsDir = $staged
    $dropped = (Get-ChildItem -LiteralPath (Join-Path $Root 'src\assets\engine\assets') -Filter *.ttf -ErrorAction SilentlyContinue |
        Measure-Object Length -Sum).Sum
    Write-Host "  低配模式：已剔字体 $([math]::Round($dropped/1MB,1)) MB（CSS 会回退到系统字体）"
}

if ($FullOffline) {
    $offlineSrc = Join-Path $Root 'build\offline\assets\offline'
    if (-not (Test-Path -LiteralPath (Join-Path $offlineSrc 'install_offline.sh'))) {
        throw "离线素材没摆好：$offlineSrc —— 先跑 tools\pc\make-offline-bundle.ps1"
    }
    if ($assetsDir -eq (Join-Path $Root 'src\assets')) {
        $staged = Join-Path $Root 'build\station-full-assets'
        Remove-Item -LiteralPath $staged -Recurse -Force -ErrorAction SilentlyContinue
        # 完全体必须保留引擎自带中文字体：它是给玩家用的正式包，不是低配瘦身包。
        Copy-TreeFiltered -Source (Join-Path $Root 'src\assets') -Destination $staged
        $assetsDir = $staged
    }
    $offlineDst = Join-Path $assetsDir 'offline'
    Remove-Item -LiteralPath $offlineDst -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $offlineSrc -Destination $offlineDst -Recurse
    $offSize = (Get-ChildItem -LiteralPath $offlineDst -Recurse -File | Measure-Object Length -Sum).Sum
    Write-Host "  完全体：已塞入离线工具链 $([math]::Round($offSize/1MB,1)) MB（Termux + deb + android.jar）"
}

# ---- 五步
$work = Join-Path $Root 'build\station'
$ks = New-DebugKeystore -Toolkit $tk -Path (Join-Path $Root 'build\debug.keystore')

$final = Build-ApkProject -Toolkit $tk `
    -Manifest (Join-Path $Root 'src\AndroidManifest.xml') `
    -ResDir (Join-Path $Root 'src\res') `
    -AssetsDir $assetsDir `
    -SrcDir (Join-Path $Root 'src\java') `
    -WorkDir $work `
    -OutputApk $Out `
    -Keystore $ks `
    -MinSdk 21 -TargetSdk 34

$size = [math]::Round((Get-Item -LiteralPath $final).Length / 1MB, 2)
Write-Host ""
Write-Host "✔ 产出 $final" -ForegroundColor Green
Write-Host "  体积 $size MB（含 WebGAL 4.6.4 引擎与内置示例工程）"
Write-Host "  签名 调试密钥，正式发布请自备"
Write-Host ""
Write-Host "  adb install -r `"$final`""
