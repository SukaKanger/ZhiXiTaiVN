# 修了什么 · UI 前端（A0 体验评审 41 条 + A1 纯前端 3 条）

总述：本次共落地 44 条（A0-1…A0-41、A1-8.1、A1-8.4、A1-7.3）。其中完整完成 41 条，部分完成 3 条（A0-14、A0-26、A0-31，原因见对应行），未做 0 条。`node --check src/assets/ui/app.js` 退出码 0；id 一致性脚本核对：JS 引用的静态 id 在 index.html 中全部存在，无缺失；HTML 标签闭合平衡；三份文件均为 UTF-8 无 BOM、纯 LF。改动仅限 index.html / style.css / app.js 三个前端文件，未触碰 Java、脚本、配置与历史评审文档。

## A0 体验评审

- A0-1 → index.html · #gate .gate__box → 免责门主文案改人话（剧本/美术/音乐授权归你；工程与产物留在本机不上传；调试签名只能自测），MPL-2.0/GPL-3.0/MIT 缩写收成一行小字指向「关于」页，五条要点保留人话部分。
- A0-2 → app.js · bindGate（#btnGateDetail / #btnGateCollapse）→ 展开后主按钮切成「收起声明」，全文底部另加「收起声明」文字按钮，两个按钮双向联动。
- A0-3 → index.html · #obTermuxItem；app.js · renderOnboard / bindOnboard（#obTermux、#obTermuxFdroid、#obTermuxOpen）→ 标题下补人话说明（Termux 是替你跑打包命令的翻译壳，不进游戏）；未装态并列「本机安装包（找到则显示文件名/大小）」与「去 F-Droid 下载」两个入口，下载入口不再藏在失败回调里。
- A0-4 → index.html · #obKitItem；app.js · deployAndCopy（#obKit、#obKitCopy）→ 主按钮改「复制环境安装命令（去 Termux 粘贴）」，提示改三步大白话（复制→Termux 粘贴回车→回来跑自检），提示区不出现 build_apk.sh 等脚本文件名。
- A0-5 → app.js · guardEnv / bindBuild（#btnPrepare、#btnBuild）→ 跳过体检后首次点生成/构建先弹轻提示「环境还没配齐，建议先去『环境』页补齐三项」+「去环境页」按钮，不直接抛原生报错。
- A0-6 → app.js · renderProjects（#projectList 空态）→ 工程库为空时主按钮改「先跑一遍新手引导工程」，导入降为下方小字说明。
- A0-7 → app.js · bindProjectList（act=pack）；style.css · .rail--right.is-flash → 卡片按钮文案「去打包」改「选到打包台」；点击后右栏打包台短闪高亮一次（动画 class，非常驻）。
- A0-8 → app.js · askDelete / cancelDelete / doDelete；style.css · .confirm-bar、.card.is-confirm → 删除改成卡片内常驻确认条「再点一次确认删除」+ 4 秒倒计时文字，倒计时内卡片保持高亮，超时自动恢复，不再只靠 toast。
- A0-9 → app.js · LAYOUT_LABEL；index.html · #fLayoutSeg label → 顶栏布局胶囊文案改「打包台：右 / 左 / 中」，环境页 label 同步「打包台摆在哪边」。
- A0-10 → app.js · bindStage（#btnStageFull）→ 全屏 toast 补「也可以按系统返回键回工作台」。
- A0-11 → app.js · createProject；app.js · toast 动作槽（#toastAction）→ 新建成功 toast 带可点跳转「去『剧本编辑』写剧本」。
- A0-12 → index.html · #edMask / #edGoWb；app.js · syncEditorGuard → 未选工程时编辑器置灰并盖遮罩（内含「去工作台选工程」按钮跳 tab），输入框 disabled + readonly 不可聚焦。
- A0-13 → app.js · CMD_ASSET / openAssetPicker / insertText / renderAssets；index.html · #assetPickDlg、#assetPickList → 插入背景/音乐/立绘指令时从已有素材弹可选列表，选中把文件名补进指令；编辑器素材一览里的文件名支持点击复制。
- A0-14 → index.html · #sceneDlg；app.js · openSceneDlg / validSceneName / submitSceneDlg → 新建/删除场景改应用内自绘弹窗，不再走原生 prompt/confirm；重名与非法字符校验在弹窗提交（含回车）时即时红字提示。部分完成：校验为提交时触发，未做逐键实时校验。
- A0-15 → app.js · guardDirty / syncDirty / openScene / onProjectChange / bindTabs；index.html · #edDirty → 文件名旁显示未保存圆点；切 tab、切工程、切场景前拦截确认（先保存 / 不保存直接走）。
- A0-16 → app.js · renderAssets / expandAssetCat → 素材列表超出折叠的 `…(N)` 变成可点开完整列表。
- A0-17 → app.js · PKG_RE / validatePkg / updatePrepareEnabled；index.html · #pkgRule、#pkgError → 包名规则常驻小字，失焦即时校验；不合规时禁用「生成构建工程」并在错误行说明原因。
- A0-18 → index.html · #fOrientation label → 「屏幕方向」改「游戏画面方向（产物 APK 锁定）」。
- A0-19 → index.html · #buildHint；app.js · enableBuild → 「开始构建」禁用态下方常驻提示「先点上面『生成构建工程』」。
- A0-20 → index.html · #ksPassHint；app.js · setSignMode → 非调试签名模式明示「口令留空将用默认口令 android，仅限自测」。
- A0-21 → index.html · #ksNewPathBox / #ksNewPath / #btnCopyKsPath；app.js · setSignMode → 新建 keystore 模式显示只读生成路径 + 复制按钮。
- A0-22 → index.html · #btnExport → 「导出到 PC」改「打包工程 zip 存到本机」。
- A0-23 → app.js · stageFromLog / pollBuild → 状态行从 build.log 抓 aapt2 / javac / d8(dx) / zipalign / apksigner 关键字，映射成人话阶段（编译资源 / 编译代码 / 生成 dex / 对齐 / 签名）；关键字对照 kit/build_apk.sh 实际输出。
- A0-24 → index.html · #envAdvanced / #envAdvTable；app.js · refreshEnv → 环境页三行路径收进「高级路径详情 ›」可折叠区。
- A0-25 → index.html · .env-steps（#stepFiles/#stepKit/#stepProbe）；app.js · syncEnvSteps → 环境页三个按钮改成 ①②③ 步骤条，每步带就位/未就位状态。
- A0-26 → index.html · .fault[data-repair] / [data-repair-setup]；app.js · bindForm 事件委托 → 红字故障配「复制修复命令」按钮（libc++/openssl 命令、android.jar 安装命令）。部分完成：第三条「javac 卡住 / ROM 冻结」是操作建议、没有可复制的 shell 命令，未配按钮。
- A0-27 → app.js · renderVerdict / syncEnvSteps；index.html · #envVerdict → 自检结果顶部给总判决，四种结论人话呈现：全齐可本机打包 / 缺 Termux / 缺打包工具 / 有红项需处理。
- A0-28 → app.js · loadStage → 预览失败 toast 点名「终端」页并自动切到该 tab、刷新日志。
- A0-29 → app.js · maybeWakePrompt / visibilitychange → 用 visibilitychange 判断回到前台，提示「装完/跑完了吗？点这里继续」，可继续构建或触发一次自检。
- A0-30 → app.js · classifyFailure / showResult → 构建失败原因归类翻译（缺打包工具 / 内存不足或被杀 / 包名或签名问题 / 其他），原始日志折叠在 details 里。
- A0-31 → app.js · renderImportBar；事件 importStart / imported / progress；index.html 无新增静态节点（进度条 JS 生成）→ 导入大工程显示进度条，预留 files/total/percent/detail 字段契约。部分完成：当前 Java 导入为同步复制、只发 importStart/imported，未流式下发文件数，前端进度条暂为 indeterminate；实时文件数待 Java 补逐文件事件。
- A0-32 → index.html · #tab-log hint → 终端页提示去掉「抓尾巴」黑话，改人话描述刷新方式与日志位置。
- A0-33 → index.html · .rail--right 副标题 → 「Termux + BuildAPKs · 端上链路」改「在手机本机完成打包，工程和产物不上传电脑」。
- A0-34 → index.html · nav tab[data-tab=editor]；app.js · GUIDE_STEPS 第 4 步 → tab「工作场景」改「剧本编辑」；新手引导第 4 步标题与文案同步；内部 data-tab 名 editor 未改。
- A0-35 → app.js · TAG_LABEL / renderProjects → 工程卡片标签统一中文：引导 / 内置 / 新建 / 导入，不再中英混排 BUILT-IN / IMPORTED / NEW。
- A0-36 → index.html · #fVersionManual；app.js · loadPrefs / savePrefs → 版本名、版本号输入框默认只读，勾「手动覆盖」开关才可编辑，偏好持久化。
- A0-37 → app.js · refreshEnv / renderOnboard / bindOnboard / GUIDE_STEPS / bindEnv；index.html · 环境页与体检文案 → 术语替换：跳板脚本→打包用的启动脚本；工具链落地→打包工具就位；释放脚本→装好打包脚本/部署；「钉路径」等黑话一并清除。
- A0-38 → style.css · .btn--sm / .seg button / .card__actions → 复核到位：小按钮 `.btn--sm` min-height 36px、分段 `.seg button` min-height 34px、卡片 `.card__actions` gap 8px，危险按钮右置分隔；窄屏 `.btn--sm` 覆盖只改 padding/字号，未覆盖 min-height，36px 保留。
- A0-39 → style.css · .split / 网格列宽 / 竖屏媒体查询 → 拖拽把手加宽到 15px（14–16px 区间），加可见竖纹握纹，竖屏/窄屏整体隐藏。
- A0-40 → style.css · 各字号规则 / --dim 变量 → 全量字号复扫后无 <12px：11.5px 提到 12px（.ed-assets、.ed-cmds button、.ed-foot .hint、.sec-head__sub、.seg--3 button、.hint、.prog__text、.note code、.ob-note、.asset-item .asset-copy、.fail-detail summary、.fail-detail pre，以及窄屏 .btn--sm、rail--narrow .card__meta / .card__actions .btn / .row .btn）；11px 提到 12px（.gate__brand）；10.5px 提到 12px（横屏媒体 .field > label / .hint、.guide__brand）；10px 提到 12px（.card__tag、窄屏 .hud__ver、dock--narrow .sec-head__sub / .field > label / .hint / .prog__text）。连带微调：.seg--3 button 左右 padding 收到 4px 防三字挤一起；.card__tag、窄屏 .hud__ver padding 调到 1px 5px；窄屏换行复用既有 flex-wrap，未压 line-height。dark/light 两套 dim 文字颜色对比度 ≥4.5:1。
- A0-41 → style.css · .guide__card；app.js · positionGuide → 引导卡片宽度 min(340px, 92vw)；定位优先目标右侧、再下方/上方，确认不盖住高亮洞，窄屏兜底居中。

