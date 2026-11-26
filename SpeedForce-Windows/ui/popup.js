// Speed Force drop-down windows. The native side shows this page on top of the web page, under the button
// that was pressed. Ids start at 1000000 so the program sends the answers back here and not to the toolbar.
'use strict';
const pending = new Map();
let seq = 1000000;
const cmd = (name, ...args) => new Promise(res => { const id = ++seq; pending.set(id, res); chrome.webview.postMessage({ id, cmd: name, args }); });
chrome.webview.addEventListener('message', e => {
  const m = e.data;
  if (m && m.id) { const f = pending.get(m.id); pending.delete(m.id); if (f) f(m.result); }
  else if (m && m.ch === 'popup') open(m.data);
});

let d = null;        // what the program says about the current tab
let sub = '';        // open sub-menu
let advanced = true; // Shields: advanced options shown
let openPick = '';   // Shields: open drop-down
let openRow = '';    // site information: open row
let cookies = null;

function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) el.append(k.nodeType ? k : document.createTextNode(String(k)));
  return el;
}

// 24x24 line icons
const ICONS = {
  tab: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM12 8v8M8 12h8',
  window: 'M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 8h18M12 11v6M9 14h6',
  incognito: 'M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM6 11h12M10 15a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM18 15a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM10 15h4',
  reopen: 'M4 12a8 8 0 1 0 3-6.2M4 3v5h5',
  video: 'M3 6h13v12H3zM16 10l5-3v10l-5-3',
  key: 'M14.5 9.5a4.5 4.500 0 1 1-1.3-3.2 4.5 4.5 0 0 1 1.3 3.2zM13.5 12.5 21 20M17 16l-2 2M19.500 18.500l-2 2',
  history: 'M4 12a8 8 0 1 0 3-6.2M4 3v5h5M12 8v5l3 2',
  book: 'M6 3h12v18l-6-4-6 4z',
  download: 'M12 4v11M7 11l5 5 5-5M5 20h14',
  puzzle: 'M9 4h4v2a2 2 0 1 0 4 0V4h3v6h-2a2 2 0 1 0 0 4h2v6h-6v-2a2 2 0 1 0-4 0v2H4v-6h2a2 2 0 1 0 0-4H4V4z',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v5M14 11v5',
  zoom: 'M17 11a6 6 0 1 1-12 0 6 6 0 0 1 12 0zM15.500 15.500 21 21M8.500 11h5M11 8.500v5',
  full: 'M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5',
  print: 'M7 9V3h10v6M7 18H4v-8h16v8h-3M7 14h10v7H7z',
  find: 'M6 3h9l4 4v14H6zM15.500 15.500 18 18M15 13a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  save: 'M6 3h9l4 4v14H6zM12 10v7M9 14l3 3 3-3',
  tools: 'M14 4a5 5 0 0 0-4.500 7L4 16.500 7.500 20l5.500-5.500A5 5 0 0 0 20 8l-3 3-3-3 3-3a5 5 0 0 0-3-1z',
  lock: 'M6 11h12v9H6zM9 11V8a3 3 0 0 1 6 0v3',
  gear: 'M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM12 3l1.500 2.500 3-.500 1 2.800 2.800 1.200-.800 3 .800 3-2.800 1.200-1 2.800-3-.500L12 21l-1.500-2.500-3 .500-1-2.800-2.800-1.200.800-3-.800-3 2.800-1.200 1-2.800 3 .500z',
  exit: 'M6 6l12 12M18 6 6 18',
  chev: 'M9 6l6 6-6 6',
  back: 'M15 6l-6 6 6 6',
  down: 'M6 9l6 6 6-6',
  up: 'M6 15l6-6 6 6',
  cookie: 'M12 3a9 9 0 1 0 9 9 4 4 0 0 1-4-4 4 4 0 0 1-5-5zM8.500 10h.010M9.500 15h.010M14.500 14h.010',
  external: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6',
  hand: 'M8 12V6a1.500 1.500 0 0 1 3 0v5V4.500a1.500 1.500 0 0 1 3 0V11V6a1.500 1.500 0 0 1 3 0v8c0 4-2 7-6 7-3 0-4.500-2-6.500-5.500-1-2 1-3 2-1.500z',
  code: 'M8 8l-4 4 4 4M16 8l4 4-4 4M13 6l-2 12',
  finger: 'M7 5a8 8 0 0 1 13 6v3M4 10a8 8 0 0 1 1-2M8 20c-1-2-2-5-2-9a6 6 0 0 1 12 0c0 3 0 6 1 8M12 11c0 4 .500 7 2 10M9 11a3 3 0 0 1 6 0c0 3 .200 5 .800 7',
  broom: 'M19 4l-7 8M9 11l5 4-3 6-8-5zM6 18l2-2',
  warn: 'M12 4 2.500 20h19zM12 10v5M12 17.500h.010',
};
function icon(name, cls) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  if (cls) s.setAttribute('class', cls);
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', ICONS[name] || '');
  s.append(p);
  return s;
}

