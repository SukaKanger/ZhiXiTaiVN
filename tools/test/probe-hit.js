// 通过 CDP 问 WebView：屏幕物理点 (px,py) 落到了哪个元素
// 用法: node probe-hit.js 1550 806 1887 866
// 依赖: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
const args = process.argv.slice(2).map(Number);

async function pickPage() {
  const r = await fetch('http://127.0.0.1:9222/json');
  const list = await r.json();
  const page = list.find((x) => x.type === 'page');
  if (!page) throw new Error('没有可用的 page');
  const meta = JSON.parse(page.description);
  return { ws: page.webSocketDebuggerUrl, meta };
}

(async () => {
  const { ws, meta } = await pickPage();
  const sock = new WebSocket(ws);
  let id = 0;
  const pending = new Map();
  const rpc = (method, params) => new Promise((res) => {
    const mid = ++id;
    pending.set(mid, res);
    sock.send(JSON.stringify({ id: mid, method, params: params || {} }));
  });
  sock.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  };
  sock.onopen = async () => {
    const env = await rpc('Runtime.evaluate', {
      expression: 'JSON.stringify({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})',
      returnByValue: true,
    });
    const g = JSON.parse(env.result.result.value);
    const scale = g.w ? meta.width / g.w : 1;
    console.log('webview', meta, 'viewport', g, 'scale', scale);
    for (let i = 0; i < args.length; i += 2) {
      const px = args[i], py = args[i + 1];
      const cx = (px - meta.screenX) / scale;
      const cy = (py - meta.screenY) / scale;
      const r = await rpc('Runtime.evaluate', {
        expression: `(() => { const el = document.elementFromPoint(${cx}, ${cy}); return JSON.stringify({tag: el && el.tagName, id: el && el.id, cls: el && el.className, text: (el && el.textContent || '').trim().slice(0, 24), cx: ${cx}, cy: ${cy}}); })()`,
        returnByValue: true,
      });
      console.log(px, py, '->', r.result.result.value);
    }
    sock.close();
    process.exit(0);
  };
  sock.onerror = (e) => { console.error('ws err', e.message); process.exit(1); };
})();
