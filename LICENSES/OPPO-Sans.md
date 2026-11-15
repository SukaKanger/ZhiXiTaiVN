# OPPO Sans 字体许可说明

本仓库的 WebGAL 引擎发行包内含 `OPPOSans-R-*.ttf`（约 9.7 MB）。该字体的版权与
许可不属于本项目，也不在 MIT / MPL-2.0 覆盖范围内。

- 权利人：OPPO 广东移动通信有限公司
- 官方页面：<https://open.oppomobile.com/documentation/page/info?id=13223>
- 官方口径：允许个人或企业免费使用，含商业用途。
- 官方使用条款（原文）：
  1. 不对字体进行改编或二次开发；
  2. 不对外售卖字体；
  3. 不向他方提供其他下载渠道；
  4. 不用于违法用途。

本项目**未修改**该字体文件，仅随 WebGAL 引擎发行包原样分发，供渲染界面与游戏文本
使用，不单独售卖、不单独提供字体下载页。若 OPPO 方面认为此种随包分发不符合其条款，
请从 `src/assets/engine/assets/` 删除 `OPPOSans-R-*.ttf`，界面会自动回退到其余
OFL 字体或系统字体；也可用 `tools/pc/build-station.ps1 -LiteEngine` 打出不含字体
的低配包。

> 这段说明不是法律意见。商业发行前请自行向权利人确认，或直接替换为 OFL 字体。
