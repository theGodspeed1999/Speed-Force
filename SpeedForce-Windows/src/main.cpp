// Speed Force (C++): a small Windows browser on the WebView2 engine that is already part of Windows.
// Window and tabs are native; the toolbar and side panels are one light web page (ui\). Ads and trackers
// are stopped here, in C++, before a page can load them. Videos and files download from here too.
#include <winsock2.h>  // must come before windows.h
#include "util.h"
#include <shellapi.h>
#include <dwmapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <atomic>
#include <deque>
#include <memory>
#include <thread>
#include "WebView2.h"
#include "tor.h"

#define APP_NAME L"Speed Force"
#define SF_VERSION "1.5.0"
static const char* kStartUrl = "https://sf.start/";
// The image search page is ours (assets\lens), shown under Lens' own address: Lens refuses uploads that come from elsewhere.
static const char* kLensUrl = "https://lens.google.com/speedforce/";
static const char* kDiagUrl = "https://sf.diag/";  // "Why didn't this page load?" (assets\diag)
static const UINT WM_POSTED = WM_APP + 1;
static const UINT WM_SHOWME = WM_APP + 2;

// ---------------------------------------------------------------- COM callbacks (small helper classes)
template <class I>
struct CbBase : I {
  ULONG n = 1;
  virtual ~CbBase() = default;
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID, void** p) override { *p = this; AddRef(); return S_OK; }
  ULONG STDMETHODCALLTYPE AddRef() override { return ++n; }
  ULONG STDMETHODCALLTYPE Release() override { ULONG k = --n; if (!k) delete this; return k; }
};
template <class I, class A, class B>
struct Cb2 : CbBase<I> {
  std::function<HRESULT(A, B)> f;
  explicit Cb2(std::function<HRESULT(A, B)> fn) : f(std::move(fn)) {}
  HRESULT STDMETHODCALLTYPE Invoke(A a, B b) override { return f(a, b); }
};
template <class I, class A>
struct Cb1 : CbBase<I> {
  std::function<HRESULT(A)> f;
  explicit Cb1(std::function<HRESULT(A)> fn) : f(std::move(fn)) {}
  HRESULT STDMETHODCALLTYPE Invoke(A a) override { return f(a); }
};
#define H2(Iface, A, B, fn) (new Cb2<Iface, A, B>(fn))
#define H1(Iface, A, fn) (new Cb1<Iface, A>(fn))

template <class T>
struct Com {
  T* p = nullptr;
  Com() = default;
  Com(T* x) : p(x) { if (p) p->AddRef(); }
  Com(const Com& o) : p(o.p) { if (p) p->AddRef(); }
  Com& operator=(const Com& o) { if (o.p) o.p->AddRef(); if (p) p->Release(); p = o.p; return *this; }
  ~Com() { if (p) p->Release(); }
  T* operator->() const { return p; }
  explicit operator bool() const { return p != nullptr; }
  T** put() { if (p) { p->Release(); p = nullptr; } return &p; }
  template <class U> Com<U> as(const IID& iid) const { U* q = nullptr; if (p) p->QueryInterface(iid, (void**)&q); Com<U> r; r.p = q; return r; }
};
struct CoStr {  // text returned by WebView2 (must be freed with CoTaskMemFree)
  LPWSTR w = nullptr;
  ~CoStr() { if (w) CoTaskMemFree(w); }
  LPWSTR* put() { return &w; }
  string str() const { return U8(w); }
};

// ---------------------------------------------------------------- app state
struct Stream { string url, kind, referer, page; ULONGLONG at; };
struct Tab {
  int id = 0;
  Com<ICoreWebView2Controller> ctl;
  Com<ICoreWebView2> wv;
  string url, title = "New tab", favicon, pending;
  wstring fpScript;  // id of the fingerprint-protection script in this tab
  wstring pageScriptId;      // id of the blocking script in this tab, so it can be replaced
  std::set<string> visited;  // site names this tab showed (for the cookie guard)
  string lastNav;            // the address being loaded
  int lastStatus = 0;        // the site's answer code for the last page
  bool lens = false; // used for image search: those pages are not kept
  bool loading = false, ready = false, suspended = false, closing = false;
  int videoCount = 0, blocked = 0, hidden = 0;
  std::map<string, int> frameVideos;
  std::vector<Stream> streams;
  std::set<string> seen, upgraded;
  ULONGLONG lastGesture = 0, lastBack = 0, lastPopup = 0, finished = 0, lastVideoTap = 0, hiddenSince = 0;
  bool canBack = false, canFwd = false;
  double zoom = 1.0;
  EventRegistrationToken resTok{}, msgTok{};
};

struct DownloadItem { json j; HANDLE proc = nullptr; HANDLE job = nullptr; Com<ICoreWebView2DownloadOperation> op; };

static HWND g_hwnd = nullptr;
static wstring g_exe, g_data, g_assets, g_ui, g_engine;
static Com<ICoreWebView2Environment> g_env;
static Com<ICoreWebView2Controller> g_uiCtl;
static Com<ICoreWebView2> g_ui_wv;
static std::vector<std::unique_ptr<Tab>> g_tabs;
static Tab* g_cur = nullptr;
static int g_nextTab = 1;
static double g_topCss = 80, g_panelCss = 0;
static bool g_fullscreen = false, g_pageFs = false, g_uiReady = false;  // window fills the screen / a page video does (toolbar hidden)
static WINDOWPLACEMENT g_wp{};
static LONG g_style = 0;
static std::vector<string> g_closed;
static std::set<string> g_httpAllowed;
static json g_settings;
static json g_stats = {{"blocked", 0}, {"bytes", 0}};
static json g_counts = {{"hidden", 0}, {"popups", 0}, {"trackers", 0}};
static std::vector<json> g_billboard;
static int g_nextAd = 1;
static HostTable g_adHosts, g_trackerHosts;
static string g_pageHosts, g_detectJs, g_guardJs;
static string g_scamRe =
    "virus|infected|malware|trojan|hacked|been compromised|security (alert|warning)|battery (is )?(damaged|infected)|"
    "you( have|'ve)? won|winner|congratulations|claim your|free iphone|prize|gift card|"
    "call (support|microsoft|apple)|tech support|your (phone|device|computer|pc) (is|has|will)|clean(er)? (your|now)";
static std::vector<DownloadItem> g_dl;
static int g_nextFile = 1;
static std::mutex g_postMu;
static std::deque<std::function<void()>> g_postQ;
static ULONGLONG g_statsDirty = 0;

/** Text member of a json object; "" when missing or null (json::value() throws on null). */
static string jstr(const json& j, const char* k) {
  if (!j.is_object()) return "";
  auto it = j.find(k);
  return it != j.end() && it->is_string() ? it->get<string>() : string();
}

static void postUi(std::function<void()> f) {
  { std::lock_guard<std::mutex> l(g_postMu); g_postQ.push_back(std::move(f)); }
  PostMessageW(g_hwnd, WM_POSTED, 0, 0);
}

// ---------------------------------------------------------------- windows and engine options
static bool g_private = false;    // this window is a private window: own empty profile, deleted on exit
static bool g_secondary = false;
static string g_torCc;             // set in a Tor window: the exit country asked for
static tor::Process g_tor;          // in a Tor window: its own tor; otherwise only runs during a country check
static std::atomic<bool> g_torBusy{false};  // an extra normal window: shares the profile, does not own the saved tabs
static wstring g_exeFile;

// engine options: only needed to switch browser extensions on
struct SfOptions : ICoreWebView2EnvironmentOptions, ICoreWebView2EnvironmentOptions6 {
  LONG rc = 1;
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID r, void** o) override {
    if (r == IID_IUnknown || r == IID_ICoreWebView2EnvironmentOptions) *o = static_cast<ICoreWebView2EnvironmentOptions*>(this);
    else if (r == IID_ICoreWebView2EnvironmentOptions6) *o = static_cast<ICoreWebView2EnvironmentOptions6*>(this);
    else { *o = nullptr; return E_NOINTERFACE; }
    AddRef();
    return S_OK;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return ++rc; }
  ULONG STDMETHODCALLTYPE Release() override { LONG n = --rc; if (!n) delete this; return n; }
  static HRESULT str(LPWSTR* v, const wchar_t* s) {
    size_t n = (wcslen(s) + 1) * sizeof(wchar_t);
    *v = (LPWSTR)CoTaskMemAlloc(n);
    if (!*v) return E_OUTOFMEMORY;
    memcpy(*v, s, n);
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE get_AdditionalBrowserArguments(LPWSTR* v) override { return str(v, L""); }
  HRESULT STDMETHODCALLTYPE put_AdditionalBrowserArguments(LPCWSTR) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE get_Language(LPWSTR* v) override { return str(v, L""); }
  HRESULT STDMETHODCALLTYPE put_Language(LPCWSTR) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE get_TargetCompatibleBrowserVersion(LPWSTR* v) override { return str(v, L"110.0.1587.40"); }
  HRESULT STDMETHODCALLTYPE put_TargetCompatibleBrowserVersion(LPCWSTR) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE get_AllowSingleSignOnUsingOSPrimaryAccount(BOOL* v) override { *v = FALSE; return S_OK; }
  HRESULT STDMETHODCALLTYPE put_AllowSingleSignOnUsingOSPrimaryAccount(BOOL) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE get_AreBrowserExtensionsEnabled(BOOL* v) override { *v = TRUE; return S_OK; }
  HRESULT STDMETHODCALLTYPE put_AreBrowserExtensionsEnabled(BOOL) override { return S_OK; }
};

static void launchSelf(const wchar_t* arg) {
  wstring cmd = L"\"" + g_exeFile + L"\" " + arg;
  STARTUPINFOW si{sizeof si};
  PROCESS_INFORMATION pi{};
  if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &si, &pi)) { CloseHandle(pi.hThread); CloseHandle(pi.hProcess); }
}

// ---------------------------------------------------------------- settings and files
static json defaultSettings() {
  return {{"folder", ""}, {"maxRes", 1080}, {"parallel", 2}, {"adMode", "HIDE"}, {"httpsOnly", true}, {"engine", "DuckDuckGo"},
          {"home", "https://sf.start/"}, {"restoreTabs", true}, {"private", false}, {"theme", "SYSTEM"}, {"shieldsOff", json::array()}, {"engineUpdated", 0}, {"rejectCookies", true}, {"cookieGuard", false}};
}
static void saveSettings() {
  int p = g_settings.value("parallel", 2);
  g_settings["parallel"] = std::min(3, std::max(1, p));
  string m = g_settings.value("adMode", "HIDE");
  if (m != "HIDE" && m != "BLOCK" && m != "OFF") g_settings["adMode"] = "HIDE";
  writeFile(g_data + L"\\settings.json", g_settings.dump());
  if (g_settings.value("private", false) || !g_settings.value("restoreTabs", true)) writeFile(g_data + L"\\tabs.json", "{}");
}
static string mode() { return g_settings.value("adMode", "HIDE"); }
static wstring saveFolder() {
  string f = g_settings.value("folder", "");
  return f.empty() ? dirOf(g_data) + L"\\Videos" : W(f);
}
static wstring filesFolder() { return dirOf(g_data) + L"\\Files"; }
static string siteOf(const string& u) { return hostOf(u); }
static bool isStart(const string& u) { return startsWith(u, kStartUrl) || u == "https://sf.start"; }
static bool shieldsDown(const Tab* t) {
  if (!t || isStart(t->url)) return false;
  string h = siteOf(t->url);
  for (auto& x : g_settings["shieldsOff"]) if (x.get<string>() == h) return true;
  return false;
}
static string modeFor(const Tab* t) { return shieldsDown(t) ? "OFF" : mode(); }

static json readList(const wchar_t* name) { return readJson(g_data + L"\\" + name, json::array()); }
/** The image search page, or the Google results it led to: never written to history, saved tabs or "reopen". */
static bool isLensPage(bool lensTab, const string& url) {
  string h = hostOf(url);
  return startsWith(url, kLensUrl) || (lensTab && (h == "lens.google.com" || h == "www.google.com" || h == "google.com"));
}
static void addHistory(const string& title, const string& url) {
  if (hostOf(url) == "sf.diag") return;
  if (g_settings.value("private", false) || !isHttp(url) || isStart(url)) return;
  json list = readList(L"history.json"), out = json::array();
  out.push_back({{"t", title}, {"u", url}, {"at", unixMs()}});
  for (auto& h : list) { if (h.value("u", "") != url && out.size() < 1000) out.push_back(h); }
  writeFile(g_data + L"\\history.json", out.dump());
}
static bool isBookmarked(const string& url) { for (auto& b : readList(L"bookmarks.json")) if (b.value("u", "") == url) return true; return false; }

// ---------------------------------------------------------------- UI messages
static void sendUi(const char* channel, const json& data) {
  if (!g_ui_wv || !g_uiReady) return;
  json m = {{"ch", channel}, {"data", data}};
  g_ui_wv->PostWebMessageAsJson(W(m.dump()).c_str());
}
// the drop-down window (menu, site information, Shields) is its own small web view above the page
static Com<ICoreWebView2Controller> g_popCtl;
static Com<ICoreWebView2> g_popWv;
static HWND g_popHwnd = nullptr;
static string g_popKind, g_popHiddenKind;
static double g_popL = 0, g_popR = 0, g_popB = 0;  // the button it hangs from, in toolbar CSS pixels
static bool g_popOpen = false;
static ULONGLONG g_popHiddenAt = 0;
static void hidePopup();
static void replyUi(int id, const json& result) {
  ICoreWebView2* to = id >= 1000000 ? g_popWv.p : g_ui_wv.p;
  if (!to) return;
  json m = {{"id", id}, {"result", result}};
  to->PostWebMessageAsJson(W(m.dump()).c_str());
}
static void notice(const string& text, json action = nullptr) { sendUi("notice", {{"text", text}, {"action", action}}); }

static json tabState(Tab* t) {
  bool st = isStart(t->url);
  return {{"id", t->id}, {"title", st ? "New tab" : t->title}, {"url", st ? "" : t->url}, {"loading", t->loading}, {"favicon", t->favicon}, {"active", t == g_cur}};
}
static bool g_statePending = false;
static void pushState() {
  if (g_statePending) return;
  g_statePending = true;
  SetTimer(g_hwnd, 1, 30, nullptr);
}
static void doPushState() {
  g_statePending = false;
  json tabs = json::array();
  for (auto& t : g_tabs) tabs.push_back(tabState(t.get()));
  json s = {{"tabs", tabs}, {"counts", g_counts}, {"engine", g_settings.value("engine", "DuckDuckGo")}, {"fullscreen", g_fullscreen}, {"stats", g_stats}};
  if (Tab* t = g_cur) {
    bool st = isStart(t->url);
    string q = searchQueryOf(t->url);
    int streams = (int)t->streams.size();
    s["cur"] = {{"url", st ? "" : t->url}, {"start", st}, {"host", st ? "" : siteOf(t->url)}, {"blocked", t->blocked}, {"hidden", t->hidden},
                {"shieldsDown", shieldsDown(t)}, {"bookmarked", isBookmarked(t->url)}, {"canBack", t->canBack}, {"canFwd", t->canFwd},
                {"loading", t->loading}, {"insecure", startsWith(t->url, "http:")}, {"videos", t->videoCount}, {"streams", streams},
                {"search", q.empty() ? json(nullptr) : json(q)}, {"zoom", (int)(t->zoom * 100 + 0.5)}};
  }
  sendUi("state", s);
}

// ---------------------------------------------------------------- layout
static const int kEdge = 3;  // width of the resize border, in 96-dpi pixels
static double dpiScale() { return GetDpiForWindow(g_hwnd) / 96.0; }
static HWND g_uiHwnd = nullptr;  // window of the toolbar web view
static void layout() {
  if (!g_hwnd) return;
  RECT rc; GetClientRect(g_hwnd, &rc);
  double s = dpiScale();
  int e = (g_fullscreen || IsZoomed(g_hwnd)) ? 0 : (int)(kEdge * s + 0.5);  // free border: grab it to resize
  int w = rc.right - e, h = rc.bottom - e;
  if (g_uiCtl) {
    RECT r = {e, e, w, h};
    g_uiCtl->put_Bounds(r);
    g_uiCtl->put_IsVisible(!g_pageFs);
    if (g_uiHwnd) SetWindowPos(g_uiHwnd, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);  // the toolbar view fills the window: page views must stay above it
  }
  int top = e + (int)(g_topCss * s + 0.5), pw = (int)(g_panelCss * s + 0.5);
  for (auto& t : g_tabs) {
    if (!t->ctl) continue;
    bool show = t.get() == g_cur;
    RECT r = g_pageFs ? RECT{0, 0, rc.right, rc.bottom} : RECT{e, top, std::max(e + 100, w - pw), std::max(top + 100, h)};
    t->ctl->put_Bounds(r);
    t->ctl->put_IsVisible(show);
#ifdef SF_TEST
    { FILE* f = fopen("D:\\ap\\SpeedForce-Windows\\shots\\layout.txt", "a"); if (f) { fprintf(f, "tab %d show=%d r=%ld,%ld,%ld,%ld rc=%ld,%ld topCss=%f panel=%f\n", t->id, (int)show, r.left, r.top, r.right, r.bottom, rc.right, rc.bottom, g_topCss, g_panelCss); fclose(f); } }
#endif
  }
}

