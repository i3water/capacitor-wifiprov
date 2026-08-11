import { WebPlugin } from '@capacitor/core';

import type {
  BleScanOptions,
  ConnectOptions,
  CustomDataOptions,
  CustomDataResult,
  DeviceOptions,
  PlatformCapabilities,
  ProvisioningDevice,
  ProvisioningPermissionStatus,
  ProvisionOptions,
  ProvisionResult,
  SelectDeviceOptions,
  WifiNetwork,
  WiFiProvPlugin,
} from './definitions';

export class WiFiProvWeb extends WebPlugin implements WiFiProvPlugin {
  async getCapabilities(): Promise<PlatformCapabilities> {
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

  async checkPermissions(): Promise<ProvisioningPermissionStatus> {
    return { bluetooth: 'prompt', location: 'prompt', nearbyWifi: 'prompt' };
  }

  async requestPermissions(): Promise<ProvisioningPermissionStatus> {
    throw this.unavailable('Permissions are managed by the native Android and iOS implementations.');
  }

  async startBleScan(_options?: BleScanOptions): Promise<{ started: boolean }> {
    throw this.unavailable('BLE provisioning is unavailable on the web.');
  }

  async stopBleScan(): Promise<void> {
    throw this.unavailable('BLE provisioning is unavailable on the web.');
  }

  async getDiscoveredDevices(): Promise<{ devices: [] }> {
    return { devices: [] };
  }

  async selectDevice(_options: SelectDeviceOptions): Promise<ProvisioningDevice> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async createDevice(_options: DeviceOptions): Promise<ProvisioningDevice> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async createDeviceFromQr(_options: { payload: string }): Promise<ProvisioningDevice> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async connect(_options?: ConnectOptions): Promise<ProvisioningDevice> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async disconnect(): Promise<void> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async getDeviceInfo(): Promise<ProvisioningDevice> {
    throw this.unavailable('No native ESP device is selected.');
  }

  async scanWifiNetworks(): Promise<{ networks: WifiNetwork[] }> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async provision(_options: ProvisionOptions): Promise<ProvisionResult> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async sendCustomData(_options: CustomDataOptions): Promise<CustomDataResult> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async resetWifi(): Promise<{ success: boolean }> {
    throw this.unavailable('ESP provisioning is unavailable on the web.');
  }

  async clearState(): Promise<void> {
    return;
  }
}
