# SPDX-License-Identifier: MIT
# Copyright (c) 2026 WebGAL 移动工作站作者
#
# lib.ps1 —— PC 端的构建管线。工作站 APK 与游戏 APK 都走这里。
# 与 Termux 里的 build_apk.sh 是同一套五步：aapt2 → javac → d8 → zipalign → apksigner。

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-LastExit {
    param([string]$What)
    if ($LASTEXITCODE -ne 0) {
        throw "$What 失败（exit $LASTEXITCODE）"
    }
}

function Resolve-Toolkit {
    param(
        [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
        [string]$JavaHome = $env:JAVA_HOME
    )

    if (-not $Sdk -or -not (Test-Path -LiteralPath $Sdk)) {
        throw "找不到 Android SDK。用 -Sdk 指定，或设 ANDROID_HOME。"
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath $JavaHome)) {
        throw "找不到 JDK。用 -JavaHome 指定，或设 JAVA_HOME。"
    }

    $btRoot = Join-Path $Sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $btRoot)) { throw "SDK 里没有 build-tools：$btRoot" }
    $bt = Get-ChildItem -LiteralPath $btRoot -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } -Descending |
        Select-Object -First 1

    $aapt2 = Join-Path $bt.FullName 'aapt2.exe'
    $d8 = Join-Path $bt.FullName 'd8.bat'
    $zipalign = Join-Path $bt.FullName 'zipalign.exe'
    $apksigner = Join-Path $bt.FullName 'apksigner.bat'
    foreach ($p in @($aapt2, $d8, $zipalign, $apksigner)) {
        if (-not (Test-Path -LiteralPath $p)) { throw "build-tools 里缺工具：$p" }
    }

    $platRoot = Join-Path $Sdk 'platforms'
    if (-not (Test-Path -LiteralPath $platRoot)) { throw "SDK 里没有 platforms：$platRoot" }
    $jar = $null
    foreach ($p in (Get-ChildItem -LiteralPath $platRoot -Directory | Sort-Object Name -Descending)) {
        $candidate = Join-Path $p.FullName 'android.jar'
        if (Test-Path -LiteralPath $candidate) { $jar = $candidate; break }
    }
    if (-not $jar) { throw "platforms 里没有可用的 android.jar" }

    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $keytool = Join-Path $JavaHome 'bin\keytool.exe'
    $jarExe = Join-Path $JavaHome 'bin\jar.exe'
    foreach ($p in @($javac, $keytool)) {
        if (-not (Test-Path -LiteralPath $p)) { throw "JDK 里缺：$p" }
    }

    return [pscustomobject]@{
        Aapt2      = $aapt2
        D8         = $d8
        ZipAlign   = $zipalign
        ApkSigner  = $apksigner
        AndroidJar = $jar
        Javac      = $javac
        Keytool    = $keytool
        Jar        = $jarExe
        BuildTools = $bt.Name
        JavaHome   = $JavaHome
    }
}

function Invoke-Aapt2Package {
    param(
        [pscustomobject]$Toolkit,
        [string]$Manifest,
        [string]$ResDir,
        [string]$AssetsDir,
        [string]$WorkDir,
        [int]$MinSdk = 21,
        [int]$TargetSdk = 34
    )

    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
    $resZip = Join-Path $WorkDir 'res.zip'
    $baseApk = Join-Path $WorkDir 'base.apk'
    Remove-Item -LiteralPath $resZip, $baseApk -Force -ErrorAction SilentlyContinue

    if (Test-Path -LiteralPath $ResDir) {
        & $Toolkit.Aapt2 compile --dir $ResDir -o $resZip 2>&1 | Out-Null
        Assert-LastExit 'aapt2 compile'
    }

    $linkArgs = @('link', '-o', $baseApk, '-I', $Toolkit.AndroidJar,
        '--manifest', $Manifest, '-A', $AssetsDir,
        '--min-sdk-version', "$MinSdk", '--target-sdk-version', "$TargetSdk",
        '--auto-add-overlay')
    if (Test-Path -LiteralPath $resZip) {
        $linkArgs += @('-R', $resZip)
    }

    & $Toolkit.Aapt2 @linkArgs 2>&1 | Out-Null
    Assert-LastExit 'aapt2 link'

    if (-not (Test-Path -LiteralPath $baseApk)) { throw "aapt2 没产出 base.apk" }
    return $baseApk
}

