/* ================= NETWORK TASK (core 0) =================
   Slow internet never freezes sensors, relays or the screen: all of this runs on the other core.
   Firebase layout, under one private key:
     /hbrt/<key>/live/s|cfg|wx|use|ev   what the apps show (streamed live)
     /hbrt/<key>/in/cmd                 commands, deleted once done
     /hbrt/<key>/in/seen                someone is watching: send every 2 s instead of 20 s
     /hbrt/<key>/log/<slot>             5-minute history, 7 days
   The view-only link gets a copy under /hbrt/<viewKey> (no control, no keys).             */

WiFiClientSecure fbTls;
HTTPClient fbHttp;

struct LogPt { uint32_t t; uint16_t slot; float T, H, ST; uint8_t S, W, P; };
const int LOG_BUF = 288;                      // 24 h of points kept while the internet is down
LogPt logBuf[LOG_BUF];
int logHead = 0, logCount = 0;
String pendingDel;                            // commands to delete from /in/cmd once handled

String fbUrl(const String& key, const String& path) {
  String u = String("https://") + FIREBASE_HOST + "/hbrt/" + key + path + ".json";
  if (strlen(FIREBASE_AUTH) > 0) u += String("?auth=") + FIREBASE_AUTH;
  return u;
}

int fbReq(const char* method, const String& key, const String& path, const String& body, String* reply) {
  if (!fbHttp.begin(fbTls, fbUrl(key, path))) return -1;
  fbHttp.addHeader("Content-Type", "application/json");
  int code = strcmp(method, "GET") == 0 ? fbHttp.GET() : fbHttp.sendRequest(method, body);
  if (reply && code == 200) *reply = fbHttp.getString();
  else if (code > 0) fbHttp.getString();      // drain so the connection can be reused
  fbHttp.end();
  if (code != 200) cloudErrors++;
  return code;
}

String withTime(const String& json) { return json.length() > 2 ? "{\"t\":{\".sv\":\"timestamp\"}," + json.substring(1) : json; }

String pull(String& src) { xSemaphoreTake(lock, portMAX_DELAY); String s = src; xSemaphoreGive(lock); return s; }

/* Events newer than *sent as one PATCH body, or "" when there is nothing new. */
String eventBatch(uint32_t& sent, uint32_t& upto) {
  xSemaphoreTake(lock, portMAX_DELAY);
  upto = evSeq;
  if (sent == upto) { xSemaphoreGive(lock); return ""; }
  if (upto - sent > EV_N) sent = upto - EV_N;
  JsonDocument doc;
  int n = 0;
  for (uint32_t i = sent; i < upto && n < 12; i++, n++) evToJson(evRing[i % EV_N], doc[String(i % EV_N)].to<JsonObject>());
  upto = sent + n;
  xSemaphoreGive(lock);
  String s; serializeJson(doc, s);
  return s;
}

void logPoint(uint32_t u) {
  LogPt& p = logBuf[(logHead + logCount) % LOG_BUF];
  if (logCount == LOG_BUF) logHead = (logHead + 1) % LOG_BUF; else logCount++;
  p.t = u; p.slot = (u / 300) % 2016;
  p.T = temperature; p.H = humidity; p.ST = soilTemp; p.S = soil; p.W = water; p.P = dev[D_PUMP];
}

String logBatch(int& n) {
  JsonDocument doc;
  n = min(logCount, 24);
  for (int i = 0; i < n; i++) {
    LogPt& p = logBuf[(logHead + i) % LOG_BUF];
    JsonObject o = doc[String(p.slot)].to<JsonObject>();
    o["t"] = (uint64_t)p.t * 1000ULL;
    o["T"] = round(p.T * 10) / 10.0; o["H"] = round(p.H); o["S"] = p.S; o["ST"] = round(p.ST * 10) / 10.0; o["W"] = p.W;
  }
  String s; serializeJson(doc, s);
  return s;
}

