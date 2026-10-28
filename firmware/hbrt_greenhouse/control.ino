/* ================= CONTROL =================
   Every 2 s (and right after a command):
   1. timed overrides that ran out go back to automatic
   2. watering times
   3. automatic decisions (climate, soil watering, grow lights, your rules), each with a reason
   4. overrides and manual mode win over automatic
   5. safety always has the last word (empty tank, pump running too long)                   */

Eff effLimits() {
  Eff e = {cfg.tempMax, cfg.tempMin, cfg.humMax, cfg.soilMin};
  if (mode == M_AWAY) { e.tMax += 3; e.tMin -= 3; e.hMax = min(95.0f, e.hMax + 10); e.sMin = max(10, e.sMin - 10); }
  return e;
}

bool ovrActive(int d) { return ovrUntil[d] != 0; }

void setDev(int d, bool v, uint8_t by, uint8_t why, float x) {
  if (dev[d] == v) return;
  dev[d] = v;
  logEvent(E_DEV, d, by, why, v ? 1 : 0, x);
}

void startWatering(int sec, uint8_t by) {
  sec = constrain(sec, 1, cfg.pumpMaxSec);
  if (water <= cfg.waterMin) { logEvent(E_SKIP, D_PUMP, by, RS_TANK, 0, water); return; }
  if (pumpLocked())          { logEvent(E_SKIP, D_PUMP, by, RS_LOCK, 0, 0); return; }
  waterUntil = millis() + (unsigned long)sec * 1000UL;
  if (waterUntil == 0) waterUntil = 1;
  waterBy = by;
  logEvent(E_WATER, D_PUMP, by, RS_NONE, 1, sec);
}

void stopWatering() { waterUntil = 0; autoWatering = false; }

/* Overrides whose time is up hand the device back to automatic. */
void overrideTimers() {
  for (int d = 0; d < D_COUNT; d++) {
    if (!ovrUntil[d] || ovrUntil[d] == OVR_FOREVER) continue;
    if ((long)(millis() - ovrUntil[d]) >= 0) {
      ovrUntil[d] = 0;
      logEvent(E_OVREND, d, BY_TIMER, RS_NONE, 0, 0);
    }
  }
}

void scheduleCheck() {
  int m = minutesNow(), wd = weekday();
  int32_t today = localDay();
  if (m < 0 || mode == M_MANUAL) return;
  for (int i = 0; i < nSched; i++) {
    Slot& s = sched[i];
    if (s.sec == 0 || m != s.min || schedFired[i] == today) continue;
    schedFired[i] = today;
    if (!(s.days & (1 << wd))) continue;
    if (mode == M_AWAY)                 { logEvent(E_SKIP, D_PUMP, BY_SCHED, RS_AWAY, 0, s.min); continue; }
    if (cfg.rainSkip && rainToday())    { logEvent(E_SKIP, D_PUMP, BY_SCHED, RS_RAIN, 0, s.min); continue; }
    startWatering(s.sec, BY_SCHED);
  }
}

bool rainToday() {
  bool r = false;
  xSemaphoreTake(lock, portMAX_DELAY);
  if (wx.ok && utcNow() - wx.at < 6UL * 3600UL) r = wx.ps[0] >= 1.0f || wx.pp[0] >= 60;
  xSemaphoreGive(lock);
  return r;
}

float sensorValue(uint8_t s) {
  switch (s) {
    case 0: return temperature;
    case 1: return humidity;
    case 2: return soil;
    case 3: return soilTemp;
    default: return water;
  }
}

