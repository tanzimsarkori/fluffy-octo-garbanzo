# Yasir Diag Android 2.5 — Bosch Zamyad V14.3 protocol

## Connection

- Android app opens a TCP connection to the E-MAX ESP-01 access point at `192.168.4.1:3333`.
- The ESP-01 bridge must forward TCP payload bytes unchanged to the STM32 command UART and forward STM32 output lines back to the TCP client.
- Bosch V14.3 commands are single ASCII characters followed by a newline. Do not prepend `!`, `!ARM`, `!Txx`, or a profile command.
- The existing Siemens/legacy app command path remains unchanged and continues using its prefixed protocol.

## Bosch command map

| TCP command | Function |
|---|---|
| `1\n` | Connect / initialize ECU |
| `2\n` | Read DTCs |
| `3\n` | Clear DTCs immediately (the Android app shows a confirmation dialog first) |
| `4\n` | Dedicated MIL lamp test |
| `6\n` | Request the 19 live parameters |
| `A\n` | Fuel pump |
| `B\n` | Injector 1 — idle |
| `C\n` | Injector 1 — engine off |
| `D\n` | Injector 2 — idle |
| `E\n` | Injector 2 — engine off |
| `F\n` | Injector 3 — idle |
| `G\n` | Injector 3 — engine off |
| `H\n` | Injector 4 — idle |
| `I\n` | Injector 4 — engine off |
| `J\n` | Oxygen sensor heater |
| `K\n` | Stepper motor |
| `L\n` | Ignition coil 1–4 |
| `M\n` | Ignition coil 2–3 |
| `N\n` | Ventilation relay |
| `X\n` | Stop the currently timed actuator test early |
| `0\n` | Disconnect K-Line session |
| `?\n` | Print the STM32 main menu |

## Timings

The Android app does not send actuator durations. V14.3 owns the timing and automatic OFF behavior:
- Fuel pump, oxygen heater, and ventilation relay: 6 seconds.
- Injectors: 1.2-second test windows.
- Stepper motor: 1.5 seconds.
- Ignition coils: 0.7 seconds.
- MIL: dedicated 5-second test.

## Response display

The app accepts the existing JSON status/telemetry messages and also displays plain-text lines from STM32 in the Bosch diagnostic console. A line saying that ON was accepted is not proof of physical actuator movement. Check that OFF is confirmed after every test.

## Important integration dependency

This repository contains the Android app. It does not include the ESP-01 bridge firmware. Before vehicle testing, verify that the ESP-01 TCP server is a transparent UART bridge at 115200 baud (or the baud configured on the STM32 command UART), forwards newline-terminated commands without adding/removing characters, and returns STM32 serial output. If the ESP-01 firmware parses only the old exclamation-prefixed commands or emits only JSON, that bridge firmware must also be updated.
