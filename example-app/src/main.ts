import { WiFiProv } from 'capacitor-wifiprov';
import type { BleDevice, WifiNetwork, WiFiProvSecurity, WiFiProvTransport } from 'capacitor-wifiprov';

import './style.css';

const element = <T extends HTMLElement>(id: string): T => {
  const value = document.getElementById(id);
  if (!value) throw new Error(`Missing element #${id}`);
  return value as T;
};

const input = (id: string): string => element<HTMLInputElement>(id).value;
const security = (): WiFiProvSecurity => Number(input('security')) as WiFiProvSecurity;
const pretty = (value: unknown): string => JSON.stringify(value, null, 2);
const logElement = element<HTMLPreElement>('log');

const log = (label: string, value?: unknown): void => {
  const time = new Date().toLocaleTimeString();
  const detail = value === undefined ? '' : `\n${typeof value === 'string' ? value : pretty(value)}`;
  logElement.textContent = `[${time}] ${label}${detail}\n\n${logElement.textContent}`;
};

const run = async (label: string, task: () => Promise<unknown>): Promise<void> => {
  try {
    const result = await task();
    log(`${label} ✓`, result);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    log(`${label} ✗`, message);
  }
};

const auth = () => ({
  security: security(),
  pop: input('pop'),
  username: input('username'),
});

const renderDevice = (device: unknown): void => {
  element<HTMLPreElement>('selected-device').textContent = pretty(device);
};

const renderNetworks = (networks: WifiNetwork[]): void => {
  const container = element<HTMLDivElement>('networks');
  container.replaceChildren();
  if (networks.length === 0) {
    container.innerHTML = '<p class="muted">ESP32 未返回 Wi‑Fi 网络</p>';
    return;
  }
  for (const network of networks.sort((a, b) => b.rssi - a.rssi)) {
    const button = document.createElement('button');
    button.className = 'network';
    button.innerHTML = `<span><strong>${network.ssid || '(隐藏 SSID)'}</strong><small>${String(network.security)}</small></span><b>${network.rssi} dBm</b>`;
    button.addEventListener('click', () => {
      element<HTMLInputElement>('ssid').value = network.ssid;
      log('已选择 Wi‑Fi', network.ssid);
    });
    container.append(button);
  }
};

const upsertBleDevice = (device: BleDevice): void => {
  const container = element<HTMLDivElement>('devices');
  const existing = container.querySelector<HTMLElement>(`[data-device-id="${CSS.escape(device.id)}"]`);
  if (existing) return;
  container.querySelector('.muted')?.remove();
  const row = document.createElement('button');
  row.className = 'device';
  row.dataset.deviceId = device.id;
  row.innerHTML = `<span><strong>${device.name}</strong><small>${device.serviceUuid ?? device.id}</small></span><b>${device.rssi ?? '—'} dBm</b>`;
  row.addEventListener('click', () => {
    void run('选择 BLE 设备', async () => {
      const selected = await WiFiProv.selectDevice({ id: device.id, ...auth() });
      renderDevice(selected);
      return selected;
    });
  });
  container.append(row);
};

const registerEvents = async (): Promise<void> => {
  await WiFiProv.addListener('bleScanResult', (event) => {
    upsertBleDevice(event);
    log('BLE 设备', event);
  });
  await WiFiProv.addListener('bleScanState', (event) => log(`BLE 扫描：${event.state}`, event.message));
  await WiFiProv.addListener('connectionState', (event) => {
    element('status-pill').textContent = event.state;
    element('status-pill').className = `pill ${event.state}`;
    if (event.device) renderDevice(event.device);
    log(`连接状态：${event.state}`, event);
  });
  await WiFiProv.addListener('provisioningProgress', (event) => log(`配网：${event.step}`, event.message));
  await WiFiProv.addListener('error', (event) => log(`原生错误：${event.operation}`, event));
};

element('capabilities').addEventListener('click', () => {
  void run('读取平台能力', async () => {
    const result = await WiFiProv.getCapabilities();
    element<HTMLPreElement>('environment').textContent = pretty(result);
    return result;
  });
});

element('check-permissions').addEventListener('click', () => {
  void run('检查权限', async () => {
    const result = await WiFiProv.checkPermissions();
    element<HTMLPreElement>('environment').textContent = pretty(result);
    return result;
  });
});

element('request-permissions').addEventListener('click', () => {
  void run('申请权限', async () => {
    const result = await WiFiProv.requestPermissions();
    element<HTMLPreElement>('environment').textContent = pretty(result);
    return result;
  });
});

element('scan-ble').addEventListener('click', () => {
  element('devices').innerHTML = '<p class="muted">扫描中…</p>';
  void run('启动 BLE 扫描', () => WiFiProv.startBleScan({ prefix: input('prefix'), security: security() }));
});

element('stop-scan').addEventListener('click', () => void run('停止 BLE 扫描', () => WiFiProv.stopBleScan()));

element('scan-snapshot').addEventListener('click', () => {
  void run('读取 BLE 扫描快照', async () => {
    const result = await WiFiProv.getDiscoveredDevices();
    result.devices.forEach(upsertBleDevice);
    return result;
  });
});

element('create-device').addEventListener('click', () => {
  void run('创建设备引用', async () => {
    const selected = await WiFiProv.createDevice({
      name: input('device-name'),
      transport: input('transport') as WiFiProvTransport,
      ...auth(),
      softApPassword: input('softap-password'),
    });
    renderDevice(selected);
    return selected;
  });
});

element('import-qr').addEventListener('click', () => {
  void run('导入 QR JSON', async () => {
    const selected = await WiFiProv.createDeviceFromQr({ payload: input('qr-payload') });
    renderDevice(selected);
    return selected;
  });
});

element('connect').addEventListener('click', () => {
  void run('建立安全会话', async () => {
    const selected = await WiFiProv.connect({
      pop: input('pop'),
      username: input('username'),
      softApPassword: input('softap-password'),
    });
    renderDevice(selected);
    return selected;
  });
});

element('device-info').addEventListener('click', () => {
  void run('读取设备信息', async () => {
    const selected = await WiFiProv.getDeviceInfo();
    renderDevice(selected);
    return selected;
  });
});

element('disconnect').addEventListener('click', () => void run('断开设备', () => WiFiProv.disconnect()));

element('clear-state').addEventListener('click', () => {
  void run('清除插件状态', async () => {
    await WiFiProv.clearState();
    renderDevice({ state: 'cleared' });
  });
});

element('scan-wifi').addEventListener('click', () => {
  void run('扫描 Wi‑Fi', async () => {
    const result = await WiFiProv.scanWifiNetworks();
    renderNetworks(result.networks);
    return result;
  });
});

element('provision').addEventListener('click', () => {
  void run('开始配网', () => WiFiProv.provision({ ssid: input('ssid'), password: input('wifi-password') }));
});

element('send-custom').addEventListener('click', () => {
  void run('发送自定义数据', async () => {
    const result = await WiFiProv.sendCustomData({
      endpoint: input('endpoint'),
      data: input('custom-data'),
      encoding: input('encoding') as 'utf8' | 'hex' | 'base64',
    });
    element<HTMLPreElement>('custom-result').textContent = pretty(result);
    return result;
  });
});

element('reset-wifi').addEventListener('click', () => {
  if (window.confirm('确定向设备发送 Wi‑Fi reset 命令吗？')) {
    void run('重置设备 Wi‑Fi', () => WiFiProv.resetWifi());
  }
});

element('clear-log').addEventListener('click', () => {
  logElement.textContent = '';
});

void registerEvents().then(() => run('插件初始化', () => WiFiProv.getCapabilities()));
