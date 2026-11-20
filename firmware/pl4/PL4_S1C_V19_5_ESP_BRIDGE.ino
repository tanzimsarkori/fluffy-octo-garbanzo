/*
  VALEO PL4_S1C NOIMMO - STM32F103CB + MC33290
  PL4 ONLY: LIVE + DTC READ/CLEAR + ACTUATORS V19.5 (V11 connect retained)

  Board: STM32F103CB, 72 MHz, STM32F1 Maple core 2022.9.26
  IDE: Arduino 1.8.19
  K-Line transceiver: MC33290
  USART3: PB10 TX, PB11 RX

  VERIFIED ON THIS ECU BY USER:
    Fast Init: HIGH 300 ms -> LOW 25 ms -> HIGH 25 ms
    StartCommunication TX: 81 10 F1 81 03
    StartCommunication RX: 83 F1 10 C1 D0 8F A4
    TesterPresent TX: 81 10 F1 3E C0
    TesterPresent RX: 81 F1 10 7E 00
    Live C0 request TX: 84 10 F1 21 C0 80 01 E7
    Positive C0 response service/local ID: 61 C0

  SISOOG SICAR DATA MAP:
    21 C0 80 01 -> 61 C0
    21 C3 80 01 -> 61 C3
    21 C1 80 01 -> 61 C1
    21 C2 80 01 -> 61 C2
    21 C5 80 01 -> 61 C5
    21 C4 80 01 -> 61 C4
    21 B0       -> 61 B0
    21 89       -> 61 89

  IMPORTANT BYTE INDEXING:
    Sicar B[1] is the first data byte after the 3-byte KWP header,
    i.e. the response service byte (0x61).
    Therefore B[3] = frame[5] when frame points at the KWP format byte.
    This matches the Sicar formulas and the user's VMAX C0 trace.

  OPERATION:
    1 CONNECT PL4; 2 LIVE on/off; 3 READ / TRANSLATE FAULTS;
    4 ERASE FAULTS (single selection, RPM=0 + observed-prep guard); 5 ACTUATOR MENU.
    Actuators: selectors A..K (numbers 1..5 always control main menu); no Y arm.
    X cancels or returns. No coding or ECU reprogramming.
    DTC 17 FF 00 / 17 FF FF and 14 FF 00 extracted from user's
    Emax 2023.apk / Unit_Engine_206_S2000.o. Not tested on PL4.
    DTC meanings: Emax dictionary for manufacturer codes; standard
    generic OBD translations for known codes. Unknown IDs stay raw.
    Unsupported/no-response PIDs are skipped; other PIDs continue.

  ACTUATOR REFERENCE: Emax 2023.apk (user supplied) contains both
    Unit_Engine_206_S2000 and Unit_Engine_Samand_Sagem modules.
    Both contain commands 83 10 F1 30 <ID> 00 <SUM> and a subsequent
    frame using subfunction 01. PL4_S1C-specific support not verified.
    This is NOT an assertion that all actuators will work on this ECU.
    Clear preparation 0x34 is a memory-transfer service and is not risk-free.
    No 0x36 / 0x37 flash transfers, EEPROM edits, or immobilizer programming.
    DTC clear needs verified engine STOP and observed 0x74 / 0x7E responses.
    Bench test only: engine OFF; disconnect fuel/spark hazards.
    Fuel-consumption formulas using PIDOF[...] are not calculated because
    their referenced PIDOF values are not defined in this Sagem JSON file.
*/

// Declared before Arduino.h so Arduino IDE 1.8.19 auto-generated
// function prototypes can use DtcFrame (fixes "not declared" error).
#include <stdint.h>
struct DtcFrame {uint16_t offset, length, dataStart, dataLength;};

#include <Arduino.h>

// --------------------------- Hardware --------------------------------------

static HardwareSerial &K = Serial3;

static const uint32_t USB_BAUD = 115200UL;
static const uint32_t KWP_BAUD = 10400UL;

static const uint8_t ECU_ADDRESS = 0x10;
static const uint8_t TESTER_ADDRESS = 0xF1;

static const uint8_t K_TX_PIN = PB10;
static const uint8_t K_RX_PIN = PB11;

static const uint32_t IDLE_HIGH_US = 300000UL;
static const uint32_t FAST_LOW_US = 25000UL;
static const uint32_t FAST_TOTAL_US = 50000UL;

static const uint16_t START_RESPONSE_TIMEOUT_MS = 350;
static const uint16_t NORMAL_RESPONSE_TIMEOUT_MS = 250;
static const uint16_t PID_GAP_MS = 50;
static const uint32_t TESTER_PRESENT_INTERVAL_MS = 2000UL;
static const uint32_t LIVE_SWEEP_INTERVAL_MS = 250UL;

static const uint8_t RX_CAPACITY = 128;
static const uint16_t ACT_RESPONSE_TIMEOUT_MS = 120;
static const uint32_t ACT_REPEAT_GAP_MS = 5000UL;


// --------------------------- State -----------------------------------------

static bool uartReady = false;
static bool ecuConnected = false;
static bool livePolling = false;
static bool actuatorMenu = false;
static uint32_t lastActuationAt = 0;
static bool hasActuated = false;
static uint8_t liveMode = 0; // 0 = off, 1 = all groups, 2 = core groups


static uint32_t lastTesterPresentMs = 0;
static uint32_t lastLiveSweepMs = 0;

static uint8_t rxBuffer[RX_CAPACITY];

// ESP-01 bridge for Yasir Diag: STM32 Serial2 PA2/PA3 at 115200.
// JSON output is separate from USB Serial debug text.
// This is a PL4-only companion firmware; other ECU profiles require their own firmware.
static void pl4WebStatus(const char *message) {
  Serial2.print(F("{\"type\":\"status\",\"message\":\""));
  Serial2.print(message);
  Serial2.println(F("\"}"));
}
static uint8_t pl4JsonParamCount = 0;
static void pl4ParamBegin() {
  pl4JsonParamCount = 0;
  Serial2.print(F("{\"type\":\"parameters\",\"profile\":\"valeo_pl4_s1c\",\"parameters\":["));
}
static void pl4ParamNumber(const char *id, float value, const char *unit) {
  if (pl4JsonParamCount++) Serial2.print(',');
  Serial2.print(F("{\"id\":\"")); Serial2.print(id);
  Serial2.print(F("\",\"value\":")); Serial2.print(value, 2);
  Serial2.print(F(",\"unit\":\"")); Serial2.print(unit);
  Serial2.print(F("\"}"));
}
static void pl4ParamEnd() { Serial2.println(F("]}")); }
static void pl4PrintHex2(uint8_t b) {
  if (b < 16) Serial2.print('0');
  Serial2.print(b, HEX);
}


// Actuator identifiers cross-checked between the two Sagem Emax modules.
// They are reference commands, NOT proven for Valeo PL4_S1C hardware.
struct ActuatorDefinition {
  uint8_t identifier;
  const char *label;
  uint16_t maxOnMs;
};
static const ActuatorDefinition ACTUATORS[] = {
  {0x11, "Injector 1", 400},
  {0x12, "Injector 2", 400},
  {0x13, "Injector 3", 400},
  {0x14, "Injector 4", 400},
  {0x23, "Fuel pump relay", 600},
  {0x41, "Idle stepper motor", 1000},
  {0x51, "Ignition coil 1/4", 300},
  {0x52, "Ignition coil 2/3", 300},
  {0x57, "Cooling fan HIGH", 800},
  {0x58, "Cooling fan LOW", 800},
  {0x61, "Canister valve", 600}
};
static const uint8_t ACTUATOR_COUNT = sizeof(ACTUATORS)/sizeof(ACTUATORS[0]);


// --------------------------- Utilities -------------------------------------

static uint8_t checksum8(const uint8_t *data, uint8_t length) {
  uint16_t sum = 0;
  for (uint8_t i = 0; i < length; ++i) {
    sum += data[i];
  }
  return (uint8_t)(sum & 0xFFU);
}

static void printHex(const uint8_t *data, uint8_t length) {
  if (length == 0) {
    Serial.println(F("NO BYTES"));
    return;
  }

  for (uint8_t i = 0; i < length; ++i) {
    if (data[i] < 0x10) Serial.print('0');
    Serial.print(data[i], HEX);
    if (i + 1U < length) Serial.print(' ');
  }
  Serial.println();
}

static void printMenu() {
  Serial.println();
  Serial.println(F("======== VALEO PL4_S1C - V19.5 ========"));
  Serial.println(F("1 = CONNECT ECU"));
  Serial.println(F("2 = LIVE PARAMETERS / STOP"));
  Serial.println(F("3 = READ FAULTS + TRANSLATE (DTC)"));
  Serial.println(F("4 = CLEAR FAULTS (engine OFF; one selection)"));
  Serial.println(F("5 = ACTUATOR TEST MENU"));
  Serial.println(F("? = HELP    X = CANCEL / RETURN"));
  Serial.println(F("STM32F103CB / MC33290 / COM 115200"));
  Serial.println(F("===================================="));
}

static void clearRx() {
  if (!uartReady) return;
  while (K.available() > 0) {
    (void)K.read();
  }
}

static void stopKLineUART() {
  if (uartReady) {
    K.flush();
    K.end();
    uartReady = false;
  }

  pinMode(K_TX_PIN, OUTPUT);
  digitalWrite(K_TX_PIN, HIGH);
}

