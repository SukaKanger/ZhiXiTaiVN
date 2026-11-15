/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 织戏台 · 移动工作站作者
 */
'use strict';

const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

const S = {
  env: null,
  projects: [],
  prefs: {},
  build: { id: '', state: '', apk: '', size: '', log: '', reason: '' },
  iconPath: null,
  orientation: 'landscape',
  signMode: 'debug',
  ksPath: '',
  stageProject: '',
  stageUrl: '',
  polling: null,
  probeTries: 0,
  probeStart: 0,
  confirmDelete: null,
  confirmTimer: null,
  onboard: null,
  probe: null,
  assets: {},
  assetPick: null,
  importing: false,
  wentHiddenAt: 0
};

/* ------------------------------------------------------------------ 桥 */

const native = () => (typeof window.Station !== 'undefined' ? window.Station : null);

const MOCK = !native();

function mockEnv() {
  return {
    ok: 1, sdk: 34, device: '浏览器演示 · 无原生层', pkg: 'com.zhixitai.vn',
    version: '1.0.0', engine: '4.6.4', termux: false, termuxVersion: '',
    allFiles: false, buildRoot: '/sdcard/Download/WebGALStation', shared: true,
    kitDir: '/sdcard/Download/WebGALStation/kit',
    setupCommand: 'bash /sdcard/Download/WebGALStation/kit/setup.sh',
    disclaimer: true, bootstrap: '/data/data/com.termux/files/home/.webgal-station/run.sh',
    versionBase: '1.1.6', versionStartCode: 2601
  };
}

const MOCK_PROJECTS = {
  ok: 1,
  items: [
    { id: 'demo', name: '内置示例 · 织戏台 · 移动工作站', gameName: '织戏台 · 移动工作站',
      gameDir: '/sdcard/Download/WebGALStation/projects/demo/game', bytes: 5865472,
      bytesText: '5.6 MB', files: 33, scenes: 4, source: 'demo', exists: true },
    { id: 'p1770000000000', name: '雾港之夜', gameName: '雾港之夜',
      gameDir: '/sdcard/Download/WebGALStation/projects/p1770000000000/game',
      bytes: 268435456, bytesText: '256.0 MB', files: 412, scenes: 38,
      source: 'import', exists: true }
  ]
};

const MOCK_LEGAL = {
  notice: '（演示模式：NOTICE 全文需在 APK 内通过本地服务读取）\n\n' +
    '引擎层  WebGAL 4.6.4 → MPL-2.0（未修改，源码公开可得）\n' +
    '工具层  Termux / BuildAPKs → GPL-3.0，独立进程调用，聚合作品\n' +
    '原创层  本工作站代码与界面 → MIT，文件头带 SPDX 标识\n',
  mpl: '（演示模式）Mozilla Public License Version 2.0 全文…',
  mit: '（演示模式）MIT License 全文…'
};

function call(fn, ...args) {
  const b = native();
  if (!b) return null;
  try {
    const r = b[fn].apply(b, args);
    return typeof r === 'string' ? parse(r) : r;
  } catch (e) {
    toast('原生调用失败：' + e.message, true);
    return null;
  }
}

function parse(text) {
  if (typeof text !== 'string') return text || null;
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

/* ------------------------------------------------------------------ 提示 */

let toastTimer = null;
let toastActionHandler = null;
function toast(message, bad, actionLabel, actionFn) {
  const el = $('#toast');
  $('#toastMsg').textContent = message;
  const act = $('#toastAction');
  if (actionLabel && typeof actionFn === 'function') {
    act.textContent = actionLabel;
    act.hidden = false;
    toastActionHandler = actionFn;
  } else {
    act.hidden = true;
    toastActionHandler = null;
  }
  el.classList.toggle('is-bad', !!bad);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
    act.hidden = true;
    toastActionHandler = null;
  }, bad ? 4200 : 2600);
}

function bindToastAction() {
  $('#toastAction').addEventListener('click', () => {
    if (typeof toastActionHandler === 'function') toastActionHandler();
  });
}

function copyText(text, okMsg) {
  if (!text) { toast('没有可复制的内容', true); return; }
  if (native()) call('copy', text);
  else if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).catch(() => {});
  }
  toast(okMsg || ('已复制：' + text));
}

/* 通用确认弹窗 */
let confirmHandlers = null;
function openConfirm(opts) {
  $('#confirmTitle').textContent = (opts && opts.title) || '提示';
  $('#confirmMsg').textContent = (opts && opts.msg) || '';
  $('#confirmYes').textContent = (opts && opts.yesText) || '确定';
  $('#confirmNo').textContent = (opts && opts.noText) || '稍后再说';
  confirmHandlers = { yes: (opts && opts.yes) || null, no: (opts && opts.no) || null };
  $('#confirmDlg').hidden = false;
}
function closeConfirm() {
  $('#confirmDlg').hidden = true;
  confirmHandlers = null;
}
function bindConfirm() {
  $('#confirmYes').addEventListener('click', () => {
    const h = confirmHandlers;
    closeConfirm();
    if (h && typeof h.yes === 'function') h.yes();
  });
  $('#confirmNo').addEventListener('click', () => {
    const h = confirmHandlers;
    closeConfirm();
    if (h && typeof h.no === 'function') h.no();
  });
}

/* ------------------------------------------------------------------ 标签页 */

function switchTab(name, persist) {
  // 旧版 prefs 里可能存着 "projects"/"build" 这种已经不存在的页签名，兜底回工作台
  const known = ['workbench', 'editor', 'log', 'env', 'about'];
  // 问答已经改成右下浮窗，不是页签了；老 prefs 里存着 ask 就直接开浮窗
  if (name === 'ask') { openAskDock(true); return; }
  const tab = known.indexOf(name) >= 0 ? name : 'workbench';
  $$('.tab').forEach((t) => t.classList.toggle('is-active', t.dataset.tab === tab));
  $$('.panel').forEach((p) => p.classList.toggle('is-active', p.id === 'tab-' + tab));
  if (persist !== false) {
    S.prefs.tab = tab;
    savePrefs();
  }
  if (tab === 'log') renderLog();
  if (tab === 'about') loadLegalIfNeeded();
  if (tab === 'editor') loadWorkspace($('#fProject').value, true);

}

/* 有未保存改动时先问一句；onOk 在确认保存（或放弃）后执行 */
function guardDirty(onOk) {
  if (!ED.dirty) { onOk(); return; }
  openConfirm({
    title: '有未保存的改动',
    msg: '剧本还没保存，先保存再离开吗？不保存的话，改动会丢掉。',
    yesText: '先保存',
    noText: '不保存，直接走',
    yes: () => { if (saveScene()) onOk(); },
    no: () => { ED.dirty = false; syncDirty(); onOk(); }
  });
}

function bindTabs() {
  $$('.tab').forEach((tab) => {
    tab.addEventListener('click', () => guardDirty(() => switchTab(tab.dataset.tab)));
  });
}

/* ------------------------------------------------------------------ 环境 */

function refreshEnv() {
  const env = MOCK ? mockEnv() : call('env');
  if (!env || !env.ok) {
    toast('环境读取失败', true);
    return;
  }
  S.env = env;
  $('#hudEngine').textContent = 'v' + (env.engine || '—');
  $('#hudSdk').textContent = 'API ' + env.sdk;
  if ($('#hudVersion')) $('#hudVersion').textContent = env.version || '';
  const led = $('#ledTermux');
  led.className = 'led ' + (env.termux ? 'is-on' : 'is-off');
  led.title = env.termux ? 'Termux 已安装 ' + env.termuxVersion : 'Termux 未安装';

  const rows = [
    ['设备', env.device],
    ['系统', 'API ' + env.sdk],
    ['工作站版本', env.version],
    ['引擎版本', 'WebGAL ' + env.engine],
    ['Termux', env.termux ? '<span class="pill pill--ok">已安装 ' + esc(env.termuxVersion) + '</span>'
      : '<span class="pill pill--bad">未安装</span>'],
    ['所有文件访问', env.allFiles ? '<span class="pill pill--ok">已授权</span>'
      : '<span class="pill pill--warn">未授权（走私有目录）</span>']
  ];
  const advRows = [
    ['构建根目录', env.buildRoot + (env.shared ? '' : '（Termux 读不到，需授权）')],
    ['脚本目录', env.kitDir],
    ['打包用的启动脚本', env.bootstrap]
  ];
  const table = $('#envTable');
  table.innerHTML = rows.map((r) => '<tr><td>' + r[0] + '</td><td class="mono">' + r[1] + '</td></tr>').join('');
  const advTable = $('#envAdvTable');
  if (advTable) {
    advTable.innerHTML = advRows.map((r) => '<tr><td>' + r[0] + '</td><td class="mono">' + esc(r[1] || '—') + '</td></tr>').join('');
    const adv = $('#envAdvanced');
    if (adv) adv.hidden = !advRows.some((r) => r[1]);
  }
  $('#aboutVersion').textContent = '工作站 ' + env.version + ' · 引擎 WebGAL ' + env.engine + ' · 全部在本机完成打包';
  renderVerdict();
  syncEnvSteps();
  const bTermux = $('#btnTermuxInstall');
  if (bTermux) bTermux.hidden = !!env.termux;
  refreshOffline();
}

/* 完全体：APK 内自带离线工具链。lite 包没有这段，按钮整块藏起来。 */
function refreshOffline() {
  const box = $('#offlineBox');
  if (!box) return;
  let o = null;
  try { o = MOCK ? null : call('offline'); } catch (e) { o = null; }
  if (!o || !o.ok || !o.bundle) {
    box.hidden = true;
    return;
  }
  S.offline = o;
  box.hidden = false;
  const st = $('#offlineState');
  if (o.toolsReady) {
    st.innerHTML = '<span class="pill pill--ok">已装好</span> 工具链在 Termux 里就位，现在断网也能打包。';
  } else if (o.extracted) {
    st.innerHTML = '<span class="pill pill--warn">已释放，待安装</span> 去 Termux 跑一次安装命令。';
  } else {
    st.innerHTML = 'APK 内置了 Termux 与 ' + o.debCount + ' 个离线包，点「释放离线工具包」搬到共享目录。';
  }
}

function bindOffline() {
  const drop = $('#btnOfflineDrop');
  if (drop) {
    drop.addEventListener('click', () => {
      if (S.env && !S.env.termux) {
        toast('Termux 还没装：先点上面「装本机 Termux」，装完再点这里', true);
        const ir = call('installTermux');
        if (ir && ir.ok) toast(ir.message || '已交给系统安装器');
        return;
      }
      const r = call('offlineInstall');
      if (!r || !r.ok) { toast((r && r.message) || '释放失败', true); return; }
      toast((r.message || '开始释放离线包，约 160 MB') + '；别切后台，完了自动打开 Termux。');
    });
  }
  const cmd = $('#btnOfflineCmd');
  if (cmd) {
    cmd.addEventListener('click', () => {
      const c = (S.offline && S.offline.command) || 'bash /sdcard/Download/WebGALStation/offline/install_offline.sh';
      copyText(c, '离线安装命令已复制，切到 Termux 粘贴回车');
    });
  }
  const open = $('#btnOfflineOpen');
  if (open) open.addEventListener('click', () => call('openTermux'));
}


/* 环境总判决：全齐可打包 / 缺 Termux / 缺工具链 / 有红项需处理 */
function renderVerdict() {
  const box = $('#envVerdict');
  if (!box) return;
  const env = S.env || {};
  const probe = S.probe;
  let verdict;
  if (!env.termux) {
    verdict = { cls: 'is-bad', title: '缺 Termux', text: '还没装跑打包命令的翻译壳。先在环境页第②步装好 Termux，再回来跑自检。' };
  } else if (probe && !probe.allOk) {
    verdict = { cls: 'is-bad', title: '有红项需处理', text: '自检有组件没就位，按下面红字提示补齐命令，再点「运行自检」。' };
  } else if (probe && probe.allOk) {
    verdict = { cls: 'is-ok', title: '全齐，可以在手机本机打包', text: '打包工具已就位，小体量工程一般二十来秒出包；工程很大时建议关后台、只打小样本先验证。' };
  } else {
    verdict = { cls: 'is-warn', title: '还没跑自检', text: '点第③步「运行自检」，跑完这里会给出能不能在本机打包的结论。' };
  }
  box.className = 'verdict ' + verdict.cls;
  box.innerHTML = '<p><b>' + esc(verdict.title) + '</b></p><p>' + esc(verdict.text) + '</p>';
  box.hidden = false;
}

/* 环境页步骤条状态 */
function syncEnvSteps() {
  const env = S.env || {};
  const ob = S.onboard || {};
  const kitOk = !!(ob.kitDeployed || env.kitDeployed);
  const termuxOk = !!(ob.termux || env.termux);
  const setStep = (id, state, cls) => {
    const el = $(id + 'State');
    if (el) obState(el, state, cls);
    const li = $(id);
    if (li) li.classList.toggle('is-done', cls === 'ok');
  };
  setStep('#stepFiles', env.allFiles ? '已授权' : '未授权', env.allFiles ? 'ok' : 'bad');
  setStep('#stepKit', termuxOk && kitOk ? '已就位' : '未就位', termuxOk && kitOk ? 'ok' : 'warn');
  const probe = S.probe;
  setStep('#stepProbe', probe ? (probe.allOk ? '通过' : '有红项') : '未运行',
    probe ? (probe.allOk ? 'ok' : 'bad') : 'warn');
}

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/* ------------------------------------------------------------------ 工程库 */

