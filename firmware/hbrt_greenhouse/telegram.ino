/* ================= TELEGRAM BOT =================
   1. Make a bot with @BotFather, copy its token into the app (Settings > Telegram).
   2. Send /start to your bot. The first chat that does is linked to this greenhouse.
   Alerts arrive there (quiet hours respected, except an empty tank and frost), plus a summary at 20:00.
   Commands: /status  /water 10  /fan on  /fan off  /lights on  /heater on  /vent on  /auto  /manual  /away */

WiFiClientSecure tgTls;
HTTPClient tgHttp;
long tgOffset = 0;
unsigned long tgLastPoll = 0;
bool tgReady = false;

enum TgText { T_HOT, T_COLD, T_HUMID, T_DRY, T_TANK, T_LOCK, T_DHT, T_ST, T_FROST,
              T_PAIRED, T_NOTALLOWED, T_HELP, T_WATER, T_DEVSET, T_MODE, T_TEST, T_SUMMARY,
              T_ON, T_OFF, T_AIR, T_HUM, T_SOIL, T_TANKL, T_MODEL, T_COUNT };

const char* TG_EN[] = {
  "Too hot: %.1f°C", "Too cold: %.1f°C", "Humidity is high: %.0f%%", "Soil is dry: %.0f%%",
  "The water tank is empty", "The pump is resting after a long run", "The air sensor is not responding",
  "The soil temperature sensor is not responding", "Frost is possible tonight: %.0f°C",
  "Connected. Alerts from %s will arrive in this chat.", "This greenhouse is linked to another chat.",
  "Commands:\n/status\n/water 10\n/fan on, /fan off\n/lights on, /heater on, /vent on\n/auto, /manual, /away",
  "Watering for %d s.", "%s %s for 1 hour, then automatic again.", "Mode: %s", "Test message from %s. Alerts will arrive here.",
  "Today in %s: %.1f to %.1f°C, %.1f L of water and %.2f kWh used.",
  "on", "off", "Air", "Humidity", "Soil", "Tank", "Mode"};

const char* TG_FA[] = {
  "خیلی گرم است: %.1f درجه", "خیلی سرد است: %.1f درجه", "رطوبت هوا زیاد است: %.0f٪", "خاک خشک است: %.0f٪",
  "ذخیرهٔ آب خالی است", "پمپ بعد از کار طولانی استراحت می‌کند", "سنسور هوا جواب نمی‌دهد",
  "سنسور حرارت خاک جواب نمی‌دهد", "امشب احتمال یخبندان است: %.0f درجه",
  "وصل شد. هشدارهای %s از این به بعد در این چت می‌آید.", "این گلخانه به یک چت دیگر وصل است.",
  "دستورها:\n/status وضعیت\n/water 10 آبدهی ۱۰ ثانیه\n/fan on یا /fan off\n/lights on، /heater on، /vent on\n/auto، /manual، /away",
  "آبدهی برای %d ثانیه.", "%s برای یک ساعت %s شد، بعد دوباره خودکار.", "حالت: %s", "پیام آزمایشی از %s. هشدارها اینجا می‌آید.",
  "امروز در %s: از %.1f تا %.1f درجه، %.1f لیتر آب و %.2f کیلووات‌ساعت برق مصرف شد.",
  "روشن", "خاموش", "هوا", "رطوبت", "خاک", "ذخیرهٔ آب", "حالت"};

const char* TG_PS[] = {
  "ډېره ګرمي ده: %.1f درجې", "ډېره یخني ده: %.1f درجې", "د هوا لندبل ډېر دی: %.0f٪", "خاوره وچه ده: %.0f٪",
  "د اوبو ټانکي تشه ده", "پمپ له اوږد کار وروسته دمه کوي", "د هوا سینسر ځواب نه ورکوي",
  "د خاورې د تودوخې سینسر ځواب نه ورکوي", "نن شپه د کنګل امکان شته: %.0f درجې",
  "وصل شو. له دې وروسته د %s خبرتیاوې په دې چټ کې راځي.", "دا شنه خونه له بل چټ سره تړلې ده.",
  "امرونه:\n/status حالت\n/water 10 لس ثانیې اوبه\n/fan on یا /fan off\n/lights on، /heater on، /vent on\n/auto، /manual، /away",
  "د %d ثانیو لپاره اوبه ورکول کیږي.", "%s د یو ساعت لپاره %s شو، بیا اتومات.", "حالت: %s", "د %s ازمایښتي پیغام. خبرتیاوې به دلته راځي.",
  "نن په %s کې: له %.1f تر %.1f درجو، %.1f لیتره اوبه او %.2f کیلوواټ ساعت برېښنا ولګېده.",
  "چالان", "بند", "هوا", "لندبل", "خاوره", "ټانکي", "حالت"};