const WIDTH = { menu: 328, shields: 402, site: 322 };
let wanted = false; // a fresh opening: the program shows the window when it gets the size
function resize() {
  // a timer, not requestAnimationFrame: a hidden view draws no frames
  const fresh = wanted;
  wanted = false;
  setTimeout(() => cmd('popupSize', WIDTH[d.kind], document.getElementById('root').offsetHeight, fresh), 30);
}
async function refresh() { d = await cmd('popupData'); if (d) render(); }
function open(data) {
  d = data; sub = ''; openPick = ''; openRow = ''; cookies = null; advanced = true; wanted = true;
  document.documentElement.dataset.theme = ({ LIGHT: 'light', DARK: 'dark' })[d.theme] || 'system';
  render();
}
window.addEventListener('keydown', e => { if (e.key === 'Escape') cmd('popupClose'); });
window.addEventListener('contextmenu', e => e.preventDefault());

// ---------------------------------------------------------------- main menu (the numbers are handled by menuAction in main.cpp)
const act = n => () => cmd('menuAct', n);
function item(ic, text, onclick, opt = {}) {
  return h('button', { class: 'mi' + (opt.cls ? ' ' + opt.cls : ''), onclick, disabled: opt.off },
    ic ? icon(ic) : h('span', { class: 'gap' }), h('span', { class: 't' }, text),
    opt.key && h('span', { class: 'k' }, opt.key), opt.sub && icon('chev', 'chev'));
}
const into = (ic, text, name) => item(ic, text, () => { sub = name; render(); }, { sub: true });
function menuView() {
  const SUBS = {
    passwords: ['Passwords and autofill', () => [item(null, d.passwords ? 'Turn off saving passwords' : 'Save passwords in this browser', act(126), { off: d.private })]],
    history: ['History', () => [item('history', 'Show history', act(109), { key: 'Ctrl+H' }), item('reopen', 'Reopen closed tab', act(102), { key: 'Ctrl+Shift+T', off: !d.closed })]],
    bookmarks: ['Bookmarks and lists', () => [item('book', 'Show bookmarks', act(110), { key: 'Ctrl+D' })]],
    extensions: ['Extensions', () => [item('puzzle', 'Add extension (unpacked folder)…', act(125), { off: d.private }), ...d.extensions.map((n, i) => item('exit', 'Remove ' + n, act(200 + i)))]],
    find: ['Find and edit', () => [item('find', 'Find in page', act(104), { key: 'Ctrl+F' }), item(null, 'Cut', act(118), { key: 'Ctrl+X' }), item(null, 'Copy', act(119), { key: 'Ctrl+C' }), item(null, 'Paste', act(120), { key: 'Ctrl+V' })]],
    save: ['Save and share', () => [item(null, 'Copy page address', act(121))]],
    more: ['More tools', () => [item('video', 'Find videos on this page', act(103)), item(null, 'Shields log', act(111)), item('code', 'Developer tools', act(122), { key: 'F12' }), item(null, d.privateMode ? 'Turn off private mode (no history)' : 'Private mode (no history)', act(113))]],
  };
  if (sub) return h('div', { class: 'menu' }, item('back', SUBS[sub][0], () => { sub = ''; render(); }, { cls: 'back' }), h('hr'), SUBS[sub][1]());
  const zoom = n => async () => { await cmd('menuAct', n); refresh(); };
  return h('div', { class: 'menu' },
    item('tab', 'New tab', act(101), { key: 'Ctrl+T' }),
    item('window', 'New window', act(123), { key: 'Ctrl+N' }),
    item('incognito', 'New private window', act(124), { key: 'Ctrl+Shift+N' }),
    h('hr'),
    into('key', 'Passwords and autofill', 'passwords'),
    into('history', 'History', 'history'),
    into('book', 'Bookmarks and lists', 'bookmarks'),
    item('download', 'Downloads', act(108), { key: 'Ctrl+J' }),
    item('find', 'Search by image (Lens)', act(127)),
    into('puzzle', 'Extensions', 'extensions'),
    item('trash', 'Delete browsing data…', act(116), { key: 'Ctrl+Shift+Del' }),
    h('hr'),
    h('div', { class: 'zoom' }, icon('zoom'), h('span', { class: 't' }, 'Zoom'),
      h('button', { title: 'Zoom out', onclick: zoom(106) }, '−'),
      h('button', { class: 'pct', title: 'Actual size', onclick: zoom(107) }, d.zoom + '%'),
      h('button', { title: 'Zoom in', onclick: zoom(105) }, '+'),
      h('span', { class: 'sep' }),
      h('button', { title: 'Full screen (F11)', onclick: act(117) }, icon('full'))),
    h('hr'),
    item('print', 'Print…', act(114), { key: 'Ctrl+P' }),
    into('find', 'Find and edit', 'find'),
    into('save', 'Save and share', 'save'),
    into('tools', 'More tools', 'more'),
    h('hr'),
    item('gear', 'Settings', act(112)),
    item('exit', 'Exit', act(115)));
}

