// 在 WebView 里执行一段 JS，并把结果打出来
// 用法: node cdp-eval.js "document.title"
// 自动从 http://127.0.0.1:9222/json 挑第一个 page，不用手抄 page id
const expr = process.argv.slice(2).join(' ');

async function pickPage() {
  const r = await fetch('http://127.0.0.1:9222/json');
  const list = await r.json();
  const page = list.find((x) => x.type === 'page');
  if (!page) throw new Error('没有可用的 page，先 adb forward');
  return page.webSocketDebuggerUrl;
}

(async () => {
  const wsUrl = await pickPage();
  const sock = new WebSocket(wsUrl);
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
    const r = await rpc('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    const res = r.result && r.result.result;
    if (r.result && r.result.exceptionDetails) console.log('EXC', JSON.stringify(r.result.exceptionDetails).slice(0, 400));
    else console.log(typeof res.value === 'string' ? res.value : JSON.stringify(res.value));
    sock.close();
    process.exit(0);
  };
  sock.onerror = (e) => { console.error('ws err', e.message); process.exit(1); };
})();
