#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebServer.h>
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

/* HBRT AGRO Smart Greenhouse - firmware 5.0
   Developed by Mostafa Mesbah Aabedi & Milad Ahmadi
   HBRT - Herat Boys Robotic Team

   New in 5.0 - control from anywhere, no tunnel:
   - Firebase Realtime Database cloud link (plain HTTPS, no Firebase library needed)
     * runs on the other CPU core, so slow internet never freezes sensors, relays or the app
     * pushes live state, receives commands, keeps a 7-day history (one point every 5 min)
     * goes "fast" (2 s updates) only while someone is watching, saves data the rest of the time
     * commands older than 60 s are ignored (no surprise pump runs after an outage)
   - Every board gets its own private cloud key. Phones pair automatically the first time they
     connect on the same Wi-Fi; after that the app and the website work from any network.
   - Real clock from the internet (NTP): grow-light schedule keeps working after a reboot
   - Open http://<board-ip>/ on the same Wi-Fi to get the "open from anywhere" link

   Local Wi-Fi control (WebSocket on port 81) works exactly like 4.0, with or without internet.

   Libraries: DHT sensor library, ESP32Servo, U8g2, OneWire, DallasTemperature, ArduinoJson,
              WebSockets (Markus Sattler). Board: ESP32 Dev Module (ESP32 package 2.x or 3.x). */

#define FW_VERSION "5.0"

/* ================= WIFI ================= */
const char* ssid     = "1";
const char* password = "123456789";

/* ================= CLOUD (Firebase Realtime Database) =================
   Host only, no https:// and no slash. Leave "" to turn the cloud link off.            */
#define FIREBASE_HOST "airqualityproject-e49ae-default-rtdb.firebaseio.com"
#define FIREBASE_AUTH ""        // database secret, only if your rules need one
#define WEB_APP "https://mostafaaabedia-tech.github.io/HBRT-AGRO/"

WebServer server(80);
WebSocketsServer webSocket = WebSocketsServer(81);

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

DHT dht(DHTPIN, DHTTYPE);
OneWire oneWire(DS18B20_PIN);
DallasTemperature ds18b20(&oneWire);
Servo ventServo;
U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
Preferences prefs;

/* ================= SETTINGS (saved in flash) ================= */
struct Config {
  float tempMax  = 35;
  float tempMin  = 15;
  float humMax   = 60;
  int   soilMin  = 30;
  int   waterMin = 5;
  int   pumpMaxSec = 60;
  bool  lightSched = false;  // grow-light schedule on/off
  int   lightOn  = 6 * 60;   // minutes after midnight
  int   lightOff = 20 * 60;
} cfg;
String ghName = "My Greenhouse";
String chipId;
String cloudKey;             // private path in the database
long   tzOffset = 0;         // seconds, learned from the phone (e.g. Kabul = 16200)
bool   tzKnown = false;

const unsigned long PUMP_COOLDOWN_MS = 5UL * 60UL * 1000UL;

/* ================= STATE ================= */
float temperature = 0, humidity = 0, soilTemp = 0;
int soil = 0, water = 0;
bool fanState = false, pumpState = false, lightsState = false, heaterState = false, servoState = false;
bool autoMode = true;
bool lastFanState = true, lastPumpState = true, lastLightsState = true, lastHeaterState = true, lastServoState = true;

bool dhtOk = true, soilTempOk = true;
int  dhtFails = 0;
bool dsRequested = false;

unsigned long lastUpdate = 0, pumpOnSince = 0, pumpLockUntil = 0, waterUntil = 0;
unsigned long lastWifiTry = 0, wifiDownSince = 0;

// Fallback clock (set by the app) when NTP is not available yet
bool timeKnown = false;
uint32_t baseEpochLocal = 0;
unsigned long baseMillis = 0;