## A1 代码评测（纯前端部分）

- A1-8.1 → app.js · __stationEmit 事件 switch（case 'termuxInstall'）→ 补断线 case：`toast(data.message || 'Termux 安装结果未知', !data.ok); refreshEnv(); refreshOnboard && refreshOnboard();`；case 块用大括号限定作用域，避免与其他 case 重复声明。
- A1-8.4 → app.js · pollIntervalMs / schedulePoll / stopPolling；bindEnv 探针定时器 → 轮询退避：prepared 3s、running（构建中）1.2s、stalled（停滞）5s；探针轮询不再固定 10 次，改为 60 秒总时长上限（按时间戳判断，间隔 1s）。
- A1-7.3 → app.js · formOptions / loadPrefs / 事件 prepared / mockEnv → 写死的 2601、1.1.5-beta 兜底值改读 `S.env.versionStartCode` 与 `S.env.versionBase`，写法 `S.env.versionStartCode || 2601`、`S.env.versionBase || '1.1.5'`，字段没来时有兜底；未改任何 Java。

## 备注 / 跨端依赖

- versionStartCode 字段由 Java 侧 apiEnv 补充；前端已按契约读取，缺字段时回退兜底常量 2601 / 1.1.5。
- A0-31 的逐文件文件数/总数进度依赖 Java 在复制过程中补发 progress 事件（files/total）；前端进度条与字段解析已就位。
- 未改动：任何 .java / .sh / .ps1 / .gradle / .xml / .json 配置、kit 目录、tools 目录、AndroidManifest、VERSION.json、DISCLAIMER.txt，以及 A0/A1 两份历史评审文档。
- 发现但未改：style.css 中 `body[data-layout="left"] .rail--right/.rail--left`、`body[data-layout="center"] .rail--right/.stage` 等未加 `.panel--wb` 作用域的布局选择器，属 MainAgent 明确标注「别的不要改」的范围；本轮未动。其中 left 布局下 `.rail--left` 选择器会顺带命中编辑器 `.rail--ed`，仅影响一条分隔边框方向，不构成布局错位，故仅记录、不改代码。
- 保留项：`.panel--wb .rail--left/.stage/#splitterL/#splitter/.rail--right` 五条 grid-area 规则维持 `.panel--wb` 作用域前缀原样，未回退为无前缀写法。

## 追加（真机 beta.23）

`app.js` 里 `S.env.versionBase || '1.1.5'` 的兜底常量与 `index.html` 的占位 `1.1.5` 已改成 `1.1.6`——兜底不跟着换代，会在真机上把上一代号打进 `job.env`（详见 `docs/PITFALLS.md` #46）。Java 侧同时补了「名字只认 `V.BASE`」的兜底，前端再传旧号也会被覆盖。