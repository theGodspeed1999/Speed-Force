// Speed Force (PC): runs in every page and frame before the page's own scripts, next to detect.js.
// Stops fake message boxes: "Your PC has a virus!", boxes from another site's (ad) frame, and endless
// box loops (more than 2 in 10 seconds). Normal message boxes still show.
(function (cfg) {
  if (cfg.mode === 'OFF') return;
  var SCAM = new RegExp(cfg.scam, 'i');
  var B = window.VideoGrabNative;
  var times = [], muted = false, otherSite = false;
  function siteOf(h) {
    var p = h.toLowerCase().replace(/^www\./, '').split('.');
    if (p.length <= 2) return p.join('.');
    var two = p[p.length - 1].length === 2 && p[p.length - 2].length <= 3;
    return p.slice(two ? -3 : -2).join('.');
  }
  try {
    var ao = location.ancestorOrigins;
    if (ao && ao.length) otherSite = siteOf(new URL(ao[ao.length - 1]).hostname) !== siteOf(location.hostname);
  } catch (e) {}
  function stopped() {
    try { B.postMessage(JSON.stringify({ t: 'hidden', n: 1, frames: [], page: location.href })); } catch (e) {}
    return false;
  }
  function allowed(m) {
    if (muted || otherSite || SCAM.test(String(m))) return stopped();
    var now = Date.now();
    times = times.filter(function (t) { return now - t < 10000; });
    times.push(now);
    if (times.length > 2) { muted = true; return stopped(); }
    return true;
  }
  var A = window.alert, C = window.confirm, P = window.prompt;
  window.alert = function (m) { if (allowed(m)) return A.apply(window, arguments); };
  window.confirm = function (m) { return allowed(m) ? C.apply(window, arguments) : false; };
  window.prompt = function (m) { return allowed(m) ? P.apply(window, arguments) : null; };
})
