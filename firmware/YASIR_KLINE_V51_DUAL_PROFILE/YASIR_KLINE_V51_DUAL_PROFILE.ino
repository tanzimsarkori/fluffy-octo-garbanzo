#include <Arduino.h>
#include <string.h>
#include "BoschZamyad7971.h"
#include "Sb13CanObd.h"

// YASIR K-LINE V51 DUAL PROFILE - STM32duino Maple STM32F1 core

// Serial: PA9 TX / PA10 RX, 115200

const uint32_t K_BAUD = 10400;
const uint32_t RESPONSE_TIMEOUT_MS = 1100;

// ESP-01 UART: STM32 PA2 (USART2 TX) -> ESP RX, STM32 PA3 (USART2 RX) <- ESP TX
const uint32_t ESP_BAUD = 115200;
uint32_t lastWebTelemetry = 0;
// Reliability-first adaptive live mode. The confirmed-working V47 pacing is
// the baseline. Faster intervals require repeated whole-cycle ACKs from the
// Android app; missing/incomplete feedback immediately returns to baseline.
const uint32_t BASELINE_PERIOD_MS = 1600UL;
const uint32_t MIN_ADAPTIVE_PERIOD_MS = 1000UL;
const uint32_t ACK_WAIT_LIMIT_MS = 1500UL;
uint32_t adaptivePeriodMs = BASELINE_PERIOD_MS;
uint8_t fullCycleAckStreak = 0;
uint8_t readFailuresInARow = 0;
bool adaptiveMode = true;
bool waitingPrideAck = false;
uint16_t ackExpectedCycle = 0;
uint32_t lastPridePacketSentAt = 0;
uint32_t successfulPrideAcks = 0;
uint32_t incompletePrideAcks = 0;
bool espCommandArmed = false;
char espLine[32];
uint8_t espLineLen = 0;
uint32_t lastEspCommand = 0;

// Safety: actuator commands require explicit arm (X), then auto-stop after 2.5 s.
bool actuatorArmed = false;
uint32_t actuatorArmUntil = 0;
uint32_t actuatorStopAt = 0;
bool actuatorActive = false;

void sendWebDTC(uint8_t count);
void sendWebStatus(const char *message);

// V22: quiet live panel, capture and compare full 93-byte blocks.
bool verboseKline = true;
bool livePanel = false;
bool previousReady = false;
uint8_t previousBlock[93];
uint8_t baselineBlock[93];
bool baselineReady = false;
uint32_t lastPanel = 0;
uint8_t rx[192];
uint16_t rxLen = 0;
bool connected = false;
// E-MAX-derived Siemens Pride profile; not assumed valid for every Siemens ECU.
bool emaxPrideProfile = true;
enum EcuProfile : uint8_t { PROFILE_SIEMENS, PROFILE_BOSCH, PROFILE_SB13_CAN };
EcuProfile activeEcuProfile = PROFILE_SIEMENS;

void sendWebStatus(const char *message) {
  Serial2.print(F("{\"type\":\"status\",\"message\":\""));
  Serial2.print(message);
  Serial2.println(F("\"}"));
}

void hexByte(uint8_t b) {
  if (b < 16) Serial.print('0');
  Serial.print(b, HEX);
}
void printData(const char *tag, const uint8_t *p, uint16_t n) {
  Serial.print(tag);
  for (uint16_t i = 0; i < n; ++i) {
    hexByte(p[i]); Serial.print(' ');
  }
  Serial.println();
}
uint8_t sum8(const uint8_t *p, uint16_t n) {
  uint8_t s = 0;
  for (uint16_t i = 0; i < n; ++i) s += p[i];
  return s;
}
int frameLengthAt(const uint8_t *p, uint16_t n) {
  if (!n) return 0;
  if ((p[0] & 0xF0) != 0x80) return -1;
  uint8_t payload = p[0] & 0x0F;
  if (payload) return 3 + payload + 1;
  if (n < 4) return 0;
  if (!p[3]) return -1;
  return 4 + p[3] + 1;
}

uint8_t serviceOffset(const uint8_t *p) { return p[0] == 0x80 ? 4 : 3; }

bool exchange(const uint8_t *tx, uint16_t txLen,
              uint8_t expectedService, uint32_t timeoutMs = RESPONSE_TIMEOUT_MS) {
  rxLen = 0;
  while (Serial3.available()) Serial3.read();
  if (verboseKline) printData("TX: ", tx, txLen);
  Serial3.write(tx, txLen);
  Serial3.flush();

  uint8_t stream[256];
  uint16_t used = 0;
  uint32_t started = millis();
  uint32_t lastByte = started;
  bool partialEchoPrinted = false;

  while ((uint32_t)(millis() - started) < timeoutMs) {
    while (Serial3.available()) {
      uint8_t c = (uint8_t)Serial3.read();
      if (used < sizeof(stream)) stream[used++] = c;
      else {
        Serial.println("STREAM OVERFLOW - RESYNC");
        used = 0;
      }
      lastByte = millis();
    }

    while (used) {
      int len = frameLengthAt(stream, used);
      if (len < 0 || len > (int)sizeof(stream)) {
        for (uint16_t j = 1; j < used; ++j) stream[j-1] = stream[j];
        --used;
        continue;
      }
      if (len == 0 || (uint16_t)len > used) break;
      if (sum8(stream, len-1) != stream[len-1]) {
        if (verboseKline) Serial.println("BAD CHECKSUM / RESYNC");
        for (uint16_t j = 1; j < used; ++j) stream[j-1] = stream[j];
        --used;
        continue;
      }
      bool echo = (len == txLen);
      if (echo) for (uint16_t j = 0; j < txLen; ++j)
        if (stream[j] != tx[j]) { echo = false; break; }
      if (echo) {
        if (verboseKline) Serial.println("ECHO IGNORED");
      } else {
        uint8_t si = serviceOffset(stream);
        if (len >= si+2 && stream[1] == 0xF1 && stream[2] == 0x11) {
          uint8_t service = stream[si];
          bool positive = service == expectedService;
          bool negative = service == 0x7F && len >= si+4 &&
                          stream[si+1] == (uint8_t)(expectedService - 0x40);
          if (positive || negative) {
            if (len > (int)sizeof(rx)) {
              Serial.println("REPLY EXCEEDS RX BUFFER");
              return false;
            }
            rxLen = len;
            for (uint16_t j = 0; j < rxLen; ++j) rx[j] = stream[j];
            if (verboseKline) { Serial.print("FRAME LENGTH: "); Serial.println(rxLen); printData("FRAME: ", rx, rxLen); }
            if (negative) {
              Serial.print("NEGATIVE RESPONSE NRC: ");
              hexByte(rx[si+2]); Serial.println();
              return false;
            }
            if (verboseKline) Serial.println("ECU RESPONSE VALID");
            return true;
          }
        }
        if (verboseKline) printData("OTHER FRAME: ", stream, len);
      }
      for (uint16_t j = len; j < used; ++j) stream[j-len] = stream[j];
      used -= len;
    }
    if (used && (uint32_t)(millis()-lastByte) > 130 && !partialEchoPrinted) {
      if (verboseKline) printData("PARTIAL (WAITING): ", stream, used);
      partialEchoPrinted = true;
    }
  }
  if (used && verboseKline) printData("INCOMPLETE AT TIMEOUT: ", stream, used);
  Serial.println("ECU RESPONSE TIMEOUT");
  return false;
}

