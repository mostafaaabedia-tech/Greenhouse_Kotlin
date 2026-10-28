/* HBRT AGRO Smart Greenhouse - firmware 6.0
   Developed by Mostafa Mesbah Aabedi & Milad Ahmadi
   HBRT - Herat Boys Robotic Team

   What the board does
   - Reads the sensors every 2 s and runs the greenhouse (automatic, manual or away mode)
   - Local control on Wi-Fi (WebSocket port 81) and from anywhere through Firebase
   - Timed overrides: "fan on for 30 min, then back to automatic"
   - Watering times (skipped when rain is forecast), soil-based watering, your own rules
   - Weather for your location (Open-Meteo, no key), frost warning
   - Crop, planting date, growth stage and harvest estimate (growing degree days)
   - Water and electricity used per day, 7-day history in the cloud, nothing lost while offline
   - Activity timeline: every change with the reason
   - Telegram bot: alerts and commands (/status, /water 10, /fan on ...)
   - Wi-Fi setup page (no Wi-Fi name in the code), soil/tank calibration, updates over the air

   Tabs: commands, control, events, net_cloud, oled, state, telegram, wifi_portal

   Board: ESP32 Dev Module.  Tools > Partition Scheme > "Minimal SPIFFS (1.9MB APP with OTA)"
   Libraries: DHT sensor library, ESP32Servo, U8g2, OneWire, DallasTemperature,
              ArduinoJson (7.x), WebSockets (Markus Sattler)                                    */

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <HTTPUpdate.h>
#include <Update.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <WebSocketsServer.h>
#include <ArduinoJson.h>
#include <DHT.h>
#include <ESP32Servo.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Preferences.h>
#include <time.h>
#include <esp_system.h>

#define FW_VERSION "6.0"

/* Wi-Fi the board tries first. Pick another one any time on the setup page
   (hold the BOOT button 3 s, or wait 1 minute without Wi-Fi, then join "HBRT-Setup-xxxx"). */
#define WIFI_SSID_DEFAULT "1"
#define WIFI_PASS_DEFAULT "123456789"

/* Cloud (Firebase Realtime Database). Host only. "" turns the cloud off. */
#define FIREBASE_HOST "airqualityproject-e49ae-default-rtdb.firebaseio.com"
#define FIREBASE_AUTH ""
#define WEB_APP "https://mostafaaabedia-tech.github.io/HBRT-AGRO/"
#define OTA_URL "https://github.com/mostafaaabedia-tech/Greenhouse_Kotlin/releases/latest/download/hbrt_greenhouse.bin"

/* ================= PINS ================= */
#define DHTPIN 4
#define DHTTYPE DHT11
#define DS18B20_PIN 14
#define SOIL_PIN 34
#define WATER_PIN 35
#define FAN_RELAY 18
#define PUMP_RELAY 19
#define LIGHTS_RELAY 25
#define HEATER_RELAY 26
#define SERVO_PIN 13
#define SETUP_PIN 0          // the BOOT button on the board

/* ================= TYPES ================= */
enum Dev  { D_FAN, D_PUMP, D_LIGHTS, D_HEATER, D_VENT, D_COUNT };
enum Mode { M_AUTO, M_MANUAL, M_AWAY };
enum By   { BY_AUTO, BY_USER, BY_SCHED, BY_SAFETY, BY_RULE, BY_TIMER, BY_AWAY, BY_TG, BY_SYS };
enum Why  { RS_NONE, RS_HOT, RS_COLD, RS_HUMID, RS_DRY, RS_MOIST, RS_TANK, RS_MAXRUN, RS_LIGHTSON, RS_LIGHTSOFF,
            RS_RULE, RS_OK, RS_SENSOR, RS_DONE, RS_RAIN, RS_FROST, RS_LOCK, RS_DHT, RS_ST, RS_AWAY, RS_MANUAL,
            RS_STAGE, RS_COUNT };
enum EvCode { E_DEV, E_ALERT, E_MODE, E_SKIP, E_BOOT, E_CFG, E_CAL, E_OTA, E_WIFI, E_WATER, E_OVREND, E_STAGE };
enum Alert  { A_HOT, A_COLD, A_HUMID, A_DRY, A_TANK, A_LOCK, A_DHT, A_ST, A_FROST, A_COUNT };

