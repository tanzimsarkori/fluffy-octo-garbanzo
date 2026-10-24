#include <Arduino.h>

// YASIR K-LINE V30 DTC FIX - based on proven V28 firmware
// Serial: PA9 TX / PA10 RX, 115200
// Serial3: PB10 TX / PB11 RX, 10400

const uint32_t K_BAUD = 10400;
const uint32_t RESPONSE_TIMEOUT_MS = 1100;
// ESP-01 UART: STM32 PA2 (USART2 TX) -> ESP RX, STM32 PA3 (USART2 RX) <- ESP TX
const uint32_t ESP_BAUD = 115200;
uint32_t lastWebTelemetry = 0;
bool espCommandArmed = false;
void sendWebDTC(uint8_t count);
void sendWebStatus(const char *message) {
  // Use plain string literals for compatibility with older STM32 Arduino cores.
  Serial2.print("{\"type\":\"status\",\"message\":\"");
  Serial2.print(message);
  Serial2.println("\"}");
}
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
// KWP frames: short 8N or extended 80 target source LEN payload checksum.
// TNM's 21 01 response starts: 80 F1 11 5F 61 01 ...
// 0x5F is a 95-byte payload length, NOT the service byte.
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
    // A full extended frame at 10400 baud takes ~100ms. Do not use
    // the old 22ms gap as a reason to discard a valid incomplete frame.
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
}

void readDTC() {
  if (!connected) {
    Serial.println("CONNECT ECU FIRST (1)");
    sendWebStatus("DTC_READ_FAILED_NOT_CONNECTED");
    return;
  }

  Serial.println("READING DTC...");
  sendWebStatus("DTC_READING");

  const uint8_t cmd[] = {0x84,0x11,0xF1,0x18,0x00,0xFF,0x00,0x9D};
  if (!exchange(cmd, sizeof(cmd), 0x58, 1400)) {
    Serial.println("DTC READ FAILED");
    sendWebStatus("DTC_READ_FAILED_ECU_NO_VALID_RESPONSE");
    return;
  }

  const uint8_t si = serviceOffset(rx);
  if (rxLen <= si + 1) {
    Serial.println("SHORT DTC FRAME");
    sendWebStatus("DTC_READ_FAILED_SHORT_FRAME");
    return;
  }

  // Siemens response example: 85 F1 11 58 01 05 05 E7 D1
  // serviceOffset=3, 0x58=response service, 0x01=count,
  // then each DTC record is 3 bytes (code high, code low, status).
  if (rx[si] != 0x58 || rxLen < (uint16_t)(si + 3)) {
    Serial.println("UNEXPECTED DTC RESPONSE HEADER");
    printData("DTC RAW FRAME: ", rx, rxLen);
    sendWebStatus("DTC_READ_FAILED_UNEXPECTED_RESPONSE_FORMAT");
    return;
  }

  uint8_t count = rx[si + 1];
  const uint16_t dataStart = (uint16_t)si + 2;
  const uint16_t availableBytes = rxLen - dataStart - 1;
  if (availableBytes < (uint16_t)count * 3) {
    Serial.print("INCOMPLETE DTC RECORDS: count=");
    Serial.print(count);
    Serial.print(" bytes=");
    Serial.println(availableBytes);
    printData("DTC RAW FRAME: ", rx, rxLen);
    sendWebStatus("DTC_READ_FAILED_INCOMPLETE_RECORDS");
    return;
  }

  Serial.print("DTC COUNT: "); Serial.println(count);
  sendWebDTC(count);

  if (count == 0) {
    Serial.println("NO DTC CODES REPORTED BY ECU");
  } else {
    for (uint8_t i = 0; i < count; ++i) {
      uint16_t j = dataStart + (uint16_t)i * 3;
      Serial.print("DTC "); Serial.print(i + 1); Serial.print(": ");
      hexByte(rx[j]); Serial.print(' '); hexByte(rx[j+1]);
      Serial.print(" STATUS: "); hexByte(rx[j+2]);
      if (rx[j] == 0x05 && rx[j+1] == 0x05) Serial.print(" (P0505 IDLE CONTROL)");
      if (rx[j] == 0x02 && rx[j+1] == 0x30) Serial.print(" (FUEL PUMP RELAY - TESTED)");
      Serial.println();
    }
  }
  sendWebStatus(count == 0 ? "DTC_READ_OK_NO_CODES" : "DTC_READ_OK");
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
  if (exchange(cmd, sizeof(cmd), 0x54, 1500) &&
      rxLen == 7 && rx[4] == 0xFF && rx[5] == 0x00) {
    Serial.println("CLEAR COMMAND ACCEPTED BY ECU");
    sendWebStatus("DTC_CLEAR_ACCEPTED_RECHECKING");
    delay(300);
    readDTC();
  } else {
    Serial.println("CLEAR NOT CONFIRMED");
    sendWebStatus("DTC_CLEAR_FAILED_ECU_DID_NOT_CONFIRM");
  }
}

