/* ================= COMMANDS =================
   Same JSON from the app, the website, the cloud and Telegram, e.g.
     {"device":"fan","value":true,"mins":30}     on for 30 min, then automatic again (-1 = until changed)
     {"device":"mode","value":"away"}           auto | manual | away
     {"device":"water","value":20}              water now for 20 s
     {"device":"config", ...any settings...}
   Commands that change the board's Wi-Fi or keys only work on the local network.             */

int devIndex(const String& n) {
  for (int d = 0; d < D_COUNT; d++) if (n == DEV_NAME[d]) return d;
  if (n == "vent") return D_VENT;
  return -1;
}

void setMode(uint8_t m, uint8_t by) {
  if (m > M_AWAY || m == mode) return;
  uint8_t old = mode;
  mode = m;
  if (old == M_MANUAL) for (int d = 0; d < D_COUNT; d++) ovrUntil[d] = 0;   // fresh start in automatic
  prefs.begin("hbrt", false); prefs.putUChar("mode", mode); prefs.end();
  logEvent(E_MODE, 255, by, RS_NONE, m, 0);
}

uint8_t modeFrom(const String& s) { return s == "manual" ? M_MANUAL : s == "away" ? M_AWAY : M_AUTO; }

void setOverride(int d, bool v, int mins, uint8_t by) {
  ovrVal[d] = v;
  uint32_t until = mins <= 0 ? OVR_FOREVER : millis() + (uint32_t)mins * 60000UL;
  if (until == 0 || (mins > 0 && until == OVR_FOREVER)) until = 1;
  ovrUntil[d] = until;
  setDev(d, v, by, RS_MANUAL, mins);
}

