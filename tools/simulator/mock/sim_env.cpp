// Runtime for the mocks: clock, pins, HTTP through curl.
#include "libs.h"
#include <unistd.h>
#include <sys/wait.h>

std::atomic<long long> simOffsetMs{0};
static const auto t0 = std::chrono::steady_clock::now();
unsigned long millis() {
  return (unsigned long)(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count() + simOffsetMs.load());
}
void delay(unsigned long ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
void vTaskDelay(uint32_t ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

int simPinLevel[40], simPinMode[40], simAnalog[40];
float simTemp = 24, simHum = 50, simSoilTemp = 20;
int simServoAngle = -1;
bool simRunTasks = false;
SerialMock Serial;
EspMock ESP;
WiFiMock WiFi;
HTTPUpdateMock httpUpdate;
UpdateMock Update;
TwoWire Wire;
const u8g2_cb_t* U8G2_R0 = nullptr;
const uint8_t u8g2_font_6x12_tr[1] = {0};
std::map<std::string, std::vector<uint8_t>> simNvs;
std::atomic<int> simHttpCount{0};
std::atomic<long long> simHttpMs{0};
bool simHttpLog = false;

static std::string shellQuote(const std::string& s) {
  std::string o = "'";
  for (char c : s) { if (c == '\'') o += "'\\''"; else o += c; }
  return o + "'";
}

int HTTPClient::run(const char* method, const String& payload) {
  auto a = std::chrono::steady_clock::now();
  char tmpl[] = "/tmp/hbrt_sim_req_XXXXXX";
  int fd = mkstemp(tmpl);
  if (fd >= 0) { if (payload.length()) { ssize_t w = write(fd, payload.c_str(), payload.length()); (void)w; } close(fd); }
  std::string cmd = "curl -s -m 10 -X " + std::string(method) + " -w '\\n%{http_code}' ";
  for (auto& h : headers) cmd += "-H " + shellQuote(h) + " ";
  if (strcmp(method, "GET") != 0) cmd += std::string("--data-binary @") + tmpl + " ";
  cmd += shellQuote(url.c_str());
  FILE* p = popen(cmd.c_str(), "r");
  std::string out;
  if (p) { char buf[4096]; size_t n; while ((n = fread(buf, 1, sizeof(buf), p)) > 0) out.append(buf, n); pclose(p); }
  unlink(tmpl);
  headers.clear();
  int code = -1;
  auto nl = out.rfind('\n');
  if (nl != std::string::npos) { code = atoi(out.c_str() + nl + 1); out.resize(nl); }
  body = String(out.c_str());
  long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - a).count();
  simHttpCount++; simHttpMs += ms;
  if (simHttpLog) std::printf("    http %s %s -> %d (%lld ms)\n", method, url.c_str() + 8, code, ms);
  return code <= 0 ? -1 : code;
}
