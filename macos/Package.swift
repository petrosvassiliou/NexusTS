// swift-tools-version: 6.0
import Foundation
import PackageDescription

let packageDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let vendorLibDir = "\(packageDir)/Vendor/lib"
let freerdpMacLibDir = URL(fileURLWithPath: "\(packageDir)/../native/third_party/freerdp3/macos/lib").standardized.path
let hasPrebuiltFreeRdpStatic = FileManager.default.fileExists(atPath: "\(freerdpMacLibDir)/libfreerdp3.a")
let hasPrebuiltFreeRdpDynamic = !hasPrebuiltFreeRdpStatic
    && FileManager.default.fileExists(atPath: "\(freerdpMacLibDir)/libfreerdp3.dylib")

var coreUnsafeLinkerFlags: [String] = [
    "-L\(vendorLibDir)",
    "-lnexusts_ffi",
    "-lnexusts_rdp_macos"
]
if hasPrebuiltFreeRdpStatic {
    var staticArchives: [String] = []
    if let enumerator = FileManager.default.enumerator(atPath: freerdpMacLibDir) {
        while let rel = enumerator.nextObject() as? String {
            if rel.hasSuffix(".a") && !rel.contains("libwinpr-tools") {
                staticArchives.append("\(freerdpMacLibDir)/\(rel)")
            }
        }
    }
    staticArchives.sort()
    coreUnsafeLinkerFlags.append(contentsOf: staticArchives)
} else if hasPrebuiltFreeRdpDynamic {
    coreUnsafeLinkerFlags += [
        "-L\(freerdpMacLibDir)",
        "-lfreerdp3",
        "-lfreerdp-client3",
        "-lwinpr3"
    ]
}

var testLinkerSettings: [LinkerSetting] = []
if hasPrebuiltFreeRdpDynamic {
    testLinkerSettings.append(
        .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", freerdpMacLibDir])
    )
}

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
                .unsafeFlags(coreUnsafeLinkerFlags),
                .linkedLibrary("c++"),
                .linkedLibrary("resolv"),
                .linkedLibrary("z"),
                .linkedLibrary("iconv"),
                .linkedFramework("Foundation"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("CoreServices"),
                .linkedFramework("Carbon"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("VideoToolbox"),
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
                .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"]),
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
            ],
            linkerSettings: testLinkerSettings
        )
    ]
)