void applyConfig(JsonDocument& c, uint8_t by) {
#define SETF(k, f) if (!c[k].isNull()) f = c[k]
  SETF("tempMax", cfg.tempMax); SETF("tempMin", cfg.tempMin); SETF("humMax", cfg.humMax);
  if (!c["soilMin"].isNull())  cfg.soilMin = constrain((int)c["soilMin"], 0, 100);
  if (!c["waterMin"].isNull()) cfg.waterMin = constrain((int)c["waterMin"], 0, 100);
  if (!c["pumpMax"].isNull())  cfg.pumpMaxSec = constrain((int)c["pumpMax"], 5, 600);
  SETF("lightSched", cfg.lightSched);
  if (!c["lightOn"].isNull())  cfg.lightOn = constrain((int)c["lightOn"], 0, 1439);
  if (!c["lightOff"].isNull()) cfg.lightOff = constrain((int)c["lightOff"], 0, 1439);
  if (!c["flowMl"].isNull())   cfg.flowMl = constrain((int)c["flowMl"], 1, 1000);
  if (!c["tankL"].isNull())    cfg.tankL = constrain((int)c["tankL"], 1, 10000);
  if (c["watt"].is<JsonObject>()) for (int d = 0; d < D_COUNT; d++) if (!c["watt"][DEV_NAME[d]].isNull()) cfg.watt[d] = constrain((int)c["watt"][DEV_NAME[d]], 0, 5000);
  SETF("autoStage", cfg.autoStage);
  bool loc = false;
  if (!c["lat"].isNull()) { cfg.lat = c["lat"]; loc = true; }
  if (!c["lon"].isNull()) { cfg.lon = c["lon"]; loc = true; }
  SETF("rainSkip", cfg.rainSkip); SETF("frostAlert", cfg.frostAlert);
  SETF("quiet", cfg.quiet); SETF("quietFrom", cfg.quietFrom); SETF("quietTo", cfg.quietTo);
  SETF("alertMask", cfg.alertMask); SETF("dailySummary", cfg.dailySummary);
  if (!c["lang"].isNull()) { String l = c["lang"].as<String>(); cfg.lang = l == "fa" ? 1 : l == "ps" ? 2 : 0; }
  if (c["cal"].is<JsonObject>()) {
    JsonObject k = c["cal"];
    if (!k["soilDry"].isNull()) cfg.soilDry = k["soilDry"];
    if (!k["soilWet"].isNull()) cfg.soilWet = k["soilWet"];
    if (!k["waterEmpty"].isNull()) cfg.waterEmpty = k["waterEmpty"];
    if (!k["waterFull"].isNull()) cfg.waterFull = k["waterFull"];
  }
  if (c["sched"].is<JsonArray>()) {
    nSched = 0;
    for (JsonObject s : c["sched"].as<JsonArray>()) {
      if (nSched >= 4) break;
      int sec = s["s"] | 0; if (sec <= 0) continue;
      sched[nSched].min = constrain((int)(s["m"] | 360), 0, 1439);
      sched[nSched].sec = constrain(sec, 1, 600);
      sched[nSched].days = (s["d"] | 127) & 127;
      schedFired[nSched] = localDay() && (s["m"] | 360) <= minutesNow() ? localDay() : 0;   // a time already passed today waits for tomorrow
      nSched++;
    }
  }
  if (c["rules"].is<JsonArray>()) {
    nRules = 0;
    for (JsonObject r : c["rules"].as<JsonArray>()) {
      if (nRules >= 6) break;
      String s = r["s"] | "S";
      int d = devIndex(r["d"] | "");
      if (d < 0) continue;
      rules[nRules].sensor = s == "T" ? 0 : s == "H" ? 1 : s == "S" ? 2 : s == "ST" ? 3 : 4;
      rules[nRules].op = String(r["o"] | "<") == ">" ? 1 : 0;
      rules[nRules].val = r["v"] | 0.0f;
      rules[nRules].dev = d;
      rules[nRules].on = r["on"] | true;
      nRules++;
    }
  }
  if (!c["crop"].isNull() || !c["plantDay"].isNull()) {
    int crop = cfg.crop;
    if (!c["crop"].isNull()) {
      String id = c["crop"].as<String>(); crop = -1;
      for (int i = 0; i < N_CROPS; i++) if (id == CROPS[i].id) crop = i;
    }
    int32_t day = c["plantDay"].isNull() ? cfg.plantDay : (int32_t)c["plantDay"];
    setPlanting(crop, day);
  }
  if (!c["tgToken"].isNull()) {
    String t = c["tgToken"].as<String>(); t.trim();
    xSemaphoreTake(lock, portMAX_DELAY);
    if (t != tgToken) { tgToken = t; tgChat = ""; }
    xSemaphoreGive(lock);
    saveString("tgtok", tgToken); saveString("tgchat", tgChat);
  }
  if (!c["tgChat"].isNull()) {
    xSemaphoreTake(lock, portMAX_DELAY); tgChat = c["tgChat"].as<String>(); xSemaphoreGive(lock);
    saveString("tgchat", tgChat);
  }
#undef SETF
  if (cfg.tempMin >= cfg.tempMax) cfg.tempMin = cfg.tempMax - 1;
  saveConfig();
  if (loc) wxRefresh = true;
  logEvent(E_CFG, 255, by, RS_NONE, 0, 0);
}

void calibrate(const String& what, uint8_t by) {
  if (what == "soilDry") cfg.soilDry = rawSoil;
  else if (what == "soilWet") cfg.soilWet = rawSoil;
  else if (what == "waterEmpty") cfg.waterEmpty = rawWater;
  else if (what == "waterFull") cfg.waterFull = rawWater;
  else if (what == "reset") { cfg.soilDry = 4095; cfg.soilWet = 0; cfg.waterEmpty = 0; cfg.waterFull = 4095; }
  else return;
  saveConfig();
  logEvent(E_CAL, 255, by, RS_NONE, 0, 0);
  readSensors();
}