function refreshProjects() {
  const data = MOCK ? MOCK_PROJECTS : call('projects');
  if (!data || !data.ok) return;
  S.projects = data.items || [];
  $('#projectCount').textContent = S.projects.length + ' 个工程';
  renderProjects();
  renderProjectSelect();
}

const TAG_LABEL = { tutorial: '引导', demo: '内置', new: '新建', import: '导入', imported: '导入' };

function renderProjects() {
  const box = $('#projectList');
  if (!S.projects.length) {
    box.innerHTML = '<div class="empty">还没有工程。建议先跑一遍新手引导工程，跟着五步走一遍。</div>' +
      '<div class="row row--tight"><button class="btn btn--sm btn--primary" data-act="tut">先跑一遍新手引导工程</button></div>' +
      '<p class="hint">已有 WebGAL 工程？点上方「导入」选工程目录即可。</p>';
    return;
  }
  const hasTut = S.projects.some((p) => p.id === 'tutorial');
  box.innerHTML = S.projects.map((p) => {
    const tagCls = p.source === 'tutorial' ? 'card__tag--tutorial'
      : p.source === 'demo' ? 'card__tag--demo'
        : p.source === 'new' ? 'card__tag--demo' : '';
    const tag = '<span class="card__tag ' + tagCls + '">' + esc(TAG_LABEL[p.source] || '新建') + '</span>';
    const missing = p.exists ? '' : '<span class="pill pill--bad">目录丢失</span>';
    return '' +
      '<article class="card" data-id="' + esc(p.id) + '">' +
        '<div class="card__head">' +
          '<h3 class="card__name">' + esc(p.name) + '</h3>' + tag +
        '</div>' +
        '<div class="card__meta">' +
          '<span>场景 <b>' + (p.scenes || 0) + '</b></span>' +
          '<span>文件 <b>' + (p.files || 0) + '</b></span>' +
          '<span>体积 <b>' + esc(p.bytesText || '—') + '</b></span>' +
          missing +
        '</div>' +
        '<div class="card__actions">' +
          '<button class="btn btn--sm btn--primary" data-act="preview">预览</button>' +
          '<button class="btn btn--sm" data-act="pack">选到打包台</button>' +
          '<button class="btn btn--sm btn--danger" data-act="delete">删除</button>' +
        '</div>' +
      '</article>';
  }).join('') + (hasTut ? '' :
    '<div class="empty">引导工程被删了。<button class="btn btn--sm" data-act="tut">重建新手引导</button></div>');
}

function renderProjectSelect() {
  const sel = $('#fProject');
  const current = sel.value || S.prefs.projectId || '';
  sel.innerHTML = S.projects.map((p) =>
    '<option value="' + esc(p.id) + '">' + esc(p.name) + '</option>').join('');
  if (current && S.projects.some((p) => p.id === current)) {
    sel.value = current;
  }
  onProjectChange(true);
}

function onProjectChange(initial) {
  const id = $('#fProject').value;
  const p = S.projects.find((x) => x.id === id);
  S.prefs.projectId = id;
  if (p && (initial || !$('#fAppName').value)) {
    $('#fAppName').value = p.gameName || p.name || '';
  }
  savePrefs();
  // 编辑器开着就跟着换工程，别让人对着旧工程的场景发呆
  if ($('#tab-editor').classList.contains('is-active')) {
    guardDirty(() => { ED.scene = ''; loadWorkspace(id, false); });
  }
}

function bindProjectList() {
  $('#projectList').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const act = btn.dataset.act;
    if (act === 'tut') {
      if (MOCK) { toast('演示模式：建不了'); return; }
      const r = call('installTutorial');
      if (r && r.ok) {
        toast('引导工程已放回工程库');
        refreshProjects();
        setTimeout(() => { openGuide(0); }, 400);
      } else {
        toast((r && r.message) || '重建失败', true);
      }
      return;
    }
    const card = btn.closest('.card');
    const id = card.dataset.id;
    if (act === 'preview') {
      if (MOCK) { toast('演示模式：预览需要原生层'); return; }
      loadStage(id);
    } else if (act === 'pack') {
      $('#fProject').value = id;
      onProjectChange(true);
      switchTab('workbench');
      const dock = $('.rail--right');
      if (dock) {
        dock.classList.remove('is-flash');
        void dock.offsetWidth;
        dock.classList.add('is-flash');
      }
      toast('已切到打包台：' + (S.projects.find((x) => x.id === id) || {}).name + '，在右边填表后点「生成构建工程」');
    } else if (act === 'delete') {
      askDelete(id);
    }
  });
}

function cssId(id) {
  return String(id).replace(/[^A-Za-z0-9_-]/g, '\\$&');
}

function askDelete(id) {
  const p = S.projects.find((x) => x.id === id);
  if (!p) return;
  const card = $('#projectList').querySelector('.card[data-id="' + cssId(id) + '"]');
  if (S.confirmDelete === id) { doDelete(id); return; }
  cancelDelete();
  S.confirmDelete = id;
  let secs = 4;
  if (card) {
    card.classList.add('is-confirm');
    const bar = document.createElement('div');
    bar.className = 'confirm-bar';
    bar.innerHTML = '<span>再点一次确认删除，副本会被真删</span><span class="confirm-bar__count">' + secs + 's</span>';
    const actions = card.querySelector('.card__actions');
    if (actions) actions.after(bar);
  }
  S.confirmTimer = setInterval(() => {
    secs--;
    if (secs <= 0) { cancelDelete(); return; }
    const bar = $('#projectList .confirm-bar');
    const count = bar && bar.querySelector('.confirm-bar__count');
    if (count) count.textContent = secs + 's';
  }, 1000);
}

function cancelDelete() {
  clearInterval(S.confirmTimer);
  S.confirmTimer = null;
  S.confirmDelete = null;
  const box = $('#projectList');
  const bar = box.querySelector('.confirm-bar');
  if (bar) bar.remove();
  box.querySelectorAll('.card.is-confirm').forEach((c) => c.classList.remove('is-confirm'));
}

function doDelete(id) {
  const p = S.projects.find((x) => x.id === id);
  cancelDelete();
  if (!p) return;
  if (MOCK) { toast('演示模式：不会真删'); return; }
  const r = call('deleteProject', id);
  if (r && r.ok) {
    toast('已移除「' + p.name + '」（磁盘副本一并删除）');
    refreshProjects();
  } else {
    toast('删除失败', true);
  }
}

/* ------------------------------------------------------------------ 预览舞台 */

function setStageBar(on, title) {
  $('#btnStageReload').disabled = !on;
  $('#btnStageFull').disabled = !on;
  $('#btnStageClose').disabled = !on;
  $('#stageTitle').textContent = title || '未载入工程';
}

function loadStage(id) {
  const p = S.projects.find((x) => x.id === id) || {};
  const r = call('stage', id);
  if (!r || !r.ok) {
    toast((r && r.message) || '预览起不来，详情去「终端」页看日志', true);
    switchTab('log');
    renderLog();
    return;
  }
  S.stageProject = id;
  S.stageUrl = r.url || '';
  $('#stageFrame').src = S.stageUrl;
  $('#stageFrame').hidden = false;
  $('#stageEmpty').hidden = true;
  setStageBar(true, '预览 · ' + (p.name || r.project || '') + ' · 127.0.0.1');
  switchTab('workbench');
}

function reloadStage() {
  if (!S.stageProject) { toast('还没选工程', true); return; }
  loadStage(S.stageProject);
}

function closeStage() {
  S.stageProject = '';
  S.stageUrl = '';
  $('#stageFrame').src = 'about:blank';
  $('#stageFrame').hidden = true;
  $('#stageEmpty').hidden = false;
  setStageBar(false, '未载入工程');
}

function bindStage() {
  $('#btnStageReload').addEventListener('click', reloadStage);
  $('#btnStageClose').addEventListener('click', closeStage);
  $('#btnStageFull').addEventListener('click', () => {
    if (MOCK) { toast('演示模式'); return; }
    call('preview', S.stageProject);
    toast('已切到全屏预览；也可以按系统返回键回工作台');
  });
}



/* ------------------------------------------------------------------ 工作场景（编辑器） */

const ED = { pid: '', scene: '', dirty: false, ws: null };

function syncDirty() {
  $('#edDirty').hidden = !ED.dirty;
  $('#edSave').classList.toggle('btn--amber', ED.dirty);
}

function syncEditorGuard() {
  const has = !!ED.pid || MOCK;
  $('#edMask').hidden = has;
  $('#edText').disabled = !has;
  $('#edText').readOnly = !has;
}

function loadWorkspace(pid, silent) {
  if (!pid) {
    ED.pid = ''; ED.scene = ''; ED.dirty = false;
    $('#edProjectName').textContent = '先在工作台选工程';
    $('#edScenes').innerHTML = '<div class="note"><p>工作台上挑一个工程，再回来写剧本。</p></div>';
    $('#edAssets').innerHTML = '';
    $('#edText').value = '';
    $('#edFile').textContent = '—';
    syncEditorGuard();
    syncDirty();
    return;
  }
  if (MOCK) {
    $('#edProjectName').textContent = '演示模式';
    syncEditorGuard();
    return;
  }
  const ws = parse(call('workspace', pid));
  if (!ws || !ws.ok) {
    toast((ws && (ws.message || ws.error)) || '读工程失败', true);
    return;
  }
  ED.pid = pid;
  ED.ws = ws;
  $('#edProjectName').textContent = (ws.gameName || pid) + ' · ' + (ws.scenes ? ws.scenes.length : 0) + ' 个场景';
  renderScenes(ws.scenes || []);
  renderAssets(ws.assets || {});
  syncEditorGuard();
  const first = (ws.scenes && ws.scenes[0]) ? ws.scenes[0].name : '';
  if (!silent || !ED.scene) {
    if (first) openScene(first);
  } else if (ED.scene) {
    const hit = (ws.scenes || []).some((s) => s.name === ED.scene);
    if (hit) openScene(ED.scene);
    else if (first) openScene(first);
  }
  syncDirty();
}

function renderScenes(list) {
  if (!list.length) {
    $('#edScenes').innerHTML = '<div class="note"><p>还没场景。点「新建场景」起一个。</p></div>';
    return;
  }
  $('#edScenes').innerHTML = list.map((s) => (
    '<button class="scene-item' + (s.name === ED.scene ? ' is-on' : '') + '" data-scene="' + esc(s.name) + '">'
    + '<span class="scene-item__name">' + esc(s.name) + '</span>'
    + '<span class="scene-item__meta mono">' + (s.lines || 0) + '行</span></button>'
  )).join('');
  $$('#edScenes .scene-item').forEach((b) => {
    b.addEventListener('click', () => openScene(b.dataset.scene));
  });
}

const ASSET_CAT_LABEL = { background: '背景', bgm: '音乐', figure: '立绘', animation: '动画', vocal: '语音', video: '视频', other: '其他' };

function renderAssets(a) {
  S.assets = a || {};
  const cats = [['背景', 'background'], ['立绘', 'figure'], ['音乐', 'bgm'],
    ['语音', 'vocal'], ['视频', 'video'], ['动画', 'animation'], ['其他', 'other']];
  const rows = cats.map(([title, key]) => {
    const list = S.assets[key] || [];
    if (!list.length) return '';
    const shown = list.slice(0, 8);
    const chips = shown.map((n) =>
      '<span class="asset-chip-wrap"><button class="asset-chip" data-asset-name="' + esc(n) + '" data-asset-cat="' + key + '">' + esc(n) + '</button>'
      + '<button class="asset-rename" data-asset-name="' + esc(n) + '" data-asset-cat="' + key + '" title="重命名">✎</button></span>').join('');
    const more = list.length > 8
      ? ' <button class="asset-more" data-asset-cat="' + key + '">…(' + list.length + ')</button>' : '';
    return '<div><b>' + title + '</b> ' + chips + more + '</div>';
  }).join('');
  $('#edAssets').innerHTML = rows || '<div>资源目录是空的</div>';
}

function expandAssetCat(cat, btn) {
  const list = S.assets[cat] || [];
  const chips = list.map((n) =>
    '<span class="asset-chip-wrap"><button class="asset-chip" data-asset-name="' + esc(n) + '" data-asset-cat="' + cat + '">' + esc(n) + '</button>'
    + '<button class="asset-rename" data-asset-name="' + esc(n) + '" data-asset-cat="' + cat + '" title="重命名">✎</button></span>').join('');
  const div = btn.closest('div');
  if (div) div.innerHTML = '<b>' + (ASSET_CAT_LABEL[cat] || cat) + '</b> ' + chips;
}

function openScene(name) {
  if (!name) return;
  if (ED.dirty) { guardDirty(() => openSceneInternal(name)); return; }
  openSceneInternal(name);
}