void connectECU() {
  connected = false;
  livePanel = false; previousReady = false; baselineReady = false;
  Serial.println("CONNECTING ECU...");
  Serial3.end();
  pinMode(PB10, OUTPUT);
  digitalWrite(PB10, HIGH);
  delay(350);
  digitalWrite(PB10, LOW);
  delay(25);
  digitalWrite(PB10, HIGH);
  delay(25);
  Serial3.begin(K_BAUD);
  delay(5);
  const uint8_t initCmd[] = {0xC1,0x33,0xF1,0x81,0x66};
  connected = exchange(initCmd, sizeof(initCmd), 0xC1, 1200);
  Serial.println(connected ? "ECU CONNECTED" : "ECU CONNECTION FAILED");
  sendWebStatus(connected ? "ECU_CONNECTED" : "ECU_CONNECTION_FAILED");
}

void readDTC() {
  if (!connected) {
    Serial.println("CONNECT ECU FIRST (1)");
    sendWebStatus("DTC_READ_FAILED_NOT_CONNECTED");
    return;
  }

  const uint8_t cmd[] = {0x84,0x11,0xF1,0x18,0x00,0xFF,0x00,0x9D};
  Serial.println("READING DTC...");
  sendWebStatus("DTC_READING");

  if (!exchange(cmd, sizeof(cmd), 0x58)) {
    Serial.println("DTC READ FAILED");
    sendWebStatus("DTC_READ_FAILED_ECU_NO_VALID_RESPONSE");
    return;
  }

  uint8_t si = serviceOffset(rx);
  if (rxLen < (uint16_t)(si + 2) || rx[si] != 0x58) {
    Serial.println("UNEXPECTED DTC RESPONSE HEADER");
    printData("DTC RAW FRAME: ", rx, rxLen);
    sendWebStatus("DTC_READ_FAILED_BAD_HEADER");
    return;
  }

  uint8_t count = rx[si + 1];
  uint16_t dataStart = (uint16_t)si + 2;
  uint16_t availableBytes = rxLen - dataStart - 1; // exclude checksum
  if (availableBytes < (uint16_t)count * 3) {
    Serial.println("UNEXPECTED DTC LENGTH");
    printData("DTC RAW FRAME: ", rx, rxLen);
    sendWebStatus("DTC_READ_FAILED_BAD_LENGTH");
    return;
  }

  Serial.print("DTC COUNT: ");
  Serial.println(count);
  sendWebDTC(count);

  if (count == 0) {
    Serial.println("NO DTC CODES REPORTED BY ECU");
    sendWebStatus("DTC_READ_OK_NO_CODES");
    return;
  }

  for (uint8_t i = 0; i < count; ++i) {
    uint16_t j = dataStart + (uint16_t)i * 3;
    Serial.print("DTC "); Serial.print(i + 1); Serial.print(": ");
    hexByte(rx[j]); Serial.print(' '); hexByte(rx[j+1]);
    Serial.print(" STATUS: "); hexByte(rx[j+2]);
    if (rx[j] == 0x05 && rx[j+1] == 0x05) Serial.print(" (P0505 IDLE CONTROL)");
    if (rx[j] == 0x02 && rx[j+1] == 0x30) Serial.print(" (FUEL PUMP RELAY - TESTED)");
    Serial.println();
  }
  sendWebStatus("DTC_READ_OK");
}

void clearDTC() {
  if (!connected) {
    Serial.println("CONNECT ECU FIRST (1)");
    sendWebStatus("DTC_CLEAR_FAILED_NOT_CONNECTED");
    return;
  }

  Serial.println("CLEAR DTC (TNM CAPTURED COMMAND)...");
  sendWebStatus("DTC_CLEARING");
  const uint8_t cmd[] = {0x83,0x11,0xF1,0x14,0xFF,0x00,0x98};

  if (exchange(cmd, sizeof(cmd), 0x54, 1300) &&
      rxLen == 7 && rx[4] == 0xFF && rx[5] == 0x00) {
    Serial.println("CLEAR COMMAND ACCEPTED BY ECU");
    sendWebStatus("DTC_CLEAR_ACCEPTED_RECHECKING");
    delay(350);
    readDTC();
  } else {
    Serial.println("CLEAR NOT CONFIRMED");
    sendWebStatus("DTC_CLEAR_FAILED_ECU_DID_NOT_CONFIRM");
  }
}

void readSiemensBlock() {
  if (!connected) { Serial.println("CONNECT ECU FIRST (1)"); return; }
  const uint8_t cmd[] = {0x82,0x11,0xF1,0x21,0x01,0xA6};
  Serial.println("SIEMENS 21 01 LONG BLOCK...");
  if (!exchange(cmd, sizeof(cmd), 0x61, 1800)) {
    Serial.println("BLOCK READ FAILED - NO VALUES ASSUMED");
    return;
  }
  uint8_t s = serviceOffset(rx);
  if (rxLen < s+3 || rx[s+1] != 0x01) {
    Serial.println("UNEXPECTED LOCAL ID");
    return;
  }
  uint16_t dataStart = s+2;
  uint16_t dataLen = rxLen - dataStart - 1;
  Serial.print("LOCAL ID 01, DATA BYTES: "); Serial.println(dataLen);
  for (uint16_t i = 0; i < dataLen; ++i) {
    Serial.print("["); Serial.print(i); Serial.print("]=");
    hexByte(rx[dataStart+i]); Serial.print(' ');
    if ((i % 12) == 11) Serial.println();
  }
  Serial.println();
  Serial.println("RAW ONLY: RPM/TEMP FORMULAS NOT YET VERIFIED");
}

void sampleBlocks() {
  if (!connected) { Serial.println("CONNECT ECU FIRST (1)"); return; }
  Serial.println("3 SAMPLES - READ ONLY");
  for (uint8_t i = 0; i < 3; ++i) {
    Serial.print("SAMPLE "); Serial.println(i+1);
    readSiemensBlock();
    if (i < 2) delay(350);
  }
}

bool fetchBlock(uint8_t *out) {
  if (!connected) { Serial.println("CONNECT ECU FIRST (1)"); return false; }
  const uint8_t cmd[] = {0x82,0x11,0xF1,0x21,0x01,0xA6};
  bool oldVerbose = verboseKline;
  verboseKline = false;
  bool ok = exchange(cmd, sizeof(cmd), 0x61, 1800);
  verboseKline = oldVerbose;
  if (!ok) return false;
  uint8_t si = serviceOffset(rx);
  if (rxLen != 100 || si != 4 || rx[si+1] != 0x01 ||
      rx[0] != 0x80 || rx[3] != 0x5F) {
    Serial.println("UNEXPECTED SIEMENS BLOCK FORMAT");
    return false;
  }
  memcpy(out, rx + si + 2, 93);
  return true;
}

void showDifferences(const uint8_t *a, const uint8_t *b, const char *title) {
  Serial.println(title);
  uint8_t changed = 0;
  for (uint8_t i = 0; i < 93; i++) {
    if (a[i] == b[i]) continue;
    Serial.print("["); Serial.print(i); Serial.print("] ");
    hexByte(a[i]); Serial.print(" -> "); hexByte(b[i]); Serial.println();
    changed++;
  }
  if (!changed) Serial.println("NO CHANGES");
  else { Serial.print("CHANGED BYTES: "); Serial.println(changed); }
}

uint16_t foreignWord(const uint8_t *d, uint8_t parLow) {
  return (uint16_t)d[parLow-1] + 255U * (uint16_t)d[parLow];
}
float sparkAngle(uint8_t raw) {
  return (255.0f - raw) * 0.42857f - 27.857f;
}


