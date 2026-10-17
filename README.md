# HBRT AGRO – Smart Greenhouse (native Kotlin)

Developed by **Mostafa Mesbah Aabedi** and **Milad Ahmadi**.

Native Android app (Kotlin + Jetpack Compose) for the HBRT ESP32 greenhouse.
Works with the existing `hbrt_greenhouse.ino` firmware (WebSocket on port 81).

## Build the APK with GitHub
1. Push this folder to the root of a GitHub repository (branch `main`).
2. Open the **Actions** tab, wait for "Build HBRT Kotlin App" to finish.
3. Download **HBRT-AGRO-Kotlin** from the run, unzip, and install `app-debug.apk`.

## Build on a PC
Open the folder in Android Studio (Koala or newer) and press Run.

## Features
- Live dashboard: gauges, live charts, Wi-Fi signal, uptime, active alerts
- Controls: auto/manual, fan, pump, lights, heater, vent, "turn everything off"
- Stable connection: ping every 5 s, dead-link watchdog, auto-reconnect with back-off,
  instant reconnect when Wi-Fi returns, local IPs always routed over Wi-Fi
- Background monitoring (foreground service): logging and alert notifications with the app closed
- History stored on the phone (SQLite, 6 months), ranges 1 h / 24 h / 7 d / 30 d / all
- Export CSV and PDF (summary, chart, table), share or save anywhere
- Optional daily PDF report saved to Downloads/HBRT at 20:00
- Edit the greenhouse limits from the app (saved on the ESP32)
- Local AI (Ollama): ask, diagnose, predict harvest