// ---------------------------------------------------------------- site information
function siteView() {
  const toggle = name => async () => {
    openRow = openRow === name ? '' : name;
    render();
    if (openRow === 'cookies') { cookies = await cmd('siteCookies'); render(); }
  };
  const row = (ic, text, onclick, end) => h('button', { class: 'mi', onclick }, icon(ic), h('span', { class: 't' }, text), icon(end, 'chev'));
  const out = [h('div', { class: 'head' }, h('span', { class: 't' }, d.start ? 'Speed Force' : d.host), h('button', { class: 'x', title: 'Close', onclick: () => cmd('popupClose') }, icon('exit')))];
  if (d.start) out.push(h('div', { class: 'detail' }, 'This is the Speed Force start page. It is stored on this PC.'));
  else {
    out.push(row(d.secure ? 'lock' : 'warn', d.secure ? 'Connection is secure' : 'Connection is not secure', toggle('conn'), 'chev'));
    if (openRow === 'conn') out.push(h('div', { class: 'detail' }, d.secure
      ? 'What you send to this site is encrypted (https). That does not prove the site itself is honest.'
      : 'This page uses http. Others on the network can read or change it, so do not enter passwords or card numbers.'));
    out.push(row('cookie', 'Cookies and site data', toggle('cookies'), 'chev'));
    if (openRow === 'cookies') out.push(h('div', { class: 'detail' },
      cookies == null ? 'Counting…' : `${cookies} cookie${cookies === 1 ? '' : 's'} saved for this page.`,
      cookies > 0 && h('div', {}, h('button', { class: 'btn', onclick: async () => { cookies = await cmd('siteClearCookies'); render(); } }, 'Delete cookies'))));
  }
  out.push(row('gear', 'Site settings', () => { cmd('popupSwitch', 'shields'); }, 'external'));
  return h('div', { class: 'site' }, out);
}