static void setFullscreen(bool on, bool page = false) {
  g_pageFs = on && page;
  if (on == g_fullscreen) { layout(); pushState(); return; }
  g_fullscreen = on;
  if (on) {
    g_style = GetWindowLongW(g_hwnd, GWL_STYLE);
    GetWindowPlacement(g_hwnd, &g_wp);
    MONITORINFO mi{sizeof(mi)};
    GetMonitorInfoW(MonitorFromWindow(g_hwnd, MONITOR_DEFAULTTONEAREST), &mi);
    SetWindowLongW(g_hwnd, GWL_STYLE, g_style & ~WS_OVERLAPPEDWINDOW);
    SetWindowPos(g_hwnd, HWND_TOP, mi.rcMonitor.left, mi.rcMonitor.top, mi.rcMonitor.right - mi.rcMonitor.left, mi.rcMonitor.bottom - mi.rcMonitor.top, SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
  } else {
    SetWindowLongW(g_hwnd, GWL_STYLE, g_style);
    SetWindowPlacement(g_hwnd, &g_wp);
    SetWindowPos(g_hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
  }
  layout();
  pushState();
}

// ---------------------------------------------------------------- ads and trackers
/** "TRACKER" (always blocked), "AD" (hidden or blocked by mode) or "". */
static string classify(const string& url) {
  if (mode() == "OFF") return "";
  string h = hostOf(url);
  if (h.empty()) return "";
  if (g_trackerHosts.blocks(h) || isRotatingAdLoader(url)) return "TRACKER";
  // standard ad script names, whatever site serves them
  string p = url.substr(0, url.find_first_of("?#"));
  if (endsWith(p, "/pagead.js") || endsWith(p, "/widget/ads.js")) return "AD";
  return g_adHosts.blocks(h) ? "AD" : "";
}
static void bumpStats(int bytes) {
  g_stats["blocked"] = g_stats["blocked"].get<int64_t>() + 1;
  g_stats["bytes"] = g_stats["bytes"].get<int64_t>() + bytes;
  g_statsDirty = nowMs();
}
static json addBillboard(const string& kind, const string& url, const string& page) {
  json e = {{"id", g_nextAd++}, {"kind", kind}, {"url", url}, {"page", page}, {"at", unixMs()}};
  g_billboard.insert(g_billboard.begin(), e);
  if (g_billboard.size() > 200) g_billboard.resize(200);
  sendUi("billboard", {{"entries", g_billboard}, {"counts", g_counts}});
  return e;
}

/** The script placed into every page and frame: bridge, settings, scam-box guard, detect.js. */
static string pageScript() {
  json ads = json::array();
  for (auto& h : split(g_pageHosts, '\n')) { string t = trim(h); if (!t.empty()) ads.push_back(t); }
  json off = g_settings["shieldsOff"];
  json cfg = {{"reject", g_settings.value("rejectCookies", true)}, {"mode", mode()}, {"ads", mode() == "OFF" ? json::array() : ads}, {"wake", ""}, {"off", off}, {"scam", g_scamRe}};
  string s = "(function(){if(window.VideoGrabNative)return;";
  s += "window.VideoGrabNative={postMessage:function(x){try{window.chrome.webview.postMessage(String(x).slice(0,20000))}catch(e){}}};";
  s += "var cfg=" + cfg.dump() + ";window.__vgCfg=cfg;";
  // A tap or key press by the user (lets downloads started by pages themselves be told apart)
  s += "var last=0;function g(){var n=Date.now();if(n-last>400){last=n;window.VideoGrabNative.postMessage('{\"t\":\"gesture\"}')}}"
       "addEventListener('pointerdown',g,true);addEventListener('keydown',g,true);";
  s += "})();\n";
  // guard.js wants its settings as an argument; detect.js reads window.__vgCfg
  s += "(function(){var cfg=window.__vgCfg;try{var top=(location.ancestorOrigins&&location.ancestorOrigins.length)?new URL(location.ancestorOrigins[location.ancestorOrigins.length-1]).hostname:location.hostname;top=top.toLowerCase().replace(/^www\\./,'');if(cfg.off.indexOf(top)>=0)cfg={mode:'OFF',ads:[],wake:'',off:cfg.off,scam:cfg.scam};}catch(e){}window.__vgCfg=cfg;})();\n";
  s += g_guardJs + "\n";
  s += "(window.__vgCfg);\n";
  s += g_detectJs;
  return s;
}

// ---------------------------------------------------------------- tabs
static Tab* tabById(int id) { for (auto& t : g_tabs) if (t->id == id) return t.get(); return nullptr; }
static void wireTab(Tab* t);
static void loadTab(Tab* t, const string& url);
static void selectTab(Tab* t);
static void reinstallScript();
static void addPageScript(Tab* t);

static string prepareUrl(Tab* t, string u) {
  u = stripTracking(u);
  string h = hostOf(u);
  if (g_settings.value("httpsOnly", true) && !h.empty() && !g_httpAllowed.count(h)) {
    string up = upgradeToHttps(u);
    if (up != u) t->upgraded.insert(h);
    u = up;
  }
  return u;
}

static void loadTab(Tab* t, const string& url) {
  t->pending.clear();
  if (url.empty()) return;
  string u = isStart(url) ? string(kStartUrl) : prepareUrl(t, url);
  if (!t->ready) { t->pending = u; t->url = u; return; }
  t->url = isStart(u) ? u : t->url;
  if (t->suspended) { t->suspended = false; }
  t->wv->Navigate(W(u).c_str());
}

static Tab* newTab(const string& url, bool front = true, bool lazy = false) {
  auto up = std::make_unique<Tab>();
  Tab* t = up.get();
  t->id = g_nextTab++;
  t->url = url;
  g_tabs.push_back(std::move(up));
  if (lazy) { t->pending = url; t->title = hostOf(url).empty() ? url : hostOf(url); }
  else t->pending = url.empty() ? g_settings.value("home", string(kStartUrl)) : url;
  if (!lazy) t->url = t->pending;
  if (front) selectTab(t); else pushState();
  return t;
}

/** Creates the web view of a tab: when it is first opened (restored tabs wait until you click them). */
static void ensureView(Tab* t) {
  if (t->ctl || !g_env || t->closing) return;
  t->ready = false;
  int id = t->id;
  g_env->CreateCoreWebView2Controller(g_hwnd, H2(ICoreWebView2CreateCoreWebView2ControllerCompletedHandler, HRESULT, ICoreWebView2Controller*, ([id](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
    Tab* t = tabById(id);
    if (!t || FAILED(hr) || !c) return S_OK;
    t->ctl = c;
    c->get_CoreWebView2(t->wv.put());
    t->ready = true;
    wireTab(t);
    layout();
    string u = t->pending.empty() ? t->url : t->pending;
    t->pending.clear();
    if (!u.empty()) { string go = isStart(u) ? string(kStartUrl) : prepareUrl(t, u); t->wv->Navigate(W(go).c_str()); }
    return S_OK;
  })));
}

static void suspendTab(Tab* t) {
  if (!t->wv || t == g_cur || t->suspended) return;
  auto w3 = t->wv.as<ICoreWebView2_3>(IID_ICoreWebView2_3);
  if (w3) { w3->TrySuspend(H2(ICoreWebView2TrySuspendCompletedHandler, HRESULT, BOOL, ([](HRESULT, BOOL) -> HRESULT { return S_OK; }))); t->suspended = true; }
}

static void selectTab(Tab* t) {
  hidePopup();
  Tab* prev = g_cur;
  g_cur = t;
  if (prev && prev != t) { prev->hiddenSince = nowMs(); if (prev->ctl) prev->ctl->put_IsVisible(FALSE); }
  ensureView(t);
  if (t->wv && t->suspended) {
    auto w3 = t->wv.as<ICoreWebView2_3>(IID_ICoreWebView2_3);
    if (w3) w3->Resume();
    t->suspended = false;
  }
  if (t->ctl) { t->ctl->put_IsVisible(TRUE); t->ctl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); }
  layout();
  pushState();
  sendUi("find-result", nullptr);
}

static bool inList(const char* key, const string& host) {
  for (auto& x : g_settings.value(key, json::array())) if (x.get<string>() == host) return true;
  return false;
}
static Com<ICoreWebView2CookieManager> cookieManager() {
  Com<ICoreWebView2CookieManager> cm;
  if (g_ui_wv) if (auto w2 = g_ui_wv.as<ICoreWebView2_2>(IID_ICoreWebView2_2)) w2->get_CookieManager(cm.put());
  return cm;
}
/** Counts the cookies saved for a page, or deletes them; answers with how many are left. */
static void siteCookies(const string& url, bool remove, std::function<void(int)> done) {
  auto cm = cookieManager();
  if (!cm || !isHttp(url)) { done(0); return; }
  ICoreWebView2CookieManager* raw = cm.p;
  raw->AddRef();
  raw->GetCookies(W(url).c_str(), H2(ICoreWebView2GetCookiesCompletedHandler, HRESULT, ICoreWebView2CookieList*, ([raw, remove, done](HRESULT, ICoreWebView2CookieList* cl) -> HRESULT {
    UINT n = 0;
    if (cl) cl->get_Count(&n);
    if (remove) for (UINT i = 0; i < n; i++) { Com<ICoreWebView2Cookie> c; cl->GetValueAtIndex(i, c.put()); raw->DeleteCookie(c.p); }
    raw->Release();
    done(remove ? 0 : (int)n);
    return S_OK;
  })));
}
// Page scripts cannot tell one PC from another: canvas reads get tiny random changes, hardware details are generic.
static const char* kFingerprintJs = R"JS((function(){var c=window.__vgCfg||{off:[]};try{var top=(location.ancestorOrigins&&location.ancestorOrigins.length)?new URL(location.ancestorOrigins[location.ancestorOrigins.length-1]).hostname:location.hostname;if((c.off||[]).indexOf(top.toLowerCase().replace(/^www\./,''))>=0)return}catch(e){}
var start=4*Math.floor(Math.random()*97);
function noise(d){for(var i=start;i<d.length;i+=388)d[i]^=1}
try{var C=CanvasRenderingContext2D.prototype,get=C.getImageData;C.getImageData=function(){var r=get.apply(this,arguments);noise(r.data);return r};
var H=HTMLCanvasElement.prototype;['toDataURL','toBlob'].forEach(function(n){var f=H[n];H[n]=function(){try{var x=this.getContext('2d');if(x&&this.width&&this.height){var d=get.call(x,0,0,this.width,this.height);noise(d.data);x.putImageData(d,0,0)}}catch(e){}return f.apply(this,arguments)}})}catch(e){}
function fix(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v},configurable:true})}catch(e){}}
fix(Navigator.prototype,'hardwareConcurrency',4);fix(Navigator.prototype,'deviceMemory',8);
[window.WebGLRenderingContext,window.WebGL2RenderingContext].forEach(function(G){if(!G)return;var f=G.prototype.getParameter;G.prototype.getParameter=function(p){if(p===37445)return 'Google Inc.';if(p===37446)return 'ANGLE (Generic GPU)';return f.apply(this,arguments)}})})();)JS";
static void addPageScript(Tab* t) {
  if (!t->wv) return;
  if (!t->pageScriptId.empty()) { t->wv->RemoveScriptToExecuteOnDocumentCreated(t->pageScriptId.c_str()); t->pageScriptId.clear(); }
  int id = t->id;
  t->wv->AddScriptToExecuteOnDocumentCreated(W(pageScript()).c_str(), H2(ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler, HRESULT, LPCWSTR, ([id](HRESULT, LPCWSTR sid) -> HRESULT {
    for (auto& x : g_tabs) if (x->id == id && sid) x->pageScriptId = sid;
    return S_OK;
  })));
}
static void applyFingerprint(Tab* t) {
  if (!t->wv) return;
  bool on = g_settings.value("antiFinger", false);
  if (!on && !t->fpScript.empty()) { t->wv->RemoveScriptToExecuteOnDocumentCreated(t->fpScript.c_str()); t->fpScript.clear(); }
  if (on && t->fpScript.empty()) {
    int id = t->id;
    t->wv->AddScriptToExecuteOnDocumentCreated(W(kFingerprintJs).c_str(), H2(ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler, HRESULT, LPCWSTR, ([id](HRESULT, LPCWSTR sid) -> HRESULT {
      for (auto& x : g_tabs) if (x->id == id && sid) x->fpScript = sid;
      return S_OK;
    })));
  }
}

/** Removes what the web engine itself noted about image searches: cached pages and its own history list. */
static void forgetLensTraces() {
  if (!g_ui_wv) return;
  if (auto w13 = g_ui_wv.as<ICoreWebView2_13>(IID_ICoreWebView2_13)) {
    Com<ICoreWebView2Profile> pr;
    w13->get_Profile(pr.put());
    if (auto p2 = pr.as<ICoreWebView2Profile2>(IID_ICoreWebView2Profile2))
      p2->ClearBrowsingData((COREWEBVIEW2_BROWSING_DATA_KINDS)(COREWEBVIEW2_BROWSING_DATA_KINDS_DISK_CACHE | COREWEBVIEW2_BROWSING_DATA_KINDS_BROWSING_HISTORY), nullptr);
  }
}
/** Is the site on the "keep me signed in" list? (a trusted name covers its sub-domains) */
static bool isTrusted(const string& host) {
  for (auto& x : g_settings.value("trustedSites", json::array())) if (sameSite("https://" + x.get<string>() + "/", "https://" + host + "/")) return true;
  return false;
}
static void listRemove(const char* key, const string& host) {
  json l = json::array();
  for (auto& x : g_settings.value(key, json::array())) if (x.get<string>() != host) l.push_back(x);
  g_settings[key] = l;
}
/** Cookie guard: the site's cookies are thrown away, unless it is trusted. */
static void guardClean(const string& host) {
  if (!g_settings.value("cookieGuard", false) || host.empty() || isTrusted(host)) return;
  siteCookies("https://" + host + "/", true, [](int) {});
  listRemove("guardSeen", host);
  saveSettings();
}
/** Remembered until cleaned, so that cookies are also thrown away if the app is closed with the tab open. */
static void guardNote(Tab* t, const string& url) {
  if (!isHttp(url) || isStart(url) || isLensPage(t->lens, url) || hostOf(url) == "sf.diag") return;
  string h = hostOf(url);
  t->visited.insert(h);
  if (g_settings.value("cookieGuard", false) && !isTrusted(h) && !inList("guardSeen", h)) {
    json l = g_settings.value("guardSeen", json::array());
    l.push_back(h);
    g_settings["guardSeen"] = l;
    saveSettings();
  }
}
/** The tab is closing: every site it showed that is open nowhere else is cleaned. */
static void guardTabClosed(Tab* t) {
  std::set<string> sites = t->visited;
  if (isHttp(t->url)) sites.insert(hostOf(t->url));
  for (auto& h : sites) {
    bool open = false;
    for (auto& x : g_tabs) if (x.get() != t && sameSite(x->url, "https://" + h + "/")) open = true;
    if (!open) guardClean(h);
  }
}
/** At start: clean what the last run left, except sites that are open again in the restored tabs. */
static void guardAtStart() {
  json saved = g_settings.value("restoreTabs", true) ? readJson(g_data + L"\\tabs.json", json::object()) : json::object();
  json seen = g_settings.value("guardSeen", json::array());
  for (auto& x : seen) {
    string h = x.get<string>();
    bool open = false;
    if (saved.contains("u")) for (auto& u : saved["u"]) if (sameSite(u.get<string>(), "https://" + h + "/")) open = true;
    if (!open) guardClean(h);
  }
}
static bool torInstalled();
static string diagUrl(const string& page, const string& kind, int status) {
  return string(kDiagUrl) + "?u=" + urlEncode(page) + "&k=" + kind + "&s=" + std::to_string(status) + (g_torCc.empty() && torInstalled() ? "&tor=1" : "");
}

/** A plain host name: nothing that could change the meaning of a command line. */
static bool plainHost(const string& h) {
  if (h.empty() || h.size() > 253 || h[0] == '-' || h.find('.') == string::npos) return false;
  for (char c : h) if (!(isalnum((unsigned char)c) || c == '.' || c == '-')) return false;
  return true;
}
static bool torInstalled() { return fileExists(g_exe + L"\\tor\\tor\\tor.exe"); }
static void tellTab(int tabId, const json& m) {
  for (auto& x : g_tabs) if (x->id == tabId && x->wv && startsWith(x->url, kDiagUrl)) x->wv->PostWebMessageAsJson(W(m.dump()).c_str());
}
/** "Try other countries through Tor": one tor, one country after another; each answer is shown as it arrives. */
static void torCheck(int tabId, const string& host) {
  if (!g_torCc.empty() || !plainHost(host)) return;
  if (g_torBusy.exchange(true)) { tellTab(tabId, {{"tor", "error"}, {"text", "A Tor check is already running."}}); return; }
  wstring torDir = g_exe + L"\\tor", data = g_data + L"\\tor-check";
  makeDirs(data);
  std::thread([=] {
    auto say = [tabId](json m) { postUi([tabId, m] { tellTab(tabId, m); }); };
    say({{"tor", "status"}, {"text", "Connecting to Tor… the first time can take a minute."}});
    string err;
    if (!g_tor.start(torDir, data, "", 120, err)) say({{"tor", "error"}, {"text", err}});
    else {
      for (const char* cc : tor::kCountries) {
        say({{"tor", "status"}, {"text", "Trying the site…"}, {"cc", cc}});
        int code = g_tor.setExit(cc) ? tor::fetch(g_tor.socks, host, string("sf") + cc) : 0;
        if (!code && g_tor.setExit(cc)) code = tor::fetch(g_tor.socks, host, string("sf2") + cc);  // one more circuit
        say({{"tor", "row"}, {"cc", cc}, {"code", code}});
      }
      say({{"tor", "done"}});
      g_tor.stop();  // nothing keeps running (and using memory) after the check
    }
    g_torBusy = false;
  }).detach();
}
/** Opens the site in its own Tor window; this window and its tabs stay on the direct connection. */
static void torOpen(const string& cc, string url) {
  if (!g_torCc.empty() || !tor::knownCountry(cc) || !isHttp(url)) return;
  if (startsWith(url, "http:")) url = "https:" + url.substr(5);
  for (char c : url) if (c == '"' || c == '\\' || (unsigned char)c <= 32) return;
  launchSelf((L"--tor=" + W(cc) + L" \"" + W(url) + L"\"").c_str());
}

/** True when [remoteTag] (e.g. "v1.5.1") is strictly newer than [localVer] (e.g. "1.5.0"). */
static bool isNewerVersion(const string& remoteTag, const string& localVer) {
  auto parse = [](string s) {
    if (!s.empty() && (s[0] == 'v' || s[0] == 'V')) s = s.substr(1);
    auto dash = s.find('-'); if (dash != string::npos) s = s.substr(0, dash);
    std::vector<int> out;
    for (auto& p : split(s, '.')) out.push_back(atoi(p.c_str()));
    return out;
  };
  auto r = parse(remoteTag), l = parse(localVer);
  if (r.empty()) return false;
  size_t n = std::max(r.size(), l.size());
  for (size_t i = 0; i < n; i++) {
    int rv = i < r.size() ? r[i] : 0, lv = i < l.size() ? l[i] : 0;
    if (rv != lv) return rv > lv;
  }
  return false;
}

static int runCurl(const wstring& args, string& out) {
  wchar_t sys[MAX_PATH];
  GetSystemDirectoryW(sys, MAX_PATH);
  wstring cmd = L"\"" + wstring(sys) + L"\\curl.exe\" " + args;
  SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
  HANDLE read = nullptr, write = nullptr;
  if (!CreatePipe(&read, &write, &sa, 0)) return -1;
  SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0);
  STARTUPINFOW si{sizeof si};
  si.dwFlags = STARTF_USESTDHANDLES;
  si.hStdOutput = write;
  PROCESS_INFORMATION pi{};
  BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
  CloseHandle(write);
  out.clear();
  DWORD code = 1;
  if (ok) {
    char b[4096];
    DWORD n = 0;
    while (ReadFile(read, b, sizeof b, &n, nullptr) && n) out.append(b, n);
    WaitForSingleObject(pi.hProcess, 150000);
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
  }
  CloseHandle(read);
  return ok ? (int)code : -1;
}

