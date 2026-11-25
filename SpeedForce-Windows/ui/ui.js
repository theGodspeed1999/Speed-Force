// Speed Force toolbar and panels. Talks to the main process only through window.vg (see preload-ui.js).
'use strict';
const $ = id => document.getElementById(id);
const cmd = (...a) => window.vg.cmd(...a);

/** Small DOM builder: h('div', {class:'x', onclick: fn}, 'text', child...) */
function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'width') el.style.width = v;
    else if (k === 'bg') el.style.background = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) el.append(k.nodeType ? k : document.createTextNode(String(k)));
  return el;
}

let st = null;          // latest state from the main process
let settings = null;
let panel = '';
let picker = { state: 'hidden' };
let downloads = [];
let billboard = { entries: [], counts: {} };
let noticeTimer = null;

// ---------------------------------------------------------------- theme
function applyTheme() {
  document.documentElement.dataset.theme = ({ LIGHT: 'light', DARK: 'dark' })[settings && settings.theme] || 'system';
}

// ---------------------------------------------------------------- tabs
/** One-colour lightning bolt (the new-tab icon); takes the text colour so it fits light and dark. */
function boltIcon() {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('class', 'bolt');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', 'M13.5 1 4 13.5h6.2L8.5 23 20 9.5h-6.6z');
  p.setAttribute('fill', 'currentColor');
  s.append(p);
  return s;
}

function renderTabs() {
  if (!st) return;
  const box = $('tabs');
  box.replaceChildren();
  st.tabs.forEach((t, i) => {
    const tab = h('div', {
      class: 'tab' + (t.active ? ' active' : ''), draggable: 'true', title: t.title,
      onclick: () => cmd('selectTab', t.id),
      onauxclick: e => { if (e.button === 1) cmd('closeTab', t.id); },
      ondragstart: e => e.dataTransfer.setData('text/plain', String(t.id)),
      ondragover: e => { e.preventDefault(); tab.classList.add('drop'); },
      ondragleave: () => tab.classList.remove('drop'),
      ondrop: e => { e.preventDefault(); tab.classList.remove('drop'); cmd('moveTab', Number(e.dataTransfer.getData('text/plain')), i); },
    },
    t.loading ? h('div', { class: 'dot loading' })
      : !t.url ? boltIcon()
      : t.favicon ? h('img', { src: t.favicon, onerror: e => e.target.replaceWith(h('div', { class: 'dot' })) })
        : h('div', { class: 'dot' }),
    h('span', {}, t.title || 'New tab'),
    h('button', { title: 'Close tab', onclick: e => { e.stopPropagation(); cmd('closeTab', t.id); } }, '✕'));
    box.append(tab);
  });
  box.append(h('button', { id: 'new-tab', title: 'New tab (Ctrl+T)', onclick: () => cmd('newTab') }, '+'));
}

// ---------------------------------------------------------------- toolbar
function renderBar() {
  if (!st) return;
  const c = st.cur || {};
  $('back').disabled = !c.canBack;
  $('fwd').disabled = !c.canFwd;
  $('reload').textContent = c.loading ? '✕' : '⟳';
  if (document.activeElement !== $('addr')) $('addr').value = c.url || '';
  $('insecure').hidden = !c.insecure;
  const z = $('zoom');
  z.hidden = !c.zoom || c.zoom === 100;
  z.textContent = (c.zoom || 100) + '%';
  const sh = $('btn-shield');
  sh.classList.toggle('down', !!c.shieldsDown);
  const b = sh.querySelector('.badge');
  b.hidden = !c.blocked || c.shieldsDown || c.start;
  b.textContent = c.blocked > 99 ? '99+' : c.blocked;
  const star = $('btn-star');
  star.textContent = c.bookmarked ? '★' : '☆';
  star.classList.toggle('on', !!c.bookmarked);
  star.hidden = !!c.start;
  const v = $('videos');
  const n = (c.videos || 0) + (c.streams || 0);
  v.classList.toggle('off', !n);
  v.textContent = '⬇ Download' + (c.streams ? ` (${c.streams})` : '');
  // "search with" row
  const eng = $('engines');
  if (c.search && settings) {
    eng.hidden = false;
    eng.replaceChildren(h('span', { class: 'label' }, 'Search with'),
      ...settings.engines.map(name => h('button', { class: 'chip', onclick: () => cmd('searchWith', name) }, name)));
  } else eng.hidden = true;
  const active = downloads.filter(d => d.status === 'RUNNING' || d.status === 'QUEUED').length;
  const bd = $('btn-downloads').querySelector('.badge');
  bd.hidden = !active;
  bd.textContent = active;
  syncHeight();
}

