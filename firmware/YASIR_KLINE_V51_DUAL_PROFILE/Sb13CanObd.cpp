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
static const uint32_t LIVE_PERIOD_MS = 180;

static eXoCAN canBus(STD_ID_LEN, BR500K, PORTA_11_12_XCVR);
static bool initialized = false;
static bool connected = false;
static bool liveEnabled = false;
static uint32_t supportedPidMasks[8] = {0};
static bool supportedPidMaskValid[8] = {false};
static uint32_t lastLive = 0;
static uint32_t lastSlowLive = 0;
static uint8_t nextPid = 0;
static uint8_t slowPidIndex = 0;
static uint8_t fastCycleCount = 0;
static bool extendedIds = false;
static bool voltagePidFailureReported = false;

static void sendStatus(const char *message) {
  // Mirror CAN diagnostics to the USB/PC serial monitor as well as the ESP link.
  Serial.print(F("SB13 CAN STATUS: "));
  Serial.println(message);
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
                        uint8_t *data, uint16_t &length,
                        uint32_t timeoutMs = RESPONSE_TIMEOUT_MS) {
  length = 0;
  if (!sendRequest(service, pid, hasPid)) return false;
  return receiveResponse((uint8_t)(service + 0x40), data, length, timeoutMs);
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
  // Always probe module voltage directly. Some ECUs answer PID 0x42 even when
  // their chained support bitmap omits it.
  if (pid == 0x42) return true;
  if (pid == 0) return false;
  const uint8_t group = (uint8_t)((pid - 1) / 32);
  if (group >= 8 || !supportedPidMaskValid[group]) return false;
  const uint8_t bitIndex = (uint8_t)((pid - 1) % 32);
  return (supportedPidMasks[group] & ((uint32_t)1 << (31 - bitIndex))) != 0;
}

static const char *fuelStatusText(uint8_t value) {
  switch (value & 0x0F) {
    case 1: return "حلقه‌باز: دمای موتور ناکافی";
    case 2: return "حلقه‌باز: شرایط بار یا کاهش سرعت";
    case 4: return "حلقه‌بسته: کنترل سوخت با سنسور اکسیژن";
    case 8: return "حلقه‌باز: خطای سامانه";
    case 16: return "حلقه‌باز: قطع موقت سوخت هنگام کاهش سرعت";
    default: return "وضعیت سوخت نامشخص یا پشتیبانی‌نشده";
  }
}