static void checkAppUpdate() {
  if (g_private || !g_torCc.empty() || g_secondary) return;
#ifdef SF_TEST
  wchar_t shotBuf[4] = {};
  if (GetEnvironmentVariableW(L"SF_SHOT", shotBuf, 4)) return;
#endif
  wstring dataDir = g_data;
  std::thread([dataDir] {
    string body;
    if (runCurl(L"-fsSL -m 15 -H \"User-Agent: SpeedForce\" \"https://api.github.com/repos/theGodspeed1999/Speed-Force/releases/latest\"", body) != 0) return;
    json j = json::parse(body, nullptr, false);
    if (!j.is_object()) return;
    string tag = j.value("tag_name", "");
    if (!isNewerVersion(tag, SF_VERSION)) return;
    string ver = (!tag.empty() && (tag[0] == 'v' || tag[0] == 'V')) ? tag.substr(1) : tag;
    string dlUrl;
    if (j.contains("assets") && j["assets"].is_array()) {
      for (auto& a : j["assets"]) if (a.value("name", "") == "SpeedForce-Setup.exe") { dlUrl = a.value("browser_download_url", ""); break; }
    }
    if (dlUrl.empty()) dlUrl = "https://github.com/theGodspeed1999/Speed-Force/releases/latest/download/SpeedForce-Setup.exe";
    wstring setup = dataDir + L"\\SpeedForce-Setup.exe", tmp = setup + L".tmp";
    DeleteFileW(tmp.c_str());
    string dummy;
    if (runCurl(L"-fsSL -L -m 120 -H \"User-Agent: SpeedForce\" -o \"" + tmp + L"\" \"" + W(dlUrl) + L"\"", dummy) != 0) { DeleteFileW(tmp.c_str()); return; }
    if (!MoveFileExW(tmp.c_str(), setup.c_str(), MOVEFILE_REPLACE_EXISTING)) { DeleteFileW(tmp.c_str()); return; }
    postUi([ver, setup] {
      notice("Speed Force " + ver + " is ready.", {{"label", "Update & restart"}, {"updateSetup", U8(setup)}});
    });
  }).detach();
}

static void closeTab(Tab* t) {
  guardTabClosed(t);
  if (isHttp(t->url) && inList("forgetSites", hostOf(t->url))) {  // "Forget me when I close this site"
    bool other = false;
    for (auto& x : g_tabs) if (x.get() != t && hostOf(x->url) == hostOf(t->url)) other = true;
    if (!other) {
      if (t->wv) t->wv->ExecuteScript(L"try{localStorage.clear();sessionStorage.clear()}catch(e){}", nullptr);
      siteCookies(t->url, true, [](int) {});
    }
  }
  size_t i = 0;
  for (; i < g_tabs.size(); i++) if (g_tabs[i].get() == t) break;
  if (i == g_tabs.size()) return;
  if (isHttp(t->url) && !isLensPage(t->lens, t->url)) g_closed.push_back(t->url);
  if (t->lens) forgetLensTraces();
  t->closing = true;
  if (t->ctl) t->ctl->Close();
  bool wasCur = g_cur == t;
  g_tabs.erase(g_tabs.begin() + i);
  if (g_tabs.empty()) { g_cur = nullptr; newTab(g_settings.value("home", string(kStartUrl))); }
  else if (wasCur) selectTab(g_tabs[std::min(i, g_tabs.size() - 1)].get());
  else pushState();
}

static void addStream(Tab* t, const string& url, const string& kind, const string& referer) {
  string key = dedupeKey(url);
  if (t->seen.count(key)) return;
  t->seen.insert(key);
  t->streams.insert(t->streams.begin(), Stream{url, kind, referer.empty() ? t->url : referer, t->url, nowMs()});
  if (t->streams.size() > 40) t->streams.pop_back();
  pushState();
}
static void resetPage(Tab* t) {
  t->seen.clear(); t->frameVideos.clear(); t->streams.clear();
  t->videoCount = 0; t->blocked = 0; t->hidden = 0;
}

static void goBack(Tab* t) {
  t->lastBack = nowMs();
  if (t->wv) t->wv->GoBack();
}

// ---------------------------------------------------------------- picker request (the page logic is in ui\ui.js)
static void openPickerFor(Tab* t, const json& req) {
  json r = req;
  r["pageUrl"] = t->url;
  json streams = json::array();
  for (auto& s : t->streams) streams.push_back({{"url", s.url}, {"kind", s.kind}, {"referer", s.referer}, {"at", (int64_t)s.at}});
  r["streams"] = streams;
  r["title"] = r.value("title", t->title);
  sendUi("pick-request", r);
}

// ---------------------------------------------------------------- downloads
static void persistDownloads() {
  json a = json::array();
  for (auto& d : g_dl) { json j = d.j; a.push_back(j); }
  writeFile(g_data + L"\\downloads.json", a.dump());
}
static DownloadItem* findDl(const string& id) { for (auto& d : g_dl) if (d.j.value("id", "") == id) return &d; return nullptr; }
static bool g_dlPending = false;
static void sendDownloads() {
  if (g_dlPending) return;
  g_dlPending = true;
  SetTimer(g_hwnd, 2, 250, nullptr);
}
static void doSendDownloads() {
  g_dlPending = false;
  json a = json::array();
  for (auto& d : g_dl) if (d.j.value("status", "") != "REMOVED") a.push_back(d.j);
  sendUi("downloads", a);
}
static string humanSize(double b) {
  if (!(b > 0)) return "";
  const char* u[] = {"B", "KB", "MB", "GB", "TB"};
  int i = 0;
  while (b >= 1024 && i < 4) { b /= 1024; i++; }
  char buf[64];
  if (i <= 1) snprintf(buf, sizeof buf, "%.0f %s", b, u[i]); else snprintf(buf, sizeof buf, "%.1f %s", b, u[i]);
  return buf;
}
static string randomId() {
  static std::atomic<int> n{0};
  char b[32]; snprintf(b, sizeof b, "%llx%x", (unsigned long long)GetTickCount64(), ++n);
  return b;
}
static wstring uniquePath(const wstring& dir, const wstring& name) {
  size_t dot = name.find_last_of(L'.');
  wstring base = dot == wstring::npos ? name : name.substr(0, dot), ext = dot == wstring::npos ? L"" : name.substr(dot);
  wstring p = dir + L"\\" + name;
  for (int n = 2; fileExists(p); n++) p = dir + L"\\" + base + L" (" + std::to_wstring(n) + L")" + ext;
  return p;
}
static string shortError(const string& text) {
  string line;
  for (auto& l : split(text, '\n')) if (l.find("ERROR") != string::npos) { line = l; break; }
  if (line.empty()) for (auto& l : split(text, '\n')) if (!trim(l).empty()) { line = l; break; }
  if (line.empty()) line = text;
  size_t e = line.find("ERROR:");
  if (e != string::npos) line = line.substr(e + 6);
  line = trim(line);
  return line.substr(0, 300);
}

struct RunResult { int code = -1; string out, err; };
/** Runs a program with hidden window; [onLine] gets each output line (on the UI thread); [done] gets the result (UI thread). */
static void runProcess(const wstring& cmdline, std::vector<std::pair<wstring, wstring>> env, std::function<void(const string&)> onLine,
                       std::function<void(RunResult)> done, std::function<void(HANDLE, HANDLE)> started = nullptr) {
  if (!g_torCc.empty()) {  // the download engine connects by itself, which would go around Tor
    postUi([done] { RunResult r; r.err = "Downloads are switched off in a Tor window."; done(r); });
    return;
  }
  std::thread([=]() {
    RunResult rr;
    SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
    HANDLE outR, outW, errR, errW;
    CreatePipe(&outR, &outW, &sa, 0); SetHandleInformation(outR, HANDLE_FLAG_INHERIT, 0);
    CreatePipe(&errR, &errW, &sa, 0); SetHandleInformation(errR, HANDLE_FLAG_INHERIT, 0);
    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION li{};
    li.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    SetInformationJobObject(job, JobObjectExtendedLimitInformation, &li, sizeof li);
    wstring envBlock;
    {
      LPWCH cur = GetEnvironmentStringsW();
      for (LPWCH p = cur; *p;) { size_t n = wcslen(p); envBlock.append(p, n + 1); p += n + 1; }
      FreeEnvironmentStringsW(cur);
      for (auto& [k, v] : env) envBlock += k + L"=" + v + L'\0';
      envBlock += L'\0';
    }
    STARTUPINFOW si{sizeof si};
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    si.hStdOutput = outW; si.hStdError = errW; si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    PROCESS_INFORMATION pi{};
    wstring cl = cmdline;
    BOOL ok = CreateProcessW(nullptr, cl.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | CREATE_SUSPENDED | CREATE_BREAKAWAY_FROM_JOB, (LPVOID)envBlock.data(), nullptr, &si, &pi);
    if (!ok) ok = CreateProcessW(nullptr, cl.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT | CREATE_SUSPENDED, (LPVOID)envBlock.data(), nullptr, &si, &pi);
    CloseHandle(outW); CloseHandle(errW);
    if (!ok) { rr.err = "Could not start the download engine"; CloseHandle(outR); CloseHandle(errR); CloseHandle(job); postUi([=]() { done(rr); }); return; }
    AssignProcessToJobObject(job, pi.hProcess);
    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    if (started) { HANDLE p2 = pi.hProcess, j2 = job; postUi([=]() { started(p2, j2); }); }
    std::thread errT([&]() { char b[4096]; DWORD n; while (ReadFile(errR, b, sizeof b, &n, nullptr) && n) { rr.err.append(b, n); if (rr.err.size() > 20000) rr.err.erase(0, rr.err.size() - 20000); } });
    string buf;
    char b[8192]; DWORD n;
    while (ReadFile(outR, b, sizeof b, &n, nullptr) && n) {
      rr.out.append(b, n);
      if (rr.out.size() > 64000000) rr.out.erase(0, 32000000);
      if (onLine) {
        buf.append(b, n);
        size_t pos;
        while ((pos = buf.find('\n')) != string::npos) {
          string line = buf.substr(0, pos);
          if (!line.empty() && line.back() == '\r') line.pop_back();
          buf.erase(0, pos + 1);
          postUi([=]() { onLine(line); });
        }
      }
    }
    errT.join();
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1; GetExitCodeProcess(pi.hProcess, &code);
    rr.code = (int)code;
    CloseHandle(pi.hProcess); CloseHandle(outR); CloseHandle(errR); CloseHandle(job);
    postUi([=]() { done(rr); });
  }).detach();
}

static wstring quoteArg(const wstring& a) {
  if (!a.empty() && a.find_first_of(L" \t\"") == wstring::npos) return a;
  wstring r = L"\"";
  for (size_t i = 0; i < a.size(); i++) {
    size_t bs = 0;
    while (i < a.size() && a[i] == L'\\') { bs++; i++; }
    if (i == a.size()) { r.append(bs * 2, L'\\'); break; }
    if (a[i] == L'"') { r.append(bs * 2 + 1, L'\\'); r += L'"'; }
    else { r.append(bs, L'\\'); r += a[i]; }
  }
  return r + L"\"";
}
static wstring ytCommand(const std::vector<string>& args) {
  wstring c = quoteArg(g_engine + L"\\yt-dlp.exe");
  for (auto& a : args) c += L" " + quoteArg(W(a));
  return c;
}
static std::vector<std::pair<wstring, wstring>> ytEnv() {
  wstring tmp = g_data + L"\\tmp";
  makeDirs(tmp);
  return {{L"PYTHONUTF8", L"1"}, {L"TMP", tmp}, {L"TEMP", tmp}};
}
static std::vector<string> commonArgs() {
  return {"--no-warnings", "--ffmpeg-location", U8(g_engine), "--cache-dir", U8(g_data + L"\\ytdlp-cache"), "--socket-timeout", "30"};
}

/** Cookies of the given pages, written in the cookies.txt format yt-dlp reads; the file name is passed to [done] ("" = none). */
static void makeCookieFile(const std::vector<string>& urls, const string& name, std::function<void(wstring)> done) {
  auto lines = std::make_shared<std::vector<string>>();
  auto seen = std::make_shared<std::set<string>>();
  auto idx = std::make_shared<size_t>(0);
  auto list = std::make_shared<std::vector<string>>();
  for (auto& u : urls) if (isHttp(u)) list->push_back(u);
  auto step = std::make_shared<std::function<void()>>();
  Com<ICoreWebView2> any = g_ui_wv;
  auto w2 = any.as<ICoreWebView2_2>(IID_ICoreWebView2_2);
  *step = [=]() {
    if (!w2 || *idx >= list->size()) {
      if (lines->empty()) { done(L""); }
      else {
        wstring f = g_data + L"\\tmp\\" + W(name) + L".cookies.txt";
        makeDirs(g_data + L"\\tmp");
        string text = "# Netscape HTTP Cookie File\n";
        for (auto& l : *lines) text += l + "\n";
        writeFile(f, text);
        done(f);
      }
      auto keep = step;
      postUi([keep]() { *keep = nullptr; });  // breaks the loop of the function holding itself, after it has returned
      return;
    }
    Com<ICoreWebView2CookieManager> cm;
    w2->get_CookieManager(cm.put());
    string u = (*list)[(*idx)++];
    cm->GetCookies(W(u).c_str(), H2(ICoreWebView2GetCookiesCompletedHandler, HRESULT, ICoreWebView2CookieList*, ([=](HRESULT, ICoreWebView2CookieList* cl) -> HRESULT {
      UINT n = 0;
      if (cl) cl->get_Count(&n);
      for (UINT i = 0; i < n; i++) {
        Com<ICoreWebView2Cookie> c;
        cl->GetValueAtIndex(i, c.put());
        CoStr nm, val, dom, path;
        c->get_Name(nm.put()); c->get_Value(val.put()); c->get_Domain(dom.put()); c->get_Path(path.put());
        BOOL sec = FALSE, sess = TRUE; double exp = 0;
        c->get_IsSecure(&sec); c->get_IsSession(&sess); c->get_Expires(&exp);
        string d = dom.str(), key = d + path.str() + nm.str();
        if (seen->count(key)) continue;
        seen->insert(key);
        bool hostOnly = !startsWith(d, ".");
        string line = (hostOnly ? d : d) + "\t" + (hostOnly ? "FALSE" : "TRUE") + "\t" + (path.str().empty() ? "/" : path.str()) + "\t" + (sec ? "TRUE" : "FALSE") + "\t" +
                      std::to_string(sess ? 2147483647LL : (long long)exp) + "\t" + nm.str() + "\t" + val.str();
        lines->push_back(line);
      }
      (*step)();
      return S_OK;
    })));
  };
  (*step)();
}

static void updateDl(const string& id, const json& change, bool save = false) {
  if (auto* d = findDl(id)) { for (auto it = change.begin(); it != change.end(); ++it) d->j[it.key()] = it.value(); }
  sendDownloads();
  if (save) persistDownloads();
}
static void pumpDownloads();

static void startVideoDownload(const string& id) {
  DownloadItem* d = findDl(id);
  if (!d) return;
  json item = d->j;
  std::vector<string> urls;
  for (auto& u : item["ctx"].value("cookieUrls", json::array())) urls.push_back(u.get<string>());
  makeCookieFile(urls, id, [=](wstring cookies) {
    wstring dir = g_data + L"\\work\\" + W(id);
    makeDirs(dir);
    std::vector<string> args = {"-f", item.value("format", "b"), "-o", U8(dir) + "\\%(title).80B.%(ext)s", "--playlist-items", std::to_string(item.value("entry", 1)),
                                "--no-mtime", "--newline", "--concurrent-fragments", "4", "--retries", "10", "--fragment-retries", "10"};
    if (item.value("audioOnly", false)) { args.push_back("-x"); args.push_back("--audio-format"); args.push_back("m4a"); }
    else { args.push_back("--merge-output-format"); args.push_back("mp4"); }
    for (auto& a : commonArgs()) args.push_back(a);
    json ctx = item["ctx"];
    args.push_back("--user-agent"); args.push_back(jstr(ctx, "userAgent"));
    if (!jstr(ctx, "referer").empty()) { args.push_back("--referer"); args.push_back(ctx["referer"]); }
    if (!jstr(ctx, "origin").empty()) { args.push_back("--add-header"); args.push_back("Origin:" + jstr(ctx, "origin")); }
    if (!cookies.empty()) { args.push_back("--cookies"); args.push_back(U8(cookies)); }
    args.push_back("--"); args.push_back(item.value("url", ""));
    auto last = std::make_shared<ULONGLONG>(0);
    runProcess(ytCommand(args), ytEnv(),
      [=](const string& line) {
        DownloadItem* c = findDl(id);
        if (!c || c->j.value("status", "") != "RUNNING") return;
        string stage;
        if (line.find("[Merger]") != string::npos) stage = "Joining video + sound…";
        else if (line.find("[ExtractAudio]") != string::npos) stage = "Converting audio…";
        else if (line.find("[Fixup") != string::npos) stage = "Fixing file…";
        static const std::regex pr(R"(\[download\]\s+([\d.]+)%)"), sp(R"(at\s+([\d.]+\s*[KMGT]?i?B/s))"), tot(R"(of\s+~?\s*([\d.]+\s*[KMGT]?i?B))"), eta(R"(ETA\s+([\d:]+))");
        std::smatch m;
        json ch;
        if (line.find("[download]") != string::npos) {
          if (std::regex_search(line, m, pr)) ch["percent"] = atof(m[1].str().c_str());
          if (std::regex_search(line, m, sp)) { string s = m[1]; s.erase(std::remove(s.begin(), s.end(), ' '), s.end()); ch["speed"] = s; }
          if (std::regex_search(line, m, tot)) { string s = m[1]; s.erase(std::remove(s.begin(), s.end(), ' '), s.end()); ch["size"] = s; }
          if (std::regex_search(line, m, eta)) ch["eta"] = m[1].str();
        }
        ULONGLONG now = nowMs();
        if (stage.empty() && (ch.empty() || now - *last < 400)) return;
        *last = now;
        ch["stage"] = stage;
        updateDl(id, ch);
      },
      [=](RunResult rr) {
        if (!cookies.empty()) DeleteFileW(cookies.c_str());
        DownloadItem* c = findDl(id);
        if (c) { c->proc = nullptr; c->job = nullptr; }
        if (!c) { return; }
        string status = c->j.value("status", "");
        if (rr.code == 0) {
          // newest biggest media file in the work folder
          wstring best; ULONGLONG bestSize = 0;
          WIN32_FIND_DATAW fd;
          HANDLE h = FindFirstFileW((dir + L"\\*").c_str(), &fd);
          if (h != INVALID_HANDLE_VALUE) {
            do {
              if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
              wstring n = fd.cFileName;
              if (n.size() > 5 && (n.rfind(L".part") == n.size() - 5 || n.rfind(L".ytdl") == n.size() - 5 || n.rfind(L".json") == n.size() - 5)) continue;
              if (n.find(L".temp") != wstring::npos || n.find(L"cookies.txt") != wstring::npos) continue;
              ULONGLONG sz = ((ULONGLONG)fd.nFileSizeHigh << 32) | fd.nFileSizeLow;
              if (sz >= bestSize) { bestSize = sz; best = dir + L"\\" + n; }
            } while (FindNextFileW(h, &fd));
            FindClose(h);
          }
          if (best.empty()) { updateDl(id, {{"status", "FAILED"}, {"error", "Download finished but no file was produced"}, {"speed", ""}, {"eta", ""}, {"stage", ""}}, true); pumpDownloads(); return; }
          size_t dot = best.find_last_of(L'.');
          if (!isMediaExt(dot == wstring::npos ? "" : U8(best.substr(dot)))) {
            updateDl(id, {{"status", "FAILED"}, {"error", "Blocked for safety: not a video or audio file"}, {"speed", ""}, {"eta", ""}, {"stage", ""}}, true);
            pumpDownloads(); return;
          }
          makeDirs(saveFolder());
          wstring name = best.substr(best.find_last_of(L'\\') + 1);
          wstring target = uniquePath(saveFolder(), name);
          if (!MoveFileExW(best.c_str(), target.c_str(), MOVEFILE_COPY_ALLOWED)) { updateDl(id, {{"status", "FAILED"}, {"error", "Could not save the file"}}, true); pumpDownloads(); return; }
          updateDl(id, {{"status", "DONE"}, {"file", U8(target)}, {"stage", ""}, {"speed", ""}, {"eta", ""}, {"size", humanSize((double)bestSize)}, {"percent", 100}}, true);
          RemoveDirectoryW(dir.c_str());
          FlashWindow(g_hwnd, TRUE);
        } else if (status == "PAUSED") {
          updateDl(id, {{"speed", ""}, {"eta", ""}, {"stage", ""}}, true);
        } else if (status == "CANCELED") {
          persistDownloads();
        } else if (rr.err.find("403") != string::npos && (!jstr(c->j["ctx"], "referer").empty() || !jstr(c->j["ctx"], "origin").empty())) {
          // some servers refuse requests that say which page you came from: try again without
          json ctx = c->j["ctx"]; ctx["referer"] = ""; ctx["origin"] = "";
          updateDl(id, {{"status", "QUEUED"}, {"ctx", ctx}, {"stage", "Retrying…"}}, true);
        } else {
          updateDl(id, {{"status", "FAILED"}, {"error", shortError(rr.err.empty() ? rr.out.substr(rr.out.size() > 2000 ? rr.out.size() - 2000 : 0) : rr.err)}, {"speed", ""}, {"eta", ""}, {"stage", ""}}, true);
        }
        pumpDownloads();
      },
      [=](HANDLE p, HANDLE j) { if (auto* c = findDl(id)) { c->proc = p; c->job = j; } });
  });
}

