/* ================= STATE OUT =================
   The same JSON goes to the phones on Wi-Fi and to the cloud:
     s    live readings and devices (every 2 s)
     cfg  settings (when they change)
     wx   weather (every 30 min)
     use  water and power used (every minute)
     ev   timeline events                                                   */

void buildState(String& out, bool forView) {
  JsonDocument doc;
  doc["name"] = ghName;
  doc["id"] = chipId;
  doc["fw"] = FW_VERSION;
  doc["temperature"] = round(temperature * 10) / 10.0;
  doc["humidity"] = round(humidity);
  doc["soil"] = soil;
  doc["water"] = water;
  doc["soilTemp"] = round(soilTemp * 10) / 10.0;
  for (int d = 0; d < D_COUNT; d++) doc[DEV_NAME[d]] = dev[d];
  doc["mode"] = MODE_NAME[mode];
  doc["auto"] = mode != M_MANUAL;
  JsonObject o = doc["ovr"].to<JsonObject>();
  for (int d = 0; d < D_COUNT; d++) {
    if (!ovrUntil[d]) continue;
    o[DEV_NAME[d]] = ovrUntil[d] == OVR_FOREVER ? -1 : (long)((ovrUntil[d] - millis()) / 1000);
  }
  doc["rssi"] = WiFi.RSSI();
  doc["uptime"] = millis() / 1000;
  doc["pumpLock"] = pumpLocked();
  doc["dhtOk"] = dhtOk;
  doc["stOk"] = soilTempOk;
  doc["timeOk"] = timeOk();
  doc["watering"] = waterUntil ? max(0L, (long)(waterUntil - millis()) / 1000 + 1) : 0;
  doc["ip"] = WiFi.localIP().toString();
  JsonArray al = doc["al"].to<JsonArray>();
  for (int a = 0; a < A_COUNT; a++) if (alertActive & (1 << a)) al.add(ALERT_NAME[a]);
  int st = stageOf(gdd, cfg.crop);
  if (st >= 0) {
    doc["stage"] = STAGE_NAME[st];
    doc["gdd"] = round(gdd);
    doc["gddH"] = CROPS[cfg.crop].gddHarvest;
  }
  if (cfg.plantDay && localDay()) doc["day"] = localDay() - cfg.plantDay;
  if (cloudOn()) { doc["db"] = FIREBASE_HOST; doc["cloud"] = (bool)cloudOk; }
  if (forView) {
    doc["ro"] = true;
  } else {
    if (cloudOn()) doc["key"] = cloudKey;
    if (viewKey.length()) doc["view"] = viewKey;
    JsonObject raw = doc["raw"].to<JsonObject>();
    raw["s"] = rawSoil; raw["w"] = rawWater;
    JsonObject hp = doc["hp"].to<JsonObject>();
    hp["heap"] = ESP.getFreeHeap();
    hp["minHeap"] = ESP.getMinFreeHeap();
    hp["rr"] = (int)esp_reset_reason();
    hp["drops"] = wifiDrops;
    hp["cerr"] = cloudErrors;
    hp["code"] = cloudCode;
    String os = pull(otaStatus);
    if (os.length()) doc["ota"] = os;
    doc["tg"] = tgToken.length() == 0 ? "off" : tgState == 1 ? (tgChat.length() ? "ok" : "wait") : tgState == 2 ? "err" : "on";
    doc["portal"] = portalOn;
    doc["ssid"] = WiFi.SSID();
  }
  serializeJson(doc, out);
}

void buildCfg(String& out) {
  JsonDocument doc;
  doc["tempMax"] = cfg.tempMax; doc["tempMin"] = cfg.tempMin; doc["humMax"] = cfg.humMax;
  doc["soilMin"] = cfg.soilMin; doc["waterMin"] = cfg.waterMin; doc["pumpMax"] = cfg.pumpMaxSec;
  doc["lightSched"] = cfg.lightSched; doc["lightOn"] = cfg.lightOn; doc["lightOff"] = cfg.lightOff;
  doc["flowMl"] = cfg.flowMl; doc["tankL"] = cfg.tankL;
  JsonObject w = doc["watt"].to<JsonObject>();
  for (int d = 0; d < D_COUNT; d++) w[DEV_NAME[d]] = cfg.watt[d];
  doc["crop"] = cfg.crop >= 0 ? CROPS[cfg.crop].id : "";
  doc["plantDay"] = cfg.plantDay;
  doc["autoStage"] = cfg.autoStage;
  doc["lat"] = cfg.lat; doc["lon"] = cfg.lon;
  doc["rainSkip"] = cfg.rainSkip; doc["frostAlert"] = cfg.frostAlert;
  doc["quiet"] = cfg.quiet; doc["quietFrom"] = cfg.quietFrom; doc["quietTo"] = cfg.quietTo;
  doc["alertMask"] = cfg.alertMask; doc["dailySummary"] = cfg.dailySummary;
  doc["lang"] = cfg.lang == 1 ? "fa" : cfg.lang == 2 ? "ps" : "en";
  JsonObject c = doc["cal"].to<JsonObject>();
  c["soilDry"] = cfg.soilDry; c["soilWet"] = cfg.soilWet; c["waterEmpty"] = cfg.waterEmpty; c["waterFull"] = cfg.waterFull;
  JsonArray sa = doc["sched"].to<JsonArray>();
  for (int i = 0; i < nSched; i++) { JsonObject s = sa.add<JsonObject>(); s["m"] = sched[i].min; s["s"] = sched[i].sec; s["d"] = sched[i].days; }
  const char* SENS[] = {"T", "H", "S", "ST", "W"};
  JsonArray ra = doc["rules"].to<JsonArray>();
  for (int i = 0; i < nRules; i++) {
    JsonObject r = ra.add<JsonObject>();
    r["s"] = SENS[min(4, (int)rules[i].sensor)]; r["o"] = rules[i].op ? ">" : "<"; r["v"] = rules[i].val;
    r["d"] = DEV_NAME[min(4, (int)rules[i].dev)]; r["on"] = (bool)rules[i].on;
  }
  doc["tgSet"] = tgToken.length() > 0;
  doc["tgChat"] = tgChat;
  serializeJson(doc, out);
}

