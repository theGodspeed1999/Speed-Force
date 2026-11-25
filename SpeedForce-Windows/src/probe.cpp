#define UNICODE
#define _UNICODE
#include <windows.h>
#include <functional>
#include "WebView2.h"
template <class I, class A, class B> struct Cb2 : I {
  std::function<HRESULT(A, B)> f; ULONG n = 1;
  explicit Cb2(std::function<HRESULT(A, B)> fn) : f(std::move(fn)) {}
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID r, void** p) override { (void)r; *p = this; AddRef(); return S_OK; }
  ULONG STDMETHODCALLTYPE AddRef() override { return ++n; }
  ULONG STDMETHODCALLTYPE Release() override { ULONG k = --n; if (!k) delete this; return k; }
  HRESULT STDMETHODCALLTYPE Invoke(A a, B b) override { return f(a, b); }
};
int main() { auto* h = new Cb2<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler, HRESULT, ICoreWebView2Environment*>([](HRESULT, ICoreWebView2Environment*) { return S_OK; }); h->Release(); return 0; }