static void pumpDownloads() {
  int running = 0;
  for (auto& d : g_dl) if (d.j.value("status", "") == "RUNNING" && !d.op) running++;
  int free = g_settings.value("parallel", 2) - running;
  for (size_t i = g_dl.size(); i-- > 0 && free > 0;) {  // oldest first
    auto& d = g_dl[i];
    if (d.j.value("status", "") == "QUEUED" && d.j.value("kind", "video") == "video") {
      string id = d.j["id"];
      d.j["status"] = "RUNNING"; d.j["error"] = ""; d.j["stage"] = "Starting…";
      free--;
      startVideoDownload(id);
    }
  }
  sendDownloads();
}

static void killDl(DownloadItem* d) {
  if (d->job) TerminateJobObject(d->job, 1);
  else if (d->proc) TerminateProcess(d->proc, 1);
}

static string enqueueVideo(const json& a) {
  json j = {{"id", randomId()}, {"kind", "video"}, {"url", a.value("url", "")}, {"title", a.value("title", a.value("url", ""))}, {"format", a.value("format", "b")},
            {"formatLabel", a.value("label", "")}, {"audioOnly", a.value("audioOnly", false)}, {"entry", a.value("entry", 1)}, {"ctx", a["ctx"]},
            {"status", "QUEUED"}, {"percent", 0}, {"speed", ""}, {"eta", ""}, {"size", ""}, {"stage", ""}, {"error", ""}, {"file", ""}, {"added", unixMs()}};
  DownloadItem d; d.j = j;
  g_dl.insert(g_dl.begin(), d);
  persistDownloads();
  pumpDownloads();
  return j["id"];
}

static void dlAction(const string& action, const string& id) {
  if (action == "clearFinished") {
    for (auto& d : g_dl) { string s = d.j.value("status", ""); if (s == "DONE" || s == "FAILED" || s == "CANCELED") d.j["status"] = "REMOVED"; }
    g_dl.erase(std::remove_if(g_dl.begin(), g_dl.end(), [](DownloadItem& d) { return d.j.value("status", "") == "REMOVED"; }), g_dl.end());
    persistDownloads(); sendDownloads(); return;
  }
  DownloadItem* d = findDl(id);
  if (!d) return;
  string s = d->j.value("status", "");
  bool isFile = d->j.value("kind", "video") == "file";
  if (action == "pause" && (s == "RUNNING" || s == "QUEUED")) {
    updateDl(id, {{"status", "PAUSED"}, {"speed", ""}, {"eta", ""}}, true);
    if (isFile && d->op) d->op->Pause(); else killDl(d);
  } else if (action == "resume" && s == "PAUSED") {
    if (isFile && d->op) { d->op->Resume(); updateDl(id, {{"status", "RUNNING"}}); }
    else { updateDl(id, {{"status", "QUEUED"}, {"error", ""}}, true); pumpDownloads(); }
  } else if (action == "retry" && !isFile) {
    wstring dir = g_data + L"\\work\\" + W(id);
    if (!d->proc) { string cmd = "rmdir /s /q"; SHFILEOPSTRUCTW op{}; wstring p = dir + L'\0'; op.wFunc = FO_DELETE; op.pFrom = p.c_str(); op.fFlags = FOF_NO_UI | FOF_SILENT | FOF_NOCONFIRMATION; SHFileOperationW(&op); }
    updateDl(id, {{"status", "QUEUED"}, {"error", ""}, {"percent", 0}}, true); pumpDownloads();
  } else if (action == "cancel" && (s == "RUNNING" || s == "QUEUED" || s == "PAUSED")) {
    updateDl(id, {{"status", "CANCELED"}, {"speed", ""}, {"eta", ""}, {"stage", ""}}, true);
    if (isFile && d->op) d->op->Cancel(); else killDl(d);
  } else if (action == "remove") {
    if (s == "RUNNING" || s == "QUEUED" || s == "PAUSED") { if (isFile && d->op) d->op->Cancel(); else killDl(d); }
    g_dl.erase(std::remove_if(g_dl.begin(), g_dl.end(), [&](DownloadItem& x) { return x.j.value("id", "") == id; }), g_dl.end());
    persistDownloads(); sendDownloads();
  }
}

/** A file the page offered (any type): saved to the Files folder and listed with the videos. */
static void onDownloadStarting(Tab* t, ICoreWebView2DownloadStartingEventArgs* args) {
  if (!g_torCc.empty()) { args->put_Cancel(TRUE); args->put_Handled(TRUE); notice("Downloads are switched off in a Tor window."); return; }
  Com<ICoreWebView2DownloadOperation> op;
  args->get_DownloadOperation(op.put());
  CoStr uri, pathOld;
  op->get_Uri(uri.put());
  args->get_ResultFilePath(pathOld.put());
  wstring oldPath = pathOld.w ? pathOld.w : L"";
  wstring name = oldPath.substr(oldPath.find_last_of(L"\\/") == wstring::npos ? 0 : oldPath.find_last_of(L"\\/") + 1);
  if (name.empty()) name = L"download";
  // A download the page starts by itself (no click or key press in the last 8 seconds) is refused
  if (nowMs() - t->lastGesture > 8000 && mode() != "OFF") {
    args->put_Cancel(TRUE);
    notice("Refused a download the page started by itself: " + U8(name));
    return;
  }
  size_t dot = name.find_last_of(L'.');
  bool media = dot != wstring::npos && isMediaExt(U8(name.substr(dot)));
  wstring folder = media ? saveFolder() : filesFolder();
  makeDirs(folder);
  wstring target = uniquePath(folder, name);
  args->put_ResultFilePath(target.c_str());
  args->put_Handled(TRUE);
  string id = "f" + randomId();
  json j = {{"id", id}, {"kind", "file"}, {"title", U8(name)}, {"formatLabel", "File"}, {"status", "RUNNING"}, {"percent", 0}, {"speed", ""}, {"eta", ""}, {"size", ""},
            {"stage", ""}, {"error", ""}, {"file", U8(target)}, {"added", unixMs()}, {"url", U8(uri.w)}};
  DownloadItem d; d.j = j; d.op = op;
  g_dl.insert(g_dl.begin(), d);
  auto last = std::make_shared<std::pair<ULONGLONG, INT64>>(nowMs(), 0);
  EventRegistrationToken tk1{}, tk2{};
  op->add_BytesReceivedChanged(H2(ICoreWebView2BytesReceivedChangedEventHandler, ICoreWebView2DownloadOperation*, IUnknown*, ([id, last](ICoreWebView2DownloadOperation* o, IUnknown*) -> HRESULT {
    INT64 got = 0, total = 0;
    o->get_BytesReceived(&got); o->get_TotalBytesToReceive(&total);
    ULONGLONG now = nowMs();
    json ch = {{"percent", total > 0 ? got * 100.0 / total : 0.0}, {"size", humanSize((double)(total > 0 ? total : got))}};
    if (now - last->first >= 500) {
      double rate = (got - last->second) * 1000.0 / (double)(now - last->first);
      ch["speed"] = humanSize(rate) + "/s";
      if (total > 0 && rate > 1) { long long s = (long long)((total - got) / rate); char b[32]; snprintf(b, sizeof b, "%lld:%02lld", s / 60, s % 60); ch["eta"] = b; }
      last->first = now; last->second = got;
    }
    updateDl(id, ch);
    return S_OK;
  })), &tk1);
  op->add_StateChanged(H2(ICoreWebView2StateChangedEventHandler, ICoreWebView2DownloadOperation*, IUnknown*, ([id, name, target](ICoreWebView2DownloadOperation* o, IUnknown*) -> HRESULT {
    COREWEBVIEW2_DOWNLOAD_STATE st;
    o->get_State(&st);
    if (st == COREWEBVIEW2_DOWNLOAD_STATE_COMPLETED) {
      INT64 got = 0; o->get_BytesReceived(&got);
      updateDl(id, {{"status", "DONE"}, {"percent", 100}, {"speed", ""}, {"eta", ""}, {"size", humanSize((double)got)}}, true);
      if (auto* d = findDl(id)) d->op = Com<ICoreWebView2DownloadOperation>();
      notice("Saved " + U8(name), {{"label", "Show"}, {"show", U8(target)}});
      FlashWindow(g_hwnd, TRUE);
    } else if (st == COREWEBVIEW2_DOWNLOAD_STATE_INTERRUPTED) {
      auto* d = findDl(id);
      string cur = d ? d->j.value("status", "") : "";
      if (cur == "CANCELED") { if (d) d->op = Com<ICoreWebView2DownloadOperation>(); persistDownloads(); }
      else { updateDl(id, {{"status", "FAILED"}, {"error", "Download interrupted"}, {"speed", ""}, {"eta", ""}}, true); if (d) d->op = Com<ICoreWebView2DownloadOperation>(); }
    }
    return S_OK;
  })), &tk2);
  sendDownloads();
}