function Invoke-JavaCompile {
    param(
        [pscustomobject]$Toolkit,
        [string]$SrcDir,
        [string]$ClassesDir
    )

    Remove-Item -LiteralPath $ClassesDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null

    $sources = Get-ChildItem -LiteralPath $SrcDir -Recurse -Filter *.java |
        ForEach-Object { $_.FullName }
    if (-not $sources -or $sources.Count -eq 0) { throw "源码目录里没有 .java：$SrcDir" }

    & $Toolkit.Javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options `
        -bootclasspath $Toolkit.AndroidJar -d $ClassesDir @sources 2>&1 | Out-Null
    Assert-LastExit 'javac'

    $classCount = (Get-ChildItem -LiteralPath $ClassesDir -Recurse -Filter *.class).Count
    if ($classCount -eq 0) { throw "javac 没产出 class" }
    return $classCount
}

function Invoke-Dexing {
    param(
        [pscustomobject]$Toolkit,
        [string]$ClassesDir,
        [string]$DexDir,
        [int]$MinSdk = 21
    )

    Remove-Item -LiteralPath $DexDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $DexDir | Out-Null

    $classFiles = Get-ChildItem -LiteralPath $ClassesDir -Recurse -Filter *.class |
        ForEach-Object { $_.FullName }
    # 57 个 class 的全路径已经超过 Windows 命令行上限，d8 支持 @argfile（一行一个参数）。
    $argFile = Join-Path $DexDir 'd8-args.txt'
    $d8Args = @('--lib', $Toolkit.AndroidJar, '--min-api', "$MinSdk", '--output', $DexDir) + $classFiles
    [IO.File]::WriteAllLines($argFile, $d8Args, (New-Object Text.UTF8Encoding $false))
    & $Toolkit.D8 "@$argFile" 2>&1 | Out-Null
    Assert-LastExit 'd8'

    $dex = Join-Path $DexDir 'classes.dex'
    if (-not (Test-Path -LiteralPath $dex)) { throw "d8 没产出 classes.dex" }
    return $dex
}

function Add-DexToApk {
    param([string]$Apk, [string]$Dex)

    # 用 python 的 zipfile 追加，不动已有条目（resources.arsc 必须保持未压缩）
    $py = @'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_STORED) as z:
    names = z.namelist()
    if 'classes.dex' in names:
        z.close()
        raise SystemExit('classes.dex already present')
    z.write(dex, 'classes.dex')
print('ok')
'@
    $out = & python -c $py $Apk $Dex 2>&1
    if ($LASTEXITCODE -ne 0) { throw "写入 classes.dex 失败：$out" }
}

function Invoke-Align {
    param([pscustomobject]$Toolkit, [string]$InputApk, [string]$OutputApk)
    Remove-Item -LiteralPath $OutputApk -Force -ErrorAction SilentlyContinue
    & $Toolkit.ZipAlign -f -p 4 $InputApk $OutputApk 2>&1 | Out-Null
    Assert-LastExit 'zipalign'
    if (-not (Test-Path -LiteralPath $OutputApk)) { throw 'zipalign 没产出文件' }
    return $OutputApk
}

function New-DebugKeystore {
    param([pscustomobject]$Toolkit, [string]$Path)

    if (Test-Path -LiteralPath $Path) { return $Path }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    & $Toolkit.Keytool -genkeypair -keystore $Path -alias webgal -storepass android `
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=WebGAL Mobile Workstation, O=Local Build, C=CN" 2>&1 | Out-Null
    Assert-LastExit 'keytool'
    return $Path
}