// Read-only local identifier used by TNM for the long Siemens data block.
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
  // Index each byte to make comparison with tester temperatures easy.
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


// V26: Foreign C# decoding for all identified fields; unverified fields labeled.
// This function validates the long reply and copies exactly 93 data bytes.
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

// Source: SIM2K-34VR C# project: par[1] battery, par[3] coolant,
// par[5] intake; par[20]/par[21] RPM. par[1] maps to d[0]
// because the C# array includes the local identifier at par[0].
// Foreign C# par[0] is local identifier 0x01; par[N] => d[N-1].
// C# source: Form1.cs + dashboard.cs in the supplied SIM2K-34VR ZIP.
// Unverified ECU-specific calculations are clearly labelled.
uint16_t foreignWord(const uint8_t *d, uint8_t parLow) {
  return (uint16_t)d[parLow-1] + 255U * (uint16_t)d[parLow];
}
float sparkAngle(uint8_t raw) {
  return (255.0f - raw) * 0.42857f - 27.857f;
}

// Send compact, newline-delimited JSON to ESP-01 over USART2 (PA2/PA3).
// Only values from a checksum-validated Siemens 21 01 block are sent.
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
  Serial2.print(F("],\"tps\":null,\"tpsRaw\":null,\"raw\":["));
  for (uint8_t i=0; i<93; ++i) { if (i) Serial2.print(','); Serial2.print(d[i]); }
  Serial2.println(F("]}"));
}

void hexByteJson(uint8_t b) { if (b < 16) Serial2.print('0'); Serial2.print(b, HEX); }

void sendWebDTC(uint8_t count) {
  Serial2.print(F("{\"type\":\"dtc\",\"count\":")); Serial2.print(count); Serial2.print(F(",\"codes\":["));
  uint16_t dataStart = (uint16_t)serviceOffset(rx) + 2;
  for (uint8_t i=0; i<count; ++i) {
    uint16_t j = dataStart + (uint16_t)i * 3; if (j+2 >= rxLen-1) break;
    if (i) Serial2.print(',');
    Serial2.print(F("{\"code\":\"")); hexByteJson(rx[j]); Serial2.print(' '); hexByteJson(rx[j+1]);
    Serial2.print(F("\",\"status\":\"")); hexByteJson(rx[j+2]); Serial2.print(F("\"}"));
  }
  Serial2.println(F("]}"));
}
void showV26Values(const uint8_t *d) {
  uint16_t rpmCSharp = foreignWord(d, 20);
  float coolant = (255.0f - d[2]) * 0.5f - 12.0f;
  float intake = (255.0f - d[4]) * 0.5f - 12.0f;
  float battery = d[0] / 10.0f;
  // Commented-out MAP formula in Form1.cs, par[26] => d[25].
  float mapCandidate = d[25] * 94.0f / 255.0f + 10.4f;

  Serial.println("\n========== YASIR ECU PANEL V27 ==========");
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
  // Live candidate values: deliberately unlabeled so they can be compared
  // against the external tester while changing one parameter at a time.
  const uint8_t extraParLow[] = {16,59,57,73,22,12};
  Serial.print("EXTRA RAW: ");
  for (uint8_t i=0; i<sizeof(extraParLow); ++i) {
    if (i) Serial.print(", ");
    Serial.print(foreignWord(d, extraParLow[i]));
  }
  Serial.println();
  Serial.print("RAW coolant d[2]=0x"); hexByte(d[2]); Serial.println();
  Serial.print("RAW intake  d[4]=0x"); hexByte(d[4]); Serial.println();
  Serial.print("RAW battery d[0]=0x"); hexByte(d[0]); Serial.println();
  Serial.println("TPS: NO FORMULA FOUND IN FOREIGN SOURCE");
  Serial.println("C# DERIVED VALUES: CHECK AGAINST TESTER/TNM");
  Serial.println("===========================================");
}