const char* DEV_NAME[]  = {"fan", "pump", "lights", "heater", "servo"};
const char* MODE_NAME[] = {"auto", "manual", "away"};
const char* BY_NAME[]   = {"auto", "you", "schedule", "safety", "rule", "timer", "away", "telegram", "system"};
const char* WHY_NAME[]  = {"", "hot", "cold", "humid", "dry", "moist", "tank", "maxrun", "lightson", "lightsoff",
                           "rule", "ok", "sensor", "done", "rain", "frost", "lock", "dht", "st", "away", "manual", "stage"};
const char* EV_NAME[]   = {"dev", "alert", "mode", "skip", "boot", "cfg", "cal", "ota", "wifi", "water", "ovrend", "stage"};
const char* ALERT_NAME[]= {"hot", "cold", "humid", "dry", "tank", "lock", "dht", "st", "frost"};
const int   DEV_PIN[]   = {FAN_RELAY, PUMP_RELAY, LIGHTS_RELAY, HEATER_RELAY, -1};

struct Slot { uint16_t min; uint16_t sec; uint8_t days; };                 // watering time
struct Rule { uint8_t sensor; uint8_t op; float val; uint8_t dev; uint8_t on; };  // if sensor op val -> dev on/off
struct Eff  { float tMax, tMin, hMax; int sMin; };                          // limits in force right now
struct Crop { const char* id; float base; float gddHarvest; float tMin, tMax, hMax; int sMin; };

const Crop CROPS[] = {
  {"tomato",     10, 1250, 18, 30, 75, 40},
  {"cucumber",   10,  850, 20, 32, 80, 50},
  {"pepper",     10, 1300, 20, 30, 70, 40},
  {"lettuce",     4,  900, 10, 24, 70, 45},
  {"strawberry",  7, 1100, 15, 26, 75, 45},
  {"herbs",      10,  700, 16, 28, 65, 35},
};
const int N_CROPS = 6;
const char* STAGE_NAME[] = {"seedling", "growing", "flowering", "fruiting", "harvest"};

struct Config {
  float tempMax = 35, tempMin = 15, humMax = 60;
  int   soilMin = 30, waterMin = 5, pumpMaxSec = 60;
  bool  lightSched = false;
  int   lightOn = 6 * 60, lightOff = 20 * 60;
  // usage
  int   flowMl = 20;                 // pump flow, millilitres per second
  int   tankL = 20;                  // tank size in litres
  int   watt[D_COUNT] = {20, 15, 40, 300, 0};
  // crop
  int   crop = -1;                   // index into CROPS, -1 = none
  int32_t plantDay = 0;              // local day number of planting (days since 1970), 0 = not set
  bool  autoStage = false;           // change limits as the plant grows
  // weather
  float lat = 34.35f, lon = 62.20f;  // Herat
  bool  rainSkip = true, frostAlert = true;
  // alerts
  bool  quiet = true;
  int   quietFrom = 22 * 60, quietTo = 7 * 60;
  uint16_t alertMask = 0x1FF;        // one bit per Alert
  bool  dailySummary = true;
  uint8_t lang = 0;                  // 0 English, 1 Dari, 2 Pashto (Telegram messages)
  // calibration (raw ADC)
  int   soilDry = 4095, soilWet = 0, waterEmpty = 0, waterFull = 4095;
} cfg;

Slot sched[4]; uint8_t nSched = 0;
Rule rules[6]; uint8_t nRules = 0;

struct Event { uint32_t n; uint32_t t; uint8_t c, d, by, w, v; float x; };
const int EV_N = 40;

struct Wx {
  bool ok = false; uint32_t at = 0;
  float now = 0; int code = 0;
  float tMax[4], tMin[4], pp[4], ps[4]; int dcode[4];
} wx;

/* ================= OBJECTS ================= */
WebServer server(80);
WebSocketsServer webSocket(81);
DNSServer dns;
DHT dht(DHTPIN, DHTTYPE);
OneWire oneWire(DS18B20_PIN);
DallasTemperature ds18b20(&oneWire);
Servo ventServo;
U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
Preferences prefs;

/* ================= STATE ================= */
String ghName = "My Greenhouse", chipId, cloudKey, viewKey, tgToken, tgChat;
String wifiSsid, wifiPass;
long  tzOffset = 16200;              // Kabul/Herat, corrected by the weather service or the phone
uint8_t mode = M_AUTO;

float temperature = 0, humidity = 0, soilTemp = 0;
int   soil = 0, water = 0, rawSoil = 0, rawWater = 0;
bool  dhtOk = true, soilTempOk = true, dsRequested = false, firstRead = true;
int   dhtFails = 0;

