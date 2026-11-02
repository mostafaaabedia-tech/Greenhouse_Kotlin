// Stand-ins for the ESP32 libraries the firmware uses.
#pragma once
#include "Arduino.h"

/* ---------- WiFi ---------- */
#define WL_CONNECTED 3
#define WL_DISCONNECTED 6
#define WIFI_STA 1
#define WIFI_AP 2
#define WIFI_AP_STA 3
#define WIFI_SCAN_FAILED -2
#define WIFI_SCAN_RUNNING -1
struct WiFiMock {
  int st = WL_CONNECTED;
  int status() { return st; }
  void begin(const char*, const char*) {}
  void disconnect(bool = false) {}
  IPAddress localIP() { return IPAddress(); }
  IPAddress softAPIP() { return IPAddress(192, 168, 4, 1); }
  int RSSI(int = 0) { return -58; }
  String SSID(int = 0) { return String("1"); }
  void mode(int) {}
  void persistent(bool) {}
  int scanComplete() { return 0; }
  int scanNetworks(bool = false) { return 0; }
  void setAutoReconnect(bool) {}
  void setHostname(const char*) {}
  void setSleep(bool) {}
  bool softAP(const char*, const char* = nullptr) { return true; }
  void softAPdisconnect(bool) {}
  int softAPgetStationNum() { return 0; }
  int encryptionType(int) { return 0; }
};
extern WiFiMock WiFi;

struct WiFiClientSecure {
  void setInsecure() {}
  void setHandshakeTimeout(int) {}
  void stop() {}
};
struct WiFiClient {};

/* ---------- HTTP (real requests through the curl program) ---------- */
#define HTTPC_FORCE_FOLLOW_REDIRECTS 2
extern std::atomic<int> simHttpCount;
extern std::atomic<long long> simHttpMs;
struct HTTPClient {
  String url, body; std::vector<std::string> headers;
  bool begin(WiFiClientSecure&, const String& u) { url = u; return true; }
  bool begin(WiFiClient&, const String& u) { url = u; return true; }
  void addHeader(const String& k, const String& v) { headers.push_back(std::string(k.c_str()) + ": " + v.c_str()); }
  void setReuse(bool) {}
  void setTimeout(int) {}
  void setFollowRedirects(int) {}
  int GET() { return run("GET", ""); }
  int POST(const String& b) { return run("POST", b); }
  int sendRequest(const char* m, const String& b) { return run(m, b); }
  String getString() { return body; }
  void end() {}
  int run(const char* method, const String& payload);
};

struct HTTPUpdateMock {
  void setFollowRedirects(int) {}
  void rebootOnUpdate(bool) {}
  int update(WiFiClientSecure&, const String&) { return 1; }
  int getLastError() { return -1; }
  String getLastErrorString() { return String("sim: no updates"); }
};
typedef int t_httpUpdate_return;
#define HTTP_UPDATE_OK 2
extern HTTPUpdateMock httpUpdate;

#define UPDATE_SIZE_UNKNOWN 0xFFFFFFFF
struct UpdateMock {
  bool begin(uint32_t) { return true; }
  size_t write(uint8_t*, size_t n) { return n; }
  bool end(bool) { return true; }
  bool hasError() { return false; }
  const char* errorString() { return ""; }
};
extern UpdateMock Update;

/* ---------- web server ---------- */
enum HTTPMethod { HTTP_ANY, HTTP_GET, HTTP_POST };
enum { UPLOAD_FILE_START, UPLOAD_FILE_WRITE, UPLOAD_FILE_END, UPLOAD_FILE_ABORTED };
struct HTTPUpload { int status = 0; String filename; size_t currentSize = 0, totalSize = 0; uint8_t buf[16]; };
struct WebServer {
  HTTPUpload up;
  WebServer(int) {}
  void on(const char*, std::function<void()>) {}
  void on(const char*, HTTPMethod, std::function<void()>) {}
  void on(const char*, HTTPMethod, std::function<void()>, std::function<void()>) {}
  void onNotFound(std::function<void()>) {}
  void begin() {}
  void handleClient() {}
  String arg(const char*) { return String(""); }
  bool hasArg(const char*) { return false; }
  void collectHeaders(const char**, int) {}
  String header(const char*) { return String(""); }
  void send(int, const char* = "", const String& = String("")) {}
  void sendHeader(const char*, const String&, bool = false) {}
  HTTPUpload& upload() { return up; }
};
struct DNSServer { bool start(int, const char*, IPAddress) { return true; } void processNextRequest() {} void stop() {} };

