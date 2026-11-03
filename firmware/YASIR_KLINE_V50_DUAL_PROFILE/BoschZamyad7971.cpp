#include <Arduino.h>
#include "BoschZamyad7971.h"

namespace BoschFirmware {
// Derived from E-MAX Zamyad Bosch 7.9.7.1 compiled profile (19 rows).
// Values are not proof of correctness until verified against real vehicle readings.
namespace BoschZamyad7971 {
static inline uint16_t wordLE(const uint8_t *d, size_t i) {
  return (uint16_t)d[i] | ((uint16_t)d[i+1] << 8);
}
static inline int8_t signed8(uint8_t v) { return (int8_t)v; }
static inline void printNumber(Stream &out, const char *label, float v, unsigned dec, const char *unit) {
  out.print(label); out.print(F(": ")); out.print(v, dec);
  if(unit && unit[0]) { out.print(' '); out.print(unit); }
  out.println();
}
// 19 PARAMETER ROWS from the E-MAX binary's Fill_Param_Table and Analyse_Param.
// Status bits are NOT decoded because the shared Func1 implementation is absent.
// d[0] is the first data byte AFTER the response service + local ID (61 01).
static inline bool printReadOnly(Stream &out, const uint8_t *d, size_t count) {
  if (!d || count < 54) {
    out.print(F("BOSCH 7.9.7.1: data too short; need 54 bytes, have "));
    out.println((unsigned)count);
    return false;
  }
  out.println(F("============== E-MAX / BOSCH ZAMYAD 7.9.7.1 =============="));
  out.println(F("SOURCE: Unit_Zamyad_Engine_Bosch_7971.o / 19 table rows"));
  out.println(F("STATUS: reference formulas only; do not assume values proven"));
  out.println(F("First d[0] = byte immediately AFTER 61 01"));
  printNumber(out, "01 Fault counter [raw]", d[0], 0, "");
  printNumber(out, "02 Coolant", d[2]*0.738f - 48.0f, 1, "C");
  printNumber(out, "03 Intake-air", d[3]*0.738f - 48.0f, 1, "C");
  printNumber(out, "04 Battery", d[4]*0.073f, 2, "V");
  printNumber(out, "05 Throttle position [LE16 tentative]", wordLE(d,7)*0.0243717f, 2, "%");
  printNumber(out, "06 Engine RPM [LE16 tentative]", wordLE(d,9)*0.25f, 0, "rpm");
  printNumber(out, "07 Spark advance [signed]", signed8(d[13])*0.75f, 2, "deg");
  out.print(F("08 Knock state: raw d[14]=0x"));
  if(d[14]<16)out.print('0'); out.println(d[14], HEX);
  out.print(F("09 Injection system state: raw d[14]=0x"));
  if(d[14]<16)out.print('0'); out.println(d[14], HEX);
  out.print(F("10 Fuel pump state: raw d[14]=0x"));
  if(d[14]<16)out.print('0'); out.println(d[14], HEX);
  out.print(F("11 Injection state: raw d[14]=0x"));
  if(d[14]<16)out.print('0'); out.println(d[14], HEX);
  out.print(F("12 Engine-load state: raw d[14]=0x"));
  if(d[14]<16)out.print('0'); out.println(d[14], HEX);
  out.println(F("   For 08..12, E-MAX calls shared IO::Func1; bit polarity NOT YET KNOWN"));
  printNumber(out, "13 Air pressure [source unit unspecified]",wordLE(d,20)*0.062f, 2, "");
  printNumber(out, "14 Injection time [source unit unspecified]",(wordLE(d,22)%7500)*0.0133f, 3, "");
  printNumber(out, "15 Target idle RPM", d[24]*10.0f, 0, "rpm");
  printNumber(out, "16 Stepper position", d[25], 0, "step");
  printNumber(out, "17 Intake-manifold MAP", wordLE(d,50)*0.039f, 2, "mBAR");
  printNumber(out, "18 Ignition coil charging time", wordLE(d,52)*0.001f, 3, "mSEC");
  printNumber(out, "19 Oxygen sensor",d[28]<=153 ? 249.0f+d[28]*4.89f : 750.7f+d[28]*4.88f, 1, "mVolts");
  out.println(F("===========================================================\n"));
  return true;
}

} // namespace

static const uint32_t K_BAUD = 10400;
static const uint8_t SOURCE_ADDR = 0xF1;      // tester address
static const uint8_t ECU_PHYSICAL_ADDR = 0x11;
static const uint8_t ECU_FUNCTIONAL_ADDR = 0x33;
static const uint32_t ECU_RESPONSE_MS = 1600;

static bool kwpSession = false;
static bool klineSerialStarted = false;
static uint8_t lastAddr = ECU_FUNCTIONAL_ADDR;
static uint8_t lastFrame[200];
static uint16_t lastFrameLen = 0;
static uint16_t lastPayloadLen = 0;
static uint8_t lastPayload[180];

static void sendBoschDtcJson();
static bool sendBoschParameters(const uint8_t *data, size_t count);
static void sendBoschStatus(const __FlashStringHelper *message);

static uint8_t checksum8(const uint8_t *p, size_t n) {
  uint8_t s = 0;
  for (size_t i = 0; i < n; ++i) s = (uint8_t)(s + p[i]);
  return s;
}
static void hexByte(uint8_t x) {
  if (x < 16) Serial.print('0');
  Serial.print(x, HEX);
}
static void dumpHex(const char *label, const uint8_t *p, size_t n) {
  Serial.print(label);
  for (size_t i = 0; i < n; ++i) {
    Serial.print(' ');
    hexByte(p[i]);
  }
  Serial.println();
}

// ISO 14230 (KWP2000) addressed response, both compact and extended length.
// Returns 0 for incomplete, -1 for malformed, or a positive full frame length.
static int frameBytes(const uint8_t *buf, size_t have) {
  if (have == 0) return 0;
  const uint8_t fmt = buf[0];
  if ((fmt & 0xC0) != 0x80 && (fmt & 0xC0) != 0xC0) return -1;
  const uint8_t shortPayload = fmt & 0x3F;
  if (shortPayload > 0) return 3 + shortPayload + 1;
  if (have < 4) return 0;
  if (buf[3] == 0) return -1;
  return 4 + buf[3] + 1;
}
static uint8_t serviceAt(const uint8_t *p) { return (p[0] & 0x3F) ? 3 : 4; }

// Collect response; handles own-message K-Line echo and discards bad checksums.
// A positive matching response is SUCCESS; even other frames appear in logs.
static bool waitForService(uint8_t expected,
                           const uint8_t *sent,
                           uint8_t sentLen,
                           uint32_t timeoutMs = ECU_RESPONSE_MS) {
  uint8_t stream[256];
  size_t count = 0;
  uint8_t raw[256];
  size_t rawCount = 0;
  bool success = false;
  bool sawNrc = false;
  bool sawAnyFrame = false;
  lastPayloadLen = 0;
  lastFrameLen = 0;
  const uint32_t started = millis();

  while ((uint32_t)(millis() - started) < timeoutMs) {
    while (Serial3.available()) {
      int ch = Serial3.read();
      if (ch < 0) break;
      const uint8_t b = (uint8_t)ch;
      if (rawCount < sizeof(raw)) raw[rawCount++] = b;
      if (count < sizeof(stream)) stream[count++] = b;
      else { Serial.println(F("WARN: RX BUFFER FULL - RESYNC")); count = 0; }
    }

    while (count) {
      const int packetLen = frameBytes(stream, count);
      if (packetLen < 0 || packetLen > (int)sizeof(stream)) {
        for (size_t j = 1; j < count; ++j) stream[j - 1] = stream[j];
        --count;
        continue;
      }
      if (packetLen == 0 || (size_t)packetLen > count) break;
      const uint16_t n = (uint16_t)packetLen;
      const bool checksumOk = checksum8(stream, n - 1) == stream[n - 1];
      if (checksumOk) {
        bool echo = n == sentLen && sentLen > 0;
        if (echo) for (uint8_t j = 0; j < sentLen; ++j)
          if (stream[j] != sent[j]) { echo = false; break; }
        if (!echo) {
          sawAnyFrame = true;
          dumpHex("RX FRAME:", stream, n);
          const uint8_t si = serviceAt(stream);
          if (n >= si + 2) {
            const uint8_t svc = stream[si];
            const bool addressedToTester = stream[1] == SOURCE_ADDR;
            if (svc == 0x7F && n >= si + 4) {
              sawNrc = true;
              Serial.print(F("NEGATIVE RESPONSE to service 0x"));
              hexByte(stream[si+1]);
              Serial.print(F("; NRC=0x"));
              hexByte(stream[si+2]);
              Serial.println();
            }
            // The E-MAX Bosch Zamyad Init code also explicitly accepts 61 03.
            // Only treat it as an initialization answer when its identifier is 03.
            const bool initLocal03 = expected == 0xC1 && svc == 0x61 &&
                                     n >= si + 4 && stream[si+1] == 0x03;
            if (addressedToTester && (svc == expected || initLocal03)) {
              if (initLocal03) Serial.println(F("E-MAX INIT CLUE MATCH: 61 03"));
              success = true;
              lastFrameLen = n < sizeof(lastFrame) ? n : sizeof(lastFrame);
              for (uint16_t k = 0; k < lastFrameLen; ++k) lastFrame[k] = stream[k];
              uint16_t useful = n - si - 2; // service at si, checksum at n-1
              if (useful > sizeof(lastPayload)) useful = sizeof(lastPayload);
              lastPayloadLen = useful;
              for (uint16_t k = 0; k < useful; ++k) lastPayload[k] = stream[si+1+k];
            }
          }
        } else {
          Serial.println(F("TX ECHO (ignored)"));
        }
      } else {
        Serial.println(F("RX frame checksum mismatch; resynchronizing"));
      }
      for (size_t j = n; j < count; ++j) stream[j-n] = stream[j];
      count -= n;
    }
    if (success) break;
    delay(1);
  }

  if (!success) {
    if (!rawCount) Serial.println(F("NO BYTES RECEIVED (timeout)"));
    else {
      dumpHex("ALL RX RAW:", raw, rawCount);
      if (!sawAnyFrame) Serial.println(F("No complete checksum-valid response frame."));
    }
    if (sawNrc) Serial.println(F("ECU responded, but refused the request."));
  }
  return success;
}

static void startKlineUart() {
  if (klineSerialStarted) Serial3.end();
  Serial3.begin(K_BAUD);  // default 8N1
  klineSerialStarted = true;
  delay(4);
}
static void stopKline() {
  if (klineSerialStarted) Serial3.end();
  klineSerialStarted = false;
  pinMode(PB10, OUTPUT);
  digitalWrite(PB10, HIGH); // recessive/idle on MC33290 TX
  kwpSession = false;
  lastPayloadLen = 0;
}
static void clearRx() {
  while (Serial3.available()) Serial3.read();
}

static void kwpSend(uint8_t target, uint8_t svc, uint8_t arg, bool hasArg,
                    uint8_t *out, uint8_t &outLen) {
  const uint8_t payLen = hasArg ? 2 : 1;
  // E-MAX Bosch Zamyad reads local IDs with PHYSICAL KWP header 0x82.
  // Prior lab accidentally used 0xC2 (functional addressing) for these reads,
  // which may have produced NRC 0x11 despite successful StartCommunication.
  // Preserve the already-confirmed functional StartCommunication 0xC1 33 F1 81 66.
  out[0] = (uint8_t)(((target == ECU_PHYSICAL_ADDR && svc == 0x21)
                         ? 0x80 : 0xC0) | payLen);
  out[1] = target;
  out[2] = SOURCE_ADDR;
  out[3] = svc;
  if (hasArg) out[4] = arg;
  outLen = (uint8_t)(payLen + 4);
  out[outLen - 1] = checksum8(out, outLen - 1);
}
static bool requestService(uint8_t service, uint8_t arg, bool hasArg,
                           uint8_t expected, uint32_t timeoutMs = ECU_RESPONSE_MS) {
  if (!klineSerialStarted) startKlineUart();
  uint8_t tx[8]; uint8_t n = 0;
  kwpSend(ECU_PHYSICAL_ADDR, service, arg, hasArg, tx, n);
  clearRx();
  dumpHex("TX:", tx, n);
  Serial3.write(tx, n);
  Serial3.flush();
  bool ok = waitForService(expected, tx, n, timeoutMs);
  Serial.println(ok ? F("POSITIVE RESPONSE RECEIVED") : F("NO EXPECTED POSITIVE RESPONSE"));
  return ok;
}

static void fastInit(uint8_t target) {
  stopKline();
  lastAddr = target;
  Serial.println();
  Serial.print(F("FAST INIT 25/25 ms; target=0x")); hexByte(target); Serial.println();
  pinMode(PB10, OUTPUT);
  digitalWrite(PB10, HIGH); delay(320);
  digitalWrite(PB10, LOW); delay(25);
  digitalWrite(PB10, HIGH); delay(25);
  startKlineUart();
  uint8_t tx[8]; uint8_t n = 0;
  kwpSend(target, 0x81, 0, false, tx, n); // StartCommunication
  clearRx();
  dumpHex("TX START COMM:", tx, n);
  Serial3.write(tx, n); Serial3.flush();
  const bool startReply = waitForService(0xC1, tx, n, 1700);
  kwpSession = startReply;
  if (startReply) Serial.println(F("RESULT: POSITIVE BOSCH INIT RESPONSE SEEN (C1 or 61 03)"));
  else Serial.println(F("RESULT: NOT VERIFIED. Try another init mode."));
}

// Standard 5-baud ISO 9141 / KWP wake-up: 0x33 (functional) or 0x11 (physical).
// Log the key-byte handshake; do not claim connected until ECU ACK is seen.
static void slow5BaudInit(uint8_t target) {
  stopKline();
  lastAddr = target;
  Serial.println();
  Serial.print(F("5-BAUD INIT (200ms/bit) address 0x"));hexByte(target);Serial.println();
  pinMode(PB10, OUTPUT);
  digitalWrite(PB10, HIGH); delay(350);
  digitalWrite(PB10, LOW); delay(200); // start bit
  for (uint8_t i = 0; i < 8; ++i) {
    digitalWrite(PB10, (target & (1U << i)) ? HIGH : LOW);
    delay(200);
  }
  digitalWrite(PB10, HIGH);  // stop bit
  // Establish 10400 8N1 receive while ECU sends 0x55 + key bytes.
  startKlineUart();
  uint8_t initial[20]; uint8_t used = 0;
  const uint32_t t0 = millis();
  while ((uint32_t)(millis() - t0) < 1500 && used < sizeof(initial)) {
    while (Serial3.available() && used < sizeof(initial)) {
      int c = Serial3.read(); if (c >= 0) initial[used++] = (uint8_t)c;
    }
    if (used >= 3) break;
    delay(1);
  }
  if (used) dumpHex("INIT RAW:", initial, used);
  if (used < 3 || initial[0] != 0x55) {
    Serial.println(F("RESULT: NO VALID 0x55/KEY1/KEY2 SYNC. NOT CONNECTED."));
    return;
  }
  const uint8_t invKey2 = (uint8_t)~initial[2];
  delay(30);
  Serial.print(F("TX inverted KEY2: ")); hexByte(invKey2); Serial.println();
  Serial3.write(invKey2); Serial3.flush();
  uint8_t ack[12]; uint8_t ackN = 0;
  const uint32_t t1 = millis();
  while ((uint32_t)(millis() - t1) < 700 && ackN < sizeof(ack)) {
    while (Serial3.available() && ackN < sizeof(ack)) {
      int c=Serial3.read();if(c>=0)ack[ackN++]=(uint8_t)c;
    }
    // one byte may be local K-line echo; allow another byte for actual ECU ACK.
    if (ackN >= 2) break;
    delay(1);
  }
  if (ackN) dumpHex("5-BAUD ACK RAW:", ack, ackN);
  const uint8_t expected = (uint8_t)~target;
  bool gotAck = false;
  for (uint8_t i=0;i<ackN;++i) if (ack[i] == expected) gotAck=true;
  kwpSession = gotAck;
  Serial.println(gotAck ? F("RESULT: 5-BAUD KEY HANDSHAKE OK (CONNECTED)") :
                        F("RESULT: KEYS SEEN, BUT ECU ADDRESS ACK NOT VERIFIED"));
}

static void localIdentifierRead(uint8_t id) {
  Serial.println();
  Serial.print(F("READ DATA BY LOCAL ID 0x")); hexByte(id); Serial.println();
  if (!kwpSession) {
    Serial.println(F("No established session yet. Connect with 1 first."));
    return;
  }
  // For Zamyad Bosch 7.9.7.1 the E-MAX reference has target 0x11.
  // Positive response for 21 03 is 61 03; for 21 01 is 61 01.
  const bool positive = requestService(0x21, id, true, 0x61, 1800);
  if (!positive) return;
  if (lastFrameLen) {
    const uint8_t si = serviceAt(lastFrame);
    if (lastFrameLen > si+2) {
      const uint8_t returnedId = lastFrame[si+1];
      Serial.print(F("RESPONSE LOCAL ID: 0x")); hexByte(returnedId); Serial.println();
      if (returnedId != id) {
        Serial.println(F("WARNING: response is 61 but Local ID does not match."));
        return;
      }
      const uint8_t *frameData = &lastFrame[si+2];
      uint16_t dataLen = (uint16_t)(lastFrameLen - si - 3);
      dumpHex("ECU PARAMETER BYTES:", frameData, dataLen);
      Serial.print(F("DATA COUNT: ")); Serial.println(dataLen);
      if (id == 0x01) sendBoschParameters(frameData, dataLen);
      if (id == 0x01) {
        Serial.println(F("BOSCH 7.9.7.1: decoding 19 E-MAX rows if >=54 data bytes."));
        BoschZamyad7971::printReadOnly(Serial, frameData, dataLen);
        if (dataLen >= 54) {
          Serial.println(F("PROFILE SUMMARY: 19 E-MAX rows; 5 status flags share d[14] (undecoded)."));
          Serial.println(F("SOURCE: Bosch Zamyad 7.9.7.1, not Siemens. Check RPM/Battery/Coolant first."));
        }
      }
    }
  }
}

// Three manual read-only snapshots; a useful baseline for byte stability.
// Each request is spaced to avoid turning this test into a high-rate poller.
static void readThreeSnapshots() {
  if (!kwpSession) {
    Serial.println(F("INIT FIRST: Press 1. Session not established."));
    return;
  }
  for (uint8_t i = 1; i <= 3; ++i) {
    Serial.println();
    Serial.print(F("================ SNAPSHOT ")); Serial.print(i);
    Serial.println(F(" / 3 ================"));
    localIdentifierRead(0x01);
    if (i < 3) delay(650);
  }
}

// Verified passive capture from Parsian Sanat tester (twice):
// TX 84 11 F1 18 00 FF 00 9D
// RX 8E F1 11 58 04 01 05 E4 03 00 61 03 04 61 01 18 E1 9C
// Read-only: do not guess diagnostic trouble-code encoding.
static void readDTCs() {
  if (!kwpSession) {
    Serial.println(F("First press 1 to connect."));
    sendBoschStatus(F("BOSCH_ECU_CONNECTION_FAILED"));
    return;
  }
  const uint8_t tx[] = {0x84, 0x11, 0xF1, 0x18, 0x00, 0xFF, 0x00, 0x9D};
  clearRx();
  dumpHex("TX VERIFIED READ DTC:", tx, sizeof(tx));
  Serial3.write(tx, sizeof(tx));
  Serial3.flush();
  if (!waitForService(0x58, tx, sizeof(tx), 2600)) {
    Serial.println(F("No confirmed DTC response. No fault count inferred."));
    Serial.println(F("If session expired, press 1 then 2 again."));
    return;
  }
  dumpHex("DTC PAYLOAD AFTER 58:", lastPayload, lastPayloadLen);
  if (lastPayloadLen >= 1) {
    Serial.print(F("Reported DTC count candidate: "));
    Serial.println(lastPayload[0]);
    Serial.println(F("Count is consistent with capture, but ECU layout is not fully verified."));
  }
  if (lastPayloadLen > 1) {
    dumpHex("RAW DTC RECORD BYTES:", lastPayload + 1, lastPayloadLen - 1);
    // Three bytes per record, as observed on this Bosch ECU.
    // Names are empirical matches against Parsian Sanat; not standardized P-codes.
    const uint8_t count = lastPayload[0];
    const uint8_t availableRecords = (lastPayloadLen - 1) / 3;
    if ((lastPayloadLen - 1) % 3 != 0 || count != availableRecords) {
      Serial.println(F("DTC payload length/count mismatch; names not decoded."));
    } else {
      for (uint8_t i = 0; i < availableRecords; ++i) {
        const uint8_t *r = lastPayload + 1 + (uint16_t)i * 3;
        Serial.print(F("DTC ")); Serial.print(i + 1); Serial.print(F(": "));
        if (r[0] == 0x01 && r[1] == 0x18 && r[2] == 0xE1) {
          Serial.println(F("CTS Fail - Coolant Temperature Sensor Fault"));
          Serial.println(F("FA (Latin): Khataye sensor damaye ab"));
        } else if (r[0] == 0x01 && r[1] == 0x05 && r[2] == 0xE4) {
          Serial.println(F("MAP Fail - Manifold Absolute Pressure Sensor Fault"));
          Serial.println(F("FA (Latin): Khataye sensor feshar manifold"));
        } else {
          Serial.print(F("Unknown raw: "));
          for (uint8_t j = 0; j < 3; ++j) {
            if (r[j] < 16) Serial.print('0');
            Serial.print(r[j], HEX);
            if (j < 2) Serial.print(' ');
          }
          Serial.println();
        }
      }
    }
  }
  Serial.println(F("READ FINISHED: no erase command transmitted."));
  sendBoschDtcJson();
  sendBoschStatus(F("BOSCH_DTC_READ_FINISHED_RAW_CODES"));
}

// Captured from Parsian Sanat: TX 83 11 F1 14 FF 00 98
// ECU replied: 83 F1 11 54 FF 00 D8
// A positive 54 confirms acceptance, not that all faults have disappeared.
static bool clearDTCsConfirmed() {
  if (!kwpSession) {
    Serial.println(F("First press 1 to connect."));
    return false;
  }
  const uint8_t tx[] = {0x83, 0x11, 0xF1, 0x14, 0xFF, 0x00, 0x98};
  clearRx();
  dumpHex("TX VERIFIED CLEAR DTC:", tx, sizeof(tx));
  Serial3.write(tx, sizeof(tx));
  Serial3.flush();
  if (!waitForService(0x54, tx, sizeof(tx), 2600)) {
    Serial.println(F("CLEAR NOT CONFIRMED. Do not assume faults were erased."));
    return false;
  }
  dumpHex("CLEAR RESPONSE PAYLOAD AFTER 54:", lastPayload, lastPayloadLen);
  if (lastPayloadLen != 2 || lastPayload[0] != 0xFF || lastPayload[1] != 0x00) {
    Serial.println(F("Unexpected 54 payload: CLEAR NOT VERIFIED."));
    return false;
  }
  Serial.println(F("ECU ACCEPTED CLEAR (54 FF 00). Reading DTCs again..."));
  delay(350);
  readDTCs();
  Serial.println(F("NOTE: An active fault may immediately reappear."));
  return true;
}


// Verified on a passive capture of the Parsian tester, ECU address 0x11:
// ON:  84 11 F1 30 10 07 FF CC  -> 84 F1 11 70 10 07 FF 0C
// OFF: 84 11 F1 30 10 07 00 CD  -> 84 F1 11 70 10 07 00 0D
// MIL (0x10) keeps its dedicated 5-second test. The other 14 actuator IDs
// are implemented in the E-MAX actuator submenu below.
static bool milControl(bool turnOn) {
  if (!kwpSession || !klineSerialStarted) {
    Serial.println(F("MIL: first connect using 1."));
    return false;
  }
  const uint8_t tx[] = {0x84,0x11,0xF1,0x30,0x10,0x07,
                        (uint8_t)(turnOn ? 0xFF : 0x00),
                        (uint8_t)(turnOn ? 0xCC : 0xCD)};
  clearRx();
  dumpHex(turnOn ? "TX MIL ON:" : "TX MIL OFF:", tx, sizeof(tx));
  Serial3.write(tx, sizeof(tx));
  Serial3.flush();
  if (!waitForService(0x70, tx, sizeof(tx), 850)) {
    Serial.println(F("MIL: no positive response."));
    sendBoschStatus(turnOn ? F("BOSCH_MIL_ON_NOT_CONFIRMED") : F("BOSCH_MIL_OFF_NOT_CONFIRMED"));
    return false;
  }
  // Verify the full positive response payload, not just the service byte.
  if (lastPayloadLen != 3 || lastPayload[0] != 0x10 ||
      lastPayload[1] != 0x07 ||
      lastPayload[2] != (turnOn ? 0xFF : 0x00)) {
    Serial.println(F("MIL: response payload mismatch."));
    return false;
  }
  Serial.println(turnOn ? F("MIL ON accepted") : F("MIL OFF accepted"));
  sendBoschStatus(turnOn ? F("BOSCH_MIL_ON_ACCEPTED") : F("BOSCH_MIL_OFF_ACCEPTED"));
  return true;
}

static void milTest() {
  if (!kwpSession) {
    Serial.println(F("First press 1 to connect."));
    sendBoschStatus(F("BOSCH_ECU_CONNECTION_FAILED"));
    return;
  }
  Serial.println(F("MIL TEST: ignition ON, engine OFF only."));
  Serial.println(F("MIL ON for 5 seconds maximum; automatic OFF follows."));
  // Even if ON is not confirmed, always send OFF to avoid leaving a lamp commanded.
  const bool started = milControl(true);
  if (started) {
    const uint32_t t0 = millis();
    while ((uint32_t)(millis() - t0) < 5000) {
      delay(10);
    }
  }
  const bool stopped = milControl(false);
  if (!stopped) {
    Serial.println(F("WARNING: MIL OFF NOT CONFIRMED. Switch ignition OFF and inspect."));
  }
  Serial.println(F("MIL TEST FINISHED."));
  sendBoschStatus(stopped ? F("BOSCH_MIL_TEST_FINISHED_OFF_CONFIRMED") : F("BOSCH_MIL_OFF_NOT_CONFIRMED_SWITCH_IGNITION_OFF"));
}


// -----------------------------------------------------------------------------
// ACTUATOR TESTS extracted from E-MAX 2023 APK:
// assets/internal/PKG_Zamyad.lib + libPKG_Zamyad.a
// Exact profile: Zamyad Engine Petrol Bosch 7.9.7.1
//
// Each actuator has two captured 8-byte KWP frames:
// ON  = 84 11 F1 30 ID 07 FF checksum
// OFF = 84 11 F1 30 ID 07 00 checksum
// Checksum = additive sum of the first seven bytes (modulo 256).
//
// This implementation uses a conservative 2-second ON window, an X key for
// early stop, a direct selection, and always attempts OFF after the ON attempt.
// The APK's own UI allowed a longer user-controlled window (up to ~10 seconds).
// -----------------------------------------------------------------------------

struct ActuatorDef {
  char key;
  uint8_t id;
  const char *name;
  const char *condition;
  uint16_t onTimeMs;
};

static const ActuatorDef ACTUATORS[] = {
  {'A', 0x12, "Fuel pump status",
   "Ignition ON, engine OFF. Check for fuel leaks and keep ignition sources away.", 6000},
  {'B', 0x39, "Injector 1 - idle",
   "Engine must be stable at idle, transmission neutral, parking brake set, ventilated area.", 1200},
  {'C', 0x41, "Injector 1 - engine OFF",
   "Engine OFF, ignition ON. Do not crank during this test.", 1200},
  {'D', 0x3C, "Injector 2 - idle",
   "Engine must be stable at idle, transmission neutral, parking brake set, ventilated area.", 1200},
  {'E', 0x44, "Injector 2 - engine OFF",
   "Engine OFF, ignition ON. Do not crank during this test.", 1200},
  {'F', 0x3A, "Injector 3 - idle",
   "Engine must be stable at idle, transmission neutral, parking brake set, ventilated area.", 1200},
  {'G', 0x42, "Injector 3 - engine OFF",
   "Engine OFF, ignition ON. Do not crank during this test.", 1200},
  {'H', 0x3B, "Injector 4 - idle",
   "Engine must be stable at idle, transmission neutral, parking brake set, ventilated area.", 1200},
  {'I', 0x43, "Injector 4 - engine OFF",
   "Engine OFF, ignition ON. Do not crank during this test.", 1200},
  {'J', 0x11, "Oxygen sensor heater",
   "Ignition ON, engine OFF. Exhaust components may be hot; do not touch them.", 6000},
  {'K', 0x23, "Stepper motor",
   "Ignition ON, engine OFF. Keep hands clear of throttle and moving linkages.", 1500},
  {'L', 0x31, "Ignition coil 1-4",
   "Engine OFF. Use a proper grounded spark tester; never touch HT leads or coil outputs.", 700},
  {'M', 0x33, "Ignition coil 2-3",
   "Engine OFF. Use a proper grounded spark tester; never touch HT leads or coil outputs.", 700},
  {'N', 0x13, "Ventilation system relay",
   "Engine OFF. Keep clear of cooling fans, belts, and any A/C compressor movement.", 6000}
};

// Keep ActuatorDef out of function signatures: Arduino IDE 1.8.x may
// auto-generate prototypes before the struct declaration.
static bool actuatorControl(uint8_t id, uint8_t state) {
  if (!kwpSession || !klineSerialStarted) {
    Serial.println(F("ACTUATOR: no active KWP session. Connect with key 1 first."));
    return false;
  }

  uint8_t tx[8] = {0x84, 0x11, 0xF1, 0x30, id, 0x07, state, 0x00};
  tx[7] = checksum8(tx, 7);

  clearRx();
  dumpHex(state == 0xFF ? "TX ACTUATOR ON:" : "TX ACTUATOR OFF:", tx, sizeof(tx));
  Serial3.write(tx, sizeof(tx));
  Serial3.flush();

  if (!waitForService(0x70, tx, sizeof(tx), 2000)) {
    Serial.println(state == 0xFF
      ? F("ACTUATOR ON: no positive 70 response.")
      : F("ACTUATOR OFF: no positive 70 response."));
    sendBoschStatus(state == 0xFF ? F("BOSCH_ACTUATOR_ON_NOT_CONFIRMED") : F("BOSCH_ACTUATOR_OFF_NOT_CONFIRMED"));
    return false;
  }

  if (lastPayloadLen != 3 ||
      lastPayload[0] != id ||
      lastPayload[1] != 0x07 ||
      lastPayload[2] != state) {
    Serial.println(F("ACTUATOR: positive service received, but echoed payload did not match."));
    dumpHex("ACTUATOR RESPONSE PAYLOAD:", lastPayload, lastPayloadLen);
    return false;
  }

  Serial.println(state == 0xFF
    ? F("ACTUATOR ON ACCEPTED (70 ID 07 FF).")
    : F("ACTUATOR OFF ACCEPTED (70 ID 07 00)."));
  sendBoschStatus(state == 0xFF ? F("BOSCH_ACTUATOR_ON_ACCEPTED") : F("BOSCH_ACTUATOR_OFF_ACCEPTED"));
  return true;
}

static void waitActuatorWindow(uint16_t durationMs) {
  Serial.println(F("Actuator is commanded ON. Send X to stop early."));
  const uint32_t started = millis();
  while ((uint32_t)(millis() - started) < durationMs) {
    while (Serial.available()) {
      int c = Serial.read();
      if (c < 0) break;
      char key = (char)c;
      if (key == 'x' || key == 'X' || key == 's' || key == 'S') {
        Serial.println(F("Early stop requested."));
        sendBoschStatus(F("BOSCH_ACTUATOR_EARLY_STOP_REQUESTED"));
        return;
      }
    }
    static bool phoneBang = false;
    while (Serial2.available()) {
      char key = (char)Serial2.read();
      if (key == '!') { phoneBang = true; continue; }
      if (key == '\r' || key == '\n') { phoneBang = false; continue; }
      if (phoneBang && (key == 'x' || key == 'X')) {
        phoneBang = false;
        Serial.println(F("Phone early-stop requested."));
        sendBoschStatus(F("BOSCH_ACTUATOR_EARLY_STOP_REQUESTED"));
        return;
      }
      phoneBang = false;
    }
    delay(5);
  }
  Serial.println(F("Maximum ON window reached; sending OFF."));
}

static void runActuatorTest(uint8_t id, const char *name,
                            const char *condition, uint16_t onTimeMs) {
  if (!kwpSession || !klineSerialStarted) {
    Serial.println(F("ACTUATOR: press 1 and establish ECU session first."));
    sendBoschStatus(F("BOSCH_ECU_CONNECTION_FAILED"));
    return;
  }

  Serial.println();
  Serial.println(F("========== ACTUATOR TEST START =========="));
  Serial.print(F("ACTUATOR: "));
  Serial.println(name);
  Serial.print(F("SAFETY: "));
  Serial.println(condition);
  Serial.print(F("ON window: "));
  Serial.print(onTimeMs);
  Serial.println(F(" ms. Starting immediately; no Y required."));

  const bool onAccepted = actuatorControl(id, 0xFF);
  if (onAccepted) {
    waitActuatorWindow(onTimeMs);
  } else {
    Serial.println(F("ON was not confirmed; attempting OFF as a safety measure."));
  }

  // Always attempt OFF, including when ON did not receive a positive response.
  const bool offAccepted = actuatorControl(id, 0x00);
  if (!offAccepted) {
    Serial.println(F("WARNING: OFF NOT CONFIRMED. Switch ignition OFF and inspect the vehicle."));
  } else {
    Serial.println(F("Actuator OFF confirmed."));
  }
  Serial.println(F("=========== ACTUATOR TEST END ==========="));
  if (!offAccepted) sendBoschStatus(F("BOSCH_ACTUATOR_OFF_NOT_CONFIRMED_SWITCH_IGNITION_OFF"));
  else if (!onAccepted) sendBoschStatus(F("BOSCH_ACTUATOR_ON_NOT_CONFIRMED_OFF_CONFIRMED"));
  else sendBoschStatus(F("BOSCH_ACTUATOR_TEST_FINISHED_OFF_CONFIRMED"));
}

// Direct main-menu dispatch for actuator keys A-N.
// Key 4 remains reserved for the dedicated, already-tested MIL test.
static bool runActuatorByKey(char key) {
  if (key >= 'a' && key <= 'n') key = (char)(key - 'a' + 'A');

  for (size_t i = 0; i < sizeof(ACTUATORS) / sizeof(ACTUATORS[0]); ++i) {
    if (key == ACTUATORS[i].key) {
      runActuatorTest(ACTUATORS[i].id, ACTUATORS[i].name,
                      ACTUATORS[i].condition, ACTUATORS[i].onTimeMs);
      return true;
    }
  }
  return false;
}


static void printMenu() {
  Serial.println();
  Serial.println(F("=== BOSCH ZAMYAD 7.9.7.1 / V14.3 MAIN MENU ==="));
  Serial.println(F("1 = CONNECT: FAST INIT physical 0x11"));
  Serial.println(F("2 = READ ECU FAULTS"));
  Serial.println(F("3 = CLEAR ECU FAULTS IMMEDIATELY (NO Y)"));
  Serial.println(F("4 = MIL / CHECK-ENGINE LAMP TEST (5s MAX, AUTO OFF)"));
  Serial.println(F("6 = READ 19 LIVE PARAMETERS"));
  Serial.println(F("--- ACTUATOR TESTS (select directly with a letter) ---"));
  Serial.println(F("A = Fuel pump"));
  Serial.println(F("B = Injector 1 - idle"));
  Serial.println(F("C = Injector 1 - engine OFF"));
  Serial.println(F("D = Injector 2 - idle"));
  Serial.println(F("E = Injector 2 - engine OFF"));
  Serial.println(F("F = Injector 3 - idle"));
  Serial.println(F("G = Injector 3 - engine OFF"));
  Serial.println(F("H = Injector 4 - idle"));
  Serial.println(F("I = Injector 4 - engine OFF"));
  Serial.println(F("J = Oxygen sensor heater"));
  Serial.println(F("K = Stepper motor"));
  Serial.println(F("L = Ignition coil 1-4"));
  Serial.println(F("M = Ignition coil 2-3"));
  Serial.println(F("N = Ventilation system relay"));
  Serial.println(F("0 = DISCONNECT  |  ? = SHOW THIS MENU"));
  Serial.println(F("Actuator letters start immediately; no Y confirmation."));
  Serial.println(F("Send X during ON to stop early; durations are actuator-specific."));
  Serial.println(F("Serial 115200; K-Line Serial3 10400 PB10/PB11"));
}

static void sendBoschStatus(const __FlashStringHelper *message) {
  Serial2.print(F("{\"type\":\"status\",\"message\":\""));
  Serial2.print(message);
  Serial2.println(F("\"}"));
}
static void sendBoschDtcJson() {
  Serial2.print(F("{\"type\":\"dtc\",\"profile\":\"bosch_zamyad_7971\",\"count\":"));
  uint8_t count = lastPayloadLen ? lastPayload[0] : 0;
  Serial2.print(count);
  Serial2.print(F(",\"codes\":["));
  uint8_t emitted = 0;
  uint8_t available = lastPayloadLen > 1 ? (uint8_t)((lastPayloadLen - 1) / 3) : 0;
  if (count > available) count = available;
  for (uint8_t i = 0; i < count; ++i) {
    const uint8_t *r = lastPayload + 1 + (uint16_t)i * 3;
    if (emitted++) Serial2.print(',');
    Serial2.print(F("{\"code\":\""));
    for (uint8_t j=0;j<3;++j) { if(r[j]<16)Serial2.print('0'); Serial2.print(r[j],HEX); }
    Serial2.print(F("\",\"status\":\""));
    if(r[2]<16)Serial2.print('0'); Serial2.print(r[2],HEX);
    Serial2.print(F("\"}"));
  }
  Serial2.println(F("]}"));
}
static bool sendBoschParameterRow(const char *id, const char *name, float value,
                                  uint8_t decimals, const char *unit, const char *status,
                                  bool &first) {
  if (!first) Serial2.print(',');
  first = false;
  Serial2.print(F("{\"id\":\"")); Serial2.print(id);
  Serial2.print(F("\",\"name\":\"")); Serial2.print(name);
  Serial2.print(F("\",\"value\":")); Serial2.print(value, decimals);
  Serial2.print(F(",\"unit\":\"")); Serial2.print(unit);
  Serial2.print(F("\",\"status\":\"")); Serial2.print(status);
  Serial2.print(F("\"}"));
  return true;
}
static bool sendBoschParameters(const uint8_t *d, size_t count) {
  if (!d || count < 54) {
    sendBoschStatus(F("BOSCH_PARAMETER_READ_FAILED"));
    return false;
  }
  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"bosch_zamyad_7971\",\"connected\":true,\"parameters\":["));
  bool first = true;
  const char *ref = "فرمول مرجع E-MAX؛ با دیاگ خودرو تطبیق شود";
  const char *raw = "خام؛ رمزگشایی بیت‌ها تأیید نشده";
  sendBoschParameterRow("bosch_zamyad_fault_count","شمارنده خطا",d[0],0,"",raw,first);
  sendBoschParameterRow("bosch_zamyad_coolant","دمای آب",d[2]*0.738f-48.0f,1,"°C",ref,first);
  sendBoschParameterRow("bosch_zamyad_intake_air","دمای هوای ورودی",d[3]*0.738f-48.0f,1,"°C",ref,first);
  sendBoschParameterRow("bosch_zamyad_battery","ولتاژ باتری",d[4]*0.073f,2,"V",ref,first);
  sendBoschParameterRow("bosch_zamyad_tps","موقعیت دریچه گاز",BoschZamyad7971::wordLE(d,7)*0.0243717f,2,"%",ref,first);
  sendBoschParameterRow("bosch_zamyad_rpm","دور موتور",BoschZamyad7971::wordLE(d,9)*0.25f,0,"rpm",ref,first);
  sendBoschParameterRow("bosch_zamyad_spark_advance","آوانس جرقه",BoschZamyad7971::signed8(d[13])*0.75f,2,"°",ref,first);
  sendBoschParameterRow("bosch_zamyad_knock_state","وضعیت ناک",d[14],0,"raw",raw,first);
  sendBoschParameterRow("bosch_zamyad_injection_system_state","وضعیت سیستم تزریق",d[14],0,"raw",raw,first);
  sendBoschParameterRow("bosch_zamyad_fuel_pump_state","وضعیت پمپ سوخت",d[14],0,"raw",raw,first);
  sendBoschParameterRow("bosch_zamyad_injection_state","وضعیت تزریق",d[14],0,"raw",raw,first);
  sendBoschParameterRow("bosch_zamyad_engine_load_state","وضعیت بار موتور",d[14],0,"raw",raw,first);
  sendBoschParameterRow("bosch_zamyad_air_pressure","فشار هوا",BoschZamyad7971::wordLE(d,20)*0.062f,2,"",ref,first);
  sendBoschParameterRow("bosch_zamyad_injection_time","زمان پاشش",(BoschZamyad7971::wordLE(d,22)%7500)*0.0133f,3,"",ref,first);
  sendBoschParameterRow("bosch_zamyad_target_idle_rpm","دور آرام هدف",d[24]*10.0f,0,"rpm",ref,first);
  sendBoschParameterRow("bosch_zamyad_stepper_position","موقعیت استپر",d[25],0,"step",ref,first);
  sendBoschParameterRow("bosch_zamyad_map","فشار منیفولد",BoschZamyad7971::wordLE(d,50)*0.039f,2,"mbar",ref,first);
  sendBoschParameterRow("bosch_zamyad_coil_charge_time","زمان شارژ کویل",BoschZamyad7971::wordLE(d,52)*0.001f,3,"ms",ref,first);
  sendBoschParameterRow("bosch_zamyad_o2_voltage","ولتاژ سنسور اکسیژن",d[28]<=153 ? 249.0f+d[28]*4.89f : 750.7f+d[28]*4.88f,1,"mV",ref,first);
  Serial2.println(F("]}"));
  return true;
}
static bool readLiveParameters() {
  if (!kwpSession) { sendBoschStatus(F("BOSCH_ECU_CONNECTION_FAILED")); return false; }
  if (!requestService(0x21,0x01,true,0x61,1800)) {
    sendBoschStatus(F("BOSCH_PARAMETER_READ_FAILED")); return false;
  }
  const uint8_t si=serviceAt(lastFrame);
  if (lastFrameLen < si+3 || lastFrame[si+1] != 0x01) {
    sendBoschStatus(F("BOSCH_PARAMETER_READ_FAILED")); return false;
  }
  const uint16_t len=(uint16_t)(lastFrameLen-si-3);
  return sendBoschParameters(lastFrame+si+2,len);
}

