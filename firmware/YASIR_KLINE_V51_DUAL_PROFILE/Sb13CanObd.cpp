#include "Sb13CanObd.h"
#include <eXoCAN.h>
#include <string.h>

// Generic emissions OBD-II over ISO 15765-4, 11-bit CAN, 500 kbit/s.
// This is not an Ezam/SB13 proprietary diagnostic implementation.
namespace Sb13CanObd {
static eXoCAN canBus(STD_ID_LEN, BR500K, PORTA_11_12_XCVR);
static bool initialized = false;
static bool connected = false;
static bool liveEnabled = false;
static uint32_t lastLive = 0;
static const uint16_t FUNCTIONAL_ID = 0x7DF;
static const uint16_t FIRST_RESPONSE_ID = 0x7E8;
static uint8_t lastPid = 0;

static void status(const char *message) {
  Serial2.print(F("{\"type\":\"status\",\"profile\":\"sb13_can_obd\",\"message\":\""));
  Serial2.print(message);
  Serial2.println(F("\"}"));
}
static void sendFrame(uint16_t id, const uint8_t *data, uint8_t len) {
  canBus.transmit(id, data, len);
}
static void request(uint8_t service, uint8_t pid, bool hasPid) {
  uint8_t d[8] = {0x02, service, pid, 0, 0, 0, 0, 0};
  if (!hasPid) { d[0] = 0x01; d[2] = 0; }
  sendFrame(FUNCTIONAL_ID, d, 8);
}
static bool response(uint8_t expectedService, uint8_t *out, uint16_t &outLen,
                     uint32_t timeoutMs = 900) {
  uint32_t start = millis();
  outLen = 0;
  uint8_t expectedSeq = 1;
  int responseId = -1;
  while ((uint32_t)(millis() - start) < timeoutMs) {
    volatile int id = 0, filter = 0;
    uint8_t d[8] = {0};
    int n = canBus.receive(id, filter, d);
    if (n <= 0 || id < FIRST_RESPONSE_ID || id > 0x7EF) continue;
    uint8_t frameType = d[0] >> 4;
    if (frameType == 0) {
      uint8_t len = d[0] & 0x0F;
      if (len < 1 || d[1] != expectedService) continue;
      outLen = len - 1;
      if (outLen > 7) outLen = 7;
      memcpy(out, d + 2, outLen);
      return true;
    }
    if (frameType == 1) {
      responseId = id;
      outLen = ((uint16_t)(d[0] & 0x0F) << 8) | d[1];
      if (outLen < 2 || outLen > 128 || d[2] != expectedService) {
        outLen = 0; responseId = -1; continue;
      }
      uint8_t fc[8] = {0x30, 0x00, 0x00, 0, 0, 0, 0, 0};
      sendFrame((uint16_t)(responseId - 8), fc, 8);
      uint16_t got = 0;
      memcpy(out, d + 3, 5); got = 5;
      while (got < outLen && (uint32_t)(millis() - start) < timeoutMs + 500) {
        volatile int cid = 0, cf = 0; uint8_t c[8] = {0};
        int cn = canBus.receive(cid, cf, c);
        if (cn <= 0 || cid != responseId || (c[0] >> 4) != 2) continue;
        uint8_t seq = c[0] & 0x0F;
        if (seq != expectedSeq) { outLen = 0; return false; }
        expectedSeq = (expectedSeq + 1) & 0x0F;
        for (uint8_t k = 1; k < 8 && got < outLen; ++k) out[got++] = c[k];
      }
      return got == outLen;
    }
  }
  outLen = 0;
  return false;
}
static bool readService(uint8_t service, uint8_t pid, bool hasPid,
                       uint8_t *data, uint16_t &length) {
  request(service, pid, hasPid);
  return response((uint8_t)(service + 0x40), data, length);
}
static void probe() {
  uint8_t data[128]; uint16_t len = 0;
  status("CAN_PROBING");
  if (readService(0x01, 0x00, true, data, len)) {
    connected = true; liveEnabled = true; lastLive = millis();
    status("OBD_CAN_CONNECTED");
  } else {
    connected = false; liveEnabled = false;
    status("OBD_CAN_NO_RESPONSE");
  }
}
static void readDtc() {
  uint8_t data[128]; uint16_t len = 0;
  if (!readService(0x03, 0, false, data, len)) {
    status("DTC_READ_FAILED"); return;
  }
  uint8_t count = (uint8_t)(len / 2);
  Serial2.print(F("{\"type\":\"dtc\",\"profile\":\"sb13_can_obd\",\"count\":"));
  Serial2.print(count);
  Serial2.print(F(",\"codes\":["));
  for (uint8_t i = 0; i < count; ++i) {
    if (i) Serial2.print(',');
    Serial2.print(F("{\"code\":\""));
    if (data[i * 2] < 16) Serial2.print('0');
    Serial2.print(data[i * 2], HEX);
    Serial2.print(' ');
    if (data[i * 2 + 1] < 16) Serial2.print('0');
    Serial2.print(data[i * 2 + 1], HEX);
    Serial2.print(F("\",\"status\":\"ذخیره‌شده\"}"));
  }
  Serial2.println(F("]}"));
  status(count ? "DTC_READ_OK" : "DTC_READ_OK_NO_CODES");
}
static void clearDtc() {
  uint8_t data[128]; uint16_t len = 0;
  if (readService(0x04, 0, false, data, len)) status("DTC_CLEAR_OK");
  else status("DTC_CLEAR_FAILED");
}
static void emitPid(uint8_t pid) {
  uint8_t data[128]; uint16_t len = 0;
  if (!readService(0x01, pid, true, data, len) || len < 1) return;
  uint8_t a = data[0], b = len > 1 ? data[1] : 0;
  const char *name = "OBD-II";
  const char *unit = "";
  float value = 0;
  switch (pid) {
    case 0x0C: name = "دور موتور"; unit = "rpm"; if (len < 2) return; value = ((a * 256.0f) + b) / 4.0f; break;
    case 0x05: name = "دمای آب"; unit = "°C"; value = (int)a - 40; break;
    case 0x0F: name = "دمای هوای ورودی"; unit = "°C"; value = (int)a - 40; break;
    case 0x0D: name = "سرعت خودرو"; unit = "km/h"; value = a; break;
    case 0x0B: name = "فشار منیفولد"; unit = "kPa"; value = a; break;
    case 0x11: name = "دریچه گاز"; unit = "%"; value = a * 100.0f / 255.0f; break;
    case 0x42: name = "ولتاژ ECU"; unit = "V"; if (len < 2) return; value = (a * 256.0f + b) / 1000.0f; break;
    default: return;
  }
  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"sb13_can_obd\",\"parameters\":[{\"id\":\"sb13_can_"));
  Serial2.print(pid, HEX);
  Serial2.print(F("\",\"name\":\"")); Serial2.print(name);
  Serial2.print(F("\",\"value\":")); Serial2.print(value, 1);
  Serial2.print(F(",\"unit\":\"")); Serial2.print(unit);
  Serial2.println(F("\",\"status\":\"تازه\"}]}"));
}
void initialize() {
  canBus.begin(STD_ID_LEN, BR500K, PORTA_11_12_XCVR);
  canBus.setAutoTxRetry(true);
  canBus.filterMask32Init(0, 0x7E8, 0x7F8);
  initialized = true;
}
void handleKey(char key) {
  if (!initialized) initialize();
  switch (key) {
    case '1': probe(); break;
    case '2': readDtc(); break;
    case '3': clearDtc(); break;
    case '6':
      liveEnabled = true; lastPid = 0; lastLive = millis() - 500;
      status("LIVE_REQUESTED"); break;
    case '0':
      liveEnabled = false; connected = false;
      status("OBD_CAN_DISCONNECTED"); break;
    default: break;
  }
}
void poll() {
  if (!initialized || !connected || !liveEnabled ||
      (uint32_t)(millis() - lastLive) < 2500UL) return;
  lastLive = millis();
  static const uint8_t pids[] = {0x0C, 0x05, 0x0F, 0x0D, 0x0B, 0x11, 0x42};
  emitPid(pids[lastPid]);
  lastPid = (lastPid + 1) % (sizeof(pids) / sizeof(pids[0]));
}
}
