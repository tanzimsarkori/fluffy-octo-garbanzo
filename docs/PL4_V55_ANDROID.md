# YASIR DIAG 2.7.8 + firmware V55

Application baseline: the original main branch at version 2.7.3. Original Wi-Fi/TCP send and auto-scan functions are retained. The PL4 controller uses MainActivity.send directly and only changes PL4 routing.

Firmware: V55 project based on the user's original RAR. Install matched V55 firmware and 2.7.8 application together. Select VALEO PL4_S1C under the Iranian vehicle gasoline catalog; the menu appears only after ECU connection acknowledgement.

* !PROFILE:PL4 — profile selection, then !1 — connection.
* !PL4:2 — parameters, !PL4:3 — DTC read, !PL4:4 — DTC clear, !PL4:5 — actuator menu, !PL4:A through !PL4:K — actuator, !PL4:X — stop/back.
* Siemens, Bosch and SB13 use their original commands. New operation prefix isolates PL4 from legacy commands.
* Engine off and stable power required for clear and actuator operations. Only the confirmed PL4_S1C ECU is supported.

No UART test buttons, no separate socket, no changes to the legacy automatic scan stages. Hardware verification is still outstanding.
