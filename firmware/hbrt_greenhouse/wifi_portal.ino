/* ================= WI-FI AND THE BOARD'S OWN WEB PAGES =================
   No Wi-Fi for 1 minute, or BOOT held for 3 s: the board opens its own hotspot "HBRT-Setup-xxxx".
   Join it with a phone, the setup page opens (or go to 192.168.4.1), pick your Wi-Fi.
   The greenhouse keeps running the whole time.                                              */

bool otaAuthOk = false;
unsigned long connectedSince = 0;
bool wasConnected = false;

void startWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.setHostname(("hbrt-" + chipId).c_str());
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
}

void startPortal() {
  if (portalOn) return;
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(("HBRT-Setup-" + chipId).c_str());
  dns.start(53, "*", WiFi.softAPIP());
  portalOn = true; portalSince = millis();
  WiFi.scanNetworks(true);
  logEvent(E_WIFI, 255, BY_SYS, RS_NONE, 0, 0);
  Serial.println("Setup hotspot: HBRT-Setup-" + chipId);
}

void stopPortal() {
  if (!portalOn) return;
  dns.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  portalOn = false;
}

void setupButton() {
  if (digitalRead(SETUP_PIN) == LOW) {
    if (!setupPressSince) setupPressSince = millis();
    else if (millis() - setupPressSince > 3000 && !portalOn) startPortal();
  } else setupPressSince = 0;
}

void wifiWatchdog() {
  if (WiFi.status() == WL_CONNECTED) {
    if (!wasConnected) { wasConnected = true; connectedSince = millis(); }
    wifiDownSince = 0;
    if (portalOn && WiFi.softAPgetStationNum() == 0 && millis() - connectedSince > 30000 && millis() - portalSince > 60000) stopPortal();
    return;
  }
  if (wasConnected) { wasConnected = false; wifiDrops++; }
  if (!wifiDownSince) wifiDownSince = millis();
  bool someoneOnSetup = portalOn && WiFi.softAPgetStationNum() > 0;
  // while a phone is on the setup page, don't retry: retries move the hotspot's channel and drop the phone
  if (!someoneOnSetup && millis() - lastWifiTry > 15000) {
    lastWifiTry = millis();
    WiFi.disconnect(false);
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  }
  if (!portalOn && millis() - wifiDownSince > 60000) startPortal();
  if (millis() - wifiDownSince > 15UL * 60000UL && !someoneOnSetup) ESP.restart();
}

/* ---------------- pages ---------------- */
const char PAGE_HEAD[] PROGMEM =
  "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
  "<title>HBRT Greenhouse</title><style>"
  "body{margin:0;font-family:system-ui,sans-serif;background:#08130e;color:#e8f3ec;display:grid;place-items:center;min-height:100vh}"
  ".b{width:min(420px,100%);box-sizing:border-box;padding:28px 20px}h1{margin:.2em 0 .1em;font-size:28px}"
  "p{color:#93ac9e}a.btn,button{display:block;width:100%;box-sizing:border-box;text-align:center;background:#39ff14;color:#062b00;"
  "padding:14px;border:0;border-radius:14px;font-weight:700;font-size:16px;text-decoration:none;margin:10px 0}"
  "a.alt{background:none;color:#e8f3ec;border:1px solid #1e3329}"
  "label{display:block;margin:14px 0 6px;color:#93ac9e;font-size:14px}"
  "input,select{width:100%;box-sizing:border-box;background:#0f1e17;color:#e8f3ec;border:1px solid #1e3329;border-radius:12px;padding:12px;font-size:16px}"
  ".k{color:#39ff14;font-size:12px;letter-spacing:.08em}</style></head><body><div class=b>";

void sendPage(const String& body) {
  String h = FPSTR(PAGE_HEAD);
  h += body;
  h += F("</div></body></html>");
  server.send(200, "text/html", h);
}

void redirectToSetup() {
  server.sendHeader("Location", "http://192.168.4.1/wifi", true);
  server.send(302, "text/plain", "");
}

void handleRoot() {
  if (portalOn && WiFi.status() != WL_CONNECTED) { redirectToSetup(); return; }
  String h = "<div class=k>HBRT GREENHOUSE</div><h1>" + ghName + "</h1><p>Firmware " FW_VERSION " &middot; " +
             String(temperature, 1) + "&deg;C &middot; soil " + String(soil) + "%</p>";
  if (cloudOn()) {
    h += "<a class=btn href='" + shareLink() + "'>Open from anywhere</a>";
    if (viewKey.length()) h += "<a class='btn alt' href='" + viewLink() + "'>View-only link</a>";
    h += F("<p>Anyone with the first link can control this greenhouse. Share it only with your team.</p>");
  }
  sendPage(h);
}