// SAE J1979 support maps are chained: bit 0 announces the next 0x20 PID block.
static bool probeAtCurrentConfig() {
  memset(supportedPidMasks, 0, sizeof(supportedPidMasks));
  memset(supportedPidMaskValid, 0, sizeof(supportedPidMaskValid));
  if (!readSupportedPids(0x00, supportedPidMasks[0])) return false;
  supportedPidMaskValid[0] = true;
  for (uint8_t group = 1; group < 8; ++group) {
    if ((supportedPidMasks[group - 1] & 1U) == 0) break;
    if (!readSupportedPids((uint8_t)(group * 0x20), supportedPidMasks[group])) break;
    supportedPidMaskValid[group] = true;
  }
  return true;
}
static bool tryConfig(uint8_t bitRate, bool useExtendedIds,
                      const char *status) {
  sendStatus(status);
  configureCan(bitRate, useExtendedIds);
  if (!probeAtCurrentConfig()) return false;

  connected = true;
  liveEnabled = true;
  voltagePidFailureReported = false;
  nextPid = 0;
  slowPidIndex = 0;
  fastCycleCount = 0;
  lastLive = millis();
  lastSlowLive = millis();
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
  memset(supportedPidMaskValid, 0, sizeof(supportedPidMaskValid));

  if (tryConfig(BR500K, false, "CAN_PROBING_11BIT_500K")) return;
  if (tryConfig(BR500K, true,  "CAN_PROBING_29BIT_500K")) return;
  if (tryConfig(BR250K, false, "CAN_PROBING_11BIT_250K")) return;
  if (tryConfig(BR250K, true,  "CAN_PROBING_29BIT_250K")) return;

  connected = false;
  liveEnabled = false;
  memset(supportedPidMaskValid, 0, sizeof(supportedPidMaskValid));
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

static void emitNumber(const char *id, const char *name, float value,
                       const char *unit) {
  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"sb13_can_obd\",\"parameters\":[{\"id\":\""));
  Serial2.print(id);
  Serial2.print(F("\",\"name\":\"")); Serial2.print(name);
  Serial2.print(F("\",\"value\":")); Serial2.print(value, 2);
  Serial2.print(F(",\"unit\":\"")); Serial2.print(unit);
  Serial2.println(F("\",\"status\":\"تازه؛ استاندارد OBD-II\"}]}"));
}
static void emitText(const char *id, const char *name, const char *value) {
  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"sb13_can_obd\",\"parameters\":[{\"id\":\""));
  Serial2.print(id);
  Serial2.print(F("\",\"name\":\"")); Serial2.print(name);
  Serial2.print(F("\",\"value\":\"")); Serial2.print(value);
  Serial2.println(F("\",\"unit\":\"\",\"status\":\"تازه؛ استاندارد OBD-II\"}]}"));
}
static void emitUnavailable(const char *id, const char *name, const char *status) {
  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"sb13_can_obd\",\"parameters\":[{\"id\":\""));
  Serial2.print(id);
  Serial2.print(F("\",\"name\":\"")); Serial2.print(name);
  Serial2.print(F("\",\"value\":\"—\",\"unit\":\"V\",\"status\":\""));
  Serial2.print(status);
  Serial2.println(F("\"}]}"));
}
static void emitPid(uint8_t pid) {
  if (!pidSupported(pid)) return;
  uint8_t data[8] = {0};
  uint16_t length = 0;
  const bool received = readService(0x01, pid, true, data, length,
                                    pid == 0x42 ? 220 : RESPONSE_TIMEOUT_MS);
  if (!received || length < 2 || data[0] != pid) {
    if (pid == 0x42 && !voltagePidFailureReported) {
      voltagePidFailureReported = true;
      emitUnavailable("sb13_can_42", "ولتاژ باتری / تغذیه ECU",
                      "پاسخی از ECU به PID ولتاژ 0x42 دریافت نشد");
    }
    return;
  }
  if (pid == 0x42) voltagePidFailureReported = false;

  const uint8_t a = data[1];
  const uint8_t b = length > 2 ? data[2] : 0;
  const uint16_t ab = (uint16_t)a * 256U + b;
  char id[24];
  snprintf(id, sizeof(id), "sb13_can_%02X", pid);

  switch (pid) {
    case 0x01:
      if (length < 5) return;
      snprintf(id, sizeof(id), "sb13_can_%02X_mil", pid);
      emitText(id, "چراغ اخطار موتور (MIL)", (a & 0x80) ? "روشن" : "خاموش");
      snprintf(id, sizeof(id), "sb13_can_%02X_count", pid);
      emitNumber(id, "تعداد خطاهای موتور گزارش‌شده توسط ECU", (float)(a & 0x7F), "کد");
      return;
    case 0x03:
      if (length < 3) return;
      emitText("sb13_can_03_fuel1", "وضعیت سامانه سوخت ۱", fuelStatusText(a));
      emitText("sb13_can_03_fuel2", "وضعیت سامانه سوخت ۲", fuelStatusText(b));
      return;
    case 0x04: emitNumber(id, "بار محاسبه‌شده موتور", a * 100.0f / 255.0f, "%"); return;
    case 0x05: emitNumber(id, "دمای مایع خنک‌کننده موتور", (int)a - 40, "°C"); return;
    case 0x06: case 0x07: case 0x08: case 0x09: {
      const char *names[] = {"تصحیح کوتاه‌مدت سوخت بانک ۱", "تصحیح بلندمدت سوخت بانک ۱",
                             "تصحیح کوتاه‌مدت سوخت بانک ۲", "تصحیح بلندمدت سوخت بانک ۲"};
      emitNumber(id, names[pid - 0x06], a * 100.0f / 128.0f - 100.0f, "%"); return;
    }
    case 0x0A: emitNumber(id, "فشار سوخت", a * 3.0f, "kPa"); return;
    case 0x0B: emitNumber(id, "فشار مطلق منیفولد (MAP)", a, "kPa"); return;
    case 0x0C: if (length < 3) return; emitNumber(id, "دور موتور", ab / 4.0f, "rpm"); return;
    case 0x0D: emitNumber(id, "سرعت خودرو", a, "km/h"); return;
    case 0x0E: emitNumber(id, "آوانس جرقه", a / 2.0f - 64.0f, "°"); return;
    case 0x0F: emitNumber(id, "دمای هوای ورودی موتور", (int)a - 40, "°C"); return;
    case 0x10: if (length < 3) return; emitNumber(id, "دبی جرمی هوای ورودی (MAF)", ab / 100.0f, "g/s"); return;
    case 0x11: emitNumber(id, "موقعیت مطلق دریچه گاز A", a * 100.0f / 255.0f, "%"); return;
    case 0x14: case 0x15: case 0x16: case 0x17:
    case 0x18: case 0x19: case 0x1A: case 0x1B: {
      if (length < 3) return;
      const uint8_t sensor = (uint8_t)(pid - 0x14);
      const uint8_t bank = sensor / 2 + 1;
      const uint8_t position = sensor % 2 + 1;
      char label[48]; char suffix[24];
      snprintf(label, sizeof(label), "ولتاژ سنسور اکسیژن بانک %u سنسور %u", bank, position);
      snprintf(suffix, sizeof(suffix), "sb13_can_%02X_v", pid);
      emitNumber(suffix, label, a / 200.0f, "V");
      snprintf(label, sizeof(label), "تصحیح سوخت سنسور اکسیژن بانک %u سنسور %u", bank, position);
      snprintf(suffix, sizeof(suffix), "sb13_can_%02X_trim", pid);
      emitNumber(suffix, label, b * 100.0f / 128.0f - 100.0f, "%"); return;
    }
    case 0x1F: if (length < 3) return; emitNumber(id, "زمان کارکرد موتور از زمان روشن‌شدن", ab, "s"); return;
    case 0x21: if (length < 3) return; emitNumber(id, "مسافت طی‌شده با چراغ چک روشن", ab, "km"); return;
    case 0x23: if (length < 3) return; emitNumber(id, "فشار ریل سوخت", ab * 10.0f, "kPa"); return;
    case 0x2C: emitNumber(id, "فرمان بازچرخانی گاز اگزوز (EGR)", a * 100.0f / 255.0f, "%"); return;
    case 0x2D: emitNumber(id, "خطای بازچرخانی گاز اگزوز (EGR)", ((int)a - 128) * 100.0f / 128.0f, "%"); return;
    case 0x2E: emitNumber(id, "فرمان شیر تخلیه بخار بنزین", a * 100.0f / 255.0f, "%"); return;
    case 0x2F: emitNumber(id, "سطح سوخت باک", a * 100.0f / 255.0f, "%"); return;
    case 0x30: emitNumber(id, "تعداد گرم‌شدن موتور پس از پاک‌کردن خطا", a, "بار"); return;
    case 0x31: if (length < 3) return; emitNumber(id, "مسافت طی‌شده از پاک‌کردن خطاها", ab, "km"); return;
    case 0x32: if (length < 3) return; emitNumber(id, "فشار بخار سامانه سوخت", ab / 4.0f - 8192.0f, "Pa"); return;
    case 0x33: emitNumber(id, "فشار جو", a, "kPa"); return;
    case 0x24: case 0x25: case 0x26: case 0x27:
    case 0x28: case 0x29: case 0x2A: case 0x2B: {
      if (length < 5) return;
      const uint8_t sensor = (uint8_t)(pid - 0x24);
      const uint8_t bank = sensor / 2 + 1;
      const uint8_t position = sensor % 2 + 1;
      const uint16_t cd = (uint16_t)data[3] * 256U + data[4];
      char label[48]; char suffix[24];
      snprintf(label, sizeof(label), "لامبدای سنسور اکسیژن پهن‌باند بانک %u سنسور %u", bank, position);
      snprintf(suffix, sizeof(suffix), "sb13_can_%02X_lambda", pid);
      emitNumber(suffix, label, ab / 32768.0f, "λ");
      snprintf(label, sizeof(label), "ولتاژ سنسور اکسیژن بانک %u سنسور %u", bank, position);
      snprintf(suffix, sizeof(suffix), "sb13_can_%02X_v", pid);
      emitNumber(suffix, label, cd / 8192.0f, "V"); return;
    }
    case 0x34: case 0x35: case 0x36: case 0x37:
    case 0x38: case 0x39: case 0x3A: case 0x3B: {
      if (length < 5) return;
      const uint8_t sensor = (uint8_t)(pid - 0x34);
      const uint8_t bank = sensor / 2 + 1;
      const uint8_t position = sensor % 2 + 1;
      const uint16_t cd = (uint16_t)data[3] * 256U + data[4];
      char label[48]; char suffix[24];
      snprintf(label, sizeof(label), "لامبدای سنسور اکسیژن بانک %u سنسور %u", bank, position);
      snprintf(suffix, sizeof(suffix), "sb13_can_%02X_lambda", pid);
      emitNumber(suffix, label, ab / 32768.0f, "λ");
      snprintf(label, sizeof(label), "جریان سنسور اکسیژن بانک %u سنسور %u", bank, position);
      snprintf(suffix, sizeof(suffix), "sb13_can_%02X_ma", pid);
      emitNumber(suffix, label, cd / 256.0f - 128.0f, "mA"); return;
    }
    case 0x42: if (length < 3) return; emitNumber(id, "ولتاژ باتری / تغذیه ECU", ab / 1000.0f, "V"); return;
    case 0x43: if (length < 3) return; emitNumber(id, "بار مطلق موتور", ab * 100.0f / 255.0f, "%"); return;
    case 0x44: if (length < 3) return; emitNumber(id, "نسبت هم‌ارزی سوخت و هوا (فرمان ECU)", ab / 32768.0f, "λ"); return;
    case 0x45: emitNumber(id, "موقعیت نسبی دریچه گاز", a * 100.0f / 255.0f, "%"); return;
    case 0x46: emitNumber(id, "دمای هوای محیط", (int)a - 40, "°C"); return;
    case 0x47: emitNumber(id, "موقعیت مطلق دریچه گاز B", a * 100.0f / 255.0f, "%"); return;
    case 0x48: emitNumber(id, "موقعیت مطلق دریچه گاز C", a * 100.0f / 255.0f, "%"); return;
    case 0x49: emitNumber(id, "موقعیت پدال گاز D", a * 100.0f / 255.0f, "%"); return;
    case 0x4A: emitNumber(id, "موقعیت پدال گاز E", a * 100.0f / 255.0f, "%"); return;
    case 0x4B: emitNumber(id, "موقعیت پدال گاز F", a * 100.0f / 255.0f, "%"); return;
    case 0x4C: emitNumber(id, "موقعیت درخواستی دریچه گاز برقی", a * 100.0f / 255.0f, "%"); return;
    case 0x4D: if (length < 3) return; emitNumber(id, "زمان کارکرد با چراغ چک روشن", ab, "min"); return;
    case 0x4E: if (length < 3) return; emitNumber(id, "زمان پس از پاک‌کردن خطاها", ab, "min"); return;
    case 0x5C: emitNumber(id, "دمای روغن موتور", (int)a - 40, "°C"); return;
    default: return;
  }
}