// E-MAX Siemens Pride profile: conversions extracted from its compiled
// Unit_Engine_Siemens_Pride parameter-analysis module.
// These use standard little-endian 16/32-bit words; keep separate from V42's
// existing foreignWord() conversion because this is a different source profile.
uint16_t emaxWord(const uint8_t *d, uint8_t lowIndex) {
  return (uint16_t)d[lowIndex] | ((uint16_t)d[lowIndex + 1] << 8);
}
uint32_t emaxLong(const uint8_t *d, uint8_t lowIndex) {
  return (uint32_t)d[lowIndex]
       | ((uint32_t)d[lowIndex + 1] << 8)
       | ((uint32_t)d[lowIndex + 2] << 16)
       | ((uint32_t)d[lowIndex + 3] << 24);
}
// V47: compact, numbered 5-value packets with pauses for ESP-01 UART handling.
// Parameter order and the 35 existing E-MAX conversions remain unchanged.
// Packet: {"t":"p","s":cycle,"i":firstParameterIndex,"v":[value,...]}
// The phone knows the corresponding ID, label, and unit by fixed array index.
uint8_t emaxJsonChunkCount = 0;
uint8_t emaxParameterIndex = 0;
uint16_t emaxCycle = 0;
void startEmaxChunkIfNeeded() {
  if (emaxJsonChunkCount == 0) {
    Serial2.print(F("{\"t\":\"p\",\"s\":")); Serial2.print(emaxCycle);
    Serial2.print(F(",\"i\":")); Serial2.print(emaxParameterIndex);
    Serial2.print(F(",\"v\":["));
  } else Serial2.print(',');
}
void finishEmaxChunkIfNeeded() {
  ++emaxJsonChunkCount;
  ++emaxParameterIndex;
  if (emaxJsonChunkCount >= 5) {
    Serial2.println(F("]}"));
    Serial2.flush();
    delay(55);  // Leave time for ESP receive ring buffers and TCP bridge.
    emaxJsonChunkCount = 0;
  }
}
void printEmaxParam(const char *id, const char *name, float value,
                    uint8_t decimals, const char *unit, const char *status,
                    bool &first) {
  (void)id; (void)name; (void)unit; (void)status; (void)first;
  startEmaxChunkIfNeeded();
  Serial2.print(value, decimals);
  finishEmaxChunkIfNeeded();
}
void printEmaxTextParam(const char *id, const char *name, const char *value,
                        const char *unit, const char *status, bool &first) {
  (void)id; (void)name; (void)unit; (void)status; (void)first;
  startEmaxChunkIfNeeded();
  Serial2.print('"'); Serial2.print(value); Serial2.print('"');
  finishEmaxChunkIfNeeded();
}
void sendEmaxPrideParameters(const uint8_t *d) {
  const char *profileStatus = "فرمول استخراج‌شده از E-MAX زیمنس پراید؛ برای ECU فعلی تأیید نشده";
  const char *rawStatus = "مقدار خام طبق منبع E-MAX";
  bool first = true;
  const char *gearNames[8] = {
    "خلاص", "دنده ۱", "دنده ۲", "دنده ۳",
    "دنده ۴", "دنده ۵", "دنده ۶", "عقب"
  };
  const uint8_t acBits = d[24];
  const uint8_t fanState = d[69] & 0x03;

  emaxJsonChunkCount = 0;
  emaxParameterIndex = 0;
  ++emaxCycle;

  // Formulas and payload offsets below were extracted from E-MAX's
  // Unit_Engine_Siemens_Pride::Analyse_Param() and Fill_Param_Table().
  printEmaxParam("emax_pride_battery", "زیمنس پراید • ولتاژ باتری",
    d[0] * 0.101875f, 2, "V", profileStatus, first);
  printEmaxParam("emax_pride_air_temp", "زیمنس پراید • دمای هوای ورودی",
    d[5] * 0.75f - 48.0f, 1, "°C", profileStatus, first);
  printEmaxParam("emax_pride_tps_voltage", "زیمنس پراید • ولتاژ دریچه گاز",
    d[6] * 0.01953f, 3, "V", "ضریب منبع؛ برچسب واحد منبع mV است و نیاز به تطبیق دارد", first);
  printEmaxParam("emax_pride_tps_angle", "زیمنس پراید • زاویه دریچه گاز",
    d[7] * 0.46862749f, 2, "°", profileStatus, first);
  printEmaxParam("emax_pride_map", "زیمنس پراید • فشار منیفولد",
    emaxWord(d, 13) * 0.08291756f, 1, "mbar", profileStatus, first);
  printEmaxParam("emax_pride_rpm", "زیمنس پراید • دور موتور",
    emaxWord(d, 19), 0, "rpm", profileStatus, first);
  printEmaxParam("emax_pride_stepper_actual", "زیمنس پراید • موقعیت واقعی استپر",
    d[60], 0, "", rawStatus, first);
  printEmaxParam("emax_pride_canister_ocr", "زیمنس پراید • فرمان شیر کنیستر",
    emaxWord(d, 65) * 0.0015258f, 2, "%", profileStatus, first);
  printEmaxParam("emax_pride_canister_load", "زیمنس پراید • بار کنیستر",
    emaxWord(d, 67) * 0.00012207034f, 4, "", "ضریب منبع؛ واحد مشخص نشده", first);
  printEmaxParam("emax_pride_stepper_target", "زیمنس پراید • موقعیت هدف استپر",
    d[70], 0, "", rawStatus, first);
  printEmaxParam("emax_pride_engine_load", "زیمنس پراید • بار موتور",
    emaxWord(d, 15) * 0.02121f, 2, "", "ضریب منبع؛ واحد مشخص نشده", first);
  printEmaxParam("emax_pride_vehicle_speed", "زیمنس پراید • سرعت خودرو",
    d[18], 0, "km/h", profileStatus, first);
  printEmaxParam("emax_pride_injection_time", "زیمنس پراید • زمان پاشش",
    emaxWord(d, 41) * 0.004f, 3, "ms", profileStatus, first);
  printEmaxParam("emax_pride_mileage", "زیمنس پراید • مسافت پیموده‌شده",
    (float)emaxLong(d, 75) * 0.1f, 1, "km", profileStatus, first);
  printEmaxTextParam("emax_pride_gear", "زیمنس پراید • وضعیت دنده",
    gearNames[d[79] & 0x07], "", profileStatus, first);
  printEmaxParam("emax_pride_speed_disconnects", "زیمنس پراید • دفعات قطع سنسور سرعت",
    d[81], 0, "count", profileStatus, first);
  printEmaxParam("emax_pride_speed_error_runtime", "زیمنس پراید • زمان حرکت با خطای سنسور سرعت",
    (float)emaxLong(d, 82) * 0.0000277777f, 3, "h", profileStatus, first);
  printEmaxParam("emax_pride_o2_voltage_1", "زیمنس پراید • ولتاژ سنسور اکسیژن ۱",
    emaxWord(d, 25) * 0.0048826f, 3, "V", profileStatus, first);
  printEmaxParam("emax_pride_o2_voltage_2", "زیمنس پراید • ولتاژ سنسور اکسیژن ۲",
    emaxWord(d, 27) * 0.0048826f, 3, "V", profileStatus, first);
  printEmaxTextParam("emax_pride_mixture_status", "زیمنس پراید • وضعیت تنظیم مخلوط",
    (d[45] & 0x01) ? "حلقه بسته (Close LOOP)" : "حلقه باز (OPEN LOOP)",
    "", profileStatus, first);
  printEmaxParam("emax_pride_o2_correction", "زیمنس پراید • تصحیح سنسور اکسیژن",
    emaxWord(d, 45) * 0.00146484375f, 3, "", "ضریب منبع؛ واحد مشخص نشده", first);
  printEmaxParam("emax_pride_coolant", "زیمنس پراید • دمای مایع خنک‌کننده",
    d[3] * 0.75f - 48.0f, 1, "°C", profileStatus, first);
  printEmaxTextParam("emax_pride_ac_switch", "زیمنس پراید • کلید تهویه مطبوع",
    (acBits & 0x01) ? "روشن (ON)" : "خاموش (OFF)", "", profileStatus, first);
  printEmaxTextParam("emax_pride_ac_control", "زیمنس پراید • کنترل تهویه مطبوع",
    (acBits & 0x02) ? "روشن (ON)" : "خاموش (OFF)", "", profileStatus, first);
  printEmaxTextParam("emax_pride_ac_relay", "زیمنس پراید • رله تهویه مطبوع",
    (acBits & 0x04) ? "روشن (ON)" : "خاموش (OFF)", "", profileStatus, first);
  const char *fanNames[4] = {"خاموش (OFF)", "کم (LOW)", "نامشخص (-)", "زیاد (HIGH)"};
  printEmaxTextParam("emax_pride_fan_status", "زیمنس پراید • وضعیت فن خنک‌کننده",
    fanNames[fanState], "", profileStatus, first);
  printEmaxParam("emax_pride_injector_1", "زیمنس پراید • زمان پاشش انژکتور ۱",
    emaxWord(d, 33) * 0.004f, 3, "ms", profileStatus, first);
  printEmaxParam("emax_pride_injector_2", "زیمنس پراید • زمان پاشش انژکتور ۲",
    emaxWord(d, 35) * 0.004f, 3, "ms", profileStatus, first);
  printEmaxParam("emax_pride_injector_3", "زیمنس پراید • زمان پاشش انژکتور ۳",
    emaxWord(d, 37) * 0.004f, 3, "ms", profileStatus, first);
  printEmaxParam("emax_pride_injector_4", "زیمنس پراید • زمان پاشش انژکتور ۴",
    emaxWord(d, 39) * 0.004f, 3, "ms", profileStatus, first);
  printEmaxParam("emax_pride_injector_average", "زیمنس پراید • میانگین زمان پاشش",
    emaxWord(d, 41) * 0.004f, 3, "ms", profileStatus, first);
  printEmaxParam("emax_pride_spark_1", "زیمنس پراید • آوانس جرقه سیلندر ۱",
    d[29] * 0.75f - 0.375f, 2, "°", profileStatus, first);
  printEmaxParam("emax_pride_spark_2", "زیمنس پراید • آوانس جرقه سیلندر ۲",
    d[30] * 0.75f - 0.375f, 2, "°", profileStatus, first);
  printEmaxParam("emax_pride_spark_3", "زیمنس پراید • آوانس جرقه سیلندر ۳",
    d[31] * 0.75f - 0.375f, 2, "°", profileStatus, first);
  printEmaxParam("emax_pride_spark_4", "زیمنس پراید • آوانس جرقه سیلندر ۴",
    d[32] * 0.75f - 0.375f, 2, "°", profileStatus, first);

  if (emaxJsonChunkCount > 0) {
    Serial2.println(F("]}"));
    Serial2.flush();
    delay(55);
    emaxJsonChunkCount = 0;
  }
}