// ---------------------------------------------------------------- Shields
function sw(on, onclick, big) { return h('div', { class: 'sw' + (on ? ' on' : '') + (big ? ' big' : ''), role: 'switch', 'aria-checked': String(!!on), onclick }, h('i')); }
function picker(name, ic, value, options, choose) {
  const cur = options.find(o => o[0] === value) || options[0];
  return h('div', { class: 'row' }, icon(ic),
    h('div', { class: 'pick' },
      h('button', { onclick: () => { openPick = openPick === name ? '' : name; render(); } }, h('span', { class: 't' }, cur[1]), icon('down')),
      openPick === name && h('div', { class: 'opts' }, options.map(([v, label]) =>
        h('button', { class: v === value ? 'sel' : '', onclick: async () => { openPick = ''; await choose(v); refresh(); } }, label)))));
}
const set = change => cmd('setSetting', change);
const siteFlag = (key, on) => async () => { await cmd('siteList', key, on); refresh(); };
function switchRow(ic, text, on, onclick) { return h('div', { class: 'row' }, icon(ic), h('span', { class: 't' }, text), sw(on, onclick)); }
function shieldsView() {
  const up = !d.shieldsDown;
  const out = [
    h('div', { class: 'sh-head' },
      d.favicon ? h('img', { src: d.favicon, alt: '' }) : h('span', { class: 'ph' }),
      h('div', { class: 't' }, h('div', { class: 'host' }, d.start ? 'All sites' : d.host),
        d.start ? h('div', { class: 'sub' }, 'Settings for every site') : h('div', { class: 'sub' }, 'Shields ', h('b', {}, up ? 'up' : 'down'), ' for this site')),
      !d.start && sw(up, async () => { await cmd('shieldsToggle'); refresh(); }, true)),
  ];
  if (!d.start) out.push(h('div', { class: 'count' }, h('b', {}, d.blocked + d.hidden), 'trackers, ads, and more blocked'));
  out.push(h('div', { class: 'band' },
    h('button', { class: 'adv', onclick: () => { advanced = !advanced; render(); } }, icon('gear'), h('span', { class: 't' }, 'Advanced options'), icon(advanced ? 'up' : 'down')),
    advanced && [
      picker('ads', 'hand', d.adMode, [['BLOCK', 'Aggressively block trackers & ads'], ['HIDE', 'Block trackers & ads'], ['OFF', 'Allow all trackers & ads']], v => set({ adMode: v })),
      picker('https', 'lock', d.httpsOnly, [[true, 'Upgrade connections to HTTPS'], [false, 'Do not upgrade connections']], v => set({ httpsOnly: v })),
      !d.start && switchRow('code', 'Block scripts', d.scriptsOff, siteFlag('scriptsOff', !d.scriptsOff)),
      switchRow('finger', 'Block fingerprinting', d.antiFinger, async () => { await set({ antiFinger: !d.antiFinger }); refresh(); }),
      picker('cookies', 'cookie', d.block3p, [[true, 'Block third-party cookies'], [false, 'Allow all cookies']], v => set({ block3p: v })),
      !d.start && switchRow('broom', 'Forget me when I close this site', d.forget, siteFlag('forgetSites', !d.forget)),
    ]));
  out.push(h('div', { class: 'two' }, h('button', { onclick: act(111) }, 'Shields log'), h('button', { onclick: act(112) }, 'Global Settings')));
  out.push(h('div', { class: 'foot' }, 'If this site seems broken, try Shields down. This turns Speed Force’s protection off for this site.'));
  return out;
}

function render() {
  const view = d.kind === 'menu' ? menuView() : d.kind === 'site' ? siteView() : shieldsView();
  document.getElementById('root').replaceChildren(...[view].flat());
  resize();
}