static uint32_t lastBoschPoll=0;
void initialize() { stopKline(); }
void disconnect() { stopKline(); kwpSession=false; }
bool isConnected() { return kwpSession; }
void handleKey(char key) {
  if (key>='a' && key<='n') key=(char)(key-'a'+'A');
  switch (key) {
    case '1': fastInit(ECU_PHYSICAL_ADDR); sendBoschStatus(kwpSession ? F("BOSCH_ECU_CONNECTED") : F("BOSCH_ECU_CONNECTION_FAILED")); break;
    case '2': readDTCs(); break;
    case '3':
      if (clearDTCsConfirmed()) sendBoschStatus(F("BOSCH_DTC_CLEAR_ACCEPTED_RECHECKING"));
      else sendBoschStatus(F("BOSCH_DTC_CLEAR_NOT_CONFIRMED"));
      break;
    case '4': milTest(); break;
    case '6': readLiveParameters(); break;
    case '0': disconnect(); sendBoschStatus(F("BOSCH_ECU_DISCONNECTED")); break;
    case 'X': sendBoschStatus(F("BOSCH_ACTUATOR_EARLY_STOP_REQUESTED")); break;
    case '?': printMenu(); break;
    default: if (!runActuatorByKey(key)) sendBoschStatus(F("BOSCH_INVALID_COMMAND")); break;
  }
}
void poll() {
  if (kwpSession && (uint32_t)(millis()-lastBoschPoll)>=1600UL) {
    lastBoschPoll=millis();
    readLiveParameters();
  }
}


} // namespace BoschFirmware
