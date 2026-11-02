#!/bin/bash
# Builds the PC simulator: the real firmware with stand-ins for the ESP32 hardware and libraries.
#   ./build.sh            build
#   ./tests               45 checks of every control path (manual, automatic, away, overrides, watering, safety)
#   python3 e2e.py        the simulated board against the real Firebase, commands sent like the app does
#   python3 web_e2e.py    the website in a browser (needs: python3 -m http.server 8765 in the HBRT-AGRO folder)
# Needs arduino-cli with the esp32 core 3.x and the firmware's libraries installed, g++ and curl.
set -e
cd "$(dirname "$0")"
FW=../../firmware/hbrt_greenhouse
CORE=$(ls -d ~/.arduino15/packages/esp32/hardware/esp32/*/cores/esp32 | tail -1)
JSON=$(ls -d ~/Arduino/libraries/ArduinoJson/src)
mkdir -p build
cp "$CORE/WString.cpp" "$CORE/WString.h" "$CORE/stdlib_noniso.c" "$CORE/stdlib_noniso.h" build/
arduino-cli compile --fqbn esp32:esp32:esp32 --preprocess "$FW" > build/fw_pre.cpp
python3 - <<'PY'
import re
s = open('build/fw_pre.cpp').read()
s = re.sub(r'^#line .*$', '', s, flags=re.M)
s = re.sub(r'^#include <(WiFi|WiFiClientSecure|HTTPClient|HTTPUpdate|Update|WebServer|DNSServer|WebSocketsServer|DHT|ESP32Servo|Wire|U8g2lib|OneWire|DallasTemperature|Preferences|esp_system|Arduino)\.h>$', '', s, flags=re.M)
open('build/fw.cpp', 'w').write('#include "libs.h"\n' + s)
PY
I="-Imock -Ibuild -I$JSON"
gcc -c -w $I build/stdlib_noniso.c -o build/noniso.o
gcc -c -w mock/itoa.c -o build/itoa.o
g++ -std=gnu++17 -O1 -w $I -c mock/sim_env.cpp -o build/env.o
g++ -std=gnu++17 -O1 -w $I -c build/WString.cpp -o build/wstring.o
OBJ="build/env.o build/wstring.o build/noniso.o build/itoa.o -lpthread"
g++ -std=gnu++17 -O1 -w $I -o tests tests.cpp $OBJ
g++ -std=gnu++17 -O1 -w $I -o cloudsim cloudsim.cpp $OBJ
echo "built: ./tests and ./cloudsim"