// ---------------------------------------------------------------- web view events of a tab
static void wireTab(Tab* t) {
  int id = t->id;
  ICoreWebView2* wv = t->wv.p;
  // settings
  Com<ICoreWebView2Settings> st;
  wv->get_Settings(st.put());
  st->put_IsStatusBarEnabled(FALSE);
  st->put_IsZoomControlEnabled(TRUE);
  { BOOL on = g_settings.value("savePasswords", false) && !g_private; if (auto s4 = st.as<ICoreWebView2Settings4>(IID_ICoreWebView2Settings4)) { s4->put_IsPasswordAutosaveEnabled(on); s4->put_IsGeneralAutofillEnabled(on); } }
  if (auto s3 = st.as<ICoreWebView2Settings3>(IID_ICoreWebView2Settings3)) s3->put_AreBrowserAcceleratorKeysEnabled(TRUE);
  if (auto w13 = t->wv.as<ICoreWebView2_13>(IID_ICoreWebView2_13)) {
    Com<ICoreWebView2Profile> pr;
    w13->get_Profile(pr.put());
    string th = g_settings.value("theme", "SYSTEM");
    pr->put_PreferredColorScheme(th == "LIGHT" ? COREWEBVIEW2_PREFERRED_COLOR_SCHEME_LIGHT : th == "DARK" ? COREWEBVIEW2_PREFERRED_COLOR_SCHEME_DARK : COREWEBVIEW2_PREFERRED_COLOR_SCHEME_AUTO);
    static bool extLoaded = false;  // extensions belong to the profile: load them once
    if (!extLoaded && !g_private) {
      extLoaded = true;
      if (auto p7 = pr.as<ICoreWebView2Profile7>(IID_ICoreWebView2Profile7))
        for (auto& e : g_settings.value("extensions", json::array()))
          p7->AddBrowserExtension(W(e.get<string>()).c_str(), H2(ICoreWebView2ProfileAddBrowserExtensionCompletedHandler, HRESULT, ICoreWebView2BrowserExtension*, ([](HRESULT, ICoreWebView2BrowserExtension*) -> HRESULT { return S_OK; })));
    }
  }
  EventRegistrationToken tk;
  // sites may not use camera, microphone, location, notifications ...: always refused
  wv->add_PermissionRequested(H2(ICoreWebView2PermissionRequestedEventHandler, ICoreWebView2*, ICoreWebView2PermissionRequestedEventArgs*, ([](ICoreWebView2*, ICoreWebView2PermissionRequestedEventArgs* a) -> HRESULT {
    a->put_State(COREWEBVIEW2_PERMISSION_STATE_DENY);
    return S_OK;
  })), &tk);
  // script for every page and frame
  addPageScript(t);
  applyFingerprint(t);
  wv->AddWebResourceRequestedFilter(L"*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
  // every request: ads and trackers are stopped here; video streams are noted
  wv->add_WebResourceRequested(H2(ICoreWebView2WebResourceRequestedEventHandler, ICoreWebView2*, ICoreWebView2WebResourceRequestedEventArgs*, ([id](ICoreWebView2*, ICoreWebView2WebResourceRequestedEventArgs* a) -> HRESULT {
    Tab* t = tabById(id);
    if (!t) return S_OK;
    Com<ICoreWebView2WebResourceRequest> rq;
    a->get_Request(rq.put());
    CoStr uri;
    rq->get_Uri(uri.put());
    string u = U8(uri.w);
    if (!isHttp(u)) return S_OK;
    bool lensPage = startsWith(u, kLensUrl);
    bool diagPage = hostOf(u) == "sf.diag";
    if (hostOf(u) == "sf.start" || lensPage || diagPage) {  // our own pages: answered here from the assets folder (no network, never filtered)
      string path = u.substr(u.find("://") + 3);
      path = path.substr(path.find('/') == string::npos ? path.size() : path.find('/') + 1);
      path = path.substr(0, path.find_first_of("?#"));
      if (lensPage) path = u.substr(strlen(kLensUrl), u.find_first_of("?#") == string::npos ? string::npos : u.find_first_of("?#") - strlen(kLensUrl));
      if (path.empty()) path = "index.html";
      const char* type = path == "index.html" ? "text/html; charset=utf-8" : path == "start.js" || path == "lens.js" || path == "diag.js" ? "application/javascript" : path == "logo.png" ? "image/png" : nullptr;
      string body = type ? readFile(g_assets + (lensPage ? L"\\lens\\" : diagPage ? L"\\diag\\" : L"\\start\\") + W(path)) : "";
      IStream* ms = SHCreateMemStream((const BYTE*)body.data(), (UINT)body.size());
      Com<ICoreWebView2WebResourceResponse> rs;
      wstring hdr = L"Content-Type: " + W(type ? type : "text/plain") + L"\r\nCache-Control: no-store";
      g_env->CreateWebResourceResponse(type && !body.empty() ? ms : nullptr, type && !body.empty() ? 200 : 404, type && !body.empty() ? L"OK" : L"Not found", hdr.c_str(), rs.put());
      a->put_Response(rs.p);
      if (ms) ms->Release();
      return S_OK;
    }
    COREWEBVIEW2_WEB_RESOURCE_CONTEXT ctx;
    a->get_ResourceContext(&ctx);
    string c = shieldsDown(t) ? "" : classify(u);
    bool block = false;
    if (c == "TRACKER") { g_counts["trackers"] = g_counts["trackers"].get<int>() + 1; block = true; }
    else if (c == "AD" && mode() == "BLOCK" && hostOf(u) != "imasdk.googleapis.com") block = true;
    if (block) {
      bumpStats(ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_SCRIPT ? 30000 : ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_IMAGE ? 60000 : ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_MEDIA ? 400000 : 6000);
      t->blocked++;
      Com<ICoreWebView2WebResourceResponse> rs;
      // A script's own request (fetch, XHR, ping) must fail, not "succeed" with nothing: it is sent on to an
      // address that cannot exist, so the page gets a real network error and nothing leaves the PC.
      // Pictures, scripts and frames still get a quiet empty answer.
      bool fail = ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_FETCH || ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_XML_HTTP_REQUEST || ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_PING;
      g_env->CreateWebResourceResponse(nullptr, ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT ? 403 : fail ? 307 : 204, fail ? L"Temporary Redirect" : L"Blocked",
                                       fail ? L"Location: https://0.0.0.0:1/\r\nAccess-Control-Allow-Origin: *" : L"", rs.put());
      a->put_Response(rs.p);
      pushState();
      return S_OK;
    }
    if (ctx != COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT) {
      string kind = classifyStream(u);
      if (!kind.empty() && c != "AD") {
        CoStr ref; Com<ICoreWebView2HttpRequestHeaders> hd;
        rq->get_Headers(hd.put());
        LPWSTR r = nullptr;
        string referer;
        if (hd && SUCCEEDED(hd->GetHeader(L"Referer", &r)) && r) { referer = U8(r); CoTaskMemFree(r); }
        addStream(t, u, kind, referer);
      }
    }
    return S_OK;
  })), &t->resTok);
  // page navigation: scam jumps, tab-under ads, back-button traps
  wv->add_NavigationStarting(H2(ICoreWebView2NavigationStartingEventHandler, ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs*, ([id](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* a) -> HRESULT {
    Tab* t = tabById(id);
    if (!t) return S_OK;
    CoStr uri; a->get_Uri(uri.put());
    string u = U8(uri.w);
    string l = lower(u.substr(0, 8));
    if (!(startsWith(l, "http") || startsWith(l, "about:") || startsWith(l, "data:") || startsWith(l, "blob:") || startsWith(l, "file:"))) { a->put_Cancel(TRUE); return S_OK; }
    if (!isHttp(u)) return S_OK;
    if (!g_torCc.empty() && startsWith(u, "http:")) { a->put_Cancel(TRUE); notice("A Tor window opens secure (https) pages only: the exit could read and change anything else."); return S_OK; }
    t->lastNav = u;
    t->lastStatus = 0;
    if (t->wv) {  // "Block scripts" for this site (takes effect for the page now being opened)
      Com<ICoreWebView2Settings> st;
      t->wv->get_Settings(st.put());
      st->put_IsScriptEnabled(!inList("scriptsOff", hostOf(u)));
    }
    if (startsWith(u, kLensUrl)) { t->lens = true; if (!g_settings.value("lensDirty", false)) { g_settings["lensDirty"] = true; saveSettings(); } }
    if (isStart(u)) return S_OK;
    BOOL user = FALSE, redirected = FALSE;
    a->get_IsUserInitiated(&user);
    a->get_IsRedirected(&redirected);
    resetPage(t);
    if (shieldsDown(t)) { t->url = u; return S_OK; }
    string c = classify(u);
    if (c == "TRACKER") { a->put_Cancel(TRUE); return S_OK; }
    if (c == "AD") {
      a->put_Cancel(TRUE);
      g_counts["popups"] = g_counts["popups"].get<int>() + 1;
      addBillboard("Redirect", u, t->url);
      pushState();
      return S_OK;
    }
    ULONGLONG now = nowMs();
    if (mode() != "OFF" && isHttp(t->url) && !user && !redirected && !sameSite(u, t->url) && !isStart(t->url) && !startsWith(t->url, kLensUrl)) {
      bool afterBack = now - t->lastBack < 3000;
      bool whileReading = t->finished && now - t->finished > 3000;
      bool tabUnder = now - t->lastPopup < 2000;
      if (tabUnder || afterBack || whileReading) {
        a->put_Cancel(TRUE);
        g_counts["popups"] = g_counts["popups"].get<int>() + 1;
        addBillboard("Redirect", u, t->url);
        notice("Page jump to another site stopped", tabUnder ? json(nullptr) : json({{"label", "Open anyway"}, {"url", u}, {"tab", t->id}}));
        pushState();
        return S_OK;
      }
    }
    return S_OK;
  })), &tk);
  wv->add_NavigationCompleted(H2(ICoreWebView2NavigationCompletedEventHandler, ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs*, ([id](ICoreWebView2* w, ICoreWebView2NavigationCompletedEventArgs* a) -> HRESULT {
    Tab* t = tabById(id);
    if (!t) return S_OK;
    t->loading = false;
    if (!t->finished) t->finished = nowMs();
    BOOL ok = TRUE; a->get_IsSuccess(&ok);
    {
      ICoreWebView2NavigationCompletedEventArgs2* a2 = nullptr;
      if (SUCCEEDED(a->QueryInterface(IID_ICoreWebView2NavigationCompletedEventArgs2, (void**)&a2)) && a2) { a2->get_HttpStatusCode(&t->lastStatus); a2->Release(); }
    }
    if (!ok) {
      COREWEBVIEW2_WEB_ERROR_STATUS es; a->get_WebErrorStatus(&es);
      CoStr uri; w->get_Source(uri.put());
      string u = U8(uri.w);
      string h = hostOf(u);
      if (startsWith(u, "https:") && !h.empty() && es != COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED && es != COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED) {
        bool wasUpgraded = t->upgraded.erase(h) > 0;
        if (g_torCc.empty() && wasUpgraded && es != COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_IS_INVALID && es != COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_EXPIRED) {
          // we added the https ourselves and the site has no working https: use the page as the link first gave it, and say so
          g_httpAllowed.insert(h);
          notice(h + " has no working https, so this page opened over insecure http.", nullptr);
          t->wv->Navigate(W("http://" + u.substr(8)).c_str());
        } else if (es != COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_IS_INVALID && es != COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_EXPIRED) {
          notice("The secure (https) version of " + h + " did not work.", {{"label", "Open insecure http"}, {"http", "http://" + u.substr(8)}, {"tab", t->id}});
        }
        if (wasUpgraded && es != COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_IS_INVALID && es != COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_EXPIRED) { pushState(); return S_OK; }
      }
      // no page at all (the site sent no answer): say why, in plain words. Certificate warnings and the
      // site's own error pages are left as they are; "Why won't this site load?" in the menu covers those.
      if (t->lastStatus == 0 && isHttp(t->lastNav) && hostOf(t->lastNav).rfind("sf.", 0) != 0) {
        const char* kind = es == COREWEBVIEW2_WEB_ERROR_STATUS_HOST_NAME_NOT_RESOLVED ? "dns"
                         : es == COREWEBVIEW2_WEB_ERROR_STATUS_TIMEOUT ? "timeout"
                         : es == COREWEBVIEW2_WEB_ERROR_STATUS_DISCONNECTED ? "offline"
                         : es == COREWEBVIEW2_WEB_ERROR_STATUS_CANNOT_CONNECT || es == COREWEBVIEW2_WEB_ERROR_STATUS_SERVER_UNREACHABLE ||
                           es == COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_RESET || es == COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN ||
                           es == COREWEBVIEW2_WEB_ERROR_STATUS_ERROR_HTTP_INVALID_SERVER_RESPONSE ? "connect" : nullptr;
        if (kind && t->wv) t->wv->Navigate(W(diagUrl(t->lastNav, kind, 0)).c_str());
      }
    } else {
      CoStr title; w->get_DocumentTitle(title.put());
      t->title = title.w && *title.w ? title.str() : t->url;
      if (!isLensPage(t->lens, t->url)) addHistory(t->title, t->url);
      guardNote(t, t->url);
    }
    pushState();
    return S_OK;
  })), &tk);
  wv->add_NavigationStarting(H2(ICoreWebView2NavigationStartingEventHandler, ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs*, ([id](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs*) -> HRESULT {
    if (Tab* t = tabById(id)) { t->loading = true; t->finished = 0; pushState(); }
    return S_OK;
  })), &tk);
  wv->add_SourceChanged(H2(ICoreWebView2SourceChangedEventHandler, ICoreWebView2*, ICoreWebView2SourceChangedEventArgs*, ([id](ICoreWebView2* w, ICoreWebView2SourceChangedEventArgs*) -> HRESULT {
    if (Tab* t = tabById(id)) {
      CoStr uri; w->get_Source(uri.put());
      if (uri.w) t->url = U8(uri.w);
      pushState();
    }
    return S_OK;
  })), &tk);
  wv->add_HistoryChanged(H2(ICoreWebView2HistoryChangedEventHandler, ICoreWebView2*, IUnknown*, ([id](ICoreWebView2* w, IUnknown*) -> HRESULT {
    if (Tab* t = tabById(id)) {
      BOOL b = FALSE, f = FALSE; w->get_CanGoBack(&b); w->get_CanGoForward(&f);
      t->canBack = b; t->canFwd = f;
      pushState();
    }
    return S_OK;
  })), &tk);
  wv->add_DocumentTitleChanged(H2(ICoreWebView2DocumentTitleChangedEventHandler, ICoreWebView2*, IUnknown*, ([id](ICoreWebView2* w, IUnknown*) -> HRESULT {
    if (Tab* t = tabById(id)) { CoStr s; w->get_DocumentTitle(s.put()); t->title = s.str(); pushState(); }
    return S_OK;
  })), &tk);
  if (auto w15 = t->wv.as<ICoreWebView2_15>(IID_ICoreWebView2_15)) {
    w15->add_FaviconChanged(H2(ICoreWebView2FaviconChangedEventHandler, ICoreWebView2*, IUnknown*, ([id](ICoreWebView2* w, IUnknown*) -> HRESULT {
      if (Tab* t = tabById(id)) {
        auto w15 = Com<ICoreWebView2>(w).as<ICoreWebView2_15>(IID_ICoreWebView2_15);
        CoStr f; if (w15) w15->get_FaviconUri(f.put());
        t->favicon = f.str();
        pushState();
        // the engine fetches the icon itself (a site may refuse a second request); the tab strip gets the picture inline
        if (w15 && !t->favicon.empty()) w15->GetFavicon(COREWEBVIEW2_FAVICON_IMAGE_FORMAT_PNG, H2(ICoreWebView2GetFaviconCompletedHandler, HRESULT, IStream*, ([id](HRESULT hr, IStream* s) -> HRESULT {
          Tab* t = tabById(id);
          if (!t || FAILED(hr) || !s) return S_OK;
          string raw; char buf[4096]; ULONG n = 0;
          while (SUCCEEDED(s->Read(buf, sizeof buf, &n)) && n) { raw.append(buf, n); if (raw.size() > 200000) return S_OK; }
          if (raw.empty()) return S_OK;
          static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
          string out = "data:image/png;base64,";
          for (size_t i = 0; i < raw.size(); i += 3) {
            unsigned v = (unsigned char)raw[i] << 16 | (i + 1 < raw.size() ? (unsigned char)raw[i + 1] << 8 : 0) | (i + 2 < raw.size() ? (unsigned char)raw[i + 2] : 0);
            out += tbl[v >> 18 & 63]; out += tbl[v >> 12 & 63];
            out += i + 1 < raw.size() ? tbl[v >> 6 & 63] : '='; out += i + 2 < raw.size() ? tbl[v & 63] : '=';
          }
          t->favicon = out;
          pushState();
          return S_OK;
        })));
      }
      return S_OK;
    })), &tk);
  }
  // links that want a new window: ad popups are refused, clicked links open as tabs
  wv->add_NewWindowRequested(H2(ICoreWebView2NewWindowRequestedEventHandler, ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs*, ([id](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* a) -> HRESULT {
    Tab* t = tabById(id);
    if (!t) return S_OK;
    CoStr uri; a->get_Uri(uri.put());
    string u = U8(uri.w);
    BOOL user = FALSE; a->get_IsUserInitiated(&user);
    a->put_Handled(TRUE);
    t->lastPopup = nowMs();
    bool clicked = user || nowMs() - t->lastGesture < 1500;
    if (modeFor(t) == "OFF") { newTab(u); return S_OK; }
    string c = classify(u);
    if (c == "TRACKER") return S_OK;
    // a link the person clicked opens in a tab, on any site; only the ad lists and gesture-less script popups are held back
    if (c != "AD" && isHttp(u) && (user || (clicked && sameSite(u, t->url)))) { newTab(u); return S_OK; }
    g_counts["popups"] = g_counts["popups"].get<int>() + 1;
    addBillboard("Popup", u, t->url);
    notice(mode() == "BLOCK" ? "Popup blocked" : "Popup sent to Shields log", (c != "AD" && clicked && isHttp(u)) ? json({{"label", "Open in tab"}, {"url", u}}) : json(nullptr));
    pushState();
    return S_OK;
  })), &tk);
  if (auto w4 = t->wv.as<ICoreWebView2_4>(IID_ICoreWebView2_4)) {
    w4->add_DownloadStarting(H2(ICoreWebView2DownloadStartingEventHandler, ICoreWebView2*, ICoreWebView2DownloadStartingEventArgs*, ([id](ICoreWebView2*, ICoreWebView2DownloadStartingEventArgs* a) -> HRESULT {
      if (Tab* t = tabById(id)) onDownloadStarting(t, a);
      return S_OK;
    })), &tk);
  }
  wv->add_ContainsFullScreenElementChanged(H2(ICoreWebView2ContainsFullScreenElementChangedEventHandler, ICoreWebView2*, IUnknown*, ([id](ICoreWebView2* w, IUnknown*) -> HRESULT {
    BOOL b = FALSE; w->get_ContainsFullScreenElement(&b);
    setFullscreen(b, true);
    return S_OK;
  })), &tk);
  wv->add_ProcessFailed(H2(ICoreWebView2ProcessFailedEventHandler, ICoreWebView2*, ICoreWebView2ProcessFailedEventArgs*, ([id](ICoreWebView2* w, ICoreWebView2ProcessFailedEventArgs* a) -> HRESULT {
    COREWEBVIEW2_PROCESS_FAILED_KIND k; a->get_ProcessFailedKind(&k);
    if (k == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED || k == COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE)
      notice("This page crashed.", {{"label", "Reload"}, {"reload", id}});
    return S_OK;
  })), &tk);
  // messages from detect.js
  wv->add_WebMessageReceived(H2(ICoreWebView2WebMessageReceivedEventHandler, ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs*, ([id](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* a) -> HRESULT {
    Tab* t = tabById(id);
    if (!t) return S_OK;
    CoStr src; a->get_Source(src.put());
    string from = U8(src.w);
    LPWSTR raw = nullptr;
    if (FAILED(a->TryGetWebMessageAsString(&raw)) || !raw) {
      // the start page asks for its numbers as a JSON object
      CoStr js; a->get_WebMessageAsJson(js.put());
      auto q = json::parse(js.str(), nullptr, false);
      // only our own diagnostic page, shown as the tab itself, may start Tor
      if (q.is_object() && startsWith(from, kDiagUrl) && startsWith(t->url, kDiagUrl)) {
        string what = q.value("t", "");
        if (what == "torcheck") { torCheck(t->id, q.value("host", "")); return S_OK; }
        if (what == "toropen") { torOpen(q.value("cc", ""), q.value("url", "")); return S_OK; }
      }
      if (q.is_object() && q.value("t", "") == "startdata" && isStart(from) && isStart(t->url)) {
        json sites = json::array();
        std::map<string, std::pair<int, string>> cnt;
        for (auto& h : readList(L"history.json")) { string d = hostOf(h.value("u", "")); if (d.empty()) continue; auto& e = cnt[d]; e.first++; if (e.second.empty()) e.second = h.value("u", ""); }
        std::vector<std::pair<string, std::pair<int, string>>> v(cnt.begin(), cnt.end());
        std::sort(v.begin(), v.end(), [](auto& x, auto& y) { return x.second.first > y.second.first; });
        for (size_t i = 0; i < v.size() && i < 8; i++) sites.push_back({{"host", v[i].first}, {"url", v[i].second.second}});
        string base = searchEngines().at(g_settings.value("engine", "DuckDuckGo"));
        json d = {{"t", "startdata"}, {"stats", g_stats}, {"sites", sites}, {"base", base}};
        t->wv->PostWebMessageAsJson(W(d.dump()).c_str());
      }
      return S_OK;
    }
    string data = U8(raw);
    CoTaskMemFree(raw);
    if (data.size() > 200000) return S_OK;
    string fromHost = hostOf(from);
    if (g_adHosts.blocks(fromHost) && g_settings.value("adMode", "HIDE") != "OFF") return S_OK;  // ad frames cannot trigger anything
    auto o = json::parse(data, nullptr, false);
    if (!o.is_object()) return S_OK;
    string type = o.value("t", "");
    if (type == "gesture") { t->lastGesture = nowMs(); return S_OK; }
    if (type == "video") {
      ULONGLONG now = nowMs();
      if (now - t->lastVideoTap < 1500) return S_OK;
      t->lastVideoTap = now;
      if (t != g_cur) return S_OK;
      openPickerFor(t, {{"frameUrl", o.value("frame", "")}, {"directUrl", o.value("src", "")}, {"title", o.value("title", "")}});
    } else if (type == "videos") {
      t->frameVideos[o.value("frame", "")] = std::max(0, std::min(100, o.value("n", 0)));
      int sum = 0;
      for (auto& [k, n] : t->frameVideos) sum += n;
      t->videoCount = sum;
      pushState();
    } else if (type == "stream") {
      string u = o.value("url", "");
      string kind = classifyStream(u);
      if (!kind.empty() && !g_adHosts.blocks(hostOf(u))) addStream(t, u, kind, o.value("frame", ""));
    } else if (type == "hidden") {
      int n = std::max(0, std::min(500, o.value("n", 0)));
      g_counts["hidden"] = g_counts["hidden"].get<int>() + n;
      t->hidden += n;
      std::set<string> known;
      for (auto& b : g_billboard) if (b.value("kind", "") == "Hidden ad") known.insert(dedupeKey(b.value("url", "")));
      int added = 0;
      if (o.contains("frames") && o["frames"].is_array())
        for (auto& f : o["frames"]) {
          string u = f.get<string>();
          if (isHttp(u) && !known.count(dedupeKey(u)) && added++ < 50) { known.insert(dedupeKey(u)); addBillboard("Hidden ad", u, o.value("page", "")); }
        }
      pushState();
    }
    return S_OK;
  })), &t->msgTok);
  // keys
  t->ctl->add_AcceleratorKeyPressed(H2(ICoreWebView2AcceleratorKeyPressedEventHandler, ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs*, ([](ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs* a) -> HRESULT {
    COREWEBVIEW2_KEY_EVENT_KIND kind; UINT vk;
    a->get_KeyEventKind(&kind); a->get_VirtualKey(&vk);
    if (kind != COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN && kind != COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN) return S_OK;
    bool ctrl = GetKeyState(VK_CONTROL) < 0, shift = GetKeyState(VK_SHIFT) < 0, alt = GetKeyState(VK_MENU) < 0;
    string cmd;
    if (ctrl && !alt) {
      switch (vk) {
        case 'T': cmd = shift ? "reopen" : "newtab"; break;
        case 'W': cmd = "closetab"; break;
        case 'L': cmd = "address"; break;
        case 'F': cmd = "find"; break;
        case 'J': cmd = "downloads"; break;
        case 'H': cmd = "history"; break;
        case 'D': cmd = "bookmark"; break;
        case 'N': cmd = shift ? "newprivate" : "newwindow"; break;
        case VK_TAB: cmd = shift ? "prevtab" : "nexttab"; break;
      }
    } else if (vk == VK_F11) cmd = "fullscreen";
    else if (vk == VK_ESCAPE && g_fullscreen) cmd = "unfull";
    else if (alt && vk == VK_LEFT) cmd = "back";
    else if (alt && vk == VK_RIGHT) cmd = "forward";
    if (cmd.empty()) return S_OK;
    a->put_Handled(TRUE);
    PostMessageW(g_hwnd, WM_APP + 3, 0, (LPARAM)new string(cmd));
    return S_OK;
  })), &tk);
  // background tabs sleep after a while (see timer 3)
  auto w19 = t->wv.as<ICoreWebView2_19>(IID_ICoreWebView2_19);
  if (w19) w19->put_MemoryUsageTargetLevel(t == g_cur ? COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_NORMAL : COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_LOW);
}

static void reinstallScript() {
  // new settings (ad mode, shields) only reach pages opened afterwards: scripts are replaced in every tab
  for (auto& t : g_tabs) addPageScript(t.get());
}

// ---------------------------------------------------------------- commands from the toolbar and panels
static wstring pickFolder(const wstring& start, const wchar_t* title = L"Save videos in…") {
  wstring out;
  Com<IFileOpenDialog> dlg;
  if (SUCCEEDED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_IFileOpenDialog, (void**)dlg.put()))) {
    DWORD o; dlg->GetOptions(&o); dlg->SetOptions(o | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM);
    dlg->SetTitle(title);
    if (SUCCEEDED(dlg->Show(g_hwnd))) {
      Com<IShellItem> it; PWSTR p = nullptr;
      if (SUCCEEDED(dlg->GetResult(it.put())) && SUCCEEDED(it->GetDisplayName(SIGDN_FILESYSPATH, &p))) { out = p; CoTaskMemFree(p); }
    }
  }
  return out;
}
static json settingsView() {
  json s = g_settings;
  s["folderShown"] = U8(saveFolder());
  s["filesShown"] = U8(filesFolder());
  json eng = json::array();
  for (auto& [n, b] : searchEngines()) eng.push_back(n);
  s["engines"] = eng;
  s["version"] = "2.0.0";
  s["built"] = "C++ / WebView2";
  return s;
}

