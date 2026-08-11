# capacitor-wifiprov

Capacitor 8 native plugin for provisioning ESP32 devices with Espressif's official protocol over BLE or SoftAP. It bridges the official Android and iOS provisioning SDKs instead of reimplementing the protobuf/security protocol in JavaScript.

## Features

- BLE discovery with live scan events and discovered-device snapshots
- Direct BLE or SoftAP device creation
- Espressif provisioning QR payload import
- Security 0, Security 1 (Proof of Possession), and Security 2 (username + PoP)
- Secure session connection and capability/version inspection
- Scan Wi-Fi networks through the ESP32
- Send Wi-Fi credentials and receive provisioning progress
- Custom endpoint requests with UTF-8, hexadecimal, or Base64 payloads
- Reset stored Wi-Fi credentials
- Capacitor permission checks and native event listeners
- Minimal Capacitor/Vite demo for every public API

The JavaScript API is intentionally platform-neutral. Android uses `esp-idf-provisioning-android` 2.4.4; iOS uses `ESPProvision` 3.1.0.

## Requirements

- Capacitor 8
- Android API 26+, compile/target SDK 36, Java 21
- iOS 15+
- An ESP32 firmware using Espressif `network_provisioning`, or the compatible Arduino-ESP32 `WiFiProv` API

## Install

```bash
npm install capacitor-wifiprov
npx cap sync
```

### Android configuration

The plugin manifest contributes the BLE, Wi-Fi, network, and Android 12/13 runtime permissions. Espressif's Android SDK is distributed through JitPack, so JitPack must also be visible to the **application root project**. Add this to `android/build.gradle` if it is not already present:

```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### iOS configuration

Add user-facing descriptions to `ios/App/App/Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Discover the ESP32 and securely transfer Wi-Fi configuration.</string>
<key>NSLocalNetworkUsageDescription</key>
<string>Connect to the ESP32 temporary access point for provisioning.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Identify the current Wi-Fi network for ESP32 provisioning.</string>
```

For SoftAP provisioning, enable **Signing & Capabilities → Hotspot Configuration** and **Access WiFi Information** for the app target. BLE-only applications do not need those entitlements. Permission prompts must be tested on a physical iPhone/iPad.

## Basic BLE flow

```ts
import { WiFiProv } from 'capacitor-wifiprov';

await WiFiProv.requestPermissions();

const scanListener = await WiFiProv.addListener('bleScanResult', device => {
  console.log(device.name, device.id, device.rssi);
});

await WiFiProv.startBleScan({ prefix: 'PROV_', security: 1 });
// Choose one of the emitted devices, then:
await WiFiProv.selectDevice({
  id: 'native-device-id',
  security: 1,
  pop: 'abcd1234',
});
await WiFiProv.connect();

const { networks } = await WiFiProv.scanWifiNetworks();
console.table(networks);

await WiFiProv.provision({
  ssid: 'My Wi-Fi',
  password: 'secret-password',
});

await scanListener.remove();
```

## QR payload flow

The native Espressif SDK accepts the raw JSON encoded in its provisioning QR code. QR camera scanning is deliberately not bundled, so the app can use any Capacitor barcode plugin and pass its result here:

```ts
await WiFiProv.createDeviceFromQr({
  payload: JSON.stringify({
    ver: 'v1',
    name: 'PROV_123',
    pop: 'abcd1234',
    transport: 'ble',
    security: 1,
  }),
});
await WiFiProv.connect();
```

For Security 2, include the firmware's `username` and `pop`. Do not ship shared example credentials in production; provision a unique secret per device.

## SoftAP flow

```ts
await WiFiProv.createDevice({
  name: 'PROV_123',
  transport: 'softap',
  security: 1,
  pop: 'abcd1234',
  softApPassword: 'esp32-ap-password',
});

await WiFiProv.connect();
await WiFiProv.provision({ ssid: 'My Wi-Fi', password: 'secret-password' });
```

The phone temporarily joins the ESP32 access point. Android binds provisioning traffic to that network; iOS uses Hotspot Configuration. OS confirmation dialogs and brief loss of internet access are normal.

## Custom endpoint

The named endpoint must be created and registered by the firmware before provisioning starts.

```ts
const response = await WiFiProv.sendCustomData({
  endpoint: 'custom-data',
  data: JSON.stringify({ timezone: 'Asia/Shanghai' }),
  encoding: 'utf8',
});

console.log(response.utf8, response.base64);
```

`encoding` may be `utf8`, `hex`, or `base64`. The result always includes Base64 and includes `utf8` when the bytes are valid UTF-8.

## API

| Method | Purpose |
| --- | --- |
| `getCapabilities()` | Native/web transport feature flags |
| `checkPermissions()` / `requestPermissions()` | Bluetooth, location, and nearby-Wi-Fi permissions |
| `startBleScan()` / `stopBleScan()` | Control native BLE discovery |
| `getDiscoveredDevices()` | Snapshot of scan results |
| `selectDevice()` | Select a discovered BLE peripheral |
| `createDevice()` | Create BLE or SoftAP device directly by service name |
| `createDeviceFromQr()` | Parse a raw Espressif QR JSON payload |
| `connect()` / `disconnect()` | Open/close the secure provisioning session |
| `getDeviceInfo()` | Current device, transport, security, capabilities and version |
| `scanWifiNetworks()` | Ask the ESP32 to scan nearby APs |
| `provision()` | Send credentials, apply them, and check station status |
| `sendCustomData()` | Exchange bytes with a firmware custom endpoint |
| `resetWifi()` | Request firmware Wi-Fi credential reset |
| `clearState()` | Disconnect and discard native device/scan state |

Events: `bleScanResult`, `bleScanState`, `connectionState`, `provisioningProgress`, and `error`.

See [`src/definitions.ts`](src/definitions.ts) for complete TypeScript types.

## Run the example app

```bash
cd example-app
npm install
npm run cap:sync
npx cap open android
# or, on macOS:
npx cap open ios
```

The example supports BLE scan/selection, direct BLE and SoftAP setup, raw QR JSON, security settings, device information, ESP32 Wi-Fi scanning, credential provisioning, custom endpoints, reset, permission state, and live event logging.

An Arduino-ESP32 companion sketch is in `firmware/arduino/WiFiProvDemo/WiFiProvDemo.ino`. Its service name is `PROV_123`, PoP is `abcd1234`, and it uses BLE Security 1. Change those example credentials before real use. On a generic 4 MB ESP32, select **Huge APP (3 MB No OTA)** or another partition with at least 1.8 MB application space; the default 1.25 MB app partition is too small for the current BLE provisioning build.

## Firmware compatibility notes

- BLE service name, transport, security version, username, and PoP must match between app and firmware.
- Security 0 has no secure authentication and should be limited to controlled development scenarios.
- `scanWifiNetworks()` requires the firmware's Wi-Fi scan provisioning capability.
- `sendCustomData()` only works for endpoints registered by firmware.
- `resetWifi()` depends on the reset endpoint/capability exposed by the firmware SDK version.
- Provisioning is for 2.4 GHz Wi-Fi on ESP32 variants that do not support 5 GHz station networks.

Official references:

- [ESP-IDF network_provisioning component](https://components.espressif.com/components/espressif/network_provisioning)
- [ESP-IDF Provisioning API](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/provisioning/index.html)
- [Espressif Android SDK](https://github.com/espressif/esp-idf-provisioning-android)
- [Espressif iOS SDK](https://github.com/espressif/esp-idf-provisioning-ios)

## License

MIT