function Invoke-Sign {
    param([pscustomobject]$Toolkit, [string]$InputApk, [string]$OutputApk, [string]$Keystore, [int]$MinSdk = 21)

    Remove-Item -LiteralPath $OutputApk -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputApk) | Out-Null
    & $Toolkit.ApkSigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android `
        --min-sdk-version "$MinSdk" --out $OutputApk $InputApk 2>&1 | Out-Null
    Assert-LastExit 'apksigner'

    & $Toolkit.ApkSigner verify --min-sdk-version "$MinSdk" $OutputApk 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'apksigner verify 不通过' }
    return $OutputApk
}

function Build-ApkProject {
    <#
      给定拆开的四样东西（清单 / res / assets / src），走完整五步，产出签名 APK。
      显式传路径，因为工作站源码与打包模板的目录布局不一样。
    #>
    param(
        [pscustomobject]$Toolkit,
        [string]$Manifest,
        [string]$ResDir,
        [string]$AssetsDir,
        [string]$SrcDir,
        [string]$WorkDir,
        [string]$OutputApk,
        [string]$Keystore,
        [int]$MinSdk = 21,
        [int]$TargetSdk = 34
    )

    if (-not (Test-Path -LiteralPath $Manifest)) { throw "缺 AndroidManifest.xml：$Manifest" }
    if (-not (Test-Path -LiteralPath $AssetsDir)) { throw "缺 assets 目录：$AssetsDir" }
    if (-not (Test-Path -LiteralPath $SrcDir)) { throw "缺源码目录：$SrcDir" }

    $baseApk = Invoke-Aapt2Package -Toolkit $Toolkit -Manifest $Manifest -ResDir $ResDir `
        -AssetsDir $AssetsDir -WorkDir $WorkDir -MinSdk $MinSdk -TargetSdk $TargetSdk
    Write-Host "  [1/5] aapt2    base.apk $([math]::Round((Get-Item -LiteralPath $baseApk).Length/1MB,2)) MB"

    $classesDir = Join-Path $WorkDir 'classes'
    $classCount = Invoke-JavaCompile -Toolkit $Toolkit -SrcDir $SrcDir -ClassesDir $classesDir
    Write-Host "  [2/5] javac    $classCount 个 class"

    $dexDir = Join-Path $WorkDir 'dex'
    $dex = Invoke-Dexing -Toolkit $Toolkit -ClassesDir $classesDir -DexDir $dexDir -MinSdk $MinSdk
    Add-DexToApk -Apk $baseApk -Dex $dex
    Write-Host "  [3/5] d8       $([math]::Round((Get-Item -LiteralPath $dex).Length/1KB,1)) KB → classes.dex"

    $aligned = Join-Path $WorkDir 'aligned.apk'
    Invoke-Align -Toolkit $Toolkit -InputApk $baseApk -OutputApk $aligned | Out-Null
    Write-Host "  [4/5] zipalign 已 4 字节对齐"

    Invoke-Sign -Toolkit $Toolkit -InputApk $aligned -OutputApk $OutputApk -Keystore $Keystore -MinSdk $MinSdk | Out-Null
    Write-Host "  [5/5] apksigner 签名并校验通过"

    return $OutputApk
}

function Copy-TreeFiltered {
    param([string]$Source, [string]$Destination, [string[]]$ExcludeExtension = @())
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $files = Get-ChildItem -LiteralPath $Source -Recurse -File
    foreach ($f in $files) {
        if ($ExcludeExtension -contains $f.Extension.ToLowerInvariant()) { continue }
        $rel = $f.FullName.Substring($Source.Length).TrimStart('\', '/')
        $target = Join-Path $Destination $rel
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $f.FullName -Destination $target -Force
    }
}