static void handleUi(const json& m);
static void hidePopup() {
  if (!g_popCtl || !g_popOpen) return;
  g_popOpen = false;
  g_popHiddenKind = g_popKind;
  g_popHiddenAt = nowMs();
  g_popCtl->put_IsVisible(FALSE);
}
static json popupData() {
  Tab* t = g_cur;
  bool st = !t || isStart(t->url);
  string url = st ? "" : t->url, host = st ? "" : hostOf(url);
  json ext = json::array();
  for (auto& x : g_settings.value("extensions", json::array())) { string n = x.get<string>(); size_t k = n.find_last_of("\\/"); ext.push_back(k == string::npos ? n : n.substr(k + 1)); }
  return {{"kind", g_popKind}, {"theme", g_settings.value("theme", "SYSTEM")}, {"start", st}, {"host", host}, {"secure", startsWith(url, "https:")},
          {"favicon", st ? "" : t->favicon}, {"blocked", t ? t->blocked : 0}, {"hidden", t ? t->hidden : 0}, {"shieldsDown", t && shieldsDown(t)},
          {"adMode", mode()}, {"httpsOnly", g_settings.value("httpsOnly", true)}, {"antiFinger", g_settings.value("antiFinger", false)},
          {"block3p", g_settings.value("block3p", false)}, {"scriptsOff", inList("scriptsOff", host)}, {"cookieGuard", g_settings.value("cookieGuard", false)}, {"rejectCookies", g_settings.value("rejectCookies", true)}, {"trusted", !host.empty() && isTrusted(host)}, {"forget", inList("forgetSites", host)},
          {"passwords", g_settings.value("savePasswords", false) && !g_private}, {"private", g_private}, {"privateMode", g_settings.value("private", false)},
          {"closed", (int)g_closed.size()}, {"extensions", ext}, {"zoom", t ? (int)(t->zoom * 100 + 0.5) : 100}};
}
static void sendPopup() {
  if (!g_popWv) return;
  json m = {{"ch", "popup"}, {"data", popupData()}};
  g_popWv->PostWebMessageAsJson(W(m.dump()).c_str());
}
static void showPopup(const string& kind, double l, double r, double btm) {
  if (!g_popWv) return;
  if (g_popOpen && g_popKind == kind) { hidePopup(); return; }
  if (g_popHiddenKind == kind && nowMs() - g_popHiddenAt < 300) return;  // the click that closed it was on its own button
  hidePopup();
  g_popKind = kind; g_popL = l; g_popR = r; g_popB = btm;
  RECT lay{0, 0, (int)((kind == "shields" ? 402 : kind == "menu" ? 328 : 322) * dpiScale()), (int)(600 * dpiScale())};
  g_popCtl->put_Bounds(lay);  // still hidden: gives the page its real width to measure itself
  sendPopup(); // the page draws itself, reports its size, and is then shown
}
static void sizePopup(double wCss, double hCss, bool fresh) {
  if (!g_popCtl || (!fresh && !g_popOpen)) return;
  RECT rc; GetClientRect(g_hwnd, &rc);
  double k = dpiScale();
  int e = (g_fullscreen || IsZoomed(g_hwnd)) ? 0 : (int)(kEdge * k + 0.5);
  int w = (int)(wCss * k + 0.5), h = (int)((hCss + 1) * k + 0.5);
  // the site panel hangs from the left of its button; menu and Shields end where their button ends
  int x = g_popKind == "site" ? e + (int)((g_popL - 4) * k) : e + (int)((g_popR + 4) * k) - w;
  int y = e + (int)((g_popB + 2) * k);
  x = std::max(4, std::min<int>(x, rc.right - w - 4));
  h = std::min<int>(h, std::max<int>(120, rc.bottom - y - 6));
  RECT box{x, y, x + w, y + h};
  g_popCtl->put_Bounds(box);
  if (g_popHwnd) {
    int round = (int)(24 * k);
    SetWindowRgn(g_popHwnd, CreateRoundRectRgn(0, 0, w + 1, h + 1, round, round), TRUE);
    SetWindowPos(g_popHwnd, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
  }
  if (fresh) {
    g_popOpen = true;
    g_popCtl->put_IsVisible(TRUE);
    g_popCtl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
  }
}
static std::set<HWND> childWindows() {
  std::set<HWND> out;
  EnumChildWindows(g_hwnd, [](HWND ch, LPARAM l) -> BOOL { if (GetParent(ch) == g_hwnd) ((std::set<HWND>*)l)->insert(ch); return TRUE; }, (LPARAM)&out);
  return out;
}
static void createPopup(std::function<void()> then) {
  auto before = std::make_shared<std::set<HWND>>(childWindows());
  g_env->CreateCoreWebView2Controller(g_hwnd, H2(ICoreWebView2CreateCoreWebView2ControllerCompletedHandler, HRESULT, ICoreWebView2Controller*, ([before, then](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
    if (SUCCEEDED(hr) && c) {
      g_popCtl = c;
      c->put_IsVisible(FALSE);
      for (HWND w : childWindows()) if (!before->count(w)) g_popHwnd = w;
      c->get_CoreWebView2(g_popWv.put());
      Com<ICoreWebView2Settings> st;
      g_popWv->get_Settings(st.put());
      st->put_AreDefaultContextMenusEnabled(FALSE);
      st->put_IsStatusBarEnabled(FALSE);
      st->put_AreDevToolsEnabled(FALSE);
      st->put_IsZoomControlEnabled(FALSE);
      if (auto s3 = st.as<ICoreWebView2Settings3>(IID_ICoreWebView2Settings3)) s3->put_AreBrowserAcceleratorKeysEnabled(FALSE);
      if (auto w3 = g_popWv.as<ICoreWebView2_3>(IID_ICoreWebView2_3)) w3->SetVirtualHostNameToFolderMapping(L"sf.ui", g_ui.c_str(), COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS);
      EventRegistrationToken tk;
      g_popWv->add_WebMessageReceived(H2(ICoreWebView2WebMessageReceivedEventHandler, ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs*, ([](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* a) -> HRESULT {
        CoStr src; a->get_Source(src.put());
        if (!startsWith(U8(src.w), "https://sf.ui/")) return S_OK;
        CoStr js; a->get_WebMessageAsJson(js.put());
        auto m = json::parse(js.str(), nullptr, false);
        if (m.is_object()) handleUi(m);
        return S_OK;
      })), &tk);
      c->add_LostFocus(H2(ICoreWebView2FocusChangedEventHandler, ICoreWebView2Controller*, IUnknown*, ([](ICoreWebView2Controller*, IUnknown*) -> HRESULT { hidePopup(); return S_OK; })), &tk);
      g_popWv->Navigate(L"https://sf.ui/popup.html");
    }
    then();
    return S_OK;
  })));
}

static void menuAction(int cmd) {
  bool priv = g_settings.value("private", false);
  Tab* t = g_cur;
  switch (cmd) {
    case 101: newTab(g_settings.value("home", string(kStartUrl))); break;
    case 102: if (!g_closed.empty()) { string u = g_closed.back(); g_closed.pop_back(); newTab(u); } break;
    case 103: if (t) openPickerFor(t, json::object()), sendUi("panel", "picker"); break;
    case 104: sendUi("find-open", nullptr); break;
    case 105: if (t && t->ctl) { t->zoom = std::min(3.0, t->zoom + 0.1); t->ctl->put_ZoomFactor(t->zoom); pushState(); } break;
    case 106: if (t && t->ctl) { t->zoom = std::max(0.3, t->zoom - 0.1); t->ctl->put_ZoomFactor(t->zoom); pushState(); } break;
    case 107: if (t && t->ctl) { t->zoom = 1.0; t->ctl->put_ZoomFactor(1.0); pushState(); } break;
    case 108: sendUi("panel", "downloads"); break;
    case 109: sendUi("panel", "history"); break;
    case 110: sendUi("panel", "bookmarks"); break;
    case 111: sendUi("panel", "ads"); break;
    case 112: sendUi("panel", "settings"); break;
    case 113: g_settings["private"] = !priv; saveSettings(); break;
    case 114: if (t && t->wv) t->wv->ExecuteScript(L"window.print()", nullptr); break;
    case 115: PostMessageW(g_hwnd, WM_CLOSE, 0, 0); break;
    case 116:
      if (MessageBoxW(g_hwnd, L"Delete history, cookies, logins and cached site data?", L"Delete browsing data", MB_YESNO | MB_ICONQUESTION) == IDYES) {
        writeFile(g_data + L"\\history.json", "[]");
        if (auto w13 = Com<ICoreWebView2>(g_ui_wv).as<ICoreWebView2_13>(IID_ICoreWebView2_13)) {
          Com<ICoreWebView2Profile> pr; w13->get_Profile(pr.put());
          if (auto p2 = pr.as<ICoreWebView2Profile2>(IID_ICoreWebView2Profile2)) p2->ClearBrowsingData(COREWEBVIEW2_BROWSING_DATA_KINDS_ALL_SITE | COREWEBVIEW2_BROWSING_DATA_KINDS_DISK_CACHE | COREWEBVIEW2_BROWSING_DATA_KINDS_COOKIES, nullptr);
        }
      }
      break;
    case 117: setFullscreen(!g_fullscreen); break;
    case 118: if (t && t->wv) t->wv->ExecuteScript(L"document.execCommand('cut')", nullptr); break;
    case 119: if (t && t->wv) t->wv->ExecuteScript(L"document.execCommand('copy')", nullptr); break;
    case 120: if (t && t->wv) t->wv->ExecuteScript(L"document.execCommand('paste')", nullptr); break;
    case 121:
      if (t && OpenClipboard(g_hwnd)) {
        wstring u = W(t->url);
        EmptyClipboard();
        HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, (u.size() + 1) * sizeof(wchar_t));
        if (h) { memcpy(GlobalLock(h), u.c_str(), (u.size() + 1) * sizeof(wchar_t)); GlobalUnlock(h); SetClipboardData(CF_UNICODETEXT, h); }
        CloseClipboard();
      }
      break;
    case 122: if (t && t->wv) t->wv->OpenDevToolsWindow(); break;
    case 123: if (g_torCc.empty()) launchSelf(L"--window"); else notice("Open new windows from your normal Speed Force window."); break;
    case 127: newTab(kLensUrl); break;
    case 128:
      if (t && isHttp(t->url) && !isStart(t->url) && hostOf(t->url) != "sf.diag") newTab(diagUrl(t->url, "", t->lastStatus));
      else notice("Open the page that does not load, then use this.", nullptr);
      break;
    case 124: if (g_torCc.empty()) launchSelf(L"--private"); else notice("Open new windows from your normal Speed Force window."); break;
    case 126:
      g_settings["savePasswords"] = !g_settings.value("savePasswords", false);
      saveSettings();
      for (auto& x : g_tabs) {
        Com<ICoreWebView2Settings> st;
        if (!x->wv) continue;
        x->wv->get_Settings(st.put());
        BOOL on = g_settings.value("savePasswords", false);
        if (auto s4 = st.as<ICoreWebView2Settings4>(IID_ICoreWebView2Settings4)) { s4->put_IsPasswordAutosaveEnabled(on); s4->put_IsGeneralAutofillEnabled(on); }
      }
      break;
    case 125: {
      wstring f = pickFolder(L"", L"Choose the unpacked extension folder (has manifest.json)");
      if (f.empty()) break;
      if (!fileExists(f + L"\\manifest.json")) { MessageBoxW(g_hwnd, L"That folder has no manifest.json, so it is not an unpacked extension.", APP_NAME, MB_ICONWARNING); break; }
      Com<ICoreWebView2Profile> pr;
      if (auto w13 = Com<ICoreWebView2>(g_ui_wv).as<ICoreWebView2_13>(IID_ICoreWebView2_13)) w13->get_Profile(pr.put());
      auto p7 = pr.p ? pr.as<ICoreWebView2Profile7>(IID_ICoreWebView2Profile7) : Com<ICoreWebView2Profile7>();
      if (!p7) { MessageBoxW(g_hwnd, L"This Windows web engine is too old for extensions.", APP_NAME, MB_ICONWARNING); break; }
      p7->AddBrowserExtension(f.c_str(), H2(ICoreWebView2ProfileAddBrowserExtensionCompletedHandler, HRESULT, ICoreWebView2BrowserExtension*, ([f](HRESULT hr, ICoreWebView2BrowserExtension*) -> HRESULT {
        if (FAILED(hr)) { MessageBoxW(g_hwnd, L"The engine could not load that extension.", APP_NAME, MB_ICONWARNING); return S_OK; }
        json l = g_settings.value("extensions", json::array());
        l.push_back(U8(f));
        g_settings["extensions"] = l;
        saveSettings();
        MessageBoxW(g_hwnd, L"Extension added.", APP_NAME, MB_OK);
        return S_OK;
      })));
      break;
    }
    default:
      if (cmd >= 200 && cmd < 250) {
        json l = g_settings.value("extensions", json::array());
        if ((size_t)(cmd - 200) < l.size()) {
          l.erase(l.begin() + (cmd - 200));
          g_settings["extensions"] = l;
          saveSettings();
          MessageBoxW(g_hwnd, L"Extension removed. Restart Speed Force to finish.", APP_NAME, MB_OK);
        }
      }
  }
}