void sendWebTelemetry(const uint8_t *d) {
  uint16_t rpm = foreignWord(d, 20);
  float coolant = (255.0f - d[2]) * 0.5f - 12.0f;
  float intake = (255.0f - d[4]) * 0.5f - 12.0f;
  float battery = d[0] / 10.0f;
  float mapCandidate = d[25] * 94.0f / 255.0f + 10.4f;
  Serial2.print(F("{\"type\":\"telemetry\",\"connected\":true,\"rpm\":")); Serial2.print(rpm);
  Serial2.print(F(",\"coolant\":")); Serial2.print(coolant, 1);
  Serial2.print(F(",\"intake\":")); Serial2.print(intake, 1);
  Serial2.print(F(",\"battery\":")); Serial2.print(battery, 1);
  Serial2.print(F(",\"map\":")); Serial2.print(mapCandidate, 1);
  Serial2.print(F(",\"mapRaw\":")); Serial2.print(d[25]);
  Serial2.print(F(",\"injectors\":["));
  for (uint8_t i=0; i<5; ++i) { if (i) Serial2.print(','); Serial2.print(foreignWord(d, 34+2*i)/250.0f, 3); }
  Serial2.print(F("],\"spark\":["));
  for (uint8_t i=0; i<4; ++i) { if (i) Serial2.print(','); Serial2.print(sparkAngle(d[29+i]), 2); }
  Serial2.println(F("],\"tps\":null,\"tpsRaw\":null}"));
  Serial2.flush();
  if (emaxPrideProfile) sendEmaxPrideParameters(d);
}

void hexByteJson(uint8_t b) { if (b < 16) Serial2.print('0'); Serial2.print(b, HEX); }

void sendWebDTC(uint8_t count) {
  uint8_t si = serviceOffset(rx);
  uint16_t dataStart = (uint16_t)si + 2;
  Serial2.print(F("{\"type\":\"dtc\",\"count\":"));
  Serial2.print(count);
  Serial2.print(F(",\"codes\":["));
  uint8_t emitted = 0;
  for (uint8_t i = 0; i < count; ++i) {
    uint16_t j = dataStart + (uint16_t)i * 3;
    if (j + 2 >= rxLen - 1) break;
    if (emitted++) Serial2.print(',');
    Serial2.print(F("{\"code\":\""));
    hexByteJson(rx[j]); Serial2.print(' '); hexByteJson(rx[j+1]);
    Serial2.print(F("\",\"status\":\""));
    hexByteJson(rx[j+2]);
    Serial2.print(F("\"}"));
  }
  Serial2.println(F("]}"));
}

void showV26Values(const uint8_t *d) {
  uint16_t rpmCSharp = foreignWord(d, 20);
  float coolant = (255.0f - d[2]) * 0.5f - 12.0f;
  float intake = (255.0f - d[4]) * 0.5f - 12.0f;
  float battery = d[0] / 10.0f;
  float mapCandidate = d[25] * 94.0f / 255.0f + 10.4f;

  Serial.println("\n========== YASIR ECU PANEL V34 ==========");
  Serial.print("ENGINE RPM (C#):    "); Serial.println(rpmCSharp);
  Serial.print("COOLANT (C):        "); Serial.println(coolant, 1);
  Serial.print("INTAKE AIR (C):     "); Serial.println(intake, 1);
  Serial.print("BATTERY (V):        "); Serial.println(battery, 1);
  Serial.print("MAP candidate:      "); Serial.print(mapCandidate, 1);
  Serial.print(" (raw par[26]=0x"); hexByte(d[25]); Serial.println(") UNVERIFIED");
  Serial.println("INJECTOR TIMES (ms, C# formula x255 /250):");
  for (uint8_t i=0; i<5; ++i) {
    uint8_t parLow = 34 + 2*i;
    uint16_t raw = foreignWord(d, parLow);
    Serial.print(i == 4 ? "  AVG: " : "  INJ ");
    if (i < 4) Serial.print(i+1);
    Serial.print(" = "); Serial.print(raw / 250.0f, 3);
    Serial.print("  (raw "); Serial.print(raw); Serial.println(")");
  }
  Serial.println("SPARK ANGLE (deg, C# candidate):");
  for (uint8_t i=0; i<4; ++i) {
    uint8_t raw = d[29+i]; // par[30..33]
    Serial.print("  CYL "); Serial.print(i+1);
    Serial.print(" = "); Serial.print(sparkAngle(raw), 2);
    Serial.print("  (raw 0x"); hexByte(raw); Serial.println(")");
  }
  Serial.println("OTHER C# COMBINED RAW WORDS (x255, meaning unknown):");
  const uint8_t extraParLow[] = {16,59,57,73,22,12};
  for (uint8_t i=0; i<sizeof(extraParLow); ++i) {
    uint8_t low = extraParLow[i];
    Serial.print("  par["); Serial.print(low); Serial.print(":");
    Serial.print(low+1); Serial.print("] = ");
    Serial.println(foreignWord(d, low));
  }
  Serial.print("RAW coolant d[2]=0x"); hexByte(d[2]); Serial.println();
  Serial.print("RAW intake  d[4]=0x"); hexByte(d[4]); Serial.println();
  Serial.print("RAW battery d[0]=0x"); hexByte(d[0]); Serial.println();
  Serial.println("TPS: NO FORMULA FOUND IN FOREIGN SOURCE");
  Serial.println("C# DERIVED VALUES: CHECK AGAINST TESTER/TNM");
  Serial.println("===========================================");
}