/* ---------------- weather (Open-Meteo, free, no key) ---------------- */
bool fetchWeather() {
  char url[360];
  snprintf(url, sizeof(url),
           "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
           "&current=temperature_2m,weather_code"
           "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,weather_code"
           "&timezone=auto&forecast_days=4", cfg.lat, cfg.lon);
  WiFiClientSecure c; c.setInsecure();
  HTTPClient h; h.setTimeout(8000);
  if (!h.begin(c, url)) return false;
  int code = h.GET();
  if (code != 200) { h.end(); return false; }
  String body = h.getString();
  h.end();
  JsonDocument doc;
  if (deserializeJson(doc, body)) return false;
  JsonObject d = doc["daily"];
  xSemaphoreTake(lock, portMAX_DELAY);
  wx.now = doc["current"]["temperature_2m"] | 0.0f;
  wx.code = doc["current"]["weather_code"] | 0;
  for (int i = 0; i < 4; i++) {
    wx.tMax[i] = d["temperature_2m_max"][i] | 0.0f;
    wx.tMin[i] = d["temperature_2m_min"][i] | 0.0f;
    wx.pp[i] = d["precipitation_probability_max"][i] | 0.0f;
    wx.ps[i] = d["precipitation_sum"][i] | 0.0f;
    wx.dcode[i] = d["weather_code"][i] | 0;
  }
  wx.ok = true; wx.at = utcNow();
  xSemaphoreGive(lock);
  if (!doc["utc_offset_seconds"].isNull()) {
    long off = doc["utc_offset_seconds"];
    if (off != tzOffset) { tzOffset = off; tzSave = true; }
  }
  wxChanged = true;
  return true;
}

/* ---------------- firmware update over the internet ---------------- */
void setOta(const String& s) { xSemaphoreTake(lock, portMAX_DELAY); otaStatus = s; xSemaphoreGive(lock); dS = true; }

void doOta() {
  otaRequest = false;
  String url = pull(otaUrl);
  setOta("downloading");
  fbHttp.end(); fbTls.stop(); tgStop();          // free memory for the download
  WiFiClientSecure c; c.setInsecure();
  httpUpdate.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
  httpUpdate.rebootOnUpdate(false);
  t_httpUpdate_return r = httpUpdate.update(c, url);
  if (r == HTTP_UPDATE_OK) {
    setOta("done");
    logEvent(E_OTA, 255, BY_USER, RS_NONE, 1, 0);
    vTaskDelay(pdMS_TO_TICKS(2500));            // give the loop time to tell everyone
    ESP.restart();
  } else {
    setOta("failed: " + httpUpdate.getLastErrorString());
    logEvent(E_OTA, 255, BY_USER, RS_NONE, 0, httpUpdate.getLastError());
  }
}

/* ---------------- commands ---------------- */
/* Reads /in (commands + "someone is watching"). Returns how many commands were queued. */
int pollCommands(const String& key) {
  String reply;
  int code = fbReq("GET", key, "/in", "", &reply);
  if (code != 200) { if (code != 0) { cloudOk = false; cloudCode = code; } return -1; }
  cloudOk = true;
  if (reply == "null") { cloudFast = false; return 0; }
  JsonDocument in;
  if (deserializeJson(in, reply)) return 0;
  double seen = in["seen"] | 0.0;
  uint32_t u = utcNow();
  cloudFast = seen > 0 && (!u || ((double)u * 1000.0 - seen) < 60000.0);
  JsonObject cmds = in["cmd"].as<JsonObject>();
  if (cmds.isNull() || cmds.size() == 0) return 0;
  int n = 0;
  String del = "{";
  for (JsonPair kv : cmds) {
    JsonObject c = kv.value().as<JsonObject>();
    double t = c["t"] | 0.0;
    bool fresh = !u || t == 0 || ((double)u * 1000.0 - t) < 60000.0;   // never act on old commands
    if (fresh) {
      CmdMsg m; m.by = BY_USER;
      c.remove("t");
      serializeJson(c, m.json, sizeof(m.json));
      if (xQueueSend(cmdQueue, &m, 0) == pdTRUE) n++;
    }
    if (del.length() > 1) del += ",";
    del += "\"" + String(kv.key().c_str()) + "\":null";
  }
  del += "}";
  pendingDel = del;                           // deleted right after the new state is out
  return n;
}

/* ---------------- the task ----------------
   Commands come first: /in is read on every pass while someone is watching, and as soon as a
   command is applied the new state goes out. Everything else (settings, timeline, view copy,
   history, weather, Telegram) takes turns, one job per pass, so it never holds a command up. */