/* ---- shared between the main loop (core 1) and the cloud task (core 0) ---- */
SemaphoreHandle_t stateLock;
String sharedState;                  // latest state JSON, written by loop, read by the cloud task
volatile bool stateDirty = true;     // something changed, push it now
volatile bool cloudOk = false;       // last cloud request worked
volatile bool cloudFast = false;     // someone is watching from the cloud
volatile int  cloudCode = 0;         // last HTTP code (shown on OLED / app when it fails)
QueueHandle_t cmdQueue;              // commands from the cloud -> loop
struct CmdMsg { char json[256]; };

uint32_t utcNow() {                  // seconds since 1970 (UTC), 0 if unknown
  time_t t = time(nullptr);
  return t > 1600000000 ? (uint32_t)t : 0;
}

int minutesNow() {
  uint32_t u = utcNow();
  if (u && tzKnown) return ((u + tzOffset) % 86400UL) / 60;
  if (!timeKnown) return -1;
  uint32_t t = baseEpochLocal + (millis() - baseMillis) / 1000;
  return (t % 86400UL) / 60;
}

/* ================= CONFIG STORAGE ================= */
String makeKey() {
  const char* abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  String k = "gh";
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
  ghName         = prefs.getString("name", ghName);
  tzKnown        = prefs.isKey("tz");
  tzOffset       = prefs.getLong("tz", 0);
  cloudKey       = prefs.getString("ckey", "");
  if (cloudKey.length() < 16) { cloudKey = makeKey(); prefs.putString("ckey", cloudKey); }
  prefs.end();
}
void saveConfig() {
  prefs.begin("hbrt", false);
  prefs.putFloat("tmax", cfg.tempMax);
  prefs.putFloat("tmin", cfg.tempMin);
  prefs.putFloat("hmax", cfg.humMax);
  prefs.putInt("smin", cfg.soilMin);
  prefs.putInt("wmin", cfg.waterMin);
  prefs.putInt("pmax", cfg.pumpMaxSec);
  prefs.putBool("lsch", cfg.lightSched);
  prefs.putInt("lon", cfg.lightOn);
  prefs.putInt("loff", cfg.lightOff);
  prefs.putString("name", ghName);
  prefs.end();
}

bool pumpLocked() { return pumpLockUntil && (long)(pumpLockUntil - millis()) > 0; }
bool cloudOn() { return strlen(FIREBASE_HOST) > 0; }
String shareLink() { return String(WEB_APP) + "#g=" + cloudKey + "@" + FIREBASE_HOST; }

/* ================= STATE OUT (WebSocket + cloud) ================= */
void buildState(String& json) {
  StaticJsonDocument<1536> doc;
  doc["name"] = ghName;
  doc["id"] = chipId;
  doc["fw"] = FW_VERSION;
  doc["temperature"] = temperature;
  doc["humidity"] = humidity;
  doc["soil"] = soil;
  doc["water"] = water;
  doc["soilTemp"] = soilTemp;
  doc["fan"] = fanState;
  doc["pump"] = pumpState;
  doc["lights"] = lightsState;
  doc["heater"] = heaterState;
  doc["servo"] = servoState;
  doc["auto"] = autoMode;
  doc["rssi"] = WiFi.RSSI();
  doc["uptime"] = millis() / 1000;
  doc["pumpLock"] = pumpLocked();
  doc["dhtOk"] = dhtOk;
  doc["stOk"] = soilTempOk;
  doc["timeOk"] = minutesNow() >= 0;
  doc["watering"] = waterUntil ? (long)(waterUntil - millis()) / 1000 + 1 : 0;
  doc["ip"] = WiFi.localIP().toString();
  if (cloudOn()) {
    doc["db"] = FIREBASE_HOST;
    doc["key"] = cloudKey;
    doc["cloud"] = (bool)cloudOk;
  }
  JsonObject c = doc.createNestedObject("cfg");
  c["tempMax"] = cfg.tempMax;
  c["tempMin"] = cfg.tempMin;
  c["humMax"] = cfg.humMax;
  c["soilMin"] = cfg.soilMin;
  c["waterMin"] = cfg.waterMin;
  c["pumpMax"] = cfg.pumpMaxSec;
  c["lightSched"] = cfg.lightSched;
  c["lightOn"] = cfg.lightOn;
  c["lightOff"] = cfg.lightOff;
  serializeJson(doc, json);
}