void buildUse() {
  JsonDocument doc;
  JsonObject t = doc["today"].to<JsonObject>();
  t["L"] = round(litersToday() * 100) / 100.0;
  t["kWh"] = round(kwhToday() * 1000) / 1000.0;
  JsonObject on = t["on"].to<JsonObject>();
  for (int d = 0; d < D_COUNT; d++) on[DEV_NAME[d]] = onMs[d] / 1000;
  if (localDay()) t["d"] = localDay();
  JsonArray days = doc["days"].to<JsonArray>();
  for (int i = 0; i < 7; i++) {
    if (!dayNum[i]) continue;
    JsonObject o = days.add<JsonObject>();
    o["d"] = dayNum[i]; o["L"] = round(dayL[i] * 100) / 100.0; o["kWh"] = round(dayKwh[i] * 1000) / 1000.0;
  }
  String s; serializeJson(doc, s);
  xSemaphoreTake(lock, portMAX_DELAY); jsUse = s; xSemaphoreGive(lock);
  dUse = vUse = true;
  if (webSocket.connectedClients()) webSocket.broadcastTXT("{\"use\":" + s + "}");
}

void publishCfg() {
  String s; buildCfg(s);
  xSemaphoreTake(lock, portMAX_DELAY); jsCfg = s; xSemaphoreGive(lock);
  dCfg = vCfg = true;
  if (webSocket.connectedClients()) webSocket.broadcastTXT("{\"cfg\":" + s + "}");
}

void publishWx() {
  JsonDocument doc;
  xSemaphoreTake(lock, portMAX_DELAY);
  bool ok = wx.ok;
  if (ok) {
    doc["t"] = (uint64_t)wx.at * 1000ULL;
    doc["now"] = wx.now; doc["code"] = wx.code;
    JsonArray a = doc["d"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
      JsonObject o = a.add<JsonObject>();
      o["max"] = wx.tMax[i]; o["min"] = wx.tMin[i]; o["pp"] = wx.pp[i]; o["ps"] = wx.ps[i]; o["code"] = wx.dcode[i];
    }
    doc["rain"] = wx.ps[0] >= 1.0f || wx.pp[0] >= 60;
    doc["frost"] = wx.tMin[1] < 2;
  }
  xSemaphoreGive(lock);
  if (!ok) return;
  String s; serializeJson(doc, s);
  xSemaphoreTake(lock, portMAX_DELAY); jsWx = s; xSemaphoreGive(lock);
  dWx = vWx = true;
  if (webSocket.connectedClients()) webSocket.broadcastTXT("{\"wx\":" + s + "}");
}

/* Called every 2 s; now = true after a command so the cloud gets it right away. */
void publish(bool now) {
  if (cfgPending) { cfgPending = false; publishCfg(); }
  String s, sv;
  buildState(s, false);
  if (viewKey.length()) buildState(sv, true);
  xSemaphoreTake(lock, portMAX_DELAY);
  jsS = s; jsSView = sv;
  xSemaphoreGive(lock);
  if (now) { dS = true; vS = true; }
  if (webSocket.connectedClients()) webSocket.broadcastTXT("{\"s\":" + s + "}");
  sendNewEventsWs();
}

void sendEverything(uint8_t num) {
  String s; buildState(s, false);
  webSocket.sendTXT(num, "{\"s\":" + s + "}");
  String c; buildCfg(c);
  webSocket.sendTXT(num, "{\"cfg\":" + c + "}");
  xSemaphoreTake(lock, portMAX_DELAY);
  String w = jsWx, u = jsUse;
  xSemaphoreGive(lock);
  if (w.length()) webSocket.sendTXT(num, "{\"wx\":" + w + "}");
  if (u.length()) webSocket.sendTXT(num, "{\"use\":" + u + "}");
  sendAllEventsWs(num);
}
