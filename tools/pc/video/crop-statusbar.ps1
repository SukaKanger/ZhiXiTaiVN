# crop-statusbar.ps1 —— 把真机横屏截图（2388x1080）顶部的手机状态栏裁掉。
# 用法：
#   .\crop-statusbar.ps1 -InDir ..\..\..\build -Filter "b39-*.png" -OutDir ..\..\..\build\video4\shots
#   .\crop-statusbar.ps1 -InFile <单张> -OutFile <输出>
# 说明：状态栏高度在 480dpi 下固定 90px（android mAppBounds 顶边 = 90），裁完为 2388x990。
# 要求：截图必须是横屏、宽 > 2000、高 = 1080；已经是 990 高的会原样复制（幂等）。

param(
  [string]$InDir,
  [string]$Filter = "b39-*.png",
  [string]$InFile,
  [Parameter(Mandatory = $true)][string]$OutDir
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$SB = 90          # 状态栏高度（px）
$RAW_H = 1080     # 未裁前的横屏高度

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Crop-One([string]$src, [string]$dst) {
  $img = [System.Drawing.Bitmap]::FromFile($src)
  try {
    $w = $img.Width; $h = $img.Height
    if ($h -gt $w) { throw "竖屏素材禁止进片：$src（$($w)x$($h)）" }
    if ($w -le 2000) { throw "不是 2388 宽的真机横屏截图：$src（$($w)x$($h)）" }
    if ($h -eq $RAW_H) {
      $rect = New-Object System.Drawing.Rectangle(0, $SB, $w, ($h - $SB))
      $crop = $img.Clone($rect, $img.PixelFormat)
      $crop.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
      $crop.Dispose()
      return "cropped $([System.IO.Path]::GetFileName($src)) -> $($w)x$($h-$SB)"
    }
    elseif ($h -eq ($RAW_H - $SB)) {
      Copy-Item -LiteralPath $src -Destination $dst -Force
      return "already-cropped $([System.IO.Path]::GetFileName($src)) -> $($w)x$h"
    }
    else { throw "高度不认识，既不是 $RAW_H 也不是 $($RAW_H-$SB)：$src（$($w)x$($h)）" }
  }
  finally { $img.Dispose() }
}

$done = 0
if ($InFile) {
  $name = [System.IO.Path]::GetFileNameWithoutExtension($InFile) + "-nowstat.png"
  $msg = Crop-One $InFile (Join-Path $OutDir $name)
  "$msg"; $done = 1
}
else {
  if (-not $InDir) { throw "要么给 -InFile，要么给 -InDir" }
  $files = Get-ChildItem -LiteralPath $InDir -Filter $Filter -File | Sort-Object Name
  if (-not $files) { throw "在 $InDir 下没找到匹配 $Filter 的素材" }
  foreach ($f in $files) {
    $name = $f.BaseName + "-nowstat.png"
    Crop-One $f.FullName (Join-Path $OutDir $name)
    $done++
  }
}
"共处理 $done 张，输出目录：$OutDir"