bool  dev[D_COUNT] = {false, false, false, false, false};
bool  applied[D_COUNT] = {true, true, true, true, true};
bool  ovrVal[D_COUNT];
uint32_t ovrUntil[D_COUNT] = {0, 0, 0, 0, 0};   // millis deadline, 0 = none
const uint32_t OVR_FOREVER = 0xFFFFFFFF;
uint8_t climate = 0;                 // 0 idle, 1 cooling, 2 heating
bool  autoWatering = false;
unsigned long waterUntil = 0, pumpOnSince = 0, pumpLockUntil = 0;
uint8_t waterBy = BY_USER;
int32_t schedFired[4] = {0, 0, 0, 0};

// usage, today and the last 7 days
uint32_t onMs[D_COUNT] = {0, 0, 0, 0, 0};
float dayL[7], dayKwh[7]; int32_t dayNum[7];
int32_t curDay = 0;
float dayTMax = -99, dayTMin = 99, gdd = 0;
int lastStage = -1;

// events and alerts
Event evRing[EV_N]; uint32_t evSeq = 0;
uint16_t alertActive = 0; uint8_t alertCount[A_COUNT]; unsigned long alertLastSent[A_COUNT];

// health
uint32_t wifiDrops = 0, cloudErrors = 0;
String otaStatus = "";

unsigned long lastTick = 0, lastWifiTry = 0, wifiDownSince = 0, bootMs = 0, lastUsageTick = 0;

/* ---- shared with the network task on core 0 ---- */
SemaphoreHandle_t lock;
String jsS, jsSView, jsCfg, jsWx, jsUse;          // ready-made JSON for the cloud
volatile bool dS = true, dCfg = true, dWx = true, dUse = true;       // dirty for the control path
volatile bool vS = true, vCfg = true, vWx = true, vUse = true;       // dirty for the view-only path
volatile bool wxChanged = false, cloudOk = false, cloudFast = false, viewFast = false;
volatile bool cfgPending = true, wxRefresh = false, tzSave = false, tgChatSave = false;
volatile bool otaRequest = false, tgTestRequest = false, wifiApply = false;
String otaUrl = OTA_URL;
volatile int cloudCode = 0;
volatile uint8_t tgState = 0;                      // 0 off, 1 ok, 2 error
QueueHandle_t cmdQueue;
struct CmdMsg { char json[300]; uint8_t by; };
QueueHandle_t tgQueue;
struct TgMsg { char text[400]; };

bool portalOn = false;
unsigned long portalSince = 0, setupPressSince = 0;

/* ================= TIME ================= */
uint32_t utcNow() { time_t t = time(nullptr); return t > 1600000000 ? (uint32_t)t : 0; }
bool timeOk() { return utcNow() != 0; }
int32_t localDay() { uint32_t u = utcNow(); return u ? (int32_t)((u + tzOffset) / 86400) : 0; }
int minutesNow() { uint32_t u = utcNow(); return u ? (int)(((u + tzOffset) % 86400UL) / 60) : -1; }
int weekday() { uint32_t u = utcNow(); return u ? (int)(((u + tzOffset) / 86400 + 4) % 7) : -1; }   // 0 = Sunday
bool inWindow(int m, int from, int to) { return from <= to ? (m >= from && m < to) : (m >= from || m < to); }

/* ================= STORAGE ================= */
String makeKey(const char* prefix) {
  const char* abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  String k = prefix;
  for (int i = 0; i < 22; i++) k += abc[esp_random() % 56];
  return k;
}

