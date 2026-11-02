// The firmware with its cloud task, against the real Firebase database. Prints relay changes.
#include "fw.cpp"
static long long wall() { return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count(); }
int main(int argc, char** argv) {
  setvbuf(stdout, nullptr, _IONBF, 0);
  Serial.quiet = true; simTemp = 24; simHum = 50;
  simAnalog[WATER_PIN] = 0; simAnalog[SOIL_PIN] = 3900; simPinLevel[SETUP_PIN] = 1;
  std::string k = argv[1];
  simNvs["ckey"] = std::vector<uint8_t>(k.begin(), k.end());
  simRunTasks = true;
  setup();
  printf("READY %s\n", cloudKey.c_str());
  const int pins[] = {FAN_RELAY, PUMP_RELAY, LIGHTS_RELAY, HEATER_RELAY};
  int last[4]; for (int i = 0; i < 4; i++) last[i] = simPinLevel[pins[i]];
  long long until = wall() + atol(argv[2]) * 1000;
  while (wall() < until) {
    loop();
    for (int i = 0; i < 4; i++) if (simPinLevel[pins[i]] != last[i]) {
      last[i] = simPinLevel[pins[i]];
      printf("PIN %s %d %lld\n", DEV_NAME[i], last[i] == RELAY_ON, wall());
    }
    delay(5);
  }
  printf("HTTP %d requests, avg %lld ms\n", simHttpCount.load(), simHttpCount ? simHttpMs.load() / simHttpCount.load() : 0);
  std::_Exit(0);
}