function openSceneInternal(name) {
  const r = parse(call('readText', ED.pid, 'scene/' + name));
  if (!r || !r.ok) {
    toast((r && (r.message || r.error)) || '读场景失败', true);
    return;
  }
  ED.scene = name;
  ED.dirty = false;
  $('#edFile').textContent = 'scene/' + name;
  $('#edText').value = r.text || '';
  $$('#edScenes .scene-item').forEach((b) => b.classList.toggle('is-on', b.dataset.scene === name));
  $('#edText').scrollTop = 0;
  syncDirty();
}

function saveScene(quiet) {
  if (!ED.pid || !ED.scene) { if (!quiet) toast('还没选场景', true); return false; }
  const r = parse(call('writeText', ED.pid, 'scene/' + ED.scene, $('#edText').value));
  if (!r || !r.ok) {
    toast((r && (r.message || r.error)) || '保存失败', true);
    return false;
  }
  ED.dirty = false;
  syncDirty();
  if (!quiet) toast('已保存 scene/' + ED.scene);
  return true;
}

/* 新建 / 删除场景：应用内自绘弹窗 */
let sceneDlgMode = 'new';
function openSceneDlg(mode) {
  if (!ED.pid) { toast('先在工作台选工程', true); return; }
  sceneDlgMode = mode;
  const input = $('#sceneNameInput');
  $('#sceneNameError').hidden = true;
  const warn = $('#sceneDeleteWarn');
  if (mode === 'new') {
    $('#sceneDlgTitle').textContent = '新建场景';
    $('#sceneOk').textContent = '创建';
    warn.hidden = true;
    input.readOnly = false;
    input.value = '';
    input.placeholder = '比如 scene2，不用带 .txt';
    setTimeout(() => input.focus(), 80);
  } else {
    $('#sceneDlgTitle').textContent = '删除场景';
    $('#sceneOk').textContent = '删除';
    warn.hidden = false;
    warn.textContent = '将删除 scene/' + ED.scene + '，文件会从工程目录移除，不可恢复。';
    input.readOnly = true;
    input.value = ED.scene;
  }
  $('#sceneDlg').hidden = false;
}

function closeSceneDlg() { $('#sceneDlg').hidden = true; }

function validSceneName(name) {
  if (!name) return '文件名不能空着';
  if (/[\\/:*?"<>|\s]/.test(name)) return '不能含空格和 \\ / : * ? " < > | 这些字符';
  const exist = (ED.ws && ED.ws.scenes || []).some((s) => s.name === name);
  if (exist) return '已有同名场景，换个名字';
  return '';
}

function submitSceneDlg() {
  const input = $('#sceneNameInput');
  if (sceneDlgMode === 'new') {
    const name = input.value.trim().replace(/\.txt$/i, '');
    const err = validSceneName(name);
    if (err) { $('#sceneNameError').textContent = err; $('#sceneNameError').hidden = false; return; }
    const r = parse(call('newScene', ED.pid, name));
    if (!r || !r.ok) {
      toast((r && (r.message || r.error)) || '新建场景失败', true);
      return;
    }
    closeSceneDlg();
    loadWorkspace(ED.pid, false);
  } else {
    const r = parse(call('deleteScene', ED.pid, ED.scene));
    if (!r || !r.ok) {
      toast((r && (r.message || r.error)) || '删除失败', true);
      return;
    }
    closeSceneDlg();
    ED.scene = '';
    loadWorkspace(ED.pid, false);
    toast('已删掉那个场景');
  }
}

function newScene() { openSceneDlg('new'); }
function deleteScene() { openSceneDlg('delete'); }

const CMD_ASSET = { 'changeBg:': 'background', 'bgm:': 'bgm', 'figure:': 'figure' };

function insertCmd(cmd) {
  const cat = CMD_ASSET[cmd];
  if (cat) { openAssetPicker(cmd, cat); return; }
  insertText(cmd);
}

function insertText(text) {
  const ta = $('#edText');
  const at = ta.selectionStart != null ? ta.selectionStart : ta.value.length;
  ta.value = ta.value.slice(0, at) + text + ta.value.slice(at);
  ta.focus();
  ta.selectionStart = ta.selectionEnd = at + text.length;
  ED.dirty = true;
  syncDirty();
}

function openAssetPicker(cmd, cat) {
  const list = S.assets[cat] || [];
  if (!list.length) {
    const placeholder = ({ background: '背景文件名', bgm: '音乐文件名', figure: '立绘文件名' })[cat] || '素材文件名';
    insertText(cmd + placeholder + ';');
    toast('这个分类还没有素材，先填文件名占位，素材放进来后再改');
    return;
  }
  $('#assetPickTitle').textContent = ASSET_CAT_LABEL[cat] ? ('选择' + ASSET_CAT_LABEL[cat]) : '选择素材';
  $('#assetPickList').innerHTML = list.map((n) =>
    '<button class="asset-item" data-asset-name="' + esc(n) + '"><span>' + esc(n) + '</span><span class="asset-copy">点选插入</span></button>').join('');
  $('#assetPickDlg').hidden = false;
  S.assetPick = { cmd, cat };
}

function closeAssetPicker() { $('#assetPickDlg').hidden = true; S.assetPick = null; }

function bindEditor() {
  $('#edRefresh').addEventListener('click', () => loadWorkspace($('#fProject').value, false));
  $('#edNewScene').addEventListener('click', newScene);
  $('#edImportAssets').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：导入素材需要原生层'); return; }
    const pid = ED.pid || $('#fProject').value;
    if (!pid) { toast('先在工作台选一个工程，再导入素材', true); return; }
    const cat = $('#edAssetCat').value || 'background';
    call('pickAssets', pid, cat);
    toast('选图/音频/视频，可多选；确定后自动复制进 game/' + cat + '/');
  });
  $('#edSave').addEventListener('click', saveScene);
  $('#edDelete').addEventListener('click', deleteScene);
  $('#edPreview').addEventListener('click', () => {
    if (ED.dirty && !saveScene()) return;
    switchTab('workbench');
    loadStage(ED.pid || $('#fProject').value);
  });
  $('#edGoWb').addEventListener('click', () => switchTab('workbench'));
  $('#edCmds').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-cmd]');
    if (b) insertCmd(b.dataset.cmd);
  });
  $('#edAssets').addEventListener('click', (e) => {
    const rename = e.target.closest('.asset-rename');
    if (rename) {
      const pid = ED.pid || $('#fProject').value;
      if (!pid) { toast('先选工程', true); return; }
      const r = call('renameAsset', pid, rename.dataset.assetCat, rename.dataset.assetName);
      if (!r || !r.ok) { toast((r && r.message) || '打不开重命名', true); return; }
      toast('改名会直接写到工程目录，改完自动刷新');
      return;
    }
    const chip = e.target.closest('.asset-chip');
    if (chip) { copyText(chip.dataset.assetName, '已复制文件名：' + chip.dataset.assetName); return; }
    const more = e.target.closest('.asset-more');
    if (more) expandAssetCat(more.dataset.assetCat, more);
  });
  $('#edText').addEventListener('input', () => { ED.dirty = true; syncDirty(); });

  $('#sceneCancel').addEventListener('click', closeSceneDlg);
  $('#sceneOk').addEventListener('click', submitSceneDlg);
  $('#sceneNameInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitSceneDlg();
  });

  $('#assetPickCancel').addEventListener('click', closeAssetPicker);
  $('#assetPickList').addEventListener('click', (e) => {
    const item = e.target.closest('.asset-item');
    if (!item || !S.assetPick) return;
    insertText(S.assetPick.cmd + item.dataset.assetName + ';');
    closeAssetPicker();
  });

  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
      e.preventDefault();
      if ($('#tab-editor').classList.contains('is-active')) saveScene();
    }
  });
}

function bindForm() {
  $('#fProject').addEventListener('change', () => onProjectChange(true));
  ['fAppName', 'fVersionName', 'fVersionCode'].forEach((id) => {
    $('#' + id).addEventListener('input', savePrefs);
  });
  $('#fPkg').addEventListener('input', () => { savePrefs(); updatePrepareEnabled(); });
  $('#fPkg').addEventListener('blur', () => validatePkg());
  $('#fBadge').addEventListener('change', savePrefs);

  $('#fVersionManual').addEventListener('change', () => {
    const manual = $('#fVersionManual').checked;
    $('#fVersionName').readOnly = !manual;
    $('#fVersionCode').readOnly = !manual;
    savePrefs();
  });

  $('#fOrientation').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-v]');
    if (!b) return;
    S.orientation = b.dataset.v;
    $$('#fOrientation button').forEach((x) => x.classList.toggle('is-on', x === b));
    savePrefs();
  });
  $('#btnIcon').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：选图需要原生层'); return; }
    call('pickIcon');
  });

  // 签名：默认调试；切 custom/new 才显示 keystore 输入
  $('#fSignMode').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-v]');
    if (!b) return;
    setSignMode(b.dataset.v);
  });
  $('#btnKs').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：选文件需要原生层'); return; }
    call('pickKeystore');
  });
  ['fKsAlias', 'fKsStorePass', 'fKsKeyPass'].forEach((id) => {
    $('#' + id).addEventListener('input', savePrefs);
  });
  $('#btnCopyKsPath').addEventListener('click', () => {
    copyText($('#ksNewPath').textContent, '密钥路径已复制');
  });

  // 环境页红字故障：复制修复命令
  document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-repair]');
    if (btn) {
      copyText(btn.dataset.repair, '修复命令已复制，切到 Termux 粘贴回车，回来点「运行自检」');
      return;
    }
    const setup = e.target.closest('[data-repair-setup]');
    if (setup) {
      const cmd = (S.env && S.env.setupCommand) || '';
      copyText(cmd, '环境安装命令已复制，切到 Termux 粘贴回车');
    }
  });
}

const PKG_RE = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/;

function updatePrepareEnabled() {
  const v = $('#fPkg').value.trim();
  $('#btnPrepare').disabled = !PKG_RE.test(v);
}

function validatePkg() {
  const v = $('#fPkg').value.trim();
  const err = $('#pkgError');
  let msg = '';
  if (!v) msg = '包名还没填';
  else if (!PKG_RE.test(v)) msg = '包名不合规：只能用小写字母、数字和点，开头必须是字母，两段之间不能空';
  if (msg) { err.textContent = msg; err.hidden = false; }
  else err.hidden = true;
  updatePrepareEnabled();
  return !msg;
}

function setSignMode(mode) {
  S.signMode = mode || 'debug';
  $$('#fSignMode button').forEach((x) => x.classList.toggle('is-on', x.dataset.v === S.signMode));
  const extra = $('#signExtra');
  extra.hidden = S.signMode === 'debug';
  const hint = $('#signHint');
  if (S.signMode === 'debug') {
    hint.textContent = '调试签名能装能测，不能上架。要发布就切到「我的 keystore」或「新建」。';
  } else if (S.signMode === 'custom') {
    hint.textContent = '用你自己的 keystore 签名：产物可安装、可升级、可发布，密钥请自己备份。';
  } else {
    hint.textContent = '第一次构建会在手机本机生成一份属于你的 keystore，之后一直复用它。';
  }
  const passHint = $('#ksPassHint');
  if (passHint) passHint.hidden = S.signMode === 'debug';
  const newBox = $('#ksNewPathBox');
  if (newBox) {
    newBox.hidden = S.signMode !== 'new';
    if (S.signMode === 'new') {
      const ksPath = S.ksPath || (S.env && S.env.buildRoot ? S.env.buildRoot + '/keys/my-release.keystore' : 'my-release.keystore');
      $('#ksNewPath').textContent = ksPath;
    }
  }
  savePrefs();
}

function formOptions() {
  const ksPath = S.signMode === 'new'
    ? (S.ksPath || (S.env && S.env.buildRoot ? S.env.buildRoot + '/keys/my-release.keystore' : 'my-release.keystore'))
    : (S.ksPath || '');
  return {
    appName: $('#fAppName').value.trim(),
    pkg: $('#fPkg').value.trim(),
    versionName: $('#fVersionName').value.trim() || (S.env.versionBase || '1.1.6'),
    versionCode: parseInt($('#fVersionCode').value, 10) || (S.env.versionStartCode || 2601),
    orientation: S.orientation,
    badge: $('#fBadge').checked,
    iconPath: S.iconPath || '',
    signMode: S.signMode,
    ksPath: ksPath,
    ksAlias: ($('#fKsAlias').value || 'webgal').trim(),
    ksStorePass: $('#fKsStorePass').value || 'android',
    ksKeyPass: $('#fKsKeyPass').value || $('#fKsStorePass').value || 'android'
  };
}

function savePrefs() {
  if (!native()) return;
  S.prefs.appName = $('#fAppName').value;
  S.prefs.pkg = $('#fPkg').value;
  S.prefs.versionName = $('#fVersionName').value;
  S.prefs.versionCode = $('#fVersionCode').value;
  S.prefs.orientation = S.orientation;
  S.prefs.badge = $('#fBadge').checked;
  S.prefs.projectId = $('#fProject').value;
  S.prefs.buildId = S.build.id;
  S.prefs.iconPath = S.iconPath || '';
  S.prefs.signMode = S.signMode;
  S.prefs.ksPath = S.ksPath || '';
  S.prefs.ksAlias = $('#fKsAlias').value;
  S.prefs.ksStorePass = $('#fKsStorePass').value;
  S.prefs.versionManual = $('#fVersionManual').checked;
  // 「不会就问」的接口配置也在同一份 prefs 里，别存一次表单把 Key 抹了。
  // 只有问答页把 prefs 回填进表单之后（AI.hydrated）才允许写回，
  // 否则启动阶段别处调 savePrefs 会把表单里的空值盖掉已存的 Key。
  if (AI.hydrated && $('#fAiBase')) S.prefs.ai = readAiCfg();
  call('savePrefs', JSON.stringify(S.prefs));
}

