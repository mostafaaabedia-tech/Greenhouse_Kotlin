# HBRT AGRO – Smart Greenhouse (native Kotlin)

Developed by **Mostafa Mesbah Aabedi** and **Milad Ahmadi**.

Native Android app (Kotlin + Jetpack Compose) for the HBRT ESP32 greenhouse, plus the ESP32 firmware.

## New in 5.0: control from anywhere, no tunnel
The board talks to a **Firebase Realtime Database** over plain HTTPS. No Cloudflare tunnel, no port
forwarding, no mixed-content problems.

- **Wi-Fi first, cloud automatically.** On the greenhouse Wi-Fi the app uses the fast local WebSocket.
  Leave the Wi-Fi (mobile data, school, home) and it switches to the cloud by itself, then back again.
- **Pairing is automatic.** Each board makes its own private key. The first time a phone connects on the
  same Wi-Fi it saves the key; from then on it works from any network.
- **Share with your team.** Settings → *Control from anywhere* → *Share access link*. The link opens the
  web dashboard (<https://mostafaaabedia-tech.github.io/HBRT-AGRO/>) or can be pasted into the app on
  another phone (*Add with a share link*).
- **Fast and light.** Commands arrive in about a second. The board only sends every 2 s while someone
  is watching and every 20 s otherwise, so the free Firebase plan is plenty.
- **7-day history in the cloud** (one point every 5 minutes). The app fills gaps in its own history with it.
- **Safe.** Commands older than 60 s are ignored (no surprise pump runs after an outage); all the 4.0
  safety rules (tank empty, pump max run, cool-down) still run on the board.
- **Real clock from the internet**, so the grow-light schedule keeps working after a power cut.

### Setup
1. Flash `firmware/hbrt_greenhouse/hbrt_greenhouse.ino` (v5.0). Set your Wi-Fi name/password at the top.
   `FIREBASE_HOST` already points at the HBRT database (same one as the air-quality project).
   Libraries: DHT sensor library, ESP32Servo, U8g2, OneWire, DallasTemperature, ArduinoJson, WebSockets
   (Markus Sattler). No Firebase library needed. Board: ESP32 Dev Module (package 2.x or 3.x).
2. Open the app on the greenhouse Wi-Fi and connect once. The OLED shows `WEB` when the cloud link works.
3. Recommended: in the Firebase console → Realtime Database → Rules, paste `firebase-rules.json`.
   It keeps the air-quality paths working and stops anyone from listing all greenhouses.

Database layout, all under one private key: `/hbrt/<key>/state` (live), `/in/cmd` (commands),
`/in/seen` (someone is watching), `/log` (7-day history).

## Build the APK with GitHub
1. Push to `main`.
2. Open the **Actions** tab, wait for "Build HBRT Kotlin App" to finish.
3. Download **HBRT-AGRO-Kotlin** from the run, unzip, and install `app-debug.apk`.

## Build on a PC
Open the folder in Android Studio (Koala or newer) and press Run.

## Features
- Finds greenhouses on your Wi-Fi or phone hotspot automatically; save several and switch between them
- Works from anywhere through the cloud (firmware 5.0), with automatic Wi-Fi ↔ cloud switching
- Name each greenhouse (stored on the ESP32)
- Plant health score, plain-language status, animated device icons, live water tank
- Crop presets (tomato, cucumber, pepper, lettuce, strawberry, herbs)
- Water now (5–60 s) and grow-light schedule
- Live dashboard: gauges, live charts, Wi-Fi signal, uptime, active alerts
- Controls: auto/manual, fan, pump, lights, heater, vent, "turn everything off"
- Stable connection: pings, dead-link watchdog, auto-reconnect with back-off, instant reconnect when the
  network returns, local IPs routed over Wi-Fi, follows the board to a new IP
- Background monitoring (foreground service): logging and alert notifications with the app closed
- History stored on the phone (SQLite, 6 months), ranges 1 h / 24 h / 7 d / 30 d / all
- Export CSV and PDF (summary, chart, table), share or save anywhere
- Optional daily PDF report saved to Downloads/HBRT at 20:00
- Edit the greenhouse limits from the app (saved on the ESP32)
- Local AI (Ollama): ask, diagnose, predict harvest
