# 引擎内置字体许可

WebGAL 4.6.4 发行包在 `src/assets/engine/assets/` 内自带三款中文字体。它们不是本项目
原创，也不在 MIT 覆盖范围内。以下许可状态已于 2026-11-14 按官方/上游资料核对。

| 字体 | 文件（含构建哈希后缀） | 体积 | 权利人 | 许可证 | 依据 |
|---|---|---|---|---|---|
| OPPO Sans | `OPPOSans-R-tAcFw8I3.ttf` | 10,152,780 B | OPPO 广东移动通信有限公司 | OPPO 自定义免费商用条款（非 OFL） | <https://open.oppomobile.com/documentation/page/info?id=13223> |
| Resource Han Rounded CN | `ResourceHanRoundedCN-Regular-C1HdCLVq.ttf` | 14,663,464 B | Cyano Hao；部分源自 Adobe | **SIL OFL 1.1** | <https://github.com/CyanoHao/Resource-Han-Rounded>（`OFL-License.txt`） |
| Source Han Serif CN（思源宋体） | `SourceHanSerifCN-Regular-B_f-kQ2u.ttf` | 13,013,172 B | Adobe / Google | **SIL OFL 1.1** | <https://github.com/adobe-fonts/source-han-serif>（`LICENSE.txt`） |

SHA-256：

```
EA92535935F8B5DA18B64BB23E5FFBFEF1417B7AE4FF3FC15372A65EE95A9580  OPPOSans-R-tAcFw8I3.ttf
1C5C623F008EABEF10C45135A48B01B46311F9369C28857355872CFE05F48DC0  ResourceHanRoundedCN-Regular-C1HdCLVq.ttf
1E60CC2EEDFA25BF5E4ECAA794402F581AD770D4C8BE46D338BF52064B307EC7  SourceHanSerifCN-Regular-B_f-kQ2u.ttf
```

## 义务与处置

1. **OFL 字体**（Resource Han Rounded、Source Han Serif）：分发时须附带 OFL 1.1 全文
   与版权声明。本项目已附 `LICENSES/OFL-1.1.txt`，版权声明见上表与各上游
   `OFL-License.txt` / `LICENSE.txt`。未修改字体文件；若修改，须继续以 OFL 1.1
   分发，并遵守 Reserved Font Name（`Source`）限制。
2. **OPPO Sans**：按 OPPO 官方条款免费商用，但禁止改编、单独售卖、单独提供下载渠道。
   本项目原样随包分发、未修改、未单独售卖。详细说明见 `LICENSES/OPPO-Sans.md`。
   如权利人提出异议，删除 `OPPOSans-R-*.ttf` 即可，界面会回退到其余字体。
3. **低配包**：`tools/pc/build-station.ps1 -LiteEngine` 会剔除三套字体，从根上避开
   字体再分发；代价是中文渲染回退系统字体。

## 说明

字体文件在引擎发行包里，随引擎一并分发；MPL-2.0 不覆盖字体本身的版权。正式发行前
请以各字体官方许可为准；本文件不是法律意见。
