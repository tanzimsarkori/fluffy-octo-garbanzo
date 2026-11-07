# Nissan Zamyad petrol — Ezam SB13 CAN profile

## Current scope

This profile implements **generic OBD-II over ISO 15765-4 CAN**. It probes all four standard CAN variants in this order: 11-bit/500 kbit/s, 29-bit/500 kbit/s, 11-bit/250 kbit/s, then 29-bit/250 kbit/s.

- 11-bit functional request: 0x7DF; ECU responses: 0x7E8–0x7EF
- 29-bit functional request: 0x18DB33F1; ECU responses: 0x18DAF100–0x18DAF1FF
- Multi-frame 29-bit replies receive flow-control frames addressed back to the ECU
- Mode 01: standard powertrain PIDs
- Mode 03: stored generic diagnostic trouble codes
- Mode 04: clear emissions-related diagnostic information
- Basic ISO-TP single-frame and multi-frame response handling

The Android screen is labelled for Nissan Zamyad / Ezam SB13 because that is the user's target ECU. The code does **not** implement a verified Ezam-specific diagnostic protocol. Manufacturer-specific DTCs, actuator tests, adaptations, ECU coding, and programming are not included. The vehicle's ECU must answer standard OBD-II CAN requests for these functions to work.

Live PIDs currently decoded: RPM, coolant temperature, intake-air temperature, vehicle speed, manifold absolute pressure, throttle position, and ECU voltage. They are requested sequentially.

## Firmware dependency

Install the **eXoCAN** Arduino library (repository: https://github.com/exothink/eXoCAN) in Arduino IDE's Library Manager or the STM32 Arduino libraries folder before compiling this sketch. The STM32F103 CAN peripheral uses PA11 (CAN RX) and PA12 (CAN TX); the profile selects eXoCAN's `PORTA_11_12_XCVR` mapping and probes both 500 and 250 kbit/s.

This source has not been compiled or validated against a vehicle in this change.

## TJA1050 module wiring

Connect the transceiver logic side to the STM32 and vehicle side to the OBD connector:

| TJA1050 module | STM32 / vehicle |
| --- | --- |
| TX | PA12 / CAN_TX |
| RX | PA11 / CAN_RX |
| GND | Common logic ground |
| VCC | Regulated 5 V supply, if required by the exact module |
| CANH | OBD-II pin 6 |
| CANL | OBD-II pin 14 |

The photo shows a TJA1050-style board, but check the labels on the exact board and its supply arrangement before powering it. Keep the vehicle bus wiring short and twisted. Avoid adding a second 120-ohm termination across CANH/CANL when the vehicle bus is already terminated; inspect the board's resistor/jumper and measure the actual setup before connecting.

## Android commands

After selecting SB13 and connecting to the device over Wi-Fi:

- `!PROFILE:SB13_CAN`: select this firmware profile
- `!1`: probe Mode 01 PID 00 and enable live reads if a response arrives
- `!2`: read Mode 03 stored DTCs
- `!3`: send Mode 04 clear request
- `!6`: enable sequential standard live PID reads
- `!0`: stop live reads / mark CAN session disconnected

Clear requests can erase emissions diagnostic information and associated freeze-frame data. Use them only when intended.

## Notes

A successful CAN electrical connection does not prove that SB13 supports generic OBD-II diagnostics on this connector. If the ECU uses a different bitrate, connector routing, addressing format, or only a proprietary service, this profile will report no response and needs verified vehicle-specific documentation or captures before extension.