function createProject() {
  const name = $('#newName').value.trim();
  if (!name) { toast('工程名不能空着', true); return; }
  const starter = $('#newStarter').value.trim();
  if (MOCK) { $('#newDlg').hidden = true; toast('演示模式：新建需要原生层'); return; }
  const r = call('createProject', name, starter);
  const res = parse(r);
  if (!res || !res.ok) {
    toast((res && (res.message || res.error)) || '新建失败', true);
    return;
  }
  $('#newDlg').hidden = true;
  refreshProjects();
  if (res.project) {
    $('#fProject').value = res.id;
    $('#fAppName').value = res.project.name || name;
    savePrefs();
    loadStage(res.id);
  }
  toast('工程建好了：' + name, false, '去「剧本编辑」写剧本', () => {
    switchTab('editor');
    loadWorkspace(res.id || $('#fProject').value, false);
  });
}

function loadPrefs() {
  let prefs = {};
  if (MOCK) {
    prefs = { appName: '', pkg: '', versionName: (S.env.versionBase || '1.1.6'), versionCode: String(S.env.versionStartCode || 2601),
      orientation: 'landscape', badge: true, tab: 'workbench', signMode: 'debug' };
  } else {
    prefs = parse(call('loadPrefs')) || {};
  }
  S.prefs = prefs || {};
  if (prefs.appName) $('#fAppName').value = prefs.appName;
  if (prefs.pkg) $('#fPkg').value = prefs.pkg;
  // 旧版 prefs 里 versionName=1.0.0 / versionCode=1，升级后别把新产物拉回旧号
  const legacyVer = !prefs.versionName || !/^\d+\.\d+\.\d+/.test(prefs.versionName) || prefs.versionName.indexOf('1.0.0') === 0;
  $('#fVersionName').value = legacyVer ? (S.env.versionBase || '1.1.6') : prefs.versionName;
  const legacyCode = !prefs.versionCode || String(prefs.versionCode) === '1';
  $('#fVersionCode').value = (legacyVer || legacyCode) ? String(S.env.versionStartCode || 2601) : prefs.versionCode;
  // 版本号默认只读，勾「手动覆盖」才放开
  $('#fVersionManual').checked = !!prefs.versionManual;
  $('#fVersionName').readOnly = !prefs.versionManual;
  $('#fVersionCode').readOnly = !prefs.versionManual;
  if (prefs.badge === false) $('#fBadge').checked = false;
  updatePrepareEnabled();
  applyTheme(prefs.theme || 'dark', false);
  applyLayout(prefs.layout || 'right', false);
  if (prefs.dock) applyDockWidth(prefs.dock, false);
  if (prefs.rail) applyRailWidth(prefs.rail, false);
  S.orientation = prefs.orientation || 'landscape';
  $$('#fOrientation button').forEach((x) => x.classList.toggle('is-on', x.dataset.v === S.orientation));
  if (prefs.iconPath) {
    S.iconPath = prefs.iconPath;
    $('#iconName').textContent = '已选图标';
  }
  S.ksPath = prefs.ksPath || '';
  if (prefs.ksAlias) $('#fKsAlias').value = prefs.ksAlias;
  if (prefs.ksStorePass) $('#fKsStorePass').value = prefs.ksStorePass;
  if (S.ksPath) $('#ksName').textContent = S.ksPath.split('/').pop();
  setSignMode(prefs.signMode || 'debug');
  if (prefs.buildId) {
    S.build.id = prefs.buildId;
    enableBuild(true);
    showResult();
    pollBuild();
    startPolling();
  }
  switchTab(prefs.tab || 'workbench', false);
}

/* ------------------------------------------------------------------ 打包流程 */

function enableBuild(on) {
  $('#btnBuild').disabled = !on;
  $('#btnBuild').classList.toggle('btn--amber', on);
  $('#badgeChannel').textContent = on ? '可构建' : '待命';
  $('#badgeChannel').className = 'badge' + (on ? ' is-run' : '');
  $('#buildHint').hidden = on;
}

function envReady() {
  const e = S.env || {};
  const ob = S.onboard || {};
  return !!(e.allFiles && (ob.kitDeployed || e.kitDeployed) && (ob.termux || e.termux));
}

function guardEnv(action) {
  if (MOCK || envReady()) { action(); return; }
  openConfirm({
    title: '环境还没配齐',
    msg: '建议先去「环境」页把文件权限、Termux、打包工具三项补齐，不然大概率打包失败。',
    yesText: '去环境页',
    noText: '继续操作',
    yes: () => switchTab('env'),
    no: action
  });
}

/* 从 Termux 回到前台：问一句要不要继续构建 / 跑自检 */
function maybeWakePrompt() {
  if (MOCK) return;
  const active = S.build.id && (S.build.state === 'running' || S.build.stalled);
  if (active) {
    openConfirm({
      title: '装完/跑完了吗？',
      msg: '从 Termux 回来了。要现在继续构建，还是先去看看状态？',
      yesText: '继续构建',
      noText: '稍后再说',
      yes: () => {
        if (!S.build.id) return;
        const r = call('startBuild', S.build.id);
        if (r && r.ok) {
          S.build.state = 'running';
          showResult();
          startPolling();
          armTermuxWatch();
        }
      },
      no: () => {}
    });
  } else if (!envReady()) {
    openConfirm({
      title: '装完/跑完了吗？',
      msg: '从 Termux 回来了。要现在跑一次自检，看看环境配齐没？',
      yesText: '运行自检',
      noText: '稍后再说',
      yes: () => { $('#btnProbe').click(); },
      no: () => {}
    });
  }
}

function progress(stage, percent, detail) {
  $('#progBar').style.width = Math.max(0, Math.min(100, percent || 0)) + '%';
  $('#progText').textContent = (stage || '') + (detail ? ' · ' + detail : '');
}

function bindBuild() {
  $('#btnParamHelp').addEventListener('click', () => { $('#paramHelpDlg').hidden = false; });
  $('#paramHelpClose').addEventListener('click', () => { $('#paramHelpDlg').hidden = true; });
  $('#paramHelpDlg').addEventListener('click', (e) => {
    if (e.target.id === 'paramHelpDlg') $('#paramHelpDlg').hidden = true;
  });
  $('#btnPrepare').addEventListener('click', () => {
    const projectId = $('#fProject').value;
    if (!projectId) { toast('先选一个工程', true); return; }
    if (!validatePkg()) { toast('包名不合规，先改好再生成构建工程', true); return; }
    guardEnv(() => {
      if (MOCK) { toast('演示模式：拼装需要原生层'); progress('演示', 100, '未执行'); return; }
      progress('开始拼装', 1, '读取工程');
      call('prepareBuild', projectId, JSON.stringify(formOptions()));
    });
  });

  $('#btnBuild').addEventListener('click', () => {
    if (!S.build.id) { toast('先点「生成构建工程」', true); return; }
    guardEnv(() => {
      if (MOCK) { toast('演示模式：构建需要 Termux'); return; }
      const r = call('startBuild', S.build.id);
      if (!r || !r.ok) { toast((r && r.message) || '启动失败', true); return; }
      S.build.command = r.command || '';
      if (r.mode === 'permission') {
        // 第一次构建：原生层去弹系统授权框，授权回来后它会自己补发，不用再点一次
        toast(r.message || '在系统弹窗里点「允许」，允许后就自动开跑');
        progress('等待授权', 6, '执行 Termux 命令');
        S.build.state = 'running';
        showResult();
        startPolling();
        armTermuxWatch();
        return;
      }
      if (r.mode === 'termux') {
        toast(r.message || '已把构建任务丢给 Termux，切过去能看到它在跑。');
      } else {
        call('copy', r.command);
        toast('没能把命令交给 Termux（被强停或没装好）。先点「打开 Termux」把它唤醒，命令已复制：' + r.command, true);
      }
      progress('构建中', 20, '等待 Termux 回写日志');
      S.build.state = 'running';
      S.build.startedAt = Date.now();
      showResult();
      startPolling();
      armTermuxWatch();
    });
  });

  $('#btnWakeTermux').addEventListener('click', () => {
    call('openTermux');
    toast('Termux 换个前台就不冻了。回来再点一次「开始构建」。');
  });

  $('#btnInstall').addEventListener('click', () => {
    if (!S.build.apk) { toast('还没产出 APK', true); return; }
    call('install', S.build.apk);
  });

  $('#btnCopyApkPath').addEventListener('click', () => {
    if (!S.build.apk) { toast('还没产出 APK', true); return; }
    if (native()) call('copy', S.build.apk);
    toast('已复制 APK 路径：' + S.build.apk);
  });

  $('#btnExport').addEventListener('click', () => {
    if (!S.build.id) { toast('没有可导出的构建', true); return; }
    if (MOCK) { toast('演示模式：导出需要原生层'); return; }
    const r = call('exportBuild', S.build.id);
    if (r && r.ok) { toast('已打包成 zip：' + r.path + '（' + r.size + '）'); }
    else { toast((r && r.message) || '导出失败', true); }
  });

  $('#btnCopyCmd').addEventListener('click', () => {
    const real = S.build.command || (S.build.path ? 'bash ' + S.build.path + '/build_apk.sh' : '');
    if (!real) { toast('还没有可复制的命令', true); return; }
    if (native()) call('copy', real);
    toast('已复制：' + real);
  });

  $('#btnLogRefresh').addEventListener('click', () => { pollBuild(true); });
  $('#btnLogCopy').addEventListener('click', () => {
    const text = S.build.log || '(空)';
    if (native()) call('copy', text);
    toast('日志已复制');
  });
}

function showResult() {
  const card = $('#resultCard');
  if (!S.build.id) { card.hidden = true; return; }
  card.hidden = false;
  card.querySelector('#resultPath').textContent = S.build.apk || ('构建目录 · ' + (S.build.path || ''));
  const badge = $('#resultState');
  const stateMap = { prepared: ['已拼装', ''], running: ['构建中', ' is-run'],
    success: ['成功', ' is-ok'], failed: ['失败', ' is-bad'] };
  const m = stateMap[S.build.state] || [S.build.state || '待命', ''];
  badge.textContent = m[0];
  badge.className = 'badge' + m[1];
  $('#resultSize').textContent = S.build.size || '';
  const installBtn = $('#btnInstall');
  installBtn.hidden = !(S.build.state === 'success' && S.build.apk);
  const hint = $('#resultHint');
  if (S.build.stalled && S.build.state !== 'success' && S.build.state !== 'failed') {
    hint.textContent = 'Termux 没回话：多半是被系统冻结、被强停过，或还没在它的弹窗里点「允许」。'
      + '点下面「打开 Termux」把它拉到前台，回来再点一次「开始构建」。';
  } else if (S.build.state === 'success') {
    const apk = S.build.apk || '';
    const dir = apk ? apk.replace(/\/[^/]+$/, '') : (S.build.path || '');
    hint.innerHTML = 'APK 已生成：<span class="mono">' + esc(apk || dir) + '</span><br>' +
      '找它：手机文件管理器 → 下载/WebGALStation/build 里对应这次构建的文件夹 → out → 那个 .apk。<br>' +
      '<span class="dim">安装前记得：调试签名不适合正式发布；分发时保留 assets/legal/ 里的来路文件。</span>';
  } else if (S.build.state === 'failed') {
    const c = classifyFailure(S.build.reason, S.build.log);
    hint.innerHTML = '<b>' + esc(c.cat) + '</b>：' + esc(c.tip) +
      '<details class="fail-detail"><summary>查看详细日志</summary><pre>' + esc(S.build.reason || S.build.log || '（无）') + '</pre></details>';
  } else if (S.build.state === 'running') {
    hint.textContent = 'Termux 正在编译。日志每 1.2 秒刷新一次。';
  } else {
    hint.textContent = '构建工程已拼装完成，可以开始构建。';
  }
}

function pollIntervalMs() {
  if (S.build.state === 'prepared') return 3000;
  if (S.build.stalled) return 5000;
  return 1200;
}

function startPolling() { schedulePoll(); }

function schedulePoll() {
  if (S.polling) clearInterval(S.polling);
  S.polling = setInterval(() => { pollBuild(); schedulePoll(); }, pollIntervalMs());
}

function stopPolling() {
  if (S.polling) {
    clearInterval(S.polling);
    S.polling = null;
  }
  clearTimeout(S.termuxWatch);
}

/* 从日志关键字映射成人话阶段（关键字对照 kit/build_apk.sh） */
function stageFromLog(log) {
  const t = log || '';
  if (/apksigner|签名/.test(t)) return '正在签名 APK…';
  if (/zipalign|对齐/.test(t)) return '正在对齐 APK…';
  if (/d8|dx\b|classes\.dex|生成 classes/.test(t)) return '正在生成 dex…';
  if (/javac|\.class|编译 Java/.test(t)) return '正在编译代码…';
  if (/aapt2|aapt\b|编译资源|link/.test(t)) return '正在编译资源…';
  return '';
}