static uint8_t readRxWindow(uint8_t *dst,
                            uint8_t capacity,
                            uint16_t timeoutMs) {
  uint8_t count = 0;
  const uint32_t started = millis();

  while ((uint32_t)(millis() - started) < timeoutMs) {
    while (K.available() > 0) {
      const int value = K.read();
      if (value >= 0 && count < capacity) {
        dst[count++] = (uint8_t)value;
      }
    }
    delayMicroseconds(50);
  }
  return count;
}

// KWP short-header total length = format lower 6 bits + 4.
static uint8_t kwpFrameLengthFromFormat(uint8_t format) {
  return (uint8_t)((format & 0x3FU) + 4U);
}

static bool findKwpFrame(const uint8_t *data,
                         uint8_t dataLength,
                         uint8_t target,
                         uint8_t source,
                         uint8_t service,
                         int expectedLocalId,
                         uint8_t &frameOffset,
                         uint8_t &frameLength) {
  for (uint8_t i = 0; i < dataLength; ++i) {
    if ((uint16_t)i + 4U > dataLength) break;

    const uint8_t length = kwpFrameLengthFromFormat(data[i]);
    if (length < 5U) continue;
    if ((uint16_t)i + length > dataLength) continue;

    if (data[i + 1U] != target || data[i + 2U] != source) continue;
    if (data[i + 3U] != service) continue;

    if (expectedLocalId >= 0) {
      if (length < 6U || data[i + 4U] != (uint8_t)expectedLocalId) {
        continue;
      }
    }

    if (checksum8(data + i, (uint8_t)(length - 1U)) != data[i + length - 1U]) {
      continue;
    }

    frameOffset = i;
    frameLength = length;
    return true;
  }

  return false;
}

// --------------------------- TX frame builders -----------------------------

static void buildStartCommunication(uint8_t frame[5]) {
  frame[0] = 0x81;
  frame[1] = ECU_ADDRESS;
  frame[2] = TESTER_ADDRESS;
  frame[3] = 0x81;
  frame[4] = checksum8(frame, 4);
}

static void buildTesterPresent(uint8_t frame[5]) {
  frame[0] = 0x81;
  frame[1] = ECU_ADDRESS;
  frame[2] = TESTER_ADDRESS;
  frame[3] = 0x3E;
  frame[4] = checksum8(frame, 4);
}

// Sicar uses 21 xx 80 01 for C0/C1/C2/C3/C4/C5,
// but only 21 B0 and 21 89 for those two short requests.
static uint8_t buildReadPID(uint8_t pid, uint8_t *frame) {
  frame[1] = ECU_ADDRESS;
  frame[2] = TESTER_ADDRESS;
  frame[3] = 0x21;
  frame[4] = pid;

  if (pid == 0xB0 || pid == 0x89) {
    frame[0] = 0x82;  // two data bytes: 21 PID
    frame[5] = checksum8(frame, 5);
    return 6;
  }

  frame[0] = 0x84;    // four data bytes: 21 PID 80 01
  frame[5] = 0x80;
  frame[6] = 0x01;
  frame[7] = checksum8(frame, 7);
  return 8;
}

static void sendContiguous(const uint8_t *frame, uint8_t length) {
  for (uint8_t i = 0; i < length; ++i) {
    K.write(frame[i]);
  }
  K.flush();
}

// --------------------------- Fast Init -------------------------------------

static bool runFastInit() {
  uint8_t tx[5];
  uint8_t frameOffset = 0;
  uint8_t frameLength = 0;

  buildStartCommunication(tx);
  ecuConnected = false;
  livePolling = false;
  liveMode = 0;
  actuatorMenu = false;

  stopKLineUART();

  pinMode(K_TX_PIN, OUTPUT);
  digitalWrite(K_TX_PIN, HIGH);

  Serial.println();
  Serial.println(F("FAST INIT START"));
  Serial.println(F("Idle HIGH 300ms -> LOW 25ms -> HIGH 25ms"));
  Serial.println(F("UART 10400 baud, 8N1"));

  const uint32_t idleStart = micros();
  while ((uint32_t)(micros() - idleStart) < IDLE_HIGH_US) {
    // Hold TXD HIGH.
  }

  digitalWrite(K_TX_PIN, LOW);
  const uint32_t fallingEdge = micros();

  while ((uint32_t)(micros() - fallingEdge) < FAST_LOW_US) {
    // Timing-critical wake LOW.
  }

  digitalWrite(K_TX_PIN, HIGH);
  const uint32_t risingEdge = micros();

  K.begin(KWP_BAUD, SERIAL_8N1);
  uartReady = true;
  clearRx();

  while ((uint32_t)(micros() - fallingEdge) < FAST_TOTAL_US) {
    // Complete the 25ms HIGH interval.
  }

  const uint32_t txStart = micros();
  sendContiguous(tx, 5);
  const uint32_t txEnd = micros();

  const uint8_t rxCount =
      readRxWindow(rxBuffer, RX_CAPACITY, START_RESPONSE_TIMEOUT_MS);

  Serial.println();
  Serial.println(F("------------- CONNECT RESULT -------------"));
  Serial.print(F("Wake LOW us: "));
  Serial.println((uint32_t)(risingEdge - fallingEdge));
  Serial.print(F("First TX from falling edge us: "));
  Serial.println((uint32_t)(txStart - fallingEdge));
  Serial.print(F("TX: "));
  printHex(tx, 5);
  Serial.print(F("TX duration us: "));
  Serial.println((uint32_t)(txEnd - txStart));
  Serial.print(F("RX RAW (echo + ECU): "));
  printHex(rxBuffer, rxCount);

  // Positive response: 83 F1 10 C1 KEY1 KEY2 CHECKSUM
  if (findKwpFrame(rxBuffer, rxCount,
                   TESTER_ADDRESS, ECU_ADDRESS, 0xC1, -1,
                   frameOffset, frameLength)) {
    if (frameLength >= 7U && rxBuffer[frameOffset] == 0x83) {
      Serial.print(F("VALID ECU RESPONSE: "));
      printHex(rxBuffer + frameOffset, frameLength);

      Serial.println(F("ECU ALIVE - STARTCOMMUNICATION ACCEPTED."));
      Serial.print(F("KEY BYTE 1: 0x"));
      if (rxBuffer[frameOffset + 4U] < 0x10) Serial.print('0');
      Serial.println(rxBuffer[frameOffset + 4U], HEX);

      Serial.print(F("KEY BYTE 2: 0x"));
      if (rxBuffer[frameOffset + 5U] < 0x10) Serial.print('0');
      Serial.println(rxBuffer[frameOffset + 5U], HEX);

      ecuConnected = true;
      lastTesterPresentMs = millis();
      lastLiveSweepMs = millis();
      Serial.println(F("SESSION CONNECTED."));
      pl4WebStatus("PL4_ECU_CONNECTED");
      Serial.println(F("Press 2: LIVE, 3: DTC, 4: CLEAR, 5: ACTUATORS."));
      return true;
    }
  }

  Serial.println(F("NO VALID C1 RESPONSE. SESSION NOT CONFIRMED."));
  pl4WebStatus("PL4_ECU_CONNECTION_FAILED");
  return false;
}

// --------------------------- Tester Present -------------------------------

static bool sendTesterPresentOnce() {
  if (!ecuConnected || !uartReady) return false;

  uint8_t tx[5];
  uint8_t frameOffset = 0;
  uint8_t frameLength = 0;

  buildTesterPresent(tx);
  clearRx();
  sendContiguous(tx, 5);

  const uint8_t rxCount =
      readRxWindow(rxBuffer, RX_CAPACITY, NORMAL_RESPONSE_TIMEOUT_MS);

  if (findKwpFrame(rxBuffer, rxCount,
                   TESTER_ADDRESS, ECU_ADDRESS, 0x7E, -1,
                   frameOffset, frameLength)) {
    lastTesterPresentMs = millis();
    return true;
  }

  Serial.println(F("WARNING: TesterPresent response not confirmed."));
  return false;
}

// --------------------------- Sicar B[] access ------------------------------

// In Sicar JSON B[1] is the first byte after the 3-byte KWP header,
// so B[1] is service 0x61 and B[3] is the first actual parameter byte.
static bool getB(const uint8_t *frame,
                 uint8_t frameLength,
                 uint8_t bIndex,
                 uint8_t &value) {
  if (bIndex == 0 || frameLength < 5) return false;

  const uint16_t pos = 2U + bIndex;
  if (pos >= (uint16_t)(frameLength - 1U)) return false; // exclude checksum

  value = frame[pos];
  return true;
}

static bool getB16(const uint8_t *frame,
                   uint8_t frameLength,
                   uint8_t firstIndex,
                   uint16_t &value) {
  uint8_t hi, lo;
  if (!getB(frame, frameLength, firstIndex, hi)) return false;
  if (!getB(frame, frameLength, (uint8_t)(firstIndex + 1U), lo)) return false;
  value = ((uint16_t)hi << 8) | lo;
  return true;
}