static void handleUi(const json& m) {
  int id = m.value("id", 0);
  string c = m.value("cmd", "");
  json a = m.value("args", json::array());
  auto A = [&](size_t i) -> json { return i < a.size() ? a[i] : json(nullptr); };
  auto ret = [&](const json& v = nullptr) { if (id) replyUi(id, v); };
  Tab* t = g_cur;
  if (c == "topHeight") { g_topCss = A(0).get<double>(); layout(); ret(); }
  else if (c == "panel") { g_panelCss = A(0).get<double>(); layout(); ret(); }
  else if (c == "go") {
    string u = toUrlOrSearch(A(0).get<string>(), searchEngines().at(g_settings.value("engine", "DuckDuckGo")));
    if (!u.empty() && t) { loadTab(t, u); if (t->ctl) t->ctl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); }
    ret();
  }
  else if (c == "searchWith") { string q = t ? searchQueryOf(t->url) : ""; auto it = searchEngines().find(A(0).get<string>()); if (!q.empty() && it != searchEngines().end()) loadTab(t, it->second + urlEncode(q)); ret(); }
  else if (c == "back") { if (t) goBack(t); ret(); }
  else if (c == "forward") { if (t && t->wv) t->wv->GoForward(); ret(); }
  else if (c == "reload") { if (t && t->wv) { if (t->loading) t->wv->Stop(); else t->wv->Reload(); } ret(); }
  else if (c == "home") {
    string hm = g_settings.value("home", string(kStartUrl));
    if (!isHttp(hm)) hm = kStartUrl;  // empty or an old-style address: the start page
    if (t) loadTab(t, hm);
    ret();
  }
  else if (c == "newTab") { newTab(A(0).is_string() ? A(0).get<string>() : g_settings.value("home", string(kStartUrl))); ret(); }
  else if (c == "selectTab") { if (Tab* x = tabById(A(0).get<int>())) selectTab(x); ret(); }
  else if (c == "closeTab") { if (Tab* x = tabById(A(0).get<int>())) closeTab(x); ret(); }
  else if (c == "moveTab") {
    int tid = A(0).get<int>(), to = A(1).get<int>();
    for (size_t i = 0; i < g_tabs.size(); i++) if (g_tabs[i]->id == tid) {
      auto up = std::move(g_tabs[i]); g_tabs.erase(g_tabs.begin() + i);
      g_tabs.insert(g_tabs.begin() + std::max(0, std::min((int)g_tabs.size(), to)), std::move(up));
      break;
    }
    pushState(); ret();
  }
  else if (c == "focusPage") { if (t && t->ctl) t->ctl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); ret(); }
  else if (c == "find") {
    // window.find(text, caseSensitive, backwards, wrap)
    if (t && t->wv) {
      string text = A(0).is_string() ? A(0).get<string>() : "";
      bool fwd = A(1).is_boolean() ? A(1).get<bool>() : true;
      if (!text.empty()) {
        string js = "window.find(" + json(text).dump() + ",false," + (fwd ? "false" : "true") + ",true)";
        t->wv->ExecuteScript(W(js).c_str(), nullptr);
      }
    }
    ret();
  }
  else if (c == "stopFind") { if (t && t->wv) t->wv->ExecuteScript(L"getSelection().removeAllRanges()", nullptr); ret(); }
  else if (c == "zoom") {
    if (t && t->ctl) { double s = A(0).get<double>(); t->zoom = s ? std::min(3.0, std::max(0.3, t->zoom + s)) : 1.0; t->ctl->put_ZoomFactor(t->zoom); pushState(); }
    ret();
  }
  else if (c == "noticeAction") {
    json x = A(0);
    Tab* tt = x.contains("tab") ? tabById(x["tab"].get<int>()) : t;
    if (!tt) tt = t;
    if (x.contains("http")) { string u = x["http"]; g_httpAllowed.insert(hostOf(u)); if (tt && tt->wv) tt->wv->Navigate(W(u).c_str()); }
    else if (x.contains("reload")) { if (tt && tt->wv) tt->wv->Reload(); }
    else if (x.contains("show")) { wstring p = W(x["show"].get<string>()); SHELLEXECUTEINFOW sei{sizeof sei}; wstring arg = L"/select,\"" + p + L"\""; ShellExecuteW(nullptr, L"open", L"explorer.exe", arg.c_str(), nullptr, SW_SHOW); }
    else if (x.contains("url") && x.contains("tab")) { if (tt) loadTab(tt, x["url"]); }
    else if (x.contains("url")) newTab(x["url"]);
    else if (x.contains("updateSetup")) {
      wstring setup = W(x["updateSetup"].get<string>());
      if (fileExists(setup)) {
        wstring bat = g_data + L"\\sf-update.cmd";
        writeFile(bat, "@echo off\r\nping -n 3 127.0.0.1 >nul\r\n\"" + U8(setup) + "\" /S /D=" + U8(g_exe) + "\r\nstart \"\" \"" + U8(g_exeFile) + "\"\r\ndel \"%~f0\"\r\n");
        wstring cmd = L"cmd.exe /c \"" + bat + L"\"";
        STARTUPINFOW si{sizeof si}; si.dwFlags = STARTF_USESHOWWINDOW; si.wShowWindow = SW_HIDE;
        PROCESS_INFORMATION pi{};
        if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
          CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
          PostMessageW(g_hwnd, WM_CLOSE, 0, 0);
        }
      }
    }
    ret();
  }
  else if (c == "popup") { json x = A(1); showPopup(A(0).get<string>(), x.value("l", 0.0), x.value("r", 0.0), x.value("b", 0.0)); ret(); }
  else if (c == "popupData") ret(popupData());
  else if (c == "lens") { newTab(kLensUrl); ret(); }
  else if (c == "popupSize") { sizePopup(A(0).get<double>(), A(1).get<double>(), A(2).is_boolean() && A(2).get<bool>()); ret(); }
  else if (c == "popupClose") { hidePopup(); if (t && t->ctl) t->ctl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); ret(); }
  else if (c == "popupSwitch") { g_popKind = A(0).get<string>(); g_popOpen = false; if (g_popCtl) g_popCtl->put_IsVisible(FALSE); g_popR = g_popL + 360; sendPopup(); ret(); }
  else if (c == "menuAct") {
    int n = A(0).get<int>();
    ret();
    if (n < 105 || n > 107) hidePopup();  // zoom buttons leave the menu open
    menuAction(n);
  }
  else if (c == "siteList") {  // per-site switches in Shields
    string key = A(0).get<string>(), host = t && !isStart(t->url) ? hostOf(t->url) : "";
    if ((key == "scriptsOff" || key == "forgetSites" || key == "trustedSites") && !host.empty()) {
      json l = json::array();
      for (auto& x : g_settings.value(key, json::array())) if (x.get<string>() != host) l.push_back(x);
      if (A(1).get<bool>()) l.push_back(host);
      g_settings[key] = l;
      saveSettings();
      if (key == "scriptsOff" && t->wv) t->wv->Reload();
    }
    ret();
  }
  else if (c == "siteCookies" || c == "siteClearCookies") siteCookies(t ? t->url : "", c == "siteClearCookies", [id](int n) { replyUi(id, n); });
  else if (c == "findVideos") { if (t) { openPickerFor(t, json::object()); sendUi("panel", "picker"); } ret(); }
  else if (c == "ytInfo") {
    // a = [url, ctx]; the page logic turns the raw output into quality rows
    string url = A(0); json ctx = A(1);
    std::vector<string> cu;
    for (auto& u : ctx.value("cookieUrls", json::array())) cu.push_back(u.get<string>());
    string name = "info-" + randomId();
    makeCookieFile(cu, name, [=](wstring cookies) {
      std::vector<string> args = {"-J", "--playlist-items", "1:10"};
      for (auto& x : commonArgs()) args.push_back(x);
      args.push_back("--user-agent"); args.push_back(jstr(ctx, "userAgent"));
      if (!jstr(ctx, "referer").empty()) { args.push_back("--referer"); args.push_back(ctx["referer"]); }
      if (!jstr(ctx, "origin").empty()) { args.push_back("--add-header"); args.push_back("Origin:" + jstr(ctx, "origin")); }
      if (!cookies.empty()) { args.push_back("--cookies"); args.push_back(U8(cookies)); }
      args.push_back("--"); args.push_back(url);
      runProcess(ytCommand(args), ytEnv(), nullptr, [=](RunResult rr) {
        if (!cookies.empty()) DeleteFileW(cookies.c_str());
        if (rr.code == 0) replyUi(id, {{"ok", true}, {"out", rr.out}});
        else replyUi(id, {{"ok", false}, {"error", shortError(rr.err.empty() ? rr.out : rr.err)}});
      });
    });
  }
  else if (c == "ytEnqueue") { string nid = enqueueVideo(A(0)); sendUi("panel", "downloads"); ret(nid); }
  else if (c == "downloads") { json arr = json::array(); for (auto& d : g_dl) arr.push_back(d.j); ret(arr); }
  else if (c == "dl") { dlAction(A(0).get<string>(), A(1).is_string() ? A(1).get<string>() : ""); ret(); }
  else if (c == "openFile" || c == "showFile") {
    if (auto* d = findDl(A(0).get<string>())) {
      wstring f = W(d->j.value("file", ""));
      if (!f.empty()) { if (c == "openFile") ShellExecuteW(nullptr, L"open", f.c_str(), nullptr, nullptr, SW_SHOW); else { wstring arg = L"/select,\"" + f + L"\""; ShellExecuteW(nullptr, L"open", L"explorer.exe", arg.c_str(), nullptr, SW_SHOW); } }
    }
    ret();
  }
  else if (c == "openFolder") { makeDirs(saveFolder()); ShellExecuteW(nullptr, L"open", saveFolder().c_str(), nullptr, nullptr, SW_SHOW); ret(); }
  else if (c == "openFilesFolder") { makeDirs(filesFolder()); ShellExecuteW(nullptr, L"open", filesFolder().c_str(), nullptr, nullptr, SW_SHOW); ret(); }
  else if (c == "billboard") { ret({{"entries", g_billboard}, {"counts", g_counts}}); }
  else if (c == "adOpen") { for (auto& b : g_billboard) if (b.value("id", 0) == A(0).get<int>() && isHttp(b.value("url", ""))) newTab(b["url"]); ret(); }
  else if (c == "adCopy") {
    for (auto& b : g_billboard) if (b.value("id", 0) == A(0).get<int>()) {
      wstring w = W(b.value("url", ""));
      if (OpenClipboard(g_hwnd)) { EmptyClipboard(); HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, (w.size() + 1) * 2); memcpy(GlobalLock(h), w.c_str(), (w.size() + 1) * 2); GlobalUnlock(h); SetClipboardData(CF_UNICODETEXT, h); CloseClipboard(); }
    }
    ret();
  }
  else if (c == "adClear") { g_billboard.clear(); sendUi("billboard", {{"entries", g_billboard}, {"counts", g_counts}}); ret(); }
  else if (c == "history") { ret(readList(L"history.json")); }
  else if (c == "clearHistory") { writeFile(g_data + L"\\history.json", "[]"); ret(); }
  else if (c == "open") { string u = A(0); if (A(1).is_boolean() && A(1).get<bool>()) newTab(u); else if (t) loadTab(t, u); else newTab(u); ret(); }
  else if (c == "settings") { ret(settingsView()); }
  else if (c == "setSetting") {
    json ch = A(0);
    string before = mode();
    for (auto it = ch.begin(); it != ch.end(); ++it) g_settings[it.key()] = it.value();
    saveSettings();
    if (ch.contains("antiFinger")) { for (auto& x : g_tabs) applyFingerprint(x.get()); notice("Fingerprint setting changed. Reload pages to use it.", t ? json({{"label", "Reload"}, {"reload", t->id}}) : json(nullptr)); }
    if (ch.contains("rejectCookies")) { reinstallScript(); notice("Cookie banner setting changed. Reload pages to use it.", t ? json({{"label", "Reload"}, {"reload", t->id}}) : json(nullptr)); }
    if (ch.contains("cookieGuard") && g_settings.value("cookieGuard", false)) { for (auto& x : g_tabs) guardNote(x.get(), x->url); notice("Cookie guard is on: a site\'s cookies are deleted when you close it. Add sites you stay signed in to under Shields.", nullptr); }
    if (ch.contains("block3p")) notice("Cookie setting changed. Close and reopen Speed Force to use it.", nullptr);
    if (ch.contains("adMode") && ch["adMode"] != before) { reinstallScript(); notice("Ad setting changed. Reload pages to use it.", t ? json({{"label", "Reload"}, {"reload", t->id}}) : json(nullptr)); }
    if (ch.contains("theme")) {
      string th = g_settings.value("theme", "SYSTEM");
      for (auto& x : g_tabs) if (x->wv) if (auto w13 = x->wv.as<ICoreWebView2_13>(IID_ICoreWebView2_13)) { Com<ICoreWebView2Profile> pr; w13->get_Profile(pr.put()); pr->put_PreferredColorScheme(th == "LIGHT" ? COREWEBVIEW2_PREFERRED_COLOR_SCHEME_LIGHT : th == "DARK" ? COREWEBVIEW2_PREFERRED_COLOR_SCHEME_DARK : COREWEBVIEW2_PREFERRED_COLOR_SCHEME_AUTO); }
    }
    ret(settingsView());
  }
  else if (c == "chooseFolder") { wstring f = pickFolder(saveFolder()); if (!f.empty()) { g_settings["folder"] = U8(f); saveSettings(); } ret(settingsView()); }
  else if (c == "engineVersion") {
    runProcess(ytCommand({"--version"}), ytEnv(), nullptr, [=](RunResult rr) { replyUi(id, trim(rr.code == 0 ? rr.out : string("unknown"))); });
  }
  else if (c == "updateEngine") {
    runProcess(ytCommand({"-U"}), ytEnv(), nullptr, [=](RunResult rr) {
      string r = rr.code != 0 ? "Update failed: " + shortError(rr.err) : (rr.out.find("up to date") != string::npos || rr.out.find("up-to-date") != string::npos) ? "Already up to date" : "Updated";
      if (rr.code == 0) { g_settings["engineUpdated"] = unixMs(); saveSettings(); }
      replyUi(id, r);
    });
  }
  else if (c == "clearSiteData") {
    if (auto w13 = Com<ICoreWebView2>(g_ui_wv).as<ICoreWebView2_13>(IID_ICoreWebView2_13)) {
      Com<ICoreWebView2Profile> pr; w13->get_Profile(pr.put());
      if (auto p2 = pr.as<ICoreWebView2Profile2>(IID_ICoreWebView2Profile2)) p2->ClearBrowsingData(COREWEBVIEW2_BROWSING_DATA_KINDS_ALL_SITE | COREWEBVIEW2_BROWSING_DATA_KINDS_DISK_CACHE | COREWEBVIEW2_BROWSING_DATA_KINDS_COOKIES, H1(ICoreWebView2ClearBrowsingDataCompletedHandler, HRESULT, ([id](HRESULT) -> HRESULT { replyUi(id, "Cookies, logins and site data cleared"); return S_OK; })));
      else ret("Not supported by this Windows web engine");
    } else ret("Not supported by this Windows web engine");
  }
  else if (c == "shieldsToggle") {
    if (t && !isStart(t->url)) {
      string h = siteOf(t->url);
      json list = json::array();
      bool had = false;
      for (auto& x : g_settings["shieldsOff"]) { if (x.get<string>() == h) had = true; else list.push_back(x); }
      if (!had) list.push_back(h);
      g_settings["shieldsOff"] = list;
      saveSettings();
      reinstallScript();
      if (t->wv) t->wv->Reload();
      pushState();
    }
    ret();
  }
  else if (c == "bookmarks") { ret(readList(L"bookmarks.json")); }
  else if (c == "toggleBookmark") {
    if (t && isHttp(t->url)) {
      json list = readList(L"bookmarks.json"), out = json::array();
      bool had = false;
      for (auto& b : list) { if (b.value("u", "") == t->url) had = true; else out.push_back(b); }
      if (!had) out.insert(out.begin(), {{"t", t->title}, {"u", t->url}});
      writeFile(g_data + L"\\bookmarks.json", out.dump());
      pushState();
    }
    ret();
  }
  else if (c == "removeBookmark") {
    json out = json::array();
    for (auto& b : readList(L"bookmarks.json")) if (b.value("u", "") != A(0).get<string>()) out.push_back(b);
    writeFile(g_data + L"\\bookmarks.json", out.dump());
    pushState(); ret();
  }
  else if (c == "stats") { ret(g_stats); }
  else if (c == "win") {
    string w = A(0).get<string>();
    if (w == "full") setFullscreen(!g_fullscreen);
    else if (w == "drag") { ReleaseCapture(); PostMessageW(g_hwnd, WM_NCLBUTTONDOWN, HTCAPTION, 0); }
    else PostMessageW(g_hwnd, WM_SYSCOMMAND, w == "close" ? SC_CLOSE : w == "min" ? SC_MINIMIZE : (IsZoomed(g_hwnd) ? SC_RESTORE : SC_MAXIMIZE), 0);
    ret();
  }
  else if (c == "ready") { g_uiReady = true; pushState(); doSendDownloads(); ret(); }
  else ret();
}

// ---------------------------------------------------------------- window
static void handleKey(const string& cmd) {
  Tab* t = g_cur;
  if (cmd == "newtab") newTab(g_settings.value("home", string(kStartUrl)));
  else if (cmd == "reopen") { if (!g_closed.empty()) { string u = g_closed.back(); g_closed.pop_back(); newTab(u); } }
  else if (cmd == "closetab") { if (t) closeTab(t); }
  else if (cmd == "address") { if (g_uiCtl) g_uiCtl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); sendUi("focus-address", nullptr); }
  else if (cmd == "find") { if (g_uiCtl) g_uiCtl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); sendUi("find-open", nullptr); }
  else if (cmd == "downloads") sendUi("panel", "downloads");
  else if (cmd == "history") sendUi("panel", "history");
  else if (cmd == "bookmark") { json m = {{"cmd", "toggleBookmark"}, {"args", json::array()}}; handleUi(m); }
  else if (cmd == "fullscreen") setFullscreen(!g_fullscreen);
  else if (cmd == "unfull") setFullscreen(false);
  else if (cmd == "newwindow" && g_torCc.empty()) launchSelf(L"--window");
  else if (cmd == "newprivate" && g_torCc.empty()) launchSelf(L"--private");
  else if (cmd == "back") { if (t) goBack(t); }
  else if (cmd == "forward") { if (t && t->wv) t->wv->GoForward(); }
  else if (cmd == "nexttab" || cmd == "prevtab") {
    if (g_tabs.size() > 1) {
      size_t i = 0;
      for (; i < g_tabs.size(); i++) if (g_tabs[i].get() == t) break;
      i = (i + (cmd == "nexttab" ? 1 : g_tabs.size() - 1)) % g_tabs.size();
      selectTab(g_tabs[i].get());
    }
  }
}

static void saveTabsNow() {
  if (g_secondary) return;
  if (g_settings.value("private", false) || !g_settings.value("restoreTabs", true)) { writeFile(g_data + L"\\tabs.json", "{}"); return; }
  json u = json::array();
  int cur = 0;
  for (auto& t : g_tabs) {
    string url = !t->pending.empty() ? t->pending : t->url;
    if (isHttp(url) && !isStart(url) && !isLensPage(t->lens, url) && hostOf(url) != "sf.diag" && u.size() < 50) { if (t.get() == g_cur) cur = (int)u.size(); u.push_back(url); }
  }
  writeFile(g_data + L"\\tabs.json", json({{"u", u}, {"i", cur}}).dump());
  writeFile(g_data + L"\\stats.json", g_stats.dump());
}

static void startBrowsing(const string& argUrl) {
  json saved = g_settings.value("restoreTabs", true) && !g_settings.value("private", false) && !g_secondary ?readJson(g_data + L"\\tabs.json", json::object()) : json::object();
  std::vector<string> urls;
  if (saved.contains("u")) for (auto& u : saved["u"]) if (isHttp(u.get<string>())) urls.push_back(u);
  int cur = saved.value("i", 0);
  for (size_t i = 0; i < urls.size(); i++) newTab(urls[i], false, (int)i != cur);
  if (!argUrl.empty()) newTab(argUrl);
  else if (!urls.empty()) selectTab(g_tabs[std::min<size_t>(cur, g_tabs.size() - 1)].get());
  else newTab(g_settings.value("home", string(kStartUrl)));
}

static string g_argUrl;

#ifdef SF_TEST
// ---- test mode (SF_SHOT=<prefix>, SF_URL=a,b): off-screen window, opens the pages, saves pictures and a log, quits
static void capturePng(ICoreWebView2* wv, const wstring& file) {
  IStream* ms = SHCreateMemStream(nullptr, 0);
  wv->CapturePreview(COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_PNG, ms, H1(ICoreWebView2CapturePreviewCompletedHandler, HRESULT, ([ms, file](HRESULT) -> HRESULT {
    STATSTG st{}; ms->Stat(&st, STATFLAG_NONAME);
    LARGE_INTEGER z{}; ms->Seek(z, STREAM_SEEK_SET, nullptr);
    string data((size_t)st.cbSize.QuadPart, 0);
    ULONG got = 0; ms->Read(data.data(), (ULONG)data.size(), &got);
    writeFile(file, data);
    ms->Release();
    return S_OK;
  })));
}
static int g_testStep = 0;
static std::vector<string> g_testUrls;
static wstring g_testPrefix;
static string g_testLog;
static void testTick() {
  g_testStep++;
  int step = g_testStep;
  wchar_t pop[8] = {};
  if (GetEnvironmentVariableW(L"SF_POPUP", pop, 8)) {
    static const char* kinds[] = {"menu", "site", "shields"};
    if (step == 4 && g_cur && !g_testUrls.empty()) loadTab(g_cur, g_testUrls[0]);
    for (int i = 0; i < 3; i++) {
      int at = 16 + i * 6;
      if (step == at) { g_popHiddenKind.clear(); hidePopup(); g_popHiddenKind.clear(); if (i == 1) showPopup(kinds[i], 150, 182, 82); else showPopup(kinds[i], 1196, 1232, 82); }
      if (step == at + 4 && g_popWv) { capturePng(g_popWv.p, g_testPrefix + W(kinds[i]) + L".png"); RECT b{}; g_popCtl->get_Bounds(&b); g_testLog += string(kinds[i]) + " open=" + std::to_string(g_popOpen) + " box=" + std::to_string(b.left) + "," + std::to_string(b.top) + "," + std::to_string(b.right) + "," + std::to_string(b.bottom) + " hwnd=" + std::to_string(g_popHwnd != nullptr) + "\n"; }
    }
    if (step == 36) { writeFile(g_testPrefix + L"log.txt", g_testLog); PostMessageW(g_hwnd, WM_CLOSE, 0, 0); }
    return;
  }
  auto snap = [&](const wstring& name) {
    if (g_cur && g_cur->wv) g_cur->wv->ExecuteScript(L"navigator.hardwareConcurrency+' cores, '+navigator.deviceMemory+' GB, cookiesEnabled '+navigator.cookieEnabled", H2(ICoreWebView2ExecuteScriptCompletedHandler, HRESULT, LPCWSTR, ([name](HRESULT, LPCWSTR r) -> HRESULT { if (r) g_testLog += "probe " + U8(name) + " " + U8(r) + "\n"; return S_OK; })));
    if (g_ui_wv) capturePng(g_ui_wv.p, g_testPrefix + name + L"-ui.png");
    if (g_cur && g_cur->wv) capturePng(g_cur->wv.p, g_testPrefix + name + L"-page.png");
    g_testLog += "snap " + U8(name) + " url=" + (g_cur ? g_cur->url : "") + " blocked=" + std::to_string(g_cur ? g_cur->blocked : 0) + " hidden=" + std::to_string(g_cur ? g_cur->hidden : 0) + " tabs=" + std::to_string(g_tabs.size()) + "\n";
  };
  if (step == 10) snap(L"1-start");
  for (size_t i = 0; i < g_testUrls.size(); i++) {
    if (step == (int)(11 + i * 14) && g_cur) loadTab(g_cur, g_testUrls[i]);
    if (step == (int)(22 + i * 14)) snap(L"2-page" + std::to_wstring(i));
  }
  int last = 22 + (int)g_testUrls.size() * 14;
  wchar_t extra[1200] = {};
  if (step == last - 11 && GetEnvironmentVariableW(L"SF_HOME", extra, 1200)) { json hm = {{"cmd", "home"}, {"args", json::array()}}; handleUi(hm); }
  if (step == last - 8 && GetEnvironmentVariableW(L"SF_HOME", extra, 1200)) snap(L"7-home");
  if (step == 14 && GetEnvironmentVariableW(L"SF_JS", extra, 1200) && g_cur && g_cur->wv) g_cur->wv->ExecuteScript(extra, nullptr);
  if (step == last - 6 && GetEnvironmentVariableW(L"SF_PICK", extra, 1200)) sendUi("pick-request", {{"pageUrl", U8(extra)}, {"frameUrl", ""}, {"directUrl", ""}, {"title", "test"}, {"streams", json::array()}});
  if (step == last - 2 && GetEnvironmentVariableW(L"SF_PICK", extra, 1200)) snap(L"5-picker");
  if (step == last - 1 && GetEnvironmentVariableW(L"SF_DLURL", extra, 1200) && g_cur && g_cur->wv) { g_cur->lastGesture = nowMs(); g_cur->wv->Navigate(extra); }
  if (step == last + 8) { sendUi("panel", "downloads"); }
  if (step == last + 1) sendUi("panel", "shields");
  if (step == last + 3) snap(L"3-shields");
  if (step == last + 4) sendUi("panel", "settings");
  if (step == last + 6) snap(L"4-settings");
  if (step == last + 12) {
    snap(L"6-downloads");
    for (auto& d : g_dl) g_testLog += "dl " + d.j.dump() + "\n";
    g_testLog += "stats " + g_stats.dump() + "\n";
    writeFile(g_testPrefix + L"log.txt", g_testLog);
    PostMessageW(g_hwnd, WM_CLOSE, 0, 0);
  }
}