const char* TG_DEV[3][5] = {
  {"Fan", "Pump", "Lights", "Heater", "Vent"},
  {"پکه", "پمپ", "چراغ‌ها", "بخاری", "دریچه"},
  {"پکه", "پمپ", "څراغونه", "بخارۍ", "کړکۍ"}};
const char* TG_MODE[3][3] = {
  {"automatic", "manual", "away"},
  {"خودکار", "دستی", "سفر"},
  {"اتومات", "لاسي", "سفر"}};

const char* tt(int id) { return cfg.lang == 1 ? TG_FA[id] : cfg.lang == 2 ? TG_PS[id] : TG_EN[id]; }

void tgQueueText(const String& s) {
  if (!tgToken.length()) return;
  TgMsg m;
  strncpy(m.text, s.c_str(), sizeof(m.text) - 1); m.text[sizeof(m.text) - 1] = 0;
  xQueueSend(tgQueue, &m, 0);
}

void tgAlert(int a, float x) {
  char b[200];
  snprintf(b, sizeof(b), tt(T_HOT + a), x);
  tgQueueText(ghName + ": " + b);
}

void tgSummary() {
  char b[300];
  snprintf(b, sizeof(b), tt(T_SUMMARY), ghName.c_str(), dayTMin < 90 ? dayTMin : temperature, dayTMax > -50 ? dayTMax : temperature, litersToday(), kwhToday());
  tgQueueText(b);
}

String tgStatus() {
  char b[360];
  int l = cfg.lang > 2 ? 0 : cfg.lang;
  String on = "";
  for (int d = 0; d < D_COUNT; d++) if (dev[d]) { if (on.length()) on += ", "; on += TG_DEV[l][d]; }
  snprintf(b, sizeof(b), "%s\n%s %.1f°C, %s %.0f%%\n%s %d%%, %s %d%%\n%s: %s\n%s: %s",
           ghName.c_str(), tt(T_AIR), temperature, tt(T_HUM), humidity, tt(T_SOIL), soil, tt(T_TANKL), water,
           tt(T_MODEL), TG_MODE[l][mode], tt(T_ON), on.length() ? on.c_str() : "-");
  return b;
}

bool tgSend(const String& tok, const String& chat, const String& text) {
  JsonDocument d; d["chat_id"] = chat; d["text"] = text;
  String body; serializeJson(d, body);
  if (!tgHttp.begin(tgTls, "https://api.telegram.org/bot" + tok + "/sendMessage")) return false;
  tgHttp.addHeader("Content-Type", "application/json");
  int c = tgHttp.POST(body);
  if (c > 0) tgHttp.getString();
  tgHttp.end();
  return c == 200;
}

void tgStop() { tgHttp.end(); tgTls.stop(); }

