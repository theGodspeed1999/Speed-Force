// Speed Force start page: asks the program for the numbers and top sites, shows them.
(function () {
  var base = 'https://duckduckgo.com/?q=';
  var colors = ['#fb542b', '#4c54d2', '#2e9d6b', '#b5517c', '#3d8bd9', '#c68a1d'];
  document.getElementById('f').onsubmit = function (e) {
    e.preventDefault();
    var q = document.getElementById('q').value.trim();
    if (!q) return;
    var isAddr = /^(https?:\/\/)?[\w-]+(\.[\w-]+)+(:\d+)?(\/\S*)?$/.test(q);
    location.href = isAddr ? (/^https?:/.test(q) ? q : 'https://' + q) : base + encodeURIComponent(q);
  };
  function size(b) { return b >= 1e9 ? (b / 1e9).toFixed(1) + ' GB' : b >= 1e6 ? (b / 1e6).toFixed(1) + ' MB' : Math.round(b / 1e3) + ' KB'; }
  function time(s) { return s >= 3600 ? (s / 3600).toFixed(1) + ' h' : s >= 60 ? Math.round(s / 60) + ' min' : s + ' s'; }
  window.chrome.webview.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || d.t !== 'startdata') return;
    base = d.base || base;
    document.getElementById('s1').textContent = d.stats.blocked;
    document.getElementById('s2').textContent = size(d.stats.bytes);
    document.getElementById('s3').textContent = time(Math.round(d.stats.bytes * 10 / 15e6 + d.stats.blocked / 30));
    var box = document.getElementById('sites');
    d.sites.forEach(function (s, i) {
      var a = document.createElement('a'); a.href = s.url;
      var ic = document.createElement('i'); ic.style.background = colors[i % 6]; ic.textContent = s.host.charAt(0).toUpperCase();
      a.appendChild(ic); a.appendChild(document.createTextNode(s.host)); box.appendChild(a);
    });
  });
  window.chrome.webview.postMessage({ t: 'startdata' });
})();