// ADD-ON ONLY: This is a second, separately labelled part of the EXISTING PC
// serial monitor panel.  All original V34 values and their formulas are untouched.
// These conversions belong to the E-MAX Siemens Pride reference profile and
// have NOT been validated on the connected SIM2K-34VR ECU.
void emaxPanelNumber(const char *name, float value, uint8_t decimals, const char *unit) {
  Serial.print("  "); Serial.print(name); Serial.print(" = ");
  Serial.print(value, decimals);
  if (unit && unit[0]) { Serial.print(" "); Serial.print(unit); }
  Serial.println();
}
void emaxPanelState(const char *name, const char *state, uint8_t raw) {
  Serial.print("  "); Serial.print(name); Serial.print(" = ");
  Serial.print(state);
  Serial.print(" (raw 0x"); hexByte(raw); Serial.println(")");
}
void showEmaxPrideSerialPanel(const uint8_t *d) {
  const char *gearNames[8] = {
    "NEUTRAL", "GEAR_1", "GEAR_2", "GEAR_3",
    "GEAR_4", "GEAR_5", "GEAR_6", "REVERSE"
  };
  const char *fanNames[4] = { "OFF", "LOW", "UNKNOWN", "HIGH" };

  Serial.println("\n========== ADDITIONAL E-MAX SIEMENS PRIDE PANEL ==========");
  Serial.println("REFERENCE FORMULAS ONLY - NOT VERIFIED ON THIS ECU");
  Serial.println("[SENSORS AND ENGINE]");
  emaxPanelNumber("BATTERY / PRIDE",             d[0] * 0.101875f,               2, "V");
  emaxPanelNumber("COOLANT / PRIDE",             d[3] * 0.75f - 48.0f,           1, "C");
  emaxPanelNumber("INTAKE AIR / PRIDE",          d[5] * 0.75f - 48.0f,           1, "C");
  emaxPanelNumber("TPS VOLTAGE / PRIDE",         d[6] * 0.01953f,               3, "V*");
  emaxPanelNumber("TPS ANGLE / PRIDE",           d[7] * 0.46862749f,            2, "deg");
  emaxPanelNumber("MAP / PRIDE",                 emaxWord(d,13) * 0.08291756f,  1, "mbar");
  emaxPanelNumber("RPM / PRIDE",                 emaxWord(d,19),                  0, "rpm");
  emaxPanelNumber("ENGINE LOAD / PRIDE",         emaxWord(d,15) * 0.02121f,     2, "(source unit unspecified)");
  emaxPanelNumber("VEHICLE SPEED / PRIDE",       d[18],                            0, "km/h");
  emaxPanelNumber("STEPPER ACTUAL / PRIDE",      d[60],                            0, "raw");
  emaxPanelNumber("STEPPER TARGET / PRIDE",      d[70],                            0, "raw");
  emaxPanelNumber("CANISTER OCR / PRIDE",        emaxWord(d,65) * 0.0015258f,   2, "%");
  emaxPanelNumber("CANISTER LOAD / PRIDE",       emaxWord(d,67) * 0.00012207034f,4,"(source unit unspecified)");
  emaxPanelNumber("O2 SENSOR 1 / PRIDE",         emaxWord(d,25) * 0.0048826f,   3, "V");
  emaxPanelNumber("O2 SENSOR 2 / PRIDE",         emaxWord(d,27) * 0.0048826f,   3, "V");
  emaxPanelNumber("O2 CORRECTION / PRIDE",       emaxWord(d,45) * 0.00146484375f,3,"(source unit unspecified)");

  Serial.println("[FUEL INJECTION AND SPARK]");
  emaxPanelNumber("INJECTION TIME / PRIDE",     emaxWord(d,41) * 0.004f,        3, "ms");
  emaxPanelNumber("INJECTOR 1 / PRIDE",          emaxWord(d,33) * 0.004f,        3, "ms");
  emaxPanelNumber("INJECTOR 2 / PRIDE",          emaxWord(d,35) * 0.004f,        3, "ms");
  emaxPanelNumber("INJECTOR 3 / PRIDE",          emaxWord(d,37) * 0.004f,        3, "ms");
  emaxPanelNumber("INJECTOR 4 / PRIDE",          emaxWord(d,39) * 0.004f,        3, "ms");
  emaxPanelNumber("INJECTOR AVG / PRIDE",        emaxWord(d,41) * 0.004f,        3, "ms");
  emaxPanelNumber("SPARK CYL 1 / PRIDE",         d[29] * 0.75f - 0.375f,         2, "deg");
  emaxPanelNumber("SPARK CYL 2 / PRIDE",         d[30] * 0.75f - 0.375f,         2, "deg");
  emaxPanelNumber("SPARK CYL 3 / PRIDE",         d[31] * 0.75f - 0.375f,         2, "deg");
  emaxPanelNumber("SPARK CYL 4 / PRIDE",         d[32] * 0.75f - 0.375f,         2, "deg");

  Serial.println("[VEHICLE AND CONTROL STATES]");
  emaxPanelNumber("MILEAGE / PRIDE",            (float)emaxLong(d,75) * 0.1f,  1, "km");
  emaxPanelState("GEAR / PRIDE",                 gearNames[d[79] & 0x07],            d[79]);
  emaxPanelNumber("SPEED DISCONNECTS / PRIDE",  d[81],                           0, "count");
  emaxPanelNumber("SPEED ERROR TIME / PRIDE",   (float)emaxLong(d,82) * 0.0000277777f, 3, "h");
  emaxPanelState("MIXTURE LOOP / PRIDE",         (d[45] & 0x01) ? "CLOSED" : "OPEN", d[45]);
  emaxPanelState("A/C SWITCH / PRIDE",          (d[24] & 0x01) ? "ON" : "OFF",       d[24]);
  emaxPanelState("A/C CONTROL / PRIDE",         (d[24] & 0x02) ? "ON" : "OFF",       d[24]);
  emaxPanelState("A/C RELAY / PRIDE",           (d[24] & 0x04) ? "ON" : "OFF",       d[24]);
  emaxPanelState("COOLING FAN / PRIDE",         fanNames[d[69] & 0x03],           d[69]);
  Serial.println("* TPS voltage has a source-unit inconsistency (mV vs ~V scale).");
  Serial.println("The original V34 panel above is unchanged.");
  Serial.println("==========================================================");
}

void panel() {
  uint8_t d[93];
  if (!fetchBlock(d)) { Serial.println("PANEL: BLOCK READ FAILED"); return; }
  showV26Values(d);
  if (emaxPrideProfile) showEmaxPrideSerialPanel(d);
  memcpy(previousBlock, d, 93);
  previousReady = true;
}

void saveBaseline() {
  if (!fetchBlock(baselineBlock)) {
    Serial.println("BASELINE NOT SAVED"); return;
  }
  baselineReady = true;
  Serial.println("BASELINE SAVED: 93 BYTES. CHANGE TESTER AND PRESS 'A'.");
  Serial.print("BASELINE RAW [5] = 0x"); hexByte(baselineBlock[5]); Serial.println();
}

void compareBaseline() {
  if (!baselineReady) { Serial.println("SAVE BASELINE FIRST (0)"); return; }
  uint8_t current[93];
  if (!fetchBlock(current)) { Serial.println("COMPARE FAILED"); return; }
  uint16_t rpm = foreignWord(current, 20);
  Serial.print("RPM C# x255: "); Serial.println(rpm);
  showV26Values(current);
  if (emaxPrideProfile) showEmaxPrideSerialPanel(current);
  showDifferences(baselineBlock, current, "BASELINE -> CURRENT:");
  Serial.println("BASELINE PRESERVED; PRESS '0' TO REPLACE IT.");
}

