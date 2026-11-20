# VALEO PL4_S1C → Yasir Diag Android (V19.5 ESP companion)

## What has been added

- A new **VALEO PL4_S1C** ECU selection under **Iran Khodro / Peugeot → Petrol**.
- A separate Android screen with five actions, following the already tested STM32 menu:
  1. Connect
  2. Live parameters (start / stop)
  3. Read DTC (current and stored) with Persian reference descriptions
  4. Clear DTC after an explicit Android confirmation
  5. Actuators A–K (11 commands; no Y)
- A standalone STM32F103CB / MC33290 PL4 sketch based on the user-tested **V19.5** core with a new ESP-01 JSON transport layer.
- 32 labeled live fields using the **same response indexes and formulas** already in V19.5.
- 104 reference DTC descriptions. Unknown/PL4-specific codes remain visible as raw hex and should **not** be guessed.

## Important firmware compatibility boundary

The existing repository Siemens/Bosch/SB13 firmware is **not** silently overwritten and **does not** become a multi-ECU binary just by adding a phone menu. To use this PL4 phone page, flash:

`firmware/pl4/PL4_S1C_V19_5_ESP_BRIDGE.ino`

This sketch supports **PL4 only**, not the other ECU profiles. Reflash the original ECU firmware when switching to Siemens/Bosch if you have not built a validated combined firmware. The PL4 sketch explicitly rejects other `!PROFILE:...` selections.

- STM32 K-Line: USART3 PB10/PB11, 10400 baud, MC33290.
- STM32 ↔ existing ESP-01: USART2 PA2/PA3, 115200 baud, ESP serial bridge.
- Android transport: Wi-Fi TCP `192.168.4.1:3333` using the same bridge as the original app.
- Power levels and ESP-01 wiring must follow your existing tested adapter; STM32's UART lines are **not** connected directly to an ECU's 12V K-Line.
- Open the .ino in Arduino IDE 1.8.19, use Generic STM32F103CB / 128K / 20K / 72MHz / Maple core as before.

## Android install / test

Use the GitHub Actions `Build YASIR DIAG APK` artifact for the branch after a green build. Install the new APK while keeping a copy of the old APK. From the app select **Iran Khodro** or **Peugeot → petrol → VALEO PL4_S1C**.

Connect the phone to the ESP-01 Wi-Fi. Tap **1 Connect** and wait for the **PL4_ECU_CONNECTED** status before trying other actions.

Tap **2 Live** for 8 PID groups. Tap again to stop. Tap **3** to read two DTC banks; duplicates may appear because the same DTC can occur in both banks. Tap **4** only with the engine OFF and stable supply. Tap **5** to show the A–K test buttons; each asks for separate confirmation.

## Serial2 bridge contract

The phone sends newline-terminated commands:

| Android request | STM32 action |
|---|---|
| `!PROFILE:PL4\n` | Enable PL4 profile; status `PROFILE_PL4_SELECTED` |
| `!1\n` | Existing V19.5 Fast Init; status `PL4_ECU_CONNECTED` on valid C1 |
| `!2\n` | Toggle live sweep; JSON `type=parameters, profile=valeo_pl4_s1c` |
| `!3\n` | Read DTC banks `17 FF 00`, `17 FF FF`; JSON `type=dtc` |
| `!4\n` | Guarded V19.5 erase sequence; only `54 FF 00` confirms |
| `!5\n` | Open actuator menu |
| `!A\n` … `!K\n` | One short actuator test each, with ECU engine-off guard |
| `!X\n` | Cancel/return (not a guaranteed hardware emergency-stop mechanism) |

All phone output is line-delimited UTF-8 JSON on STM32 Serial2. USB Serial remains usable for human-readable diagnostics; the original USB 1–5/A–K commands stay supported.

## Safety and scope

The DTC-clear path includes the observed `0x34` RequestDownload preparation and is potentially memory-sensitive. **No** 0x36 or 0x37 data-transfer service, firmware flashing, security bypass or calibration writes have been added. The sketch checks engine RPM zero from a fresh C0 reading and validates 0x74, 0x7E and the final 0x54 response before calling erase successful. Do not experiment with this on a different ECU variant.

Actuators operate for short, bounded periods with a second-phase stop attempt. Use a safely prepared bench; prevent fuel spray, ignition sparks, or unexpected fan movement. An ECU ACK is not proof of physical movement; an OFF ACK cannot be guaranteed across hardware faults.

## Verification

Android source is included in the GitHub Actions build. **Physical Android + ESP-01 + STM32 + PL4 end-to-end testing is not yet verified in this branch.** The tested serial-console V19.5 remains the reference implementation. The PL4-specific ESP-01 companion sketch has not been compiled in the target Arduino IDE or tested on hardware as part of this change.

Original firmware profiles have been preserved. The additional PL4 UI does not claim to auto-detect the ECU.
