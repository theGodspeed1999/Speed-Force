// "Why didn't this page load?" The same file is used by the Windows and Android apps.
// The country check runs only when the button is pressed, and sends only the site's name (no path, no cookies)
// to Globalping, a free public service that tries the site from its own test servers.
'use strict';

const LOCAL = {
  dns: ['The address could not be found', 'Your connection could not turn this name into a server address. The site may be gone, the name may be mistyped, or your internet provider may be hiding it.'],
  connect: ['The connection was cut or refused', 'Your device reached out, but the connection was closed. The site may be down, or something between you and the site may be stopping it.'],
  timeout: ['The site did not answer in time', 'The server is very slow, down, or your connection to it is being dropped.'],
  offline: ['You appear to be offline', 'This device has no working internet connection.'],
  cert: ['Security certificate problem', 'The site did not prove who it is. Do not enter passwords or payment details.'],
  refused: ['The site refused this request', 'The server answered "access denied". It may be blocking your browser, your internet address or your country.'],
  server: ['The website has a problem', 'The site answered with a server error. This is usually on the website’s side.'],
  check: ['Checking a page', 'Use the button below to see whether this site opens from other places.'],
};

/** What kind of failure an HTTP status is. */
function localKind(kind, status) {
  if (status === 401 || status === 403 || status === 429 || status === 451) return 'refused';
  if (status >= 500) return 'server';
  return LOCAL[kind] ? kind : 'check';
}

/** One test server's answer: 'ok', 'refused' or 'fail'. */
function rate(row) {
  if (!row || row.state !== 'finished' || !row.code) return 'fail';
  if (row.code === 401 || row.code === 403 || row.code === 429 || row.code === 451) return 'refused';
  return row.code >= 500 ? 'fail' : 'ok';
}

/**
 * Puts what this device saw next to what the test servers saw.
 * local: one of the LOCAL keys. rows: [{country, state, code}]. home: this device's country code.
 */
function conclude(local, rows, home) {
  if (local === 'offline') return ['Your internet is off', 'Check your Wi-Fi or network cable, then try again.'];
  if (!rows.length) return ['No answer from the test servers', 'The country check did not work this time. Try again in a minute.'];
  const marks = rows.map(r => ({ country: r.country, mark: rate(r) }));
  const here = marks.filter(m => m.country === home);
  const away = marks.filter(m => m.country !== home);
  const ok = list => list.some(m => m.mark === 'ok');
  if (local === 'cert') return ['Certificate problem', 'Your device rejected the site’s certificate. If other devices open it without a warning, check this device’s date and time and any network filter.'];
  if (!ok(marks)) {
    if (marks.every(m => m.mark === 'refused')) return ['Can’t tell: the site refuses automatic checks', 'Every test server was refused. That is common for sites with bot protection. Try the address in another browser.'];
    return ['The website is probably down', 'It did not open from any test server either. This is on the website’s side; try again later.'];
  }
  if (here.length && !ok(here) && ok(away)) return ['It may be blocked in your country', 'It opens from other countries but not from the test server in ' + home + '.'];
  if (local === 'server') return ['The website is having trouble', 'It opens from at least one test server, but gave you a server error. Try again later.'];
  if (local === 'refused') return ['The site may be refusing your browser or connection', 'Test servers can open it, but you were refused. Try the same address in another browser: if it works there, this browser is being refused; if not, your internet address may be.'];
  if (local === 'dns' || local === 'connect' || local === 'timeout') {
    return here.length
      ? ['Your internet provider or network may be blocking it', 'The test server in ' + home + ' can open the site, but your device cannot. Another network (for example mobile data) will show if it is your provider.']
      : ['It works elsewhere; the problem may be your network', 'No test server in your country answered, but the site opens from other countries.'];
  }
  return ['The site is up', 'It opens from the test servers. If it still fails for you, try another network or browser.'];
}

const COUNTRIES = { IN: 'India', US: 'United States', GB: 'United Kingdom', DE: 'Germany', SG: 'Singapore', AE: 'United Arab Emirates', AU: 'Australia', CA: 'Canada', FR: 'France', JP: 'Japan', BR: 'Brazil', RU: 'Russia', CN: 'China', PK: 'Pakistan', BD: 'Bangladesh', LK: 'Sri Lanka', ID: 'Indonesia', TR: 'Turkey', SA: 'Saudi Arabia' };

