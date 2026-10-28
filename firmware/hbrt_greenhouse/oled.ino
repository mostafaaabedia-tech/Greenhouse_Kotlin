/* ================= OLED (SH1106 128x64) ================= */

void oledMessage(const char* a, const char* b, const char* c) {
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x12_tr);
  u8g2.drawStr(0, 12, a);
  u8g2.drawLine(0, 16, 128, 16);
  u8g2.drawStr(0, 34, b);
  u8g2.drawStr(0, 50, c);
  u8g2.sendBuffer();
}

void updateOLED() {
  if (portalOn && WiFi.status() != WL_CONNECTED) {
    String ap = "HBRT-Setup-" + chipId;
    oledMessage("Wi-Fi setup: join", ap.c_str(), "then open 192.168.4.1");
    return;
  }
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x12_tr);
  bool wifi = WiFi.status() == WL_CONNECTED;
  String title = wifi ? ghName.substring(0, 15) : String("** NO WIFI **");
  u8g2.drawStr(0, 10, title.c_str());
  if (wifi && cloudOn()) u8g2.drawStr(104, 10, cloudOk ? (cloudFast ? "WEB*" : "WEB") : "LAN");
  u8g2.drawLine(0, 13, 128, 13);
  u8g2.setCursor(0, 27);   u8g2.print("T:");    u8g2.print(temperature, 1); u8g2.print("C");
  u8g2.setCursor(64, 27);  u8g2.print("H:");    u8g2.print(humidity, 0);    u8g2.print("%");
  u8g2.setCursor(0, 41);   u8g2.print("Soil:"); u8g2.print(soil);           u8g2.print("%");
  u8g2.setCursor(64, 41);  u8g2.print("Tank:"); u8g2.print(water);          u8g2.print("%");
  u8g2.setCursor(0, 55);   u8g2.print(mode == M_AUTO ? "AUTO" : mode == M_AWAY ? "AWAY" : "MAN");
  const char* L = "FPLHV";
  for (int d = 0; d < D_COUNT; d++) {
    int x = 40 + d * 17;
    if (dev[d]) { u8g2.drawBox(x - 1, 46, 11, 11); u8g2.setDrawColor(0); }
    char s[2] = {L[d], 0};
    u8g2.drawStr(x + 1, 55, s);
    u8g2.setDrawColor(1);
  }
  if (alertActive) u8g2.drawStr(122, 55, "!");
  u8g2.sendBuffer();
}