void tgCommand(const String& text, const String& tok, const String& chat) {
  String t = text; t.trim(); t.toLowerCase();
  int sp = t.indexOf(' ');
  String cmd = sp > 0 ? t.substring(0, sp) : t, arg = sp > 0 ? t.substring(sp + 1) : "";
  int at = cmd.indexOf('@'); if (at > 0) cmd = cmd.substring(0, at);   // /status@MyBot
  int l = cfg.lang > 2 ? 0 : cfg.lang;
  char b[200];
  CmdMsg m; m.by = BY_TG;
  if (cmd == "/status") { tgSend(tok, chat, tgStatus()); return; }
  if (cmd == "/water") {
    int s = arg.toInt(); if (s <= 0) s = 10;
    snprintf(m.json, sizeof(m.json), "{\"device\":\"water\",\"value\":%d}", s);
    xQueueSend(cmdQueue, &m, 0);
    snprintf(b, sizeof(b), tt(T_WATER), s); tgSend(tok, chat, b); return;
  }
  const char* devs[] = {"/fan", "/pump", "/lights", "/heater", "/vent"};
  for (int d = 0; d < D_COUNT; d++) {
    if (cmd != devs[d]) continue;
    bool on = arg != "off" && arg != "0";
    if (d == D_PUMP && on) snprintf(m.json, sizeof(m.json), "{\"device\":\"water\",\"value\":10}");
    else snprintf(m.json, sizeof(m.json), "{\"device\":\"%s\",\"value\":%s,\"mins\":60}", DEV_NAME[d], on ? "true" : "false");
    xQueueSend(cmdQueue, &m, 0);
    snprintf(b, sizeof(b), tt(T_DEVSET), TG_DEV[l][d], tt(on ? T_ON : T_OFF)); tgSend(tok, chat, b); return;
  }
  if (cmd == "/auto" || cmd == "/manual" || cmd == "/away") {
    int md = cmd == "/manual" ? M_MANUAL : cmd == "/away" ? M_AWAY : M_AUTO;
    snprintf(m.json, sizeof(m.json), "{\"device\":\"mode\",\"value\":\"%s\"}", MODE_NAME[md]);
    xQueueSend(cmdQueue, &m, 0);
    snprintf(b, sizeof(b), tt(T_MODE), TG_MODE[l][md]); tgSend(tok, chat, b); return;
  }
  tgSend(tok, chat, tt(T_HELP));
}

/* Called from the network task. */
void tgService() {
  xSemaphoreTake(lock, portMAX_DELAY);
  String tok = tgToken, chat = tgChat;
  xSemaphoreGive(lock);
  if (!tok.length()) { tgState = 0; if (tgReady) { tgStop(); tgReady = false; } return; }
  if (!tgReady) { tgTls.setInsecure(); tgTls.setHandshakeTimeout(12); tgHttp.setReuse(true); tgHttp.setTimeout(6000); tgReady = true; }

  TgMsg m;
  while (chat.length() && xQueueReceive(tgQueue, &m, 0) == pdTRUE) tgSend(tok, chat, m.text);
  if (tgTestRequest && chat.length()) {
    tgTestRequest = false;
    char b[200]; snprintf(b, sizeof(b), tt(T_TEST), ghName.c_str()); tgSend(tok, chat, b);
  }

  if (millis() - tgLastPoll < 3000) return;
  tgLastPoll = millis();
  if (!tgHttp.begin(tgTls, "https://api.telegram.org/bot" + tok + "/getUpdates?timeout=0&limit=5&offset=" + String(tgOffset))) return;
  int code = tgHttp.GET();
  String body = code == 200 ? tgHttp.getString() : "";
  if (code > 0 && code != 200) tgHttp.getString();
  tgHttp.end();
  tgState = code == 200 ? 1 : 2;
  if (code != 200) return;

  JsonDocument filter;
  filter["result"][0]["update_id"] = true;
  filter["result"][0]["message"]["chat"]["id"] = true;
  filter["result"][0]["message"]["text"] = true;
  JsonDocument doc;
  if (deserializeJson(doc, body, DeserializationOption::Filter(filter))) return;
  for (JsonObject u : doc["result"].as<JsonArray>()) {
    tgOffset = (long)(u["update_id"] | 0L) + 1;
    String from = u["message"]["chat"]["id"].as<String>();
    String text = u["message"]["text"] | "";
    if (!from.length() || from == "null" || !text.length()) continue;
    if (!chat.length() && text.startsWith("/start")) {
      chat = from;
      xSemaphoreTake(lock, portMAX_DELAY); tgChat = from; xSemaphoreGive(lock);
      tgChatSave = true;
      char b[200]; snprintf(b, sizeof(b), tt(T_PAIRED), ghName.c_str()); tgSend(tok, chat, b);
      continue;
    }
    if (from != chat) { tgSend(tok, from, tt(T_NOTALLOWED)); continue; }
    tgCommand(text, tok, chat);
  }
}
