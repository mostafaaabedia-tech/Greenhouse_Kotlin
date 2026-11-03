// Minimal ESP32/Arduino environment so the real greenhouse firmware runs on a PC.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <ctime>
#include <string>
#include <map>
#include <vector>
#include <deque>
#include <mutex>
#include <thread>
#include <atomic>
#include <functional>
#include <algorithm>
#include <chrono>
#define ARDUINOJSON_ENABLE_ARDUINO_STREAM 0
#define ARDUINOJSON_ENABLE_ARDUINO_PRINT 0
#define ARDUINOJSON_ENABLE_PROGMEM 0
#define ARDUINOJSON_ENABLE_ARDUINO_STRING 1
#include "pgmspace.h"
#include "stdlib_noniso.h"
#include "WString.h"

#define ARDUINO 10813
using std::min; using std::max; using std::isnan;
inline void configTime(long, int, const char*, const char* = nullptr, const char* = nullptr) {}
typedef uint8_t byte;
#define HIGH 1
#define LOW 0
#define OUTPUT 1
#define INPUT 0
#define INPUT_PULLUP 2
#define PROGMEM
#define F(x) (x)
#define constrain(v, lo, hi) ((v) < (lo) ? (lo) : ((v) > (hi) ? (hi) : (v)))
inline long map(long x, long a, long b, long c, long d) { return (x - a) * (d - c) / (b - a) + c; }

/* ---------- clock ---------- */
extern std::atomic<long long> simOffsetMs;
unsigned long millis();
void delay(unsigned long ms);
inline void yield() {}

/* ---------- pins ---------- */
extern int simPinLevel[40];
extern int simPinMode[40];
extern int simAnalog[40];
inline void pinMode(int p, int m) { if (p >= 0 && p < 40) simPinMode[p] = m; }
struct PinEvent { int pin, level; unsigned long at; };
extern std::vector<PinEvent> simPinLog;
inline void digitalWrite(int p, int v) { if (p >= 0 && p < 40) { if (simPinLevel[p] != v) simPinLog.push_back({p, v, millis()}); simPinLevel[p] = v; } }
inline int digitalRead(int p) { return (p >= 0 && p < 40) ? simPinLevel[p] : 1; }
inline int analogRead(int p) { return (p >= 0 && p < 40) ? simAnalog[p] : 0; }

/* ---------- Serial ---------- */
struct SerialMock {
  bool quiet = false;
  std::string input;                         // what the tests "type" into the Serial Monitor
  int available() { return (int)input.size(); }
  int read() { if (input.empty()) return -1; char c = input[0]; input.erase(0, 1); return c; }
  void begin(long) {}
  void println(const String& s) { if (!quiet) printf("  [board] %s\n", s.c_str()); }
  void println(const char* s) { if (!quiet) printf("  [board] %s\n", s); }
  template <class... A> void printf(const char* f, A... a) { if (quiet) return; std::printf("  [board] "); std::printf(f, a...); }
  void print(const String& s) { if (!quiet) std::printf("%s", s.c_str()); }
};
extern SerialMock Serial;

/* ---------- ESP ---------- */
struct EspMock {
  uint64_t getEfuseMac() { return 0xA1B2C3D4E5F6ULL; }
  uint32_t getFreeHeap() { return 180000; }
  uint32_t getMinFreeHeap() { return 150000; }
  [[noreturn]] void restart() { std::printf("  [board] *** ESP.restart() ***\n"); std::exit(3); }
};
extern EspMock ESP;
inline uint32_t esp_random() { return (uint32_t)rand(); }
typedef enum { ESP_RST_UNKNOWN, ESP_RST_POWERON, ESP_RST_EXT, ESP_RST_SW, ESP_RST_PANIC, ESP_RST_INT_WDT, ESP_RST_TASK_WDT, ESP_RST_WDT, ESP_RST_DEEPSLEEP, ESP_RST_BROWNOUT, ESP_RST_SDIO } esp_reset_reason_t;
inline esp_reset_reason_t esp_reset_reason() { return ESP_RST_POWERON; }

/* ---------- FreeRTOS ---------- */
typedef std::recursive_mutex* SemaphoreHandle_t;
#define portMAX_DELAY 0xFFFFFFFF
#define pdTRUE 1
#define pdFALSE 0
#define pdMS_TO_TICKS(x) (x)
inline SemaphoreHandle_t xSemaphoreCreateMutex() { return new std::recursive_mutex(); }
inline int xSemaphoreTake(SemaphoreHandle_t m, uint32_t) { m->lock(); return pdTRUE; }
inline int xSemaphoreGive(SemaphoreHandle_t m) { m->unlock(); return pdTRUE; }
struct QueueMock { std::mutex m; std::deque<std::vector<uint8_t>> q; size_t item, cap; };
typedef QueueMock* QueueHandle_t;
inline QueueHandle_t xQueueCreate(size_t n, size_t sz) { auto q = new QueueMock(); q->item = sz; q->cap = n; return q; }
inline int xQueueSend(QueueHandle_t q, const void* p, uint32_t) {
  std::lock_guard<std::mutex> g(q->m);
  if (q->q.size() >= q->cap) return pdFALSE;
  q->q.emplace_back((const uint8_t*)p, (const uint8_t*)p + q->item); return pdTRUE;
}
inline int xQueueReceive(QueueHandle_t q, void* p, uint32_t) {
  std::lock_guard<std::mutex> g(q->m);
  if (q->q.empty()) return pdFALSE;
  memcpy(p, q->q.front().data(), q->item); q->q.pop_front(); return pdTRUE;
}
void vTaskDelay(uint32_t ms);
extern bool simRunTasks;
inline int xTaskCreatePinnedToCore(void (*f)(void*), const char*, int, void*, int, void*, int) {
  if (simRunTasks) std::thread([f] { f(nullptr); }).detach();
  return 1;
}

/* ---------- network basics ---------- */
struct IPAddress {
  uint8_t b[4] = {192, 168, 43, 50};
  IPAddress() {}
  IPAddress(int a, int c, int d, int e) { b[0] = a; b[1] = c; b[2] = d; b[3] = e; }
  String toString() const { char s[20]; snprintf(s, sizeof(s), "%d.%d.%d.%d", b[0], b[1], b[2], b[3]); return String(s); }
};
