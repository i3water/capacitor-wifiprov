import AVFoundation
import Capacitor
import CoreBluetooth
import CoreLocation
import ESPProvision
import Foundation

@objc(WiFiProvPlugin)
public class WiFiProvPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "WiFiProvPlugin"
    public let jsName = "WiFiProv"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getCapabilities", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startBleScan", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopBleScan", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDiscoveredDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "selectDevice", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createDevice", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createDeviceFromQr", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDeviceInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanWifiNetworks", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "provision", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendCustomData", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resetWifi", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearState", returnType: CAPPluginReturnPromise)
    ]

    private let manager = ESPProvisionManager.shared
    private var discoveredDevices: [String: ESPDevice] = [:]
    private var discoveryOrder: [String] = []
    private var selectedDevice: ESPDevice?
    private var selectedID: String?
    private var selectedName = ""
    private var selectedTransport: ESPTransport = .ble
    private var selectedSecurity: ESPSecurity = .secure2
    private var selectedPop = ""
    private var selectedUsername = ""
    private var selectedSoftAPPassword = ""
    private var connected = false

    private var permissionBluetoothManager: CBCentralManager?
    private var permissionLocationManager: CLLocationManager?

    public override func load() {
        manager.enableLogs(false)
    }

    @objc func getCapabilities(_ call: CAPPluginCall) {
        call.resolve([
            "platform": "ios",
            "bleProvisioning": true,
            "softApProvisioning": true,
            "customEndpoints": true,
            "qrPayloadImport": true,
            "nativeQrScanner": false,
            "notes": [
                "Pass the raw QR JSON from a Capacitor barcode scanner to createDeviceFromQr().",
                "SoftAP requires the Hotspot Configuration capability in the host app.",
                "Local network and Bluetooth prompts are shown by iOS when the feature is first used."
            ]
        ])
    }

    @objc func checkPermissions(_ call: CAPPluginCall) {
        call.resolve(permissionStatus())
    }

    @objc func requestPermissions(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.permissionBluetoothManager = CBCentralManager(delegate: nil, queue: .main)
            self.permissionLocationManager = CLLocationManager()
            self.permissionLocationManager?.requestWhenInUseAuthorization()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                call.resolve(self.permissionStatus())
            }
        }
    }

    @objc func startBleScan(_ call: CAPPluginCall) {
        let prefix = call.getString("prefix") ?? "PROV_"
        let security = securityValue(call.getInt("security"))
        discoveredDevices.removeAll()
        discoveryOrder.removeAll()
        emitScanState("started")
        call.resolve(["started": true])

        manager.searchESPDevices(devicePrefix: prefix, transport: .ble, security: security) { devices, error in
            DispatchQueue.main.async {
                if let error = error {
                    self.emitError(operation: "startBleScan", message: error.description)
                    self.emitScanState("failed", message: error.description)
                    return
                }
                for device in devices ?? [] {
                    let id = "ios:\(UUID().uuidString)"
                    self.discoveredDevices[id] = device
                    self.discoveryOrder.append(id)
                    self.notifyListeners("bleScanResult", data: self.bleDeviceInfo(id: id, device: device))
                }
                self.emitScanState("completed")
            }
        }
    }

    @objc func stopBleScan(_ call: CAPPluginCall) {
        manager.stopESPDevicesSearch()
        emitScanState("stopped")
        call.resolve()
    }

    @objc func getDiscoveredDevices(_ call: CAPPluginCall) {
        let devices = discoveryOrder.compactMap { id -> [String: Any]? in
            guard let device = discoveredDevices[id] else { return nil }
            return bleDeviceInfo(id: id, device: device)
        }
        call.resolve(["devices": devices])
    }

    @objc func selectDevice(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let device = discoveredDevices[id] else {
            call.reject("Unknown BLE device id. Scan again before selecting a device.")
            return
        }
        let security = securityValue(call.getInt("security"))
        select(device: device,
               id: id,
               transport: .ble,
               security: security,
               pop: call.getString("pop") ?? "",
               username: call.getString("username") ?? "",
               softAPPassword: "")
        call.resolve(deviceInfo())
    }

    @objc func createDevice(_ call: CAPPluginCall) {
        guard let name = call.getString("name"), !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let transport = transportValue(call.getString("transport")) else {
            call.reject("name and a valid transport ('ble' or 'softap') are required.")
            return
        }
        createDevice(
            call: call,
            name: name,
            transport: transport,
            security: securityValue(call.getInt("security")),
            pop: call.getString("pop") ?? "",
            username: call.getString("username") ?? "",
            softAPPassword: call.getString("softApPassword") ?? ""
        )
    }

    @objc func createDeviceFromQr(_ call: CAPPluginCall) {
        guard let payload = call.getString("payload"), let data = payload.data(using: .utf8) else {
            call.reject("payload is required.")
            return
        }
        do {
            guard let qr = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (qr["ver"] as? String)?.lowercased() == "v1",
                  let name = qr["name"] as? String,
                  let transportText = qr["transport"] as? String,
                  let transport = transportValue(transportText) else {
                call.reject("Invalid Espressif provisioning QR payload.")
                return
            }
            if let network = qr["network"] as? String, !network.isEmpty, network.lowercased() != "wifi" {
                call.reject("This plugin currently supports Wi-Fi provisioning QR payloads only.")
                return
            }
            createDevice(
                call: call,
                name: name,
                transport: transport,
                security: securityValue(qr["security"] as? Int),
                pop: qr["pop"] as? String ?? "",
                username: qr["username"] as? String ?? "",
                softAPPassword: qr["password"] as? String ?? ""
            )
        } catch {
            emitError(operation: "createDeviceFromQr", message: error.localizedDescription)
            call.reject("QR payload is not valid JSON.")
        }
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard var device = selectedDevice else {
            call.reject("No ESP device is selected.")
            return
        }
        selectedPop = call.getString("pop") ?? selectedPop
        selectedUsername = call.getString("username") ?? selectedUsername
        selectedSoftAPPassword = call.getString("softApPassword") ?? selectedSoftAPPassword

        if selectedTransport == .softap {
            device = ESPDevice(
                name: selectedName,
                security: selectedSecurity,
                transport: .softap,
                proofOfPossession: selectedPop,
                username: selectedUsername,
                network: .wifi,
                softAPPassword: selectedSoftAPPassword
            )
            selectedDevice = device
        }

        device.security = selectedSecurity
        device.username = selectedUsername
        device.bleDelegate = self
        connected = false
        emitConnectionState("connecting")

        var settled = false
        device.connect(delegate: self) { status in
            DispatchQueue.main.async {
                switch status {
                case .connected:
                    self.connected = true
                    self.emitConnectionState("connected")
                    if !settled {
                        settled = true
                        call.resolve(self.deviceInfo())
                    }
                case let .failedToConnect(error):
                    self.connected = false
                    self.emitConnectionState("failed", message: error.description)
                    if !settled {
                        settled = true
                        call.reject(error.description)
                    }
                case .disconnected:
                    self.connected = false
                    self.emitConnectionState("disconnected")
                    if !settled {
                        settled = true
                        call.reject("The ESP device disconnected while connecting.")
                    }
                }
            }
        }
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        selectedDevice?.disconnect()
        connected = false
        emitConnectionState("disconnected")
        call.resolve()
    }

    @objc func getDeviceInfo(_ call: CAPPluginCall) {
        guard selectedDevice != nil else {
            call.reject("No ESP device is selected.")
            return
        }
        call.resolve(deviceInfo())
    }

    @objc func scanWifiNetworks(_ call: CAPPluginCall) {
        guard let device = requireConnected(call) else { return }
        device.scanWifiList { networks, error in
            DispatchQueue.main.async {
                if let error = error {
                    self.emitError(operation: "scanWifiNetworks", message: error.description)
                    call.reject(error.description)
                    return
                }
                let result = (networks ?? []).map { network -> [String: Any] in
                    [
                        "ssid": network.ssid,
                        "rssi": network.rssi,
                        "security": String(describing: network.auth),
                        "channel": network.channel,
                        "bssid": network.bssid.base64EncodedString()
                    ]
                }
                call.resolve(["networks": result])
            }
        }
    }

    @objc func provision(_ call: CAPPluginCall) {
        guard let device = requireConnected(call) else { return }
        guard let ssid = call.getString("ssid"), !ssid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            call.reject("ssid is required.")
            return
        }
        let password = call.getString("password") ?? ""
        var settled = false
        emitProvisioningProgress("session", message: "Secure session is ready.")
        device.provision(ssid: ssid, passPhrase: password) { status in
            DispatchQueue.main.async {
                switch status {
                case .configApplied:
                    self.emitProvisioningProgress("config-applied", message: "The device accepted the Wi-Fi configuration.")
                    self.emitProvisioningProgress("checking-connection", message: "Waiting for the device to connect to the access point.")
                case .success:
                    self.emitProvisioningProgress("success", message: "The device connected to Wi-Fi.")
                    if !settled {
                        settled = true
                        var result: [String: Any] = ["success": true]
                        if let ipAddress = device.wifiConnectedIp4Addr() {
                            result["ipAddress"] = ipAddress
                        }
                        call.resolve(result)
                    }
                case let .failure(error):
                    self.emitProvisioningProgress("failed", message: error.description)
                    if !settled {
                        settled = true
                        call.reject(error.description)
                    }
                }
            }
        }
    }

    @objc func sendCustomData(_ call: CAPPluginCall) {
        guard let device = requireConnected(call) else { return }
        guard let endpoint = call.getString("endpoint"), !endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            call.reject("endpoint is required.")
            return
        }
        let encoding = normalizeEncoding(call.getString("encoding"))
        do {
            let request = try decode(call.getString("data") ?? "", encoding: encoding)
            device.sendData(path: endpoint, data: request) { response, error in
                DispatchQueue.main.async {
                    if let error = error {
                        self.emitError(operation: "sendCustomData", message: error.description)
                        call.reject(error.description)
                        return
                    }
                    call.resolve(self.binaryResult(response ?? Data(), encoding: encoding))
                }
            }
        } catch {
            emitError(operation: "sendCustomData", message: error.localizedDescription)
            call.reject(error.localizedDescription)
        }
    }

    @objc func resetWifi(_ call: CAPPluginCall) {
        guard let device = requireConnected(call) else { return }
        device.resetWifiStatus { success, error in
            DispatchQueue.main.async {
                if let error = error {
                    self.emitError(operation: "resetWifi", message: error.localizedDescription)
                    call.reject(error.localizedDescription)
                } else {
                    call.resolve(["success": success])
                }
            }
        }
    }

    @objc func clearState(_ call: CAPPluginCall) {
        manager.stopESPDevicesSearch()
        selectedDevice?.disconnect()
        discoveredDevices.removeAll()
        discoveryOrder.removeAll()
        selectedDevice = nil
        selectedID = nil
        selectedName = ""
        selectedTransport = .ble
        selectedSecurity = .secure2
        selectedPop = ""
        selectedUsername = ""
        selectedSoftAPPassword = ""
        connected = false
        call.resolve()
    }

    private func createDevice(
        call: CAPPluginCall,
        name: String,
        transport: ESPTransport,
        security: ESPSecurity,
        pop: String,
        username: String,
        softAPPassword: String
    ) {
        if transport == .ble,
           let existing = discoveryOrder.compactMap({ id in discoveredDevices[id].map { (id, $0) } }).first(where: { $0.1.name == name }) {
            select(device: existing.1, id: existing.0, transport: .ble, security: security, pop: pop, username: username, softAPPassword: "")
            call.resolve(deviceInfo())
            return
        }

        if transport == .ble {
            emitScanState("started")
        }
        manager.createESPDevice(
            deviceName: name,
            transport: transport,
            security: security,
            proofOfPossession: pop,
            softAPPassword: softAPPassword,
            username: username,
            network: .wifi
        ) { device, error in
            DispatchQueue.main.async {
                if transport == .ble {
                    self.emitScanState(error == nil ? "completed" : "failed", message: error?.description)
                }
                if let error = error {
                    self.emitError(operation: "createDevice", message: error.description)
                    call.reject(error.description)
                    return
                }
                guard let device = device else {
                    call.reject("Espressif SDK did not return a device.")
                    return
                }
                let id = transport == .ble ? "ios:\(UUID().uuidString)" : "softap:\(name)"
                if transport == .ble {
                    self.discoveredDevices[id] = device
                    self.discoveryOrder.append(id)
                    self.notifyListeners("bleScanResult", data: self.bleDeviceInfo(id: id, device: device))
                }
                self.select(
                    device: device,
                    id: id,
                    transport: transport,
                    security: security,
                    pop: pop,
                    username: username,
                    softAPPassword: softAPPassword
                )
                call.resolve(self.deviceInfo())
            }
        }
    }

    private func select(
        device: ESPDevice,
        id: String,
        transport: ESPTransport,
        security: ESPSecurity,
        pop: String,
        username: String,
        softAPPassword: String
    ) {
        device.security = security
        device.username = username
        selectedDevice = device
        selectedID = id
        selectedName = device.name
        selectedTransport = transport
        selectedSecurity = security
        selectedPop = pop
        selectedUsername = username
        selectedSoftAPPassword = softAPPassword
        connected = false
    }

    private func requireConnected(_ call: CAPPluginCall) -> ESPDevice? {
        guard let device = selectedDevice, connected, device.isSessionEstablished() else {
            call.reject("No connected ESP provisioning device.")
            return nil
        }
        return device
    }

    private func permissionStatus() -> [String: Any] {
        let bluetooth: String
        switch CBManager.authorization {
        case .allowedAlways: bluetooth = "granted"
        case .denied, .restricted: bluetooth = "denied"
        case .notDetermined: bluetooth = "prompt"
        @unknown default: bluetooth = "prompt"
        }

        let location: String
        switch CLLocationManager.authorizationStatus() {
        case .authorizedAlways, .authorizedWhenInUse: location = "granted"
        case .denied, .restricted: location = "denied"
        case .notDetermined: location = "prompt"
        @unknown default: location = "prompt"
        }
        return ["bluetooth": bluetooth, "location": location, "nearbyWifi": "prompt"]
    }

    private func deviceInfo() -> [String: Any] {
        var result: [String: Any] = [
            "id": selectedID ?? "",
            "name": selectedName,
            "transport": selectedTransport.rawValue,
            "security": selectedSecurity.rawValue,
            "connected": connected,
            "capabilities": selectedDevice?.capabilities ?? []
        ]
        if let versionInfo = selectedDevice?.versionInfo {
            result["versionInfo"] = versionInfo
        }
        return result
    }

    private func bleDeviceInfo(id: String, device: ESPDevice) -> [String: Any] {
        ["id": id, "name": device.name, "transport": "ble"]
    }

    private func transportValue(_ value: String?) -> ESPTransport? {
        guard let value = value?.lowercased() else { return nil }
        return ESPTransport(rawValue: value)
    }

    private func securityValue(_ value: Int?) -> ESPSecurity {
        ESPSecurity(rawValue: value ?? 2)
    }

    private func normalizeEncoding(_ value: String?) -> String {
        let normalized = value?.lowercased() ?? "utf8"
        return ["utf8", "base64", "hex"].contains(normalized) ? normalized : "utf8"
    }

    private func decode(_ value: String, encoding: String) throws -> Data {
        switch encoding {
        case "base64":
            guard let data = Data(base64Encoded: value) else {
                throw WiFiProvBridgeError.invalidBase64
            }
            return data
        case "hex":
            let normalized = value.filter { !$0.isWhitespace }
            guard normalized.count.isMultiple(of: 2) else {
                throw WiFiProvBridgeError.invalidHex
            }
            var data = Data(capacity: normalized.count / 2)
            var index = normalized.startIndex
            while index < normalized.endIndex {
                let next = normalized.index(index, offsetBy: 2)
                guard let byte = UInt8(normalized[index..<next], radix: 16) else {
                    throw WiFiProvBridgeError.invalidHex
                }
                data.append(byte)
                index = next
            }
            return data
        default:
            return Data(value.utf8)
        }
    }

    private func binaryResult(_ data: Data, encoding: String) -> [String: Any] {
        let base64 = data.base64EncodedString()
        let utf8 = String(data: data, encoding: .utf8)
        let value: String
        switch encoding {
        case "base64": value = base64
        case "hex": value = data.map { String(format: "%02x", $0) }.joined()
        default: value = utf8 ?? ""
        }
        var result: [String: Any] = ["data": value, "encoding": encoding, "base64": base64]
        if let utf8 = utf8 {
            result["utf8"] = utf8
        }
        return result
    }

    private func emitScanState(_ state: String, message: String? = nil) {
        var data: [String: Any] = ["state": state]
        if let message = message { data["message"] = message }
        notifyListeners("bleScanState", data: data)
    }

    private func emitConnectionState(_ state: String, message: String? = nil) {
        var data: [String: Any] = ["state": state]
        if selectedDevice != nil { data["device"] = deviceInfo() }
        if let message = message { data["message"] = message }
        notifyListeners("connectionState", data: data)
    }

    private func emitProvisioningProgress(_ step: String, message: String? = nil) {
        var data: [String: Any] = ["step": step]
        if let message = message { data["message"] = message }
        notifyListeners("provisioningProgress", data: data)
    }

    private func emitError(operation: String, message: String) {
        notifyListeners("error", data: ["operation": operation, "message": message])
    }
}