static bool getB24(const uint8_t *frame,
                   uint8_t frameLength,
                   uint8_t firstIndex,
                   uint32_t &value) {
  uint8_t b1, b2, b3;
  if (!getB(frame, frameLength, firstIndex, b1)) return false;
  if (!getB(frame, frameLength, (uint8_t)(firstIndex + 1U), b2)) return false;
  if (!getB(frame, frameLength, (uint8_t)(firstIndex + 2U), b3)) return false;
  value = ((uint32_t)b1 << 16) | ((uint32_t)b2 << 8) | b3;
  return true;
}

static void printFloatValue(const __FlashStringHelper *label,
                            float value,
                            const __FlashStringHelper *unit) {
  Serial.print(label);
  Serial.print(F(": "));
  Serial.print(value, 2);
  Serial.print(' ');
  Serial.println(unit);
}

static void printRawValue(const __FlashStringHelper *label, uint8_t value) {
  Serial.print(label);
  Serial.print(F(": 0x"));
  if (value < 0x10) Serial.print('0');
  Serial.println(value, HEX);
}

static void printBitStatus(const __FlashStringHelper *label, uint8_t value) {
  Serial.print(label);
  Serial.println((value & 0x80U) ? F(": ON") : F(": OFF"));
}

// --------------------------- Parameter formulas ---------------------------

static void decodePID(uint8_t pid,
                      const uint8_t *frame,
                      uint8_t frameLength) {
  uint8_t b = 0;
  uint16_t word = 0;
  uint32_t longWord = 0;

  Serial.println(F("DECODED PARAMETERS:"));

  switch (pid) {
    case 0xC0:
      // Sicar: RPM B[3]*32; battery B[4]*0.1;
      // injection B[7]*0.819607843; fuel-cut B[8];
      // coolant B[13]-40; TPS voltage B[11]*0.0196078431.
      if (getB(frame, frameLength, 3, b))
        printFloatValue(F("  Engine speed"), (float)b * 32.0f, F("rpm"));
      if (getB(frame, frameLength, 4, b))
        printFloatValue(F("  Battery voltage"), (float)b * 0.1f, F("V"));
      if (getB(frame, frameLength, 7, b))
        printFloatValue(F("  Injection time"), (float)b * 0.819607843f, F("ms"));
      if (getB(frame, frameLength, 8, b))
        printRawValue(F("  Fuel cut status (raw)"), b);
      if (getB(frame, frameLength, 13, b))
        printFloatValue(F("  Coolant temperature"), (float)b - 40.0f, F("C"));
      if (getB(frame, frameLength, 11, b))
        printFloatValue(F("  Throttle sensor voltage"), (float)b * 0.0196078431f, F("V"));
      break;

    case 0xC3:
      // Sicar: throttle state B[7]; angle B[8]*0.388235294;
      // air temp B[13]-40; MAP B[14]*5.117647058;
      // stepper B[17]; canister B[19]*0.38823529; canister state B[18].
      if (getB(frame, frameLength, 3, b))
        printFloatValue(F("  Engine speed"), (float)b * 32.0f, F("rpm"));
      if (getB(frame, frameLength, 4, b))
        printFloatValue(F("  Battery voltage"), (float)b * 0.1f, F("V"));
      if (getB(frame, frameLength, 7, b))
        printRawValue(F("  Throttle state (raw)"), b);
      if (getB(frame, frameLength, 8, b))
        printFloatValue(F("  Throttle angle"), (float)b * 0.388235294f, F("%"));
      if (getB(frame, frameLength, 13, b))
        printFloatValue(F("  Intake air temperature (Sicar B13)"), (float)b - 40.0f, F("C"));
      if (getB(frame, frameLength, 14, b))
        printFloatValue(F("  Manifold pressure"), (float)b * 5.117647058f, F("mbar"));
      if (getB(frame, frameLength, 17, b))
        printFloatValue(F("  Stepper motor position"), (float)b, F("step"));
      if (getB(frame, frameLength, 19, b))
        printFloatValue(F("  Canister command"), (float)b * 0.38823529f, F("%"));
      if (getB(frame, frameLength, 18, b))
        printRawValue(F("  Canister state (raw)"), b);

      // Additional alternate formulas present in the same Sicar JSON.
      if (getB(frame, frameLength, 11, b))
        printFloatValue(F("  Coolant temperature (alternate B11)"), (float)b - 40.0f, F("C"));
      if (getB(frame, frameLength, 12, b))
        printFloatValue(F("  Intake air temperature (alternate B12)"), (float)b - 40.0f, F("C"));
      if (getB(frame, frameLength, 20, b))
        printBitStatus(F("  A/C request"), b);
      if (getB(frame, frameLength, 21, b))
        printBitStatus(F("  Intake air thermostat"), b);
      if (getB(frame, frameLength, 25, b))
        printFloatValue(F("  A/C system pressure"), (float)b * 164.0f, F("mbar"));
      break;

    case 0xC1:
      // Sicar: advance B[7]*0.374980392; coil dwell B[8]/B[9]*0.8196078431.
      if (getB(frame, frameLength, 3, b))
        printFloatValue(F("  Engine speed"), (float)b * 32.0f, F("rpm"));
      if (getB(frame, frameLength, 4, b))
        printFloatValue(F("  Battery voltage"), (float)b * 0.1f, F("V"));
      if (getB(frame, frameLength, 7, b))
        printFloatValue(F("  Ignition advance"), (float)b * 0.374980392f, F("deg"));
      if (getB(frame, frameLength, 8, b))
        printFloatValue(F("  Coil dwell 1-4"), (float)b * 0.8196078431f, F("ms"));
      if (getB(frame, frameLength, 9, b))
        printFloatValue(F("  Coil dwell 2-3"), (float)b * 0.8196078431f, F("ms"));
      break;

    case 0xC2:
      // Sicar: O2 sensors B[10]/B[11]*0.019490196; fuel control B[14].
      if (getB(frame, frameLength, 3, b))
        printFloatValue(F("  Engine speed"), (float)b * 32.0f, F("rpm"));
      if (getB(frame, frameLength, 10, b))
        printFloatValue(F("  Oxygen sensor before catalyst"), (float)b * 0.019490196f, F("V"));
      if (getB(frame, frameLength, 11, b))
        printFloatValue(F("  Oxygen sensor after catalyst"), (float)b * 0.019490196f, F("V"));
      if (getB(frame, frameLength, 14, b))
        printRawValue(F("  Fuel regulation status (raw)"), b);
      break;

    case 0xC5:
      // Sicar: fan relay bit 7; actual/target duty B[10]/B[11]*0.388235294.
      if (getB(frame, frameLength, 9, b))
        printBitStatus(F("  Fan relay"), b);
      if (getB(frame, frameLength, 10, b))
        printFloatValue(F("  Actual fan duty"), (float)b * 0.388235294f, F("%"));
      if (getB(frame, frameLength, 11, b))
        printFloatValue(F("  Target fan duty"), (float)b * 0.388235294f, F("%"));
      break;

    case 0xC4:
      // Sicar: torque B[7]*0.388235294; injection B[8]*0.8196078431;
      // speed B[21]; gear B[23]%8; power relay bit 7 of B[18].
      if (getB(frame, frameLength, 3, b))
        printFloatValue(F("  Engine speed"), (float)b * 32.0f, F("rpm"));
      if (getB(frame, frameLength, 4, b))
        printFloatValue(F("  Battery voltage"), (float)b * 0.1f, F("V"));
      if (getB(frame, frameLength, 7, b))
        printFloatValue(F("  Engine torque"), (float)b * 0.388235294f, F("%"));
      if (getB(frame, frameLength, 8, b))
        printFloatValue(F("  Injection time"), (float)b * 0.8196078431f, F("ms"));
      if (getB(frame, frameLength, 21, b))
        printFloatValue(F("  Vehicle speed"), (float)b, F("km/h"));
      if (getB(frame, frameLength, 23, b))
        printFloatValue(F("  Gear code"), (float)(b % 8U), F(""));
      if (getB(frame, frameLength, 18, b))
        printBitStatus(F("  Power relay control"), b);
      break;

    case 0xB0:
      // Sicar: central-lock bit 7 of B[3]; immobilizer and unlock status B[4].
      if (getB(frame, frameLength, 3, b))
        printBitStatus(F("  Central lock control"), b);
      if (getB(frame, frameLength, 4, b)) {
        printRawValue(F("  Immobilizer status (raw)"), b);
        printRawValue(F("  Unlock fault status (raw)"), b);
      }
      break;

    case 0x89:
      // Sicar: distance B[12~14], disconnect count B[15],
      // time without speed sensor B[16~17]*0.1.
      if (getB24(frame, frameLength, 12, longWord))
        printFloatValue(F("  Distance traveled"), (float)longWord, F("km"));
      if (getB(frame, frameLength, 15, b))
        printFloatValue(F("  Speed sensor disconnect count"), (float)b, F("count"));
      if (getB16(frame, frameLength, 16, word))
        printFloatValue(F("  Time without speed sensor"), (float)word * 0.1f, F("h"));
      break;

    default:
      Serial.println(F("  No decoder for this PID."));
      break;
  }
}

