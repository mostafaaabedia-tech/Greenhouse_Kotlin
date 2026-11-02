// Runs the real greenhouse firmware on the PC and checks every control path.
#include "fw.cpp"

static int passed = 0, failed = 0;
#define CHECK(cond, msg) do { if (cond) { passed++; printf("  PASS  %s\n", msg); } else { failed++; printf("  FAIL  %s   (line %d)\n", msg, __LINE__); } } while (0)

static const int PIN[] = {FAN_RELAY, PUMP_RELAY, LIGHTS_RELAY, HEATER_RELAY};
static bool relay(int d) { return d == D_VENT ? simServoAngle == 180 : simPinLevel[PIN[d]] == RELAY_ON; }

// a command exactly as the phone sends it over Wi-Fi
static void cmd(const char* json) {
  String s(json);
  webSocketEvent(0, WStype_TEXT, (uint8_t*)s.c_str(), s.length());
}
// a command arriving through the cloud queue (same path the internet uses)
static void cloudCmd(const char* json) {
  CmdMsg m; m.by = BY_USER; strncpy(m.json, json, sizeof(m.json)); xQueueSend(cmdQueue, &m, 0);
}
// let the board run for ms of board time (fast-forwarded)
static void run(long ms) {
  long steps = ms / 500;
  for (long i = 0; i < steps; i++) { simOffsetMs += 500; loop(); }
}
static JsonDocument state() { JsonDocument d; deserializeJson(d, jsS); return d; }
static void setTank(int pct) { simAnalog[WATER_PIN] = 4095 * pct / 100; }
static void setSoil(int pct) { simAnalog[SOIL_PIN] = 4095 - 4095 * pct / 100; }

