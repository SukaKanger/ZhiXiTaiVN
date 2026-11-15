# 五期「静音底片」：按 170 字/分留白，只出画面+字幕+SRT，方便自己配音
# 【已废弃 2026-11-14】本脚本的 SrtTime() 用 [int] 取整（PowerShell 是四舍五入），分钟提前进位，
# 会产出 00:01:60,990 这种非法时间码、甚至 start > end；同时截图不裁状态栏、版面留 106px 黑边。
# 已被 tools\pc\video\build.ps1 + verify.ps1 取代（见 docs\VIDEO-PIPELINE-FIX.md）。要考古跑一次，删掉下面这行 throw。
throw "已废弃：请用 tools\pc\video\build.ps1 -Plan <plan>；校验用 tools\pc\video\verify.ps1"
$root = "C:\Users\Administrator\Doubao\chats\2026-11-13\new-chat\WebGAL-Mobile-Workstation"
$ff = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffmpeg.exe"
$fp = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffprobe.exe"
$build = Join-Path $root "build"
$outDir = Join-Path $build "video\dub"
$src = Join-Path $build "video\v2"
$tmp = Join-Path $outDir "tmp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$plan = Get-Content -LiteralPath (Join-Path $build "video\plan5.json") -Raw -Encoding utf8 | ConvertFrom-Json
$CPM = 170.0

function Dur($f) { [double](& $fp -hide_banner -v error -show_entries format=duration -of csv=p=0 $f) }

function Esc($t) {
  $t = $t.Replace('\', '\\').Replace(':', '\:').Replace("'", "\u2019").Replace('%', '%%').Replace(',', '\,').Replace('[', '\[').Replace(']', '\]')
  return $t
}

function Shot($img, $d, $dst) {
  & $ff -y -hide_banner -loglevel error -loop 1 -i $img -t $d -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b0b0d,format=yuv420p" -r 30 -c:v libx264 -preset veryfast -crf 20 $dst
}

function FindImg($name) {
  $p1 = Join-Path $build $name
  if (Test-Path -LiteralPath $p1) { return $p1 }
  $p2 = Join-Path $src $name
  if (Test-Path -LiteralPath $p2) { return $p2 }
  throw "缺图：$name"
}

function SrtTime($s) {
  $ms = [int]([math]::Round($s * 1000))
  $h = [int]($ms / 3600000); $m = [int](($ms % 3600000) / 60000); $sec = [int](($ms % 60000) / 1000); $r = [int]($ms % 1000)
  return ("{0:D2}:{1:D2}:{2:D2},{3:D3}" -f $h, $m, $sec, $r)
}

$font = "C\:/Windows/Fonts/msyh.ttc"

foreach ($ep in $plan.episodes) {
  $id = $ep.id
  $charsA = 0; $charsB = 0
  foreach ($s in $ep.shots[0..($ep.split - 1)]) { $charsA += $s.sub.Length }
  foreach ($s in $ep.shots[$ep.split..($ep.shots.Count - 1)]) { $charsB += $s.sub.Length }

  $hookFile = Join-Path $src "hook$id.wav"
  $hookDur = Dur $hookFile
  $sayDur = ($charsA + $charsB) / $CPM * 60.0 + 0.6

  $restChars = 0
  for ($j = 2; $j -lt $ep.shots.Count; $j++) { $restChars += ($ep.shots[$j].sub.Length + 5) }

  $lines = @(); $filters = @(); $t = 0.0; $srt = @(); $n = 0
  for ($i = 0; $i -lt $ep.shots.Count; $i++) {
    $s = $ep.shots[$i]
    if ($i -eq 0) { $d = [math]::Round($hookDur * 0.55, 2) }
    elseif ($i -eq 1) { $d = [math]::Round($hookDur - $hookDur * 0.55, 2) }
    else {
      $d = [math]::Round(($s.sub.Length + 5) / $restChars * $sayDur, 2)
      if ($d -lt 1.6) { $d = 1.6 }
    }
    $clip = Join-Path $tmp ("{0}_{1}.mp4" -f $id, $i)
    Shot (FindImg $s.img) $d $clip
    $lines += "file '$($clip.Replace('\','/'))'"
    if ($i -ge 2) {
      $filters += "drawtext=fontfile='$font':text='$(Esc $s.sub)':fontsize=44:fontcolor=white:box=1:boxcolor=0x000000@0.62:boxborderw=22:x=(w-text_w)/2:y=h-text_h-54:enable='between(t,$([math]::Round($t,2)),$([math]::Round(($t+$d),2)))'"
      $n++
      $srt += "$n"
      $srt += "$(SrtTime $t) --> $(SrtTime ($t + $d))"
      $srt += $s.sub
      $srt += ""
    }
    $t += $d
  }

  $list = Join-Path $tmp "$id.txt"
  [IO.File]::WriteAllText($list, ($lines -join "`n"), (New-Object Text.UTF8Encoding $false))
  $name = "$id-$($ep.title)-静音底片.mp4"
  $out = Join-Path $outDir $name
  & $ff -y -hide_banner -loglevel error -f concat -safe 0 -i $list -vf ($filters -join ',') -c:v libx264 -preset medium -crf 21 -pix_fmt yuv420p -an -movflags +faststart $out
  [IO.File]::WriteAllText((Join-Path $outDir "$id-$($ep.title).srt"), ($srt -join "`r`n"), (New-Object Text.UTF8Encoding $false))
  "$name  $([math]::Round($t,1))s  $((Get-Item -LiteralPath $out).Length) bytes  字幕 $n 条"
}