// Export the SAME checksum-validated PID bytes and formulas as the tested
// USB-console V19.5 firmware, without claiming unsupported parameters.
static void pl4BridgeParameters(uint8_t pid, const uint8_t *f, uint8_t n) {
  uint8_t b = 0;
  pl4ParamBegin();
  switch (pid) {
    case 0xC0:
      if (getB(f,n,3,b)) pl4ParamNumber("pl4_rpm", b*32.0f, "rpm");
      if (getB(f,n,4,b)) pl4ParamNumber("pl4_battery", b*0.1f, "V");
      if (getB(f,n,7,b)) pl4ParamNumber("pl4_injection_time", b*0.819607843f, "ms");
      if (getB(f,n,8,b)) pl4ParamNumber("pl4_fuel_cut_raw", b, "raw");
      if (getB(f,n,13,b)) pl4ParamNumber("pl4_coolant", (float)b-40.0f, "C");
      if (getB(f,n,11,b)) pl4ParamNumber("pl4_tps_voltage", b*0.0196078431f, "V");
      break;
    case 0xC3:
      if (getB(f,n,7,b)) pl4ParamNumber("pl4_throttle_state_raw", b, "raw");
      if (getB(f,n,8,b)) pl4ParamNumber("pl4_throttle_angle", b*0.388235294f, "%");
      if (getB(f,n,13,b)) pl4ParamNumber("pl4_intake", (float)b-40.0f, "C");
      if (getB(f,n,14,b)) pl4ParamNumber("pl4_map", b*5.117647058f, "mbar");
      if (getB(f,n,17,b)) pl4ParamNumber("pl4_stepper", b, "step");
      if (getB(f,n,18,b)) pl4ParamNumber("pl4_canister_state_raw", b, "raw");
      if (getB(f,n,19,b)) pl4ParamNumber("pl4_canister_duty", b*0.38823529f, "%");
      if (getB(f,n,20,b)) pl4ParamNumber("pl4_ac_request_raw", b, "raw");
      break;
    case 0xC1:
      if (getB(f,n,7,b)) pl4ParamNumber("pl4_advance", b*0.374980392f, "deg");
      if (getB(f,n,8,b)) pl4ParamNumber("pl4_dwell14", b*0.8196078431f, "ms");
      if (getB(f,n,9,b)) pl4ParamNumber("pl4_dwell23", b*0.8196078431f, "ms");
      break;
    case 0xC2:
      if (getB(f,n,10,b)) pl4ParamNumber("pl4_o2_front", b*0.019490196f, "V");
      if (getB(f,n,11,b)) pl4ParamNumber("pl4_o2_rear", b*0.019490196f, "V");
      if (getB(f,n,14,b)) pl4ParamNumber("pl4_fuel_reg_raw", b, "raw");
      break;
    case 0xC5:
      if (getB(f,n,9,b)) pl4ParamNumber("pl4_fan_relay_raw", b, "raw");
      if (getB(f,n,10,b)) pl4ParamNumber("pl4_fan_actual", b*0.388235294f, "%");
      if (getB(f,n,11,b)) pl4ParamNumber("pl4_fan_target", b*0.388235294f, "%");
      break;
    case 0xC4:
      if (getB(f,n,7,b)) pl4ParamNumber("pl4_torque", b*0.388235294f, "%");
      if (getB(f,n,21,b)) pl4ParamNumber("pl4_speed", b, "km/h");
      if (getB(f,n,23,b)) pl4ParamNumber("pl4_gear_raw", b%8, "raw");
      if (getB(f,n,18,b)) pl4ParamNumber("pl4_power_relay_raw", b, "raw");
      break;
    case 0xB0:
      if (getB(f,n,3,b)) pl4ParamNumber("pl4_lock_raw", b, "raw");
      if (getB(f,n,4,b)) pl4ParamNumber("pl4_immo_raw", b, "raw");
      break;
    case 0x89: {
      uint32_t v24;
      uint16_t v16;
      if (getB24(f,n,12,v24)) pl4ParamNumber("pl4_distance", (float)v24, "km");
      if (getB(f,n,15,b)) pl4ParamNumber("pl4_speed_disconnects", b, "count");
      if (getB16(f,n,16,v16)) pl4ParamNumber("pl4_speed_error_time", v16*0.1f, "h");
      break;
    }
  }
  pl4ParamEnd();
}

// --------------------------- Read one PID ----------------------------------

static bool requestAndDecodePID(uint8_t pid) {
  uint8_t tx[8];
  uint8_t txLength = buildReadPID(pid, tx);
  uint8_t frameOffset = 0;
  uint8_t frameLength = 0;

  clearRx();
  sendContiguous(tx, txLength);

  const uint8_t rxCount =
      readRxWindow(rxBuffer, RX_CAPACITY, NORMAL_RESPONSE_TIMEOUT_MS);

  Serial.println();
  Serial.print(F("[PID 0x"));
  if (pid < 0x10) Serial.print('0');
  Serial.print(pid, HEX);
  Serial.println(F("]"));

  Serial.print(F("  TX: "));
  printHex(tx, txLength);
  Serial.print(F("  RX RAW (echo + ECU): "));
  printHex(rxBuffer, rxCount);

  if (findKwpFrame(rxBuffer, rxCount,
                   TESTER_ADDRESS, ECU_ADDRESS, 0x61, pid,
                   frameOffset, frameLength)) {
    const uint8_t *frame = rxBuffer + frameOffset;

    Serial.print(F("  VALID RESPONSE: "));
    printHex(frame, frameLength);
    Serial.println(F("  CHECKSUM: VALID"));

    decodePID(pid, frame, frameLength);
    pl4BridgeParameters(pid, frame, frameLength);
    delay(PID_GAP_MS);
    return true;
  }

  Serial.println(F("  NO VALID POSITIVE RESPONSE - skipped."));
  Serial.println(F("  ECU session is kept; next PID will be tested."));
  delay(PID_GAP_MS);
  return false;
}

// --------------------------- Full live sweep -------------------------------

static void readAllLiveParameters(bool coreOnly) {
  if (!ecuConnected || !uartReady) {
    livePolling = false;
    Serial.println(F("Not connected. Press 1 first."));
    return;
  }

  Serial.println();
  Serial.println(F("=============================================="));
  Serial.println(F("        PL4 LIVE PARAMETER SWEEP"));
  Serial.println(F("=============================================="));

  // Exact command groups and formulas from the Sicar 405/Pars Sagem S2000 JSON.
  // C0 is already confirmed on this physical ECU by the user's VMAX trace.
  requestAndDecodePID(0xC0);
  requestAndDecodePID(0xC3);
  requestAndDecodePID(0xC1);
  requestAndDecodePID(0xC2);
  if (!coreOnly) {
    requestAndDecodePID(0xC5);
    requestAndDecodePID(0xC4);
    requestAndDecodePID(0xB0);
    requestAndDecodePID(0x89);
  }

  Serial.println();
  Serial.println(F("--------------- SWEEP COMPLETE ---------------"));
  Serial.println(F("Any PID without a valid response was skipped."));
  Serial.println(F("Press 2 again to stop live polling."));
}


