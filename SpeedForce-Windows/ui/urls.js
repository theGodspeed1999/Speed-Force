// Pure helpers for web addresses (no Electron here, so test/test.js can check them).
'use strict';

/** Search engines: name to the address the search words are added to. */
const engines = {
  DuckDuckGo: 'https://duckduckgo.com/?q=',
  Google: 'https://www.google.com/search?q=',
  Bing: 'https://www.bing.com/search?q=',
  Brave: 'https://search.brave.com/search?q=',
};

/** The search words when [url] is a results page of one of the engines, else null. */
function searchQuery(url) {
  if (!Object.values(engines).some(e => url.startsWith(e.split('?')[0]))) return null;
  const m = /[?&]q=([^&#]+)/.exec(url);
  try { return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : null; } catch (e) { return null; }
}

/** Turn what was typed in the address bar into an address (or a search). */
function toUrlOrSearch(input, searchBase = engines.DuckDuckGo) {
  const text = input.trim();
  if (!text) return '';
  if (/^https?:\/\//i.test(text) || /^(about|file):/i.test(text)) return text;
  if (!text.includes(' ') && /^[\w-]+(\.[\w-]+)+(:\d+)?(\/.*)?$/.test(text)) return 'https://' + text;
  if (/^localhost(:\d+)?(\/.*)?$/i.test(text)) return 'http://' + text;
  return searchBase + encodeURIComponent(text).replace(/%20/g, '+');
}

function host(url) {
  try { return new URL(url).hostname.toLowerCase().replace(/^www\./, '') || null; } catch (e) { return null; }
}

function origin(url) {
  try { const u = new URL(url); return u.host ? u.origin : null; } catch (e) { return null; }
}

const isHttp = u => typeof u === 'string' && /^https?:\/\//i.test(u);

/** True if the host or any parent domain of it is in [blocked] (a Set). */
function isBlockedHost(h, blocked) {
  if (!h) return false;
  h = h.toLowerCase().replace(/^www\./, '');
  for (;;) {
    if (blocked.has(h)) return true;
    const dot = h.indexOf('.');
    if (dot < 0) return false;
    h = h.substring(dot + 1);
    if (!h.includes('.')) return false; // stop at the top-level domain
  }
}

/** HLS / DASH / MP4 / WEBM when the address looks like a video stream (pieces like .ts are ignored). */
function classifyStream(url) {
  const lower = url.toLowerCase();
  if (!lower.startsWith('http')) return null;
  const path = lower.split('#')[0].split('?')[0];
  if (path.endsWith('.m3u8')) return 'HLS';
  if (path.endsWith('.mpd')) return 'DASH';
  if (/\.(mp4|m4v|mov)$/.test(path)) return 'MP4';
  if (path.endsWith('.webm')) return 'WEBM';
  return null;
}

/** File names players use for the playlist that lists every quality. */
const looksLikeMasterPlaylist = url =>
  /^(master|index|playlist|manifest|main)[^/]*\.(m3u8|mpd)$/.test(fileName(url).toLowerCase());

/**
 * Ad networks that keep changing their domain name (so no list can keep up) still use the
 * same address shape: https://random-words.org/22/0c68eb516d80d3a1ce9486707ffc5f64
 * Others (fake chat popups): https://29548.random-words.com/4/js/253804 and their click servers.
 */
function isRotatingAdLoader(url) {
  const rest = url.includes('://') ? url.substring(url.indexOf('://') + 3) : '';
  const path = '/' + (rest.includes('/') ? rest.substring(rest.indexOf('/') + 1) : '').split('?')[0].split('#')[0];
  if (/^\/\d{1,4}\/[0-9a-f]{32}\/?$/.test(path)) return true;
  const h = host(url);
  if (!h) return false;
  if (/^\d{3,7}\.[a-z]{6,}\.[a-z]{2,6}$/.test(h) && /^\/\d{1,3}\/js\/\d{3,9}\/?$/.test(path)) return true;
  return /^\d{5,10}-\d{3,7}-ex\./.test(h);
}

/** A video player asking an ad server which ad to play first (VAST/VMAP). */
const isVastRequest = url =>
  /vast|vmap|\/gampad\/ads|output=xml|[?&]format=vast|\.xml(\?|$)/i.test(url.includes('://') ? url.split('://')[1] : url);

/** The site part of a host: "cdn.example.co.uk" -> "example.co.uk", "m.site.com" -> "site.com". */
function siteOf(h) {
  const p = h.toLowerCase().replace(/^www\./, '').split('.');
  if (p.length <= 2) return p.join('.');
  const twoPart = p[p.length - 1].length === 2 && p[p.length - 2].length <= 3; // co.uk, com.au, co.in
  return p.slice(twoPart ? -3 : -2).join('.');
}

function sameSite(a, b) {
  const ha = a && host(a), hb = b && host(b);
  return !!(ha && hb && siteOf(ha) === siteOf(hb));
}

/** Key used to avoid listing the same stream twice (same file, different query tokens). */
const dedupeKey = url => url.split('#')[0].split('?')[0].toLowerCase();

/** Last part of the address without "?…", e.g. "93818767_720p.mp4". */
const fileName = url => url.split('#')[0].split('?')[0].replace(/\/+$/, '').split('/').pop();

/** Hover previews, thumbnails and site-wide background clips: not the film you want. */
const looksLikePreviewClip = url =>
  /preview|thumb|teaser|trailer|sprite|promo|(^|[/_-])site\.webm/i.test(fileName(url));

/** Short readable name for a stream address, e.g. "cdn.site.com/…/master.m3u8". */
function shortName(url) {
  const f = url.split('?')[0].split('/').pop();
  return f ? `${host(url) || ''}/…/${f}` : (host(url) || '');
}

const SCAM_TEXT = new RegExp(
  'virus|infected|malware|trojan|hacked|been compromised|security (alert|warning)|battery (is )?(damaged|infected)|' +
  "you( have|'ve)? won|winner|congratulations|claim your|free iphone|prize|gift card|" +
  'call (support|microsoft|apple)|tech support|your (phone|device|computer|pc) (is|has|will)|clean(er)? (your|now)', 'i');

/** Text of fake "your PC has a virus" / "you won" message boxes. */
const isScamMessage = m => !!m && SCAM_TEXT.test(m);

const TRACKING_PARAMS = new Set([
  'fbclid', 'gclid', 'dclid', 'gbraid', 'wbraid', 'msclkid', 'yclid', 'twclid', 'ttclid',
  'igshid', 'mc_eid', 'mc_cid', '_hsenc', '_hsmi', 'mkt_tok', 'oly_anon_id', 'oly_enc_id',
  'vero_id', 's_cid', 'rb_clickid', '_openstat', 'wickedid', 'srsltid',
]);

/** Removes click-tracking codes (utm_*, fbclid, gclid …) from a link, like Brave/Firefox do. */
function stripTracking(url) {
  const q = url.indexOf('?');
  if (q < 0) return url;
  const hash = url.indexOf('#', q);
  const query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
  const fragment = hash < 0 ? '' : url.substring(hash);
  const kept = query.split('&').filter(part => {
    const name = part.split('=')[0].toLowerCase();
    return part && !name.startsWith('utm_') && !TRACKING_PARAMS.has(name);
  });
  return url.substring(0, q) + (kept.length ? '?' + kept.join('&') : '') + fragment;
}

/** Home-network / local addresses are left on http (routers, NAS boxes …). */
function isLocalHost(h) {
  if (!h) return true;
  h = h.toLowerCase();
  if (h === 'localhost' || /\.(local|lan|home)$/.test(h)) return true;
  const m = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(h);
  if (!m) return false;
  const [a, b] = [+m[1], +m[2]];
  return a === 10 || a === 127 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 169 && b === 254);
}

/** "http://site/x" -> "https://site/x" (unchanged if already secure, local, or not a web link). */
const upgradeToHttps = url =>
  /^http:\/\//i.test(url) && !isLocalHost(host(url)) ? 'https://' + url.substring(7) : url;

const MEDIA_EXT = new Set(['mp4', 'm4v', 'mkv', 'webm', 'mov', '3gp', 'ts', 'avi', 'flv',
  'm4a', 'aac', 'mp3', 'opus', 'ogg', 'oga', 'wav', 'flac']);

/** Only real video/audio files may be saved; blocks programs a site could push. */
const isMediaExtension = ext => !!ext && MEDIA_EXT.has(ext.toLowerCase().replace(/^\./, ''));

module.exports = {
  engines, searchQuery, toUrlOrSearch, host, origin, isHttp, isBlockedHost, classifyStream,
  looksLikeMasterPlaylist, isRotatingAdLoader, isVastRequest, siteOf, sameSite, dedupeKey, fileName,
  looksLikePreviewClip, shortName, isScamMessage, SCAM_SOURCE: SCAM_TEXT.source, stripTracking, isLocalHost, upgradeToHttps, isMediaExtension,
};
