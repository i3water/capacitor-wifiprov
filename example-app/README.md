# WiFiProv demo

Minimal Capacitor 8 + Vite app exercising every public `capacitor-wifiprov` API. The plugin is linked from the parent folder through `file:..`.

```bash
npm install
npm run cap:sync
npx cap open android
# macOS only:
npx cap open ios
```

For Android, keep the JitPack repository in `android/build.gradle`. For iOS SoftAP, select a Development Team and retain the Hotspot Configuration and Access WiFi Information capabilities already configured in the example project.

BLE and Wi-Fi provisioning must be tested on a physical device. Browser preview is a UI-only fallback and reports that native provisioning is unavailable.