static bool confirmEngineStopped(); // implemented in V18 actuator section
// KWP DTC services extracted from Emax 2023 APK 206 S2000 module:
// read 17 FF 00 and 17 FF FF, clear 14 FF 00.
// Exact functionality with PL4 requires a real ECU test.
static const uint16_t DTC_RX_CAPACITY=320;
static uint8_t dtcRx[DTC_RX_CAPACITY];
static uint16_t dtcHits=0;
struct DtcText {uint16_t raw; const char *fa; const char *en;};
static const DtcText DTC_TEXT[] = {
  {0x0100,"سنسور دبی هوای ورودی (MAF)","Mass air flow sensor circuit"},
  {0x0105,"سنسور فشار منیفولد MAP","MAP sensor circuit"},
  {0x0106,"محدوده عملکرد سنسور MAP","MAP sensor range/performance"},
  {0x0107,"ولتاژ پایین سنسور MAP","MAP sensor circuit low"},
  {0x0108,"ولتاژ بالای سنسور MAP","MAP sensor circuit high"},
  {0x0110,"مدار سنسور دمای هوای ورودی","Intake air temperature circuit"},
  {0x0112,"ولتاژ پایین سنسور دمای هوا","Intake air temperature low"},
  {0x0113,"ولتاژ بالا سنسور دمای هوا","Intake air temperature high"},
  {0x0115,"مدار سنسور دمای آب موتور","Engine coolant temperature circuit"},
  {0x0116,"عملکرد غیرعادی سنسور دمای آب","Coolant temperature range/performance"},
  {0x0117,"ولتاژ پایین سنسور دمای آب","Coolant temperature low"},
  {0x0118,"ولتاژ بالای سنسور دمای آب","Coolant temperature high"},
  {0x0120,"مدار سنسور موقعیت دریچه گاز","Throttle position sensor circuit"},
  {0x0121,"دامنه عملکرد سنسور دریچه گاز","Throttle position range/performance"},
  {0x0122,"ولتاژ پایین سنسور دریچه گاز","Throttle position low"},
  {0x0123,"ولتاژ بالای سنسور دریچه گاز","Throttle position high"},
  {0x0125,"دمای موتور برای کنترل حلقه بسته کافی نیست","Insufficient coolant temperature for closed loop"},
  {0x0130,"مدار سنسور اکسیژن قبل کاتالیست","Oxygen sensor circuit B1S1"},
  {0x0131,"ولتاژ پایین سنسور اکسیژن بالایی","Oxygen sensor low B1S1"},
  {0x0132,"ولتاژ بالای سنسور اکسیژن بالایی","Oxygen sensor high B1S1"},
  {0x0133,"پاسخ کند سنسور اکسیژن بالایی","Oxygen sensor slow response B1S1"},
  {0x0134,"عدم فعالیت سنسور اکسیژن بالایی","Oxygen sensor no activity B1S1"},
  {0x0135,"مدار گرمکن سنسور اکسیژن بالایی","Oxygen sensor heater B1S1"},
  {0x0136,"مدار سنسور اکسیژن پایین","Oxygen sensor circuit B1S2"},
  {0x0137,"ولتاژ پایین سنسور اکسیژن پایین","Oxygen sensor low B1S2"},
  {0x0138,"ولتاژ بالای سنسور اکسیژن پایین","Oxygen sensor high B1S2"},
  {0x0140,"عدم فعالیت سنسور اکسیژن پایین","Oxygen sensor no activity B1S2"},
  {0x0141,"گرمکن سنسور اکسیژن پایین","Oxygen sensor heater B1S2"},
  {0x0170,"خرابی تنظیم سوخت","Fuel trim malfunction"},
  {0x0171,"مخلوط سوخت و هوا رقیق","Fuel system too lean"},
  {0x0172,"مخلوط سوخت و هوا غنی","Fuel system too rich"},
  {0x0200,"مدار انژکتور","Injector circuit"},
  {0x0201,"مدار انژکتور سیلندر یک","Injector circuit cylinder 1"},
  {0x0202,"مدار انژکتور سیلندر دو","Injector circuit cylinder 2"},
  {0x0203,"مدار انژکتور سیلندر سه","Injector circuit cylinder 3"},
  {0x0204,"مدار انژکتور سیلندر چهار","Injector circuit cylinder 4"},
  {0x0230,"مدار اولیه رله پمپ بنزین","Fuel pump primary circuit"},
  {0x0231,"ولتاژ پایین مدار پمپ بنزین","Fuel pump secondary circuit low"},
  {0x0232,"ولتاژ بالای مدار پمپ بنزین","Fuel pump secondary circuit high"},
  {0x0300,"احتراق ناقص یا میسفایر تصادفی","Random/multiple cylinder misfire"},
  {0x0301,"میسفایر سیلندر یک","Cylinder 1 misfire"},
  {0x0302,"میسفایر سیلندر دو","Cylinder 2 misfire"},
  {0x0303,"میسفایر سیلندر سه","Cylinder 3 misfire"},
  {0x0304,"میسفایر سیلندر چهار","Cylinder 4 misfire"},
  {0x0325,"مدار سنسور ناک","Knock sensor circuit"},
  {0x0326,"عملکرد غیرعادی سنسور ناک","Knock sensor range/performance"},
  {0x0327,"ولتاژ پایین سنسور ناک","Knock sensor low"},
  {0x0328,"ولتاژ بالای سنسور ناک","Knock sensor high"},
  {0x0335,"مدار سنسور موقعیت میل لنگ","Crankshaft position sensor circuit"},
  {0x0336,"سیگنال غیرعادی سنسور میل لنگ","Crankshaft position range/performance"},
  {0x0340,"مدار سنسور میل سوپاپ","Camshaft position sensor circuit"},
  {0x0351,"مدار اولیه یا ثانویه کوئل A","Ignition coil A circuit"},
  {0x0352,"مدار اولیه یا ثانویه کوئل B","Ignition coil B circuit"},
  {0x0353,"مدار اولیه یا ثانویه کوئل C","Ignition coil C circuit"},
  {0x0354,"مدار اولیه یا ثانویه کوئل D","Ignition coil D circuit"},
  {0x0400,"اختلال جریان سیستم بازچرخانی دود","EGR flow malfunction"},
  {0x0420,"بازده پایین کاتالیست","Catalyst efficiency below threshold"},
  {0x0443,"مدار شیر برقی کنیستر","EVAP purge control valve circuit"},
  {0x0444,"باز بودن مدار شیر کنیستر","EVAP purge control valve circuit open"},
  {0x0445,"اتصال کوتاه مدار شیر کنیستر","EVAP purge control valve circuit shorted"},
  {0x0480,"مدار فرمان فن خنک کننده یک","Cooling fan control circuit 1"},
  {0x0481,"مدار فرمان فن خنک کننده دو","Cooling fan control circuit 2"},
  {0x0500,"سنسور سرعت خودرو","Vehicle speed sensor"},
  {0x0505,"سیستم کنترل دور آرام","Idle air control system"},
  {0x0506,"دور آرام کمتر از مقدار هدف","Idle speed lower than expected"},
  {0x0507,"دور آرام بیشتر از مقدار هدف","Idle speed higher than expected"},
  {0x0510,"مدار کلید بسته بودن دریچه گاز","Closed throttle position switch"},
  {0x0530,"مدار سنسور فشار گاز کولر","A/C refrigerant pressure sensor circuit"},
  {0x0560,"ولتاژ غیرعادی سیستم برق خودرو","System voltage malfunction"},
  {0x0562,"ولتاژ پایین سیستم برق","System voltage low"},
  {0x0563,"ولتاژ بالای سیستم برق","System voltage high"},
  {0x0600,"ارتباط سریال ECU","Serial communication link"},
  {0x0601,"خطای حافظه داخلی ECU","Internal control module memory checksum"},
  {0x0602,"خطای برنامه ریزی ECU","Control module programming error"},
  {0x0606,"خطای پردازنده ECU","Control module processor fault"},
  {0x0627,"مدار رله یا فرمان پمپ بنزین","Fuel pump control circuit open"},
  {0x0638,"عملکرد غیرعادی کنترل دریچه گاز","Throttle actuator range/performance"},
  {0x0700,"وجود خطا در سیستم گیربکس","Transmission control system malfunction"},
  {0x1108,"خطای اختصاصی جدول Emax، جزئیات نامشخص","Emax OEM fault 1108 (description not recovered)"},
  {0x1109,"خطای اختصاصی جدول Emax، جزئیات نامشخص","Emax OEM fault 1109 (description not recovered)"},
  {0x1110,"تطبیق مقدار هوای ورودی منیفولد","Intake manifold air adaptation (Emax OEM)"},
  {0x1186,"تنظیم ترکیب سوخت و هوا و پاشش","Fuel injection / mixture regulation (Emax OEM)"},
  {0x1305,"اشکال شمع یا جرقه سیلندر یک","Spark plug cylinder 1 (Emax OEM)"},
  {0x1308,"اشکال شمع یا جرقه سیلندر دو","Spark plug cylinder 2 (Emax OEM)"},
  {0x1311,"اشکال شمع یا جرقه سیلندر سه","Spark plug cylinder 3 (Emax OEM)"},
  {0x1314,"اشکال شمع یا جرقه سیلندر چهار","Spark plug cylinder 4 (Emax OEM)"},
  {0x1327,"اختلال شناسایی پالس جرقه","Integrated ignition pulse detection (Emax OEM)"},
  {0x1336,"احتراق ناقص موتور","Ignition misfiring (Emax OEM)"},
  {0x1511,"تغذیه مثبت احتراق ECU","ECU ignition positive supply (Emax OEM)"},
  {0x1523,"خطای اختصاصی Emax، توضیح بازیابی نشده","Emax OEM fault 1523 (description unavailable)"},
  {0x1526,"موتور پله ای تنظیم دور آرام","Idle regulation stepper motor (Emax OEM)"},
  {0x1543,"اطلاعات خروجی کولر","A/C information output (Emax OEM)"},
  {0x1544,"اشکال تنظیم استپر دور آرام","Idle stepper regulation/coherence (Emax OEM)"},
  {0x1601,"کنترل رله تغذیه ECU","ECU supply relay control (Emax OEM)"},
  {0x1603,"اطلاعات سنسور دمای آب","Coolant temperature information (Emax OEM)"},
  {0x1608,"چراغ هشدار دمای آب","Coolant temperature warning lamp (Emax OEM)"},
  {0x1609,"داده های دور موتور","Engine speed data (Emax OEM)"},
  {0x1610,"اطلاعات مصرف سوخت","Fuel consumption information (Emax OEM)"},
  {0x1613,"پیکربندی ECU","Configuration (Emax OEM)"},
  {0x1614,"تغذیه سنسورها اتصال کوتاه به مثبت یا زمین","Sensor supply short to positive/ground (Emax OEM)"},
  {0x1794,"اختلال ارتباط با ABS","ABS communication fault (Emax OEM)"},
  {0xD000,"خطای ارتباط ECU با شبکه CAN","CAN communication with other ECUs (Emax OEM)"},
  {0xD118,"اختلال ارتباط با BSI","No communication with BSI (Emax OEM)"},
  {0xD003,"اختلال ارتباط روی شبکه CAN","CAN communication missing (Emax OEM)"},
};
static const uint16_t DTC_TEXT_COUNT=sizeof(DTC_TEXT)/sizeof(DTC_TEXT[0]);
static void printHex16(uint16_t value) {
  for(int i=12;i>=0;i-=4) {
    uint8_t n=(value>>i)&15;
    Serial.print((char)(n<10?'0'+n:'A'+n-10));
  }
}
static void printObdCode(uint16_t raw) {
  const char cats[4]={'P','C','B','U'};
  Serial.print(cats[(raw>>14)&3]);
  Serial.print((char)('0'+((raw>>12)&3)));
  for(int i=8;i>=0;i-=4) {
    uint8_t n=(raw>>i)&15;
    Serial.print((char)(n<10?'0'+n:'A'+n-10));
  }
}
static void printDtcSystem(uint16_t raw) {
  switch((raw>>14)&3) {
    case 0:Serial.println(F("موتور / POWERTRAIN"));break;
    case 1:Serial.println(F("شاسی / CHASSIS"));break;
    case 2:Serial.println(F("بدنه / BODY"));break;
    case 3:Serial.println(F("شبکه ارتباط / NETWORK"));break;
  }
  Serial.println(F("No verified exact description for this raw DTC."));
}
static void printDtc(uint16_t raw,uint8_t status,const char* bank) {
  ++dtcHits;
  Serial.print(F("["));Serial.print(bank);Serial.print(F("] RAW=0x"));printHex16(raw);
  Serial.print(F(" OBD="));printObdCode(raw);
  Serial.print(F(" STATUS=0x"));if(status<16)Serial.print('0');Serial.println(status,HEX);
  for(uint16_t i=0;i<DTC_TEXT_COUNT;++i) {
    if(DTC_TEXT[i].raw==raw) {
      Serial.print(F("  FA: "));Serial.println(DTC_TEXT[i].fa);
      Serial.print(F("  EN: "));Serial.println(DTC_TEXT[i].en);
      return;
    }
  }
  printDtcSystem(raw);
}
// DTC response can be larger than a single live-data reply.
static uint16_t readDtcRx(uint16_t maxMs) {
  uint16_t count=0;
  uint32_t begin=millis(),lastByte=begin;
  while((uint32_t)(millis()-begin)<maxMs) {
    bool received=false;
    while(K.available()>0) {
      int v=K.read();
      if(v>=0) {if(count<DTC_RX_CAPACITY) dtcRx[count++]=(uint8_t)v;received=true;}
    }
    if(received)lastByte=millis();
    if(count>0 && (uint32_t)(millis()-begin)>1100U && (uint32_t)(millis()-lastByte)>300U)break;
    delay(1);
  }
  return count;
}
// Return a checksum-valid KWP frame, skipping echoed outbound frames.
static bool parseDtcFrame(uint16_t i,uint16_t total,DtcFrame& f) {
  if(i+5U>total)return false;
  if(dtcRx[i+1]!=TESTER_ADDRESS || dtcRx[i+2]!=ECU_ADDRESS)return false;
  uint16_t dataStart=i+3U;
  uint16_t dataLength=dtcRx[i]&63U;
  if(dtcRx[i]==0x80) { dataStart=i+4U;dataLength=dtcRx[i+3U]; }
  if(!dataLength)return false;
  uint16_t frameLen=(dataStart-i)+dataLength+1U;
  if(i+frameLen>total)return false;
  uint8_t sum=0;
  for(uint16_t j=i;j<i+frameLen-1U;++j)sum=(uint8_t)(sum+dtcRx[j]);
  if(sum!=dtcRx[i+frameLen-1U])return false;
  f.offset=i;f.length=frameLen;f.dataStart=dataStart;f.dataLength=dataLength;
  return true;
}
static void printDtcRx(uint16_t count) {
  Serial.print(F("RAW RX bytes="));Serial.println(count);
  uint16_t limit=count<96U?count:96U;
  for(uint16_t i=0;i<limit;++i) {
    if(dtcRx[i]<16)Serial.print('0');
    Serial.print(dtcRx[i],HEX);Serial.print(' ');
  }
  Serial.println();
  if(count==DTC_RX_CAPACITY)Serial.println(F("BUFFER FULL; DTC list may be truncated."));
}
static void printDtcNegative(const DtcFrame& f) {
  if(f.dataLength<3)return;
  uint8_t sid=dtcRx[f.dataStart+1],code=dtcRx[f.dataStart+2];
  Serial.print(F("ECU NEGATIVE: SID=0x"));Serial.print(sid,HEX);
  Serial.print(F(" NRC=0x"));Serial.println(code,HEX);
  if(code==0x11)Serial.println(F("Service unsupported."));
  if(code==0x12)Serial.println(F("Subfunction unsupported."));
  if(code==0x22)Serial.println(F("Conditions not correct."));
  if(code==0x31)Serial.println(F("Request out of range."));
  if(code==0x33)Serial.println(F("Security access denied."));
  if(code==0x78)Serial.println(F("ECU response pending; final response may follow."));
  if(code==0x21)Serial.println(F("Busy: try again after a pause."));
  if(code==0x24)Serial.println(F("Sequence error: diagnostic session condition not met."));
}
static void sendDtcCommand(uint8_t sid,uint8_t hi,uint8_t lo) {
  uint8_t f[7]={0x83,ECU_ADDRESS,TESTER_ADDRESS,sid,hi,lo,0};
  f[6]=checksum8(f,6);
  clearRx();
  Serial.print(F("TX: "));printHex(f,7);
  sendContiguous(f,7);
}