void sendData() {
  String json;
  buildState(json);
  if (webSocket.connectedClients() > 0) webSocket.broadcastTXT(json);
  xSemaphoreTake(stateLock, portMAX_DELAY);
  sharedState = json;
  xSemaphoreGive(stateLock);
}

/* ================= OUTPUTS ================= */
void applyOutputs() {
  if (fanState != lastFanState)       { digitalWrite(FAN_RELAY, fanState ? LOW : HIGH);       lastFanState = fanState; }
  if (pumpState != lastPumpState)     { digitalWrite(PUMP_RELAY, pumpState ? LOW : HIGH);     lastPumpState = pumpState; }
  if (lightsState != lastLightsState) { digitalWrite(LIGHTS_RELAY, lightsState ? LOW : HIGH); lastLightsState = lightsState; }
  if (heaterState != lastHeaterState) { digitalWrite(HEATER_RELAY, heaterState ? LOW : HIGH); lastHeaterState = heaterState; }
  if (servoState != lastServoState)   { ventServo.write(servoState ? 180 : 0);                lastServoState = servoState; }
}

/* ================= SAFETY (always active) ================= */
void safety() {
  if (pumpState) {
    if (!pumpOnSince) pumpOnSince = millis();
    if (water <= cfg.waterMin) {
      pumpState = false; pumpOnSince = 0; waterUntil = 0;
    } else if (millis() - pumpOnSince > (unsigned long)cfg.pumpMaxSec * 1000UL) {
      pumpState = false; pumpOnSince = 0; waterUntil = 0;
      pumpLockUntil = millis() + PUMP_COOLDOWN_MS;
    }
  } else {
    pumpOnSince = 0;
  }
  if (pumpLocked()) { pumpState = false; waterUntil = 0; }
}

/* ================= TIMED WATERING ================= */
void timedWatering() {
  if (!waterUntil) return;
  if ((long)(waterUntil - millis()) > 0) pumpState = true;
  else { pumpState = false; waterUntil = 0; }
}

