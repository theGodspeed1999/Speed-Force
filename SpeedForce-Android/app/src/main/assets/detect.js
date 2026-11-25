// Speed Force: injected into every page and iframe (after window.__vgCfg is set).
//  1. Finds <video> elements and puts a "Download" button on top of each.
//  2. Reports .m3u8/.mpd/.mp4 addresses the page loads through XHR/fetch.
//  3. Ad handling: in HIDE mode ads stay on the page (so anti-adblock scripts are happy)
//     but are made invisible and unclickable; in BLOCK mode they are removed from layout.
(function () {
  if (window.__vgInjected) return;
  window.__vgInjected = true;
  var B = window.VideoGrabBridge || window.VideoGrabNative;
  if (!B) return;
  function send(o) { try { B.postMessage(JSON.stringify(o)); } catch (e) {} }

  var CFG = window.__vgCfg || { mode: 'OFF', ads: [] };
  // Shields switched down for this site (the page's own address, or the top page when this is a frame)
  try {
    var topHost = location.ancestorOrigins && location.ancestorOrigins.length ?
      new URL(location.ancestorOrigins[location.ancestorOrigins.length - 1]).hostname : location.hostname;
    topHost = topHost.toLowerCase().replace(/^www\./, '');
    if (CFG.off && CFG.off.indexOf(topHost) >= 0) CFG = { mode: 'OFF', ads: [], wake: CFG.wake, off: CFG.off };
  } catch (e) {}
  var ADS = {};
  for (var a = 0; a < CFG.ads.length; a++) ADS[CFG.ads[a]] = 1;
  function isAdHost(h) {
    h = (h || '').toLowerCase().replace(/^www\./, '');
    while (h) {
      if (ADS[h]) return true;
      var i = h.indexOf('.');
      if (i < 0) return false;
      h = h.substring(i + 1);
      if (h.indexOf('.') < 0) return false;
    }
    return false;
  }
  function hostOf(u) { try { return new URL(u, location.href).hostname; } catch (e) { return ''; } }

  // Remember every element that hides its content in a "shadow" box: fake chat/notification ads
  // draw themselves there so page styles (and ad filters) can't see inside.
  var shadowHosts = [];
  try {
    var origAttach = Element.prototype.attachShadow;
    if (origAttach) {
      Element.prototype.attachShadow = function () {
        var root = origAttach.apply(this, arguments);
        if (shadowHosts.length >= 200) shadowHosts.shift();
        shadowHosts.push(this);
        return root;
      };
    }
  } catch (e) {}

  // Back-button trap: scripts quietly add fake history steps so Back keeps you on the page or opens
  // their ad. A history step added without your tap replaces the current step instead of adding one.
  try {
    var act = navigator.userActivation;
    if (act && CFG.mode !== 'OFF') {
      var origPush = history.pushState, origReplace = history.replaceState;
      history.pushState = function () {
        if (!act.isActive) return origReplace.apply(history, arguments);
        return origPush.apply(history, arguments);
      };
    }
  } catch (e) {}

  // --- video ads: the player asks a server which ad to play (VAST/VMAP, an XML document) ---
  // Whatever the address, the answer is recognised by its content and replaced by "no ad today":
  // the player then starts the real video at once.
  var NO_VAST = '<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"></VAST>';
  function hasVastAd(t) {
    return typeof t === 'string' && t.length < 5e6 && /<(VAST|vmap:VMAP)[\s>]/.test(t.slice(0, 3000)) && /<(Ad|vmap:AdBreak)[\s>]/.test(t);
  }
  if (CFG.mode !== 'OFF') {
    try {
      var XP = XMLHttpRequest.prototype;
      var rawText = Object.getOwnPropertyDescriptor(XP, 'responseText');
      var rawResp = Object.getOwnPropertyDescriptor(XP, 'response');
      var rawXml = Object.getOwnPropertyDescriptor(XP, 'responseXML');
      Object.defineProperty(XP, 'responseText', { configurable: true, enumerable: true, get: function () {
        var t = rawText.get.call(this);
        return hasVastAd(t) ? NO_VAST : t;
      } });
      Object.defineProperty(XP, 'response', { configurable: true, enumerable: true, get: function () {
        var r = rawResp.get.call(this);
        if (hasVastAd(r)) return NO_VAST;
        if (r && this.responseType === 'document' && r.documentElement && /^(VAST|vmap:VMAP)$/.test(r.documentElement.nodeName) && hasVastAd(r.documentElement.outerHTML)) {
          return new DOMParser().parseFromString(NO_VAST, 'text/xml');
        }
        return r;
      } });
      Object.defineProperty(XP, 'responseXML', { configurable: true, enumerable: true, get: function () {
        var d = rawXml.get.call(this);
        if (d && d.documentElement && /^(VAST|vmap:VMAP)$/.test(d.documentElement.nodeName) && hasVastAd(d.documentElement.outerHTML)) {
          return new DOMParser().parseFromString(NO_VAST, 'text/xml');
        }
        return d;
      } });
    } catch (e) {}
    try {
      if (window.fetch) {
        var fetchForAds = window.fetch;
        window.fetch = function (input) {
          return fetchForAds.apply(this, arguments).then(function (res) {
            try {
              var u = String((input && input.url) || input);
              var ct = (res.headers && res.headers.get('content-type')) || '';
              if (!/xml/i.test(ct) && !/vast|vmap|\.xml(\?|$)/i.test(u)) return res;
              return res.clone().text().then(function (t) {
                return hasVastAd(t) ? new Response(NO_VAST, { status: 200, headers: { 'Content-Type': 'text/xml' } }) : res;
              });
            } catch (e) { return res; }
          });
        };
      }
    } catch (e) {}
  }

  // --- video ads that must "play" before the real video (pre-roll) ---
  // Blocking them often breaks the player, so they are allowed to run but are muted,
  // fast-forwarded to the end and their "Skip" button is pressed: the player then starts the real video.
  var AD_PLAYER = '.vjs-ad-playing,.vjs-ad-loading,.jw-flag-ads,.ima-ad-container,[id*="ima-ad-container"],' +
    '.plyr--ad,.fp-ad,.is-ad-visible,.fluid_ad_playing,.ts-im-container';
  var SKIP_SEL = '.vast-skip-button,.videoAdUiSkipButton,.skip_button,.jw-skip,.fp-ad-skip,.ytp-ad-skip-button,' +
    '.ytp-ad-skip-button-modern,[class*="skip-ad"],[class*="skipAd"],[class*="ad-skip"],[class*="adSkip"]';
  var SKIP_TEXT = /^\s*(skip|skip ads?|skip ad now|skip video|skip this ad|close ad|skip now)\s*[›»>▶︎→]*\s*$/i;
  // Any player: while an ad runs it says so ("Skip ad in 3", "Ad - 00:28", "Ad 1 of 2")
  var AD_NOTE = /skip ad|skip in \d|skip this ad|\bad\s*[-–—:·]\s*\d{1,2}:\d\d|\bads?\s*\d+\s*(of|\/)\s*\d+|your video (will|starts)|video (will )?(play|start)s? (in|after)|ad ends in|after (this|the) ad/i;
  function playerSaysAd(v) {
    var now = Date.now();
    if (v.__vgSaysAt && now - v.__vgSaysAt < 1200) return v.__vgSays;
    v.__vgSaysAt = now;
    v.__vgSays = playerSaysAdNow(v);
    return v.__vgSays;
  }
  function playerSaysAdNow(v) {
    try {
      if (v.paused || !v.offsetWidth) return false;
      var up = v.parentElement;
      for (var k = 0; k < 6 && up && up !== document.body; k++, up = up.parentElement) {
        // Stop at the edge of the player: below it are comments and titles that may say anything
        if (up.offsetHeight > v.offsetHeight * 1.5 + 80) return false;
        if (AD_NOTE.test((up.innerText || '').slice(0, 500))) return true;
      }
    } catch (e) {}
    return false;
  }
  function isAdVideo(v, adFrame) {
    if (adFrame) return true;
    if (isAdHost(hostOf(v.currentSrc || v.src || ''))) return true;
    try { if (v.closest && v.closest(AD_PLAYER)) return true; } catch (e) {}
    return playerSaysAd(v);
  }
  function speedThrough(v) {
    try {
      if (!v.__vgSped) v.__vgSped = { muted: v.muted };
      v.muted = true;
      if (v.playbackRate < 16) v.playbackRate = 16;
      var d = v.duration;
      if (d && isFinite(d) && d > 0.5 && v.currentTime < d - 0.3) v.currentTime = d - 0.1;
    } catch (e) {}
  }
  function pressSkip() {
    try {
      var c = document.querySelectorAll(SKIP_SEL + ',button,div[role="button"],a[role="button"],span');
      for (var i = 0, seen = 0; i < c.length && seen < 400; i++) {
        var el = c[i];
        if (el.getAttribute('data-videograb') || !el.offsetParent) continue;
        var txt = (el.textContent || '').trim();
        if (txt.length > 24) continue;
        seen++;
        if (/content|main|navigation|menu/i.test(txt)) continue; // "Skip to content" links are not ads
        var bySel = el.matches && el.matches(SKIP_SEL) && /skip|close/i.test(txt + ' ' + el.className);
        if (bySel || SKIP_TEXT.test(txt)) {
          var r = el.getBoundingClientRect();
          if (r.width < 8 || r.height < 8) continue;
          el.click();
        }
      }
    } catch (e) {}
  }
  function skipAds(adFrame) {
    if (CFG.mode === 'OFF') return;
    var vids = document.getElementsByTagName('video'), any = false;
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (isAdVideo(v, adFrame)) { speedThrough(v); any = true; }
      else if (v.__vgSped) {
        // Phones often play the ad in the same video as the film: give the film normal speed and sound back
        try { v.playbackRate = 1; v.muted = v.__vgSped.muted; } catch (e) {}
        v.__vgSped = null;
      }
    }
    if (any || document.querySelector(AD_PLAYER)) pressSkip();
  }

  // Inside an ad's own frame: only speed its video ads through (no buttons, no reports)
  if (CFG.mode !== 'OFF' && isAdHost(location.hostname)) {
    setInterval(function () { skipAds(true); }, 400);
    return;
  }

  var STREAM_RE = /\.(m3u8|mpd|mp4|m4v|webm)(\?|#|$)/i;
  var reported = {};
  function reportStream(u) {
    try {
      if (!u) return;
      u = new URL(u, location.href).href;
      if (!/^https?:/i.test(u)) return;
      var path = u.split('?')[0].split('#')[0].replace(/\/+$/, '');
      if (!STREAM_RE.test(path)) return;
      if (reported[path]) return;
      reported[path] = 1;
      if (isAdHost(hostOf(u))) return; // video ads are not what you want to download
      send({ t: 'stream', url: u, frame: location.href });
    } catch (e) {}
  }

  // --- find video addresses hidden in the page itself ---
  // Works even when the site's player is broken: many sites pack the real video address into
  // the player's link (plain, %-encoded, base64-encoded) or into page scripts/JSON.
  var URL_IN_TEXT = /https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4|m4v|webm)(?:\?[^\s"'<>\\]*)?/gi;
  function findUrls(text) {
    if (!text || text.length < 10) return;
    var t = text.replace(/\\\//g, '/').replace(/\\u0026/g, '&');            // JSON-escaped https:\/\/...
    var m = t.match(URL_IN_TEXT);
    if (m) for (var i = 0; i < m.length && i < 50; i++) reportStream(m[i]);
  }
  function tryDecode(v) {
    var out = [v];
    try { var d = decodeURIComponent(v); if (d !== v) out.push(d); } catch (e) {}
    // base64 (also the URL-safe kind), at least 20 chars
    var b = v.replace(/-/g, '+').replace(/_/g, '/');
    if (/^[A-Za-z0-9+\/]{20,}={0,2}$/.test(b)) {
      try {
        while (b.length % 4) b += '=';
        var s = atob(b);
        out.push(s);
        try { out.push(decodeURIComponent(s)); } catch (e) {}
      } catch (e) {}
    }
    return out;
  }
  var scannedSrc = {};
  function scanLink(src) {
    if (!src || scannedSrc[src]) return;
    scannedSrc[src] = 1;
    findUrls(src);
    var q = src.indexOf('?');
    if (q < 0) return;
    var parts = src.substring(q + 1).split('#')[0].split('&');
    for (var i = 0; i < parts.length; i++) {
      var val = parts[i].substring(parts[i].indexOf('=') + 1);
      var dec = tryDecode(val);
      for (var j = 0; j < dec.length; j++) findUrls(dec[j]);
    }
  }
  function deepScan(wholePage) {
    try {
      var els = document.querySelectorAll('iframe[src],embed[src],object[data],a[href*="m3u8"],a[href*=".mp4"],[data-src],[data-video],[data-file]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.hasAttribute('data-vgad')) continue;
        var key = el.getAttribute('src') || el.getAttribute('href') || el.getAttribute('data-src') || '';
        if (el.__vgDeep === key) continue; // looked at already (and unchanged)
        el.__vgDeep = key;
        scanLink(el.getAttribute('src') || el.getAttribute('data') || el.getAttribute('href') ||
          el.getAttribute('data-src') || el.getAttribute('data-video') || el.getAttribute('data-file'));
      }
      if (wholePage && document.documentElement) findUrls(document.documentElement.innerHTML);
    } catch (e) {}
  }

  // --- watch network calls made by players (hls.js, dash.js, ...) ---
  try {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      reportStream(u);
      // Apps (Instagram, ...) get the address of the whole video in a data answer
      this.addEventListener('load', function () {
        try {
          if (this.responseType && this.responseType !== 'text') return;
          var t = this.responseText;
          if (t && t.length < 5e6 && t.indexOf('.mp4') >= 0) findUrls(t);
        } catch (e) {}
      });
      return origOpen.apply(this, arguments);
    };
  } catch (e) {}
  try {
    if (window.fetch) {
      var origFetch = window.fetch;
      window.fetch = function (input) {
        try { reportStream(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
        var answer = origFetch.apply(this, arguments);
        answer.then(function (res) {
          if (!/json|javascript|text/i.test((res.headers && res.headers.get('content-type')) || '')) return;
          return res.clone().text().then(function (t) {
            if (t.length < 5e6 && t.indexOf('.mp4') >= 0) findUrls(t);
          });
        }).catch(function () {});
        return answer;
      };
    }
  } catch (e) {}

  // --- YouTube: its video ads come from the same servers as the video, so host lists cannot stop them ---
  if (CFG.mode !== 'OFF' && /(^|\.)(youtube\.com|youtube-nocookie\.com)$/.test(location.hostname)) {
    try {
      // 1. take the ad schedule out of what the player is told, before it reads it
      var YT_AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
      var ytClean = function (o) {
        if (o && typeof o === 'object') {
          YT_AD_KEYS.forEach(function (k) {
            if (o[k] !== undefined) delete o[k];
            if (o.playerResponse && o.playerResponse[k] !== undefined) delete o.playerResponse[k];
          });
        }
        return o;
      };
      var ytParse = JSON.parse;
      JSON.parse = function () { return ytClean(ytParse.apply(this, arguments)); };
      var ytJson = window.Response && Response.prototype.json;
      if (ytJson) Response.prototype.json = function () { return ytJson.apply(this, arguments).then(ytClean); };
      var ytFirst;  // the first video's data is written straight into the page
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function () { return ytFirst; },
        set: function (v) { ytFirst = ytClean(v); }
      });
    } catch (e) {}
    // 2. an ad that still starts is muted, run to its end and skipped
    var ytMuted = null;
    setInterval(function () {
      var p = document.querySelector('.html5-video-player');
      var v = p && p.querySelector('video');
      if (!v) return;
      if (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')) {
        if (ytMuted === null) ytMuted = v.muted;
        v.muted = true;
        if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.3) v.currentTime = v.duration - 0.1;
        var b = p.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
        if (b) b.click();
      } else if (ytMuted !== null) {
        v.muted = ytMuted;
        ytMuted = null;
      }
    }, 300);
    // 3. promoted boxes in the feed, search results and beside the video
    var ytCss = function () {
      var root = document.head || document.documentElement;
      if (!root) return setTimeout(ytCss, 50);
      var st = document.createElement('style');
      st.textContent = ['ytd-ad-slot-renderer', 'ytd-rich-item-renderer:has(ytd-ad-slot-renderer)', 'ytd-in-feed-ad-layout-renderer',
        'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-video-renderer', 'ytd-search-pyv-renderer', 'ytd-display-ad-renderer',
        'ytd-companion-slot-renderer', 'ytd-action-companion-ad-renderer', 'ytd-banner-promo-renderer', '#masthead-ad', '#player-ads',
        'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]', '.ytp-ad-overlay-container',
        '.ytp-featured-product', 'ytm-promoted-sparkles-web-renderer', 'ytm-companion-ad-renderer', 'ytm-promoted-video-renderer',
        'ad-slot-renderer'].map(function (s) { return s + '{display:none!important}'; }).join('');
      root.appendChild(st);
    };
    ytCss();
  }

  // --- ad hiding ---
  var AD_SELECTORS = [
    'ins.adsbygoogle', '[id^="div-gpt-ad"]', '[id^="google_ads_"]', '[data-ad-slot]',
    '[data-google-query-id]', '[id^="taboola-"]', '.OUTBRAIN', '[data-ad-unit]', '[data-adunit]',
    // Ad boxes that ad scripts build directly into the page (no ad frame, links via ever-changing domains):
    // TrafficStars native cards + floating video, ExoClick zones/native widgets, Stripchat cam widgets
    '[id^="ts_ms_"]', '.ts-im-container', '.mn-related-container', 'ins[data-zoneid]', '.exo-native-widget',
    '[id^="exo-native-widget"]', '[id^="ts_in_"]',
    // TrafficStars fake "notification" popup and "Suggested for you" native ads, pop-up loader slots
    '.ad-notification-popup', '[id^="ts_ad_native_"]', 'ins[data-key]',
    // ExoClick video slider in the corner
    '[class*="msg-video-slider"]', '[id$="-msg-video-slider-content"]', '.exo_wrapper',
    // Google: full-screen "vignette", sticky bottom "anchor" and "side rail" ads, ad frames without an address
    'ins[data-vignette-loaded]', 'ins[data-anchor-status]', 'ins[data-side-rail-status]', '[id^="google_ads_iframe"]', '[id^="aswift_"]'
  ].join(',');
  var styleAdded = false, ownStyles = [], revealed = false;
  function addStyle() {
    if (styleAdded || CFG.mode === 'OFF') return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var st = document.createElement('style');
    st.textContent = (CFG.mode === 'BLOCK'
      ? '[data-vgad]{display:none!important}'
      // Frames and pictures fold away; other marked boxes stay in place so anti-adblock checks are happy
      : '[data-vgad]{opacity:0!important;pointer-events:none!important}iframe[data-vgad],ins[data-vgad],img[data-vgad]{display:none!important}') +
      // Ads floating over the page are always removed: an invisible one could still catch your taps
      '[data-vgov]{display:none!important}';
    root.appendChild(st);
    ownStyles.push(st);
    styleAdded = true;
  }
  // Some ads (Google's full-screen "vignette") write their own "!important" styles onto themselves,
  // which beat a style sheet. Then the hiding is written onto the ad itself, and again if it changes it.
  function enforce(el) {
    var s = el.style;
    if (el.hasAttribute('data-vgov') || CFG.mode === 'BLOCK' || /^(IFRAME|INS|IMG)$/.test(el.tagName)) {
      if (s.getPropertyValue('display') !== 'none' || s.getPropertyPriority('display') !== 'important') s.setProperty('display', 'none', 'important');
      return;
    }
    var cs = getComputedStyle(el);
    if (cs.opacity !== '0') s.setProperty('opacity', '0', 'important');
    if (cs.pointerEvents !== 'none') s.setProperty('pointer-events', 'none', 'important');
  }
  function enforceAll() {
    var m = document.querySelectorAll('[data-vgad]');
    for (var i = 0; i < m.length; i++) enforce(m[i]);
  }

  // Google's vignette shows when you click a link and holds the link back until you close the ad.
  // The ad is hidden, so the click goes on to its page by itself.
  var lastLink = null;
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a[href]');
    lastLink = a && /^https?:/i.test(a.href) ? { href: a.href, at: Date.now() } : null;
  }, true);
  function continueAfterVignette() {
    if (CFG.mode === 'OFF' || location.hash !== '#google_vignette' || !lastLink || Date.now() - lastLink.at > 5000) return;
    var go = lastLink.href;
    lastLink = null;
    location.href = go;
  }
  window.addEventListener('hashchange', continueAfterVignette);

  function mark(el, frames) {
    if (el.hasAttribute('data-vgad')) return 0;
    el.setAttribute('data-vgad', '');
    if (el.tagName === 'IFRAME' && el.src) frames.push(el.src);
    return 1;
  }
  // --- ads floating over the page ---
  // Fake chat messages ("Julia (35) - your city"), fake notifications, full-screen "vignette" ads and
  // invisible layers that open an ad on your first tap. Their names and domains change all the time,
  // but they all need the very top layer of the screen, which normal sites don't use.
  var TOP_LAYER = 2147480000;
  var SITE_NOTICE = /cookie|consent|gdpr|privacy|verify your age|age verification|are you 18|i am 18|i'm 18|over 18|18 years|18\+|18 or older|adults only|legal age/i;
  var SITE_BOX = /age-?gate|age-?verif|consent|cookie|gdpr/i;
  function zOf(cs) { var z = parseInt(cs.zIndex, 10); return isNaN(z) ? 0 : z; }
  function topFloating(root, depth) {
    var best = null, bestZ = -1;
    (function walk(e, d) {
      var cs = getComputedStyle(e);
      if (cs.position === 'fixed' || cs.position === 'sticky') {
        var z = zOf(cs);
        if (z > bestZ) { bestZ = z; best = e; }
      }
      if (d <= 0) return;
      for (var c = e.firstElementChild; c; c = c.nextElementSibling) walk(c, d - 1);
    })(root, depth);
    return best ? { el: best, z: bestZ } : null;
  }
  function isOwnOrSkip(el) {
    var t = el.tagName;
    return t === 'SCRIPT' || t === 'STYLE' || t === 'LINK' || t === 'META' || t === 'NOSCRIPT' || t === 'HEAD' ||
      t === 'BODY' || el.hasAttribute('data-videograb') || el.hasAttribute('data-vgov');
  }
  function hasRealVideo(el) {
    var v = el.getElementsByTagName('video');
    for (var i = 0; i < v.length; i++) if (v[i].offsetWidth > window.innerWidth * 0.6) return true;
    return false;
  }
  function inFullscreen(el) {
    var f = document.fullscreenElement || document.webkitFullscreenElement;
    return !!(f && (f === el || el.contains(f) || f.contains(el)));
  }
  /** Invisible full-screen layer: no text, no picture, see-through. Its only job is to catch a tap. */
  function isTapTrap(el, cs) {
    var r = el.getBoundingClientRect();
    if (r.width * r.height < window.innerWidth * window.innerHeight * 0.6) return false;
    if ((el.innerText || '').trim() || el.querySelector('img,video,canvas,svg,iframe,input,button')) return false;
    var bg = cs.backgroundColor;
    return parseFloat(cs.opacity) < 0.05 || bg === 'transparent' || /rgba\(.*,\s*0(\.0+)?\)$/.test(bg);
  }
  function unlockScroll() {
    // Full-screen ads freeze the page under them; hiding the ad must give scrolling back
    [document.documentElement, document.body].forEach(function (e) {
      if (e && e.style && e.style.overflow === 'hidden') e.style.overflow = '';
    });
  }
  function markOverlay(el) {
    if (el.hasAttribute('data-vgov')) return 0;
    var r = el.getBoundingClientRect(); // measured before hiding (hidden = no size)
    el.setAttribute('data-vgov', '');
    el.setAttribute('data-vgad', '');
    if (r.width * r.height > window.innerWidth * window.innerHeight * 0.5) unlockScroll();
    return 1;
  }
  function scanOverlays() {
    var n = 0, i, el;
    // 1. Shadow boxes made by scripts (plain <div>s; real page widgets use their own tag names)
    for (i = shadowHosts.length - 1; i >= 0; i--) {
      el = shadowHosts[i];
      if (!el.isConnected) continue; // ads often build the box first and show it later
      if (el.hasAttribute('data-vgov') || el.tagName.indexOf('-') >= 0 || inFullscreen(el)) continue;
      var inner = el.shadowRoot; // null = "closed" box: only ads hide that hard
      var floating = inner ? null : true;
      if (inner) {
        for (var c = inner.firstElementChild; c && !floating; c = c.nextElementSibling) {
          if (c.tagName === 'STYLE' || c.tagName === 'SCRIPT') continue;
          var f = topFloating(c, 3);
          if (f && f.z >= 10000) floating = true;
        }
      }
      if (floating && !hasRealVideo(el)) n += markOverlay(el);
    }
    // 2. Boxes on the very top layer, and invisible tap traps, added straight into the page
    var roots = [];
    if (document.body) roots = roots.concat(Array.prototype.slice.call(document.body.children));
    if (document.documentElement) roots = roots.concat(Array.prototype.slice.call(document.documentElement.children));
    for (i = 0; i < roots.length; i++) {
      el = roots[i];
      if (isOwnOrSkip(el)) continue;
      var top = topFloating(el, 2);
      if (!top || top.el.hasAttribute('data-vgov') || top.el.hasAttribute('data-videograb') || inFullscreen(top.el)) continue;
      var cs = getComputedStyle(top.el);
      if (top.z >= TOP_LAYER) {
        if (hasRealVideo(top.el) || SITE_BOX.test(top.el.id + ' ' + top.el.className) ||
          SITE_NOTICE.test((top.el.innerText || '').slice(0, 600))) continue;
        n += markOverlay(top.el);
      } else if (top.z >= 1000 && isTapTrap(top.el, cs)) {
        n += markOverlay(top.el);
      }
    }
    return n;
  }

  // Banners come in a few standard sizes, whatever network serves them; a frame of that size is an ad
  var AD_SIZES = [[300, 250], [320, 50], [320, 100], [728, 90], [160, 600], [300, 600], [336, 280], [970, 250], [970, 90], [468, 60], [234, 60], [120, 600], [300, 100], [250, 250]];
  function adSized(f) {
    if (/player|embed|video|youtube|vimeo|stream|captcha|challenge|login|pay/i.test(f.src || '')) return false;
    var w = f.offsetWidth, h = f.offsetHeight;
    for (var i = 0; i < AD_SIZES.length; i++) {
      if (Math.abs(w - AD_SIZES[i][0]) <= 24 && Math.abs(h - AD_SIZES[i][1]) <= 24) return true;
    }
    return false;
  }

  // --- whatever calls itself an ad ---
  // A box labelled "Advertisement" is an ad, whatever network it comes from.
  var AD_LABEL = /^(advertisements?|advertising|adverts?|ads|sponsored|sponsored (ads?|links?|content)|ads? by .{1,20}|publicidad|publicit[eé]|werbung|anzeige|реклама|विज्ञापन|广告)$/i;
  function labelledBox(label) {
    var best = null, up = label.parentElement;
    for (var k = 0; k < 4 && up && up !== document.body && up !== document.documentElement; k++, up = up.parentElement) {
      var t = up.tagName;
      if (t === 'MAIN' || t === 'ARTICLE' || t === 'NAV' || t === 'HEADER' || t === 'FOOTER') break;
      var r = up.getBoundingClientRect();
      if (r.height > window.innerHeight * 0.7 || (up.innerText || '').length > 250 || hasRealVideo(up)) break;
      if (up.querySelector('iframe,img,ins,video,canvas,a[href]') && !up.querySelector('input,form,[data-videograb]')) best = up;
    }
    return best || label;
  }
  function scanLabels() {
    var n = 0, c = document.querySelectorAll('div,span,p,small,center,aside,section,h1,h2,h3,h4,h5,h6,strong,b,i,em,font');
    for (var i = 0; i < c.length && i < 15000; i++) {
      var el = c[i];
      if (el.firstElementChild || el.hasAttribute('data-vgad')) continue;
      var t = (el.textContent || '').trim();
      if (t.length < 3 || t.length > 30 || !AD_LABEL.test(t)) continue;
      if (el.closest('a,button,nav,header,footer,li,[role="button"],[role="menu"],[data-vgad]')) continue;
      var box = labelledBox(el);
      if (box !== el) { box.setAttribute('data-vgov', ''); box.setAttribute('data-vgad', ''); }
      el.setAttribute('data-vgov', '');
      el.setAttribute('data-vgad', '');
      n++;
    }
    return n;
  }

  // Tell the app which names the page uses; it answers with the ad rules from the lists that apply
  var seenNames = {}, firstLook = true, domChanged = true, lastFullLook = 0;
  if (B.addEventListener && CFG.mode !== 'OFF') {
    try {
      B.onmessage = function (ev) {
        var st = document.createElement('style');
        if (revealed) return;
        st.textContent = String(ev.data);
        (document.head || document.documentElement).appendChild(st);
        ownStyles.push(st);
      };
    } catch (e) {}
  }
  function askForRules() {
    if (CFG.mode === 'OFF') return;
    var cl = [], ids = [], all = document.querySelectorAll('[class],[id]');
    for (var i = 0; i < all.length && i < 8000; i++) {
      var id = all[i].getAttribute('id'), names = (all[i].getAttribute('class') || '').split(/\s+/);
      if (id && !seenNames['#' + id]) { seenNames['#' + id] = 1; ids.push(id); }
      for (var k = 0; k < names.length; k++) {
        var c = names[k];
        if (c && !seenNames['.' + c]) { seenNames['.' + c] = 1; cl.push(c); }
      }
    }
    if (!firstLook && !cl.length && !ids.length) return;
    send({ t: 'cosmetic', host: location.hostname, page: location.href, c: cl.slice(0, 4000), i: ids.slice(0, 2000), first: firstLook });
    firstLook = false;
  }
  // The app stopped a picture or frame: fold its empty box away
  window.__vgHide = function (urls) {
    var set = {}, n = 0;
    for (var i = 0; i < urls.length; i++) set[urls[i]] = 1;
    var els = document.querySelectorAll('iframe[src],img[src]');
    for (i = 0; i < els.length; i++) {
      if (set[els[i].src] && !els[i].hasAttribute('data-vgad')) { els[i].setAttribute('data-vgad', ''); n++; }
    }
    if (n) enforceAll();
  };

  function scanAds() {
    if (CFG.mode === 'OFF' || revealed) return;
    addStyle();
    var n = 0, frames = [], i, el;
    if (domChanged && Date.now() - lastFullLook > 4000) {
      domChanged = false;
      lastFullLook = Date.now();
      askForRules();
      try { n += scanLabels(); } catch (e) {}
    }
    var ifr = document.getElementsByTagName('iframe');
    for (i = 0; i < ifr.length; i++) {
      el = ifr[i];
      if (el.hasAttribute('data-vgad')) continue;
      var fk = el.src + '|' + el.offsetWidth + 'x' + el.offsetHeight;
      if (el.__vgChecked === fk) continue;
      el.__vgChecked = fk;
      if ((el.src && isAdHost(hostOf(el.src))) || adSized(el)) n += mark(el, frames);
    }
    var links = document.querySelectorAll('a[href]');
    for (i = 0; i < links.length; i++) {
      el = links[i];
      if (el.__vgChecked === el.href) continue; // each link is checked once (again only if it changes)
      el.__vgChecked = el.href;
      if (!el.hasAttribute('data-vgad') && isAdHost(hostOf(el.href))) n += mark(el, frames);
    }
    try {
      var sel = document.querySelectorAll(AD_SELECTORS);
      for (i = 0; i < sel.length; i++) n += mark(sel[i], frames);
    } catch (e) {}
    // Video ads: a video played from an ad server hides together with its floating box
    var vids = document.getElementsByTagName('video');
    for (i = 0; i < vids.length; i++) {
      el = vids[i];
      if (el.closest && el.closest('[data-vgad]')) continue;
      if (!isAdHost(hostOf(srcOf(el)))) continue;
      // Only a floating box is hidden: a pre-roll ad inside the real player is sped through instead
      // (hiding it would also hide the real video that plays in the same place afterwards)
      for (var k = 0, up = el.parentElement; k < 5 && up && up !== document.body; k++, up = up.parentElement) {
        if (getComputedStyle(up).position === 'fixed' && up.offsetWidth < window.innerWidth * 0.7) { n += mark(up, frames); break; }
      }
    }
    try { n += scanOverlays(); } catch (e) {}
    if (n > 0) enforceAll(); // marks already hidden stay hidden through the style watcher
    continueAfterVignette();
    if (n > 0) { guardOn = true; send({ t: 'hidden', n: n, frames: frames, page: location.href }); }
    if (document.querySelector('[data-vgad]')) guardOn = true;
    guardScroll();
  }

  // --- buttons over videos ---
  var tracked = [];
  var lastCount = -1;

  function srcOf(v) {
    var s = v.currentSrc || v.src || '';
    if (!s) {
      var so = v.querySelector('source[src]');
      if (so) s = so.src;
    }
    return s || '';
  }

  // Download tapped before the film started: start the player (muted) so it loads its real stream
  // address, which the app catches and reads in about a second. Much faster than yt-dlp digging
  // through the page. Works through frames: each frame passes the message on to its own frames.
  var WAKE = CFG.wake || '';
  var PLAY_BUTTONS = '.jw-display-icon-display,.jw-icon-display,.vjs-big-play-button,.plyr__control--overlaid,' +
    '.fp-play,.mejs__overlay-button,.ytp-large-play-button,[class*="big-play"],[class*="play-button"],[class*="playbutton"]';
  function bigVideo() {
    var best = null, area = 0, vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.closest && v.closest('[data-vgad]')) continue;
      var s = v.offsetWidth * v.offsetHeight;
      if (s > area) { area = s; best = v; }
    }
    return best;
  }
  function clickPlayButton() {
    var best = null, area = 0, els = document.querySelectorAll(PLAY_BUTTONS);
    for (var i = 0; i < els.length; i++) {
      var r = els[i].getBoundingClientRect(), s = r.width * r.height;
      if (s > area && !els[i].closest('[data-vgad],a[href]')) { area = s; best = els[i]; }
    }
    if (best) { try { best.click(); } catch (e) {} return true; }
    return false;
  }
  function quietPlay(v) {
    if (!v || !v.paused) return;
    v.muted = true;
    try { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
    // Only the address was needed: stop again a few seconds later and give the sound back
    setTimeout(function () { try { v.pause(); v.muted = false; } catch (e) {} }, 6000);
  }
  function wake() {
    var done = false;
    try {
      var jw = window.jwplayer && window.jwplayer();
      if (jw && jw.play && jw.getState) {
        var st = jw.getState();
        if (st !== 'playing' && st !== 'buffering') {
          if (jw.setMute) jw.setMute(true);
          jw.play();
          setTimeout(function () { try { jw.pause(); if (jw.setMute) jw.setMute(false); } catch (e) {} }, 6000);
        }
        done = true;
      }
    } catch (e) {}
    if (!done) {
      var v = bigVideo();
      if (v && srcOf(v)) quietPlay(v);
      else if (!clickPlayButton() && v) quietPlay(v);
    }
    var fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage({ __vgWake: WAKE }, '*'); } catch (e) {}
    }
  }
  // --- scroll guard ---
  // Some ads lock the page's scrolling until you close them. With the ad hidden there is nothing to close,
  // so the page looked frozen until a reload. Once this page had an ad hidden: when scrolling is locked
  // but nothing is visible on screen that could explain it (an open menu, a box, an age check), the lock
  // is lifted, and the hidden ad's script can no longer swallow swipes.
  var guardOn = false, layerSeen = { at: 0, v: false };
  function bigVisibleLayer() {
    var now = Date.now();
    if (now - layerSeen.at < 500) return layerSeen.v;
    var W = window.innerWidth, H = window.innerHeight, found = false;
    var pts = [[W / 2, H / 2], [W / 2, H / 4], [W / 2, H * 3 / 4]];
    for (var i = 0; i < pts.length && !found; i++) {
      // hidden ads don't count: they are see-through for taps (or not drawn at all)
      for (var e = document.elementFromPoint(pts[i][0], pts[i][1]); e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
        if (e.hasAttribute('data-videograb')) break;
        var cs = getComputedStyle(e);
        if (cs.position === 'fixed' || cs.position === 'sticky') {
          var r = e.getBoundingClientRect();
          if (r.width * r.height >= W * H * 0.4 && parseFloat(cs.opacity) > 0.1 && cs.visibility !== 'hidden') { found = true; break; }
        }
      }
    }
    layerSeen = { at: now, v: found };
    return found;
  }
  function scrollLocked() {
    var h = document.documentElement, b = document.body;
    if (!b) return false;
    if (Math.max(h.scrollHeight, b.scrollHeight) <= window.innerHeight + 20) return false; // nothing to scroll
    var ch = getComputedStyle(h), cb = getComputedStyle(b);
    return /hidden|clip/.test(ch.overflowY) || /hidden|clip/.test(cb.overflowY) || cb.position === 'fixed' ||
      ch.touchAction === 'none' || cb.touchAction === 'none';
  }
  function guardScroll() {
    if (!guardOn || revealed || CFG.mode === 'OFF') return;
    try {
      if (!scrollLocked() || bigVisibleLayer()) return;
      var h = document.documentElement, b = document.body;
      if (getComputedStyle(b).position === 'fixed') {
        var top = parseInt(b.style.top, 10) || 0;
        b.style.setProperty('position', 'static', 'important');
        if (top < 0) window.scrollTo(0, -top);
      }
      [h, b].forEach(function (e) {
        e.style.setProperty('overflow-y', 'auto', 'important');
        e.style.setProperty('touch-action', 'auto', 'important');
      });
    } catch (e) {}
  }
  try {
    var origPrevent = Event.prototype.preventDefault;
    Event.prototype.preventDefault = function () {
      try {
        if (guardOn && !revealed && CFG.mode !== 'OFF' && this.cancelable && (this.type === 'touchmove' || this.type === 'wheel')) {
          var ct = this.currentTarget;
          if ((ct === window || ct === document || ct === document.documentElement || ct === document.body) && !bigVisibleLayer()) return;
        }
      } catch (e) {}
      return origPrevent.apply(this, arguments);
    };
  } catch (e) {}

  // --- "Show hidden ads on this page" (menu) ---
  // Hiding stops for this page until it is reloaded, so the user can see an ad and close it themselves.
  function reveal() {
    if (revealed) return;
    revealed = true;
    for (var i = 0; i < ownStyles.length; i++) { try { ownStyles[i].remove(); } catch (e) {} }
    var m = document.querySelectorAll('[data-vgad],[data-vgov]');
    for (i = 0; i < m.length; i++) {
      var el = m[i], st = el.style;
      el.removeAttribute('data-vgad');
      el.removeAttribute('data-vgov');
      ['display', 'opacity', 'pointer-events'].forEach(function (k) { if (st.getPropertyPriority(k) === 'important') st.removeProperty(k); });
    }
    var fr = document.getElementsByTagName('iframe');
    for (i = 0; i < fr.length; i++) { try { fr[i].contentWindow.postMessage({ __vgReveal: WAKE }, '*'); } catch (e) {} }
  }

  if (WAKE) {
    window.addEventListener('message', function (e) {
      if (e.data && e.data.__vgWake === WAKE) wake();
      if (e.data && e.data.__vgReveal === WAKE) reveal();
    });
    window.__vgWake = wake;
    window.__vgReveal = function () { reveal(); return document.querySelectorAll('iframe').length; };
  }

  function makeButton(v) {
    var b = document.createElement('div');
    b.textContent = '⬇ Download';
    b.setAttribute('data-videograb', '1');
    b.style.cssText = [
      'position:fixed', 'z-index:2147483647', 'display:none',
      'background:#FB542B', 'color:#fff', 'font:bold 14px/1 sans-serif',
      'padding:9px 13px', 'border-radius:18px', 'box-shadow:0 2px 8px rgba(0,0,0,.6)',
      'user-select:none', '-webkit-user-select:none', 'pointer-events:auto', 'cursor:pointer',
      'border:2px solid #fff', 'opacity:0.92'
    ].join(';');
    var fire = function (e) {
      e.preventDefault();
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
      if (WAKE && v.paused) wake();
      send({ t: 'video', frame: location.href, src: srcOf(v), title: document.title || '', dur: v.duration || 0, p: Math.min(v.videoWidth, v.videoHeight) || 0 });
    };
    b.addEventListener('click', fire, true);
    ['touchstart', 'touchend', 'mousedown', 'mouseup', 'pointerdown', 'pointerup'].forEach(function (t) {
      b.addEventListener(t, function (e) { e.stopPropagation(); }, true);
    });
    (document.body || document.documentElement).appendChild(b);
    return b;
  }

  var lastScroll = 0;
  function scan() {
    if (document.hidden) return;
    // A full look costs time: while you scroll it waits, so scrolling stays smooth
    if (Date.now() - lastScroll < 500) { schedule(); return; }
    scanAds();
    deepScan(false);
    var vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.__vgButton) continue;
      if (v.closest && v.closest('[data-vgad]')) continue; // video ad
      v.__vgButton = makeButton(v);
      tracked.push(v);
      var s = srcOf(v);
      if (/^https?:/i.test(s)) reportStream(s);
      v.addEventListener('loadedmetadata', function () { var u = srcOf(this); if (/^https?:/i.test(u)) reportStream(u); });
    }
    if (tracked.length !== lastCount) {
      lastCount = tracked.length;
      send({ t: 'videos', n: tracked.length, frame: location.href });
    }
    place();
  }

  // Moves the Download buttons with their videos. Runs at most once per screen refresh, and measures
  // every video before moving any button: mixing the two makes the phone re-measure the whole page
  // for each video, which made long pages stutter and freeze while scrolling.
  var placeQueued = false;
  function place() {
    if (placeQueued) return;
    placeQueued = true;
    (window.requestAnimationFrame || setTimeout)(placeNow);
  }
  function placeNow() {
    placeQueued = false;
    var i, v, todo = [], W = window.innerWidth, H = window.innerHeight;
    for (i = tracked.length - 1; i >= 0; i--) {
      v = tracked[i];
      if (!v.isConnected) {
        var old = v.__vgButton;
        if (old && old.parentNode) old.parentNode.removeChild(old);
        tracked.splice(i, 1);
        continue;
      }
      var r = v.getBoundingClientRect();
      var visible = r.width >= 80 && r.height >= 50 && r.bottom > 0 && r.right > 0 && r.top < H && r.left < W;
      todo.push([v.__vgButton, visible, Math.max(4, r.top + 8) + 'px', Math.max(4, r.left + 8) + 'px']);
    }
    for (i = 0; i < todo.length; i++) {
      var b = todo[i][0], s = b.style;
      if (!b.isConnected) (document.body || document.documentElement).appendChild(b);
      if (!todo[i][1]) { if (s.display !== 'none') s.display = 'none'; continue; }
      if (s.display !== 'block') s.display = 'block';
      if (s.top !== todo[i][2]) s.top = todo[i][2];
      if (s.left !== todo[i][3]) s.left = todo[i][3];
    }
  }

  var pending = false;
  function schedule() {
    domChanged = true;
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; scan(); }, 600);
  }

  // A hidden ad that shows itself again is hidden again, but a moment later: answering in the same
  // instant let some ads and this script push each other back and forth forever, which froze the page.
  // An ad that keeps doing it is taken off the page.
  var fought = [], fightQueued = false;
  function settleFights() {
    fightQueued = false;
    if (revealed) { fought = []; return; }
    var list = fought;
    fought = [];
    var now = Date.now();
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!el.isConnected || el.__vgGone || el.__vgSettled === now) continue;
      el.__vgSettled = now;
      if (!el.__vgFight || now - el.__vgFight.at > 3000) el.__vgFight = { at: now, n: 0 };
      if (++el.__vgFight.n > 6) {
        el.__vgGone = true;
        if (el.hasAttribute('data-vgov') || /^(IFRAME|INS|IMG)$/.test(el.tagName)) {
          try { el.parentNode.removeChild(el); } catch (e) {}
        }
        continue; // other boxes: stop arguing, the style sheet still keeps them see-through
      }
      enforce(el);
    }
  }

  function start() {
    scan();
    deepScan(true);
    setTimeout(function () { deepScan(true); }, 3000); // after late scripts have filled the page
    document.addEventListener('DOMContentLoaded', function () { deepScan(true); });
    try {
      new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
      if (CFG.mode !== 'OFF') new MutationObserver(function (ms) {
        for (var i = 0; i < ms.length; i++) {
          var t = ms[i].target;
          if (t.hasAttribute('data-vgad')) fought.push(t);
        }
        if (fought.length && !fightQueued) { fightQueued = true; setTimeout(settleFights, 50); }
      }).observe(document.documentElement, { attributes: true, attributeFilter: ['style'], subtree: true });
    } catch (e) {}
    window.addEventListener('scroll', function () { lastScroll = Date.now(); place(); }, { capture: true, passive: true });
    window.addEventListener('resize', place);
    setInterval(scan, 1500);
    setInterval(function () { skipAds(false); }, 400);
  }

  if (document.documentElement) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
