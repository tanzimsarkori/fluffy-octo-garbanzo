#include "Sb13CanObd.h"
#include <eXoCAN.h>
#include <string.h>

// Generic ISO 15765-4 OBD-II CAN only. No Ezam/SB13 proprietary services.
namespace Sb13CanObd {
static const uint32_t REQUEST_ID_11 = 0x7DF;
static const uint32_t RESPONSE_MIN_11 = 0x7E8;
static const uint32_t RESPONSE_MAX_11 = 0x7EF;
static const uint32_t RX_MASK_11 = 0x7F8;
static const uint32_t REQUEST_ID_29 = 0x18DB33F1UL;
static const uint32_t RESPONSE_BASE_29 = 0x18DAF100UL;
static const uint32_t RESPONSE_MASK_29 = 0x1FFFFF00UL;
static const uint32_t RESPONSE_TIMEOUT_MS = 900;
static const uint32_t LIVE_PERIOD_MS = 350;

static eXoCAN canBus(STD_ID_LEN, BR500K, PORTA_11_12_XCVR);
static bool initialized = false;
static bool connected = false;
static bool liveEnabled = false;
static bool havePids01 = false;
static bool havePids40 = false;
static uint32_t pids01 = 0;
static uint32_t pids40 = 0;
static uint32_t lastLive = 0;
static uint8_t nextPid = 0;
static bool extendedIds = false;

static void sendStatus(const char *message) {
  Serial2.print(F("{\"type\":\"status\",\"profile\":\"sb13_can_obd\",\"message\":\""));
  Serial2.print(message);
  Serial2.println(F("\"}"));
}
static void configureCan(uint8_t bitRate, bool useExtendedIds) {
  extendedIds = useExtendedIds;
  canBus.begin(useExtendedIds ? EXT_ID_LEN : STD_ID_LEN, bitRate,
               PORTA_11_12_XCVR);
  canBus.setAutoTxRetry(true);
  if (useExtendedIds)
    canBus.filterMask32Init(0, RESPONSE_BASE_29, RESPONSE_MASK_29);
  else
    // eXoCAN's 32-bit filter helper sets IDE=1 (extended frame).
    // Use its 16-bit filter helper for standard 11-bit OBD responses.
    canBus.filterMask16Init(0, RESPONSE_MIN_11, RX_MASK_11);
}
static bool sendFrame(uint16_t id, const uint8_t *data, uint8_t length) {
  return canBus.transmit(id, data, length);
}
static bool sendRequest(uint8_t service, uint8_t pid, bool hasPid) {
  uint8_t frame[8] = {0x02, service, pid, 0, 0, 0, 0, 0};
  frame[0] = hasPid ? 0x02 : 0x01;
  if (!hasPid) frame[2] = 0;
  const uint32_t requestId = extendedIds ? REQUEST_ID_29 : REQUEST_ID_11;
  return sendFrame(requestId, frame, 8);
}

// Returns application bytes after the positive response service byte.
// Handles ISO-TP single frames and bounded first/consecutive frame replies.
static bool receiveResponse(uint8_t expectedService, uint8_t *out,
                            uint16_t &outLength,
                            uint32_t timeoutMs = RESPONSE_TIMEOUT_MS) {
  const uint32_t started = millis();
  outLength = 0;
  uint32_t responseId = 0;
  uint16_t expectedLength = 0;
  uint16_t copied = 0;
  uint8_t nextSequence = 1;
  bool multiFrame = false;

  while ((uint32_t)(millis() - started) < timeoutMs) {
    volatile int id = 0;
    volatile int filterIndex = 0;
    uint8_t frame[8] = {0};
    const int dlc = canBus.receive(id, filterIndex, frame);
    if (dlc <= 0) {
      delay(1);
      continue;
    }
    const uint32_t canId = (uint32_t)id;
    const bool isObdResponse = extendedIds
        ? ((canId & RESPONSE_MASK_29) == RESPONSE_BASE_29)
        : (canId >= RESPONSE_MIN_11 && canId <= RESPONSE_MAX_11);
    if (!isObdResponse || dlc > 8) continue;

    const uint8_t frameType = frame[0] >> 4;
    if (!multiFrame && frameType == 0) {
      const uint8_t length = frame[0] & 0x0F;
      if (length < 1 || length > 7 || dlc < (int)(length + 1)) continue;
      if (frame[1] != expectedService) continue;
      outLength = length - 1;
      if (outLength) memcpy(out, frame + 2, outLength);
      return true;
    }

    if (!multiFrame && frameType == 1) {
      responseId = canId;
      expectedLength = ((uint16_t)(frame[0] & 0x0F) << 8) | frame[1];
      if (expectedLength < 2 || expectedLength > 128 || dlc < 8 ||
          frame[2] != expectedService) {
        expectedLength = 0;
        continue;
      }
      const uint8_t flowControl[8] = {0x30, 0x00, 0x00, 0, 0, 0, 0, 0};
      const uint32_t flowControlId = extendedIds
          ? (0x18DA00F1UL | ((responseId & 0xFFUL) << 8))
          : (responseId - 8);
      if (!sendFrame(flowControlId, flowControl, 8)) {
        expectedLength = 0;
        continue;
      }
      outLength = expectedLength - 1;
      copied = outLength > 5 ? 5 : outLength;
      if (copied) memcpy(out, frame + 3, copied);
      multiFrame = true;
      continue;
    }

    if (multiFrame && frameType == 2 && canId == responseId) {
      if (dlc < 2 || (frame[0] & 0x0F) != nextSequence) {
        outLength = 0;
        return false;
      }
      nextSequence = (nextSequence + 1) & 0x0F;
      for (uint8_t i = 1; i < (uint8_t)dlc && copied < outLength; ++i)
        out[copied++] = frame[i];
      if (copied >= outLength) return true;
    }
  }
  outLength = 0;
  return false;
}

static bool readService(uint8_t service, uint8_t pid, bool hasPid,
                        uint8_t *data, uint16_t &length) {
  length = 0;
  if (!sendRequest(service, pid, hasPid)) return false;
  return receiveResponse((uint8_t)(service + 0x40), data, length);
}
static bool readSupportedPids(uint8_t basePid, uint32_t &mask) {
  uint8_t data[8] = {0};
  uint16_t length = 0;
  if (!readService(0x01, basePid, true, data, length) ||
      length < 5 || data[0] != basePid) return false;
  mask = ((uint32_t)data[1] << 24) | ((uint32_t)data[2] << 16) |
         ((uint32_t)data[3] << 8) | data[4];
  return true;
}
static bool pidSupported(uint8_t pid) {
  uint8_t base = 0;
  uint32_t mask = 0;
  if (pid >= 0x01 && pid <= 0x20) {
    if (!havePids01) return true;
    base = 0x00; mask = pids01;
  } else if (pid >= 0x41 && pid <= 0x60) {
    if (!havePids40) return false;
    base = 0x40; mask = pids40;
  } else {
    return false;
  }
  const uint8_t bitIndex = (uint8_t)(pid - (base + 1));
  return (mask & ((uint32_t)1 << (31 - bitIndex))) != 0;
}

static bool probeAtCurrentConfig() {
  havePids01 = readSupportedPids(0x00, pids01);
  if (!havePids01) return false;
  havePids40 = readSupportedPids(0x40, pids40);
  return true;
}
static bool tryConfig(uint8_t bitRate, bool useExtendedIds,
                      const char *status) {
  sendStatus(status);
  configureCan(bitRate, useExtendedIds);
  if (!probeAtCurrentConfig()) return false;

  connected = true;
  liveEnabled = true;
  nextPid = 0;
  lastLive = millis();
  if (useExtendedIds)
    sendStatus(bitRate == BR500K ? "OBD_CAN_CONNECTED_29BIT_500K"
                                 : "OBD_CAN_CONNECTED_29BIT_250K");
  else
    sendStatus(bitRate == BR500K ? "OBD_CAN_CONNECTED_11BIT_500K"
                                 : "OBD_CAN_CONNECTED_11BIT_250K");
  return true;
}
static void probe() {
  connected = false;
  liveEnabled = false;
  havePids01 = havePids40 = false;

  if (tryConfig(BR500K, false, "CAN_PROBING_11BIT_500K")) return;
  if (tryConfig(BR500K, true,  "CAN_PROBING_29BIT_500K")) return;
  if (tryConfig(BR250K, false, "CAN_PROBING_11BIT_250K")) return;
  if (tryConfig(BR250K, true,  "CAN_PROBING_29BIT_250K")) return;

  connected = false;
  liveEnabled = false;
  havePids01 = havePids40 = false;
  sendStatus("OBD_CAN_NO_RESPONSE");
}

static void readDtc() {
  if (!connected) {
    sendStatus("OBD_CAN_NOT_CONNECTED");
    return;
  }
  uint8_t data[128] = {0};
  uint16_t length = 0;
  if (!readService(0x03, 0, false, data, length)) {
    sendStatus("DTC_READ_FAILED");
    return;
  }
  const uint8_t pairCount = (uint8_t)(length / 2);
  uint8_t count = 0;
  for (uint8_t i = 0; i < pairCount; ++i)
    if (data[i * 2] || data[i * 2 + 1]) ++count;

  Serial2.print(F("{\"type\":\"dtc\",\"profile\":\"sb13_can_obd\",\"count\":"));
  Serial2.print(count);
  Serial2.print(F(",\"codes\":["));
  uint8_t emitted = 0;
  for (uint8_t i = 0; i < pairCount; ++i) {
    if (!(data[i * 2] || data[i * 2 + 1])) continue;
    if (emitted++) Serial2.print(',');
    Serial2.print(F("{\"code\":\""));
    if (data[i * 2] < 16) Serial2.print('0');
    Serial2.print(data[i * 2], HEX);
    Serial2.print(' ');
    if (data[i * 2 + 1] < 16) Serial2.print('0');
    Serial2.print(data[i * 2 + 1], HEX);
    Serial2.print(F("\",\"status\":\"ذخیره‌شده\"}"));
  }
  Serial2.println(F("]}"));
  sendStatus(count ? "DTC_READ_OK" : "DTC_READ_OK_NO_CODES");
}
static void clearDtc() {
  if (!connected) {
    sendStatus("OBD_CAN_NOT_CONNECTED");
    return;
  }
  uint8_t data[8] = {0};
  uint16_t length = 0;
  if (readService(0x04, 0, false, data, length))
    sendStatus("DTC_CLEAR_OK");
  else
    sendStatus("DTC_CLEAR_FAILED");
}

static void emitPid(uint8_t pid) {
  if (!pidSupported(pid)) return;
  uint8_t data[8] = {0};
  uint16_t length = 0;
  if (!readService(0x01, pid, true, data, length) ||
      length < 2 || data[0] != pid) return;

  const uint8_t a = data[1];
  const uint8_t b = length > 2 ? data[2] : 0;
  const char *name = "";
  const char *unit = "";
  float value = 0.0f;

  switch (pid) {
    case 0x05:
      name = "دمای آب"; unit = "°C"; value = (int)a - 40; break;
    case 0x0B:
      name = "فشار منیفولد"; unit = "kPa"; value = a; break;
    case 0x0C:
      if (length < 3) return;
      name = "دور موتور"; unit = "rpm"; value = ((uint16_t)a * 256U + b) / 4.0f; break;
    case 0x0D:
      name = "سرعت خودرو"; unit = "km/h"; value = a; break;
    case 0x0F:
      name = "دمای هوای ورودی"; unit = "°C"; value = (int)a - 40; break;
    case 0x11:
      name = "موقعیت دریچه گاز"; unit = "%"; value = a * 100.0f / 255.0f; break;
    case 0x42:
      if (length < 3) return;
      name = "ولتاژ ECU"; unit = "V"; value = ((uint16_t)a * 256U + b) / 1000.0f; break;
    default:
      return;
  }

  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"sb13_can_obd\",\"parameters\":[{\"id\":\"sb13_can_"));
  if (pid < 16) Serial2.print('0');
  Serial2.print(pid, HEX);
  Serial2.print(F("\",\"name\":\"")); Serial2.print(name);
  Serial2.print(F("\",\"value\":")); Serial2.print(value, 1);
  Serial2.print(F(",\"unit\":\"")); Serial2.print(unit);
  Serial2.println(F("\",\"status\":\"تازه\"}]}"));
}

void initialize() {
  configureCan(BR500K, false);
  initialized = true;
}
void handleKey(char key) {
  if (!initialized) initialize();
  switch (key) {
    case '1': probe(); break;
    case '2': readDtc(); break;
    case '3': clearDtc(); break;
    case '6':
      if (!connected) {
        sendStatus("OBD_CAN_NOT_CONNECTED");
        break;
      }
      liveEnabled = true;
      nextPid = 0;
      lastLive = millis() - LIVE_PERIOD_MS;
      sendStatus("LIVE_REQUESTED");
      break;
    case '0':
      liveEnabled = false;
      connected = false;
      sendStatus("OBD_CAN_DISCONNECTED");
      break;
    default:
      break;
  }
}
void poll() {
  if (!initialized || !connected || !liveEnabled ||
      (uint32_t)(millis() - lastLive) < LIVE_PERIOD_MS) return;
  static const uint8_t pids[] = {0x0C, 0x05, 0x0F, 0x0D, 0x0B, 0x11, 0x42};
  lastLive = millis();
  emitPid(pids[nextPid]);
  nextPid = (uint8_t)((nextPid + 1) % (sizeof(pids) / sizeof(pids[0])));
}
}