void showAllForeignParameters() {
  uint8_t d[93];
  if (!fetchBlock(d)) { Serial.println("RAW PARAMETER READ FAILED"); return; }
  Serial.println("\n=== V27: ALL 93 FOREIGN par[] BYTES ===");
  Serial.println("par[0]=01 (LOCAL IDENTIFIER, NOT A SENSOR)");
  for (uint8_t i=0; i<93; ++i) {
    uint8_t parIndex = i + 1;
    Serial.print("par["); Serial.print(parIndex); Serial.print("] / d[");
    Serial.print(i); Serial.print("] = 0x"); hexByte(d[i]);
    Serial.print(" ("); Serial.print(d[i]); Serial.print(")");
    switch (parIndex) {
      case 1: Serial.print("  BATTERY RAW"); break;
      case 3: Serial.print("  COOLANT RAW"); break;
      case 5: Serial.print("  INTAKE AIR RAW"); break;
      case 20: case 21: Serial.print("  RPM WORD BYTE"); break;
      case 26: Serial.print("  MAP CANDIDATE RAW (SOURCE COMMENTED)"); break;
      case 30: case 31: case 32: case 33: Serial.print("  SPARK RAW"); break;
      case 34: case 35: case 36: case 37: case 38:
      case 39: case 40: case 41: case 42: case 43:
        Serial.print("  INJECTOR WORD BYTE"); break;
    }
    Serial.println();
  }
  Serial.println("RAW BYTES ARE NOT VERIFIED SENSOR MEASUREMENTS");
}

bool csvMode = false;
void csvHeader() {
  Serial.println("millis,rpm,coolant_C,intake_C,battery_V,map_candidate_unverified,inj1_ms,inj2_ms,inj3_ms,inj4_ms,inj_avg_ms,spark1_deg,spark2_deg,spark3_deg,spark4_deg,raw_map,raw_tps_unknown");
}
void csvSample() {
  uint8_t d[93];
  if (!fetchBlock(d)) { Serial.println("# CSV READ FAILED"); return; }
  Serial.print(millis()); Serial.print(',');
  Serial.print(foreignWord(d,20)); Serial.print(',');
  Serial.print((255.0f-d[2])*0.5f-12.0f,1); Serial.print(',');
  Serial.print((255.0f-d[4])*0.5f-12.0f,1); Serial.print(',');
  Serial.print(d[0]/10.0f,1); Serial.print(',');
  Serial.print(d[25]*94.0f/255.0f+10.4f,2); Serial.print(',');
  for(uint8_t i=0;i<5;i++) { Serial.print(foreignWord(d,34+2*i)/250.0f,3); Serial.print(','); }
  for(uint8_t i=0;i<4;i++) { Serial.print(sparkAngle(d[29+i]),2); Serial.print(','); }
  Serial.print(d[25]); Serial.println(",NA");
}

bool sendActuatorFrame(uint8_t id, bool allOff = false) {
  if (!connected) {
    Serial.println("ACTUATOR TEST BLOCKED: CONNECT ECU FIRST (1)");
    sendWebStatus("ACTUATOR_FAILED_NOT_CONNECTED");
    return false;
  }

  uint8_t cmd[8];
  uint8_t len;
  if (allOff) {
    cmd[0] = 0x83; cmd[1] = 0x11; cmd[2] = 0xF1;
    cmd[3] = 0x30; cmd[4] = 0x3F; cmd[5] = 0x00;
    cmd[6] = sum8(cmd, 6); // checksum = F4
    len = 7;
  } else {
    cmd[0] = 0x84; cmd[1] = 0x11; cmd[2] = 0xF1;
    cmd[3] = 0x30; cmd[4] = id; cmd[5] = 0x07; cmd[6] = 0xFF;
    cmd[7] = sum8(cmd, 7);
    len = 8;
  }

  Serial.print(allOff ? "ACTUATOR ALL-OFF TX: " : "ACTUATOR TX ID 0x");
  if (!allOff) { hexByte(id); Serial.print(": "); }
  printData("", cmd, len);
  sendWebStatus(allOff ? "ACTUATOR_ALL_OFF_SENT" : "ACTUATOR_COMMAND_SENT_WAITING_ECU");

  bool ok = exchange(cmd, len, 0x70, 1300);
  if (ok) {
    Serial.println("ECU ACCEPTED IO-CONTROL REQUEST (VERIFY PHYSICAL ACTUATOR)");
    sendWebStatus(allOff ? "ACTUATOR_ALL_OFF_ACCEPTED" : "ACTUATOR_REQUEST_ACCEPTED");
  } else {
    Serial.println("ACTUATOR REQUEST NOT CONFIRMED BY ECU");
    sendWebStatus("ACTUATOR_REQUEST_NOT_CONFIRMED");
  }
  return ok;
}

void stopActuators() {
  actuatorActive = false;
  actuatorStopAt = 0;
  sendActuatorFrame(0x3F, true);
}

void runActuator(uint8_t id, const char *name) {
  if (!actuatorArmed || (int32_t)(millis() - actuatorArmUntil) >= 0) {
    actuatorArmed = false;
    Serial.println("ACTUATOR BLOCKED: SEND X TO ARM, THEN SELECT ONE ACTUATOR.");
    Serial.println("Arming lasts 20 seconds. Test duration is limited to 2.5 seconds.");
    sendWebStatus("ACTUATOR_BLOCKED_NOT_ARMED");
    return;
  }
  if (actuatorActive) {
    Serial.println("ACTUATOR BLOCKED: SEND Z TO STOP CURRENT TEST FIRST.");
    return;
  }
  Serial.print("ACTUATOR TEST: "); Serial.print(name); Serial.print(" (ID 0x"); hexByte(id); Serial.println(")");
  if (!connected) {
    sendActuatorFrame(id, false); // reports that connection is required; no frame sent
    actuatorArmed = false;
    return;
  }
  sendActuatorFrame(id, false);
  actuatorActive = true;
  actuatorStopAt = millis() + 2500UL;
  actuatorArmed = false; // one command per explicit arm
  Serial.println("AUTO STOP IN 2.5 SECONDS; SEND Z FOR IMMEDIATE ALL-OFF.");
}

void actuatorMenu() {
  Serial.println("\n=== SIM2K-34VR ACTUATOR TESTS (EXTERNAL C# IDS) ===");
  Serial.println("X = ARM ONE TEST (20 s)");
  Serial.println("L = LOW FAN  ID 1B | H = HIGH FAN ID 1A");
  Serial.println("P = FUEL PUMP ID 12 | M = MAIN RELAY ID 1C");
  Serial.println("Q = A/C RELAY ID 13 | G = CHECK-ENGINE LIGHT ID 10");
  Serial.println("K = CANISTER PURGE ID 20 | U = EXHAUST OUTPUT ID 14 (SOURCE LABEL)");
  Serial.println("S = STEPPER ID 24");
  Serial.println("J = COIL 1 ID 31 | W = COIL 2 ID 32");
  Serial.println("N/O/R/Y = INJECTORS 1/2/3/4 IDs 3A/3B/3C/3D");
  Serial.println("Z = ALL-OFF (available even if not armed)");
  Serial.println("WARNING: do not test coils/injectors with engine running; fuel/ignition risk.");
  Serial.println("Acknowledge only means ECU replied positively, not proof of physical movement.");
}

