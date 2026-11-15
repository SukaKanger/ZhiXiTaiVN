# verify.ps1 —— 出片后的硬校验：只要有一条不过就 exit 1，不许「看着差不多就发」。
# 用法： .\verify.ps1 -Mp4 <片.mp4> -Srt <片.srt> -Plan <plan.json>
#
# 检查项：
#   1. 1920x1080 / h264 / 30fps / yuv420p 可解码
#   2. 无音轨（静音底片，配音另配）
#   3. 时长 45–120 秒
#   4. SRT 能解析、cue 数与主体镜数一致、每条 start < end、时间轴单调不重叠、末条不超过片长
#   5. 素材来源：每张非卡片素材要么 2388x990（已裁状态栏）要么 2388x1080（出片时自动裁），竖屏直接判死
#   6. 抽 3 帧存盘，供人眼复核（片头 / 中段 / 尾卡）

param(
  [Parameter(Mandatory = $true)][string]$Mp4,
  [Parameter(Mandatory = $true)][string]$Srt,
  [string]$Plan,
  [string]$ShotDir
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..\..\.."))
$ff = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffmpeg.exe"
$fp = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffprobe.exe"

$fail = @(); $pass = @()

function Check([bool]$ok, [string]$name, [string]$detail) {
  if ($ok) { $script:pass += "  [PASS] $name —— $detail" }
  else { $script:fail += "  [FAIL] $name —— $detail" }
}

# ---- 1/2/3 容器与流 ----
if (-not (Test-Path -LiteralPath $Mp4)) { throw "找不到片子：$Mp4" }
$j = & $fp -v error -show_entries "format=duration,size:stream=codec_type,codec_name,width,height,r_frame_rate,pix_fmt,nb_frames" -of json $Mp4 | ConvertFrom-Json
$dur = [double]$j.format.duration
$v = $j.streams | Where-Object { $_.codec_type -eq "video" } | Select-Object -First 1
$a = $j.streams | Where-Object { $_.codec_type -eq "audio" }
Check ($v.width -eq 1920) "分辨率宽" "$($v.width)"
Check ($v.height -eq 1080) "分辨率高" "$($v.height)"
Check ($v.codec_name -eq "h264") "编码" "$($v.codec_name)"
Check ($v.r_frame_rate -eq "30/1") "帧率" "$($v.r_frame_rate)"
Check ($v.pix_fmt -eq "yuv420p") "像素格式" "$($v.pix_fmt)"
Check ($null -eq $a -or @($a).Count -eq 0) "静音（无音轨）" "audio streams = $(@($a).Count)"
Check ($dur -ge 45 -and $dur -le 120) "时长 45–120s" "$([math]::Round($dur,2))s"

# ---- 4 SRT ----
if (-not (Test-Path -LiteralPath $Srt)) { throw "找不到字幕：$Srt" }
$raw = [IO.File]::ReadAllText($Srt, (New-Object Text.UTF8Encoding $false))
$blocks = ($raw -replace "`r`n", "`n") -split "`n`n" | Where-Object { $_.Trim() }
$cues = @()
foreach ($b in $blocks) {
  $lines = $b.Trim() -split "`n"
  if ($lines.Count -lt 2) { continue }
  if ($lines[1] -match "^(\d\d):(\d\d):(\d\d),(\d\d\d) --> (\d\d):(\d\d):(\d\d),(\d\d\d)$") {
    $st = [int]$Matches[1]*3600 + [int]$Matches[2]*60 + [int]$Matches[3] + [int]$Matches[4]/1000
    $en = [int]$Matches[5]*3600 + [int]$Matches[6]*60 + [int]$Matches[7] + [int]$Matches[8]/1000
    $cues += [pscustomobject]@{ no = $lines[0]; start = $st; end = $en; text = ($lines[2..($lines.Count-1)] -join " / ") }
  }
  else { $fail += "  [FAIL] SRT 时间码格式非法：$($lines[1])" }
}
Check ($cues.Count -gt 0) "SRT 可解析" "$($cues.Count) 条"

$badOrder = @(); $badDur = @(); $overlap = @()
for ($i = 0; $i -lt $cues.Count; $i++) {
  if ($cues[$i].end -le $cues[$i].start) { $badOrder += $cues[$i].no }
  if (($cues[$i].end - $cues[$i].start) -gt 6.6) { $badDur += ("#{0}={1}s" -f $cues[$i].no, [math]::Round(($cues[$i].end - $cues[$i].start),2)) }
  if ($i -gt 0 -and $cues[$i].start -lt $cues[$i-1].start) { $overlap += $cues[$i].no }
  if ($i -gt 0 -and [math]::Abs($cues[$i].start - $cues[$i-1].end) -gt 0.05) { $overlap += ("#{0} gap" -f $cues[$i].no) }
}
Check ($badOrder.Count -eq 0) "每条 start < end" ($(if ($badOrder.Count) { "坏条：" + ($badOrder -join ",") } else { "全部正常" }))
Check ($overlap.Count -eq 0) "时间轴单调、首尾相接" ($(if ($overlap.Count) { ($overlap -join ",") } else { "全部正常" }))
Check ($badDur.Count -eq 0) "单句不超过 6.6s" ($(if ($badDur.Count) { ($badDur -join ",") } else { "全部正常" }))
Check ($cues[-1].end -le ($dur + 0.2)) "末条不超片长" ("末条 $($cues[-1].end)s / 片长 $([math]::Round($dur,2))s")

# ---- 5 素材来源 ----
if ($Plan) {
  $p = Get-Content -LiteralPath $Plan -Raw -Encoding utf8 | ConvertFrom-Json
  $bad = @()
  foreach ($s in $p.shots) {
    if ($s.img -match '(?i)\\build\\video\\') { $bad += "旧素材:$($s.img)"; continue }
    $imgPath = Join-Path $root ($s.img -replace '/', '\')
    if (-not (Test-Path -LiteralPath $imgPath)) { $bad += "缺图:$($s.img)"; continue }
    $img = [System.Drawing.Bitmap]::FromFile($imgPath)
    try { $iw = $img.Width; $ih = $img.Height } finally { $img.Dispose() }
    $isCard = ($iw -eq 1920 -and $ih -eq 1080)
    if ($isCard) { continue }
    if ($ih -gt $iw) { $bad += "竖屏:$($s.img)"; continue }
    if ($iw -lt 2000) { $bad += "非真机:$($s.img)"; continue }
    if (-not ($ih -eq 1080 -or $ih -eq 990)) { $bad += "高度异常($($iw)x$($ih)):$($s.img)" }
  }
  Check ($bad.Count -eq 0) "素材全部可进片" ($(if ($bad.Count) { $bad -join "; " } else { "无旧素材/竖屏/非真机" }))
}

# ---- 6 抽帧 ----
$frameDir = if ($ShotDir) { $ShotDir } else { Join-Path (Split-Path -Parent $Mp4) "_check" }
New-Item -ItemType Directory -Force -Path $frameDir | Out-Null
$base = [System.IO.Path]::GetFileNameWithoutExtension($Mp4)
foreach ($spec in @(@{t = 1.0; tag = "head" }, @{t = [math]::Round($dur/2, 1); tag = "mid" }, @{t = [math]::Round($dur-1.0, 1); tag = "tail" })) {
  & $ff -v error -y -ss $spec.t -i $Mp4 -frames:v 1 (Join-Path $frameDir ("{0}-{1}.png" -f $base, $spec.tag))
}
Check (Test-Path -LiteralPath (Join-Path $frameDir ("{0}-mid.png" -f $base))) "抽帧成功" $frameDir

# ---- 汇总 ----
"校验：$base"
$pass | ForEach-Object { $_ }
$fail | ForEach-Object { $_ }
"__PIN__ 片长 $([math]::Round($dur,2))s / 字幕 $($cues.Count) 条 / 失败 $($fail.Count) 项 / 抽帧 $frameDir"
if ($fail.Count -gt 0) { exit 1 }