void handleInfo() {
  JsonDocument d;
  d["app"] = "hbrt"; d["name"] = ghName; d["id"] = chipId; d["fw"] = FW_VERSION; d["ws"] = 81;
  if (cloudOn()) { d["db"] = FIREBASE_HOST; d["key"] = cloudKey; d["cloud"] = (bool)cloudOk; }
  d["portal"] = portalOn;
  String s; serializeJson(d, s);
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", s);
}

void handleWifiPage() {
  if (!portalOn) { server.send(404, "text/plain", "Hold BOOT for 3 s to open Wi-Fi setup."); return; }
  String opts = "";
  int n = WiFi.scanComplete();
  if (n == WIFI_SCAN_FAILED) WiFi.scanNetworks(true);
  for (int i = 0; i < n && i < 20; i++) {
    String s = WiFi.SSID(i);
    if (!s.length() || opts.indexOf("'" + s + "'") >= 0) continue;
    opts += "<option value='" + s + "'>" + s + " (" + String(WiFi.RSSI(i)) + " dBm)</option>";
  }
  String h = F("<div class=k>WI-FI SETUP</div><h1>Pick your Wi-Fi</h1><p>The greenhouse keeps working while you do this.</p>"
               "<form method=post action=/wifi/save>");
  if (opts.length()) h += "<label>Network</label><select name=ssid>" + opts + "</select><label>Or type its name</label><input name=ssid2 placeholder='Wi-Fi name'>";
  else h += F("<p>Still searching. <a style='color:#39ff14' href=/wifi>Refresh</a></p><label>Wi-Fi name</label><input name=ssid2 required>");
  h += F("<label>Password</label><input name=pass type=password autocomplete=off><button>Connect</button></form>");
  h += "<p>Board " + chipId + "</p>";
  sendPage(h);
}

void handleWifiSave() {
  String s = server.arg("ssid2"); s.trim();
  if (!s.length()) s = server.arg("ssid");
  if (!s.length()) { redirectToSetup(); return; }
  wifiSsid = s; wifiPass = server.arg("pass");
  saveString("wssid", wifiSsid); saveString("wpass", wifiPass);
  logEvent(E_WIFI, 255, BY_USER, RS_NONE, 1, 0);
  sendPage("<div class=k>CONNECTING</div><h1>" + s + "</h1><p>The board is joining this Wi-Fi. Its screen shows <b>WEB</b> when it's online. "
           "If the setup hotspot comes back after a minute, the password was wrong: join it again and retry.</p>");
  delay(300);
  WiFi.disconnect(false);
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  lastWifiTry = millis();
}

void handleUpdateDone() {
  bool ok = otaAuthOk && !Update.hasError();
  server.sendHeader("Connection", "close");
  server.send(ok ? 200 : (otaAuthOk ? 500 : 401), "text/plain", ok ? "OK" : (otaAuthOk ? "Update failed" : "Wrong key"));
  if (ok) { delay(800); ESP.restart(); }
}

void handleUpdateUpload() {
  HTTPUpload& up = server.upload();
  if (up.status == UPLOAD_FILE_START) {
    otaAuthOk = server.header("X-Key") == cloudKey || server.arg("key") == cloudKey;
    if (otaAuthOk) { setOta("uploading"); Update.begin(UPDATE_SIZE_UNKNOWN); }
  } else if (up.status == UPLOAD_FILE_WRITE) {
    if (otaAuthOk && Update.write(up.buf, up.currentSize) != up.currentSize) setOta("failed: write");
  } else if (up.status == UPLOAD_FILE_END) {
    if (!otaAuthOk) return;
    if (Update.end(true)) { setOta("done"); logEvent(E_OTA, 255, BY_USER, RS_NONE, 1, 0); }
    else setOta("failed: " + String(Update.errorString()));
  }
}

void handleNotFound() {
  if (portalOn) { redirectToSetup(); return; }
  server.send(404, "text/plain", "Not found");
}

void setupHttp() {
  const char* hdrs[] = {"X-Key"};
  server.collectHeaders(hdrs, 1);
  server.on("/", handleRoot);
  server.on("/info", handleInfo);
  server.on("/wifi", handleWifiPage);
  server.on("/wifi/save", HTTP_POST, handleWifiSave);
  server.on("/update", HTTP_POST, handleUpdateDone, handleUpdateUpload);
  server.on("/generate_204", handleNotFound);          // Android captive-portal check
  server.on("/hotspot-detect.html", handleNotFound);   // iPhone
  server.on("/connecttest.txt", handleNotFound);       // Windows
  server.onNotFound(handleNotFound);
  server.begin();
}
