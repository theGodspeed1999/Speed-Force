// Small helpers: text conversion, files, web addresses, the compact ad-host table.
#pragma once
#define UNICODE
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <algorithm>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>
#include "json.hpp"

using json = nlohmann::json;
using std::string;
using std::wstring;

inline wstring W(const string& s) {
  if (s.empty()) return L"";
  int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
  wstring w(n, 0);
  MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
  return w;
}
inline string U8(const wchar_t* w, int len = -1) {
  if (!w) return "";
  if (len < 0) len = (int)wcslen(w);
  if (!len) return "";
  int n = WideCharToMultiByte(CP_UTF8, 0, w, len, nullptr, 0, nullptr, nullptr);
  string s(n, 0);
  WideCharToMultiByte(CP_UTF8, 0, w, len, s.data(), n, nullptr, nullptr);
  return s;
}
inline string U8(const wstring& w) { return U8(w.c_str(), (int)w.size()); }

inline string readFile(const wstring& path) {
  HANDLE h = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) return "";
  string out;
  char buf[65536];
  DWORD n;
  while (ReadFile(h, buf, sizeof buf, &n, nullptr) && n) out.append(buf, n);
  CloseHandle(h);
  return out;
}
inline bool writeFile(const wstring& path, const string& data) {
  wstring tmp = path + L".tmp";
  HANDLE h = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) return false;
  size_t done = 0;
  while (done < data.size()) {
    DWORD n = 0;
    if (!WriteFile(h, data.data() + done, (DWORD)std::min<size_t>(data.size() - done, 1 << 20), &n, nullptr) || !n) break;
    done += n;
  }
  CloseHandle(h);
  if (done != data.size()) { DeleteFileW(tmp.c_str()); return false; }
  return MoveFileExW(tmp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING) != 0;
}
inline json readJson(const wstring& path, json fallback) {
  string s = readFile(path);
  if (s.empty()) return fallback;
  auto j = json::parse(s, nullptr, false);
  return j.is_discarded() ? fallback : j;
}
inline bool fileExists(const wstring& p) { return GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES; }
inline void makeDirs(const wstring& p) {
  for (size_t i = 3; i < p.size(); i++)
    if (p[i] == L'\\' || p[i] == L'/') CreateDirectoryW(p.substr(0, i).c_str(), nullptr);
  CreateDirectoryW(p.c_str(), nullptr);
}
inline wstring dirOf(const wstring& p) { size_t i = p.find_last_of(L"\\/"); return i == wstring::npos ? L"" : p.substr(0, i); }
inline wstring exeDir() {
  wchar_t b[MAX_PATH * 2];
  GetModuleFileNameW(nullptr, b, MAX_PATH * 2);
  return dirOf(b);
}
inline ULONGLONG nowMs() { return GetTickCount64(); }
inline int64_t unixMs() {
  FILETIME ft; GetSystemTimeAsFileTime(&ft);
  uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
  return (int64_t)((t - 116444736000000000ULL) / 10000);
}

inline string lower(string s) { for (auto& c : s) c = (char)tolower((unsigned char)c); return s; }
inline bool startsWith(const string& s, const string& p) { return s.rfind(p, 0) == 0; }
inline bool endsWith(const string& s, const string& p) { return s.size() >= p.size() && s.compare(s.size() - p.size(), p.size(), p) == 0; }
inline string trim(const string& s) {
  size_t a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
  return a == string::npos ? "" : s.substr(a, b - a + 1);
}
inline std::vector<string> split(const string& s, char d) {
  std::vector<string> out; string cur;
  for (char c : s) { if (c == d) { out.push_back(cur); cur.clear(); } else cur += c; }
  out.push_back(cur);
  return out;
}

// ---------------------------------------------------------------- web addresses (port of urls.js)
inline bool isHttp(const string& u) { return startsWith(lower(u.substr(0, 8)), "http://") || startsWith(lower(u.substr(0, 8)), "https://"); }