function syncHeight() { cmd('topHeight', $('top').offsetHeight); $('panel').style.top = $('top').offsetHeight + 'px'; }
new ResizeObserver(syncHeight).observe($('top'));

$('w-close').onclick = () => cmd('win', 'close');
$('w-min').onclick = () => cmd('win', 'min');
$('w-max').onclick = () => cmd('win', 'max');
// empty parts of the tab strip move the window (a second way, in case the page cannot ask for it itself); double-click = maximise
$('strip').onmousedown = e => { if (e.button === 0 && (e.target === $('strip') || e.target === $('tabs'))) cmd('win', 'drag'); };
$('strip').ondblclick = e => { if (e.target === $('strip') || e.target === $('tabs')) cmd('win', 'max'); };
$('back').onclick = () => cmd('back');
$('fwd').onclick = () => cmd('forward');
$('reload').onclick = () => cmd('reload');
$('home').onclick = () => cmd('home');
$('videos').onclick = () => cmd('findVideos');
$('btn-star').onclick = () => cmd('toggleBookmark');
$('btn-shield').onclick = () => (panel === 'shields' ? closePanel() : openPanel('shields'));
$('btn-downloads').onclick = () => (panel === 'downloads' ? closePanel() : openPanel('downloads'));
$('btn-menu').onclick = () => cmd('menu');
$('zoom').onclick = () => cmd('zoom', 0);
$('new-tab') && ($('new-tab').onclick = () => cmd('newTab'));
$('tabs').ondblclick = e => { if (e.target === $('tabs')) cmd('newTab'); };

const addr = $('addr');
addr.onfocus = () => addr.select();
addr.onkeydown = e => {
  if (e.key === 'Enter') { cmd('go', addr.value); addr.blur(); }
  if (e.key === 'Escape') { addr.value = (st && st.cur && st.cur.url) || ''; addr.blur(); cmd('focusPage'); }
};
addr.onblur = () => renderBar();

// ---------------------------------------------------------------- find, notice
function openFind() {
  $('find').hidden = false;
  $('find-text').focus();
  $('find-text').select();
  syncHeight();
}
function closeFind() { $('find').hidden = true; $('find-count').textContent = ''; cmd('stopFind'); cmd('focusPage'); syncHeight(); }
$('find-text').oninput = () => cmd('find', $('find-text').value, true, true);
$('find-text').onkeydown = e => {
  if (e.key === 'Enter') cmd('find', $('find-text').value, !e.shiftKey, false);
  if (e.key === 'Escape') closeFind();
};
$('find-next').onclick = () => cmd('find', $('find-text').value, true, false);
$('find-prev').onclick = () => cmd('find', $('find-text').value, false, false);
$('find-close').onclick = closeFind;

function showNotice(n) {
  clearTimeout(noticeTimer);
  $('notice').hidden = false;
  $('notice-text').textContent = n.text;
  const a = $('notice-action');
  a.hidden = !n.action;
  if (n.action) { a.textContent = n.action.label || 'OK'; a.onclick = () => { cmd('noticeAction', n.action); hideNotice(); }; }
  noticeTimer = setTimeout(hideNotice, 12000);
  syncHeight();
}
function hideNotice() { $('notice').hidden = true; syncHeight(); }
$('notice-close').onclick = hideNotice;

// ---------------------------------------------------------------- panels
const TITLES = { shields: 'Shields', downloads: 'Downloads', history: 'History', bookmarks: 'Bookmarks', ads: 'Shields log', settings: 'Settings', picker: 'Download video' };

function openPanel(name) {
  panel = name;
  $('panel').hidden = false;
  $('panel').style.top = $('top').offsetHeight + 'px';
  $('panel-title').textContent = TITLES[name] || name;
  cmd('panel', 400);
  renderPanel();
}
function closePanel() {
  if (panel === 'picker') pickerClose();
  panel = '';
  $('panel').hidden = true;
  cmd('panel', 0);
  cmd('focusPage');
}
$('panel-close').onclick = closePanel;
document.addEventListener('keydown', e => { if (e.key === 'Escape' && panel) closePanel(); });