/* ---------- WebSocket (what the board sends is recorded) ---------- */
typedef enum { WStype_ERROR, WStype_DISCONNECTED, WStype_CONNECTED, WStype_TEXT, WStype_BIN } WStype_t;
struct WebSocketsServer {
  std::mutex m; std::vector<std::string> sent; int clients = 1;
  WebSocketsServer(int) {}
  void begin() {}
  void loop() {}
  void onEvent(void (*)(uint8_t, WStype_t, uint8_t*, size_t)) {}
  void enableHeartbeat(int, int, int) {}
  int connectedClients() { return clients; }
  bool broadcastTXT(const String& s) { std::lock_guard<std::mutex> g(m); sent.push_back(s.c_str()); return true; }
  bool broadcastTXT(const char* s) { std::lock_guard<std::mutex> g(m); sent.push_back(s); return true; }
  bool sendTXT(uint8_t, const String& s) { std::lock_guard<std::mutex> g(m); sent.push_back(s.c_str()); return true; }
  bool sendTXT(uint8_t, const char* s) { std::lock_guard<std::mutex> g(m); sent.push_back(s); return true; }
};

/* ---------- sensors and outputs ---------- */
#define DHT11 11
#define DHT22 22
extern float simTemp, simHum, simSoilTemp;
struct DHT { DHT(int, int) {} void begin() {} float readTemperature() { return simTemp; } float readHumidity() { return simHum; } };
struct OneWire { OneWire(int) {} };
#define DEVICE_DISCONNECTED_C -127
struct DallasTemperature {
  DallasTemperature(OneWire*) {}
  void begin() {} void setWaitForConversion(bool) {} void requestTemperatures() {}
  float getTempCByIndex(int) { return simSoilTemp; }
};
extern int simServoAngle;
struct ESP32PWM { static void allocateTimer(int) {} };
struct Servo { void setPeriodHertz(int) {} int attach(int, int, int) { return 1; } void write(int a) { simServoAngle = a; } };
struct TwoWire { void begin(int, int) {} };
extern TwoWire Wire;
#define U8X8_PIN_NONE 255
struct u8g2_cb_t {};
extern const u8g2_cb_t* U8G2_R0;
extern const uint8_t u8g2_font_6x12_tr[];
struct U8G2_SH1106_128X64_NONAME_F_HW_I2C {
  U8G2_SH1106_128X64_NONAME_F_HW_I2C(const u8g2_cb_t*, int) {}
  void begin() {} void setPowerSave(int) {} void clearBuffer() {} void sendBuffer() {}
  void setFont(const uint8_t*) {} void drawStr(int, int, const char*) {} void drawBox(int, int, int, int) {}
  void drawLine(int, int, int, int) {} void setCursor(int, int) {} void setDrawColor(int) {}
  template <class T> void print(T) {}
  template <class T> void print(T, int) {}
};

/* ---------- Preferences (kept in memory) ---------- */
extern std::map<std::string, std::vector<uint8_t>> simNvs;
struct Preferences {
  bool begin(const char*, bool = false) { return true; }
  void end() {}
  template <class T> T getT(const char* k, T d) { auto it = simNvs.find(k); if (it == simNvs.end() || it->second.size() != sizeof(T)) return d; T v; memcpy(&v, it->second.data(), sizeof(T)); return v; }
  template <class T> size_t putT(const char* k, T v) { simNvs[k] = std::vector<uint8_t>((uint8_t*)&v, (uint8_t*)&v + sizeof(T)); return sizeof(T); }
  bool getBool(const char* k, bool d = false) { return getT(k, d); }
  float getFloat(const char* k, float d = 0) { return getT(k, d); }
  int32_t getInt(const char* k, int32_t d = 0) { return getT(k, d); }
  int32_t getLong(const char* k, int32_t d = 0) { return getT(k, d); }
  uint8_t getUChar(const char* k, uint8_t d = 0) { return getT(k, d); }
  size_t putBool(const char* k, bool v) { return putT(k, v); }
  size_t putFloat(const char* k, float v) { return putT(k, v); }
  size_t putInt(const char* k, int32_t v) { return putT(k, v); }
  size_t putLong(const char* k, int32_t v) { return putT(k, v); }
  size_t putUChar(const char* k, uint8_t v) { return putT(k, v); }
  String getString(const char* k, const String& d = String()) { auto it = simNvs.find(k); return it == simNvs.end() ? d : String(std::string(it->second.begin(), it->second.end()).c_str()); }
  size_t putString(const char* k, const String& v) { simNvs[k] = std::vector<uint8_t>(v.c_str(), v.c_str() + v.length()); return v.length(); }
  size_t getBytesLength(const char* k) { auto it = simNvs.find(k); return it == simNvs.end() ? 0 : it->second.size(); }
  size_t getBytes(const char* k, void* p, size_t n) { auto it = simNvs.find(k); if (it == simNvs.end()) return 0; n = std::min(n, it->second.size()); memcpy(p, it->second.data(), n); return n; }
  size_t putBytes(const char* k, const void* p, size_t n) { simNvs[k] = std::vector<uint8_t>((const uint8_t*)p, (const uint8_t*)p + n); return n; }
};
