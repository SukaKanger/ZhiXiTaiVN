# 生成 3 期玩家教学视频：真机截图 + 旁白 + 字幕（ffmpeg）
# 【已废弃 2026-11-14】旧出片器：SRT 用 [int] 四舍五入导致时间码错乱、截图不裁状态栏、按 170 字/分把时长放大到 133–158s。
# 已被 tools\pc\video\build.ps1 + verify.ps1 取代（见 docs\VIDEO-PIPELINE-FIX.md）。要考古跑一次，删掉下面这行 throw。
throw "已废弃：请用 tools\pc\video\build.ps1 -Plan <plan>；校验用 tools\pc\video\verify.ps1"
$ErrorOnly = $null
$root = "C:\Users\Administrator\Doubao\chats\2026-11-13\new-chat\WebGAL-Mobile-Workstation"
$ff = Join-Path $root "tools\pc\ffmpeg\ffmpeg-9.0.2-essentials_build\bin\ffmpeg.exe"
$build = Join-Path $root "build"
$outDir = Join-Path $build "video"
$tmp = Join-Path $outDir "tmp"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Shot($src, $dur, $dst) {
  & $ff -y -hide_banner -loglevel error -loop 1 -i $src -t $dur -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b0b0d,format=yuv420p" -r 30 -c:v libx264 -preset veryfast -crf 20 $dst
}

$ep1 = @(
  @{ img = "b30-after-gate.png"; dur = 7.0 },
  @{ img = "b29-base.png";       dur = 6.0 },
  @{ img = "b29-collapsed-both.png"; dur = 6.0 },
  @{ img = "b28-dock-open.png";  dur = 6.0 }
)
$ep2 = @(
  @{ img = "v2-step1.png"; dur = 4.8 },
  @{ img = "v2-step2b.png"; dur = 4.8 },
  @{ img = "v2-step4.png"; dur = 4.8 },
  @{ img = "guide8-step4.png"; dur = 4.8 },
  @{ img = "guide8-step5.png"; dur = 4.8 },
  @{ img = "guide8-step6.png"; dur = 4.8 },
  @{ img = "guide8-step7.png"; dur = 4.8 },
  @{ img = "b28-dock-open.png"; dur = 4.5 }
)
$ep3 = @(
  @{ img = "ask-state.png";    dur = 8.0 },
  @{ img = "ask-fill.png";     dur = 7.0 },
  @{ img = "ask-success.png";  dur = 8.0 },
  @{ img = "b28-dock-open.png";dur = 7.0 },
  @{ img = "ask-result5.png";  dur = 5.0 }
)

function BuildList($name, $shots) {
  $list = Join-Path $tmp "$name.txt"
  $lines = @()
  $i = 0
  foreach ($s in $shots) {
    $clip = Join-Path $tmp ("{0}_{1}.mp4" -f $name, $i)
    Shot (Join-Path $build $s.img) $s.dur $clip
    $lines += "file '$($clip.Replace('\','/'))'"
    $i++
  }
  [IO.File]::WriteAllText($list, ($lines -join "`n"), (New-Object Text.UTF8Encoding $false))
  return $list
}

$l1 = BuildList "ep1" $ep1
$l2 = BuildList "ep2" $ep2
$l3 = BuildList "ep3" $ep3

$font = "C\:/Windows/Fonts/msyh.ttc"

# 字幕：每条 [开始, 结束, 文案]
$sub1 = @(
  @(0.5, 6.8, "启动先挑项目：跟 PS 一样，不直接甩你进工作台"),
  @(7.0, 12.8, "两个栏：左边工程库、右边打包台"),
  @(13.0, 18.8, "顶上「收起」一按，整栏缩成竖条，舞台铺满"),
  @(19.0, 24.6, "右下「问」浮球常在，第三期专门讲")
)
$sub2 = @(
  @(0.3, 4.6, "第 1 步 · 工程库：带「引导」标签的是真工程"),
  @(4.8, 9.4, "第 2 步 · 预览舞台：内嵌窗口跑引擎，点重载看新内容"),
  @(9.6, 14.2, "第 3 步 · 场景与素材：场景名切文件，新建别用空格斜杠"),
  @(14.4, 19.0, "第 4 步 · 底部快捷插入：旁白、台词、换背景、跳场景"),
  @(19.2, 23.8, "第 5 步 · 打包台：应用名、包名、版本号"),
  @(24.0, 28.6, "第 6 步 · 签名：内置调试只能自测，上架换自己的 keystore"),
  @(28.8, 33.4, "第 7 步 · 环境：权限、Termux、工具包全绿才打包"),
  @(33.6, 38.0, "第 8 步 · 不会就问：右下浮球，走完就不再打扰")
)
$sub3 = @(
  @(0.3, 7.6, "浮球点开就是问答浮窗；收起后球还在"),
  @(7.8, 14.6, "接口地址写全到 chat/completions，模型名照抄服务商"),
  @(14.8, 22.6, "Key 只存本机；配置在，冷启动也不会丢"),
  @(22.8, 29.6, "回答带软件用法上下文，末尾标注「AI 生成可能有误」"),
  @(29.8, 34.8, "报错先看提示，别硬猜；三期到此，够上手")
)

function DrawTexts($subs) {
  $filters = @()
  foreach ($s in $subs) {
    $a = [double]$s[0]; $b = [double]$s[1]; $txt = $s[2]
    $esc = $txt.Replace('\', '\\').Replace(':', '\:').Replace("'", "\u2019").Replace('%', '%%').Replace(',', '\,').Replace('[', '\[').Replace(']', '\]')
    $filters += "drawtext=fontfile='$font':text='$esc':fontsize=44:fontcolor=white:box=1:boxcolor=0x000000@0.62:boxborderw=22:x=(w-text_w)/2:y=h-text_h-54:enable='between(t,$a,$b)'"
  }
  return ($filters -join ',')
}

function Compose($name, $list, $subs, $audio, $dur) {
  $filter = DrawTexts $subs
  $out = Join-Path $outDir ($name + ".mp4")
  & $ff -y -hide_banner -loglevel error -f concat -safe 0 -i $list -i $audio -t $dur -vf $filter -c:v libx264 -preset medium -crf 21 -pix_fmt yuv420p -c:a aac -b:a 160k -movflags +faststart $out
  return $out
}

$o1 = Compose "01-开箱与收起两栏" $l1 $sub1 (Join-Path $outDir "ep1.wav") 25.0
$o2 = Compose "02-八步引导走一遍" $l2 $sub2 (Join-Path $outDir "ep2.wav") 38.2
$o3 = Compose "03-不会就问与自带API" $l3 $sub3 (Join-Path $outDir "ep3.wav") 34.9

Get-ChildItem -LiteralPath $outDir -Filter "0*.mp4" | Select-Object Name, Length