async function renderPanel() {
  const body = $('panel-body');
  const keep = body.scrollTop;
  if (panel === 'shields') body.replaceChildren(...shieldsView());
  else if (panel === 'downloads') body.replaceChildren(...downloadsView());
  else if (panel === 'picker') body.replaceChildren(...pickerView());
  else if (panel === 'ads') body.replaceChildren(...adsView());
  else if (panel === 'history') body.replaceChildren(...await historyView());
  else if (panel === 'bookmarks') body.replaceChildren(...await bookmarksView());
  else if (panel === 'settings') body.replaceChildren(...await settingsView());
  body.scrollTop = keep;
}

// ---- shields
function shieldSvg() {
  const d = document.createElement('div');
  d.innerHTML = '<svg viewBox="0 0 24 24" width="34" height="34"><path d="M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z" fill="currentColor"/></svg>';
  return d.firstChild;
}

function shieldsView() {
  const c = (st && st.cur) || {};
  const out = [];
  if (!c.host) {
    out.push(h('p', { class: 'hint' }, 'Open a website to see what Speed Force stopped on it.'));
  } else {
    out.push(h('div', { class: 'shield-head' + (c.shieldsDown ? ' down' : '') },
      shieldSvg(),
      h('div', {}, h('div', { class: 'big' }, c.host),
        h('div', { class: c.shieldsDown ? 'dn' : 'up' }, c.shieldsDown ? 'Shields are DOWN for this site' : 'Shields are UP for this site')),
      h('div', { class: 'sw' + (c.shieldsDown ? '' : ' on'), onclick: () => cmd('shieldsToggle') }, h('i'))));
    if (!c.shieldsDown) out.push(h('div', { class: 'counts' },
      h('div', {}, h('b', {}, c.blocked || 0), h('span', {}, 'Trackers & ads blocked')),
      h('div', {}, h('b', {}, c.hidden || 0), h('span', {}, 'Ads hidden'))));
  }
  const s = st && st.stats || { blocked: 0, bytes: 0 };
  out.push(h('div', { class: 'set' }, h('label', {}, 'Trackers & ads blocking'),
    ...[['BLOCK', 'Aggressively block trackers & ads', 'Ads never load (saves data, a few sites complain).'],
      ['HIDE', 'Block trackers & ads', 'Ads load invisibly, sites cannot tell. Popups go to the Shields log.'],
      ['OFF', 'Allow all trackers & ads', 'No filtering anywhere.']].map(([m, name, text]) =>
      h('label', { class: 'opt' }, h('input', { type: 'radio', name: 'adm', checked: settings && settings.adMode === m, onchange: async () => { settings = await cmd('setSetting', { adMode: m }); } }),
        h('div', {}, name, h('div', { class: 's' }, text))))));
  out.push(opt('Upgrade connections to HTTPS', 'Opens https:// even when a link says http://', settings && settings.httpsOnly, v => cmd('setSetting', { httpsOnly: v })));
  out.push(h('div', { class: 'set' }, h('div', { class: 's' }, `All time: ${s.blocked} blocked, about ${Math.round(s.bytes / 1e6)} MB saved.`)));
  return out;
}

const opt = (label, hint, checked, onchange) => h('label', { class: 'opt' },
  h('input', { type: 'checkbox', checked: !!checked, onchange: async e => { settings = await onchange(e.target.checked) || settings; } }),
  h('div', {}, label, hint ? h('div', { class: 's' }, hint) : null));