static void pl4BridgeDtcReply(const char *bank, uint16_t declared, uint16_t present, uint16_t begin) {
  Serial2.print(F("{\"type\":\"dtc\",\"profile\":\"valeo_pl4_s1c\",\"bank\":\""));
  Serial2.print(bank);
  Serial2.print(F("\",\"count\":")); Serial2.print(declared);
  Serial2.print(F(",\"codes\":["));
  for (uint16_t i=0; i<present; ++i) {
    const uint16_t at = begin + i*3U;
    if (i) Serial2.print(',');
    Serial2.print(F("{\"code\":\""));
    pl4PrintHex2(dtcRx[at]); Serial2.print(' '); pl4PrintHex2(dtcRx[at+1]);
    Serial2.print(F("\",\"status\":\"")); pl4PrintHex2(dtcRx[at+2]);
    Serial2.print(F("\"}"));
  }
  Serial2.println(F("]}"));
}

static bool readFaultBank(uint8_t selector,const char* bank) {
  Serial.println();Serial.print(F("DTC REQUEST: "));Serial.println(bank);
  sendDtcCommand(0x17,0xFF,selector);
  uint16_t rxLen=readDtcRx(1900);
  printDtcRx(rxLen);
  bool positive=false,negative=false;
  for(uint16_t i=0;i<rxLen;++i) {
    DtcFrame f;
    if(!parseDtcFrame(i,rxLen,f))continue;
    uint8_t sid=dtcRx[f.dataStart];
    if(sid==0x7F) {negative=true;printDtcNegative(f);}
    else if(sid==0x57) {
      positive=true;
      if(f.dataLength<2) {Serial.println(F("Invalid 0x57 reply."));continue;}
      uint16_t declared=dtcRx[f.dataStart+1];
      uint16_t present=(f.dataLength-2U)/3U;
      pl4BridgeDtcReply(bank, declared, present, f.dataStart+2U);
      Serial.print(F("ECU DTC count field="));Serial.println(declared);
      if(declared==0)Serial.println(F("NO DTC in this bank (valid ECU reply)."));
      for(uint16_t k=0;k<present;++k) {
        uint16_t p=f.dataStart+2U+k*3U;
        uint16_t raw=((uint16_t)dtcRx[p]<<8)|dtcRx[p+1];
        printDtc(raw,dtcRx[p+2],bank);
      }
      if(declared>present)Serial.println(F("WARNING: reply truncated or additional records expected."));
    }
    i=f.offset+f.length-1U;
  }
  if(!positive && !negative)Serial.println(F("No verified ECU DTC response. Echo is NOT zero faults."));
  return positive;
}
static void readFaults() {
  if(!ecuConnected || !uartReady) {Serial.println(F("First press 1 CONNECT."));return;}
  livePolling=false;liveMode=0;
  dtcHits=0;
  Serial.println(F("======== READ FAULTS ========"));
  pl4WebStatus("PL4_DTC_READING");
  bool a=readFaultBank(0x00,"17 FF 00");
  delay(90);
  bool b=readFaultBank(0xFF,"17 FF FF");
  Serial.print(F("DTC records displayed (including duplicates): "));Serial.println(dtcHits);
  if(!a && !b)Serial.println(F("Read not verified on this PL4 - inspect RAW RX."));
  pl4WebStatus((a || b) ? "PL4_DTC_READ_OK" : "PL4_DTC_READ_FAILED");
  lastTesterPresentMs=millis();
}
// Clear result: 1 positive ACK, 2 ECU says conditions-not-correct,
// 3 some other negative response, 0 unconfirmed. Never treat TX echo as ACK.
static uint8_t inspectClearReply() {
  uint16_t n=readDtcRx(2200U);
  printDtcRx(n);
  uint8_t status=0;
  for(uint16_t i=0;i<n;++i) {
    DtcFrame f;
    if(!parseDtcFrame(i,n,f))continue;
    uint8_t service=dtcRx[f.dataStart];
    if(service==0x54 && f.dataLength>=3U &&
       dtcRx[f.dataStart+1U]==0xFFU && dtcRx[f.dataStart+2U]==0x00U)status=1;
    if(service==0x7F && f.dataLength>=3U && dtcRx[f.dataStart+1U]==0x14) {
      printDtcNegative(f);
      if(status!=1U)status=(dtcRx[f.dataStart+2U]==0x22)?2U:3U;
    }
    i=f.offset+f.length-1U;
  }
  return status;
}

