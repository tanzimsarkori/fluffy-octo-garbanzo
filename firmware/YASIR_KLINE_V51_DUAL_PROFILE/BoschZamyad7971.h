#pragma once
#include <Arduino.h>
namespace BoschFirmware {
void initialize();
void disconnect();
bool isConnected();
void handleKey(char key);
void poll();
}