// ---- downloads
function downloadsView() {
  const out = [h('div', { class: 'toolbar' },
    h('button', { class: 'btn', onclick: () => cmd('openFolder') }, 'Videos folder'),
    h('button', { class: 'btn', onclick: () => cmd('openFilesFolder') }, 'Files folder'),
    h('button', { class: 'btn', onclick: () => { cmd('dl', 'clearFinished', 'f0'); cmd('dl', 'clearFinished', 'x'); } }, 'Clear finished'))];
  if (!downloads.length) out.push(h('p', { class: 'hint' }, 'Nothing downloaded yet. Videos: press the Download button on a page. Files: just click a link to the file.'));
  for (const d of downloads) {
    const acts = [];
    const act = (label, fn, primary) => acts.push(h('button', { class: 'btn' + (primary ? ' primary' : ''), onclick: fn }, label));
    if (d.status === 'RUNNING' || d.status === 'QUEUED') { act('Pause', () => cmd('dl', 'pause', d.id)); act('Cancel', () => cmd('dl', 'cancel', d.id)); }
    else if (d.status === 'PAUSED') { act('Resume', () => cmd('dl', 'resume', d.id), true); act('Cancel', () => cmd('dl', 'cancel', d.id)); }
    else if (d.status === 'DONE') { act('Open', () => cmd('openFile', d.id), true); act('Show in folder', () => cmd('showFile', d.id)); act('Remove from list', () => cmd('dl', 'remove', d.id)); }
    else { if (!/^f/.test(d.id)) act('Retry', () => cmd('dl', 'retry', d.id), true); act('Remove', () => cmd('dl', 'remove', d.id)); }
    const status = { QUEUED: 'Waiting', RUNNING: d.stage || 'Downloading', PAUSED: 'Paused', DONE: d.formatLabel === 'File' ? 'Saved to Files' : 'Saved', FAILED: 'Failed', CANCELED: 'Cancelled' }[d.status] || d.status;
    out.push(h('div', { class: 'item' + (d.status === 'DONE' ? ' done' : '') },
      h('div', { class: 't' }, d.title),
      h('div', { class: 's' }, [d.formatLabel, status, d.size, d.speed, d.eta && 'ETA ' + d.eta].filter(Boolean).join(' · ')),
      d.error ? h('div', { class: 'err' }, d.error) : null,
      d.status === 'RUNNING' || d.status === 'PAUSED' ? h('div', { class: 'bar' }, h('i', { width: Math.max(0, Math.min(100, d.percent || 0)) + '%' })) : null,
      h('div', { class: 'acts' }, ...acts)));
  }
  return out;
}

// ---- picker
function pickerView() {
  const p = picker;
  if (p.state === 'loading') return [h('div', { class: 'center' }, h('div', { class: 'spinner' }), h('div', {}, p.message), h('div', { class: 's hint' }, p.detail || ''),
    h('button', { class: 'btn', onclick: closePanel }, 'Cancel'))];
  if (p.state === 'failed') {
    return [h('div', { class: 'big' }, 'The engine could not read this page'), h('div', { class: 'err' }, p.message),
      ...(p.others && p.others.length ? [h('p', { class: 'hint' }, 'Captured streams — pick one to see its qualities, or download it directly:'),
        ...p.others.map(o => h('div', { class: 'item' }, h('div', { class: 's' }, o.label),
          h('div', { class: 'acts' }, h('button', { class: 'btn', onclick: () => pickerTry(o.url) }, 'Qualities'),
            h('button', { class: 'btn primary', onclick: () => pickerDirect(o.url) }, 'Download'))))] : [h('p', { class: 'hint' }, 'Press play on the video for a few seconds, then try again.')])];
  }
  if (p.state !== 'ready') return [h('p', { class: 'hint' }, 'Press the Download button on a page with a video.')];
  const dur = p.duration ? ` · ${Math.floor(p.duration / 60)}:${String(Math.round(p.duration % 60)).padStart(2, '0')}` : '';
  const out = [h('div', { class: 'big' }, p.title), h('div', { class: 's hint' }, 'From: ' + p.from + dur + ' · saved as MP4')];
  if (p.entries && p.entries.length > 1) {
    out.push(h('select', { onchange: e => pickerEntry(Number(e.target.value)) },
      ...p.entries.map((t, i) => h('option', { value: i, selected: i === p.entry }, `${i + 1}. ${t}`))));
  }
  p.options.forEach((o, i) => out.push(h('div', { class: 'q' + (i === p.defaultIndex ? ' default' : '') },
    h('div', {}, h('div', {}, o.label), h('div', { class: 's' }, o.detail)),
    h('button', { class: 'btn primary', onclick: () => pickerChoose(i) }, '⬇ Download'))));
  if (p.others && p.others.length) {
    out.push(h('p', { class: 'hint' }, 'Other videos found on this page:'));
    p.others.forEach(o => out.push(h('div', { class: 'item' }, h('div', { class: 's' }, o.label),
      h('div', { class: 'acts' }, h('button', { class: 'btn', onclick: () => pickerTry(o.url) }, 'Qualities'),
        h('button', { class: 'btn', onclick: () => pickerDirect(o.url) }, 'Download')))));
  }
  return out;
}

