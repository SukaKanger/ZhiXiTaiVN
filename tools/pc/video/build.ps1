# build.ps1 —— 四期教学视频统一出片器（修完旧管线缺陷后的唯一入口）
#
# 修掉的旧缺陷（详见 docs\VIDEO-PIPELINE-FIX.md）：
#   1. SRT 时间码：旧版 SrtTime() 用 [int] 取整，而 PowerShell 的 [int] 是四舍五入，
#      分钟提前进位、区间倒挂。本版一律 [math]::Floor。
#   2. 状态栏：真机截图顶部 90px 手机状态栏一律裁掉（未裁的自动裁，竖屏直接拒绝）。
#   3. 版面：不再 scale+pad 塞黑边，改成 16:9 画布 + 居中应用窗口 + 1px 边框，
#      窗口上下留白承担「期号/字幕」，字幕永远不压 UI。
#   4. 字幕：字号 40、左下起排、最多两行、按标点断行、textfile 传入（绕开 drawtext 转义）。
#   5. 时长：按 4.6 字/秒 给每句停留，单句 2.2–6.5s，全片强制 45–120s。
#   6. 素材守卫：引用 build\video 旧目录直接报错退出。
#   7. 片头卡/尾卡由本脚本重画，信息层级固定，画面里不出现爱发电。
#
# plan JSON（build\video4\plans\NN.json）：
#   { "id":"01", "title":"工作台与两栏收起", "no":"第 1 期"(可省),
#     "hook":"开场句"(可省), "shots":[ {"img":"build\\b39-app0.png","sub":"口播字幕"} ] }
#   img 相对项目根；真机截图须 2388x1080（自动裁状态栏）或 2388x990（已裁）；
#   1920x1080 整图当卡片：不叠字幕、不写 SRT。
#
# 用法： .\build.ps1 -Plan ..\..\..\build\video4\plans\01.json

param(
  [Parameter(Mandatory = $true)][string]$Plan,
  [string]$OutDir,
  [string]$Root
)

$ErrorActionPreference = "Stop"