/* ================= COMMANDS (same JSON from the app, the website and the cloud) ================= */
// wsNum >= 0: came over the local WebSocket.  wsNum = -1: came from the cloud.
void applyCommand(JsonDocument& doc, int wsNum) {
  if (doc["ping"]) { if (wsNum >= 0) webSocket.sendTXT(wsNum, "{\"pong\":1}"); return; }

  String device = doc["device"] | "";

  if (device == "config") {
    cfg.tempMax    = doc["tempMax"]  | cfg.tempMax;
    cfg.tempMin    = doc["tempMin"]  | cfg.tempMin;
    cfg.humMax     = doc["humMax"]   | cfg.humMax;
    cfg.soilMin    = constrain((int)(doc["soilMin"]  | cfg.soilMin), 0, 100);
    cfg.waterMin   = constrain((int)(doc["waterMin"] | cfg.waterMin), 0, 100);
    cfg.pumpMaxSec = constrain((int)(doc["pumpMax"]  | cfg.pumpMaxSec), 5, 600);
    cfg.lightSched = doc["lightSched"] | cfg.lightSched;
    cfg.lightOn    = constrain((int)(doc["lightOn"]  | cfg.lightOn), 0, 1439);
    cfg.lightOff   = constrain((int)(doc["lightOff"] | cfg.lightOff), 0, 1439);
    saveConfig();
  }
  else if (device == "name") {
    String n = doc["value"] | "";
    n.trim();
    if (n.length() > 0 && n.length() <= 32) { ghName = n; saveConfig(); }
  }
  else if (device == "time") {               // phone sends its local time in seconds
    uint32_t local = doc["value"] | 0UL;
    if (local > 1600000000UL) {
      baseEpochLocal = local; baseMillis = millis(); timeKnown = true;
      uint32_t u = utcNow();
      if (u) {                                // learn the time zone (rounded to 15 min)
        long off = lround(((double)local - (double)u) / 900.0) * 900L;
        if (!tzKnown || off != tzOffset) {
          tzOffset = off; tzKnown = true;
          prefs.begin("hbrt", false); prefs.putLong("tz", tzOffset); prefs.end();
        }
      }
    }
    return;
  }
  else if (device == "water") {               // water now for N seconds
    int sec = constrain((int)(doc["value"] | 10), 1, cfg.pumpMaxSec);
    if (water > cfg.waterMin && !pumpLocked()) {
      waterUntil = millis() + (unsigned long)sec * 1000UL;
      pumpState = true;
    }
  }
  else if (device == "rekey" && wsNum >= 0) { // new private cloud key (old links stop working)
    String k = makeKey();
    xSemaphoreTake(stateLock, portMAX_DELAY);
    cloudKey = k;
    xSemaphoreGive(stateLock);
    prefs.begin("hbrt", false); prefs.putString("ckey", k); prefs.end();
  }
  else if (device == "alloff") {
    fanState = pumpState = lightsState = heaterState = servoState = false;
    waterUntil = 0; autoMode = false;
  }
  else {
    bool value = doc["value"] | false;
    if (device == "fan")         { fanState = value;    autoMode = false; }
    else if (device == "pump")   {
      if (value && (water <= cfg.waterMin || pumpLocked())) { sendData(); return; }
      pumpState = value; autoMode = false;
      if (!value) waterUntil = 0;
    }
    else if (device == "lights") { lightsState = value; autoMode = false; }
    else if (device == "heater") { heaterState = value; autoMode = false; }
    else if (device == "servo")  { servoState = value;  autoMode = false; }
    else if (device == "auto")   { autoMode = value; }
    else return;
  }

  safety();
  applyOutputs();
  sendData();
  stateDirty = true;                          // show the result in the cloud right away
}

void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED: sendData(); break;
    case WStype_TEXT: {
      StaticJsonDocument<512> doc;
      if (!deserializeJson(doc, payload, length)) applyCommand(doc, num);
      break;
    }
    default: break;
  }
}

/* ================= CLOUD TASK (core 0) =================
   Database layout (everything under one private key):
     /hbrt/<key>/state       live state, same JSON as the WebSocket + "t" (server time)
     /hbrt/<key>/in/cmd      commands pushed by the app / website, deleted once done
     /hbrt/<key>/in/seen     server time when an app last looked (makes the board go fast)
     /hbrt/<key>/log/<slot>  history ring buffer, 5-min points, 7 days                    */
WiFiClientSecure tls;
HTTPClient http;

String fbKey;                      // the cloud task's own copy of cloudKey

String fbUrl(const String& path) {
  String u = String("https://") + FIREBASE_HOST + "/hbrt/" + fbKey + path + ".json";
  if (strlen(FIREBASE_AUTH) > 0) u += String("?auth=") + FIREBASE_AUTH;
  return u;
}

int fbRequest(const char* method, const String& path, const String& body, String* reply = nullptr) {
  if (!http.begin(tls, fbUrl(path))) return -1;
  http.addHeader("Content-Type", "application/json");
  int code;
  if (strcmp(method, "GET") == 0) code = http.GET();
  else code = http.sendRequest(method, body);
  if (reply && code == 200) *reply = http.getString();
  else if (code > 0) http.getString();              // drain so the connection can be reused
  http.end();
  return code;
}