void panel() {
  uint8_t d[93];
  if (!fetchBlock(d)) { Serial.println("PANEL: BLOCK READ FAILED"); return; }
  showV26Values(d);
  // Live display stays compact. Use A to inspect changed bytes.
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
  showDifferences(baselineBlock, current, "BASELINE -> CURRENT:");
  Serial.println("BASELINE PRESERVED; PRESS '0' TO REPLACE IT.");
}


// Complete foreign C# par[] display. In the source par[0] is local ID 01;
// par[1..93] map to the 93-byte Siemens data payload d[0..92].
// Only documented sensor positions receive labels; other bytes stay RAW.
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


// CSV output for saving real-time ECU measurements in Serial Monitor.
// Every row comes from one checksum-validated 21 01 reply.
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


/* V35 LAB ONLY - independent of the verified V34 production firmware.
   Read-only snapshots from validated Siemens 21 01 replies.
   No ECU clear/reset/actuator commands exposed. */
uint8_t labSamples[3][93];
bool labValid[3]={false,false,false};
const char* labNames[3]={"CLOSED","HALF","FULL"};
void labCapture(uint8_t slot) {
  uint8_t d[93];
  if(!fetchBlock(d)) { Serial.println("LAB_CAPTURE_FAILED"); return; }
  memcpy(labSamples[slot],d,93); labValid[slot]=true;
  Serial.print("LAB_CAPTURE_OK,"); Serial.println(labNames[slot]);
  Serial.println("index,hex,decimal");
  for(int i=0;i<93;i++) {
    Serial.print(i);Serial.print(",");
    hexByte(d[i]);Serial.print(",");Serial.println(d[i]);
  }
}
void labDiff(uint8_t a,uint8_t b) {
  if(!labValid[a]||!labValid[b]) { Serial.println("CAPTURE_BOTH_STATES_FIRST"); return; }
  Serial.print("COMPARE,");Serial.print(labNames[a]);Serial.print(",");Serial.println(labNames[b]);
  Serial.println("index,old_dec,new_dec,delta");
  int changed=0;
  for(int i=0;i<93;i++) if(labSamples[a][i]!=labSamples[b][i]) {
    Serial.print(i);Serial.print(",");Serial.print(labSamples[a][i]);Serial.print(",");
    Serial.print(labSamples[b][i]);Serial.print(",");
    Serial.println((int)labSamples[b][i]-(int)labSamples[a][i]);changed++;
  }
  Serial.print("CHANGED_BYTES,");Serial.println(changed);
}
// Command 7: inspect six candidate 16-bit fields copied from the foreign SIM2K-34VR C# source.
// Source uses par[] indexed from 1; our d[] is zero-based, so par[n] maps to d[n-1].
// Preserve the source's multiplier 255 (not 256) for direct comparison with the reference app.
uint16_t candidatePair(uint8_t highIndex, uint8_t lowIndex, const uint8_t *d) {
  return (uint16_t)d[highIndex] * 255U + d[lowIndex];
}
void printCandidate(const char *name, uint8_t parHigh, uint8_t parLow, const uint8_t *d) {
  uint8_t hiIndex = parHigh - 1;
  uint8_t loIndex = parLow - 1;
  uint16_t value = candidatePair(hiIndex, loIndex, d);
  Serial.print(name); Serial.print(",par["); Serial.print(parHigh);
  Serial.print("],par["); Serial.print(parLow);
  Serial.print("],d["); Serial.print(hiIndex);
  Serial.print("],d["); Serial.print(loIndex);
  Serial.print("],raw_hi="); Serial.print(d[hiIndex]);
  Serial.print(",raw_lo="); Serial.print(d[loIndex]);
  Serial.print(",candidate="); Serial.println(value);
}
void printCandidateParameters() {
  uint8_t d[93];
  if (!fetchBlock(d)) {
    Serial.println("CANDIDATE_READ_FAILED");
    return;
  }
  Serial.println("CANDIDATE_PARAMETERS_BEGIN");
  Serial.println("name,source_par_high,source_par_low,d_high,d_low,raw_hi,raw_lo,candidate_value");
  printCandidate("P7", 17, 16, d);
  printCandidate("P8", 60, 59, d);
  printCandidate("P9", 58, 57, d);
  printCandidate("P10", 74, 73, d);
  printCandidate("P11", 23, 22, d);
  printCandidate("P12", 13, 12, d);
  Serial.println("NOTE=Candidate values are raw combinations only; names/units are not confirmed.");
  Serial.println("RAW_BYTES,index,decimal,hex");
  for (uint8_t i=0; i<93; i++) {
    Serial.print("RAW,"); Serial.print(i); Serial.print(",");
    Serial.print(d[i]); Serial.print(","); hexByte(d[i]); Serial.println();
  }
  Serial.println("CANDIDATE_PARAMETERS_END");
}

