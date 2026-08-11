# Companion firmware

`arduino/WiFiProvDemo/WiFiProvDemo.ino` is the smallest companion firmware for the demo app:

- BLE service: `PROV_123`
- Security: Security 1
- Proof of Possession: `abcd1234`
- Provisioning service UUID: Espressif sample UUID

For a generic 4 MB ESP32 in Arduino IDE, choose **Tools → Partition Scheme → Huge APP (3 MB No OTA/1 MB SPIFFS)**. Arduino-ESP32 3.3.11 produced a 1.71 MB sketch during validation, which does not fit the default 1.25 MB app partition.

Equivalent CLI build:

```bash
arduino-cli compile \
  --fqbn esp32:esp32:esp32 \
  --board-options PartitionScheme=huge_app \
  firmware/arduino/WiFiProvDemo
```

The plugin also works with ESP-IDF's `network_provisioning` component. Configure the same transport, security scheme, username/PoP, service name, and optional custom endpoints on both sides.
