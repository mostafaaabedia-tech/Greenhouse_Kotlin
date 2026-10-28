/* ================= EVENTS (activity timeline) =================
   Every change is stored with who did it and why. Apps turn the codes into sentences in
   their own language. The newest 40 are kept here and in the cloud (/live/ev).             */

uint32_t evWsSent = 0;
int32_t summaryDay = 0;

void logEvent(uint8_t c, uint8_t d, uint8_t by, uint8_t w, uint8_t v, float x) {
  xSemaphoreTake(lock, portMAX_DELAY);
  Event& e = evRing[evSeq % EV_N];
  e.n = evSeq; e.t = utcNow(); e.c = c; e.d = d; e.by = by; e.w = w; e.v = v; e.x = x;
  evSeq++;
  xSemaphoreGive(lock);
}

void evToJson(const Event& e, JsonObject o) {
  o["n"] = e.n;
  o["t"] = (uint64_t)e.t * 1000ULL;
  o["c"] = EV_NAME[e.c];
  if (e.d < D_COUNT) o["d"] = DEV_NAME[e.d];
  o["by"] = BY_NAME[e.by];
  if (e.w && e.w < RS_COUNT) o["w"] = WHY_NAME[e.w];
  if (e.c == E_ALERT && e.v < A_COUNT) o["a"] = ALERT_NAME[e.v];
  else if (e.c == E_MODE && e.v <= M_AWAY) o["m"] = MODE_NAME[e.v];
  else if (e.c == E_STAGE && e.v < 5) o["st"] = STAGE_NAME[e.v];
  else o["v"] = e.v;
  o["x"] = round(e.x * 10) / 10.0;
}

/* New events to the phones on Wi-Fi (the cloud task sends them to Firebase). */
void sendNewEventsWs() {
  if (evWsSent == evSeq) return;
  if (webSocket.connectedClients() == 0) { evWsSent = evSeq; return; }
  JsonDocument doc;
  JsonObject ev = doc["ev"].to<JsonObject>();
  xSemaphoreTake(lock, portMAX_DELAY);
  if (evSeq - evWsSent > EV_N) evWsSent = evSeq - EV_N;
  for (uint32_t n = evWsSent; n < evSeq; n++) evToJson(evRing[n % EV_N], ev[String(n % EV_N)].to<JsonObject>());
  evWsSent = evSeq;
  xSemaphoreGive(lock);
  String s; serializeJson(doc, s);
  webSocket.broadcastTXT(s);
}

void sendAllEventsWs(uint8_t num) {
  JsonDocument doc;
  JsonObject ev = doc["ev"].to<JsonObject>();
  xSemaphoreTake(lock, portMAX_DELAY);
  uint32_t from = evSeq > EV_N ? evSeq - EV_N : 0;
  for (uint32_t n = from; n < evSeq; n++) evToJson(evRing[n % EV_N], ev[String(n % EV_N)].to<JsonObject>());
  xSemaphoreGive(lock);
  String s; serializeJson(doc, s);
  webSocket.sendTXT(num, s);
}

/* ================= ALERTS ================= */
bool quietNow() {
  int m = minutesNow();
  return cfg.quiet && m >= 0 && inWindow(m, cfg.quietFrom, cfg.quietTo);
}

float frostMin() {
  float v = 99;
  xSemaphoreTake(lock, portMAX_DELAY);
  if (wx.ok && utcNow() - wx.at < 6UL * 3600UL) v = wx.tMin[1];
  xSemaphoreGive(lock);
  return v;
}

void checkAlerts() {
  if (firstRead) return;
  Eff e = effLimits();
  float fm = frostMin();
  bool cond[A_COUNT] = {
    dhtOk && temperature > e.tMax,
    dhtOk && temperature < e.tMin,
    dhtOk && humidity > e.hMax,
    soil < e.sMin,
    water <= cfg.waterMin,
    pumpLocked(),
    !dhtOk,
    !soilTempOk,
    cfg.frostAlert && fm < 2,
  };
  float val[A_COUNT] = {temperature, temperature, humidity, (float)soil, (float)water, 0, 0, 0, fm};
  for (int a = 0; a < A_COUNT; a++) {
    if (!cond[a]) { alertCount[a] = 0; alertActive &= ~(1 << a); continue; }
    if (alertCount[a] < 255) alertCount[a]++;
    if (alertCount[a] != 3) continue;                 // held for 6 s: real, not a glitch
    alertActive |= (1 << a);
    logEvent(E_ALERT, 255, BY_SYS, RS_NONE, a, val[a]);
    bool critical = a == A_TANK || a == A_FROST;
    bool cooled = !alertLastSent[a] || millis() - alertLastSent[a] > 30UL * 60UL * 1000UL;
    if ((cfg.alertMask & (1 << a)) && cooled && (critical || !quietNow())) {
      alertLastSent[a] = millis();
      tgAlert(a, val[a]);
    }
  }
  // daily summary at 20:00
  int m = minutesNow(); int32_t today = localDay();
  if (cfg.dailySummary && m == 20 * 60 && summaryDay != today) { summaryDay = today; tgSummary(); }
}