static const uint8_t LIVE_PIDS[] = {
  0x01,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,0x11,
  0x14,0x15,0x16,0x17,0x18,0x19,0x1A,0x1B,0x1F,0x21,0x23,
  0x24,0x25,0x26,0x27,0x28,0x29,0x2A,0x2B,0x2C,0x2D,0x2E,0x2F,0x30,0x31,0x32,0x33,
  0x34,0x35,0x36,0x37,0x38,0x39,0x3A,0x3B,
  0x42,0x43,0x44,0x45,0x46,0x47,0x48,0x49,0x4A,0x4B,0x4C,0x4D,0x4E,0x5C
};

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
      slowPidIndex = 0;
      fastCycleCount = 0;
      lastLive = millis() - LIVE_PERIOD_MS;
      lastSlowLive = millis();
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
  // Keep the driver-relevant values fresh, while cycling through every other
  // supported standard PID in the background.
  static const uint8_t fastPids[] = {0x0C, 0x05, 0x0D, 0x0F, 0x11, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x42};
  static const uint8_t slowPids[] = {
    0x01,0x03,0x04,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0E,0x10,
    0x14,0x15,0x16,0x17,0x18,0x19,0x1A,0x1B,0x1F,0x21,0x23,
    0x24,0x25,0x26,0x27,0x28,0x29,0x2A,0x2B,0x2C,0x2D,0x2E,0x2F,0x30,0x31,0x32,0x33,
    0x34,0x35,0x36,0x37,0x38,0x39,0x3A,0x3B,0x43,0x44,0x46,0x4D,0x4E,0x5C
  };
  lastLive = millis();
  if (++fastCycleCount >= 6 && (uint32_t)(millis() - lastSlowLive) >= 250) {
    fastCycleCount = 0;
    lastSlowLive = millis();
    emitPid(slowPids[slowPidIndex]);
    slowPidIndex = (uint8_t)((slowPidIndex + 1) % (sizeof(slowPids) / sizeof(slowPids[0])));
    return;
  }
  emitPid(fastPids[nextPid]);
  nextPid = (uint8_t)((nextPid + 1) % (sizeof(fastPids) / sizeof(fastPids[0])));
}
}