void control() {
  Eff e = effLimits();
  bool want[D_COUNT]; uint8_t why[D_COUNT]; float wx_[D_COUNT]; bool has[D_COUNT];
  for (int d = 0; d < D_COUNT; d++) { want[d] = dev[d]; why[d] = RS_NONE; wx_[d] = 0; has[d] = false; }

  // climate, with hysteresis so relays don't chatter
  if (!dhtOk) { climate = 0; for (int d : {D_FAN, D_VENT, D_HEATER}) { has[d] = true; want[d] = false; why[d] = RS_SENSOR; } }
  else {
    bool hot = temperature > e.tMax, humid = humidity > e.hMax, cold = temperature < e.tMin;
    bool okay = temperature < e.tMax - 2 && humidity < e.hMax - 5 && temperature > e.tMin + 2;
    uint8_t w = RS_NONE; float x = 0;
    if (hot || humid)      { climate = 1; w = hot ? RS_HOT : RS_HUMID; x = hot ? temperature : humidity; }
    else if (cold)         { climate = 2; w = RS_COLD; x = temperature; }
    else if (okay && climate) { climate = 0; w = RS_OK; x = temperature; }
    for (int d : {D_FAN, D_VENT, D_HEATER}) {
      has[d] = true; why[d] = w; wx_[d] = x;
      want[d] = (d == D_HEATER) ? climate == 2 : climate == 1;
    }
  }

  // soil watering
  if (!autoWatering && soil < e.sMin && water > cfg.waterMin && !pumpLocked()) { autoWatering = true; why[D_PUMP] = RS_DRY; wx_[D_PUMP] = soil; }
  else if (autoWatering && soil >= e.sMin + 10)  { autoWatering = false; why[D_PUMP] = RS_MOIST; wx_[D_PUMP] = soil; }
  else if (autoWatering && water <= cfg.waterMin) { autoWatering = false; why[D_PUMP] = RS_TANK; wx_[D_PUMP] = water; }
  has[D_PUMP] = true; want[D_PUMP] = autoWatering;

  // grow lights
  int m = minutesNow();
  if (mode == M_AWAY) { has[D_LIGHTS] = true; want[D_LIGHTS] = false; why[D_LIGHTS] = RS_AWAY; }
  else if (cfg.lightSched && m >= 0) {
    has[D_LIGHTS] = true;
    want[D_LIGHTS] = inWindow(m, cfg.lightOn, cfg.lightOff);
    why[D_LIGHTS] = want[D_LIGHTS] ? RS_LIGHTSON : RS_LIGHTSOFF;
  }

  // your rules: the first one that matches decides that device
  bool ruled[D_COUNT] = {false, false, false, false, false};
  for (int i = 0; i < nRules; i++) {
    Rule& r = rules[i];
    if (r.dev >= D_COUNT || ruled[r.dev]) continue;
    if ((r.sensor <= 1 && !dhtOk) || (r.sensor == 3 && !soilTempOk)) continue;   // never act on a dead sensor
    float v = sensorValue(r.sensor);
    bool match = r.op == 0 ? v < r.val : v > r.val;
    if (!match) continue;
    ruled[r.dev] = true; has[r.dev] = true; want[r.dev] = r.on; why[r.dev] = RS_RULE; wx_[r.dev] = i;
    if (r.dev == D_PUMP && r.on && (water <= cfg.waterMin || pumpLocked())) want[r.dev] = false;
  }

  // apply: watering sessions > overrides > manual mode > automatic
  for (int d = 0; d < D_COUNT; d++) {
    if (d == D_PUMP && waterUntil) {
      if ((long)(waterUntil - millis()) > 0) { setDev(d, true, waterBy, RS_NONE, 0); continue; }
      waterUntil = 0;
      setDev(d, false, waterBy, RS_DONE, 0);
      continue;
    }
    if (ovrActive(d)) { setDev(d, ovrVal[d], BY_USER, RS_MANUAL, 0); continue; }
    if (mode == M_MANUAL || !has[d]) continue;
    uint8_t by = ruled[d] ? BY_RULE : (mode == M_AWAY ? BY_AWAY : BY_AUTO);
    setDev(d, want[d], by, why[d], wx_[d]);
  }
}

void safety() {
  if (dev[D_PUMP]) {
    if (!pumpOnSince) pumpOnSince = millis();
    if (water <= cfg.waterMin) {
      stopWatering(); ovrUntil[D_PUMP] = 0;
      setDev(D_PUMP, false, BY_SAFETY, RS_TANK, water);
    } else if (millis() - pumpOnSince > (unsigned long)cfg.pumpMaxSec * 1000UL) {
      stopWatering(); ovrUntil[D_PUMP] = 0;
      pumpLockUntil = millis() + 5UL * 60UL * 1000UL;
      setDev(D_PUMP, false, BY_SAFETY, RS_MAXRUN, cfg.pumpMaxSec);
    }
  } else pumpOnSince = 0;
  if (pumpLocked() && dev[D_PUMP]) { stopWatering(); setDev(D_PUMP, false, BY_SAFETY, RS_LOCK, 0); }
}

