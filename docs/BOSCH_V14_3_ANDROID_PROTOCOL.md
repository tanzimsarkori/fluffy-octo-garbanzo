# Bosch Zamyad 7.9.7.1 integration — V50 / Yasir Diag 2.5

## Architecture

The dual-profile STM32 firmware is based on the proven Siemens V49 firmware and retains its existing hardware links:

- K-Line ECU: STM32 `Serial3`, PB10/PB11, 10400 baud.
- Existing ESP-01 link: STM32 `Serial2`, PA2/PA3, 115200 baud.
- Android TCP endpoint: existing app connection to `192.168.4.1:3333`.

No ESP-01 firmware change is required for this integration, provided the existing bridge continues forwarding newline-terminated TCP bytes unchanged to/from STM32 Serial2 as it already does for the working Siemens profile.

## ECU profile selection

The Android app sends one of these profile commands over the existing TCP socket:

- `!PROFILE:SIEMENS\n` — select the original V49 Siemens profile.
- `!PROFILE:BOSCH\n` — select Bosch Zamyad 7.9.7.1.

Switching profiles resets the active K-Line session. Reconnect to the ECU after changing profiles.

## Bosch commands

Once Bosch is selected, the Android app sends a single ASCII character followed by newline. There is no `!` prefix, `ARM` sequence, or `Y` confirmation.

| Command | Function |
|---|---|
| `1` | Fast-init Bosch ECU at physical address 0x11 |
| `2` | Read DTCs |
| `3` | Clear DTCs, verify positive response, then re-read |
| `4` | Dedicated MIL test, 5-second maximum, automatic OFF |
| `6` | One-shot read of 19 live parameters |
| `A` | Fuel pump — 6 seconds |
| `B` | Injector 1 — idle test |
| `C` | Injector 1 — engine-off test |
| `D` | Injector 2 — idle test |
| `E` | Injector 2 — engine-off test |
| `F` | Injector 3 — idle test |
| `G` | Injector 3 — engine-off test |
| `H` | Injector 4 — idle test |
| `I` | Injector 4 — engine-off test |
| `J` | Oxygen sensor heater — 6 seconds |
| `K` | Stepper motor |
| `L` | Ignition coil 1–4 |
| `M` | Ignition coil 2–3 |
| `N` | Ventilation relay — 6 seconds |
| `X` | Request early stop during a timed test |
| `0` | Disconnect Bosch K-Line session |

The firmware also polls the 19-row Bosch parameter block every 1600 ms while the Bosch session is active. Siemens adaptive 35-parameter ACK pacing is unchanged and runs only in the Siemens profile.

## Live-data JSON contract

The firmware emits the existing app-compatible `type=parameters` JSON envelope with `profile=bosch_zamyad_7971`. Parameter IDs match the app's existing Bosch table:

- `bosch_zamyad_fault_count`
- `bosch_zamyad_coolant`
- `bosch_zamyad_intake_air`
- `bosch_zamyad_battery`
- `bosch_zamyad_tps`
- `bosch_zamyad_rpm`
- `bosch_zamyad_spark_advance`
- `bosch_zamyad_knock_state`
- `bosch_zamyad_injection_system_state`
- `bosch_zamyad_fuel_pump_state`
- `bosch_zamyad_injection_state`
- `bosch_zamyad_engine_load_state`
- `bosch_zamyad_air_pressure`
- `bosch_zamyad_injection_time`
- `bosch_zamyad_target_idle_rpm`
- `bosch_zamyad_stepper_position`
- `bosch_zamyad_map`
- `bosch_zamyad_coil_charge_time`
- `bosch_zamyad_o2_voltage`

The five state rows sourced from byte d[14] remain raw because bit meanings/polarities are not verified. Other formulas are reference formulas from the extracted E-MAX Zamyad 7.9.7.1 profile and should be compared with live tester readings before being treated as calibrated measurements.

## Safety and validation

- Injector and coil test windows remain short; the six-second duration is limited to the fuel pump, oxygen heater, and ventilation relay.
- Each actuator test attempts OFF even when ON was not confirmed.
- An OFF-not-confirmed status is a stop condition: switch ignition OFF and inspect before further testing.
- A positive ECU response confirms command acceptance, not physical actuator movement.
- Static C++ syntax and app-source structure checks were performed. The exact STM32 Arduino core build, Android APK build, and vehicle hardware validation remain to be run on the target setup.
