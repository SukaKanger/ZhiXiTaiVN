# 五期教学视频 v3：片头卡 + 「曼波讲故事」慢速旁白 + 分段字幕 + 真机截图
# 【已废弃 2026-11-14】旧出片器：SRT 用 [int] 四舍五入导致时间码错乱、截图不裁状态栏、按 170 字/分把时长放大到 133–158s。
# 已被 tools\pc\video\build.ps1 + verify.ps1 取代（见 docs\VIDEO-PIPELINE-FIX.md）。要考古跑一次，删掉下面这行 throw。
throw "已废弃：请用 tools\pc\video\build.ps1 -Plan <plan>；校验用 tools\pc\video\verify.ps1"
$root = "C:\Users\Administrator\Doubao\chats\2026-11-13\new-chat\WebGAL-Mobile-Workstation"
$ff = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffmpeg.exe"
$fp = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffprobe.exe"
$build = Join-Path $root "build"
$outDir = Join-Path $build "video\big"
$src = Join-Path $build "video\v2"
$tmp = Join-Path $outDir "tmp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$plan = Get-Content -LiteralPath (Join-Path $build "video\plan5.json") -Raw -Encoding utf8 | ConvertFrom-Json

function Dur($f) { [double](& $fp -hide_banner -v error -show_entries format=duration -of csv=p=0 $f) }

function Tempo($chars, $d) {
  $target = $chars / 150.0 * 60.0
  $t = $d / $target
  if ($t -lt 0.88) { $t = 0.88 }
  if ($t -gt 1.0) { $t = 1.0 }
  return [math]::Round($t, 3)
}

function MakeAudio($id, $charsA, $charsB) {
  $fa = Join-Path $src "$id`A.wav"; $fb = Join-Path $src "$id`B.wav"
  $fh = Join-Path $src "hook$id.wav"
  $ta = Tempo $charsA (Dur $fa)
  $tb = Tempo $charsB (Dur $fb)
  $out = Join-Path $src "${id}final.wav"
  $fc = "[0:a]aresample=44100[h];[3:a]atrim=0:0.30,asetpts=N/SR/TB,asplit=3[s1][s2][s3];[1:a]atempo=$ta,aresample=44100[a0];[2:a]atempo=$tb,aresample=44100[a1];[h][s1][a0][s2][a1][s3]concat=n=6:v=0:a=1[out]"
  & $ff -y -hide_banner -loglevel error -i $fh -i $fa -i $fb -f lavfi -i "anullsrc=r=44100:cl=stereo" -filter_complex $fc -map "[out]" $out
  return @{ file = $out; ta = $ta; tb = $tb; hook = (Dur $fh) }
}

function Esc($t) {
  $t = $t.Replace('\', '\\').Replace(':', '\:').Replace("'", "\u2019").Replace('%', '%%').Replace(',', '\,').Replace('[', '\[').Replace(']', '\]')
  return $t
}

function Shot($img, $dur, $dst) {
  & $ff -y -hide_banner -loglevel error -loop 1 -i $img -t $dur -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b0b0d,format=yuv420p" -r 30 -c:v libx264 -preset veryfast -crf 20 $dst
}

function FindImg($name) {
  $p1 = Join-Path $build $name
  if (Test-Path -LiteralPath $p1) { return $p1 }
  $p2 = Join-Path $src $name
  if (Test-Path -LiteralPath $p2) { return $p2 }
  throw "缺图：$name"
}

$font = "C\:/Windows/Fonts/msyh.ttc"

foreach ($ep in $plan.episodes) {
  $id = $ep.id
  $charsA = 0; $charsB = 0
  foreach ($s in $ep.shots[0..($ep.split - 1)]) { $charsA += $s.sub.Length }
  foreach ($s in $ep.shots[$ep.split..($ep.shots.Count - 1)]) { $charsB += $s.sub.Length }

  $audio = MakeAudio $id $charsA $charsB
  $total = Dur $audio.file
  $hookDur = $audio.hook
  $bodyDur = $total - $hookDur

  # 片头两镜固定占满 hook 时长，其余按字数分 bodyDur
  $restChars = 0
  for ($j = 2; $j -lt $ep.shots.Count; $j++) { $restChars += ($ep.shots[$j].sub.Length + 5) }

  $lines = @(); $filters = @(); $t = 0.0
  for ($i = 0; $i -lt $ep.shots.Count; $i++) {
    $s = $ep.shots[$i]
    if ($i -eq 0) { $d = [math]::Round($hookDur * 0.55, 2) }
    elseif ($i -eq 1) { $d = [math]::Round($hookDur - $hookDur * 0.55, 2) }
    else { $d = [math]::Round(($s.sub.Length + 5) / $restChars * $bodyDur, 2) }
    $clip = Join-Path $tmp ("{0}_{1}.mp4" -f $id, $i)
    Shot (FindImg $s.img) $d $clip
    $lines += "file '$($clip.Replace('\','/'))'"
    # 片头卡自带大字，前两镜不再叠字幕，免得压住页脚
    if ($i -ge 2) {
      $filters += "drawtext=fontfile='$font':text='$(Esc $s.sub)':fontsize=44:fontcolor=white:box=1:boxcolor=0x000000@0.62:boxborderw=22:x=(w-text_w)/2:y=h-text_h-54:enable='between(t,$([math]::Round($t,2)),$([math]::Round(($t+$d),2)))'"
    }
    $t += $d
  }

  $list = Join-Path $tmp "$id.txt"
  [IO.File]::WriteAllText($list, ($lines -join "`n"), (New-Object Text.UTF8Encoding $false))
  $name = "$id-$($ep.title).mp4"
  $out = Join-Path $outDir $name
  & $ff -y -hide_banner -loglevel error -f concat -safe 0 -i $list -i $audio.file -t $total -vf ($filters -join ',') -c:v libx264 -preset medium -crf 21 -pix_fmt yuv420p -c:a aac -b:a 160k -movflags +faststart $out
  $size = (Get-Item -LiteralPath $out).Length
  "$name  $([math]::Round($total,1))s  $size bytes  atempo=$($audio.ta)/$($audio.tb)"
}
