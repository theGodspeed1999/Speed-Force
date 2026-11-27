// Tor for "Try other countries": starts the bundled tor.exe (tor\ next to the program), picks the exit
// country, and tries a site through it. Everything listens on 127.0.0.1 only, and tor is stopped with the
// window that started it (job object), so nothing keeps running.
#pragma once
#include <winsock2.h>
#include <windows.h>
#include <atomic>
#include <string>
#include <thread>

namespace tor {

inline const char* const kCountries[] = {"nl", "de", "us", "gb", "fr"};
inline bool knownCountry(const std::string& cc) {
  for (auto c : kCountries) if (cc == c) return true;
  return false;
}

/** A TCP port on this PC that is free right now. */
inline int freePort() {
  WSADATA w;
  WSAStartup(MAKEWORD(2, 2), &w);
  SOCKET s = socket(AF_INET, SOCK_STREAM, 0);
  sockaddr_in a{};
  a.sin_family = AF_INET;
  a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  int len = sizeof a, port = 0;
  if (bind(s, (sockaddr*)&a, sizeof a) == 0 && getsockname(s, (sockaddr*)&a, &len) == 0) port = ntohs(a.sin_port);
  closesocket(s);
  return port;
}

struct Process {
  HANDLE proc = nullptr, job = nullptr;
  int socks = 0, control = 0;
  std::wstring dataDir;

  void stop() {
    if (job) { TerminateJobObject(job, 0); CloseHandle(job); job = nullptr; }
    if (proc) { CloseHandle(proc); proc = nullptr; }
  }

  /** Starts tor and waits until it is connected (at most [seconds]). exitCountry may be empty. */
  bool start(const std::wstring& torDir, const std::wstring& data, const std::string& exitCountry, int seconds, std::string& error) {
    stop();
    dataDir = data;
    socks = freePort();
    control = freePort();
    if (!socks || !control || socks == control) { error = "no free local port"; return false; }
    std::wstring cmd = L"\"" + torDir + L"\\tor\\tor.exe\" --SocksPort 127.0.0.1:" + std::to_wstring(socks) +
                       L" --ControlPort 127.0.0.1:" + std::to_wstring(control) + L" --CookieAuthentication 1 --DataDirectory \"" + data +
                       L"\" --GeoIPFile \"" + torDir + L"\\data\\geoip\" --GeoIPv6File \"" + torDir + L"\\data\\geoip6\" --__OwningControllerProcess " +
                       std::to_wstring(GetCurrentProcessId()) + L" --Log \"notice stdout\"";
    if (!exitCountry.empty()) cmd += L" --ExitNodes {" + std::wstring(exitCountry.begin(), exitCountry.end()) + L"} --StrictNodes 1";
    SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
    HANDLE read = nullptr, write = nullptr;
    if (!CreatePipe(&read, &write, &sa, 0)) { error = "could not start tor"; return false; }
    SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW si{sizeof si};
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = si.hStdError = write;
    PROCESS_INFORMATION pi{};
    BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW | CREATE_SUSPENDED, nullptr, nullptr, &si, &pi);
    CloseHandle(write);
    if (!ok) { CloseHandle(read); error = "tor.exe is missing or was blocked"; return false; }
    job = CreateJobObjectW(nullptr, nullptr);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION lim{};
    lim.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    SetInformationJobObject(job, JobObjectExtendedLimitInformation, &lim, sizeof lim);
    AssignProcessToJobObject(job, pi.hProcess);
    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    proc = pi.hProcess;

    std::string log;
    ULONGLONG until = GetTickCount64() + seconds * 1000ULL;
    bool connected = false;
    while (!connected && GetTickCount64() < until) {
      DWORD avail = 0;
      if (!PeekNamedPipe(read, nullptr, 0, nullptr, &avail, nullptr)) break;
      if (!avail) {
        if (WaitForSingleObject(proc, 150) == WAIT_OBJECT_0) break;
        continue;
      }
      char buf[4096];
      DWORD n = 0;
      if (!ReadFile(read, buf, (DWORD)std::min<size_t>(avail, sizeof buf), &n, nullptr)) break;
      log.append(buf, n);
      connected = log.find("Bootstrapped 100%") != std::string::npos;
      if (log.size() > 8000) log.erase(0, log.size() - 4000);
    }
    if (!connected) {
      CloseHandle(read);
      stop();
      error = "Tor could not connect. Your network may block it.";
      return false;
    }
    // keep reading so tor never stalls on a full pipe; the thread ends when tor does
    std::thread([read] { char b[4096]; DWORD n; while (ReadFile(read, b, sizeof b, &n, nullptr) && n) {} CloseHandle(read); }).detach();
    return true;
  }

  /** Asks the running tor to leave from this country from now on. */
  bool setExit(const std::string& cc) {
    if (!knownCountry(cc)) return false;
    HANDLE f = CreateFileW((dataDir + L"\\control_auth_cookie").c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    unsigned char c[32];
    DWORD got = 0;
    ReadFile(f, c, 32, &got, nullptr);
    CloseHandle(f);
    if (got != 32) return false;
    static const char* hex = "0123456789abcdef";
    std::string auth = "AUTHENTICATE ";
    for (unsigned char b : c) { auth += hex[b >> 4]; auth += hex[b & 15]; }
    SOCKET s = socket(AF_INET, SOCK_STREAM, 0);
    DWORD wait = 10000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (const char*)&wait, sizeof wait);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons((u_short)control);
    auto say = [s](const std::string& line) {
      std::string out = line + "\r\n";
      if (send(s, out.c_str(), (int)out.size(), 0) <= 0) return false;
      char r[512];
      int n = recv(s, r, sizeof r, 0);
      return n >= 3 && std::string(r, 3) == "250";
    };
    bool ok = connect(s, (sockaddr*)&a, sizeof a) == 0 && say(auth) && say("SETCONF ExitNodes={" + cc + "} StrictNodes=1");
    closesocket(s);
    return ok;
  }
};

/**
 * Tries https://host/ through tor and returns the site's answer code, 0 when nothing came back.
 * Uses Windows' own curl; the name is looked up by tor, not by this PC. [tag] keeps countries on separate circuits.
 * The caller must have checked that host holds only letters, digits, dots and hyphens.
 */
inline int fetch(int socksPort, const std::string& host, const std::string& tag) {
  wchar_t sys[MAX_PATH];
  GetSystemDirectoryW(sys, MAX_PATH);
  std::wstring h(host.begin(), host.end()), t(tag.begin(), tag.end());
  std::wstring cmd = L"\"" + std::wstring(sys) + L"\\curl.exe\" -s -I -o NUL -w %{http_code} --max-time 25 --socks5-hostname 127.0.0.1:" +
                     std::to_wstring(socksPort) + L" --proxy-user " + t + L":x https://" + h + L"/";
  SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
  HANDLE read = nullptr, write = nullptr;
  if (!CreatePipe(&read, &write, &sa, 0)) return 0;
  SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0);
  STARTUPINFOW si{sizeof si};
  si.dwFlags = STARTF_USESTDHANDLES;
  si.hStdOutput = write;
  PROCESS_INFORMATION pi{};
  BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
  CloseHandle(write);
  std::string out;
  if (ok) {
    char b[64];
    DWORD n = 0;
    while (ReadFile(read, b, sizeof b, &n, nullptr) && n) out.append(b, n);
    WaitForSingleObject(pi.hProcess, 5000);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
  }
  CloseHandle(read);
  return atoi(out.c_str());
}

}  // namespace tor
