#include <Arduino.h>
#include <WiFi.h>
#include <WiFiProv.h>

static const char *SERVICE_NAME = "PROV_123";
static const char *PROOF_OF_POSSESSION = "abcd1234";

static uint8_t provisioningUuid[16] = {
  0xb4, 0xdf, 0x5a, 0x1c, 0x3f, 0x6b, 0xf4, 0xbf,
  0xea, 0x4a, 0x82, 0x03, 0x04, 0x90, 0x1a, 0x02
};

void onSystemEvent(arduino_event_t *event) {
  switch (event->event_id) {
    case ARDUINO_EVENT_PROV_START:
      Serial.println("Provisioning started");
      break;
    case ARDUINO_EVENT_PROV_CRED_RECV:
      Serial.printf("Received SSID: %s\n", event->event_info.prov_cred_recv.ssid);
      break;
    case ARDUINO_EVENT_PROV_CRED_SUCCESS:
      Serial.println("Provisioning successful");
      break;
    case ARDUINO_EVENT_PROV_CRED_FAIL:
      Serial.println("Provisioning failed");
      break;
    case ARDUINO_EVENT_WIFI_STA_GOT_IP:
      Serial.print("IP address: ");
      Serial.println(WiFi.localIP());
      break;
    default:
      break;
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Starting ESP32-S3 WiFiProv BLE test firmware");
  Serial.printf("BLE service: %s, Security: 1, PoP: %s\n", SERVICE_NAME, PROOF_OF_POSSESSION);
  WiFi.onEvent(onSystemEvent);
  WiFi.begin();

  WiFiProv.beginProvision(
    NETWORK_PROV_SCHEME_BLE,
    NETWORK_PROV_SCHEME_HANDLER_FREE_BLE,
    NETWORK_PROV_SECURITY_1,
    PROOF_OF_POSSESSION,
    SERVICE_NAME,
    nullptr,
    provisioningUuid,
    true
  );

  WiFiProv.printQR(SERVICE_NAME, PROOF_OF_POSSESSION, "ble");
}

void loop() {}