void loadConfig() {
  prefs.begin("hbrt", false);
  cfg.tempMax    = prefs.getFloat("tmax", cfg.tempMax);
  cfg.tempMin    = prefs.getFloat("tmin", cfg.tempMin);
  cfg.humMax     = prefs.getFloat("hmax", cfg.humMax);
  cfg.soilMin    = prefs.getInt("smin", cfg.soilMin);
  cfg.waterMin   = prefs.getInt("wmin", cfg.waterMin);
  cfg.pumpMaxSec = prefs.getInt("pmax", cfg.pumpMaxSec);
  cfg.lightSched = prefs.getBool("lsch", cfg.lightSched);
  cfg.lightOn    = prefs.getInt("lon", cfg.lightOn);
  cfg.lightOff   = prefs.getInt("loff", cfg.lightOff);
  if (prefs.getBytesLength("cfg2") == sizeof(Config)) prefs.getBytes("cfg2", &cfg, sizeof(Config));
  if (prefs.getBytesLength("sched") == sizeof(sched)) { prefs.getBytes("sched", sched, sizeof(sched)); nSched = prefs.getUChar("nsch", 0); }
  if (prefs.getBytesLength("rules") == sizeof(rules)) { prefs.getBytes("rules", rules, sizeof(rules)); nRules = prefs.getUChar("nrul", 0); }
  if (prefs.getBytesLength("days") == sizeof(dayL)) {
    prefs.getBytes("days", dayL, sizeof(dayL)); prefs.getBytes("dkwh", dayKwh, sizeof(dayKwh)); prefs.getBytes("dnum", dayNum, sizeof(dayNum));
  } else { for (int i = 0; i < 7; i++) { dayL[i] = 0; dayKwh[i] = 0; dayNum[i] = 0; } }
  gdd      = prefs.getFloat("gdd", 0);
  curDay   = prefs.getInt("curday", 0);
  mode     = prefs.getUChar("mode", M_AUTO); if (mode > M_AWAY) mode = M_AUTO;
  ghName   = prefs.getString("name", ghName);
  tzOffset = prefs.getLong("tz", tzOffset);
  cloudKey = prefs.getString("ckey", "");
  viewKey  = prefs.getString("vkey", "");
  tgToken  = prefs.getString("tgtok", "");
  tgChat   = prefs.getString("tgchat", "");
  wifiSsid = prefs.getString("wssid", WIFI_SSID_DEFAULT);
  wifiPass = prefs.getString("wpass", WIFI_PASS_DEFAULT);
  if (cloudKey.length() < 16) { cloudKey = makeKey("gh"); prefs.putString("ckey", cloudKey); }
  prefs.end();
}

void saveConfig() {
  prefs.begin("hbrt", false);
  prefs.putFloat("tmax", cfg.tempMax);  prefs.putFloat("tmin", cfg.tempMin);  prefs.putFloat("hmax", cfg.humMax);
  prefs.putInt("smin", cfg.soilMin);    prefs.putInt("wmin", cfg.waterMin);   prefs.putInt("pmax", cfg.pumpMaxSec);
  prefs.putBool("lsch", cfg.lightSched); prefs.putInt("lon", cfg.lightOn);    prefs.putInt("loff", cfg.lightOff);
  prefs.putBytes("cfg2", &cfg, sizeof(Config));
  prefs.putBytes("sched", sched, sizeof(sched)); prefs.putUChar("nsch", nSched);
  prefs.putBytes("rules", rules, sizeof(rules)); prefs.putUChar("nrul", nRules);
  prefs.putString("name", ghName);
  prefs.putUChar("mode", mode);
  prefs.end();
  cfgPending = true;
}

void saveString(const char* k, const String& v) { prefs.begin("hbrt", false); prefs.putString(k, v); prefs.end(); }

void saveDays() {
  prefs.begin("hbrt", false);
  prefs.putBytes("days", dayL, sizeof(dayL)); prefs.putBytes("dkwh", dayKwh, sizeof(dayKwh)); prefs.putBytes("dnum", dayNum, sizeof(dayNum));
  prefs.putFloat("gdd", gdd); prefs.putInt("curday", curDay);
  prefs.end();
}

bool pumpLocked() { return pumpLockUntil && (long)(pumpLockUntil - millis()) > 0; }
bool cloudOn() { return strlen(FIREBASE_HOST) > 0; }
String shareLink() { return String(WEB_APP) + "#g=" + cloudKey + "@" + FIREBASE_HOST; }
String viewLink()  { return viewKey.length() ? String(WEB_APP) + "#v=" + viewKey + "@" + FIREBASE_HOST : String(""); }

