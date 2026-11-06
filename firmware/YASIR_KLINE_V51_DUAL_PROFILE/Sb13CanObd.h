#pragma once
#include <Arduino.h>

namespace Sb13CanObd {
  void initialize();
  void handleKey(char key);
  void poll();
}