# ---------- 路径 ----------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Root) { $Root = [System.IO.Path]::GetFullPath((Join-Path $scriptDir "..\..\..")) }
if (-not $OutDir) { $OutDir = Join-Path $Root "build\video4\out" }
$ff = Join-Path $Root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffmpeg.exe"
if (-not (Test-Path -LiteralPath $ff)) { throw "找不到 ffmpeg：$ff" }
if (-not (Test-Path -LiteralPath $Plan)) { throw "找不到 plan：$Plan" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$tmp = Join-Path $OutDir ("_tmp-" + (Get-Date -Format "HHmmss"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

# ---------- 常量 ----------
$W = 1920; $H = 1080
$SB = 90
$WIN_W = 1840
$WIN_X = ($W - $WIN_W) / 2
$FONT = "C\:/Windows/Fonts/msyh.ttc"
$CPS = 4.6
$HEAD_T = 3.2
$TAIL_T = 3.0
$SUB_MAXCHARS = 26
$BG = "0x0b0b0d"
$BORDER = "0x2b2b36"
$HOOK_DEFAULT = "我和 AI 协作开发了一款安卓手机能用的开源视觉小说引擎，支持手机打包 APK。"

Add-Type -AssemblyName System.Drawing

function SrtTime([double]$s) {
  if ($s -lt 0) { $s = 0 }
  $ms = [math]::Floor($s * 1000 + 0.0001)
  $h = [math]::Floor($ms / 3600000)
  $m = [math]::Floor(($ms - $h * 3600000) / 60000)
  $sec = [math]::Floor(($ms - $h * 3600000 - $m * 60000) / 1000)
  $msR = [math]::Floor($ms - $h * 3600000 - $m * 60000 - $sec * 1000)
  return ("{0:D2}:{1:D2}:{2:D2},{3:D3}" -f [int]$h, [int]$m, [int]$sec, [int]$msR)
}

function Wrap-Sub([string]$text) {
  $t = $text.Trim()
  if ($t.Length -le $SUB_MAXCHARS) { return @($t) }
  if ($t.Length -gt ($SUB_MAXCHARS * 2)) {
    throw "字幕太长（$($t.Length) 字 > $($SUB_MAXCHARS*2)）：$t —— 把口播句拆成两句"
  }
  $marks = @("，", "。", "、", "；", "：", "！", "？", " ", "—")
  $cut = -1
  for ($i = $SUB_MAXCHARS; $i -ge 12; $i--) {
    if ($marks -contains $t.Substring($i, 1)) { $cut = $i + 1; break }
  }
  if ($cut -lt 0) { $cut = $SUB_MAXCHARS }
  $l1 = $t.Substring(0, $cut).Trim()
  $l2 = $t.Substring($cut).Trim()
  if ($l2.Length -gt $SUB_MAXCHARS) { $l2 = $l2.Substring(0, $SUB_MAXCHARS) }
  return @($l1, $l2)
}

function Make-Card([string]$path, [string]$kind, [string]$no, [string]$title) {
  $bg = [Drawing.Color]::FromArgb(11, 11, 13)
  $amber = [Drawing.Color]::FromArgb(240, 180, 41)
  $white = [Drawing.Color]::FromArgb(238, 238, 240)
  $grey = [Drawing.Color]::FromArgb(150, 150, 158)
  $fn = "Microsoft YaHei"
  $bmp = New-Object Drawing.Bitmap($W, $H)
  $g = [Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = "AntiAlias"
  $g.TextRenderingHint = "AntiAliasGridFit"
  $g.Clear($bg)
  $penA = New-Object Drawing.Pen($amber, 2)
  $g.DrawLine($penA, 120, 120, 1800, 120)
  if ($kind -eq "head") { $g.DrawLine($penA, 120, 936, 1800, 936) }
  $penB = New-Object Drawing.Pen([Drawing.Color]::FromArgb(48, 48, 56), 2)
  $g.DrawRectangle($penB, 100, 100, 1720, 836)
  $brA = New-Object Drawing.SolidBrush($amber)
  $brW = New-Object Drawing.SolidBrush($white)
  $brG = New-Object Drawing.SolidBrush($grey)

  $f26 = New-Object Drawing.Font($fn, 26)
  $f28 = New-Object Drawing.Font($fn, 28)
  $f30b = New-Object Drawing.Font($fn, 30, [Drawing.FontStyle]::Bold)
  $f52 = New-Object Drawing.Font($fn, 52)
  $f40b = New-Object Drawing.Font($fn, 40, [Drawing.FontStyle]::Bold)
  $f34 = New-Object Drawing.Font($fn, 34)
  $f36 = New-Object Drawing.Font($fn, 36)
  $f64b = New-Object Drawing.Font($fn, 64, [Drawing.FontStyle]::Bold)
  $f22 = New-Object Drawing.Font($fn, 22)

  if ($kind -eq "head") {
    $g.DrawString("ZHI XITAI  ·  VN", $f26, $brG, 150, 145)
    $g.DrawString("织戏台 · 视觉小说工作站", $f30b, $brA, 150, 188)
    $g.DrawString("我和 AI 协作开发了一款", $f52, $brW, 150, 318)
    $g.DrawString("安卓手机能用的开源视觉小说引擎", $f52, $brW, 150, 398)
    $g.DrawString("支持手机打包 APK", $f40b, $brA, 150, 498)
    $g.DrawString("$no · $title", $f34, $brW, 150, 662)
    $g.DrawString("github.com/SukaKanger    ·    详情看主页", $f22, $brG, 150, 862)
  }
  else {
    $g.DrawString("ZHI XITAI  ·  VN", $f26, $brG, 150, 300)
    $g.DrawString("github.com/SukaKanger", $f64b, $brW, 150, 420)
    $g.DrawString("详情看主页", $f36, $brG, 150, 540)
    $g.DrawString("$no · $title", $f28, $brA, 150, 700)
  }
  $bmp.Save($path, [Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
  $f26.Dispose(); $f28.Dispose(); $f30b.Dispose(); $f52.Dispose(); $f40b.Dispose()
  $f34.Dispose(); $f36.Dispose(); $f64b.Dispose(); $f22.Dispose()
  $brA.Dispose(); $brW.Dispose(); $brG.Dispose(); $penA.Dispose(); $penB.Dispose()
}

function Window-Filter([string]$imgPath) {
  $img = [System.Drawing.Bitmap]::FromFile($imgPath)
  try { $iw = $img.Width; $ih = $img.Height } finally { $img.Dispose() }
  if ($ih -gt $iw) { throw "竖屏素材禁止进片：$imgPath" }
  if ($iw -lt 2000) { throw "不是真机横屏截图（宽 $iw < 2000）：$imgPath" }
  if ($ih -eq 1080) { $cropH = 1080 - $SB } elseif ($ih -eq (1080 - $SB)) { $cropH = $ih }
  else { throw "截图高度不认识（$($iw)x$($ih)）：$imgPath" }

  $winH = [int]([math]::Round($WIN_W * $cropH / $iw))
  if ($winH % 2 -ne 0) { $winH = $winH - 1 }
  $winY = [int](($H - $winH) / 2)

  $parts = @()
  if ($ih -eq 1080) { $parts += "crop=${iw}:${cropH}:0:${SB}" }
  $parts += "scale=${WIN_W}:${winH}:flags=lanczos"
  $parts += "pad=${W}:${H}:${WIN_X}:${winY}:color=${BG}"
  $parts += "drawbox=x=${WIN_X}:y=${winY}:w=${WIN_W}:h=${winH}:color=${BORDER}:t=1"
  $parts += "format=yuv420p"
  return ($parts -join ",")
}

# ---------- 主流程 ----------
$data = Get-Content -LiteralPath $Plan -Raw -Encoding utf8 | ConvertFrom-Json
if (-not $data.id -or -not $data.shots -or $data.shots.Count -lt 2) { throw "plan 不完整：需要 id 和至少 2 个 shots" }
$no = if ($data.no) { $data.no } else { "第 $([int]$data.id) 期" }
$hook = if ($data.hook) { $data.hook } else { $HOOK_DEFAULT }
if (-not $data.title) { throw "plan 缺 title" }

$items = @()
foreach ($s in $data.shots) {
  if ($s.card) {
    $kind = [string]$s.card
    if ($kind -ne "head" -and $kind -ne "tail") { throw "card 只能是 head 或 tail：$kind" }
    $items += [pscustomobject]@{ img = $null; sub = $null; isCard = $true; kind = $kind; hold = $(if ($kind -eq "head") { $HEAD_T } else { $TAIL_T }) }
    continue
  }
  if (-not $s.img) { throw "shot 缺 img" }
  if ($s.img -match '(?i)\\build\\video\\') { throw "引用了作废的旧素材：$($s.img)" }
  $imgPath = Join-Path $Root ($s.img -replace '/', '\')
  if (-not (Test-Path -LiteralPath $imgPath)) { throw "缺图：$($s.img)" }
  if (-not $s.sub) { throw "截图镜缺字幕：$($s.img)" }
  if ($s.sub.Length -gt ($SUB_MAXCHARS * 2)) { throw "字幕超出两行上限：$($s.sub)" }
  $hold = [math]::Round(($s.sub.Length / $CPS), 1)
  if ($hold -lt 2.2) { $hold = 2.2 }
  if ($hold -gt 6.5) { $hold = 6.5 }
  $items += [pscustomobject]@{ img = $imgPath; sub = $s.sub; isCard = $false; kind = "shot"; hold = $hold }
}

# 设计固定：片头卡 + 主体 + 尾卡（plan 里没写就自动补，别让执行人自己记）
if (-not ($items[0].isCard -and $items[0].kind -eq "head")) {
  $items = @([pscustomobject]@{ img = $null; sub = $null; isCard = $true; kind = "head"; hold = $HEAD_T }) + $items
}
if (-not ($items[-1].isCard -and $items[-1].kind -eq "tail")) {
  $items += [pscustomobject]@{ img = $null; sub = $null; isCard = $true; kind = "tail"; hold = $TAIL_T }
}

function Body-Total { return (($items | Where-Object { -not $_.isCard } | Measure-Object -Property hold -Sum).Sum) }

$bodyCount = @($items | Where-Object { -not $_.isCard }).Count
if ($bodyCount -eq 0) { throw "plan 里没有真机截图，只有卡片" }

$fixedT = $HEAD_T + $TAIL_T
$bodyT = Body-Total
$total = $fixedT + $bodyT

# 先按比例缩放一次，再用 0.1s 余量逐镜补齐（0.1 的舍入会让比例法卡在 44.9 这种死点）
if ($total -lt 45) {
  $scale = (45 - $fixedT) / $bodyT
  foreach ($it in $items) { if (-not $it.isCard) { $it.hold = [math]::Round([math]::Min($it.hold * $scale, 6.5), 1) } }
}
elseif ($total -gt 120) {
  $scale = (120 - $fixedT) / $bodyT
  foreach ($it in $items) { if (-not $it.isCard) { $it.hold = [math]::Round([math]::Max($it.hold * $scale, 2.0), 1) } }
}

$total = $fixedT + (Body-Total)
for ($guard = 0; $guard -lt 400; $guard++) {
  $total = $fixedT + (Body-Total)
  if ($total -ge 45 -and $total -le 120) { break }
  if ($total -lt 45) {
    $nudged = $false
    foreach ($it in $items) {
      if ($it.isCard -or $it.hold -ge 6.5) { continue }
      $it.hold = [math]::Round([math]::Min($it.hold + 0.1, 6.5), 1); $nudged = $true; break
    }
    if (-not $nudged) { break }
  }
  else {
    $nudged = $false
    foreach ($it in $items) {
      if ($it.isCard -or $it.hold -le 2.0) { continue }
      $it.hold = [math]::Round([math]::Max($it.hold - 0.1, 2.0), 1); $nudged = $true; break
    }
    if (-not $nudged) { break }
  }
}
$bodyT = Body-Total
$total = $fixedT + $bodyT
if ($total -lt 45) { throw "凑不到 45 秒（只有 $([math]::Round($total,1))s）：镜头太少或每句太短，加镜头" }
if ($total -gt 120) { throw "压不进 120 秒（仍有 $([math]::Round($total,1))s）：删镜头，别拖语速" }

$cardDir = Join-Path $OutDir "cards"
New-Item -ItemType Directory -Force -Path $cardDir | Out-Null
if ($items[0].isCard) {
  $p = Join-Path $cardDir ("$($data.id)-head.png")
  Make-Card $p "head" $no $data.title
  $items[0].img = $p; $items[0].hold = $HEAD_T
}
if ($items[-1].isCard) {
  $p = Join-Path $cardDir ("$($data.id)-tail.png")
  Make-Card $p "tail" $no $data.title
  $items[-1].img = $p; $items[-1].hold = $TAIL_T
}

$clipList = @(); $srt = @(); $narration = @(); $cueNo = 0; $t = 0.0
$narration += "织戏台 · 视觉小说工作站 —— $no $($data.title)"
$narration += "总时长 $([math]::Round($total,1)) 秒（片头卡 $HEAD_T s / 主体 $([math]::Round($bodyT,1)) s / 尾卡 $TAIL_T s）；口播语速基准 $CPS 字/秒"
$narration += ""

for ($i = 0; $i -lt $items.Count; $i++) {
  $it = $items[$i]
  $d = $it.hold
  $clip = Join-Path $tmp ("{0}_{1}.mp4" -f $data.id, $i)
  if ($it.isCard) {
    $vf = "scale=${W}:${H},format=yuv420p"
  }
  else {
    $vf = Window-Filter $it.img
    if ($it.sub) {
      $lines = Wrap-Sub $it.sub
      $txt = Join-Path $tmp ("{0}_{1}.txt" -f $data.id, $i)
      [IO.File]::WriteAllText($txt, ($lines -join "`n"), (New-Object Text.UTF8Encoding $false))
      $txtEsc = $txt.Replace('\', '/').Replace(':', '\:')
      $vf = $vf + "," + "drawtext=fontfile='$FONT':textfile='$txtEsc':expansion=none:fontsize=40:fontcolor=white:box=1:boxcolor=0x000000@0.72:boxborderw=20:x=52:y=h-text_h-28"
    }
  }
  & $ff -y -hide_banner -v error -loop 1 -i $it.img -t $d -vf $vf -r 30 -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p $clip
  if ($LASTEXITCODE -ne 0) { throw "出片失败：镜 $i（$($it.img)）" }
  $clipList += "file '$($clip.Replace('\','/'))'"

  if (-not $it.isCard -and $it.sub) {
    $cueNo++
    $srt += "$cueNo"
    $srt += "$(SrtTime $t) --> $(SrtTime ($t + $d))"
    $srt += $it.sub.Trim()
    $srt += ""
  }
  if ($it.isCard) { $narration += "[$(SrtTime $t) - $(SrtTime ($t + $d))] （卡片）$hook" }
  else { $narration += "[$(SrtTime $t) - $(SrtTime ($t + $d))] $($it.sub.Trim())" }
  $t = [math]::Round(($t + $d), 2)
}

$list = Join-Path $tmp "$($data.id)-concat.txt"
[IO.File]::WriteAllText($list, ($clipList -join "`n"), (New-Object Text.UTF8Encoding $false))

$baseName = "$($data.id)-$($data.title)"
$outMp4 = Join-Path $OutDir "$baseName-静音底片.mp4"
& $ff -y -hide_banner -v error -f concat -safe 0 -i $list -c copy -movflags +faststart $outMp4
if ($LASTEXITCODE -ne 0) { throw "拼接失败" }

[IO.File]::WriteAllText((Join-Path $OutDir "$baseName.srt"), ($srt -join "`r`n"), (New-Object Text.UTF8Encoding $false))
[IO.File]::WriteAllText((Join-Path $OutDir "$baseName-旁白.txt"), ($narration -join "`r`n"), (New-Object Text.UTF8Encoding $false))

$size = (Get-Item -LiteralPath $outMp4).Length
"$baseName  总时长 $([math]::Round($t,2))s  字幕 $cueNo 条  $([math]::Round($size/1MB,2)) MB"
"输出：$outMp4"
