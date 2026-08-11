package com.wifiprov.capacitor;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.espressif.provisioning.DeviceConnectionEvent;
import com.espressif.provisioning.ESPConstants;
import com.espressif.provisioning.ESPDevice;
import com.espressif.provisioning.ESPProvisionManager;
import com.espressif.provisioning.WiFiAccessPoint;
import com.espressif.provisioning.listeners.BleScanListener;
import com.espressif.provisioning.listeners.ProvisionListener;
import com.espressif.provisioning.listeners.ResponseListener;
import com.espressif.provisioning.listeners.WiFiScanListener;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@CapacitorPlugin(
    name = "WiFiProv",
    permissions = {
        @Permission(
            strings = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT },
            alias = "bluetooth"
        ),
        @Permission(strings = { Manifest.permission.ACCESS_FINE_LOCATION }, alias = "location"),
        @Permission(strings = { Manifest.permission.NEARBY_WIFI_DEVICES }, alias = "nearbyWifi")
    }
)
public class WiFiProvPlugin extends Plugin {

    private static final String DEFAULT_PREFIX = "PROV_";
    private static final String DEFAULT_SEC2_USERNAME = "wifiprov";

    private ESPProvisionManager manager;
    private final Map<String, BleCandidate> discoveredDevices = new LinkedHashMap<>();

    private ESPDevice selectedDevice;
    private BleCandidate selectedCandidate;
    private String selectedId;
    private String selectedName;
    private String selectedTransport;
    private int selectedSecurity = 2;
    private String selectedPop = "";
    private String selectedUsername = "";
    private String selectedSoftApPassword = "";
    private boolean connected = false;
    private PluginCall pendingConnectCall;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback softApNetworkCallback;

    @Override
    public void load() {
        manager = ESPProvisionManager.getInstance(getContext().getApplicationContext());
        connectivityManager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    protected void handleOnDestroy() {
        cleanupSoftApBinding();
        if (selectedDevice != null) {
            selectedDevice.disconnectDevice();
        }
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        super.handleOnDestroy();
    }

    @PluginMethod
    public void getCapabilities(PluginCall call) {
        JSObject result = new JSObject();
        result.put("platform", "android");
        result.put("bleProvisioning", true);
        result.put("softApProvisioning", true);
        result.put("customEndpoints", true);
        result.put("qrPayloadImport", true);
        result.put("nativeQrScanner", false);
        JSArray notes = new JSArray();
        notes.put("Pass the raw QR JSON from any Capacitor barcode scanner to createDeviceFromQr().");
        notes.put("Android 12+ requires Nearby Devices permission for BLE scanning.");
        result.put("notes", notes);
        call.resolve(result);
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        call.resolve(buildPermissionStatus());
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        List<String> aliases = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            aliases.add("bluetooth");
        } else {
            aliases.add("location");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            aliases.add("nearbyWifi");
        }
        requestPermissionForAliases(aliases.toArray(new String[0]), call, "permissionsCallback");
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        call.resolve(buildPermissionStatus());
    }

    @PluginMethod
    public void startBleScan(PluginCall call) {
        if (!hasBleScanPermission()) {
            call.reject("Bluetooth scan permission is not granted. Call requestPermissions() first.");
            return;
        }

        String prefix = call.getString("prefix", DEFAULT_PREFIX);
        discoveredDevices.clear();
        emitScanState("started", null);

        try {
            manager.searchBleEspDevices(prefix, new BleScanListener() {
                @Override
                public void scanStartFailed() {
                    emitScanState("failed", "Bluetooth is disabled or scanning could not start.");
                }

                @Override
                public void onPeripheralFound(BluetoothDevice device, ScanResult scanResult) {
                    BleCandidate candidate = candidateFrom(device, scanResult);
                    if (candidate == null) {
                        return;
                    }
                    discoveredDevices.put(candidate.id, candidate);
                    notifyListeners("bleScanResult", candidate.toJSObject());
                }

                @Override
                public void scanCompleted() {
                    emitScanState("completed", null);
                }

                @Override
                public void onFailure(Exception error) {
                    emitError("startBleScan", error);
                    emitScanState("failed", message(error));
                }
            });
            JSObject result = new JSObject();
            result.put("started", true);
            call.resolve(result);
        } catch (Exception error) {
            emitError("startBleScan", error);
            call.reject(message(error));
        }
    }