function guessCountry() {
  const zone = (Intl.DateTimeFormat().resolvedOptions().timeZone || '');
  const zones = { 'Asia/Calcutta': 'IN', 'Asia/Kolkata': 'IN', 'Asia/Dubai': 'AE', 'Asia/Singapore': 'SG', 'Asia/Karachi': 'PK', 'Asia/Dhaka': 'BD', 'Asia/Colombo': 'LK', 'Europe/London': 'GB', 'Europe/Berlin': 'DE', 'Europe/Paris': 'FR', 'Asia/Tokyo': 'JP' };
  if (zones[zone]) return zones[zone];
  const m = /-([A-Z]{2})\b/.exec((typeof navigator !== 'undefined' && navigator.language) || '');
  return m && COUNTRIES[m[1]] ? m[1] : 'US';
}

async function askTestServers(host, home) {
  const places = [...new Set([home, 'IN', 'US', 'GB', 'DE', 'SG'])].slice(0, 6);
  const api = 'https://api.globalping.io/v1/measurements';
  const made = await fetch(api, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'omit', referrerPolicy: 'no-referrer',
    body: JSON.stringify({ type: 'http', target: host, locations: places.map(c => ({ country: c, limit: 1 })), measurementOptions: { protocol: 'HTTPS', request: { method: 'HEAD' } } }),
  });
  if (made.status === 429) throw new Error('The free service’s limit is used up for now. Try again later.');
  if (!made.ok) throw new Error('The test service did not accept the check (' + made.status + ').');
  const id = (await made.json()).id;
  for (let i = 0; i < 20; i++) {
    await new Promise(r => setTimeout(r, 1500));
    const now = await (await fetch(api + '/' + encodeURIComponent(id), { credentials: 'omit', referrerPolicy: 'no-referrer' })).json();
    if (now.status !== 'in-progress') {
      return now.results.map(x => ({ country: x.probe.country, city: x.probe.city, state: x.result.status, code: x.result.statusCode }));
    }
  }
  throw new Error('The test servers took too long.');
}

async function online() {
  const tries = ['https://www.gstatic.com/generate_204', 'https://cp.cloudflare.com/generate_204']
    .map(u => fetch(u, { mode: 'no-cors', cache: 'no-store', credentials: 'omit' }));
  try { await Promise.any(tries); return true; } catch (e) { return false; }
}

function start(info) {
  const $ = id => document.getElementById(id);
  let target;
  try { target = new URL(info.url); } catch (e) { target = null; }
  if (!target || !/^https?:$/.test(target.protocol)) { $('title').textContent = 'There is no web page to check'; $('check').hidden = true; $('retry').hidden = true; return; }
  let local = localKind(info.kind, Number(info.status) || 0);
  const show = () => { $('title').textContent = LOCAL[local][0]; $('why').textContent = LOCAL[local][1]; };
  show();
  $('host').textContent = target.hostname;
  online().then(up => { if (!up) { local = 'offline'; show(); } });
  const home = guessCountry();
  for (const [code, name] of Object.entries(COUNTRIES)) {
    const o = document.createElement('option');
    o.value = code; o.textContent = name; o.selected = code === home;
    $('home').append(o);
  }
  $('retry').addEventListener('click', () => { location.href = target.href; });
  $('check').addEventListener('click', async () => {
    $('check').disabled = true;
    $('verdict').hidden = false;
    $('verdict-title').textContent = 'Asking test servers…';
    $('verdict-text').textContent = '';
    $('rows').replaceChildren();
    try {
      const mine = $('home').value;
      const rows = await askTestServers(target.hostname, mine);
      for (const r of rows) {
        const tr = document.createElement('tr');
        const mark = rate(r);
        for (const text of [COUNTRIES[r.country] || r.country, r.city || '', mark === 'ok' ? 'Opens' : mark === 'refused' ? 'Refused (' + r.code + ')' : r.code ? 'Server error (' + r.code + ')' : 'Does not open']) {
          const td = document.createElement('td'); td.textContent = text; tr.append(td);
        }
        tr.className = mark;
        $('rows').append(tr);
      }
      const [title, text] = conclude(local, rows, mine);
      $('verdict-title').textContent = title;
      $('verdict-text').textContent = text;
    } catch (e) {
      $('verdict-title').textContent = 'The check did not work';
      $('verdict-text').textContent = e && e.message ? e.message : 'Try again in a minute.';
    }
    $('check').disabled = false;
  });
}

if (typeof module !== 'undefined') module.exports = { conclude, rate, localKind };
if (typeof document !== 'undefined') {
  const q = new URLSearchParams(location.search);
  start(window.SF_DIAG || { url: q.get('u') || '', kind: q.get('k') || '', status: q.get('s') || 0 });
}