// Command 8: scan every payload byte as an unlabeled candidate for the OBD formulas
// shown by the user. This is a discovery aid only: OBD PIDs are not proven to map
// directly onto this KWP2000 93-byte local-ID payload.
void printObdFormulaCandidates() {
  uint8_t d[93];
  if (!fetchBlock(d)) {
    Serial.println("CMD8_READ_FAILED");
    return;
  }
  Serial.println("\n=== CMD8 CANDIDATE SCAN: NOT CONFIRMED PID MAPPING ===");
  Serial.println("Each row applies candidate formulas to one raw byte A=d[i] and adjacent pair A=d[i],B=d[i+1].");
  Serial.println("idx,raw,A-40,A_degC_ambient,MAP_kPa_candidate,percent_100A255,speed_A,timing_A2minus64,fuelpress_3A,trim_pct,fuelvolt_A200,pair_RPM_256AplusB_div4,pair_MAF_div100,pair_ECU_V_div1000,pair_runtime_raw");
  for (uint8_t i=0; i<93; ++i) {
    float a = (float)d[i];
    Serial.print(i); Serial.print(",");
    Serial.print(d[i]); Serial.print(",");
    Serial.print(a - 40.0f, 1); Serial.print(",");
    Serial.print(a - 40.0f, 1); Serial.print(",");
    Serial.print(a, 1); Serial.print(",");
    Serial.print(100.0f * a / 255.0f, 1); Serial.print(",");
    Serial.print(a, 1); Serial.print(",");
    Serial.print(a / 2.0f - 64.0f, 1); Serial.print(",");
    Serial.print(3.0f * a, 1); Serial.print(",");
    Serial.print(100.0f * (a - 128.0f) / 128.0f, 1); Serial.print(",");
    Serial.print(a / 200.0f, 3); Serial.print(",");
    if (i < 92) {
      uint16_t word255 = (uint16_t)d[i] * 255U + d[i+1];
      uint16_t word256 = (uint16_t)d[i] * 256U + d[i+1];
      Serial.print(word256 / 4.0f, 1); Serial.print(",");
      Serial.print(word256 / 100.0f, 2); Serial.print(",");
      Serial.print(word256 / 1000.0f, 3); Serial.print(",");
      Serial.print(word256);
      // Keep the foreign project's x255 candidate visible as a separate diagnostic.
      Serial.print(",word255="); Serial.print(word255);
    } else {
      Serial.print(",,,,");
    }
    Serial.println();
  }
  Serial.println("NOTE: These are formula candidates applied to every byte position, NOT identified sensors.");
  Serial.println("Change one tester value at a time, press 8 again, and compare which raw index/candidate changes.");
  Serial.println("=== CMD8 END ===");
}