void cloudTask(void*) {
  tls.setInsecure();               // skip the certificate check (fine for a school project)
  tls.setHandshakeTimeout(12);
  http.setReuse(true);             // one connection kept open = fast requests
  http.setTimeout(5000);

  unsigned long lastPush = 0, lastPoll = 0, lastLog = 0;
  uint32_t lastSlot = 0xFFFFFFFF;
  int fails = 0;

  for (;;) {
    if (WiFi.status() != WL_CONNECTED) { cloudOk = false; vTaskDelay(pdMS_TO_TICKS(1000)); continue; }
    unsigned long now = millis();
    bool fast = cloudFast;
    xSemaphoreTake(stateLock, portMAX_DELAY);
    fbKey = cloudKey;
    xSemaphoreGive(stateLock);

    // 1) live state: every 2 s while watched, every 20 s otherwise, immediately after a change
    if (stateDirty || now - lastPush > (fast ? 2000UL : 20000UL) || lastPush == 0) {
      stateDirty = false;
      xSemaphoreTake(stateLock, portMAX_DELAY);
      String s = sharedState;
      xSemaphoreGive(stateLock);
      if (s.length() > 2) {
        String body = "{\"t\":{\".sv\":\"timestamp\"}," + s.substring(1);
        int code = fbRequest("PUT", "/state", body);
        cloudCode = code;
        cloudOk = code == 200;
        lastPush = millis();
        if (code != 200) Serial.printf("[cloud] state failed: %d %s\n", code, http.errorToString(code).c_str());
      }
    }

    // 2) commands + "is anyone watching?": every 0.6 s while watched, every 3 s otherwise
    if (millis() - lastPoll > (fast ? 600UL : 3000UL)) {
      lastPoll = millis();
      String reply;
      int code = fbRequest("GET", "/in", "", &reply);
      if (code == 200) {
        cloudOk = true;
        StaticJsonDocument<3072> in;
        if (reply != "null" && !deserializeJson(in, reply)) {
          double seen = in["seen"] | 0.0;
          uint32_t u = utcNow();
          cloudFast = seen > 0 && (!u || ((double)u * 1000.0 - seen) < 60000.0);
          JsonObject cmds = in["cmd"].as<JsonObject>();
          if (!cmds.isNull() && cmds.size() > 0) {
            String del = "{";
            for (JsonPair kv : cmds) {
              JsonObject c = kv.value().as<JsonObject>();
              double t = c["t"] | 0.0;
              bool fresh = !u || t == 0 || ((double)u * 1000.0 - t) < 60000.0;
              if (fresh) {
                CmdMsg m;
                c.remove("t");
                serializeJson(c, m.json, sizeof(m.json));
                xQueueSend(cmdQueue, &m, 0);
              }
              if (del.length() > 1) del += ",";
              del += "\"" + String(kv.key().c_str()) + "\":null";
            }
            del += "}";
            fbRequest("PATCH", "/in/cmd", del);        // delete exactly the ones we handled
          }
        } else cloudFast = false;
        fails = 0;
      } else if (code != 0) {
        cloudOk = false; cloudCode = code;
        if (++fails >= 3) { tls.stop(); fails = 0; }  // fresh connection after repeated errors
      }
    }

    // 3) history point every 5 minutes (7-day ring buffer, overwrites itself)
    uint32_t u = utcNow();
    if (u && millis() - lastLog > 60000UL) {
      uint32_t slot = (u / 300) % 2016;
      if (slot != lastSlot) {
        char b[200];
        snprintf(b, sizeof(b),
                 "{\"t\":{\".sv\":\"timestamp\"},\"T\":%.1f,\"H\":%.0f,\"S\":%d,\"ST\":%.1f,\"W\":%d}",
                 temperature, humidity, soil, soilTemp, water);
        if (fbRequest("PUT", "/log/" + String(slot), b) == 200) { lastSlot = slot; lastLog = millis(); }
      }
    }

    vTaskDelay(pdMS_TO_TICKS(fast ? 50 : 200));
  }
}

void drainCloudCommands() {
  CmdMsg m;
  while (xQueueReceive(cmdQueue, &m, 0) == pdTRUE) {
    StaticJsonDocument<512> doc;
    if (!deserializeJson(doc, m.json)) applyCommand(doc, -1);
  }
}

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

  soil  = constrain(map(sSum / 8, 4095, 0, 0, 100), 0, 100);
  water = constrain(map(wSum / 8, 0, 4095, 0, 100), 0, 100);
}