// Observed on this exact user's VALEO PL4_S1C via a passive K-Line sniff:
//   91 10 F1 34 CF 00 00 00 09 00 00 00 00 00 00 00 00 00 52 E4 D4
//   82 F1 10 74 02 F9   (valid ECU-origin response)
//   81 10 F1 3E C0 -> 81 F1 10 7E 00
//   83 10 F1 14 FF 00 97 -> 83 F1 10 54 FF 00 D7
// In standard KWP2000, 0x34 is RequestDownload, NOT a DTC erase SID.
// We do NOT send 0x36/0x37 or any flash payload, and we never assume
// a response 0x74 means faults are erased.  Only 0x54 FF 00 confirms clear.
// This sequence has been observed in the original tool, but the same
// sequence has NOT yet been tested on this STM32/PL4 pair.
static const uint8_t PL4_CLEAR_PREP[21] = {
  0x91,0x10,0xF1,0x34,0xCF,0x00,0x00,0x00,0x09,0x00,0x00,
  0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x52,0xE4,0xD4
};
static bool observedClearPreparation() {
  if(checksum8(PL4_CLEAR_PREP,20U)!=PL4_CLEAR_PREP[20U]) {
    Serial.println(F("PREP FRAME CHECKSUM ERROR: ABORT.")); return false;
  }
  Serial.println(F("PL4-SPECIFIC PREP: 0x34 (RequestDownload)."));
  Serial.println(F("WARNING: This is a memory-transfer service. No transfer data will be sent."));
  clearRx();
  Serial.print(F("TX: "));printHex(PL4_CLEAR_PREP,21U);
  sendContiguous(PL4_CLEAR_PREP,21U);
  uint16_t n=readDtcRx(1500U);
  printDtcRx(n);
  bool positive=false;
  for(uint16_t i=0;i<n;++i) {
    DtcFrame frame;
    if(!parseDtcFrame(i,n,frame)) continue;
    const uint8_t sid=dtcRx[frame.dataStart];
    if(sid==0x74 && frame.dataLength>=2U && dtcRx[frame.dataStart+1U]==0x02U) {
      positive=true;
    } else if(sid==0x7F && frame.dataLength>=3U && dtcRx[frame.dataStart+1U]==0x34U) {
      printDtcNegative(frame);
    }
    i=frame.offset+frame.length-1U;
  }
  if(positive) Serial.println(F("VALID ECU 74 02: preparation accepted (NOT DTC erased)."));
  else Serial.println(F("NO VALID ECU 74 02: aborting before DTC erase."));
  return positive;
}

// 81 F1 10 7E 00 is a valid five-byte KWP short frame:
// format=0x81 -> exactly ONE data byte (0x7E), and 0x00 is CHECKSUM.
// parseDtcFrame() already verified addresses and checksum and excludes TX echo.
static bool isTesterPresentAckFrame(const DtcFrame& f) {
  return f.dataLength == 1U && dtcRx[f.dataStart] == 0x7EU;
}

static bool observedClearTesterPresent() {
  uint8_t tx[5];
  buildTesterPresent(tx);
  clearRx();
  Serial.print(F("TX TesterPresent: "));printHex(tx,5U);
  sendContiguous(tx,5U);
  uint16_t n=readDtcRx(850U);
  printDtcRx(n);
  for(uint16_t i=0;i<n;++i) {
    DtcFrame f;
    if(!parseDtcFrame(i,n,f))continue;
    const uint8_t sid=dtcRx[f.dataStart];
    if(isTesterPresentAckFrame(f)) {
      lastTesterPresentMs=millis();
      Serial.println(F("VALID ECU TesterPresent ACK: 7E (00 is checksum)."));
      return true;
    }
    if(sid==0x7F && f.dataLength>=3U && dtcRx[f.dataStart+1U]==0x3E)printDtcNegative(f);
    i=f.offset+f.length-1U;
  }
  Serial.println(F("NO VALID ECU 7E ACK: aborting before DTC erase."));
  return false;
}

static void clearFaults() {
  if(!ecuConnected || !uartReady) {Serial.println(F("First press 1 CONNECT."));return;}
  livePolling=false;liveMode=0;
  Serial.println(F("DTC CLEAR REQUEST: 4 selected."));
  pl4WebStatus("PL4_DTC_CLEARING");
  Serial.println(F("WARNING: STORED FAULTS WILL BE LOST if ECU accepts."));
  Serial.println(F("WARNING: 0x34 is an ECU memory-transfer preparation service."));
  Serial.println(F("Only use on the exact PL4_S1C ECU with confirmed trace."));
  Serial.println(F("Checking engine stopped (C0 RPM=0) before request..."));
  if(!confirmEngineStopped()) {
    Serial.println(F("CLEAR ABORTED: fresh RPM=0 verification failed. No erase requested."));
    pl4WebStatus("PL4_DTC_CLEAR_ABORTED");
    lastTesterPresentMs=millis(); return;
  }
  if(!observedClearPreparation()) {
    Serial.println(F("CLEAR ABORTED: 0x74 0x02 not confirmed. No erase requested."));
    pl4WebStatus("PL4_DTC_CLEAR_ABORTED");
    lastTesterPresentMs=millis(); return;
  }
  delay(75);
  if(!observedClearTesterPresent()) {
    Serial.println(F("CLEAR ABORTED: TesterPresent not confirmed. No erase requested."));
    pl4WebStatus("PL4_DTC_CLEAR_ABORTED");
    lastTesterPresentMs=millis(); return;
  }
  delay(75);
  Serial.println(F("SENDING VERIFIED DTC CLEAR SID 14 FF 00..."));
  sendDtcCommand(0x14,0xFF,0x00);
  uint8_t result=inspectClearReply();
  if(result==1U) {
    Serial.println(F("CLEAR ACK 0x54 RECEIVED: ECU accepted the request."));
    pl4WebStatus("PL4_DTC_CLEAR_ACCEPTED_RECHECKING");
    Serial.println(F("Reading DTC banks again for verification..."));
    delay(400);
    readFaults();
  } else {
    Serial.println(F("CLEAR NOT CONFIRMED: no ECU-origin 0x54."));
    pl4WebStatus("PL4_DTC_CLEAR_NOT_CONFIRMED");
    if(result==2U)Serial.println(F("ECU REJECTED: NRC 22, conditions not correct."));
    else if(result==3U)Serial.println(F("ECU REJECTED: negative response; inspect RAW RX."));
    else Serial.println(F("NO VALID CLEAR RESPONSE; inspect RAW RX."));
    Serial.println(F("No automatic retries or flash payloads are sent."));
  }
  lastTesterPresentMs=millis();
}

// ---------------------- PL4 actuator control -------------------------------
// Emax 206/Samand Sagem modules: 83 10 F1 30 ID 00/01 CHECKSUM.
// Emax sends 00 followed by 01 later. We interpret these as start/stop,
// but this interpretation and the mapping are NOT validated on PL4_S1C.
// Never start the test without a fresh engine-OFF check and user arming.

static void buildActuatorFrame(uint8_t id, uint8_t mode, uint8_t out[7]) {
  out[0] = 0x83;
  out[1] = ECU_ADDRESS;
  out[2] = TESTER_ADDRESS;
  out[3] = 0x30;
  out[4] = id;
  out[5] = mode;
  out[6] = checksum8(out, 6);
}

static void printActuatorMenu() {
  Serial.println();
  Serial.println(F("===== PL4 ACTUATORS (NO Y) ====="));
  Serial.println(F("A - Injector 1"));
  Serial.println(F("B - Injector 2"));
  Serial.println(F("C - Injector 3"));
  Serial.println(F("D - Injector 4"));
  Serial.println(F("E - Fuel pump relay"));
  Serial.println(F("F - Idle stepper"));
  Serial.println(F("G - Ignition coil 1/4"));
  Serial.println(F("H - Ignition coil 2/3"));
  Serial.println(F("I - Fan HIGH"));
  Serial.println(F("J - Fan LOW"));
  Serial.println(F("K - Canister valve"));
  Serial.println(F("1..5 - MAIN MENU OPTIONS (always active)"));
  Serial.println(F("X - RETURN TO MAIN MENU"));
  Serial.println(F("Engine must be OFF; fuel and sparks isolated."));
  Serial.println(F("RPM=0 checked; automatic STOP; 5s cooldown."));
  Serial.println(F("=================================="));
}

static int actuatorIndexFromSelector(char command) {
  if (command >= 'a' && command <= 'k') return (int)(command - 'a');
  if (command >= 'A' && command <= 'K') return (int)(command - 'A');
  return -1;
}

// Read the exact C0 RPM field immediately before each actuator command.
// Unknown RPM blocks actuation; this prevents commands with a running engine.
static bool confirmEngineStopped() {
  uint8_t tx[8];
  const uint8_t txLen = buildReadPID(0xC0, tx);
  uint8_t off = 0, flen = 0;
  clearRx();
  sendContiguous(tx, txLen);
  uint8_t n = readRxWindow(rxBuffer, RX_CAPACITY, NORMAL_RESPONSE_TIMEOUT_MS);
  if (!findKwpFrame(rxBuffer, n, TESTER_ADDRESS, ECU_ADDRESS,
                    0x61, 0xC0, off, flen)) {
    Serial.println(F("ACT BLOCKED: fresh C0 RPM response unavailable."));
    return false;
  }
  uint8_t raw = 0;
  if (!getB(rxBuffer + off, flen, 3, raw)) {
    Serial.println(F("ACT BLOCKED: C0 RPM byte is unavailable."));
    return false;
  }
  Serial.print(F("Pre-test RPM raw: "));
  Serial.print(raw);
  Serial.print(F(" / scaled: "));
  Serial.println((uint16_t)raw * 32U);
  if (raw != 0U) {
    Serial.println(F("ACT BLOCKED: Engine is running, or RPM byte is nonzero."));
    return false;
  }
  return true;
}