// ---- shields log (popups and ads caught)
function adsView() {
  const c = billboard.counts || {};
  const out = [h('div', { class: 'counts' },
    h('div', {}, h('b', {}, c.hidden || 0), h('span', {}, 'Ads hidden')),
    h('div', {}, h('b', {}, c.popups || 0), h('span', {}, 'Popups caught')),
    h('div', {}, h('b', {}, c.trackers || 0), h('span', {}, 'Trackers blocked'))),
  h('div', { class: 'toolbar' }, h('button', { class: 'btn', onclick: () => cmd('adClear') }, 'Clear all'))];
  if (!billboard.entries.length) out.push(h('p', { class: 'hint' }, 'Nothing caught yet.'));
  for (const e of billboard.entries) {
    out.push(h('div', { class: 'item' }, h('div', { class: 't' }, e.kind), h('div', { class: 's' }, e.url), h('div', { class: 's' }, 'on ' + e.page),
      h('div', { class: 'acts' }, h('button', { class: 'btn', onclick: () => cmd('adOpen', e.id) }, 'Open in tab'), h('button', { class: 'btn', onclick: () => cmd('adCopy', e.id) }, 'Copy'))));
  }
  return out;
}

// ---- history and bookmarks
async function historyView() {
  const list = await cmd('history');
  const out = [h('div', { class: 'toolbar' }, h('button', { class: 'btn', onclick: async () => { await cmd('clearHistory'); renderPanel(); } }, 'Clear history'))];
  if (!list.length) out.push(h('p', { class: 'hint' }, settings && settings.private ? 'Private mode is on: history is not saved.' : 'No history yet.'));
  list.slice(0, 300).forEach(x => out.push(h('div', { class: 'item' },
    h('div', { class: 't', onclick: () => { cmd('open', x.u, false); closePanel(); } }, x.t || x.u), h('div', { class: 's' }, x.u))));
  return out;
}

async function bookmarksView() {
  const list = await cmd('bookmarks');
  if (!list.length) return [h('p', { class: 'hint' }, 'No bookmarks yet. Press the star in the address bar.')];
  return list.map(x => h('div', { class: 'item' },
    h('div', { class: 't', onclick: () => { cmd('open', x.u, false); closePanel(); } }, x.t || x.u), h('div', { class: 's' }, x.u),
    h('div', { class: 'acts' }, h('button', { class: 'btn', onclick: () => cmd('open', x.u, true) }, 'Open in new tab'),
      h('button', { class: 'btn', onclick: async () => { await cmd('removeBookmark', x.u); renderPanel(); } }, 'Remove'))));
}