void handleActuatorCommand(char c) {
  if (c == 'X' || c == 'x') {
    actuatorArmed = true;
    actuatorArmUntil = millis() + 20000UL;
    Serial.println("ACTUATOR TEST ARMED FOR 20 SECONDS. SELECT ONE TEST.");
    return;
  }
  if (c == 'Z' || c == 'z') { stopActuators(); return; }
  switch (c) {
    case 'L': case 'l': runActuator(0x1B, "LOW FAN"); break;
    case 'H': case 'h': runActuator(0x1A, "HIGH FAN"); break;
    case 'P': case 'p': runActuator(0x12, "FUEL PUMP RELAY"); break;
    case 'M': case 'm': runActuator(0x1C, "MAIN RELAY"); break;
    case 'Q': case 'q': runActuator(0x13, "A/C RELAY"); break;
    case 'G': case 'g': runActuator(0x10, "CHECK-ENGINE LIGHT"); break;
    case 'K': case 'k': runActuator(0x20, "CANISTER PURGE"); break;
    case 'U': case 'u': runActuator(0x14, "EXHAUST OUTPUT (SOURCE LABEL)"); break;
    case 'S': case 's': runActuator(0x24, "STEPPER MOTOR"); break;
    case 'J': case 'j': runActuator(0x31, "IGNITION COIL 1"); break;
    case 'W': case 'w': runActuator(0x32, "IGNITION COIL 2"); break;
    case 'N': case 'n': runActuator(0x3A, "INJECTOR 1"); break;
    case 'O': case 'o': runActuator(0x3B, "INJECTOR 2"); break;
    case 'R': case 'r': runActuator(0x3C, "INJECTOR 3"); break;
    case 'Y': case 'y': runActuator(0x3D, "INJECTOR 4"); break;
    case 'T': case 't': actuatorMenu(); break;
    default: break;
  }
}

void menu() {
  Serial.println("\n=== YASIR K-LINE V51 - DUAL PROFILE (SIEMENS + BOSCH) ===");
  Serial.println("1 = CONNECT ECU");
  Serial.println("2 = READ DTC");
  Serial.println("3 = CLEAR DTC AND RECHECK (ONLY IF INTENDED)");
  Serial.println("4 = MENU");
  Serial.println("5 = READ FULL SIEMENS 21 01 HEX BLOCK");
  Serial.println("6 = READ 3 FULL BLOCKS");
  Serial.println("7 = PANEL ONCE (ORIGINAL + E-MAX REFERENCE)");
  Serial.println("8 = LIVE PANEL ON (1 SECOND, ORIGINAL + E-MAX REFERENCE)");
  Serial.println("9 = LIVE PANEL OFF");
  Serial.println("0 = SAVE BASELINE (AT CURRENT TESTER SETTINGS)");
  Serial.println("A = COMPARE CURRENT DATA TO SAVED BASELINE");
  Serial.println("B = SHOW ALL 93 C# par[] BYTES WITH KNOWN LABELS");
  Serial.println("C = START CSV LIVE OUTPUT (1 SECOND)");
  Serial.println("E = ENABLE E-MAX SIEMENS PRIDE PARAMETER PROFILE");
  Serial.println("D = DISABLE E-MAX SIEMENS PRIDE PARAMETER PROFILE");
  Serial.println("V = TOGGLE E-MAX PARAMETER PROFILE");
  Serial.println("9 = STOP LIVE PANEL OR CSV");
  Serial.println("T = ACTUATOR TEST MENU (EXTERNAL SIM2K-34VR IDS)");
  Serial.println("X = ARM ONE ACTUATOR TEST | Z = ALL-OFF");
  Serial.println("Siemens: V49 reliable UART pacing; adaptive speed only after 35/35 ACK.");
  Serial.println("I = LOCK STABLE 1600ms | F = ADAPTIVE 1600->1000ms on 35/35");
  Serial.println("Siemens V49 panels, DTC, actuators, and 35 E-MAX conversions remain available.");
  Serial.println("MAP formula was commented out in source; TPS is not identified.");
  Serial.println("NOTE: 0/A ARE READ-ONLY; 3 CLEARS FAULT CODES.");
}
void handleCommand(char c) {
  if (activeEcuProfile == PROFILE_BOSCH) { BoschFirmware::handleKey(c); return; }
  if (activeEcuProfile == PROFILE_SB13_CAN) { Sb13CanObd::handleKey(c); return; }
  if (c == '\r' || c == '\n' || c == ' ' || c == '\t') return;
  if (c == 'I' || c == 'i') {
    adaptiveMode = false;
    adaptivePeriodMs = BASELINE_PERIOD_MS;
    fullCycleAckStreak = 0;
    Serial.println("LIVE: RELIABLE STABLE 1600ms locked");
    sendWebStatus("LIVE_STABLE_1600");
    return;
  }
  if (c == 'F' || c == 'f') {
    adaptiveMode = true;
    adaptivePeriodMs = BASELINE_PERIOD_MS;
    fullCycleAckStreak = 0;
    Serial.println("LIVE: ADAPTIVE 1600ms baseline, accelerates after confirmed full cycles");
    sendWebStatus("LIVE_ADAPTIVE_ENABLED");
    return;
  }
  if (c == 'E' || c == 'e') {
    emaxPrideProfile = true;
    Serial.println("E-MAX SIEMENS PRIDE PARAMETER PROFILE ENABLED");
    return;
  }
  if (c == 'D' || c == 'd') {
    emaxPrideProfile = false;
    Serial.println("E-MAX SIEMENS PRIDE PARAMETER PROFILE DISABLED");
    return;
  }
  if (c == 'V' || c == 'v') {
    emaxPrideProfile = !emaxPrideProfile;
    Serial.print("E-MAX PROFILE: ");
    Serial.println(emaxPrideProfile ? "ON" : "OFF");
    return;
  }
  if (c == '8') { csvMode=false; livePanel=true; lastPanel=millis()-1000UL; Serial.println("LIVE PANEL STARTED"); }
  else if (c == '9') { csvMode=false; livePanel=false; Serial.println("LIVE PANEL STOPPED"); }
  else if (c == '1') { csvMode=false; connectECU(); }
  else if (c == '2') { csvMode=false; livePanel=false; readDTC(); }
  else if (c == '3') { csvMode=false; livePanel=false; clearDTC(); }
  else if (c == '4') menu();
  else if (c == '5') { csvMode=false; livePanel=false; readSiemensBlock(); }
  else if (c == '6') { csvMode=false; livePanel=false; sampleBlocks(); }
  else if (c == '7') { csvMode=false; livePanel=false; panel(); }
  else if (c == '0') { csvMode=false; livePanel=false; saveBaseline(); }
  else if (c == 'A' || c == 'a') { csvMode=false; livePanel=false; compareBaseline(); }
  else if (c == 'B' || c == 'b') { csvMode=false; livePanel=false; showAllForeignParameters(); }
  else if (c == 'C' || c == 'c') { livePanel=false; csvMode=true; lastPanel=millis()-1000UL; csvHeader(); }
  else handleActuatorCommand(c);
}

void selectEcuProfile(EcuProfile profile) {
  if (activeEcuProfile == PROFILE_SB13_CAN) Sb13CanObd::handleKey('0');
  if (activeEcuProfile == PROFILE_BOSCH) {
    BoschFirmware::disconnect();
  } else {
    // Do not abandon a Siemens test before its all-off command is sent.
    if (actuatorActive && connected) stopActuators();
    Serial3.end();
  }
  pinMode(PB10, OUTPUT);
  digitalWrite(PB10, HIGH);
  connected = false;
  livePanel = false;
  csvMode = false;
  actuatorActive = false;
  actuatorArmed = false;
  baselineReady = false;
  previousReady = false;
  waitingPrideAck = false;
  fullCycleAckStreak = 0;
  adaptivePeriodMs = BASELINE_PERIOD_MS;
  readFailuresInARow = 0;
  activeEcuProfile = profile;
  lastWebTelemetry = millis();
  if (profile == PROFILE_BOSCH) {
    Serial.println(F("ACTIVE ECU PROFILE: BOSCH ZAMYAD 7.9.7.1"));
    sendWebStatus("PROFILE_BOSCH_SELECTED");
  } else if (profile == PROFILE_SB13_CAN) {
    Serial.println(F("ACTIVE ECU PROFILE: SB13 GENERIC OBD-II CAN"));
    sendWebStatus("PROFILE_SB13_CAN_SELECTED");
  } else {
    Serial.println(F("ACTIVE ECU PROFILE: SIEMENS"));
    sendWebStatus("PROFILE_SIEMENS_SELECTED");
  }
}

