# Bosch Zamyad 7.9.7.1 integration — V50 / Yasir Diag 2.5

## Architecture

The dual-profile firmware is based on the Siemens V47 sketch supplied with the project and keeps the existing links:

- K-Line: STM32 `Serial3`, PB10/PB11, 10400 baud.
- ESP-01: STM32 `Serial2`, PA2/PA3, 115200 baud.
- Android TCP endpoint: `192.168.4.1:3333`.

No ESP-01 firmware change is required if the current bridge forwards newline-terminated TCP messages unchanged to and from STM32 Serial2.

The combined sketch is in `firmware/YASIR_KLINE_V50_DUAL_PROFILE/`. Open `YASIR_KLINE_V50_DUAL_PROFILE.ino` in the Arduino IDE; keep the adjacent `.cpp` and `.h` files in the sketch folder.

## ECU profile selection

The Android app sends one of these commands over the existing TCP socket:

- `!PROFILE:SIEMENS\n` — select the Siemens profile.
- `!PROFILE:BOSCH\n` — select Bosch Zamyad 7.9.7.1.

Changing profiles resets the K-Line session. Reconnect to the ECU after switching.

## Bosch commands

After selecting Bosch, Android sends `!` followed by one ASCII key and a newline:

| Command | Function |
|---|---|
| `1` | Connect to Bosch at physical address 0x11 |
| `2` | Read DTCs |
| `3` | Clear DTCs after app confirmation, then reread |
| `4` | MIL test, up to five seconds, automatic OFF |
| `6` | Read 19 live parameters |
| `A`–`N` | Run the selected actuator test |
| `X` | Stop a timed test early |
| `0` | Disconnect the Bosch K-Line session |

Siemens retains the existing V47 commands and Wi-Fi link.

## Live data and limitations

Bosch live data uses the app's existing `type=parameters` JSON contract with profile `bosch_zamyad_7971`; the firmware polls every 1600 ms. The five status rows sourced from d[14] remain raw because their bit meanings are not verified. Other values use the extracted E-MAX Zamyad 7.9.7.1 reference formulas and need comparison with live scan-tool readings before being treated as calibrated measurements.

Actuator commands apply specifically to Bosch Zamyad 7.9.7.1. Each timed test attempts OFF afterward. If OFF is not confirmed, switch ignition off and inspect before continuing. A positive ECU response confirms command acceptance, not physical actuator movement.

## Validation status

No STM32 build, Android APK build, or vehicle test is claimed. Validate parameter readings against a scan tool before relying on Bosch conversions or operating actuators.
