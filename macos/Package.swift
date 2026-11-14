// swift-tools-version: 6.0
import Foundation
import PackageDescription

let packageDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let vendorLibDir = "\(packageDir)/Vendor/lib"

let package = Package(
    name: "NexusTSMac",
    defaultLocalization: "en",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(name: "NexusTSCore", targets: ["NexusTSCore"]),
        .executable(name: "NexusTS", targets: ["NexusTSMacApp"])
    ],
    targets: [
        .target(
            name: "CNexusTSNative",
            path: "Sources/CNexusTSNative",
            publicHeadersPath: "include"
        ),
        .target(
            name: "NexusTSCore",
            dependencies: ["CNexusTSNative"],
            path: "Sources/NexusTSCore",
            swiftSettings: [
                .swiftLanguageMode(.v5)
            ],
            linkerSettings: [
                .unsafeFlags(["-L\(vendorLibDir)", "-lnexusts_ffi", "-lnexusts_rdp_macos"]),
                .linkedLibrary("c++"),
                .linkedLibrary("resolv"),
                .linkedLibrary("z"),
                .linkedFramework("Foundation"),
                .linkedFramework("Security"),
                .linkedFramework("LocalAuthentication"),
                .linkedFramework("CoreSpotlight"),
                .linkedFramework("IOKit"),
                .linkedFramework("UniformTypeIdentifiers"),
                .linkedFramework("CryptoKit")
            ]
        ),
        .executableTarget(
            name: "NexusTSMacApp",
            dependencies: ["NexusTSCore", "CNexusTSNative"],
            path: "Sources/NexusTSMacApp",
            swiftSettings: [
                .swiftLanguageMode(.v5)
            ],
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("SwiftUI"),
                .linkedFramework("WebKit"),
                .linkedFramework("QuartzCore"),
                .linkedFramework("Metal"),
                .linkedFramework("UniformTypeIdentifiers"),
                .linkedFramework("CoreSpotlight"),
                .linkedFramework("LocalAuthentication")
            ]
        ),
        .testTarget(
            name: "NexusTSCoreTests",
            dependencies: ["NexusTSCore", "CNexusTSNative"],
            path: "Tests/NexusTSCoreTests",
            swiftSettings: [
                .swiftLanguageMode(.v5)
            ]
        )
    ]
)
