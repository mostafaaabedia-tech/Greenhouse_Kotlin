# HBRT AGRO – Smart Greenhouse

Developed by **Mostafa Mesbah Aabedi** and **Milad Ahmadi** (HBRT, Herat Boys Robotic Team).

Native Android app (Kotlin + Jetpack Compose) and ESP32 firmware for the HBRT smart greenhouse.
The web dashboard lives in [HBRT-AGRO](https://github.com/mostafaaabedia-tech/HBRT-AGRO)
(<https://mostafaaabedia-tech.github.io/HBRT-AGRO/>).

## New in 6.0
**A new look.** The "Night Garden" design: a living plant inside glowing rings (air, humidity, soil, tank)
under a sky that follows the time of day and the real weather. It droops when the soil is dry, flowers and
fruits as it grows, and drinks while the pump runs. Light and dark themes, colourful device buttons,
new icons and animations. English, Dari and Pashto, right-to-left where needed.

**Control**
- Modes: **Automatic**, **Manual** and **Away** (saves water and power while you're gone, plants stay safe).
- Tap a device in automatic mode to keep it on or off for 15 min, 30 min, 1 hour or until you change it.
  The board then goes back to automatic by itself. "Return now" undoes it.
- **Hold to water**: the longer you hold, the longer it waters. Quick 5–60 s buttons too.
- **Watering times** (up to 4 a day, per weekday), skipped automatically when rain is forecast.
- **Your own rules**: "if soil below 35 % then pump on", up to 6.
- **All off** with confirmation.

**Knowing what happens**
- **Timeline**: every switch with the reason ("Fan on · Automatic: air reached 31 °C").
- **Weather** for your location (Open-Meteo, no key needed), frost warning, rain skip.
- **Growth**: pick the crop and planting date. Growing-degree-days give the stage and a harvest date;
  limits can follow the stage automatically.
- **Water and power** used per day, and how many days the tank lasts.
- **Air dryness (VPD)** and **dew point**, range bars that show where each value sits.
- **History**: 1 h to all, compare with yesterday, hour-by-day heatmap, CSV/PDF export, alert history.
- **Journal** shared by the team: notes, photos and harvests (grams), stored in the cloud.

**Alerts**
- **Telegram bot**: alerts and commands (`/status`, `/water 10`, `/away`, `/auto`…) even with the app closed.
- Pick which problems alert you, quiet hours at night (empty tank and frost still come through),
  daily summary at 20:00.
- Phone notifications from the background monitor, and a **home-screen widget** with a water button.

**Board**
- **Firmware update from the app or website** (downloads the latest GitHub release), or upload a `.bin`
  over Wi-Fi.
- **Wi-Fi setup without re-flashing**: hold BOOT for 3 s (or the board can't find its Wi-Fi for 60 s)
  and it opens the hotspot `HBRT-Setup-xxxx`. Join it and pick your network.
- **Sensor calibration** from the app (dry/wet soil, empty/full tank).
- Board health: signal, memory, restart reason, Wi-Fi drops, cloud errors.
- **View-only link** for parents, teachers or judges, and an exhibition mode with QR code on the website.

The camera and AI-camera ideas were left out on purpose. The AI assistant in the app still talks to
your own Ollama server on the local network.

## Setup
1. **Flash the firmware** `firmware/hbrt_greenhouse/` (open `hbrt_greenhouse.ino`; the other `.ino`
   files are tabs of the same sketch).
   - Board: *ESP32 Dev Module*, Arduino core 3.x. Partition scheme: **Minimal SPIFFS (1.9 MB APP with OTA)**
     so wireless updates have room. (The default scheme also fits today.)
   - Libraries: ArduinoJson 7, WebSockets (Markus Sattler), DHT sensor library, OneWire,
     DallasTemperature, ESP32Servo, U8g2.
   - Wi-Fi: change the name/password at the top, or leave them and use the setup hotspot after flashing.
   - After this first flash, later versions install from the app: *Settings → Board → Update firmware*.
2. **Open the app** on the greenhouse Wi-Fi and connect once. From then on it works from anywhere.
3. **Firebase rules** (recommended): paste `firebase-rules.json` in the Firebase console →
   Realtime Database → Rules.
4. **Telegram** (optional): in Telegram open @BotFather, send `/newbot`, copy the token, paste it in
   *Settings → Telegram*, then press Start in your new bot.

Database layout under each private key `/hbrt/<key>/`: `live/{s,cfg,wx,use,ev}` (state, settings,
weather, usage, timeline), `in/cmd` and `in/seen` (commands, someone watching), `log` (7-day history),
`journal` and `photos`. A view-only copy lives under its own `gv…` key.

## Get the APK and firmware
Every push to `main` builds both on GitHub Actions and publishes them as the **latest release**:
`HBRT-AGRO.apk` and `hbrt_greenhouse.bin`. The board's update button downloads that `.bin`.

## Build on a PC
Open the folder in Android Studio (Koala or newer) and press Run.

UI text for the app and website comes from one file: edit `tools/i18n/strings.py`, then run
`python3 tools/i18n/build.py` (writes `app/src/main/res/values*/strings.xml` and the website's `i18n.js`).

## Licences
Fonts in `app/src/main/res/font`: Space Grotesk, Oswald and Vazirmatn, all under the SIL Open Font
License 1.1 (from the google/fonts repository).