/* ================= SENSORS ================= */
void readSensors() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (!isnan(t) && !isnan(h)) { temperature = t; humidity = h; dhtFails = 0; dhtOk = true; }
  else if (++dhtFails >= 5) dhtOk = false;

  if (dsRequested) {
    float st = ds18b20.getTempCByIndex(0);
    if (st != DEVICE_DISCONNECTED_C && st > -50) { soilTemp = st; soilTempOk = true; }
    else soilTempOk = false;
  }
  ds18b20.requestTemperatures();
  dsRequested = true;

  // dummy read + settle time between the two ADC pins stops crosstalk
  analogRead(SOIL_PIN); delay(10);
  long sSum = 0; for (int i = 0; i < 8; i++) { sSum += analogRead(SOIL_PIN); delay(2); }
  analogRead(WATER_PIN); delay(10);
  long wSum = 0; for (int i = 0; i < 8; i++) { wSum += analogRead(WATER_PIN); delay(2); }
  rawSoil = sSum / 8; rawWater = wSum / 8;

  soil  = cfg.soilDry == cfg.soilWet ? 0 : constrain(map(rawSoil, cfg.soilDry, cfg.soilWet, 0, 100), 0, 100);
  water = cfg.waterEmpty == cfg.waterFull ? 0 : constrain(map(rawWater, cfg.waterEmpty, cfg.waterFull, 0, 100), 0, 100);

  if (dhtOk) { if (temperature > dayTMax) dayTMax = temperature; if (temperature < dayTMin) dayTMin = temperature; }
  firstRead = false;
}

/* ================= OUTPUTS ================= */
void applyOutputs() {
  for (int d = 0; d < D_COUNT; d++) {
    if (dev[d] == applied[d]) continue;
    applied[d] = dev[d];
    if (d == D_VENT) { ventServo.write(dev[d] ? 180 : 0); delay(50); }
    else digitalWrite(DEV_PIN[d], dev[d] ? LOW : HIGH);   // relays are active LOW
  }
}

/* ================= SETUP ================= */
void setup() {
  Serial.begin(115200);
  bootMs = millis();
  char id[7];
  snprintf(id, sizeof(id), "%06X", (uint32_t)(ESP.getEfuseMac() & 0xFFFFFF));
  chipId = id;
  lock = xSemaphoreCreateMutex();
  cmdQueue = xQueueCreate(8, sizeof(CmdMsg));
  tgQueue = xQueueCreate(6, sizeof(TgMsg));
  loadConfig();

  for (int d = 0; d < D_COUNT; d++) if (DEV_PIN[d] >= 0) { pinMode(DEV_PIN[d], OUTPUT); digitalWrite(DEV_PIN[d], HIGH); }
  pinMode(SETUP_PIN, INPUT_PULLUP);

  dht.begin();
  ds18b20.begin();
  ds18b20.setWaitForConversion(false);

  ESP32PWM::allocateTimer(0);
  ventServo.setPeriodHertz(50);
  ventServo.attach(SERVO_PIN, 500, 2400);
  ventServo.write(0);

  Wire.begin(21, 22);
  u8g2.begin();
  u8g2.setPowerSave(0);
  oledMessage("HBRT AGRO " FW_VERSION, "Connecting to", wifiSsid.c_str());

  startWiFi();
  for (int i = 0; i < 20 && WiFi.status() != WL_CONNECTED; i++) delay(500);
  if (WiFi.status() == WL_CONNECTED) Serial.println("IP: " + WiFi.localIP().toString());
  else Serial.println("No Wi-Fi yet. Retrying, setup page opens after 1 minute.");

  configTime(0, 0, "pool.ntp.org", "time.google.com", "time.cloudflare.com");
  setupHttp();
  webSocket.begin();
  webSocket.onEvent(webSocketEvent);
  webSocket.enableHeartbeat(15000, 3000, 2);

  logEvent(E_BOOT, 255, BY_SYS, RS_NONE, 0, (float)esp_reset_reason());
  readSensors();
  publish(true);
  if (cloudOn()) {
    xTaskCreatePinnedToCore(cloudTask, "cloud", 16384, nullptr, 1, nullptr, 0);
    Serial.println("Open from anywhere: " + shareLink());
  }
}

/* ================= LOOP ================= */
void loop() {
  if (portalOn) dns.processNextRequest();
  server.handleClient();
  webSocket.loop();
  drainCommands();
  setupButton();
  wifiWatchdog();
  if (wxChanged) { wxChanged = false; publishWx(); }
  if (tzSave) { tzSave = false; prefs.begin("hbrt", false); prefs.putLong("tz", tzOffset); prefs.end(); }
  if (tgChatSave) { tgChatSave = false; saveString("tgchat", tgChat); cfgPending = true; }

  if (millis() - lastTick >= 2000) {
    lastTick = millis();
    readSensors();
    dailyCheck();
    tick();
    trackUsage();
    checkAlerts();
    updateOLED();
    publish(false);
  }
}