/* 失败原因归类：缺工具 / 内存不足 / 包名签名问题 / 其他 */
function classifyFailure(reason, log) {
  const t = ((reason || '') + '\n' + (log || '')).toLowerCase();
  if (/killed|out of memory|oom|heap|内存|124|超时|timed out/.test(t)) {
    return { cat: '内存不足或被系统杀掉', tip: '关掉后台其他 app，或换一个更小的工程再试；低配机建议只打极小样本验证链路。' };
  }
  if (/command not found|not found|aapt2|javac|d8|dx|apksigner|zipalign|缺失|缺/.test(t)) {
    return { cat: '缺打包工具', tip: '去「环境」页复制修复命令，贴到 Termux 装好后重跑自检。' };
  }
  if (/package|包名|signature|keystore|alias|口令|密码|certificate|apksigner/.test(t)) {
    return { cat: '包名或签名问题', tip: '回打包台检查包名写法、签名方式与口令是否填对。' };
  }
  return { cat: '其他原因', tip: '原始日志折叠在下面，可复制后反馈。' };
}

/**
 * Termux 的坑：被系统冻结、被 adb 强停过、或者没给「允许执行命令」，RUN_COMMAND
 * 都会石沉大海。25 秒没日志就把人往「打开 Termux」上推，别让人对着进度条干等。
 */
function armTermuxWatch() {
  clearTimeout(S.termuxWatch);
  S.build.stalled = false;
  S.termuxWatch = setTimeout(() => {
    // 别信 S.build.state：轮询会把 prepared 盖回来，只认「有没有真日志」
    if (S.build.state === 'success' || S.build.state === 'failed') return;
    if ((S.build.log || '').trim().length > 0) return;
    S.build.stalled = true;
    progress('卡住了', 20, 'Termux 没回写日志');
    showResult();
    toast('Termux 那边没动静，八成被冻结了', true);
  }, 25000);
}

function pollBuild(manual) {
  if (!S.build.id) { return; }
  if (MOCK) {
    S.build.state = 'prepared';
    S.build.log = '（演示模式）这里会显示 Termux 的 aapt2 / javac / d8 / apksigner 输出。';
    renderLog();
    showResult();
    return;
  }
  const st = call('buildState', S.build.id);
  if (!st || !st.ok) {
    if (manual) toast((st && st.message) || '读不到构建状态', true);
    return;
  }
  S.build.state = st.state || 'prepared';
  S.build.apk = st.apk || '';
  S.build.size = st.size || '';
  S.build.log = st.log || '';
  S.build.reason = st.reason || '';
  renderLog();
  showResult();
  const stage = stageFromLog(S.build.log);
  if (stage) $('#progText').textContent = stage;
  if (S.build.state === 'success' || S.build.state === 'failed') {
    S.build.stalled = false;
    stopPolling();
    if (S.build.state === 'success') {
      progress('完成', 100, 'APK 已产出');
      if (native() && S.build.apk) call('copy', S.build.apk);
      toast('打包成功：' + (S.build.apk || '') + '（路径已复制，去文件管理器就能找到）');
    } else {
      progress('失败', 100, S.build.reason || '看日志');
      toast('构建失败，去看终端日志', true);
    }
    savePrefs();
  }
}

function renderLog() {
  const view = $('#logView');
  $('#logBuildId').textContent = S.build.id ? S.build.id : '没有进行中的构建';
  view.textContent = S.build.log && S.build.log.trim() ? S.build.log : '（空 · 还没开始构建）';
  view.scrollTop = view.scrollHeight;
}

/* ------------------------------------------------------------------ 环境页动作 */

function resetTermuxBtn() {
  clearTimeout(S.termuxTimer);
  const b = $('#btnTermuxInstall');
  if (!b) return;
  b.disabled = false;
  b.textContent = '装本机 Termux';
}

function bindEnv() {
  const bTermux = $('#btnTermuxInstall');
  if (bTermux) {
    bTermux.addEventListener('click', () => {
      if (MOCK) { toast('演示模式：装 Termux 需要原生层'); return; }
      if (bTermux.disabled) return;
      const r = call('installTermux');
      if (!r || !r.ok) { toast((r && r.message) || '装 Termux 失败', true); return; }
      if (r.extracting || r.sending) {
        bTermux.disabled = true;
        bTermux.textContent = '正在解包 108 MB…（别退后台）';
        toast((r && r.message) || '正在从安装包里解出 Termux');
        clearTimeout(S.termuxTimer);
        S.termuxTimer = setTimeout(resetTermuxBtn, 240000);
      } else {
        bTermux.disabled = true;
        bTermux.textContent = '已拉起安装器…';
        toast((r && r.message) || '已请求安装 Termux');
        clearTimeout(S.termuxTimer);
        S.termuxTimer = setTimeout(resetTermuxBtn, 60000);
      }
    });
  }
  $('#btnDeploy').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：复制安装命令需要原生层'); return; }
    const r = call('deployKit');
    if (!r || !r.ok) { toast((r && r.message) || '部署失败', true); return; }
    call('copy', r.setup);
    toast('安装命令已复制，去 Termux 粘贴回车：' + r.setup, false);
    if (!r.readable) {
      toast('注意：构建目录在应用私有空间，Termux 读不到。先授权「所有文件访问」。', true);
    }
  });

  $('#btnProbe').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：自检需要 Termux'); return; }
    call('runProbe');
    S.probeStart = Date.now();
    toast('已请求 Termux 自检，等它回写结果…');
    const timer = setInterval(() => {
      const p = call('readProbe');
      if (p && p.ok) {
        clearInterval(timer);
        renderProbe(p);
        toast(p.allOk ? '打包工具齐全，可以打包了' : '有组件缺失，看环境页的说明', !p.allOk);
      } else if (Date.now() - S.probeStart > 60000) {
        clearInterval(timer);
        toast('60 秒还没等到自检结果。Termux 里那条命令可能没执行，手动跑自检脚本试试。', true);
      }
    }, 1000);
  });

  $('#btnTermux').addEventListener('click', () => {
    if (MOCK) { toast('演示模式'); return; }
    call('openTermux');
  });

  $('#btnAllFiles').addEventListener('click', () => {
    if (MOCK) { toast('演示模式'); return; }
    call('requestAllFiles');
  });
}

function renderProbe(p) {
  S.probe = p;
  const rows = [
    ['javac / keytool', p.java],
    ['aapt / aapt2', p.aapt2 || p.aapt],
    ['d8 / dx', p.d8 || p.dx],
    ['apksigner', p.apksigner],
    ['zipalign', p.zipalign],
    ['android.jar', p.androidJar],
    ['BuildAPKs', p.buildApks]
  ];
  const trs = rows.map((r) => {
    const ok = r[1] === true || r[1] === 'ok';
    const pill = ok ? '<span class="pill pill--ok">OK</span>'
      : (r[1] ? '<span class="pill pill--warn">' + esc(r[1]) + '</span>' : '<span class="pill pill--bad">缺失</span>');
    return '<tr data-probe-row><td>' + r[0] + '</td><td>' + pill + '</td></tr>';
  }).join('');
  const table = $('#envTable');
  table.querySelectorAll('tr[data-probe-row]').forEach((r) => r.remove());
  table.insertAdjacentHTML('beforeend', trs);
  renderVerdict();
  syncEnvSteps();
}

/* ------------------------------------------------------------------ 关于 / 许可 */

let legalLoaded = false;

async function loadLegalIfNeeded() {
  if (legalLoaded) return;
  legalLoaded = true;
}

function bindAbout() {
  const show = async (which) => {
    const view = $('#legalView');
    view.hidden = false;
    view.textContent = '读取中…';
    if (MOCK) {
      view.textContent = which === 'notice' ? MOCK_LEGAL.notice
        : which === 'mpl' ? MOCK_LEGAL.mpl : MOCK_LEGAL.mit;
      return;
    }
    const file = which === 'disclaimer' ? 'DISCLAIMER.txt'
      : which === 'notice' ? 'NOTICE.txt'
        : which === 'mpl' ? 'MPL-2.0.txt' : 'LICENSE-MIT.txt';
    try {
      const res = await fetch('/legal/' + file, { cache: 'no-store' });
      view.textContent = await res.text();
    } catch (e) {
      view.textContent = '读取失败：' + e.message;
    }
    // 正文插在按钮下方，别让用户以为按钮没反应
    try { view.scrollIntoView({ block: 'nearest' }); } catch (e) { /* 老 WebView 没有就算了 */ }
  };
  $('#btnDisc').addEventListener('click', () => show('disclaimer'));
  $('#btnNotice').addEventListener('click', () => show('notice'));
  $('#btnMpl').addEventListener('click', () => show('mpl'));
  $('#btnMit').addEventListener('click', () => show('mit'));
  $$('.btn[data-url]').forEach((b) => {
    b.addEventListener('click', () => {
      if (MOCK) { window.open(b.dataset.url, '_blank'); return; }
      call('openUrl', b.dataset.url);
    });
  });
}

/* ------------------------------------------------------------------ 首启免责 */