/* ================= AUTO MODE ================= */
void autoControl() {
  if (!autoMode) return;

  bool hot  = temperature > cfg.tempMax || humidity > cfg.humMax;
  bool cold = temperature < cfg.tempMin;
  bool okay = temperature < cfg.tempMax - 2 && humidity < cfg.humMax - 5 && temperature > cfg.tempMin + 2;

  if (!dhtOk)    { fanState = false; servoState = false; heaterState = false; }
  else if (hot)  { fanState = true;  servoState = true;  heaterState = false; }
  else if (cold) { heaterState = true; fanState = false; servoState = false; }
  else if (okay) { fanState = false; servoState = false; heaterState = false; }

  if (!waterUntil) {
    if (!pumpState && soil < cfg.soilMin && water > cfg.waterMin && !pumpLocked()) pumpState = true;
    else if (pumpState && (soil >= cfg.soilMin + 10 || water <= cfg.waterMin)) pumpState = false;
  }

  int m = minutesNow();
  if (cfg.lightSched && m >= 0) {
    lightsState = (cfg.lightOn <= cfg.lightOff) ? (m >= cfg.lightOn && m < cfg.lightOff)
                                                : (m >= cfg.lightOn || m < cfg.lightOff);   // overnight
  }
}

/* ================= OLED ================= */
void updateOLED() {
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x12_tr);
  bool wifi = WiFi.status() == WL_CONNECTED;
  String title = wifi ? ghName.substring(0, 16) : String("** NO WIFI **");
  u8g2.drawStr(0, 10, title.c_str());
  if (wifi && cloudOn()) u8g2.drawStr(104, 10, cloudOk ? (cloudFast ? "WEB*" : "WEB") : "LAN");
  u8g2.drawLine(0, 13, 128, 13);
  u8g2.setCursor(0, 27);  u8g2.print("T:");    u8g2.print(temperature, 1); u8g2.print("C");
  u8g2.setCursor(64, 27); u8g2.print("H:");    u8g2.print(humidity, 0);    u8g2.print("%");
  u8g2.setCursor(0, 41);  u8g2.print("Soil:"); u8g2.print(soil);           u8g2.print("%");
  u8g2.setCursor(64, 41); u8g2.print("ST:");   u8g2.print(soilTemp, 1);    u8g2.print("C");
  u8g2.setCursor(0, 55);  u8g2.print(autoMode ? "AUTO" : "MAN");
  u8g2.setCursor(30, 55); u8g2.print("W:");    u8g2.print(water);          u8g2.print("%");
  u8g2.setCursor(70, 55); u8g2.print("F"); u8g2.print(fanState ? "1" : "0");
  u8g2.setCursor(86, 55); u8g2.print("P"); u8g2.print(pumpState ? "1" : "0");
  u8g2.setCursor(102, 55); u8g2.print("L"); u8g2.print(lightsState ? "1" : "0");
  u8g2.sendBuffer();
}

/* ================= WIFI KEEP-ALIVE ================= */
void startWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.setHostname(("hbrt-" + chipId).c_str());
  WiFi.begin(ssid, password);
}

void wifiWatchdog() {
  if (WiFi.status() == WL_CONNECTED) { wifiDownSince = 0; return; }
  if (!wifiDownSince) wifiDownSince = millis();
  if (millis() - lastWifiTry > 10000) {
    lastWifiTry = millis();
    WiFi.disconnect();
    WiFi.begin(ssid, password);
  }
  if (millis() - wifiDownSince > 10UL * 60UL * 1000UL) ESP.restart();
}