// Find service 0x70 positive response, or 0x7F 0x30 negative response.
static uint8_t lastRxCount = 0;
static uint8_t reportActuatorReply() {
  uint8_t off = 0, len = 0;
  if (findKwpFrame(rxBuffer, lastRxCount, TESTER_ADDRESS, ECU_ADDRESS,
                   0x70, -1, off, len)) {
    Serial.print(F("POSITIVE 0x70: "));
    printHex(rxBuffer + off, len);
    return 1;
  }
  if (findKwpFrame(rxBuffer, lastRxCount, TESTER_ADDRESS, ECU_ADDRESS,
                   0x7F, -1, off, len) && len >= 7U &&
      rxBuffer[off + 4U] == 0x30) {
    Serial.print(F("NEGATIVE 0x7F 0x30: "));
    printHex(rxBuffer + off, len);
    return 2;
  }
  Serial.println(F("No validated 0x70/0x7F response (echo alone is NOT ACK)."));
  return 0;
}

static void sendActuatorPhase(const uint8_t id, const uint8_t mode) {
  uint8_t tx[7];
  buildActuatorFrame(id, mode, tx);
  clearRx();
  sendContiguous(tx, 7);
  lastRxCount = readRxWindow(rxBuffer, RX_CAPACITY, ACT_RESPONSE_TIMEOUT_MS);
  Serial.print(mode == 0 ? F("START TX: ") : F("STOP TX: "));
  printHex(tx, 7);
  Serial.print(F("RAW RX: "));
  printHex(rxBuffer, lastRxCount);
  (void)reportActuatorReply();
}

static void performActuator(uint8_t index) {
  if (!ecuConnected || !uartReady || index >= ACTUATOR_COUNT) {
    Serial.println(F("Not connected / invalid actuator."));
    return;
  }
  if (hasActuated && (uint32_t)(millis() - lastActuationAt) < ACT_REPEAT_GAP_MS) {
    Serial.println(F("ACT BLOCKED: wait at least 5 seconds between tests."));
    return;
  }
  livePolling = false;
  liveMode = 0;
  if (!confirmEngineStopped()) return;

  const ActuatorDefinition &a = ACTUATORS[index];
  Serial.println();
  Serial.print(F("ACTUATOR: "));
  Serial.println(a.label);
  Serial.println(F("Only one short activation, then forced STOP."));
  const uint32_t started = millis();
  sendActuatorPhase(a.identifier, 0x00);
  // Time measured since first phase began; never wait indefinitely.
  while ((uint32_t)(millis() - started) < a.maxOnMs) {
    if (Serial.available() > 0) {
      char v = (char)Serial.peek();
      if (v == 'X' || v == 'x') {
        (void)Serial.read();
        Serial.println(F("User requested emergency stop."));
        break;
      }
    }
    delay(1);
  }
  // Always send the Emax second phase, even if response/ACK is missing.
  sendActuatorPhase(a.identifier, 0x01);
  hasActuated = true;
  lastActuationAt = millis();
  lastTesterPresentMs = millis();
  actuatorMenu = true; // Stay in list; cooldown continues to protect hardware.
  Serial.println(F("ACT TEST COMPLETE. Choose next actuator or X."));
  pl4WebStatus("PL4_ACTUATOR_TEST_FINISHED");
}

// -------------------------------- Setup ------------------------------------

void setup() {
  Serial.begin(USB_BAUD);
  Serial2.begin(115200); // ESP-01 existing bridge on PA2/PA3
  pl4WebStatus("PL4_BRIDGE_READY");

  pinMode(K_TX_PIN, OUTPUT);
  digitalWrite(K_TX_PIN, HIGH);

  delay(500);
  Serial.println(F("VALEO PL4_S1C NOIMMO - LIVE + DTC + ACTUATORS V19.5"));
  Serial.println(F("STM32F103CB + MC33290 / USART3 PB10-PB11"));
  Serial.println(F("V11 connect/live and V18 actuator commands retained; DTC from Emax"));
  Serial.println(F("DTC commands are new: check RAW RX after command 3."));
  printMenu();
}

// -------------------------------- Loop -------------------------------------

// ESP bridge commands are newline-delimited ASCII, e.g. !PROFILE:PL4, !1, !2.
// Never interpret an unsupported profile as a PL4 command.
static bool pl4ProfileSelected=false;
static char pl4BridgeLine[48];
static uint8_t pl4BridgeLineLength=0;

static void pl4Command(char command, bool fromPhone) {
  if(command=='\r' || command=='\n' || command==' ' || command=='\t') return;
  if(command=='x' || command=='X') {
    actuatorMenu=false;
    livePolling=false; liveMode=0;
    Serial.println(F("Cancelled / returned to MAIN menu."));
    if(fromPhone) pl4WebStatus("PL4_CANCELLED");
    printMenu(); return;
  }
  if(command=='?') { printMenu(); return; }
  // Main menu 1..5 always wins even inside actuator mode.
  if(command=='1') {
    actuatorMenu=false;
    (void)runFastInit();
  } else if(command=='2') {
    actuatorMenu=false;
    if(!ecuConnected || !uartReady) {
      Serial.println(F("First press 1 CONNECT."));
      if(fromPhone) pl4WebStatus("PL4_NOT_CONNECTED");
    } else {
      liveMode=(liveMode==1)?0:1;
      livePolling=(liveMode==1);
      lastLiveSweepMs=millis()-LIVE_SWEEP_INTERVAL_MS;
      Serial.println(livePolling?F("LIVE ALL ON"):F("LIVE OFF"));
      if(fromPhone) pl4WebStatus(livePolling?"PL4_LIVE_ON":"PL4_LIVE_OFF");
    }
  } else if(command=='3') {
    actuatorMenu=false; readFaults();
  } else if(command=='4') {
    actuatorMenu=false;
    if(!ecuConnected || !uartReady) {
      Serial.println(F("First press 1 CONNECT."));
      if(fromPhone) pl4WebStatus("PL4_NOT_CONNECTED");
    } else clearFaults();
  } else if(command=='5') {
    if(!ecuConnected || !uartReady) {
      Serial.println(F("First press 1 CONNECT."));
      if(fromPhone) pl4WebStatus("PL4_NOT_CONNECTED");
    } else { livePolling=false;liveMode=0;actuatorMenu=true;printActuatorMenu(); }
  } else if(actuatorMenu || fromPhone) {
    const int chosen=actuatorIndexFromSelector(command);
    if(chosen>=0) performActuator((uint8_t)chosen);
    else Serial.println(F("Invalid actuator. Select A..K or X."));
  } else Serial.println(F("Unknown option. Use 1..5 or ?."));
}

static void pl4PhoneLine() {
  pl4BridgeLine[pl4BridgeLineLength]='\0';
  if(!strcmp(pl4BridgeLine,"!PROFILE:PL4")) {
    pl4ProfileSelected=true;
    pl4WebStatus("PROFILE_PL4_SELECTED");
  } else if(pl4BridgeLine[0]=='!' && pl4BridgeLine[1] &&
            pl4BridgeLine[2]=='\0') {
    if (pl4ProfileSelected) pl4Command(pl4BridgeLine[1],true);
    else pl4WebStatus("PROFILE_UNSUPPORTED_IN_PL4_FIRMWARE");
  } else if(pl4BridgeLine[0]=='!' && !strncmp(pl4BridgeLine,"!PROFILE:",9)) {
    pl4ProfileSelected=false;
    pl4WebStatus("PROFILE_UNSUPPORTED_IN_PL4_FIRMWARE");
  } else if(pl4BridgeLineLength) {
    pl4WebStatus("PL4_INVALID_COMMAND");
  }
  pl4BridgeLineLength=0;
}

void loop() {
  while (Serial.available()) pl4Command((char)Serial.read(), false);
  while (Serial2.available()) {
    char c=(char)Serial2.read();
    if(c=='\r' || c=='\n') {
      if(pl4BridgeLineLength) pl4PhoneLine();
    } else if(pl4BridgeLineLength+1<sizeof(pl4BridgeLine)) {
      pl4BridgeLine[pl4BridgeLineLength++]=c;
    } else {
      pl4BridgeLineLength=0;
      pl4WebStatus("PL4_INVALID_COMMAND");
    }
  }
  if(!ecuConnected || !uartReady) return;
  uint32_t now=millis();
  if(livePolling) {
    if((uint32_t)(now-lastLiveSweepMs)>=LIVE_SWEEP_INTERVAL_MS) {
      lastLiveSweepMs=millis();
      readAllLiveParameters(false);
    }
  } else if((uint32_t)(now-lastTesterPresentMs)>=TESTER_PRESENT_INTERVAL_MS) {
    (void)sendTesterPresentOnce();
    lastTesterPresentMs=millis();
  }
}