/* wsNum >= 0: from a phone on the local Wi-Fi. -1: from the cloud or Telegram. */
void applyCommand(JsonDocument& doc, int wsNum, uint8_t by) {
  if (doc["ping"]) { if (wsNum >= 0) webSocket.sendTXT(wsNum, "{\"pong\":1}"); return; }
  bool local = wsNum >= 0;
  String device = doc["device"] | "";
  int d = devIndex(device);

  if (d >= 0) {
    bool v = doc["value"] | false;
    bool hasMins = !doc["mins"].isNull();
    int mins = doc["mins"] | 0;
    if (d == D_PUMP) {
      if (v) { ovrUntil[D_PUMP] = 0; startWatering(doc["sec"] | cfg.pumpMaxSec, by); }
      else {
        stopWatering();
        if (!hasMins) setMode(M_MANUAL, by);
        else if (mode != M_MANUAL) setOverride(D_PUMP, false, mins, by);
        setDev(D_PUMP, false, by, RS_NONE, 0);
      }
    } else if (!hasMins) {                    // old apps: switching a device means manual mode
      setMode(M_MANUAL, by);
      setDev(d, v, by, RS_NONE, 0);
    } else if (mode == M_MANUAL) {
      setDev(d, v, by, RS_NONE, 0);
    } else {
      setOverride(d, v, mins, by);
    }
  }
  else if (device == "ovr_clear") {
    String which = doc["value"] | "all";
    for (int i = 0; i < D_COUNT; i++) if (which == "all" || which == DEV_NAME[i] || (which == "vent" && i == D_VENT)) ovrUntil[i] = 0;
  }
  else if (device == "auto")      setMode((doc["value"] | false) ? M_AUTO : M_MANUAL, by);
  else if (device == "mode")      setMode(modeFrom(doc["value"] | "auto"), by);
  else if (device == "water")     startWatering(doc["value"] | 10, by);
  else if (device == "stopwater") { stopWatering(); setDev(D_PUMP, false, by, RS_NONE, 0); }
  else if (device == "alloff") {
    setMode(M_MANUAL, by);
    stopWatering();
    for (int i = 0; i < D_COUNT; i++) { ovrUntil[i] = 0; setDev(i, false, by, RS_NONE, 0); }
  }
  else if (device == "config")    applyConfig(doc, by);
  else if (device == "name") {
    String n = doc["value"] | ""; n.trim();
    if (n.length() > 0 && n.length() <= 32) { ghName = n; saveConfig(); }
  }
  else if (device == "time") {                // the phone's local time: learn the time zone
    uint32_t loc = doc["value"] | 0UL, u = utcNow();
    if (loc > 1600000000UL && u) {
      long off = lround(((double)loc - (double)u) / 900.0) * 900L;
      if (off != tzOffset) { tzOffset = off; prefs.begin("hbrt", false); prefs.putLong("tz", tzOffset); prefs.end(); }
    }
    return;
  }
  else if (device == "cal")       calibrate(doc["value"] | "", by);
  else if (device == "view") {
    bool on = doc["value"] | true;
    xSemaphoreTake(lock, portMAX_DELAY);
    viewKey = on ? (viewKey.length() ? viewKey : makeKey("gv")) : String("");
    xSemaphoreGive(lock);
    saveString("vkey", viewKey);
    vS = vCfg = vWx = vUse = true;
  }
  else if (device == "tgtest")    tgTestRequest = true;
  else if (device == "ota") {
    String u = doc["value"] | "";
    xSemaphoreTake(lock, portMAX_DELAY); otaUrl = u.startsWith("https://") ? u : String(OTA_URL); xSemaphoreGive(lock);
    setOta("starting");
    otaRequest = true;
  }
  else if (device == "reboot")    { publish(true); delay(300); ESP.restart(); }
  else if (local && device == "rekey") {
    String k = makeKey("gh");
    xSemaphoreTake(lock, portMAX_DELAY); cloudKey = k; xSemaphoreGive(lock);
    saveString("ckey", k);
    dS = dCfg = dWx = dUse = true;
  }
  else if (local && device == "wifi") {
    String s = doc["ssid"] | "", p = doc["pass"] | "";
    if (s.length()) { wifiSsid = s; wifiPass = p; saveString("wssid", s); saveString("wpass", p); logEvent(E_WIFI, 255, by, RS_NONE, 1, 0); publish(true); delay(200); WiFi.disconnect(); WiFi.begin(wifiSsid.c_str(), wifiPass.c_str()); }
    return;
  }
  else if (local && device == "wifisetup") startPortal();
  else return;

  tick();
  publish(true);
}

void drainCommands() {
  CmdMsg m;
  while (xQueueReceive(cmdQueue, &m, 0) == pdTRUE) {
    JsonDocument doc;
    if (!deserializeJson(doc, m.json)) applyCommand(doc, -1, m.by);
  }
}

void webSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
  if (type == WStype_CONNECTED) { sendEverything(num); return; }
  if (type != WStype_TEXT) return;
  JsonDocument doc;
  if (!deserializeJson(doc, payload, length)) applyCommand(doc, num, BY_USER);
}