/* ================= HTTP ================= */
void handleInfo() {
  StaticJsonDocument<384> d;
  d["app"] = "hbrt";
  d["name"] = ghName;
  d["id"] = chipId;
  d["fw"] = FW_VERSION;
  d["ws"] = 81;
  if (cloudOn()) { d["db"] = FIREBASE_HOST; d["key"] = cloudKey; d["cloud"] = (bool)cloudOk; }
  String s; serializeJson(d, s);
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", s);
}

void handleRoot() {   // small page: name, status and the "open from anywhere" link
  String link = shareLink();
  String h = F("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
               "<title>HBRT Greenhouse</title><body style='margin:0;font-family:system-ui;background:#0b1410;color:#e8f5ec;"
               "display:grid;place-items:center;min-height:100vh'><div style='max-width:420px;padding:28px'>"
               "<p style='color:#39FF14;letter-spacing:.2em;font-size:12px;margin:0'>HBRT GREENHOUSE SERVER IS RUNNING</p><h1 style='margin:.3em 0'>");
  h += ghName;
  h += F("</h1><p style='color:#8fb39b'>Firmware " FW_VERSION " &middot; ");
  h += String(temperature, 1) + "&deg;C &middot; soil " + String(soil) + "%</p>";
  if (cloudOn()) {
    h += F("<a style='display:block;text-align:center;background:#39FF14;color:#000;padding:14px;border-radius:14px;"
           "font-weight:700;text-decoration:none' href='");
    h += link;
    h += F("'>Open from anywhere</a><p style='color:#8fb39b;font-size:13px'>Bookmark that page or send it to your team. "
           "Anyone with the link can control this greenhouse, so share it carefully.</p>");
  }
  h += F("</div>");
  server.send(200, "text/html", h);
}

/* ================= SETUP ================= */
void setup() {
  Serial.begin(115200);
  char id[7];
  snprintf(id, sizeof(id), "%06X", (uint32_t)(ESP.getEfuseMac() & 0xFFFFFF));
  chipId = id;
  loadConfig();
  stateLock = xSemaphoreCreateMutex();
  cmdQueue = xQueueCreate(8, sizeof(CmdMsg));

  dht.begin();
  ds18b20.begin();
  ds18b20.setWaitForConversion(false);

  int relays[] = {FAN_RELAY, PUMP_RELAY, LIGHTS_RELAY, HEATER_RELAY};
  for (int p : relays) { pinMode(p, OUTPUT); digitalWrite(p, HIGH); }   // active LOW: HIGH = off

  ESP32PWM::allocateTimer(0);
  ventServo.setPeriodHertz(50);
  ventServo.attach(SERVO_PIN, 500, 2400);
  ventServo.write(0);

  Wire.begin(21, 22);
  u8g2.begin();
  u8g2.setPowerSave(0);

  startWiFi();
  Serial.print("Connecting");
  for (int i = 0; i < 20 && WiFi.status() != WL_CONNECTED; i++) { delay(500); Serial.print("."); }
  if (WiFi.status() == WL_CONNECTED) { Serial.print("\nIP: "); Serial.println(WiFi.localIP()); }
  else Serial.println("\nWiFi failed - will keep retrying in background");

  configTime(0, 0, "pool.ntp.org", "time.google.com", "time.cloudflare.com");

  server.on("/", handleRoot);
  server.on("/info", handleInfo);
  server.begin();
  webSocket.begin();
  webSocket.onEvent(webSocketEvent);
  webSocket.enableHeartbeat(15000, 3000, 2);

  sendData();
  if (cloudOn()) {
    xTaskCreatePinnedToCore(cloudTask, "cloud", 12288, nullptr, 1, nullptr, 0);
    Serial.println("Cloud link: " + shareLink());
  }
}

/* ================= LOOP ================= */
void loop() {
  wifiWatchdog();
  server.handleClient();
  webSocket.loop();
  drainCloudCommands();

  if (millis() - lastUpdate > 2000) {
    lastUpdate = millis();
    readSensors();
    autoControl();
    timedWatering();
    safety();
    applyOutputs();
    updateOLED();
    sendData();
  }
}