/* One control pass: called every 2 s and right after every command. */
void tick() {
  overrideTimers();
  scheduleCheck();
  control();
  safety();
  applyOutputs();
}

/* ================= USAGE, DAYS, GROWTH ================= */
void trackUsage() {
  unsigned long now = millis();
  unsigned long dt = lastUsageTick ? now - lastUsageTick : 0;
  lastUsageTick = now;
  for (int d = 0; d < D_COUNT; d++) if (dev[d]) onMs[d] += dt;
  static unsigned long lastUsePub = 0;
  if (now - lastUsePub > 60000) { lastUsePub = now; dUse = vUse = true; buildUse(); }
}

float litersToday() { return onMs[D_PUMP] / 1000.0f * cfg.flowMl / 1000.0f; }
float kwhToday() { float wh = 0; for (int d = 0; d < D_COUNT; d++) wh += onMs[d] / 3600000.0f * cfg.watt[d]; return wh / 1000.0f; }

int stageOf(float g, int crop) {
  if (crop < 0 || crop >= N_CROPS) return -1;
  float f = g / CROPS[crop].gddHarvest;
  return f < 0.15f ? 0 : f < 0.45f ? 1 : f < 0.70f ? 2 : f < 1.0f ? 3 : 4;
}

/* Limits for a crop at a growth stage. */
void applyStageLimits(int crop, int stage) {
  const Crop& c = CROPS[crop];
  cfg.tempMin = c.tMin; cfg.tempMax = c.tMax; cfg.humMax = c.hMax; cfg.soilMin = c.sMin;
  if (stage == 0) { cfg.tempMin += 2; cfg.humMax = min(90.0f, cfg.humMax + 10); cfg.soilMin += 5; }
  if (stage == 2) cfg.humMax -= 5;
  if (stage == 3) cfg.soilMin += 5;
}

void stageCheck() {
  int st = stageOf(gdd, cfg.crop);
  if (st == lastStage) return;
  bool first = lastStage == -1;
  lastStage = st;
  if (first || st < 0) return;
  logEvent(E_STAGE, 255, BY_SYS, RS_STAGE, st, gdd);
  if (cfg.autoStage) { applyStageLimits(cfg.crop, st); saveConfig(); logEvent(E_CFG, 255, BY_SYS, RS_STAGE, st, 0); }
}

/* At local midnight: keep yesterday's usage and add its growing degree days. */
void dailyCheck() {
  int32_t today = localDay();
  if (!today) return;
  if (!curDay) { curDay = today; saveDays(); }
  if (today != curDay) {
    for (int i = 6; i > 0; i--) { dayL[i] = dayL[i - 1]; dayKwh[i] = dayKwh[i - 1]; dayNum[i] = dayNum[i - 1]; }
    dayL[0] = litersToday(); dayKwh[0] = kwhToday(); dayNum[0] = curDay;
    for (int d = 0; d < D_COUNT; d++) onMs[d] = 0;
    if (cfg.crop >= 0 && dayTMax > -50 && dayTMin < 90) gdd += max(0.0f, (dayTMax + dayTMin) / 2 - CROPS[cfg.crop].base);
    dayTMax = -99; dayTMin = 99;
    curDay = today;
    saveDays();
    dUse = vUse = true; buildUse();
  }
  stageCheck();
}

/* Planting date set: estimate the degree days so far from the current temperature. */
void setPlanting(int crop, int32_t day) {
  cfg.crop = crop; cfg.plantDay = day;
  int32_t today = localDay();
  float base = crop >= 0 ? CROPS[crop].base : 10;
  gdd = (crop >= 0 && day > 0 && today > day) ? (today - day) * max(0.0f, (dhtOk ? temperature : 20) - base) : 0;
  lastStage = -1;
  saveDays();
}