extension WiFiProvPlugin: ESPDeviceConnectionDelegate {
    public func getProofOfPossesion(forDevice _: ESPDevice, completionHandler: @escaping (String) -> Void) {
        completionHandler(selectedPop)
    }

    public func getUsername(forDevice _: ESPDevice, completionHandler: @escaping (String?) -> Void) {
        completionHandler(selectedUsername.isEmpty ? nil : selectedUsername)
    }
}

extension WiFiProvPlugin: ESPBLEDelegate {
    public func peripheralConnected() {
        // The secure session is not ready until connect(delegate:) returns .connected.
    }

    public func peripheralDisconnected(peripheral _: CBPeripheral, error: Error?) {
        DispatchQueue.main.async {
            self.connected = false
            self.emitConnectionState("disconnected", message: error?.localizedDescription)
        }
    }

    public func peripheralFailedToConnect(peripheral _: CBPeripheral?, error: Error?) {
        DispatchQueue.main.async {
            self.connected = false
            self.emitConnectionState("failed", message: error?.localizedDescription ?? "Failed to connect to the BLE device.")
        }
    }
}

private enum WiFiProvBridgeError: LocalizedError {
    case invalidBase64
    case invalidHex

    var errorDescription: String? {
        switch self {
        case .invalidBase64: return "Data is not valid Base64."
        case .invalidHex: return "Data is not valid hexadecimal text."
        }
    }
}