    @PluginMethod
    public void stopBleScan(PluginCall call) {
        try {
            manager.stopBleScan();
            emitScanState("stopped", null);
            call.resolve();
        } catch (Exception error) {
            emitError("stopBleScan", error);
            call.reject(message(error));
        }
    }

    @PluginMethod
    public void getDiscoveredDevices(PluginCall call) {
        JSArray devices = new JSArray();
        for (BleCandidate candidate : discoveredDevices.values()) {
            devices.put(candidate.toJSObject());
        }
        JSObject result = new JSObject();
        result.put("devices", devices);
        call.resolve(result);
    }

    @PluginMethod
    public void selectDevice(PluginCall call) {
        String id = call.getString("id");
        if (id == null || !discoveredDevices.containsKey(id)) {
            call.reject("Unknown BLE device id. Scan again before selecting a device.");
            return;
        }
        BleCandidate candidate = discoveredDevices.get(id);
        int security = securityFrom(call.getInt("security"));
        selectCandidate(candidate, security, call.getString("pop", ""), call.getString("username", ""));
        call.resolve(buildDeviceInfo());
    }

    @PluginMethod
    public void createDevice(PluginCall call) {
        String name = call.getString("name");
        String transport = normalizeTransport(call.getString("transport"));
        if (name == null || name.trim().isEmpty() || transport == null) {
            call.reject("name and a valid transport ('ble' or 'softap') are required.");
            return;
        }
        createDeviceFromSpec(
            call,
            name,
            transport,
            securityFrom(call.getInt("security")),
            call.getString("pop", ""),
            call.getString("username", ""),
            call.getString("softApPassword", "")
        );
    }

