// Speed Force (C++): connects the toolbar page to the native program, and lets urls.js / formats.js load as plain scripts.
window.module = { exports: {} };
(function () {
  const pending = new Map(), handlers = {};
  let seq = 0;
  chrome.webview.addEventListener('message', e => {
    const m = e.data;
    if (m && m.id) { const f = pending.get(m.id); pending.delete(m.id); if (f) f(m.result); }
    else if (m && m.ch) (handlers[m.ch] || []).forEach(f => f(m.data));
  });
  window.vg = {
    cmd: (name, ...args) => new Promise(res => { const id = ++seq; pending.set(id, res); chrome.webview.postMessage({ id, cmd: name, args }); }),
    on: (ch, fn) => { (handlers[ch] = handlers[ch] || []).push(fn); },
  };
})();