function bindGate() {
  const gate = $('#gate');
  const accept = () => {
    gate.hidden = true;
    if (native()) call('acceptDisclaimer');
    // 免责看完就查环境：缺权限/缺 Termux 的当场补，别等打包时撞墙
    setTimeout(afterGate, 80);
  };
  $('#btnGateOk').addEventListener('click', accept);

  let gateDetailOpen = false;
  const collapseBtn = $('#btnGateCollapse');
  const toggleDetail = async () => {
    const box = $('.gate__box');
    let pre = box.querySelector('pre');
    if (gateDetailOpen && pre) {
      pre.remove();
      gateDetailOpen = false;
      $('#btnGateDetail').textContent = '读完整免责声明';
      $('#gateDetailActions').hidden = true;
      $('#gateDetailActions').append(collapseBtn);
      return;
    }
    gateDetailOpen = true;
    pre = document.createElement('pre');
    pre.className = 'legal-view mono';
    pre.textContent = '读取中…';
    box.appendChild(pre);
    pre.after(collapseBtn);
    $('#btnGateDetail').textContent = '收起声明';
    $('#gateDetailActions').hidden = false;
    if (MOCK) { pre.textContent = MOCK_LEGAL.notice; return; }
    try {
      const res = await fetch('/legal/DISCLAIMER.txt', { cache: 'no-store' });
      pre.textContent = await res.text();
    } catch (e) {
      pre.textContent = '读取失败：' + e.message;
    }
    pre.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  $('#btnGateDetail').addEventListener('click', toggleDetail);
  $('#btnGateCollapse').addEventListener('click', toggleDetail);
  return gate;
}

function maybeShowGate() {
  const env = S.env;
  if (!env) return;
  if (MOCK) return;
  if (!env.disclaimer) {
    $('#gate').hidden = false;
  }
}

/* ------------------------------------------------------------------ 布局 / 主题 */

const LAYOUTS = ['right', 'left', 'center'];
const LAYOUT_LABEL = { right: '打包台：右', left: '打包台：左', center: '打包台：中' };

function applyLayout(mode, persist) {
  const m = LAYOUTS.indexOf(mode) >= 0 ? mode : 'right';
  document.body.dataset.layout = m;
  if ($('#btnLayout')) $('#btnLayout').textContent = LAYOUT_LABEL[m];
  $$('#fLayoutSeg button').forEach((b) => b.classList.toggle('is-on', b.dataset.v === m));
  if (persist !== false) { S.prefs.layout = m; savePrefs(); }
  // 换布局后两条栏可能把舞台挤成一条缝，按可用宽度回收一次
  setTimeout(fitWidths, 60);
}

/** 舞台至少留 26% 宽，留不出来就按比例压两条栏（用户之前拖多宽都尊重，能塞下就不动）。 */
function fitWidths() {
  const wb = $('#tab-workbench');
  if (!wb || document.body.dataset.layout === 'center') return;
  const avail = wb.getBoundingClientRect().width;
  if (avail < 400) return;
  const rs = getComputedStyle(document.documentElement);
  let dock = parseFloat(rs.getPropertyValue('--dock')) || 360;
  let rail = parseFloat(rs.getPropertyValue('--rail')) || 260;
  const handles = 18;
  const minStage = Math.max(180, avail * 0.26);
  if (avail - dock - rail - handles >= minStage) return;
  const allowed = Math.max(0, avail - minStage - handles);
  const total = dock + rail;
  const k = total > 0 ? allowed / total : 1;
  applyDockWidth(Math.max(46, Math.floor(dock * k)), true);
  applyRailWidth(Math.max(46, Math.floor(rail * k)), true);
}

function applyTheme(theme, persist) {
  const t = theme === 'light' ? 'light' : 'dark';
  document.documentElement.dataset.theme = t;
  if ($('#btnTheme')) $('#btnTheme').textContent = t === 'light' ? '亮色' : '深色';
  $$('#fThemeSeg button').forEach((b) => b.classList.toggle('is-on', b.dataset.v === t));
  if (persist !== false) { S.prefs.theme = t; savePrefs(); }
}

function applyDockWidth(px, persist) {
  if (!px || isNaN(px)) return;
  const w = Math.round(Math.max(46, Math.min(px, 720)));
  document.documentElement.style.setProperty('--dock', w + 'px');
  // 窄了就把字号和两列网格收掉，别让控件挤成一条缝
  document.body.classList.toggle('dock--collapsed', w < 120);
  document.body.classList.toggle('dock--narrow', w >= 120 && w < 320);
  document.body.classList.toggle('dock--wide', w >= 460);
  if (persist) {
    S.prefs.dock = w;
    // 记住收起前的宽度，细条还原时回到用户自己拉的那一档
    if (w >= 120) S.prefs.dockPrev = w;
    savePrefs();
  }
}

function applyRailWidth(px, persist) {
  if (!px || isNaN(px)) return;
  const w = Math.round(Math.max(46, Math.min(px, 460)));
  document.documentElement.style.setProperty('--rail', w + 'px');
  document.body.classList.toggle('rail--collapsed', w < 100);
  document.body.classList.toggle('rail--narrow', w >= 100 && w < 210);
  document.body.classList.toggle('rail--wide', w >= 300);
  if (persist) {
    S.prefs.rail = w;
    if (w >= 100) S.prefs.railPrev = w;
    savePrefs();
  }
}

function restoreRail() {
  applyRailWidth(S.prefs.railPrev || 260, true);
}

function restoreDock() {
  applyDockWidth(S.prefs.dockPrev || 380, true);
}

function bindChrome() {
  if ($('#btnLayout')) {
    $('#btnLayout').addEventListener('click', () => {
      const cur = document.body.dataset.layout || 'right';
      const next = LAYOUTS[(LAYOUTS.indexOf(cur) + 1) % LAYOUTS.length];
      applyLayout(next, true);
    });
  }
  if ($('#btnTheme')) {
    $('#btnTheme').addEventListener('click', () => {
      const cur = document.documentElement.dataset.theme === 'light' ? 'light' : 'dark';
      applyTheme(cur === 'light' ? 'dark' : 'light', true);
    });
  }
  $$('#fLayoutSeg button').forEach((b) => {
    b.addEventListener('click', () => applyLayout(b.dataset.v, true));
  });
  $$('#fThemeSeg button').forEach((b) => {
    b.addEventListener('click', () => applyTheme(b.dataset.v, true));
  });
  // 两条缝都能拖：左改工程库，右改打包台
  bindSplit('#splitterL', '--rail', applyRailWidth, true);
  bindSplit('#splitter', '--dock', applyDockWidth, false);
}

/**
 * 通用拖拽。dockSide=true 表示拖的是左边那条（宽度 = 指针离容器左边），
 * 否则是右边那条（宽度 = 容器右边到指针）。
 */
function bindSplit(selector, cssVar, apply, dockSide) {
  const el = $(selector);
  const wb = $('#tab-workbench');
  if (!el || !wb) return;
  let dragging = false;
  let startX = 0, startY = 0, lastX = 0, lastY = 0;

  const onDown = (e) => {
    dragging = true;
    startX = lastX = e.clientX;
    startY = lastY = e.clientY;
    el.classList.add('is-drag');
    try { el.setPointerCapture(e.pointerId); } catch (err) { /* 老 WebView 没这接口 */ }
    e.preventDefault();
  };
  const onMove = (e) => {
    if (!dragging) return;
    lastX = e.clientX; lastY = e.clientY;
    const rect = wb.getBoundingClientRect();
    const mode = document.body.dataset.layout || 'right';
    // 布局翻转后，物理左右跟着翻，判据以「这条缝实际在哪一侧」为准
    const leftSide = dockSide ? (mode !== 'left') : (mode === 'left');
    const width = leftSide ? (e.clientX - rect.left) : (rect.right - e.clientX);
    const max = Math.min(720, rect.width * 0.68);
    apply(Math.max(46, Math.min(width, max)), false);
    e.preventDefault();
  };
  const onUp = () => {
    if (!dragging) return;
    dragging = false;
    el.classList.remove('is-drag');
    const cur = parseInt(getComputedStyle(document.documentElement).getPropertyValue(cssVar), 10);
    // 指尖落在缝上、只挪了不到 10px：当成「点了一下」，两栏收起来时就还原
    const moved = Math.abs((lastX || 0) - startX) > 9 || Math.abs((lastY || 0) - startY) > 9;
    if (!moved) {
      if (cssVar === '--rail' && document.body.classList.contains('rail--collapsed')) { restoreRail(); return; }
      if (cssVar === '--dock' && document.body.classList.contains('dock--collapsed')) { restoreDock(); return; }
    }
    if (cur) apply(cur, true);
  };

  el.addEventListener('pointerdown', onDown);
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  window.addEventListener('pointercancel', onUp);
}

/* ------------------------------------------------------------------ 首启环境体检 */

function obState(el, text, cls) {
  if (!el) return;
  el.textContent = text;
  el.className = 'ob-state' + (cls ? ' is-' + cls : '');
}

function renderOnboard(o) {
  obState($('#obFilesState'), o.allFiles ? '已授权' : '未授权', o.allFiles ? 'ok' : 'bad');
  if (o.allFiles) {
    $('#obFiles').textContent = '去系统里检查';
  }

  obState($('#obTermuxState'), o.termux ? ('已安装 ' + (o.termuxVersion || '')) : '未安装',
    o.termux ? 'ok' : 'bad');
  $('#obTermuxOpen').hidden = !o.termux;
  $('#obTermuxFdroid').hidden = !!o.termux;
  if (!o.termux) {
    $('#obTermux').hidden = false;
    $('#obTermux').textContent = o.termuxApk ? ('本机安装包（' + (o.termuxApkSize || '已找到') + '）') : '本机安装包';
    $('#obTermuxHint').textContent = o.termuxApk
      ? ('本机已找到安装包：' + o.termuxApk)
      : '本机还没找到安装包，可点右边去 F-Droid 下载。';
  } else {
    $('#obTermux').hidden = true;
  }

  obState($('#obKitState'), o.kitDeployed ? '已就位' : '未就位', o.kitDeployed ? 'ok' : 'warn');
  $('#obKitCopy').hidden = !o.kitDeployed;
}

function refreshOnboard() {
  if (MOCK) return null;
  const o = call('onboard');
  if (!o || !o.ok) return null;
  S.onboard = o;
  renderOnboard(o);
  syncEnvSteps();
  return o;
}

function maybeShowOnboard() {
  if (MOCK) return;
  if (S.prefs.obDone || S.prefs.obSkipped) return;
  const o = refreshOnboard();
  if (!o) return;
  // 三样全齐就不用打扰
  if (o.allFiles && o.termux && o.kitDeployed) return;
  $('#onboard').hidden = false;

  // 头一次缺文件权限：直接把系统页拉起来，别让人自己去找开关
  if (!o.allFiles && !S.obAutoAsked) {
    S.obAutoAsked = true;
    toast('缺「所有文件访问」：这就帮你打开系统权限页');
    setTimeout(() => { if (!MOCK) call('requestAllFiles'); }, 700);
  }
}

/**
 * 复制离线安装命令并延迟打开 Termux。
 * 不延迟的话，Termux 抢到前台时剪贴板还没写完，玩家粘出来会是上一段旧文本。
 */
function copyOfflineCommand(cmd) {
  if (!cmd) {
    toast('没有拿到安装命令：回环境页点「复制环境安装命令」再来一次', true);
    return false;
  }
  const r = call('copy', cmd);
  if (!r || !r.ok) {
    toast('自动复制失败：回环境页点「复制环境安装命令」，再在 Termux 长按粘贴', true);
    return false;
  }
  toast('命令已复制。Termux 里长按屏幕 → Paste → 回车；全程离线，约 1 分钟。');
  setTimeout(() => { if (!MOCK) call('openTermux'); }, 900);
  return true;
}

function bindOnboard() {
  const close = (skipped) => {
    $('#onboard').hidden = true;
    if (skipped) { S.prefs.obSkipped = true; } else { S.prefs.obDone = true; }
    savePrefs();
    // 体检关掉之后才轮到「这玩意儿怎么用」，顺序反了会同时叠两个浮层
    setTimeout(maybeShowGuide, 200);
  };

  $('#obFiles').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：权限得在真机上给'); return; }
    call('requestAllFiles');
    toast('已拉起系统权限页：找到「允许访问所有文件」打开，回来状态会自己变。');
  });

  $('#obTermux').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：装不了 Termux'); return; }
    const r = call('installTermux');
    if (!r || !r.ok) {
      toast((r && r.message) || '装不了 Termux', true);
      if (r && r.needDownload) call('openUrl', 'https://f-droid.org/packages/com.termux/');
      return;
    }
    toast(r.message || '已交给系统安装器');
  });

  $('#obTermuxOpen').addEventListener('click', () => {
    if (MOCK) return;
    call('openTermux');
  });

  $('#obTermuxFdroid').addEventListener('click', () => {
    if (MOCK) { window.open('https://f-droid.org/packages/com.termux/', '_blank'); return; }
    call('openUrl', 'https://f-droid.org/packages/com.termux/');
  });

  const deployAndCopy = () => {
    if (MOCK) { toast('演示模式：复制安装命令需要原生层'); return null; }
    if (S.onboard && !S.onboard.termux) {
      toast('Termux 还没装：先点上面「本机安装包」装完，再回来点这个', true);
      const ir = call('installTermux');
      if (ir && ir.ok) toast(ir.message || '已交给系统安装器');
      return ir;
    }
    const r = call('deployKit');
    if (!r || !r.ok) { toast((r && r.message) || '部署失败', true); return null; }
    const off = parse(call('offline'));
    if (off && off.ok && off.bundle) {
      // 完全体：不再去网络源碰运气，直接释放随包工具链，释放完自动复制命令 + 打开 Termux。
      const x = call('offlineInstall');
      if (!x || !x.ok) { toast((x && x.message) || '离线包释放失败', true); return null; }
      if (x.sending) {
        toast((x.message || '正在释放离线工具链') + '；完了自动把命令塞进剪贴板。');
        refreshOnboard();
        return x;
      }
      // 缓存命中：不重拷 160 MB，但必须把命令复制了，别让按钮点了没反应。
      toast(x.message || '离线包已在位');
      refreshOnboard();
      copyOfflineCommand(x.command || r.setup);
      return x;
    }
    call('copy', r.setup);
    toast('安装命令已复制，切到 Termux 粘贴回车（只做一次）');
    refreshOnboard();
    return r;
  };

  $('#obKit').addEventListener('click', deployAndCopy);
  $('#obKitCopy').addEventListener('click', () => {
    deployAndCopy();
  });

  $('#obSkip').addEventListener('click', () => close(true));
  $('#obOk').addEventListener('click', () => close(false));
}

/* ------------------------------------------------------------------ 新手引导 */

/**
 * 八步，指哪打哪。目标元素先切到对应标签页再量，别对着隐藏的面板算坐标。
 * 引导工程本身也是真工程，第 2 步就让人知道「预览」在哪，别只给一张说明图。
 * 顺序按新手真实动线走：看工程 → 看预览 → 改剧本 → 插语句 → 打包台填参数 → 选签名 → 体检 → 有问题问谁。
 */
const GUIDE_STEPS = [
  {
    tab: 'workbench', sel: '#projectList',
    title: '第 1 步 · 工程库',
    text: '工程库这一栏。带「引导」标签那张就是你现在这个工程——它是真工程，能改、能预览、能打包。下面那三颗按钮是新建、导入、刷新。'
  },
  {
    tab: 'workbench', sel: '#tab-workbench .stage',
    title: '第 2 步 · 预览舞台',
    text: '中间这块是引擎本体，跑在内嵌窗口里，不占满屏。选工程后点卡片上的「预览」，游戏就出现在这里；改完剧本点「重载」看新内容。'
  },
  {
    tab: 'editor', sel: '#tab-editor .rail--ed',
    title: '第 3 步 · 场景与素材',
    text: '「剧本编辑」左边是场景列表和素材一览。点场景名切换文件，点「新建场景」加一幕——名字别用空格和斜杠，重名会当场拦你。'
  },
  {
    tab: 'editor', sel: '#tab-editor .ed-foot',
    title: '第 4 步 · 语句快捷插入',
    text: '底下这一排按钮：旁白、角色台词、换背景、跳场景、音乐、立绘、变量。点一下就把指令插到光标处，改完点右上「保存」，回工作台预览立刻生效。'
  },
  {
    tab: 'workbench', sel: '#tab-workbench .rail--right',
    title: '第 5 步 · 打包台参数',
    text: '打包台这一栏（默认在右边，也能拖到左边或中间）。应用名随便起；包名必须是 com.xxx.yyy 这种格式，填不对按钮不亮。版本号自己往上走，不用手改。方向默认锁横屏，图标可以选图。'
  },
  {
    tab: 'workbench', sel: '#fSignMode',
    title: '第 6 步 · 签名方式',
    text: '三档：内置调试（能装能测、不能上架）、我的 keystore（填路径和口令）、新建 keystore（手机上现生成）。自测就选第一档，别纠结。'
  },
  {
    tab: 'env', sel: '#tab-env .env-steps',
    title: '第 7 步 · 环境自检',
    text: '「环境」页检查文件权限、Termux、打包工具。红哪条点哪条，每条都给了复制命令的按钮。第一次用先点「运行自检」跑一遍。'
  },
  {
    tab: 'workbench', sel: '#askFab',
    title: '第 8 步 · 不会就问（右下浮球）',
    text: '右下角这颗「问」是浮窗答疑：点开填你自己的 OpenAI 兼容接口、模型名和 Key，它带着本软件的用法和当前剧本一起读，答完点「收起」回到球。Key 只存本机；回答是 AI 生成，可能有误，照着做前先核实。'
  }
];