    @PluginMethod
    public void createDeviceFromQr(PluginCall call) {
        String payload = call.getString("payload");
        if (payload == null || payload.trim().isEmpty()) {
            call.reject("payload is required.");
            return;
        }
        try {
            JSONObject qr = new JSONObject(payload);
            String version = qr.optString("ver", "");
            String name = qr.optString("name", "");
            String transport = normalizeTransport(qr.optString("transport", ""));
            String network = qr.optString("network", "wifi");
            if (!"v1".equalsIgnoreCase(version) || name.isEmpty() || transport == null) {
                call.reject("Invalid Espressif provisioning QR payload.");
                return;
            }
            if (!network.isEmpty() && !"wifi".equalsIgnoreCase(network)) {
                call.reject("This plugin currently supports Wi-Fi provisioning QR payloads only.");
                return;
            }
            createDeviceFromSpec(
                call,
                name,
                transport,
                securityFrom(qr.has("security") ? qr.optInt("security", 2) : 2),
                qr.optString("pop", ""),
                qr.optString("username", ""),
                qr.optString("password", "")
            );
        } catch (JSONException error) {
            emitError("createDeviceFromQr", error);
            call.reject("QR payload is not valid JSON.");
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (selectedDevice == null) {
            call.reject("No ESP device is selected.");
            return;
        }
        if (pendingConnectCall != null) {
            call.reject("A connection attempt is already in progress.");
            return;
        }

        selectedPop = call.getString("pop", selectedPop);
        selectedUsername = call.getString("username", selectedUsername);
        selectedSoftApPassword = call.getString("softApPassword", selectedSoftApPassword);
        selectedDevice.setProofOfPossession(selectedPop == null ? "" : selectedPop);
        selectedDevice.setUserName(selectedUsername == null ? "" : selectedUsername);
        pendingConnectCall = call;
        connected = false;
        emitConnectionState("connecting", null);

        try {
            if ("ble".equals(selectedTransport)) {
                if (!hasBleConnectPermission()) {
                    failPendingConnect("Bluetooth connect permission is not granted.");
                    return;
                }
                if (selectedCandidate == null || selectedCandidate.serviceUuid == null || selectedCandidate.serviceUuid.isEmpty()) {
                    failPendingConnect("The BLE provisioning service UUID was not found. Scan the device again.");
                    return;
                }
                selectedDevice.connectBLEDevice(selectedCandidate.device, selectedCandidate.serviceUuid);
            } else {
                connectSoftAp();
            }
        } catch (Exception error) {
            emitError("connect", error);
            failPendingConnect(message(error));
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        try {
            if (selectedDevice != null) {
                selectedDevice.disconnectDevice();
            }
            cleanupSoftApBinding();
            connected = false;
            emitConnectionState("disconnected", null);
            call.resolve();
        } catch (Exception error) {
            emitError("disconnect", error);
            call.reject(message(error));
        }
    }

    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        if (selectedDevice == null) {
            call.reject("No ESP device is selected.");
            return;
        }
        call.resolve(buildDeviceInfo());
    }

    @PluginMethod
    public void scanWifiNetworks(PluginCall call) {
        if (!requireConnected(call)) {
            return;
        }
        selectedDevice.scanNetworks(new WiFiScanListener() {
            @Override
            public void onWifiListReceived(ArrayList<WiFiAccessPoint> wifiList) {
                JSArray networks = new JSArray();
                for (WiFiAccessPoint accessPoint : wifiList) {
                    JSObject network = new JSObject();
                    network.put("ssid", accessPoint.getWifiName());
                    network.put("rssi", accessPoint.getRssi());
                    network.put("security", accessPoint.getSecurity());
                    networks.put(network);
                }
                JSObject result = new JSObject();
                result.put("networks", networks);
                call.resolve(result);
            }

            @Override
            public void onWiFiScanFailed(Exception error) {
                emitError("scanWifiNetworks", error);
                call.reject(message(error));
            }
        });
    }

    @PluginMethod
    public void provision(PluginCall call) {
        if (!requireConnected(call)) {
            return;
        }
        String ssid = call.getString("ssid");
        String password = call.getString("password", "");
        if (ssid == null || ssid.trim().isEmpty()) {
            call.reject("ssid is required.");
            return;
        }

        AtomicBoolean settled = new AtomicBoolean(false);
        emitProvisioningProgress("session", "Secure session is ready.");
        selectedDevice.provision(ssid, password, new ProvisionListener() {
            @Override
            public void createSessionFailed(Exception error) {
                rejectProvision(call, settled, "Failed to establish the secure session: " + message(error));
            }

            @Override
            public void wifiConfigSent() {
                emitProvisioningProgress("config-sent", "Wi-Fi credentials were sent to the device.");
            }

            @Override
            public void wifiConfigFailed(Exception error) {
                rejectProvision(call, settled, "Failed to send Wi-Fi credentials: " + message(error));
            }

            @Override
            public void wifiConfigApplied() {
                emitProvisioningProgress("config-applied", "The device accepted the Wi-Fi configuration.");
                emitProvisioningProgress("checking-connection", "Waiting for the device to connect to the access point.");
            }

            @Override
            public void wifiConfigApplyFailed(Exception error) {
                rejectProvision(call, settled, "Failed to apply Wi-Fi credentials: " + message(error));
            }

            @Override
            public void provisioningFailedFromDevice(ESPConstants.ProvisionFailureReason reason) {
                rejectProvision(call, settled, "Provisioning failed on the device: " + reason.name());
            }

            @Override
            public void deviceProvisioningSuccess() {
                if (settled.compareAndSet(false, true)) {
                    emitProvisioningProgress("success", "The device connected to Wi-Fi.");
                    JSObject result = new JSObject();
                    result.put("success", true);
                    call.resolve(result);
                }
            }

            @Override
            public void onProvisioningFailed(Exception error) {
                rejectProvision(call, settled, "Provisioning failed: " + message(error));
            }
        });
    }

    @PluginMethod
    public void sendCustomData(PluginCall call) {
        if (!requireConnected(call)) {
            return;
        }
        String endpoint = call.getString("endpoint");
        String data = call.getString("data", "");
        String encoding = normalizeEncoding(call.getString("encoding", "utf8"));
        if (endpoint == null || endpoint.trim().isEmpty()) {
            call.reject("endpoint is required.");
            return;
        }
        try {
            byte[] request = decode(data, encoding);
            selectedDevice.sendDataToCustomEndPoint(endpoint, request, new ResponseListener() {
                @Override
                public void onSuccess(byte[] response) {
                    call.resolve(binaryResult(response, encoding));
                }

                @Override
                public void onFailure(Exception error) {
                    emitError("sendCustomData", error);
                    call.reject(message(error));
                }
            });
        } catch (Exception error) {
            emitError("sendCustomData", error);
            call.reject(message(error));
        }
    }

    @PluginMethod
    public void resetWifi(PluginCall call) {
        if (!requireConnected(call)) {
            return;
        }
        selectedDevice.resetWifiStatus(new ResponseListener() {
            @Override
            public void onSuccess(byte[] data) {
                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            }

            @Override
            public void onFailure(Exception error) {
                emitError("resetWifi", error);
                call.reject(message(error));
            }
        });
    }

    @PluginMethod
    public void clearState(PluginCall call) {
        try {
            manager.stopBleScan();
        } catch (Exception ignored) {
            // A scan may not be active.
        }
        if (selectedDevice != null) {
            selectedDevice.disconnectDevice();
        }
        cleanupSoftApBinding();
        discoveredDevices.clear();
        selectedDevice = null;
        selectedCandidate = null;
        selectedId = null;
        selectedName = null;
        selectedTransport = null;
        selectedPop = "";
        selectedUsername = "";
        selectedSoftApPassword = "";
        connected = false;
        pendingConnectCall = null;
        call.resolve();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDeviceConnectionEvent(DeviceConnectionEvent event) {
        switch (event.getEventType()) {
            case ESPConstants.EVENT_DEVICE_CONNECTED:
                connected = true;
                JSObject info = buildDeviceInfo();
                emitConnectionState("connected", null);
                if (pendingConnectCall != null) {
                    PluginCall call = pendingConnectCall;
                    pendingConnectCall = null;
                    call.resolve(info);
                }
                break;
            case ESPConstants.EVENT_DEVICE_CONNECTION_FAILED:
                connected = false;
                failPendingConnect("Failed to connect to the ESP provisioning service.");
                break;
            case ESPConstants.EVENT_DEVICE_DISCONNECTED:
                connected = false;
                if (pendingConnectCall != null) {
                    failPendingConnect("The ESP device disconnected while connecting.");
                } else {
                    emitConnectionState("disconnected", null);
                }
                break;
            default:
                break;
        }
    }

    private void createDeviceFromSpec(
        PluginCall call,
        String name,
        String transport,
        int security,
        String pop,
        String username,
        String softApPassword
    ) {
        if ("softap".equals(transport)) {
            selectedDevice = manager.createESPDevice(ESPConstants.TransportType.TRANSPORT_SOFTAP, securityType(security));
            selectedDevice.setDeviceName(name);
            selectedDevice.setProofOfPossession(pop == null ? "" : pop);
            selectedDevice.setUserName(username == null ? "" : username);
            selectedCandidate = null;
            selectedId = "softap:" + name;
            selectedName = name;
            selectedTransport = "softap";
            selectedSecurity = security;
            selectedPop = pop == null ? "" : pop;
            selectedUsername = username == null ? "" : username;
            selectedSoftApPassword = softApPassword == null ? "" : softApPassword;
            connected = false;
            call.resolve(buildDeviceInfo());
            return;
        }

        for (BleCandidate candidate : discoveredDevices.values()) {
            if (name.equalsIgnoreCase(candidate.name)) {
                selectCandidate(candidate, security, pop, username);
                call.resolve(buildDeviceInfo());
                return;
            }
        }

        if (!hasBleScanPermission()) {
            call.reject("Bluetooth scan permission is not granted. Call requestPermissions() first.");
            return;
        }

        AtomicReference<BleCandidate> match = new AtomicReference<>();
        AtomicBoolean settled = new AtomicBoolean(false);
        emitScanState("started", null);
        manager.searchBleEspDevices(name, new BleScanListener() {
            @Override
            public void scanStartFailed() {
                if (settled.compareAndSet(false, true)) {
                    emitScanState("failed", "Bluetooth scanning could not start.");
                    call.reject("Bluetooth scanning could not start.");
                }
            }

            @Override
            public void onPeripheralFound(BluetoothDevice device, ScanResult scanResult) {
                BleCandidate candidate = candidateFrom(device, scanResult);
                if (candidate == null) {
                    return;
                }
                discoveredDevices.put(candidate.id, candidate);
                notifyListeners("bleScanResult", candidate.toJSObject());
                if (name.equalsIgnoreCase(candidate.name)) {
                    match.compareAndSet(null, candidate);
                }
            }

            @Override
            public void scanCompleted() {
                emitScanState("completed", null);
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                BleCandidate candidate = match.get();
                if (candidate == null) {
                    call.reject("No BLE provisioning device named " + name + " was found.");
                } else {
                    selectCandidate(candidate, security, pop, username);
                    call.resolve(buildDeviceInfo());
                }
            }

            @Override
            public void onFailure(Exception error) {
                if (settled.compareAndSet(false, true)) {
                    emitError("createDevice", error);
                    emitScanState("failed", message(error));
                    call.reject(message(error));
                }
            }
        });
    }

    private void selectCandidate(BleCandidate candidate, int security, String pop, String username) {
        selectedDevice = manager.createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, securityType(security));
        selectedDevice.setDeviceName(candidate.name);
        selectedDevice.setBluetoothDevice(candidate.device);
        selectedDevice.setPrimaryServiceUuid(candidate.serviceUuid);
        selectedDevice.setProofOfPossession(pop == null ? "" : pop);
        selectedDevice.setUserName(username == null ? "" : username);
        selectedCandidate = candidate;
        selectedId = candidate.id;
        selectedName = candidate.name;
        selectedTransport = "ble";
        selectedSecurity = security;
        selectedPop = pop == null ? "" : pop;
        selectedUsername = username == null ? "" : username;
        selectedSoftApPassword = "";
        connected = false;
    }

    private void connectSoftAp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            selectedDevice.connectWiFiDevice(selectedName, selectedSoftApPassword == null ? "" : selectedSoftApPassword);
            return;
        }

        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder().setSsid(selectedName);
        if (selectedSoftApPassword != null && !selectedSoftApPassword.isEmpty()) {
            specifier.setWpa2Passphrase(selectedSoftApPassword);
        }

        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier.build())
            .build();

        softApNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                connectivityManager.bindProcessToNetwork(network);
                getActivity().runOnUiThread(() -> selectedDevice.connectWiFiDevice());
            }

            @Override
            public void onUnavailable() {
                failPendingConnect("The phone could not join the ESP SoftAP network.");
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (connected) {
                    connected = false;
                    emitConnectionState("disconnected", "The ESP SoftAP network was lost.");
                }
            }
        };
        connectivityManager.requestNetwork(request, softApNetworkCallback);
    }

    private void cleanupSoftApBinding() {
        if (connectivityManager == null) {
            return;
        }
        connectivityManager.bindProcessToNetwork(null);
        if (softApNetworkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(softApNetworkCallback);
            } catch (Exception ignored) {
                // Callback may already be unregistered by Android.
            }
            softApNetworkCallback = null;
        }
    }

    private BleCandidate candidateFrom(BluetoothDevice device, ScanResult result) {
        if (result == null || result.getScanRecord() == null) {
            return null;
        }
        String name = result.getScanRecord().getDeviceName();
        if ((name == null || name.isEmpty()) && hasBleConnectPermission()) {
            name = device.getName();
        }
        if (name == null || name.isEmpty()) {
            name = "Unknown ESP device";
        }
        String serviceUuid = null;
        List<ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
        if (serviceUuids != null && !serviceUuids.isEmpty()) {
            serviceUuid = serviceUuids.get(0).toString();
        }
        String address = device.getAddress();
        return new BleCandidate("android:" + address, name, result.getRssi(), serviceUuid, device);
    }

    private JSObject buildDeviceInfo() {
        JSObject result = new JSObject();
        result.put("id", selectedId);
        result.put("name", selectedName == null ? "" : selectedName);
        result.put("transport", selectedTransport == null ? "ble" : selectedTransport);
        result.put("security", selectedSecurity);
        result.put("connected", connected);

        JSArray capabilities = new JSArray();
        if (selectedDevice != null && selectedDevice.getDeviceCapabilities() != null) {
            for (String capability : selectedDevice.getDeviceCapabilities()) {
                capabilities.put(capability);
            }
        }
        result.put("capabilities", capabilities);

        if (selectedDevice != null && selectedDevice.getVersionInfo() != null) {
            String versionInfo = selectedDevice.getVersionInfo();
            try {
                result.put("versionInfo", new JSONObject(versionInfo));
            } catch (JSONException ignored) {
                result.put("versionInfo", versionInfo);
            }
        }
        return result;
    }

    private JSObject buildPermissionStatus() {
        JSObject result = new JSObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.put("bluetooth", permissionName(getPermissionState("bluetooth")));
            result.put("location", "granted");
        } else {
            result.put("bluetooth", "granted");
            result.put("location", permissionName(getPermissionState("location")));
        }
        result.put(
            "nearbyWifi",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? permissionName(getPermissionState("nearbyWifi"))
                : "granted"
        );
        return result;
    }

    private String permissionName(PermissionState state) {
        if (state == null) {
            return "prompt";
        }
        switch (state) {
            case GRANTED:
                return "granted";
            case DENIED:
                return "denied";
            case PROMPT_WITH_RATIONALE:
                return "prompt-with-rationale";
            case PROMPT:
            default:
                return "prompt";
        }
    }

    private boolean hasBleScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBleConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean requireConnected(PluginCall call) {
        if (selectedDevice == null || !connected) {
            call.reject("No connected ESP provisioning device.");
            return false;
        }
        return true;
    }

    private int securityFrom(Integer security) {
        return securityFrom(security == null ? 2 : security.intValue());
    }

    private int securityFrom(int security) {
        return security >= 0 && security <= 2 ? security : 2;
    }

    private ESPConstants.SecurityType securityType(int security) {
        switch (security) {
            case 0:
                return ESPConstants.SecurityType.SECURITY_0;
            case 1:
                return ESPConstants.SecurityType.SECURITY_1;
            case 2:
            default:
                return ESPConstants.SecurityType.SECURITY_2;
        }
    }

    private String normalizeTransport(String transport) {
        if (transport == null) {
            return null;
        }
        String normalized = transport.toLowerCase(Locale.ROOT).trim();
        return "ble".equals(normalized) || "softap".equals(normalized) ? normalized : null;
    }

    private String normalizeEncoding(String encoding) {
        if (encoding == null) {
            return "utf8";
        }
        String normalized = encoding.toLowerCase(Locale.ROOT);
        return "base64".equals(normalized) || "hex".equals(normalized) ? normalized : "utf8";
    }

    private byte[] decode(String data, String encoding) {
        switch (encoding) {
            case "base64":
                return Base64.decode(data, Base64.DEFAULT);
            case "hex":
                return decodeHex(data);
            case "utf8":
            default:
                return data.getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] decodeHex(String value) {
        String normalized = value.replaceAll("\\s", "");
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex data must contain an even number of characters.");
        }
        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            int high = Character.digit(normalized.charAt(i), 16);
            int low = Character.digit(normalized.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Hex data contains an invalid character.");
            }
            result[i / 2] = (byte) ((high << 4) + low);
        }
        return result;
    }

    private String encodeHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private JSObject binaryResult(byte[] response, String encoding) {
        JSObject result = new JSObject();
        String base64 = Base64.encodeToString(response, Base64.NO_WRAP);
        String utf8 = new String(response, StandardCharsets.UTF_8);
        result.put("encoding", encoding);
        result.put("base64", base64);
        result.put("utf8", utf8);
        switch (encoding) {
            case "base64":
                result.put("data", base64);
                break;
            case "hex":
                result.put("data", encodeHex(response));
                break;
            default:
                result.put("data", utf8);
                break;
        }
        return result;
    }

    private void rejectProvision(PluginCall call, AtomicBoolean settled, String reason) {
        if (settled.compareAndSet(false, true)) {
            emitProvisioningProgress("failed", reason);
            call.reject(reason);
        }
    }

    private void failPendingConnect(String reason) {
        connected = false;
        emitConnectionState("failed", reason);
        if (pendingConnectCall != null) {
            PluginCall call = pendingConnectCall;
            pendingConnectCall = null;
            call.reject(reason);
        }
    }

    private void emitScanState(String state, String detail) {
        JSObject event = new JSObject();
        event.put("state", state);
        if (detail != null) {
            event.put("message", detail);
        }
        notifyListeners("bleScanState", event);
    }

    private void emitConnectionState(String state, String detail) {
        JSObject event = new JSObject();
        event.put("state", state);
        if (selectedDevice != null) {
            event.put("device", buildDeviceInfo());
        }
        if (detail != null) {
            event.put("message", detail);
        }
        notifyListeners("connectionState", event);
    }

    private void emitProvisioningProgress(String step, String detail) {
        JSObject event = new JSObject();
        event.put("step", step);
        if (detail != null) {
            event.put("message", detail);
        }
        notifyListeners("provisioningProgress", event);
    }

    private void emitError(String operation, Exception error) {
        JSObject event = new JSObject();
        event.put("operation", operation);
        event.put("message", message(error));
        notifyListeners("error", event);
    }

    private String message(Exception error) {
        if (error == null) {
            return "Unknown error";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class BleCandidate {
        final String id;
        final String name;
        final int rssi;
        final String serviceUuid;
        final BluetoothDevice device;

        BleCandidate(String id, String name, int rssi, String serviceUuid, BluetoothDevice device) {
            this.id = id;
            this.name = name;
            this.rssi = rssi;
            this.serviceUuid = serviceUuid;
            this.device = device;
        }

        JSObject toJSObject() {
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("name", name);
            result.put("rssi", rssi);
            result.put("serviceUuid", serviceUuid);
            result.put("transport", "ble");
            return result;
        }
    }
}