// ---- settings
async function settingsView() {
  settings = await cmd("settings");
  const S = settings;
  const set = async ch => { settings = await cmd('setSetting', ch); applyTheme(); };
  const version = h('span', { class: 's' }, 'checking…');
  cmd('engineVersion').then(v => { version.textContent = v; });
  const upd = h('div', { class: 's' });
  const home = h('input', { value: /^(speedforce:\/\/start\/|https:\/\/sf\.start\/?)$/.test(S.home) ? '' : S.home, placeholder: 'https://…' });
  return [
    h('div', { class: 'set' }, h('label', {}, 'Theme'),
      h('div', { class: 'flex' }, ...[['SYSTEM', 'System'], ['LIGHT', 'Light'], ['DARK', 'Dark']].map(([v, name]) =>
        h('button', { class: 'btn' + (S.theme === v ? ' primary' : ''), onclick: async () => { await set({ theme: v }); renderPanel(); } }, name)))),
    h('div', { class: 'set' }, h('label', {}, 'Download engine (yt-dlp)'), h('div', {}, 'Version: ', version),
      h('div', { class: 'flex' }, h('button', { class: 'btn', onclick: async () => { upd.textContent = 'Updating…'; upd.textContent = await cmd('updateEngine'); version.textContent = await cmd('engineVersion'); } }, 'Update engine')), upd),
    h('div', { class: 'set' }, h('label', {}, 'Where videos are saved'), h('div', { class: 'path' }, S.folderShown),
      h('div', { class: 'flex' }, h('button', { class: 'btn', onclick: async () => { settings = await cmd('chooseFolder'); renderPanel(); } }, 'Change folder')),
      h('div', { class: 's' }, 'Other files go to: ' + S.filesShown)),
    h('div', { class: 'set' }, h('label', {}, 'Default quality'),
      h('select', { onchange: e => set({ maxRes: Number(e.target.value) }) },
        ...[[0, 'Best available'], [2160, '4K'], [1440, '1440p'], [1080, '1080p'], [720, '720p'], [480, '480p'], [360, '360p']].map(([v, n]) => h('option', { value: v, selected: S.maxRes === v }, n)))),
    h('div', { class: 'set' }, h('label', {}, 'Downloads at the same time'),
      h('select', { onchange: e => set({ parallel: Number(e.target.value) }) }, ...[1, 2, 3].map(n => h('option', { value: n, selected: S.parallel === n }, n)))),
    h('div', { class: 'set' }, h('label', {}, 'Ads and trackers'),
      ...[['BLOCK', 'Aggressively block trackers & ads'], ['HIDE', 'Block trackers & ads'], ['OFF', 'Allow all trackers & ads']].map(([m, name]) =>
        h('label', { class: 'opt' }, h('input', { type: 'radio', name: 'adm2', checked: S.adMode === m, onchange: () => set({ adMode: m }) }), name))),
    h('div', { class: 'set' }, h('label', {}, 'Browser'),
      opt('Upgrade connections to HTTPS', null, S.httpsOnly, v => cmd('setSetting', { httpsOnly: v })),
      opt('Reopen my tabs when the app starts', null, S.restoreTabs, v => cmd('setSetting', { restoreTabs: v })),
      opt('Private mode (no history, no saved tabs)', null, S.private, v => cmd('setSetting', { private: v })),
      h('div', { class: 's' }, 'Search engine'),
      h('select', { onchange: e => set({ engine: e.target.value }) }, ...S.engines.map(n => h('option', { value: n, selected: S.engine === n }, n))),
      h('div', { class: 's' }, 'Home page (empty = Speed Force start page)'),
      h('div', { class: 'flex' }, home, h('button', { class: 'btn', onclick: () => set({ home: home.value.trim() || 'https://sf.start/' }) }, 'Save'))),
    h('div', { class: 'set' }, h('label', {}, 'Privacy'),
      h('button', { class: 'btn', onclick: async e => { e.target.textContent = await cmd('clearSiteData'); } }, 'Clear cookies and site data')),
    h('div', { class: 'set' }, h('label', {}, 'About'), h('div', { class: 's' }, `Speed Force ${S.version} (${S.built})`)),
  ];
}

// ---------------------------------------------------------------- finding a video's qualities
let pickList = [];
let pickToken = 0;
let ready = null;
const UA = navigator.userAgent;

function setPicker(p) {
  picker = p;
  if (p.state !== 'hidden' && panel !== 'picker') openPanel('picker');
  else if (panel === 'picker') renderPanel();
}
function pickerClose() { pickToken++; picker = { state: 'hidden' }; }

/** Every address we can hand to yt-dlp: the page, the player frame, the video file, captured streams. */
function candidates(req) {
  const list = [], added = new Set();
  const add = (u, label, referer, stream) => {
    if (!U.isHttp(u) || added.has(U.dedupeKey(u))) return;
    added.add(U.dedupeKey(u));
    list.push({ url: u, label, stream: !!stream, ctx: {
      referer: referer || null, origin: stream && referer ? U.origin(referer) : null, userAgent: UA,
      cookieUrls: [u, req.pageUrl].filter(U.isHttp),  // only the address itself and the page you are on: a page cannot ask for other sites' cookies
    } });
  };
  add(req.pageUrl, 'This page', null);
  if (req.frameUrl && req.frameUrl !== req.pageUrl) add(req.frameUrl, 'Video player frame', req.pageUrl);
  add(req.directUrl, 'Video file', req.frameUrl || req.pageUrl, true);
  // Playlists (HLS/DASH) first: they list every quality. Little preview clips last.
  (req.streams || []).slice().sort((a, b) =>
    (U.looksLikePreviewClip(a.url) - U.looksLikePreviewClip(b.url)) ||
    ((a.kind === 'HLS' || a.kind === 'DASH' ? 0 : 1) - (b.kind === 'HLS' || b.kind === 'DASH' ? 0 : 1)) ||
    ((U.looksLikeMasterPlaylist(a.url) ? 0 : 1) - (U.looksLikeMasterPlaylist(b.url) ? 0 : 1)) ||
    a.at - b.at,
  ).slice(0, 12).forEach(s => add(s.url, `Captured ${s.kind}: ${U.shortName(s.url)}`, s.referer || req.pageUrl, true));
  return list;
}

