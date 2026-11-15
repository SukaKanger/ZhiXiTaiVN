Add-Type -AssemblyName System.Drawing
$dir = "C:\Users\Administrator\Doubao\chats\2026-11-13\new-chat\WebGAL-Mobile-Workstation\build\video\v2"
$eps = @(
  @{ id = "01"; no = "第 1 期"; name = "装包与第一次启动" },
  @{ id = "02"; no = "第 2 期"; name = "工作台与两栏近乎收起" },
  @{ id = "03"; no = "第 3 期"; name = "八步引导每一站在哪" },
  @{ id = "04"; no = "第 4 期"; name = "不会就问 · 配好你自己的 API" },
  @{ id = "05"; no = "第 5 期"; name = "AI 帮你写剧本 · 端上打成 APK" }
)
$W = 1920; $H = 1080
$bg = [Drawing.Color]::FromArgb(11, 11, 13)
$amber = [Drawing.Color]::FromArgb(240, 180, 41)
$white = [Drawing.Color]::FromArgb(238, 238, 240)
$grey = [Drawing.Color]::FromArgb(150, 150, 158)
$fontName = "Microsoft YaHei"
foreach ($e in $eps) {
  $bmp = New-Object Drawing.Bitmap($W, $H)
  $g = [Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = "AntiAlias"
  $g.TextRenderingHint = "AntiAliasGridFit"
  $g.Clear($bg)

  $pen = New-Object Drawing.Pen($amber, 2)
  $g.DrawLine($pen, 120, 120, 1800, 120)
  $g.DrawLine($pen, 120, 960, 1800, 960)
  $pen2 = New-Object Drawing.Pen([Drawing.Color]::FromArgb(48, 48, 56), 2)
  $g.DrawRectangle($pen2, 100, 100, 1720, 880)

  $f1 = New-Object Drawing.Font($fontName, 26, [Drawing.FontStyle]::Regular)
  $f2 = New-Object Drawing.Font($fontName, 30, [Drawing.FontStyle]::Bold)
  $f3 = New-Object Drawing.Font($fontName, 52, [Drawing.FontStyle]::Regular)
  $f4 = New-Object Drawing.Font($fontName, 40, [Drawing.FontStyle]::Bold)
  $f5 = New-Object Drawing.Font($fontName, 34, [Drawing.FontStyle]::Regular)
  $f6 = New-Object Drawing.Font($fontName, 22, [Drawing.FontStyle]::Regular)
  $brA = New-Object Drawing.SolidBrush($amber)
  $brW = New-Object Drawing.SolidBrush($white)
  $brG = New-Object Drawing.SolidBrush($grey)

  $g.DrawString("ZHI XITAI  ·  VN", $f1, $brG, 150, 145)
  $g.DrawString("织戏台 · 视觉小说工作站", $f2, $brA, 150, 190)

  $g.DrawString("我和 AI 协作开发了一款", $f3, $brW, 150, 320)
  $g.DrawString("安卓手机能用的开源视觉小说引擎", $f3, $brW, 150, 400)
  $g.DrawString("支持手机打包 APK", $f4, $brA, 150, 500)

  $g.DrawString(($e.no + " · " + $e.name), $f5, $brW, 150, 660)
  $g.DrawString("github.com/SukaKanger    ·    详情看主页", $f6, $brG, 150, 880)

  $path = Join-Path $dir ("card" + $e.id + ".png")
  $bmp.Save($path, [Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
  "$path"
}