let guideIdx = -1;

function maybeShowGuide() {
  if (MOCK) return;
  if (S.prefs.guideDone) return;
  if (!$('#gate').hidden || !$('#onboard').hidden) return;
  openGuide(0);
}

function openGuide(i) {
  if (i < 0 || i >= GUIDE_STEPS.length) { closeGuide(true); return; }
  guideIdx = i;
  const step = GUIDE_STEPS[i];
  $('#guide').hidden = false;
  $('#guideStep').textContent = (i + 1) + ' / ' + GUIDE_STEPS.length;
  $('#guideTitle').textContent = step.title;
  $('#guideText').textContent = step.text;
  $('#guidePrev').disabled = i === 0;
  $('#guideNext').textContent = i === GUIDE_STEPS.length - 1 ? '知道了' : '下一步';
  switchTab(step.tab);
  // 切页后布局要一帧才稳，量坐标前先等一次
  requestAnimationFrame(() => positionGuide(step.sel));
}

function positionGuide(sel) {
  const spot = $('#guideSpot');
  const card = $('#guideCard');
  const el = $(sel);
  const pad = 8;
  if (!el || el.offsetParent === null) {
    // 目标不可见（比如面板被隐藏）：不画洞，卡片居中，别留个黑洞吓人
    spot.style.left = spot.style.top = '0px';
    spot.style.width = spot.style.height = '0px';
    spot.style.boxShadow = '0 0 0 2400px rgba(0,0,0,.72)';
    card.style.left = '50%';
    card.style.top = '50%';
    card.style.transform = 'translate(-50%, -50%)';
    return;
  }
  const r = el.getBoundingClientRect();
  spot.style.boxShadow = '0 0 0 2400px rgba(0,0,0,.72)';
  spot.style.left = Math.max(0, r.left - pad) + 'px';
  spot.style.top = Math.max(0, r.top - pad) + 'px';
  spot.style.width = (r.width + pad * 2) + 'px';
  spot.style.height = (r.height + pad * 2) + 'px';
  spot.style.borderRadius = '10px';

  const cw = card.offsetWidth || 340;
  const ch = card.offsetHeight || 160;
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const gap = 14;
  // 优先放右边、再放下面，最后才压到目标身上——卡片盖住洞等于白指
  let x;
  let y;
  if (vw - r.right >= cw + gap) {
    x = r.right + gap;
    y = Math.min(Math.max(8, r.top), vh - ch - 8);
  } else if (r.bottom + gap + ch <= vh - 8) {
    x = Math.min(Math.max(8, r.left), vw - cw - 8);
    y = r.bottom + gap;
  } else if (r.top - gap - ch >= 8) {
    x = Math.min(Math.max(8, r.left), vw - cw - 8);
    y = r.top - ch - gap;
  } else if (r.left - gap - cw >= 8) {
    x = r.left - cw - gap;
    y = Math.min(Math.max(8, r.top), vh - ch - 8);
  } else {
    x = Math.max(8, Math.min(r.left + 12, vw - cw - 8));
    y = Math.max(8, Math.min(r.bottom - ch - 12, vh - ch - 8));
  }
  card.style.transform = 'none';
  card.style.left = x + 'px';
  card.style.top = Math.max(8, Math.min(y, vh - ch - 8)) + 'px';
}

function closeGuide(done) {
  guideIdx = -1;
  $('#guide').hidden = true;
  if (done) { S.prefs.guideDone = true; savePrefs(); }
}

function bindGuide() {
  if (!$('#guide')) return;
  $('#btnGuide').addEventListener('click', () => { openGuide(0); });
  $('#guideNext').addEventListener('click', () => {
    if (guideIdx >= GUIDE_STEPS.length - 1) { closeGuide(true); toast('引导走完了，顶栏「引导」随时能重看'); return; }
    openGuide(guideIdx + 1);
  });
  $('#guidePrev').addEventListener('click', () => openGuide(guideIdx - 1));
  $('#guideSkip').addEventListener('click', () => { closeGuide(true); toast('跳过引导。顶栏「引导」还能叫回来'); });
  // 转屏后重算洞的位置，否则卡片会飘到屏幕外
  window.addEventListener('resize', () => {
    if (guideIdx >= 0) positionGuide(GUIDE_STEPS[guideIdx].sel);
  });
}

/* ------------------------------------------------------------------ 事件 */

function renderImportBar(data) {
  data = data || {};
  const box = $('#projectList');
  let bar = box.querySelector('.import-bar');
  if (!bar) {
    bar = document.createElement('div');
    bar.className = 'import-bar';
    box.prepend(bar);
  }
  const f = data.files || 0;
  const total = data.total || 0;
  const pct = total ? Math.min(100, Math.round((f / total) * 100)) : (data.percent || 0);
  const meta = data.detail || (total ? (f + ' / ' + total) : '');
  bar.innerHTML = '<span>正在复制工程文件…</span>' +
    '<div class="prog"><div class="prog__bar" style="width:' + pct + '%"></div></div>' +
    '<span class="mono">' + esc(meta || '') + '</span>';
}

/* ============ 不会就问：用户自带 API ============ */

const AI = { busy: false, id: '', hydrated: false };

function loadAskPage() {
  const ai = (S.prefs && S.prefs.ai) || {};
  if (!$('#fAiBase')) return;
  $('#fAiBase').value = ai.base || '';
  $('#fAiModel').value = ai.model || '';
  $('#fAiKey').value = ai.key || '';
  AI.hydrated = true;
  renderAiState();
}

function readAiCfg() {
  return {
    base: $('#fAiBase').value.trim(),
    model: $('#fAiModel').value.trim(),
    key: $('#fAiKey').value.trim()
  };
}

function renderAiState() {
  const ai = readAiCfg();
  const st = $('#aiState');
  if (!st) return;
  if (!ai.base || !ai.model) {
    st.textContent = '没配过就是空的';
    st.classList.remove('hint--bad');
  } else {
    st.textContent = '已配置 · ' + ai.model + (ai.key ? ' · 带 Key' : ' · 无 Key');
    st.classList.remove('hint--bad');
  }
}

function saveAiCfg() {
  const ai = readAiCfg();
  if (!ai.base || !ai.model) {
    toast('接口地址和模型名都得填，不然问谁去', true);
    return;
  }
  if (!/^https:\/\//i.test(ai.base) && !/^http:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?\//i.test(ai.base)) {
    toast('只收 https；本机回环（127.0.0.1 / localhost）才允许 http', true);
    return;
  }
  S.prefs.ai = ai;
  savePrefs();
  renderAiState();
  toast('存好了，Key 只在本机');
}

function clearAiCfg() {
  $('#fAiBase').value = '';
  $('#fAiModel').value = '';
  $('#fAiKey').value = '';
  S.prefs.ai = { base: '', model: '', key: '' };
  savePrefs();
  renderAiState();
  $('#aiAnswer').hidden = true;
  toast('配置清空了');
}

function askAI() {
  if (AI.busy) return;
  const q = $('#fAiQ').value.trim();
  if (!q) { toast('先写问题', true); return; }
  const ai = readAiCfg();
  if (!ai.base || !ai.model) { toast('先把上面的接口地址和模型名填上，再点「问一下」', true); return; }
  AI.busy = true;
  AI.id = 'q' + Date.now() + '-' + Math.floor(Math.random() * 1000);
  $('#btnAiAsk').disabled = true;
  $('#aiTip').textContent = '等回答…';
  $('#aiAnswer').hidden = false;
  $('#aiAnswer').classList.remove('is-bad');
  $('#aiAnswerHead').textContent = '请求中 · ' + ai.model;
  $('#aiAnswerBody').textContent = '已经发出去，最多等 60 秒。这段时间别急着再点。';

  if (MOCK) {
    setTimeout(() => handleAiReply({ id: AI.id, ok: 1, text: '（桌面预览模式）这里不会真发请求；装到手机上才会用你配的接口。' }), 500);
    return;
  }
  const n = native();
  if (!n || typeof n.askAI !== 'function') {
    handleAiReply({ id: AI.id, ok: 0, message: '原生层没有 askAI 接口（装的不是这一版 APK？）' });
    return;
  }
  let r = null;
  try {
    r = JSON.parse(n.askAI(JSON.stringify({
      base: ai.base, model: ai.model, key: ai.key, prompt: q, id: AI.id,
      ctx: AI_BRIEF + '\n\n' + aiContext()
    })) || '{}');
  } catch (e) {
    handleAiReply({ id: AI.id, ok: 0, message: '桥没回话：' + e.message });
    return;
  }
  if (!r || !r.ok) {
    handleAiReply({ id: AI.id, ok: 0, message: (r && r.message) || '请求没被受理' });
  }
}

function handleAiReply(d) {
  if (!d) return;
  // 只认最后一次提问的回答，别让上一个慢请求把新问题盖掉
  if (d.id && AI.id && d.id !== AI.id) return;
  AI.busy = false;
  $('#btnAiAsk').disabled = false;
  $('#aiTip').textContent = '答案来自你配的模型';
  $('#aiAnswer').hidden = false;
  $('#aiAnswer').classList.toggle('is-bad', !d.ok);
  const ai = readAiCfg();
  $('#aiAnswerHead').textContent = d.ok ? '回答 · ' + (ai.model || '') : '出错了';
  $('#aiAnswerBody').textContent = d.ok ? (d.text || '（空回答）') : (d.message || '没拿到回答');
}

function bindAsk() {
  if (!$('#askDock')) return;
  $('#btnAiSave').addEventListener('click', saveAiCfg);
  $('#btnAiClear').addEventListener('click', clearAiCfg);
  $('#btnAiAsk').addEventListener('click', askAI);
  ['#fAiBase', '#fAiModel', '#fAiKey'].forEach((sel) => {
    $(sel).addEventListener('change', renderAiState);
  });
  $('#fAiQ').addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') askAI();
  });
  if ($('#askFab')) {
    $('#askFab').addEventListener('click', () => openAskDock($('#askDock').hidden));
  }
  if ($('#btnAiFold')) $('#btnAiFold').addEventListener('click', () => openAskDock(false));
}

function openAskDock(on) {
  const dock = $('#askDock');
  if (!dock) return;
  dock.hidden = !on;
  const fab = $('#askFab');
  if (fab) {
    fab.classList.toggle('is-on', !!on);
    fab.textContent = on ? '收' : '问';
  }
  if (on) {
    loadAskPage();
    AI.hydrated = true;
  }
}


/* ---------- 两栏拖到近乎收起：点一下细条就还原 ---------- */
function bindRailCollapse() {
  const wb = $('#tab-workbench');
  if (!wb) return;
  if ($('#btnFoldDock')) $('#btnFoldDock').addEventListener('click', (e) => { e.stopPropagation(); applyDockWidth(46, true); toast('打包台已收起 · 点细条展开'); });
  if ($('#btnFoldRail')) $('#btnFoldRail').addEventListener('click', (e) => { e.stopPropagation(); applyRailWidth(46, true); toast('工程库已收起 · 点细条展开'); });

  // 细条只有 46px，还紧挨着 10px 的拖拽缝；靠冒泡等 click 经常被 pointerdown 的
  // preventDefault 吞掉，或者手指落在缝上变成「拖了 0 像素」。所以：
  // 1) 直接在栏元素上挂 click（伪元素 ::after 的命中也会落到栏本体）
  // 2) 再补 pointerup 兜底，位移 <8px 才算点击，避免和拖拽抢事件
  const bindRailTap = (sel, collapsed, restore) => {
    const el = $(sel);
    if (!el) return;
    const fire = (e) => {
      if (!collapsed()) return;
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      restore();
    };
    el.addEventListener('click', (e) => { if (collapsed()) fire(e); });
    let sx = 0, sy = 0, moved = false, down = false;
    el.addEventListener('pointerdown', (e) => { down = true; moved = false; sx = e.clientX; sy = e.clientY; });
    el.addEventListener('pointermove', (e) => {
      if (!down) return;
      if (Math.abs(e.clientX - sx) > 8 || Math.abs(e.clientY - sy) > 8) moved = true;
    });
    el.addEventListener('pointerup', (e) => {
      if (!down) return;
      down = false;
      if (!moved) fire(e);
    });
    el.addEventListener('pointercancel', () => { down = false; });
  };
  bindRailTap('.rail--left',
    () => document.body.classList.contains('rail--collapsed'),
    () => { restoreRail(); toast('工程库已还原'); });
  bindRailTap('.rail--right',
    () => document.body.classList.contains('dock--collapsed'),
    () => { restoreDock(); toast('打包台已还原'); });

  // 兜底：老路径（委托）保留，防止某些布局下栏被重建、直挂的监听丢了
  wb.addEventListener('click', (e) => {
    const rack = e.target.closest ? e.target.closest('.rail--left, .rail--right') : null;
    if (!rack) return;
    if (rack.classList.contains('rail--left') && document.body.classList.contains('rail--collapsed')) {
      restoreRail();
      e.stopPropagation();
      return;
    }
    if (rack.classList.contains('rail--right') && document.body.classList.contains('dock--collapsed')) {
      restoreDock();
      e.stopPropagation();
    }
  });
}