#endif

static void createUi() {
  g_env->CreateCoreWebView2Controller(g_hwnd, H2(ICoreWebView2CreateCoreWebView2ControllerCompletedHandler, HRESULT, ICoreWebView2Controller*, ([](HRESULT hr, ICoreWebView2Controller* c) -> HRESULT {
    if (FAILED(hr) || !c) { MessageBoxW(g_hwnd, L"Could not start the web engine (WebView2).", APP_NAME, MB_ICONERROR); PostQuitMessage(1); return S_OK; }
    g_uiCtl = c;
    EnumChildWindows(g_hwnd, [](HWND ch, LPARAM l) -> BOOL { *(HWND*)l = ch; return FALSE; }, (LPARAM)&g_uiHwnd);
    c->get_CoreWebView2(g_ui_wv.put());
    Com<ICoreWebView2Settings> st;
    g_ui_wv->get_Settings(st.put());
    st->put_AreDefaultContextMenusEnabled(FALSE);
    st->put_IsStatusBarEnabled(FALSE);
    st->put_AreDevToolsEnabled(FALSE);
    st->put_IsZoomControlEnabled(FALSE);
    if (auto s3 = st.as<ICoreWebView2Settings3>(IID_ICoreWebView2Settings3)) s3->put_AreBrowserAcceleratorKeysEnabled(FALSE);
    if (auto s9 = st.as<ICoreWebView2Settings9>(IID_ICoreWebView2Settings9)) s9->put_IsNonClientRegionSupportEnabled(TRUE);
    if (auto w3 = Com<ICoreWebView2>(g_ui_wv).as<ICoreWebView2_3>(IID_ICoreWebView2_3)) {
      w3->SetVirtualHostNameToFolderMapping(L"sf.ui", g_ui.c_str(), COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS);
      w3->SetVirtualHostNameToFolderMapping(L"sf.start", (g_assets + L"\\start").c_str(), COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_ALLOW);
    }
    EventRegistrationToken tk;
    g_ui_wv->add_WebMessageReceived(H2(ICoreWebView2WebMessageReceivedEventHandler, ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs*, ([](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* a) -> HRESULT {
      CoStr js; a->get_WebMessageAsJson(js.put());
      auto m = json::parse(js.str(), nullptr, false);
      if (m.is_object()) handleUi(m);
      return S_OK;
    })), &tk);
    g_ui_wv->add_NavigationCompleted(H2(ICoreWebView2NavigationCompletedEventHandler, ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs*, ([](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs*) -> HRESULT {
      static bool first = true;
      if (first) { first = false; g_uiReady = true; createPopup([] { for (auto& h : g_settings.value("forgetPending", json::array())) siteCookies("https://" + h.get<string>() + "/", true, [](int) {}); g_settings["forgetPending"] = json::array(); guardAtStart(); if (g_settings.value("lensDirty", false)) { forgetLensTraces(); g_settings["lensDirty"] = false; saveSettings(); } startBrowsing(g_argUrl); layout(); }); }
      return S_OK;
    })), &tk);
    c->add_AcceleratorKeyPressed(H2(ICoreWebView2AcceleratorKeyPressedEventHandler, ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs*, ([](ICoreWebView2Controller*, ICoreWebView2AcceleratorKeyPressedEventArgs* a) -> HRESULT {
      COREWEBVIEW2_KEY_EVENT_KIND kind; UINT vk;
      a->get_KeyEventKind(&kind); a->get_VirtualKey(&vk);
      if (kind != COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN && kind != COREWEBVIEW2_KEY_EVENT_KIND_SYSTEM_KEY_DOWN) return S_OK;
      bool ctrl = GetKeyState(VK_CONTROL) < 0, shift = GetKeyState(VK_SHIFT) < 0, alt = GetKeyState(VK_MENU) < 0;
      string cmd;
      if (ctrl && !alt) {
        if (vk == 'T') cmd = shift ? "reopen" : "newtab";
        else if (vk == 'W') cmd = "closetab";
        else if (vk == 'J') cmd = "downloads";
        else if (vk == 'H') cmd = "history";
        else if (vk == 'D') cmd = "bookmark";
        else if (vk == VK_TAB) cmd = shift ? "prevtab" : "nexttab";
      } else if (vk == VK_F11) cmd = "fullscreen";
      else if (alt && vk == VK_LEFT) cmd = "back";
      else if (alt && vk == VK_RIGHT) cmd = "forward";
      if (cmd.empty()) return S_OK;
      a->put_Handled(TRUE);
      PostMessageW(g_hwnd, WM_APP + 3, 0, (LPARAM)new string(cmd));
      return S_OK;
    })), &tk);
    layout();
    g_ui_wv->Navigate(L"https://sf.ui/index.html");
    return S_OK;
  })));
}

static LRESULT CALLBACK WndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
  switch (m) {
    case WM_NCCALCSIZE:
      if (w) {  // the whole window is client area; a maximized window is kept inside the screen
        if (IsZoomed(h)) {
          MONITORINFO mi{sizeof mi};
          GetMonitorInfoW(MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST), &mi);
          ((NCCALCSIZE_PARAMS*)l)->rgrc[0] = mi.rcWork;
        }
        return 0;
      }
      break;
    case WM_NCHITTEST: {
      if (IsZoomed(h) || g_fullscreen) return HTCLIENT;
      POINT pt{(short)LOWORD(l), (short)HIWORD(l)};
      RECT rc; GetWindowRect(h, &rc);
      int b = (int)(kEdge * dpiScale() + 0.5), corner = b * 3;
      bool L = pt.x < rc.left + b, R = pt.x >= rc.right - b, T = pt.y < rc.top + b, B = pt.y >= rc.bottom - b;
      if (T && pt.x < rc.left + corner) return HTTOPLEFT;
      if (T && pt.x >= rc.right - corner) return HTTOPRIGHT;
      if (B && pt.x < rc.left + corner) return HTBOTTOMLEFT;
      if (B && pt.x >= rc.right - corner) return HTBOTTOMRIGHT;
      if (L) return HTLEFT;
      if (R) return HTRIGHT;
      if (T) return HTTOP;
      if (B) return HTBOTTOM;
      return HTCLIENT;
    }
    case WM_MOVE: hidePopup(); break;
    case WM_SIZE: hidePopup(); layout(); return 0;
    case WM_DPICHANGED: {
      RECT* r = (RECT*)l;
      SetWindowPos(h, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE);
      layout();
      return 0;
    }
    case WM_GETMINMAXINFO: { auto* mi = (MINMAXINFO*)l; mi->ptMinTrackSize = {640, 420}; return 0; }
    case WM_SETFOCUS: if (g_cur && g_cur->ctl) g_cur->ctl->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC); return 0;
    case WM_POSTED: {
      for (;;) {
        std::function<void()> f;
        { std::lock_guard<std::mutex> lk(g_postMu); if (g_postQ.empty()) break; f = std::move(g_postQ.front()); g_postQ.pop_front(); }
        f();
      }
      return 0;
    }
    case WM_APP + 3: { string* s = (string*)l; handleKey(*s); delete s; return 0; }
    case WM_TIMER:
#ifdef SF_TEST
      if (w == 4) { testTick(); return 0; }
#endif
      if (w == 1) { KillTimer(h, 1); doPushState(); }
      else if (w == 2) { KillTimer(h, 2); doSendDownloads(); }
      else if (w == 3) {
        // every 10 s: tabs that were not looked at for a minute go to sleep (they keep their page, use almost no memory)
        ULONGLONG now = nowMs();
        for (auto& t : g_tabs) if (t.get() != g_cur && t->wv && !t->suspended && t->hiddenSince && now - t->hiddenSince > 60000) {
          if (auto w19 = t->wv.as<ICoreWebView2_19>(IID_ICoreWebView2_19)) w19->put_MemoryUsageTargetLevel(COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_LOW);
          suspendTab(t.get());
        }
        if (g_statsDirty && now - g_statsDirty > 5000) { g_statsDirty = 0; writeFile(g_data + L"\\stats.json", g_stats.dump()); }
      }
      return 0;
    case WM_COPYDATA: {
      auto* cd = (COPYDATASTRUCT*)l;
      if (cd->dwData == 1 && cd->lpData) { string u((char*)cd->lpData, cd->cbData); if (isHttp(u)) newTab(u); if (IsIconic(h)) ShowWindow(h, SW_RESTORE); SetForegroundWindow(h); }
      return TRUE;
    }
    case WM_CLOSE: {
      bool running = false;
      for (auto& d : g_dl) { string s = d.j.value("status", ""); if (s == "RUNNING") running = true; }
      if (running && MessageBoxW(h, L"Downloads are still running. They continue the next time you open Speed Force.", APP_NAME, MB_YESNO | MB_ICONQUESTION) != IDYES) return 0;
      {
        json pending = json::array();
        for (auto& x : g_tabs) if (isHttp(x->url) && inList("forgetSites", hostOf(x->url))) pending.push_back(hostOf(x->url));
        if (!pending.empty()) { g_settings["forgetPending"] = pending; saveSettings(); }
      }
      saveTabsNow();
      for (auto& d : g_dl) { if (d.job) TerminateJobObject(d.job, 1); }
      persistDownloads();
      DestroyWindow(h);
      return 0;
    }
    case WM_DESTROY: PostQuitMessage(0); return 0;
  }
  return DefWindowProcW(h, m, w, l);
}

/** Extra engine switches of a Tor window: everything goes to Tor, and nothing may look names up or connect around it. */
static wstring torArgs() {
  if (g_torCc.empty()) return L"";
  return L"--proxy-server=socks5://127.0.0.1:" + std::to_wstring(g_tor.socks) +
         L" --host-resolver-rules=\"MAP * ~NOTFOUND , EXCLUDE 127.0.0.1\" --force-webrtc-ip-handling-policy=disable_non_proxied_udp --disable-quic --dns-prefetch-disable ";
}
static bool startEngine() {
  // fewer engine processes and no background extras: less memory
  SetEnvironmentVariableW(L"WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
    wstring(g_settings.value("block3p", false) ? L"--test-third-party-cookie-phaseout " : L"").append(torArgs()).append(L"--renderer-process-limit=4 --disable-background-networking --disable-component-update --disable-sync --disable-features=msSmartScreenProtection,msEdgeShoppingAssistant,msEdgeRewards,Translate").append(g_torCc.empty() ? L"" : L",MediaRouter,DialMediaRouteProvider").append(L" --disable-breakpad --no-pings").c_str());
  wstring udf = g_data + L"\\browser";
  typedef HRESULT(STDAPICALLTYPE * CreateEnvFn)(PCWSTR, PCWSTR, ICoreWebView2EnvironmentOptions*, ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*);
  HMODULE loader = LoadLibraryW((g_exe + L"\\WebView2Loader.dll").c_str());
  CreateEnvFn createEnv = loader ? (CreateEnvFn)GetProcAddress(loader, "CreateCoreWebView2EnvironmentWithOptions") : nullptr;
  if (!createEnv) { MessageBoxW(nullptr, L"WebView2Loader.dll is missing.", APP_NAME, MB_ICONERROR); return false; }
  HRESULT hr = createEnv(nullptr, udf.c_str(), g_private ? nullptr : new SfOptions, H2(ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler, HRESULT, ICoreWebView2Environment*, ([](HRESULT hr, ICoreWebView2Environment* e) -> HRESULT {
    if (FAILED(hr) || !e) { MessageBoxW(g_hwnd, L"The Windows web engine (WebView2) is not installed or could not start.", APP_NAME, MB_ICONERROR); PostQuitMessage(1); return S_OK; }
    g_env = e;
    createUi();
    return S_OK;
  })));
  if (FAILED(hr)) { MessageBoxW(g_hwnd, L"The Windows web engine (WebView2) is not installed.", APP_NAME, MB_ICONERROR); return false; }

  return true;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR cmdLine, int show) {
  SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  g_exe = exeDir();
  g_data = g_exe + L"\\data";
  g_assets = g_exe + L"\\assets";
  g_ui = g_exe + L"\\ui";
  g_engine = g_exe + L"\\engine";
  if (!fileExists(g_ui)) {  // running from the project folder
    g_assets = dirOf(g_exe) + L"\\assets"; g_ui = dirOf(g_exe) + L"\\ui"; g_engine = dirOf(g_exe) + L"\\engine";
  }
  makeDirs(g_data);
  // one window only: a second start hands its address to the first
  int argc = 0;
  LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  for (int i = 1; i < argc; i++) {
    string a = U8(argv[i]);
    if (isHttp(a)) g_argUrl = a;
    else if (a == "--private") g_private = true;
    else if (a == "--window") g_secondary = true;
    else if (a.size() == 8 && startsWith(a, "--tor=") && tor::knownCountry(a.substr(6))) { g_torCc = a.substr(6); g_private = true; }
  }
  { wchar_t buf[MAX_PATH]; GetModuleFileNameW(nullptr, buf, MAX_PATH); g_exeFile = buf; }
  wstring mainData = g_data;
  HANDLE mtx = nullptr;
  if (!g_private && !g_secondary) mtx = CreateMutexW(nullptr, TRUE, L"SpeedForceSingleInstance");
  if (mtx && GetLastError() == ERROR_ALREADY_EXISTS) {
    HWND other = FindWindowW(L"SpeedForceWnd", nullptr);
    if (other) {
      if (!g_argUrl.empty()) { COPYDATASTRUCT cd{1, (DWORD)g_argUrl.size(), (void*)g_argUrl.data()}; SendMessageW(other, WM_COPYDATA, 0, (LPARAM)&cd); }
      else { if (IsIconic(other)) ShowWindow(other, SW_RESTORE); SetForegroundWindow(other); }
    }
    return 0;
  }
  g_settings = defaultSettings();
  { json saved = readJson(g_data + L"\\settings.json", json::object()); for (auto it = saved.begin(); it != saved.end(); ++it) g_settings[it.key()] = it.value(); }
  if (g_private) {  // own empty profile in a folder that is deleted when the window closes
    g_data = mainData + L"\\private-" + std::to_wstring(GetCurrentProcessId());
    makeDirs(g_data);
    g_settings["private"] = true;
    g_settings["restoreTabs"] = false;
    g_settings["savePasswords"] = false;
    g_settings["extensions"] = json::array();
    if (!g_torCc.empty()) { g_settings["httpsOnly"] = true; g_settings["home"] = kStartUrl; g_settings["antiFinger"] = true; g_settings["savePasswords"] = false; }
  }
  g_stats = readJson(g_data + L"\\stats.json", g_stats);
  g_adHosts.load(g_assets + L"\\adhosts.txt");
  g_trackerHosts.load(g_assets + L"\\trackers.txt");
  g_pageHosts = readFile(g_assets + L"\\pagehosts.txt");
  g_detectJs = readFile(g_assets + L"\\detect.js");
  g_guardJs = readFile(g_assets + L"\\guard.js");
  // saved download list: unfinished ones continue
  for (auto& j : readJson(g_data + L"\\downloads.json", json::array())) {
    DownloadItem d; d.j = j;
    if (j.value("kind", "video") == "file" && (j.value("status", "") == "RUNNING" || j.value("status", "") == "PAUSED")) d.j["status"] = "FAILED", d.j["error"] = "Interrupted when the app closed";
    if (d.j.value("status", "") == "RUNNING") d.j["status"] = "QUEUED";
    g_dl.push_back(d);
  }

  WNDCLASSW wc{};
  wc.lpfnWndProc = WndProc; wc.hInstance = inst; wc.lpszClassName = g_torCc.empty() ? L"SpeedForceWnd" : L"SpeedForceTorWnd";
  wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  wc.hIcon = LoadIconW(inst, MAKEINTRESOURCEW(1));
  wc.hbrBackground = CreateSolidBrush(RGB(27, 28, 34));
  RegisterClassW(&wc);
  g_hwnd = CreateWindowExW(0, wc.lpszClassName, !g_torCc.empty() ? L"Speed Force (Tor)" : g_private ? L"Speed Force (Private)" : APP_NAME, WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1280, 840, nullptr, nullptr, inst, nullptr);
  { COLORREF none = 0xFFFFFFFE; DwmSetWindowAttribute(g_hwnd, 34, &none, sizeof none); }
  SetWindowPos(g_hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);
  ShowWindow(g_hwnd, show);
  SetTimer(g_hwnd, 3, 10000, nullptr);
#ifdef SF_TEST
  wchar_t shot[MAX_PATH] = {};
  if (GetEnvironmentVariableW(L"SF_SHOT", shot, MAX_PATH)) {
    g_testPrefix = shot;
    wchar_t urls[2000] = {};
    GetEnvironmentVariableW(L"SF_URL", urls, 2000);
    for (auto& u : split(U8(urls), ',')) if (!u.empty()) g_testUrls.push_back(u);
    SetWindowPos(g_hwnd, nullptr, -4000, 0, 1280, 840, SWP_NOZORDER | SWP_NOACTIVATE);
    wchar_t tick[16] = {};  // SF_TICK: milliseconds per test step, for pages that need longer
    GetEnvironmentVariableW(L"SF_TICK", tick, 16);
    SetTimer(g_hwnd, 4, tick[0] ? _wtoi(tick) : 1000, nullptr);
  }

#endif
  if (g_torCc.empty()) { if (!startEngine()) return 1; checkAppUpdate(); }
  else {  // connect to Tor first: the web engine is only started once it can go through it
    wstring torDir = g_exe + L"\\tor", data = g_data + L"\\tor";
    makeDirs(data);
    std::thread([torDir, data] {
      string err;
      bool ok = g_tor.start(torDir, data, g_torCc, 150, err);
      postUi([ok, err] {
        if (!ok) { MessageBoxW(g_hwnd, W(err).c_str(), L"Speed Force (Tor)", MB_ICONERROR); DestroyWindow(g_hwnd); }
        else if (!startEngine()) DestroyWindow(g_hwnd);
      });
    }).detach();
  }

  MSG msg;
  while (GetMessageW(&msg, nullptr, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessageW(&msg); }
  if (mtx) CloseHandle(mtx);
  if (g_private && g_data.find(L"\\data\\private-") != wstring::npos) {  // remove this window's profile once the engine has let go of it
    wstring cmd = L"cmd.exe /c ping -n 4 127.0.0.1 >nul & rmdir /s /q \"" + g_data + L"\"";
    STARTUPINFOW si{sizeof si}; si.dwFlags = STARTF_USESHOWWINDOW; si.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION pi{};
    if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) { CloseHandle(pi.hThread); CloseHandle(pi.hProcess); }
  }
  return 0;
}
