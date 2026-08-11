import { WebPlugin } from '@capacitor/core';
import type { BleScanOptions, ConnectOptions, CustomDataOptions, CustomDataResult, DeviceOptions, PlatformCapabilities, ProvisioningDevice, ProvisioningPermissionStatus, ProvisionOptions, ProvisionResult, SelectDeviceOptions, WifiNetwork, WiFiProvPlugin } from './definitions';
export declare class WiFiProvWeb extends WebPlugin implements WiFiProvPlugin {
    getCapabilities(): Promise<PlatformCapabilities>;
    checkPermissions(): Promise<ProvisioningPermissionStatus>;
    requestPermissions(): Promise<ProvisioningPermissionStatus>;
    startBleScan(_options?: BleScanOptions): Promise<{
        started: boolean;
    }>;
    stopBleScan(): Promise<void>;
    getDiscoveredDevices(): Promise<{
        devices: [];
    }>;
    selectDevice(_options: SelectDeviceOptions): Promise<ProvisioningDevice>;
    createDevice(_options: DeviceOptions): Promise<ProvisioningDevice>;
    createDeviceFromQr(_options: {
        payload: string;
    }): Promise<ProvisioningDevice>;
    connect(_options?: ConnectOptions): Promise<ProvisioningDevice>;
    disconnect(): Promise<void>;
    getDeviceInfo(): Promise<ProvisioningDevice>;
    scanWifiNetworks(): Promise<{
        networks: WifiNetwork[];
    }>;
    provision(_options: ProvisionOptions): Promise<ProvisionResult>;
    sendCustomData(_options: CustomDataOptions): Promise<CustomDataResult>;
    resetWifi(): Promise<{
        success: boolean;
    }>;
    clearState(): Promise<void>;
}