/** Host of an address without "www.", lower case; "" when there is none. */
inline string hostOf(const string& url) {
  size_t i = url.find("://");
  if (i == string::npos) return "";
  size_t s = i + 3, e = url.find_first_of("/?#", s);
  string h = url.substr(s, e == string::npos ? string::npos : e - s);
  size_t at = h.rfind('@');
  if (at != string::npos) h = h.substr(at + 1);
  if (!h.empty() && h[0] == '[') return lower(h);
  size_t colon = h.find(':');
  if (colon != string::npos) h = h.substr(0, colon);
  h = lower(h);
  if (startsWith(h, "www.")) h = h.substr(4);
  return h;
}
inline string originOf(const string& url) {
  size_t i = url.find("://");
  if (i == string::npos) return "";
  size_t e = url.find_first_of("/?#", i + 3);
  return url.substr(0, e == string::npos ? string::npos : e);
}
inline string siteOfHost(string h) {
  h = lower(h);
  if (startsWith(h, "www.")) h = h.substr(4);
  auto p = split(h, '.');
  if (p.size() <= 2) return h;
  bool two = p.back().size() == 2 && p[p.size() - 2].size() <= 3;
  size_t take = two ? 3 : 2;
  string out;
  for (size_t i = p.size() - take; i < p.size(); i++) out += (out.empty() ? "" : ".") + p[i];
  return out;
}
inline bool sameSite(const string& a, const string& b) {
  string ha = hostOf(a), hb = hostOf(b);
  return !ha.empty() && !hb.empty() && siteOfHost(ha) == siteOfHost(hb);
}
inline string dedupeKey(const string& url) {
  string u = url.substr(0, url.find('#'));
  return lower(u.substr(0, u.find('?')));
}
inline string fileNameOf(const string& url) {
  string u = url.substr(0, url.find('#'));
  u = u.substr(0, u.find('?'));
  while (!u.empty() && u.back() == '/') u.pop_back();
  size_t i = u.rfind('/');
  return i == string::npos ? u : u.substr(i + 1);
}
inline string classifyStream(const string& url) {
  string l = lower(url);
  if (!startsWith(l, "http")) return "";
  string path = l.substr(0, l.find('#'));
  path = path.substr(0, path.find('?'));
  if (endsWith(path, ".m3u8")) return "HLS";
  if (endsWith(path, ".mpd")) return "DASH";
  if (endsWith(path, ".mp4") || endsWith(path, ".m4v") || endsWith(path, ".mov")) return "MP4";
  if (endsWith(path, ".webm")) return "WEBM";
  return "";
}
inline bool isLocalHost(const string& h) {
  if (h.empty() || h == "localhost" || endsWith(h, ".local") || endsWith(h, ".lan") || endsWith(h, ".home")) return true;
  int a, b, c, d;
  if (sscanf(h.c_str(), "%d.%d.%d.%d", &a, &b, &c, &d) == 4)
    return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
  return false;
}
inline string upgradeToHttps(const string& url) {
  if (startsWith(lower(url.substr(0, 7)), "http://") && !isLocalHost(hostOf(url))) return "https://" + url.substr(7);
  return url;
}
inline string stripTracking(const string& url) {
  static const std::set<string> P = {"fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid", "igshid", "mc_eid", "mc_cid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "s_cid", "rb_clickid", "_openstat", "wickedid", "srsltid"};
  size_t q = url.find('?');
  if (q == string::npos) return url;
  size_t hash = url.find('#', q);
  string query = hash == string::npos ? url.substr(q + 1) : url.substr(q + 1, hash - q - 1);
  string frag = hash == string::npos ? "" : url.substr(hash);
  string kept;
  for (auto& part : split(query, '&')) {
    if (part.empty()) continue;
    string name = lower(part.substr(0, part.find('=')));
    if (startsWith(name, "utm_") || P.count(name)) continue;
    kept += (kept.empty() ? "" : "&") + part;
  }
  return url.substr(0, q) + (kept.empty() ? "" : "?" + kept) + frag;
}
inline string urlEncode(const string& s) {
  static const char* hex = "0123456789ABCDEF";
  string o;
  for (unsigned char c : s) {
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') o += (char)c;
    else if (c == ' ') o += '+';
    else { o += '%'; o += hex[c >> 4]; o += hex[c & 15]; }
  }
  return o;
}
inline const std::map<string, string>& searchEngines() {
  static const std::map<string, string> e = {{"DuckDuckGo", "https://duckduckgo.com/?q="}, {"Google", "https://www.google.com/search?q="}, {"Bing", "https://www.bing.com/search?q="}, {"Brave", "https://search.brave.com/search?q="}};
  return e;
}
inline string toUrlOrSearch(const string& input, const string& base) {
  string t = trim(input);
  if (t.empty()) return "";
  string l = lower(t);
  if (startsWith(l, "http://") || startsWith(l, "https://") || startsWith(l, "about:") || startsWith(l, "file:")) return t;
  static const std::regex dom(R"(^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$)");
  static const std::regex local(R"(^localhost(:\d+)?(/.*)?$)", std::regex::icase);
  if (t.find(' ') == string::npos && std::regex_match(t, dom)) return "https://" + t;
  if (std::regex_match(t, local)) return "http://" + t;
  return base + urlEncode(t);
}
inline string searchQueryOf(const string& url) {
  for (auto& [n, base] : searchEngines()) {
    if (startsWith(url, base.substr(0, base.find('?')))) {
      size_t i = url.find("q=");
      if (i == string::npos) return "";
      string q = url.substr(i + 2, url.find_first_of("&#", i + 2) - i - 2), o;
      for (size_t k = 0; k < q.size(); k++) {
        if (q[k] == '+') o += ' ';
        else if (q[k] == '%' && k + 2 < q.size()) { o += (char)strtol(q.substr(k + 1, 2).c_str(), nullptr, 16); k += 2; }
        else o += q[k];
      }
      return o;
    }
  }
  return "";
}
inline bool isMediaExt(string ext) {
  static const std::set<string> M = {"mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi", "flv", "m4a", "aac", "mp3", "opus", "ogg", "oga", "wav", "flac"};
  if (!ext.empty() && ext[0] == '.') ext = ext.substr(1);
  return M.count(lower(ext)) > 0;
}
inline bool isRotatingAdLoader(const string& url) {
  size_t i = url.find("://");
  if (i == string::npos) return false;
  string rest = url.substr(i + 3);
  size_t sl = rest.find('/');
  string path = "/" + (sl == string::npos ? "" : rest.substr(sl + 1));
  path = path.substr(0, path.find_first_of("?#"));
  static const std::regex a(R"(^/\d{1,4}/[0-9a-f]{32}/?$)"), b(R"(^\d{3,7}\.[a-z]{6,}\.[a-z]{2,6}$)"), c(R"(^/\d{1,3}/js/\d{3,9}/?$)"), d(R"(^\d{5,10}-\d{3,7}-ex\.)");
  if (std::regex_match(path, a)) return true;
  string h = hostOf(url);
  if (h.empty()) return false;
  if (std::regex_match(h, b) && std::regex_match(path, c)) return true;
  return std::regex_search(h, d);
}

// ---------------------------------------------------------------- compact host table
/** Sorted host names in one block of memory: about 2 MB for 100,000 names (no per-name allocations). */
class HostTable {
 public:
  void load(const wstring& path) {
    blob_ = readFile(path);
    offs_.clear();
    size_t start = 0;
    for (size_t i = 0; i <= blob_.size(); i++) {
      if (i == blob_.size() || blob_[i] == '\n') {
        if (i > start) {
          size_t e = i;
          if (blob_[e - 1] == '\r') e--;
          if (e > start) { offs_.push_back((uint32_t)start); lens_.push_back((uint16_t)std::min<size_t>(e - start, 65535)); }
        }
        start = i + 1;
      }
    }
    // the file is written sorted, but sort again so that any list works
    std::vector<uint32_t> idx(offs_.size());
    for (size_t i = 0; i < idx.size(); i++) idx[i] = (uint32_t)i;
    std::sort(idx.begin(), idx.end(), [&](uint32_t a, uint32_t b) { return view(a) < view(b); });
    std::vector<uint32_t> o2(offs_.size());
    std::vector<uint16_t> l2(offs_.size());
    for (size_t i = 0; i < idx.size(); i++) { o2[i] = offs_[idx[i]]; l2[i] = lens_[idx[i]]; }
    offs_.swap(o2);
    lens_.swap(l2);
  }
  size_t size() const { return offs_.size(); }
  bool has(std::string_view h) const {
    size_t lo = 0, hi = offs_.size();
    while (lo < hi) {
      size_t mid = (lo + hi) / 2;
      auto v = view((uint32_t)mid);
      if (v == h) return true;
      if (v < h) lo = mid + 1; else hi = mid;
    }
    return false;
  }
  /** The host or any parent domain of it is in the table (stops before the top-level domain). */
  bool blocks(string h) const {
    if (h.empty()) return false;
    for (;;) {
      if (has(h)) return true;
      size_t dot = h.find('.');
      if (dot == string::npos) return false;
      h = h.substr(dot + 1);
      if (h.find('.') == string::npos) return false;
    }
  }

 private:
  std::string_view view(uint32_t i) const { return std::string_view(blob_.data() + offs_[i], lens_[i]); }
  string blob_;
  std::vector<uint32_t> offs_;
  std::vector<uint16_t> lens_;
};