int main() {
  setvbuf(stdout, nullptr, _IONBF, 0);
  Serial.quiet = true;
  simTemp = 24; simHum = 50;
  setTank(0);      // tank sensor reads empty, like on the real board
  setSoil(5);      // dry soil
  simAnalog[SETUP_PIN] = 1; simPinLevel[SETUP_PIN] = 1;

  printf("\n== 1. Power-up ==\n");
  setup();
  CHECK(!relay(D_FAN) && !relay(D_PUMP) && !relay(D_LIGHTS) && !relay(D_HEATER), "all relays OFF after power-up");
  CHECK(simPinLevel[PUMP_RELAY] == HIGH, "pump pin is HIGH (= off for an active-LOW relay)");
  run(6000);
  CHECK(mode == M_AUTO, "starts in automatic");
  CHECK(!relay(D_PUMP), "automatic: dry soil but empty tank -> pump stays OFF (protects the pump)");

  printf("\n== 2. Manual mode from the app ==\n");
  cmd("{\"device\":\"mode\",\"value\":\"manual\"}");
  CHECK(mode == M_MANUAL, "mode is manual");
  cmd("{\"device\":\"pump\",\"value\":true,\"mins\":0}");
  CHECK(relay(D_PUMP), "manual: pump turns ON even with an empty tank");
  CHECK(state()["pump"] == true, "state sent to the app says pump ON");
  run(5 * 60000);
  CHECK(relay(D_PUMP), "manual: pump still ON after 5 minutes (no automatic stop)");
  cmd("{\"device\":\"pump\",\"value\":false,\"mins\":0}");
  CHECK(!relay(D_PUMP), "manual: pump turns OFF");
  run(10000);
  CHECK(!relay(D_PUMP), "manual: pump stays OFF");
  for (int i = 0; i < 20; i++) {
    cmd(i % 2 ? "{\"device\":\"pump\",\"value\":false,\"mins\":0}" : "{\"device\":\"pump\",\"value\":true,\"mins\":0}");
    run(1000);
    if (relay(D_PUMP) != !(i % 2)) { CHECK(false, "manual: pump follows 20 quick taps"); break; }
    if (i == 19) CHECK(true, "manual: pump follows 20 quick taps");
  }

  printf("\n== 3. Manual: nothing switches by itself ==\n");
  simTemp = 40; simHum = 90;   // very hot and humid: automatic would react
  cmd("{\"device\":\"lights\",\"value\":true,\"mins\":0}");
  cmd("{\"device\":\"fan\",\"value\":false,\"mins\":0}");
  cmd("{\"device\":\"heater\",\"value\":true,\"mins\":0}");
  run(30 * 60000);
  CHECK(relay(D_LIGHTS), "lights stay ON for 30 min");
  CHECK(!relay(D_FAN), "fan stays OFF for 30 min although it is 40 C");
  CHECK(relay(D_HEATER), "heater stays ON for 30 min");
  simTemp = 24; simHum = 50;

  printf("\n== 4. Old bug: an override from automatic must not fight manual mode ==\n");
  cmd("{\"device\":\"mode\",\"value\":\"auto\"}");
  cmd("{\"device\":\"lights\",\"value\":false,\"mins\":-1}");     // auto: keep lights OFF until changed
  cmd("{\"device\":\"pump\",\"value\":false,\"mins\":-1}");
  cmd("{\"device\":\"mode\",\"value\":\"manual\"}");
  cmd("{\"device\":\"lights\",\"value\":true,\"mins\":0}");
  cmd("{\"device\":\"pump\",\"value\":true,\"mins\":0}");
  run(20000);
  CHECK(relay(D_LIGHTS), "manual: lights stay ON (old override ignored)");
  CHECK(relay(D_PUMP), "manual: pump stays ON (old override ignored)");
  CHECK(state()["ovr"].size() == 0, "no overrides listed in manual mode");

  printf("\n== 5. Same commands through the cloud path ==\n");
  cloudCmd("{\"device\":\"lights\",\"value\":false,\"mins\":0}");
  cloudCmd("{\"device\":\"pump\",\"value\":false,\"mins\":0}");
  uint32_t before = cmdDone;
  loop();
  CHECK(cmdDone - before == 2, "main loop applied both cloud commands at once");
  CHECK(!relay(D_LIGHTS) && !relay(D_PUMP), "lights and pump OFF");

  printf("\n== 6. Hold-to-water in manual with an empty tank ==\n");
  cmd("{\"device\":\"water\",\"value\":10}");
  run(2000);
  CHECK(relay(D_PUMP), "watering starts");
  run(12000);
  CHECK(!relay(D_PUMP), "watering stops by itself after 10 s");

  printf("\n== 7. Automatic watering with a full tank ==\n");
  cmd("{\"device\":\"mode\",\"value\":\"auto\"}");
  setTank(80); setSoil(5);
  run(4000);
  CHECK(relay(D_PUMP), "dry soil + water in tank -> pump ON");
  setSoil(60);
  run(4000);
  CHECK(!relay(D_PUMP), "soil moist again -> pump OFF");
  setSoil(5);
  run(4000);
  CHECK(relay(D_PUMP), "dry again -> pump ON");
  run(70000);
  CHECK(!relay(D_PUMP), "pump stops after its 60 s maximum run");
  CHECK(state()["pumpLock"] == true, "pump rests after a long run");
  run(3 * 60000);
  CHECK(!relay(D_PUMP), "still resting 3 min later");
  run(2 * 60000 + 30000);
  CHECK(relay(D_PUMP), "after the 5 min rest it waters again");
  setTank(0);
  run(4000);
  CHECK(!relay(D_PUMP), "tank runs empty -> pump OFF straight away");
  setSoil(60);

  printf("\n== 8. Automatic climate ==\n");
  simTemp = 38; run(4000);
  CHECK(relay(D_FAN) && relay(D_VENT) && !relay(D_HEATER), "38 C -> fan ON, vent open");
  simTemp = 25; run(4000);
  CHECK(!relay(D_FAN) && !relay(D_VENT), "25 C -> fan OFF, vent closed");
  simTemp = 10; run(4000);
  CHECK(relay(D_HEATER) && !relay(D_FAN), "10 C -> heater ON");
  simTemp = 22; run(4000);
  CHECK(!relay(D_HEATER), "22 C -> heater OFF");
  simTemp = NAN; simHum = NAN; run(14000);
  CHECK(!relay(D_FAN) && !relay(D_HEATER), "air sensor unplugged -> fan and heater OFF (safe)");
  simTemp = 24; simHum = 50; run(4000);

  printf("\n== 9. Timed override in automatic ==\n");
  cmd("{\"device\":\"lights\",\"value\":true,\"mins\":15}");
  CHECK(relay(D_LIGHTS), "lights ON for 15 min");
  run(14 * 60000);
  CHECK(relay(D_LIGHTS), "still ON after 14 min");
  run(2 * 60000);
  CHECK(!relay(D_LIGHTS) || state()["ovr"]["lights"].isNull(), "after 15 min it is back to automatic");
  cmd("{\"device\":\"fan\",\"value\":true,\"mins\":-1}");
  run(60 * 60000);
  CHECK(relay(D_FAN), "'until I change it' keeps the fan ON for an hour");
  cmd("{\"device\":\"ovr_clear\",\"value\":\"fan\"}");
  run(4000);
  CHECK(!relay(D_FAN), "'Return now' gives the fan back to automatic (OFF at 24 C)");

  printf("\n== 10. Away mode ==\n");
  cmd("{\"device\":\"mode\",\"value\":\"away\"}");
  simTemp = 32; run(4000);
  CHECK(!relay(D_FAN), "away: 32 C is still fine (limit +3 C)");
  simTemp = 40; run(4000);
  CHECK(relay(D_FAN), "away: 40 C -> fan ON");
  simTemp = 24; run(6000);

  printf("\n== 11. All off ==\n");
  cmd("{\"device\":\"lights\",\"value\":true,\"mins\":-1}");
  cmd("{\"device\":\"alloff\"}");
  CHECK(mode == M_MANUAL && !relay(D_FAN) && !relay(D_PUMP) && !relay(D_LIGHTS) && !relay(D_HEATER) && !relay(D_VENT), "everything OFF and manual");
  run(30000);
  CHECK(!relay(D_LIGHTS), "stays OFF");

  printf("\n== 12. Old apps (no mins) ==\n");
  cmd("{\"device\":\"mode\",\"value\":\"auto\"}");
  cmd("{\"device\":\"fan\",\"value\":true}");
  CHECK(mode == M_MANUAL && relay(D_FAN), "old app: switching a device = manual + ON");
  cmd("{\"device\":\"fan\",\"value\":false}");

  printf("\n== 13. Saved across a restart ==\n");
  CHECK(simNvs.count("mode") && simNvs["mode"][0] == M_MANUAL, "manual mode is saved");

  printf("\n%d passed, %d failed\n", passed, failed);
  return failed ? 1 : 0;
}
