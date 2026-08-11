var capacitorWiFiProv = (function (exports, core) {
    'use strict';

    const WiFiProv = core.registerPlugin('WiFiProv', {
        web: () => Promise.resolve().then(function () { return web; }).then((module) => new module.WiFiProvWeb()),
    });

    class WiFiProvWeb extends core.WebPlugin {
        async getCapabilities() {
            return {
                platform: 'web',
                bleProvisioning: false,
                softApProvisioning: false,
                customEndpoints: false,
                qrPayloadImport: false,
                nativeQrScanner: false,
                notes: ['ESP provisioning requires the Android or iOS native implementation.'],
            };
        }
        async checkPermissions() {
            return { bluetooth: 'prompt', location: 'prompt', nearbyWifi: 'prompt' };
        }
        async requestPermissions() {
            throw this.unavailable('Permissions are managed by the native Android and iOS implementations.');
        }
        async startBleScan(_options) {
            throw this.unavailable('BLE provisioning is unavailable on the web.');
        }
        async stopBleScan() {
            throw this.unavailable('BLE provisioning is unavailable on the web.');
        }
        async getDiscoveredDevices() {
            return { devices: [] };
        }
        async selectDevice(_options) {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async createDevice(_options) {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async createDeviceFromQr(_options) {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async connect(_options) {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async disconnect() {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async getDeviceInfo() {
            throw this.unavailable('No native ESP device is selected.');
        }
        async scanWifiNetworks() {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async provision(_options) {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async sendCustomData(_options) {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async resetWifi() {
            throw this.unavailable('ESP provisioning is unavailable on the web.');
        }
        async clearState() {
            return;
        }
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        WiFiProvWeb: WiFiProvWeb
    });

    exports.WiFiProv = WiFiProv;

    return exports;

})({}, capacitorExports);
//# sourceMappingURL=plugin.js.map