/* ---------- 每 5 分钟自动保存（剧本 + 偏好） ---------- */
function startAutoSave() {
  setInterval(() => {
    if (ED.dirty && ED.pid && ED.scene) {
      if (saveScene(true)) toast('已自动保存 · 每 5 分钟一次');
    }
    if (native()) savePrefs();
  }, 5 * 60 * 1000);
}

/* ---------- 免责关掉后：体检 → 启动页 → 引导，一条链走完 ---------- */
function afterGate() {
  if (MOCK) { if (!S.prefs.bootSkip) showBootPick(); return; }
  maybeShowOnboard();
  if ($('#onboard') && !$('#onboard').hidden) return;
  if (!S.prefs.bootSkip) showBootPick(); else maybeShowGuide();
}
/* ---------- 启动页：像 PS 那样先挑项目 ---------- */
function renderBootList() {
  const box = $('#bootList');
  if (!box) return;
  if (!S.projects.length) {
    box.innerHTML = '<div class="empty">还没有工程。点「新建项目」起一个，或「导入工程」把别处的包拎进来。</div>';
    return;
  }
  const cur = $('#fProject').value || S.prefs.projectId || '';
  box.innerHTML = S.projects.map((p) =>
    '<button class="boot__item' + (p.id === cur ? ' is-cur' : '') + '" data-boot="' + esc(p.id) + '">' +
      '<span class="boot__name">' + esc(p.name) + '</span>' +
      '<span class="boot__meta">场景 ' + (p.scenes || 0) + ' · 文件 ' + (p.files || 0) + ' · ' + esc(p.bytesText || '—') + '</span>' +
    '</button>').join('');
}

function closeBoot() {
  $('#bootPick').hidden = true;
  if ($('#bootNoAsk') && $('#bootNoAsk').checked) {
    S.prefs.bootSkip = true;
    savePrefs();
  }
  maybeShowGuide();
}

function showBootPick() {
  if (MOCK) return;
  if (S.prefs.bootSkip) return;
  renderBootList();
  $('#bootPick').hidden = false;
}

function bindBoot() {
  if (!$('#bootPick')) return;
  $('#bootPick').addEventListener('click', (e) => {
    const it = e.target.closest ? e.target.closest('[data-boot]') : null;
    if (!it) return;
    const id = it.getAttribute('data-boot');
    const name = (it.querySelector('.boot__name') || {}).textContent || id;
    closeBoot();
    switchTab('workbench');
    $('#fProject').value = id;
    S.prefs.projectId = id;
    savePrefs();
    try { loadStage(id); } catch (err) { /* 舞台挂了也要先把启动页关掉 */ }
    toast('已打开工程 · ' + name);
  });
  $('#bootSkip').addEventListener('click', closeBoot);
  $('#bootNew').addEventListener('click', () => {
    closeBoot();
    $('#newDlg').hidden = false;
    $('#newName').value = '';
    $('#newStarter').value = '';
    setTimeout(() => $('#newName').focus(), 80);
  });
  $('#bootImport').addEventListener('click', () => {
    closeBoot();
    call('pickProject');
  });
}

/* ---------- 喂给 AI 的软件说明 + 当前上下文 ---------- */
const AI_BRIEF = [
  '软件名：织戏台 · 移动工作站（Android 工作站，网页 UI + Termux 端上打包，包名 com.zhixitai.vn）。',
  '页签：工作台（左/右栏是打包台与工程库，可拖到近乎收起，点细条还原；中间是引擎预览舞台）、剧本编辑（场景列表+素材+语句快捷插入）、终端（构建日志）、环境（自检与故障说明）、关于（许可）。',
  '不会就问：右下角圆球，点开是浮窗，再点「收起」或球收回。用户自填 OpenAI 兼容 baseUrl（只放行 https，本机 127.0.0.1/localhost 例外）、模型名、Key；Key 只存本机偏好，不进日志与产物。',
  '打包：打包台填应用名、包名 com.xxx.yyy、版本号自动递增（versionCode 2618xxxx、名字 1.1.6-beta.N），签名三档（内置调试/自有 keystore/新建 keystore）；点开始构建后在手机本机跑 BuildAPKs，产物落 /storage/emulated/0/Download/WebGALStation/build/<job>/out。',
  '剧本拼接逻辑：工程在 projects/<id>/，配置 config.txt，剧本 game/scene/*.txt；语句按行拼：`角色名:台词`、旁白直接写、`changeBg:文件名 -next`、`bgm:文件名`、`changeScene:文件名.txt`、`jumpLabel:标签`、`setVar:变量=值`；场景名不要空格和斜杠；编辑页底部按钮把语句插到光标处，保存后回工作台点「重载」生效。',
  '每 5 分钟自动保存剧本与偏好。已知限制：release 包关了 WebView 调试口；明文 http 只允许回环；WebGAL file:// 直跑不行；端上安装中文名 APK 要加 --no-incremental；vivo「重新安装」等于卸载清数据。'
].join('\n');

function aiContext() {
  let proj = '';
  const sel = $('#fProject');
  if (sel && sel.options.length) {
    const opt = sel.options[sel.selectedIndex];
    proj = opt ? opt.textContent : '';
  }
  const scene = ED.scene || '';
  const text = $('#edText') ? ($('#edText').value || '').slice(0, 1200) : '';
  return [
    '当前工程：' + (proj || '（未选）'),
    '当前场景：' + (scene || '（未打开）'),
    '当前剧本片段（最多 1200 字）：',
    text || '（空）'
  ].join('\n');
}

function clearImportBar() {
  const bar = $('#projectList .import-bar');
  if (bar) bar.remove();
  S.importing = false;
  $('#btnImport').disabled = false;
  $('#btnImportZip').disabled = false;
}

window.__stationEmit = function (type, payloadJson) {
  let data = {};
  try { data = JSON.parse(payloadJson || '{}'); } catch (e) { data = {}; }
  switch (type) {
    case 'ready':
      refreshProjects();
      refreshOnboard();
      break;
    case 'importStart':
      S.importing = true;
      $('#btnImport').disabled = true;
      $('#btnImportZip').disabled = true;
      renderImportBar({});
      progress('导入工程', 5, '复制文件中');
      toast('开始导入，别切后台…');
      break;
    case 'imported': {
      const p = data.project || {};
      clearImportBar();
      toast('导入完成：' + (p.name || '') + ' · ' + (p.scenes || 0) + ' 个场景');
      refreshProjects();
      break;
    }
    case 'assetsImported':
      toast(data.message || (data.ok ? '素材已导入' : '素材导入失败'), !data.ok);
      if (data.ok && ED.pid && data.project === ED.pid) {
        loadWorkspace(ED.pid, true);
      }
      break;
    case 'assetRenamed':
      toast(data.message || (data.ok ? '已改名' : '改名失败'), !data.ok);
      if (data.ok && !data.same && ED.pid && data.pid === ED.pid) {
        loadWorkspace(ED.pid, true);
      }
      break;
    case 'progress':
      if (S.importing) {
        renderImportBar(data);
      } else {
        progress(data.stage, data.percent, data.detail);
      }
      break;
    case 'prepared':
      S.build.id = data.buildId || '';
      S.build.state = 'prepared';
      S.build.path = data.path || '';
      S.build.apk = '';
      S.build.size = data.size || '';
      if (data.pkg) $('#fPkg').value = data.pkg;
      if (data.appName) $('#fAppName').value = data.appName;
      updatePrepareEnabled();
      // 原生层会给重复打包自动 +1 的号，界面跟着走，别让用户以为没变
      if (data.versionCode) $('#fVersionCode').value = data.versionCode;
      if (data.versionName) $('#fVersionName').value = data.versionName;
      enableBuild(true);
      showResult();
      savePrefs();
      // 版本被原生层抬过号就明说一句，不然玩家以为界面没刷新
      const code = String(data.versionCode || '');
      const bumped = code && code !== String(S.env.versionStartCode || 2601) && code !== String(S.lastPreparedCode || '');
      S.lastPreparedCode = code || S.lastPreparedCode;
      toast(bumped
        ? `已拼装 ${data.size || ''} · 版本抬到 ${data.versionName || code}`
        : '构建工程已拼装：' + (data.size || ''));
      switchTab('workbench');
      break;
    case 'error':
      toast(data.message || '出错了', true);
      progress('出错', 100, data.message || '');
      break;
    case 'install':
      toast(data.message || (data.ok ? '安装完成' : '安装失败'), !data.ok);
      break;
    case 'aiReply':
      handleAiReply(data);
      break;
    case 'buildSent':
      // 授权回来、原生层补发了 RUN_COMMAND：这里接着走"构建中"的界面状态
      toast(data.message || '已交给 Termux，构建中');
      progress('构建中', 20, '等待 Termux 回写日志');
      S.build.state = 'running';
      showResult();
      startPolling();
      break;
    case 'buildDenied':
      toast(data.message || 'Termux 没接住，复制命令手动跑', true);
      if (data.command) { S.build.command = data.command; call('copy', data.command); }
      progress('待命', 0, 'Termux 没接住');
      break;
    case 'icon':
      S.iconPath = data.path;
      $('#iconName').textContent = '已选：' + (data.path || '').split('/').pop();
      savePrefs();
      break;
    case 'keystore':
      S.ksPath = data.path || '';
      $('#ksName').textContent = data.name || (S.ksPath.split('/').pop());
      S.signMode = 'custom';
      setSignMode('custom');
      toast('已选签名文件：' + (data.name || '') + '；记得填别名和口令');
      break;
    case 'probeSent':
      break;
    case 'offlineReady':
      toast(data.message || '离线包释放完成', !data.ok);
      if (data.ok && data.command) {
        S.offline = Object.assign({}, S.offline || {}, { extracted: 1, command: data.command });
        copyOfflineCommand(data.command);
      }
      refreshOffline();
      break;
    case 'termuxInstall': {
      resetTermuxBtn();
      toast(data.message || 'Termux 安装结果未知', !data.ok);
      refreshEnv();
      if (typeof refreshOnboard === 'function') refreshOnboard();
      break;
    }
    default:
      break;
  }
};

/* ------------------------------------------------------------------ 启动 */

function init() {
  bindToastAction();
  bindConfirm();
  bindTabs();
  bindChrome();
  bindProjectList();
  bindStage();
  bindForm();
  bindEditor();
  bindBuild();
  bindEnv();
  bindOffline();
  bindAbout();
  bindGate();
  bindOnboard();
  bindGuide();
  bindAsk();
  bindBoot();
  bindRailCollapse();
  startAutoSave();

  $('#btnImport').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：导入需要原生层'); return; }
    call('pickProject');
  });
  $('#btnImportZip').addEventListener('click', () => {
    if (MOCK) { toast('演示模式：导入需要原生层'); return; }
    call('pickProjectZip');
  });
  $('#btnRefresh').addEventListener('click', () => {
    refreshEnv();
    refreshProjects();
    toast('已刷新');
  });

  // 新建项目
  $('#btnNew').addEventListener('click', () => {
    $('#newDlg').hidden = false;
    $('#newName').value = '';
    $('#newStarter').value = '';
    setTimeout(() => $('#newName').focus(), 80);
  });
  $('#newCancel').addEventListener('click', () => { $('#newDlg').hidden = true; });
  $('#newCancel2').addEventListener('click', () => { $('#newDlg').hidden = true; });
  $('#newOk').addEventListener('click', createProject);
  $('#newOk2').addEventListener('click', createProject);
  $('#newName').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') createProject();
  });

  refreshEnv();
  loadPrefs();
  refreshProjects();
  maybeShowGate();
  // 免责已经点过的话，门不会弹，环境体检得自己站出来
  if ($('#gate').hidden) setTimeout(maybeShowOnboard, 60);
  // 体检也不弹（老用户三样都齐）时，引导直接上场；不然新装的人打开就是一片空白
  setTimeout(() => {
    if (!$('#gate').hidden || !$('#onboard').hidden) return;
    // 先给启动选项目页（PS 那味儿），关掉再谈引导
    if (!S.prefs.bootSkip) showBootPick();
    else maybeShowGuide();
  }, 900);

  // 回到前台时刷新一次环境（用户可能刚授了权限）
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { S.wentHiddenAt = Date.now(); return; }
    refreshEnv();
    refreshOnboard();
    if (S.build.id) pollBuild();
    maybeWakePrompt();
  });

  // 转屏 / 系统栏变化后重新算一次，别让舞台被挤没了
  let fitTimer = null;
  window.addEventListener('resize', () => {
    clearTimeout(fitTimer);
    fitTimer = setTimeout(fitWidths, 150);
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