void cloudTask(void*) {
  fbTls.setInsecure();               // no certificate check (fine for a school project)
  fbTls.setHandshakeTimeout(12);
  fbHttp.setReuse(true);             // keep one connection open = fast requests
  fbHttp.setTimeout(6000);

  unsigned long lastS = 0, lastPoll = 0, lastVS = 0, lastVSeen = 0, lastWx = 0, lastLogTry = 0;
  uint32_t evSent = 0, vEvSent = 0, lastSlot = 0xFFFFFFFF;
  int fails = 0, job = 0;
  bool wxTried = false;

  for (;;) {
    if (WiFi.status() != WL_CONNECTED) { cloudOk = false; vTaskDelay(pdMS_TO_TICKS(1000)); continue; }
    if (otaRequest) { doOta(); continue; }
    bool fast = cloudFast;
    String key = pull(cloudKey), vkey = pull(viewKey);

    // 1) commands
    if (millis() - lastPoll >= (fast ? 0UL : 2500UL)) {
      lastPoll = millis();
      uint32_t before = cmdDone;
      int n = pollCommands(key);
      if (n < 0) fails++; else fails = 0;
      if (n > 0) {
        unsigned long w = millis();          // the main loop applies them within a few ms
        while (cmdDone - before < (uint32_t)n && millis() - w < 800) vTaskDelay(pdMS_TO_TICKS(5));
        dS = true;
      }
    }

    // 2) live state: right after a change, every 2 s while watched, every 20 s otherwise
    unsigned long now = millis();
    if (dS || !lastS || now - lastS > (fast ? 2000UL : 20000UL)) {
      dS = false;
      String s = pull(jsS);
      if (s.length() > 2) {
        int c = fbReq("PUT", key, "/live/s", withTime(s), nullptr);
        cloudOk = c == 200; cloudCode = c; lastS = millis();
        if (c == 200) fails = 0; else fails++;
      }
    }
    if (pendingDel.length()) { String d = pendingDel; pendingDel = ""; fbReq("PATCH", key, "/in/cmd", d, nullptr); continue; }

    // 3) one other job per pass
    switch (job++ % 6) {
      case 0:   // settings, weather, usage when they change
        if (dCfg) { String s = pull(jsCfg); if (s.length() && fbReq("PUT", key, "/live/cfg", s, nullptr) == 200) dCfg = false; }
        else if (dWx)  { String s = pull(jsWx);  if (!s.length() || fbReq("PUT", key, "/live/wx", s, nullptr) == 200) dWx = false; }
        else if (dUse) { String s = pull(jsUse); if (!s.length() || fbReq("PUT", key, "/live/use", s, nullptr) == 200) dUse = false; }
        break;
      case 1: { // timeline
        uint32_t upto;
        String eb = eventBatch(evSent, upto);
        if (eb.length() && fbReq("PATCH", key, "/live/ev", eb, nullptr) == 200) evSent = upto;
        break;
      }
      case 2:   // view-only copy
        if (!vkey.length()) break;
        if (now - lastVSeen > 15000) {
          lastVSeen = millis();
          String r;
          if (fbReq("GET", vkey, "/seen", "", &r) == 200) {
            double seen = r.toDouble(); uint32_t u = utcNow();
            viewFast = seen > 0 && u && ((double)u * 1000.0 - seen) < 60000.0;
          }
        } else if (vS || !lastVS || now - lastVS > (viewFast ? 5000UL : 60000UL)) {
          vS = false; lastVS = millis();
          String s = pull(jsSView);
          if (s.length() > 2) fbReq("PUT", vkey, "/live/s", withTime(s), nullptr);
        } else if (vCfg) { String s = pull(jsCfg); if (s.length() && fbReq("PUT", vkey, "/live/cfg", s, nullptr) == 200) vCfg = false; }
        else if (vWx)  { String s = pull(jsWx);  if (!s.length() || fbReq("PUT", vkey, "/live/wx", s, nullptr) == 200) vWx = false; }
        else if (vUse) { String s = pull(jsUse); if (!s.length() || fbReq("PUT", vkey, "/live/use", s, nullptr) == 200) vUse = false; }
        else {
          uint32_t upto;
          String vb = eventBatch(vEvSent, upto);
          if (vb.length() && fbReq("PATCH", vkey, "/live/ev", vb, nullptr) == 200) vEvSent = upto;
        }
        break;
      case 3: { // history: one point every 5 minutes, kept here while the internet is down
        uint32_t u = utcNow();
        if (u && !firstRead) {
          uint32_t slot = (u / 300) % 2016;
          if (slot != lastSlot) { lastSlot = slot; logPoint(u); }
        }
        if (logCount && cloudOk && now - lastLogTry > 5000) {
          lastLogTry = millis();
          int n; String b = logBatch(n);
          if (fbReq("PATCH", key, "/log", b, nullptr) == 200) {
            if (vkey.length()) fbReq("PATCH", vkey, "/log", b, nullptr);
            logHead = (logHead + n) % LOG_BUF; logCount -= n;
          }
        }
        break;
      }
      case 4:   // weather every 30 minutes, and when the location changes
        if (wxRefresh || !wxTried || now - lastWx > (wx.ok ? 30UL * 60000UL : 5UL * 60000UL)) {
          wxRefresh = false; wxTried = true; lastWx = millis();
          fetchWeather();
        }
        break;
      case 5:   // Telegram
        tgService();
        break;
    }

    if (fails >= 3) { fbHttp.end(); fbTls.stop(); fails = 0; }   // fresh connection after repeated errors
    vTaskDelay(pdMS_TO_TICKS(fast ? 20 : 150));
  }
}
