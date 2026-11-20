// Minimal host reproduction of the Android JNI call path (extension::Module::execute) for #1988.
// usage: runner <pte> <method> <pcm.f32> <steps> <window> <commit> <spf> <filter> <attn> <wc> <ws> <out_prefix>
// wc/ws: -1 = build default, else XNNPACK weight_cache_enabled / workspace_sharing_mode load options.
#include <executorch/extension/module/module.h>
#include <executorch/extension/tensor/tensor.h>
#include <executorch/runtime/backend/backend_options_map.h>
#include <executorch/runtime/backend/options.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>
using namespace executorch::extension;
using namespace executorch::runtime;
static std::vector<float> readf(const char* p) {
  std::ifstream f(p, std::ios::binary | std::ios::ate); size_t n = f.tellg(); f.seekg(0);
  std::vector<float> v(n / 4); f.read((char*)v.data(), n); return v;
}
static void writef(const std::string& p, const float* d, size_t n) { std::ofstream f(p, std::ios::binary); f.write((const char*)d, n * 4); }
int main(int argc, char** argv) {
  if (argc != 13) { fprintf(stderr, "bad args\n"); return 2; }
  const char* pte = argv[1]; std::string method = argv[2]; auto pcm = readf(argv[3]);
  int steps = atoi(argv[4]), window = atoi(argv[5]), commit = atoi(argv[6]), spf = atoi(argv[7]);
  int nf = atoi(argv[8]), na = atoi(argv[9]), wc = atoi(argv[10]), ws = atoi(argv[11]); std::string out = argv[12];
  Module m(pte, Module::LoadMode::Mmap);
  BackendOption opts[2]; size_t no = 0;
  if (wc >= 0) { std::strcpy(opts[no].key, "weight_cache_enabled"); opts[no].value = wc != 0; no++; }
  if (ws >= 0) { std::strcpy(opts[no].key, "workspace_sharing_mode"); opts[no].value = ws; no++; }
  Error err;
  if (no) { LoadBackendOptionsMap map; map.set_options("XnnpackBackend", Span<BackendOption>(opts, no)); err = m.load(map); }
  else err = m.load();
  if (err != Error::Ok) { fprintf(stderr, "load failed %d\n", (int)err); return 1; }
  std::vector<float> filter(nf, 0.f), attn(na, 0.f), win(window);
  for (int s = 0; s < steps; s++) {
    size_t start = (size_t)s * commit * spf;
    for (int i = 0; i < window; i++) win[i] = start + i < pcm.size() ? pcm[start + i] : 0.f;
    auto tw = from_blob(win.data(), {1, window}); auto tf = from_blob(filter.data(), {nf}); auto ta = from_blob(attn.data(), {na});
    auto r = m.execute(method, {tw, tf, ta});
    if (!r.ok()) { fprintf(stderr, "execute failed %d\n", (int)r.error()); return 1; }
    auto& o = r.get();
    auto lg = o[0].toTensor(); auto hd = o[3].toTensor();
    std::memcpy(filter.data(), o[1].toTensor().const_data_ptr<float>(), nf * 4);
    std::memcpy(attn.data(), o[2].toTensor().const_data_ptr<float>(), na * 4);
    writef(out + "logits" + std::to_string(s) + ".f32", lg.const_data_ptr<float>(), lg.numel());
    writef(out + "hidden" + std::to_string(s) + ".f32", hd.const_data_ptr<float>(), hd.numel());
    const float* l = lg.const_data_ptr<float>(); int V = lg.size(2);
    printf("step %d argmax", s);
    for (int t = 0; t < commit; t++) { int b = 0; for (int v = 1; v < V; v++) if (l[t * V + v] > l[t * V + b]) b = v; printf(" %d", b); }
    printf("\n");
  }
  return 0;
}