// Live menu view of OBD-II formulas supplied by the user.
// d[] positions in this proprietary Siemens 21 01 block are not proven to map
// directly to standard OBD PIDs; these are candidate conversions for comparison.
void liveObdParameterCandidates() {
  uint8_t d[93];
  if (!fetchBlock(d)) { Serial.println("LIVE PARAMETER READ FAILED"); return; }

  Serial.println("\n========== YASIR ECU LIVE PARAMETER PANEL ==========");
  Serial.println("OBD-II FORMULA CANDIDATES - RAW BYTE MAPPING NOT CONFIRMED");
  Serial.println("Each row is one raw byte; values are candidates only, not identified sensors.");
  Serial.println("----------------------------------------------------");
  for (uint8_t i=0; i<93; ++i) {
    float a=(float)d[i];
    Serial.print("d["); if(i<10) Serial.print("0"); Serial.print(i); Serial.print("] RAW=");
    Serial.print(d[i]); Serial.print(" | TEMP(A-40)="); Serial.print(a-40.0f,1);
    Serial.print(" | PCT(100A/255)="); Serial.print(100.0f*a/255.0f,1); Serial.print("%");
    Serial.print(" | MAP(A candidate)="); Serial.print(a,1); Serial.print(" kPa");
    Serial.print(" | SPEED(A candidate)="); Serial.print(a,1); Serial.print(" km/h");
    Serial.print(" | TIMING(A/2-64)="); Serial.print(a/2.0f-64.0f,1); Serial.print(" deg");
    Serial.print(" | FUEL PRESS(3A)="); Serial.print(3.0f*a,1); Serial.print(" kPa");
    Serial.print(" | TRIM="); Serial.print(100.0f*(a-128.0f)/128.0f,1); Serial.print("%");
    Serial.print(" | O2(A/200)="); Serial.print(a/200.0f,3); Serial.println(" V");
    if (i<92) {
      uint16_t word=(uint16_t)d[i]*256U+d[i+1];
      Serial.print("   PAIR d["); if(i<10) Serial.print("0"); Serial.print(i);
      Serial.print(":"); if(i+1<10) Serial.print("0"); Serial.print(i+1);
      Serial.print("] = "); Serial.print(word);
      Serial.print(" | RPM/4="); Serial.print(word/4.0f,1);
      Serial.print(" | MAF/100="); Serial.print(word/100.0f,2);
      Serial.print(" | ECU_V/1000="); Serial.println(word/1000.0f,3);
    }
  }
  Serial.println("Change one tester parameter at a time, then send 2 again.");
  Serial.println("====================================================");
}

void labCsv() {
  Serial.print("state");
  for(int i=0;i<93;i++){Serial.print(",d");Serial.print(i);}
  Serial.println();
  for(int s=0;s<3;s++) if(labValid[s]) {
    Serial.print(labNames[s]);
    for(int i=0;i<93;i++){Serial.print(",");Serial.print(labSamples[s][i]);}
    Serial.println();
  }
}

void menu() {
 Serial.println("\\n=== YASIR V35 LAB - READ ONLY ===");
 Serial.println("1 = CONNECT ECU");
  Serial.println("2 = LIVE PARAMETER MENU (OBD formula candidates)");
 Serial.println("S = SNAPSHOT CLOSED THROTTLE");
 Serial.println("T = SNAPSHOT HALF THROTTLE");
 Serial.println("U = SNAPSHOT FULL THROTTLE");
 Serial.println("D = DIFF CLOSED vs HALF");
 Serial.println("E = DIFF CLOSED vs FULL");
 Serial.println("F = DIFF HALF vs FULL");
 Serial.println("X = EXPORT ALL 93 BYTES CSV");
 Serial.println("5 = READ RAW BLOCK");
 Serial.println("7 = PRINT SIX CANDIDATE PARAMETERS + ALL RAW BYTES");
 Serial.println("8 = SCAN ALL 93 BYTES WITH OBD FORMULA CANDIDATES");
 Serial.println("H = HELP");
 Serial.println("NO CLEAR/RESET/ACTUATOR COMMANDS");
}
void handleCommand(char c) {
 if(c=='1') connectECU();
  else if(c=='2') liveObdParameterCandidates();
 else if(c=='S'||c=='s') labCapture(0);
 else if(c=='T'||c=='t') labCapture(1);
 else if(c=='U'||c=='u') labCapture(2);
 else if(c=='D'||c=='d') labDiff(0,1);
 else if(c=='E'||c=='e') labDiff(0,2);
 else if(c=='F'||c=='f') labDiff(1,2);
 else if(c=='X'||c=='x') labCsv();
 else if(c=='5') readSiemensBlock();
 else if(c=='7') printCandidateParameters();
 else if(c=='8') printObdFormulaCandidates();
 else if(c=='H'||c=='h'||c=='4') menu();
}

void setup() {
  Serial.begin(115200);       // USB-TTL diagnostic console on PA9/PA10
  Serial2.begin(ESP_BAUD);    // ESP-01 UART on PA2/PA3
  Serial3.begin(K_BAUD);      // K-Line on PB10/PB11
  delay(1200);
  menu();
  Serial2.println(F("{\"type\":\"status\",\"message\":\"STM32_READY\"}"));
}
void loop() {
 while(Serial.available()) handleCommand((char)Serial.read());
 // ESP-01 input intentionally ignored in this laboratory firmware.
 while(Serial2.available()) Serial2.read();
}