void setup() {
  Serial.begin(115200);       // USB-TTL diagnostic console on PA9/PA10
  Serial2.begin(ESP_BAUD);    // ESP-01 UART on PA2/PA3
  Serial3.begin(K_BAUD);      // K-Line on PB10/PB11
  BoschFirmware::initialize();
  Sb13CanObd::initialize();
  delay(1200);
  menu();
  Serial2.println(F("{\"type\":\"status\",\"message\":\"STM32_READY\"}"));
}
void processEspLine() {
  espLine[espLineLen] = 0;

  if (strcmp(espLine, "!PROFILE:BOSCH") == 0) {
    selectEcuProfile(PROFILE_BOSCH);
    Serial2.println(F("{\"type\":\"ack\",\"command\":\"PROFILE:BOSCH\"}"));
    espLineLen = 0;
    return;
  }
  if (strcmp(espLine, "!PROFILE:SB13_CAN") == 0) {
    selectEcuProfile(PROFILE_SB13_CAN);
    Serial2.println(F("{\"type\":\"ack\",\"command\":\"PROFILE:SB13_CAN\"}"));
    espLineLen = 0;
    return;
  }
  if (strcmp(espLine, "!PROFILE:SIEMENS") == 0) {
    selectEcuProfile(PROFILE_SIEMENS);
    Serial2.println(F("{\"type\":\"ack\",\"command\":\"PROFILE:SIEMENS\"}"));
    espLineLen = 0;
    return;
  }

  // Android reports the number of distinct parameters received in each Siemens cycle.
  // Bosch uses a separate fixed-rate stream and does not participate in this ACK.
  if (activeEcuProfile == PROFILE_SIEMENS && espLineLen > 3 && espLine[0] == '@') {
    char *afterCycle = 0;
    unsigned long cycleNumber = strtoul(espLine + 1, &afterCycle, 10);
    if (afterCycle && *afterCycle == ',') {
      char *afterCount = 0;
      unsigned long receivedCount = strtoul(afterCycle + 1, &afterCount, 10);
      if (afterCount && *afterCount == 0 && receivedCount <= 35 &&
          cycleNumber <= 65535UL && waitingPrideAck &&
          (uint16_t)cycleNumber == ackExpectedCycle) {
        waitingPrideAck = false;
        if (receivedCount == 35) {
          ++successfulPrideAcks;
          if (fullCycleAckStreak < 255) ++fullCycleAckStreak;
          if (adaptiveMode && fullCycleAckStreak >= 4 &&
              adaptivePeriodMs > MIN_ADAPTIVE_PERIOD_MS) {
            adaptivePeriodMs = (adaptivePeriodMs > MIN_ADAPTIVE_PERIOD_MS + 150UL)
                                 ? adaptivePeriodMs - 150UL : MIN_ADAPTIVE_PERIOD_MS;
            fullCycleAckStreak = 0;
            Serial.print("LIVE: 35/35 complete, next period ms: ");
            Serial.println(adaptivePeriodMs);
            sendWebStatus("LIVE_SPEED_ADAPTED");
          }
        } else {
          ++incompletePrideAcks;
          fullCycleAckStreak = 0;
          adaptivePeriodMs = BASELINE_PERIOD_MS;
          Serial.print("LIVE: incomplete 35-parameter cycle: ");
          Serial.println(receivedCount);
          sendWebStatus("LIVE_INCOMPLETE_STABLE_FALLBACK");
        }
        espLineLen = 0;
        return;
      }
    }
    espLineLen = 0;
    return;
  }

  char command = 0;
  if (espLineLen == 1 && (espLine[0] == '1' || espLine[0] == '2' || espLine[0] == '3'))
    command = espLine[0];
  if (espLineLen == 2 && espLine[0] == '!') {
    char key = espLine[1];
    if (activeEcuProfile == PROFILE_BOSCH) {
      if (strchr("012346ABCDEFGHIJKLMNX?", key)) command = key;
    } else if (activeEcuProfile == PROFILE_SB13_CAN) {
      if (strchr("01236", key)) command = key;
    } else if (strchr("EDVIF1234567890ABCTXZLHPMQGKUSJWNORY", key)) {
      command = key;
    }
  }
  if (command) {
    Serial.print("PHONE COMMAND RECEIVED: "); Serial.println(command);
    Serial2.print("{\"type\":\"ack\",\"command\":\"");
    Serial2.print(command);
    Serial2.println("\"}");
    lastEspCommand = millis();
    handleCommand(command);
    lastWebTelemetry = millis();
  } else if (espLineLen) {
    Serial.print("ESP IGNORED LINE: "); Serial.println(espLine);
  }
  espLineLen = 0;
}

void loop() {
  while (Serial.available()) handleCommand((char)Serial.read());
  while (Serial2.available()) {
    char c = (char)Serial2.read();
    if (c == '\r' || c == '\n') { processEspLine(); continue; }
    if (espLineLen < sizeof(espLine) - 1) espLine[espLineLen++] = c;
    else espLineLen = 0;
  }
  if (activeEcuProfile == PROFILE_SIEMENS) {
    if (actuatorActive && (int32_t)(millis() - actuatorStopAt) >= 0) {
      Serial.println("ACTUATOR AUTO-STOP: SENDING ALL-OFF");
      stopActuators();
    }
    if (actuatorArmed && (int32_t)(millis() - actuatorArmUntil) >= 0) {
      actuatorArmed = false; Serial.println("ACTUATOR ARM EXPIRED");
    }
    if (csvMode && (uint32_t)(millis() - lastPanel) >= 1000UL) { lastPanel=millis(); csvSample(); }
    if (livePanel && (uint32_t)(millis() - lastPanel) >= 1000UL) { lastPanel=millis(); panel(); }

    // Keep V49's packet ACK pacing and stable rollback for the Siemens stream.
    uint32_t liveNow = millis();
    if (waitingPrideAck &&
        (uint32_t)(liveNow - lastPridePacketSentAt) >= ACK_WAIT_LIMIT_MS) {
      waitingPrideAck = false;
      ++incompletePrideAcks;
      adaptivePeriodMs = BASELINE_PERIOD_MS;
      fullCycleAckStreak = 0;
      sendWebStatus("LIVE_ACK_TIMEOUT_STABLE_FALLBACK");
    }
    if (connected && !livePanel && !csvMode && !waitingPrideAck &&
        (uint32_t)(liveNow - lastWebTelemetry) >= adaptivePeriodMs &&
        (uint32_t)(liveNow - lastEspCommand) >= 700UL) {
      lastWebTelemetry = liveNow;
      uint8_t d[93];
      if (fetchBlock(d)) {
        readFailuresInARow = 0;
        sendWebTelemetry(d);
        if (emaxPrideProfile) {
          ackExpectedCycle = emaxCycle;
          lastPridePacketSentAt = millis();
          waitingPrideAck = true;
        }
      } else {
        sendWebStatus("READ_FAILED");
        if (readFailuresInARow < 255) ++readFailuresInARow;
        if (readFailuresInARow >= 2) {
          fullCycleAckStreak = 0;
          adaptivePeriodMs = BASELINE_PERIOD_MS;
        }
      }
    }
  } else if (activeEcuProfile == PROFILE_SB13_CAN) {
    Sb13CanObd::poll();
  } else {
    BoschFirmware::poll();
  }
}