function othersOf(c) { return pickList.filter(x => x.stream && (!c || x.url !== c.url)).map(x => ({ url: x.url, label: x.label })); }

function openPicker(req) {
  pickList = candidates(req);
  if (!pickList.length) return setPicker({ state: 'failed', message: 'Nothing to download on this page.', others: [] });
  tryInOrder(pickList);
}

async function tryInOrder(order) {
  const token = ++pickToken;
  let lastError = '';
  for (let i = 0; i < order.length; i++) {
    const c = order[i];
    setPicker({ state: 'loading', message: 'Looking for video qualities…', detail: c.label + (order.length > 1 ? `  (${i + 1}/${order.length})` : '') });
    // Most video servers want to know which page you came from, some (Twitter/X) refuse it: try both
    const variants = c.stream && (c.ctx.referer || c.ctx.origin) ? [c, { ...c, ctx: { ...c.ctx, referer: null, origin: null } }] : [c];
    for (const v of variants) {
      const r = await cmd('ytInfo', v.url, v.ctx);
      if (token !== pickToken) return;
      if (r && r.ok) {
        try {
          const infos = F.parseInfo(r.out);
          const first = infos.findIndex(x => x.formats.length);
          if (first < 0) { lastError = 'No video formats found'; continue; }
          return showInfo(v, infos, first);
        } catch (e) { lastError = 'Could not read the video information'; }
      } else lastError = F.shortError(r && r.error);
    }
  }
  setPicker({ state: 'failed', message: lastError, others: othersOf(null) });
}

function showInfo(c, infos, entry) {
  const maxRes = settings ? settings.maxRes : 1080;
  const options = F.buildOptions(infos[entry], maxRes);
  ready = { c, infos, entry, options };
  setPicker({
    state: 'ready', title: infos[entry].title, from: c.label, duration: infos[entry].duration,
    entries: infos.map(x => x.title), entry, options, defaultIndex: F.defaultIndex(options, maxRes), others: othersOf(c),
  });
}
function pickerEntry(i) { if (ready && ready.infos[i]) showInfo(ready.c, ready.infos, i); }
function pickerTry(url) { const c = pickList.find(x => x.url === url); if (c) tryInOrder([c]); }
function pickerChoose(i) {
  if (!ready || !ready.options[i]) return;
  const info = ready.infos[ready.entry], o = ready.options[i];
  cmd('ytEnqueue', { url: ready.c.url, title: info.title, format: o.format, label: o.label, audioOnly: !!o.audioOnly, entry: ready.entry + 1, ctx: ready.c.ctx });
  pickerClose();
}
function pickerDirect(url) {
  const c = pickList.find(x => x.url === url);
  if (!c) return;
  const title = U.fileName(c.url).replace(/\.[^.]+$/, '') || 'video';
  cmd('ytEnqueue', { url: c.url, title, format: 'bv*+ba/b', label: 'Best', audioOnly: false, entry: 1, ctx: c.ctx });
  pickerClose();
}

// ---------------------------------------------------------------- messages from the main process
window.vg.on('state', s => { st = s; renderTabs(); renderBar(); if (panel === 'shields') renderPanel(); });
window.vg.on('notice', showNotice);
window.vg.on('find-open', openFind);
window.vg.on('find-result', r => { $('find-count').textContent = r && r.matches != null ? `${r.activeMatchOrdinal || 0}/${r.matches}` : ''; });
window.vg.on('focus-address', () => { addr.focus(); addr.select(); });
window.vg.on('panel', name => { if (name === 'picker' || name !== panel) openPanel(name); else closePanel(); });
window.vg.on('pick-request', req => openPicker(req));
window.vg.on('downloads', d => { downloads = d; renderBar(); if (panel === 'downloads') renderPanel(); });
window.vg.on('billboard', b => { billboard = b; if (panel === 'ads') renderPanel(); });
window.vg.on('settings', s => { settings = s; applyTheme(); if (panel === 'settings') renderPanel(); });

(async () => {
  settings = await cmd("settings");
  applyTheme();
  downloads = await cmd('downloads');
  billboard = await cmd('billboard');
  renderBar();
  syncHeight();
})();
