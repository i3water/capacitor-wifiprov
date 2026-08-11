import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export type WiFiProvTransport = 'ble' | 'softap';
export type WiFiProvSecurity = 0 | 1 | 2;
export type BinaryEncoding = 'utf8' | 'base64' | 'hex';

export interface PlatformCapabilities {
  platform: 'android' | 'ios' | 'web';
  bleProvisioning: boolean;
  softApProvisioning: boolean;
  customEndpoints: boolean;
  qrPayloadImport: boolean;
  nativeQrScanner: boolean;
  notes: string[];
}

export interface ProvisioningPermissionStatus {
  bluetooth: PermissionState;
  location: PermissionState;
  nearbyWifi: PermissionState;
}

export interface BleScanOptions {
  /** BLE name prefix. Espressif examples normally use PROV_. */
  prefix?: string;
  security?: WiFiProvSecurity;
}

export interface BleDevice {
  id: string;
  name: string;
  rssi?: number;
  serviceUuid?: string;
  transport: 'ble';
}

export interface DeviceOptions {
  name: string;
  transport: WiFiProvTransport;
  security?: WiFiProvSecurity;
  pop?: string;
  username?: string;
  softApPassword?: string;
}

export interface SelectDeviceOptions {
  id: string;
  security?: WiFiProvSecurity;
  pop?: string;
  username?: string;
}

export interface ConnectOptions {
  pop?: string;
  username?: string;
  /** Used only when the selected device uses SoftAP transport. */
  softApPassword?: string;
}

export interface ProvisioningDevice {
  id?: string;
  name: string;
  transport: WiFiProvTransport;
  security: WiFiProvSecurity;
  connected: boolean;
  capabilities: string[];
  versionInfo?: unknown;
}

export interface WifiNetwork {
  ssid: string;
  rssi: number;
  security: number | string;
  channel?: number;
  bssid?: string;
}

export interface ProvisionOptions {
  ssid: string;
  password?: string;
}

export interface ProvisionResult {
  success: boolean;
  ipAddress?: string;
}

export interface CustomDataOptions {
  endpoint: string;
  data: string;
  encoding?: BinaryEncoding;
}

export interface CustomDataResult {
  data: string;
  encoding: BinaryEncoding;
  base64: string;
  utf8?: string;
}

export interface BleScanStateEvent {
  state: 'started' | 'stopped' | 'completed' | 'failed';
  message?: string;
}

export interface ConnectionStateEvent {
  state: 'connecting' | 'connected' | 'disconnected' | 'failed';
  device?: ProvisioningDevice;
  message?: string;
}

export interface ProvisioningProgressEvent {
  step:
    | 'session'
    | 'config-sent'
    | 'config-applied'
    | 'checking-connection'
    | 'success'
    | 'failed';
  message?: string;
}

export interface WiFiProvErrorEvent {
  operation: string;
  message: string;
  code?: string;
}

export interface WiFiProvPlugin {
  getCapabilities(): Promise<PlatformCapabilities>;
  checkPermissions(): Promise<ProvisioningPermissionStatus>;
  requestPermissions(): Promise<ProvisioningPermissionStatus>;

  startBleScan(options?: BleScanOptions): Promise<{ started: boolean }>;
  stopBleScan(): Promise<void>;
  getDiscoveredDevices(): Promise<{ devices: BleDevice[] }>;

  selectDevice(options: SelectDeviceOptions): Promise<ProvisioningDevice>;
  createDevice(options: DeviceOptions): Promise<ProvisioningDevice>;
  /** Import the raw JSON string encoded in an Espressif provisioning QR code. */
  createDeviceFromQr(options: { payload: string }): Promise<ProvisioningDevice>;

  connect(options?: ConnectOptions): Promise<ProvisioningDevice>;
  disconnect(): Promise<void>;
  getDeviceInfo(): Promise<ProvisioningDevice>;
  scanWifiNetworks(): Promise<{ networks: WifiNetwork[] }>;
  provision(options: ProvisionOptions): Promise<ProvisionResult>;
  sendCustomData(options: CustomDataOptions): Promise<CustomDataResult>;
  resetWifi(): Promise<{ success: boolean }>;
  clearState(): Promise<void>;

  addListener(eventName: 'bleScanResult', listenerFunc: (event: BleDevice) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'bleScanState', listenerFunc: (event: BleScanStateEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'connectionState',
    listenerFunc: (event: ConnectionStateEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'provisioningProgress',
    listenerFunc: (event: ProvisioningProgressEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'error', listenerFunc: (event: WiFiProvErrorEvent) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
