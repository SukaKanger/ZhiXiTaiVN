# tap.ps1 —— 按「文字」点按钮，坐标来自 uiautomator dump，不靠截图目测。
# 修的是 docs\BUG-ENV-CLICK.md 记的那个坑：目测 y 偏高 50–60px → 点空；离屏节点 bounds=[0,0][0,0] → 点到 (0,0)。
#
# 用法：
#   .\tap.ps1 -Text "释放离线工具包"            # 真点
#   .\tap.ps1 -Text "释放离线工具包" -DumpOnly   # 只 dump 和定位，不点（安全）
#   .\tap.ps1 -Text "工作台" -Serial 10ACCU1XH4003XK
#
# 行为：命中唯一节点 → 取其 bounds 中心点 → adb shell input tap。
#       命中多个 → 全部列出并退出（让你改 Text，别瞎点）。
#       节点 bounds 宽或高为 0（离屏）→ 跳过并提示先滚动。
#       找不到 → 退出码 2。

param(
  [Parameter(Mandatory = $true)][string]$Text,
  [string]$Serial = "10ACCU1XH4003XK",
  [string]$Adb = "R:\soft\android202422\sdk_home\platform-tools\adb.exe",
  [switch]$DumpOnly
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Adb)) { throw "找不到 adb：$Adb" }

$remote = "/sdcard/window_dump.xml"
$local = Join-Path ([System.IO.Path]::GetTempPath()) ("uidump-" + (Get-Date -Format "HHmmss") + ".xml")

& $Adb -s $Serial shell uiautomator dump $remote | Out-Null
& $Adb -s $Serial pull $remote $local | Out-Null
if (-not (Test-Path -LiteralPath $local)) { throw "dump 拉取失败" }

[xml]$xml = Get-Content -LiteralPath $local -Raw -Encoding utf8
$nodes = $xml.SelectNodes("//node")
$hits = @()
foreach ($n in $nodes) {
  $label = $n.GetAttribute("text")
  $desc = $n.GetAttribute("content-desc")
  if (($label -and $label -match [regex]::Escape($Text)) -or ($desc -and $desc -match [regex]::Escape($Text))) {
    $b = $n.GetAttribute("bounds")
    if ($b -match "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") {
      $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
      if (($x2 - $x1) -le 0 -or ($y2 - $y1) -le 0) {
        "跳过离屏节点（bounds=$b）：text=$label desc=$desc —— 先滚动到可见再点"
        continue
      }
      $hits += [pscustomobject]@{
        text = $label; desc = $desc; bounds = $b
        x = [int](($x1 + $x2) / 2); y = [int](($y1 + $y2) / 2)
        cls = $n.GetAttribute("class"); clickable = $n.GetAttribute("clickable")
      }
    }
  }
}

if ($hits.Count -eq 0) { "没找到含「$Text」的节点（dump：$local）"; exit 2 }
if ($hits.Count -gt 1) {
  "命中 $($hits.Count) 个，拒绝乱点。请换更精确的 Text："
  $hits | Select-Object text, desc, bounds, x, y, clickable | Format-Table -AutoSize | Out-String | Write-Output
  exit 3
}

$h = $hits[0]
"命中：text=$($h.text) / desc=$($h.desc) / bounds=$($h.bounds) / clickable=$($h.clickable) / 点 ($($h.x),$($h.y))"
if (-not $DumpOnly) {
  & $Adb -s $Serial shell input tap $h.x $h.y | Out-Null
  "已 tap $($h.x) $($h.y)"
}
Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
