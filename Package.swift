// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorWifiprov",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorWifiprov", targets: ["WiFiProvPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        .package(url: "https://github.com/espressif/esp-idf-provisioning-ios.git", from: "3.1.0")
    ],
    targets: [
        .target(
            name: "WiFiProvPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "ESPProvision", package: "esp-idf-provisioning-ios")
            ],
            path: "ios/Sources/WiFiProvPlugin"
        )
    ]
)
