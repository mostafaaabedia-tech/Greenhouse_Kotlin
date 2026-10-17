#include <WiFi.h>
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

/* HBRT AGRO Smart Greenhouse - firmware 4.0
   Developed by Mostafa Mesbah Aabedi & Milad Ahmadi

   New in 4.0:
   - Greenhouse name stored on the board (set it from the app)
   - GET http://<ip>/info  -> lets the app find this board when it scans the network
   - Clock synced from the app -> grow-light schedule in auto mode
   - "Water now" for N seconds                                                  */

#define FW_VERSION "4.0"

/* ================= WIFI ================= */
const char* ssid     = "1";
const char* password = "123456789";

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

// Clock (set by the app): local time = baseEpochLocal + (millis - baseMillis)/1000
bool timeKnown = false;
uint32_t baseEpochLocal = 0;
unsigned long baseMillis = 0;

int minutesNow() {
  if (!timeKnown) return -1;
  uint32_t t = baseEpochLocal + (millis() - baseMillis) / 1000;
  return (t % 86400UL) / 60;
}

/* ================= CONFIG STORAGE ================= */
void loadConfig() {
  prefs.begin("hbrt", true);
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

/* ================= WEBSOCKET OUT ================= */
void sendData() {
  StaticJsonDocument<1024> doc;
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
  doc["timeOk"] = timeKnown;
  doc["watering"] = waterUntil ? (long)(waterUntil - millis()) / 1000 + 1 : 0;
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

  String json;
  serializeJson(doc, json);
  webSocket.broadcastTXT(json);
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

/* ================= WEBSOCKET IN ================= */
void handleControl(uint8_t num, uint8_t* payload) {
  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, payload)) return;

  if (doc["ping"]) { webSocket.sendTXT(num, "{\"pong\":1}"); return; }

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
    sendData();
    return;
  }
  if (device == "name") {
    String n = doc["value"] | "";
    n.trim();
    if (n.length() > 0 && n.length() <= 32) { ghName = n; saveConfig(); }
    sendData();
    return;
  }
  if (device == "time") {               // app sends local time in seconds
    baseEpochLocal = doc["value"] | 0UL;
    baseMillis = millis();
    timeKnown = baseEpochLocal > 1600000000UL;
    return;
  }
  if (device == "water") {              // water now for N seconds
    int sec = constrain((int)(doc["value"] | 10), 1, cfg.pumpMaxSec);
    if (water > cfg.waterMin && !pumpLocked()) {
      waterUntil = millis() + (unsigned long)sec * 1000UL;
      pumpState = true;
    }
    safety(); applyOutputs(); sendData();
    return;
  }

  bool value = doc["value"] | false;
  if (device == "fan")         { fanState = value;    autoMode = false; }
  else if (device == "pump")   {
    if (value && (water <= cfg.waterMin || pumpLocked())) return;
    pumpState = value; autoMode = false;
    if (!value) waterUntil = 0;
  }
  else if (device == "lights") { lightsState = value; autoMode = false; }
  else if (device == "heater") { heaterState = value; autoMode = false; }
  else if (device == "servo")  { servoState = value;  autoMode = false; }
  else if (device == "auto")   { autoMode = value; }
  else return;

  safety();
  applyOutputs();
  sendData();
}

void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED: sendData(); break;
    case WStype_TEXT:      handleControl(num, payload); break;
    default: break;
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
  String title = WiFi.status() == WL_CONNECTED ? ghName.substring(0, 21) : String("** NO WIFI **");
  u8g2.drawStr(0, 10, title.c_str());
  u8g2.drawLine(0, 13, 128, 13);
  u8g2.setCursor(0, 27);  u8g2.print("T:");    u8g2.print(temperature, 1); u8g2.print("C");
  u8g2.setCursor(64, 27); u8g2.print("H:");    u8g2.print(humidity, 0);    u8g2.print("%");
  u8g2.setCursor(0, 41);  u8g2.print("Soil:"); u8g2.print(soil);           u8g2.print("%");
  u8g2.setCursor(64, 41); u8g2.print("ST:");   u8g2.print(soilTemp, 1);    u8g2.print("C");
  u8g2.setCursor(0, 55);  u8g2.print(autoMode ? "AUTO" : "MAN");
  u8g2.setCursor(30, 55); u8g2.print("W:");    u8g2.print(water);          u8g2.print("%");
  u8g2.setCursor(70, 55); u8g2.print("F"); u8g2.print(fanState ? "1" : "0");
  u8g2.setCursor(86, 55); u8g2.print("P"); u8g2.print(pumpState ? "1" : "0");
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
  StaticJsonDocument<256> d;
  d["app"] = "hbrt";
  d["name"] = ghName;
  d["id"] = chipId;
  d["fw"] = FW_VERSION;
  d["ws"] = 81;
  String s; serializeJson(d, s);
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", s);
}

/* ================= SETUP ================= */
void setup() {
  Serial.begin(115200);
  char id[7];
  snprintf(id, sizeof(id), "%06X", (uint32_t)(ESP.getEfuseMac() & 0xFFFFFF));
  chipId = id;
  loadConfig();
  dht.begin();
  ds18b20.begin();
  ds18b20.setWaitForConversion(false);

  int relays[] = {FAN_RELAY, PUMP_RELAY, LIGHTS_RELAY, HEATER_RELAY};
  for (int p : relays) { pinMode(p, OUTPUT); digitalWrite(p, HIGH); }

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

  server.on("/", []() { server.send(200, "text/plain", "HBRT Greenhouse Server is running."); });
  server.on("/info", handleInfo);
  server.begin();
  webSocket.begin();
  webSocket.onEvent(webSocketEvent);
  webSocket.enableHeartbeat(15000, 3000, 2);
}

/* ================= LOOP ================= */
void loop() {
  wifiWatchdog();
  server.handleClient();
  webSocket.loop();

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
