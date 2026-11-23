#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MACOS_DIR="${REPO_ROOT}/macos"
DIST_DIR="${MACOS_DIR}/dist"
APP_BUNDLE="${DIST_DIR}/NexusTS.app"

UNIVERSAL_FLAG=""
if [[ "${1:-}" == "--universal" ]]; then
    UNIVERSAL_FLAG="--universal"
fi

export MACOSX_DEPLOYMENT_TARGET="14.0"

"${SCRIPT_DIR}/build-macos-native.sh" ${UNIVERSAL_FLAG}

echo "==> Compiling NexusTS SwiftUI / AppKit macOS executable..."
if [[ -n "${UNIVERSAL_FLAG}" ]]; then
    swift build --package-path "${MACOS_DIR}" -c release --arch arm64
    swift build --package-path "${MACOS_DIR}" -c release --arch x86_64
    ARM64_BIN="$(swift build --package-path "${MACOS_DIR}" -c release --arch arm64 --show-bin-path)/NexusTS"
    X86_64_BIN="$(swift build --package-path "${MACOS_DIR}" -c release --arch x86_64 --show-bin-path)/NexusTS"
    mkdir -p "${MACOS_DIR}/.build/universal"
    BIN_PATH="${MACOS_DIR}/.build/universal/NexusTS"
    lipo -create -output "${BIN_PATH}" "${ARM64_BIN}" "${X86_64_BIN}"
else
    swift build --package-path "${MACOS_DIR}" -c release
    BIN_PATH="$(swift build --package-path "${MACOS_DIR}" -c release --show-bin-path)/NexusTS"
fi

rm -rf "${DIST_DIR}"
mkdir -p "${APP_BUNDLE}/Contents/MacOS" "${APP_BUNDLE}/Contents/Resources"

cp "${BIN_PATH}" "${APP_BUNDLE}/Contents/MacOS/NexusTS"
chmod +x "${APP_BUNDLE}/Contents/MacOS/NexusTS"
cp "${MACOS_DIR}/Info.plist" "${APP_BUNDLE}/Contents/Info.plist"

RAW_RELEASE_TAG=""
if [[ "${NEXUSTS_RELEASE_TAG:-}" == v* ]]; then
    RAW_RELEASE_TAG="${NEXUSTS_RELEASE_TAG}"
elif [[ "${GITHUB_REF_NAME:-}" == v* ]]; then
    RAW_RELEASE_TAG="${GITHUB_REF_NAME}"
fi

if [[ -n "${RAW_RELEASE_TAG}" ]]; then
    RESOLVED_VERSION_NAME="${RAW_RELEASE_TAG#v}"
    if [[ "${RESOLVED_VERSION_NAME}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
        V_MAJOR=$(( 10#${BASH_REMATCH[1]} ))
        V_MINOR=$(( 10#${BASH_REMATCH[2]} ))
        V_PATCH=$(( 10#${BASH_REMATCH[3]} ))
        if (( V_MAJOR <= 1 && V_MINOR <= 9 && V_PATCH <= 99 )); then
            COMPUTED_BUILD=$(( 10000 + V_MAJOR * 1000 + V_MINOR * 100 + V_PATCH ))
        else
            COMPUTED_BUILD=$(( V_MAJOR * 1000000 + V_MINOR * 1000 + V_PATCH ))
        fi
        RESOLVED_BUILD_NUMBER=$(( COMPUTED_BUILD > 11000 ? COMPUTED_BUILD : 11000 ))
    else
        RESOLVED_BUILD_NUMBER="11000"
    fi
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString ${RESOLVED_VERSION_NAME}" "${APP_BUNDLE}/Contents/Info.plist"
    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion ${RESOLVED_BUILD_NUMBER}" "${APP_BUNDLE}/Contents/Info.plist"
else
    RESOLVED_VERSION_NAME="$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "${APP_BUNDLE}/Contents/Info.plist")"
    RESOLVED_BUILD_NUMBER="$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "${APP_BUNDLE}/Contents/Info.plist")"
fi
echo "==> Stamped NexusTS.app Info.plist: CFBundleShortVersionString=${RESOLVED_VERSION_NAME}, CFBundleVersion=${RESOLVED_BUILD_NUMBER}"

printf "APPL????" > "${APP_BUNDLE}/Contents/PkgInfo"

FREERDP_MAC_LIB_DIR="${REPO_ROOT}/native/third_party/freerdp3/macos/lib"
if [[ ! -f "${FREERDP_MAC_LIB_DIR}/libfreerdp3.a" && -e "${FREERDP_MAC_LIB_DIR}/libfreerdp3.dylib" ]]; then
    echo "==> Embedding Universal2 FreeRDP 3.x dynamic libraries..."
    mkdir -p "${APP_BUNDLE}/Contents/Frameworks"
    cp -a "${FREERDP_MAC_LIB_DIR}/"*.dylib "${APP_BUNDLE}/Contents/Frameworks/"
else
    echo "==> FreeRDP 3.x + OpenSSL 3.4 statically linked into Contents/MacOS/NexusTS (zero external dylibs)."
fi

echo "==> Generating Apple HIG continuous superellipse AppIcon (832x832 in 1024x1024) + Assets.car..."
ICONSET_DIR="${DIST_DIR}/AppIcon.iconset"
XCASSETS_DIR="${DIST_DIR}/Assets.xcassets"
APPICONSET_DIR="${XCASSETS_DIR}/AppIcon.appiconset"
rm -rf "${ICONSET_DIR}" "${XCASSETS_DIR}"
mkdir -p "${ICONSET_DIR}" "${APPICONSET_DIR}"

swift - "${ICONSET_DIR}" "${APPICONSET_DIR}" <<'SWIFT_ICON'
import AppKit
import CoreGraphics
import Foundation
import SwiftUI

let outDir = CommandLine.arguments[1]
let appIconSetDir = CommandLine.arguments[2]
let specs: [(String, Int, Int, String)] = [
    ("icon_16x16.png", 16, 16, "1x"),
    ("icon_16x16@2x.png", 32, 16, "2x"),
    ("icon_32x32.png", 32, 32, "1x"),
    ("icon_32x32@2x.png", 64, 32, "2x"),
    ("icon_128x128.png", 128, 128, "1x"),
    ("icon_128x128@2x.png", 256, 128, "2x"),
    ("icon_256x256.png", 256, 256, "1x"),
    ("icon_256x256@2x.png", 512, 256, "2x"),
    ("icon_512x512.png", 512, 512, "1x"),
    ("icon_512x512@2x.png", 1024, 512, "2x")
]

func rgba(_ hex: UInt32, alpha: CGFloat = 1.0) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255.0
    let g = CGFloat((hex >> 8) & 0xFF) / 255.0
    let b = CGFloat(hex & 0xFF) / 255.0
    return CGColor(red: r, green: g, blue: b, alpha: alpha)
}

for (filename, px, pointDim, _) in specs {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: px,
        pixelsHigh: px,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    )!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let ctx = NSGraphicsContext.current!.cgContext
    let size = CGFloat(px)
    ctx.clear(CGRect(x: 0, y: 0, width: size, height: size))

    // Flip to top-down coordinate system matching Android VectorDrawable (0,0 = top-left)
    ctx.translateBy(x: 0, y: size)
    ctx.scaleBy(x: 1, y: -1)

    // Exact Apple macOS Sonoma / Sequoia / Tahoe 26 continuous superellipse grid:
    // 832x832 squircle centered inside 1024x1024 canvas (96px transparent margin on all 4 sides).
    // Do NOT draw any outer CoreGraphics shadow outside squirclePath: macOS Dock & IconFoundation
    // inspect alpha bounds and automatically downscale any icon whose non-zero alpha exceeds the
    // standard 832x832 squircle envelope, while compositing native Dock shadow & Liquid Glass rim.
    let inset = (96.0 / 1024.0) * size
    let squircleRect = CGRect(x: inset, y: inset, width: size - inset * 2.0, height: size - inset * 2.0)
    let squircleCornerRadius = squircleRect.width * 0.225
    let squirclePath = RoundedRectangle(cornerRadius: squircleCornerRadius, style: .continuous)
        .path(in: squircleRect)
        .cgPath

    ctx.saveGState()
    ctx.addPath(squirclePath)
    ctx.clip()

    // Map foreground emblem viewport (x=17.5..90.5, y=17.5..90.5 of 108x108) into full 832x832 squircle
    let cropOrigin: CGFloat = 17.5
    let cropSpan: CGFloat = 73.0
    let scale = squircleRect.width / cropSpan
    ctx.saveGState()
    ctx.translateBy(x: squircleRect.minX - cropOrigin * scale, y: squircleRect.minY - cropOrigin * scale)
    ctx.scaleBy(x: scale, y: scale)

    // --- 1. ic_launcher_background.xml ---
    // Deep Midnight Sapphire Base (#081124)
    ctx.setFillColor(rgba(0x081124))
    ctx.fill(CGRect(x: 0, y: 0, width: 108, height: 108))

    // Expressive Tonal Horizon Sweep (#102347): M0,0 L108,0 L108,66 C78,80 34,68 0,88 Z
    let sweep = CGMutablePath()
    sweep.move(to: CGPoint(x: 0, y: 0))
    sweep.addLine(to: CGPoint(x: 108, y: 0))
    sweep.addLine(to: CGPoint(x: 108, y: 66))
    sweep.addCurve(to: CGPoint(x: 0, y: 88), control1: CGPoint(x: 78, y: 80), control2: CGPoint(x: 34, y: 68))
    sweep.closeSubpath()
    ctx.setFillColor(rgba(0x102347))
    ctx.addPath(sweep)
    ctx.fillPath()

    // Ambient Royal Sapphire Glow (#173673, alpha 0.58)
    let glow = CGMutablePath()
    glow.move(to: CGPoint(x: 54, y: 12))
    glow.addCurve(to: CGPoint(x: 98, y: 52), control1: CGPoint(x: 82, y: 12), control2: CGPoint(x: 98, y: 28))
    glow.addCurve(to: CGPoint(x: 54, y: 78), control1: CGPoint(x: 98, y: 72), control2: CGPoint(x: 78, y: 84))
    glow.addCurve(to: CGPoint(x: 24, y: 34), control1: CGPoint(x: 30, y: 72), control2: CGPoint(x: 18, y: 54))
    glow.addCurve(to: CGPoint(x: 54, y: 12), control1: CGPoint(x: 29, y: 18), control2: CGPoint(x: 40, y: 12))
    glow.closeSubpath()
    ctx.setFillColor(rgba(0x173673, alpha: 0.58))
    ctx.addPath(glow)
    ctx.fillPath()

    // --- 2. ic_launcher_foreground.xml ---
    // Remote Desktop Display Outer Bezel (#E2EEFF): x=25..83, y=27..67, rx=7
    let bezelPath = CGPath(roundedRect: CGRect(x: 25, y: 27, width: 58, height: 40), cornerWidth: 7, cornerHeight: 7, transform: nil)
    ctx.setFillColor(rgba(0xE2EEFF))
    ctx.addPath(bezelPath)
    ctx.fillPath()

    // Midnight Sapphire Screen Interior (#081328): x=28.5..79.5, y=30.5..63.5, rx=4.5
    let screenRect = CGRect(x: 28.5, y: 30.5, width: 51, height: 33)
    let screenPath = CGPath(roundedRect: screenRect, cornerWidth: 4.5, cornerHeight: 4.5, transform: nil)
    ctx.setFillColor(rgba(0x081328))
    ctx.addPath(screenPath)
    ctx.fillPath()

    // Remote Window Header Strip (#142C57) clipped to screen top
    ctx.saveGState()
    ctx.addPath(screenPath)
    ctx.clip()
    ctx.setFillColor(rgba(0x142C57))
    ctx.fill(CGRect(x: 28.5, y: 30.5, width: 51, height: 7.0))
    ctx.restoreGState()

    // Window Control Dots (#38BDF8 at 34,34 r=1.6; #7AA8FF at 39,34 r=1.6)
    ctx.setFillColor(rgba(0x38BDF8))
    ctx.fillEllipse(in: CGRect(x: 34 - 1.6, y: 34 - 1.6, width: 3.2, height: 3.2))
    ctx.setFillColor(rgba(0x7AA8FF))
    ctx.fillEllipse(in: CGRect(x: 39 - 1.6, y: 34 - 1.6, width: 3.2, height: 3.2))

    // Left Pane: Electric Sky-Cyan SSH Terminal Chevron + Prompt Underscore (#38BDF8)
    let chevron = CGMutablePath()
    chevron.move(to: CGPoint(x: 34.2, y: 42.5))
    chevron.addLine(to: CGPoint(x: 44.2, y: 50.2))
    chevron.addLine(to: CGPoint(x: 34.2, y: 57.9))
    chevron.addLine(to: CGPoint(x: 31.9, y: 55.1))
    chevron.addLine(to: CGPoint(x: 38.3, y: 50.2))
    chevron.addLine(to: CGPoint(x: 31.9, y: 45.3))
    chevron.closeSubpath()
    ctx.setFillColor(rgba(0x38BDF8))
    ctx.addPath(chevron)
    ctx.fillPath()

    let underscore = CGPath(
        roundedRect: CGRect(x: 43.6, y: 54.4, width: 11.5, height: 3.2),
        cornerWidth: 1.6,
        cornerHeight: 1.6,
        transform: nil
    )
    ctx.addPath(underscore)
    ctx.fillPath()

    // Right Pane: Connected Remote Desktop Tile (#1A386E + #3B82F6 header + #93C5FD / #60A5FA bars)
    let rdpTileRect = CGRect(x: 57.5, y: 41.5, width: 18.5, height: 17.5)
    let rdpTilePath = CGPath(roundedRect: rdpTileRect, cornerWidth: 2.5, cornerHeight: 2.5, transform: nil)
    ctx.setFillColor(rgba(0x1A386E))
    ctx.addPath(rdpTilePath)
    ctx.fillPath()

    ctx.saveGState()
    ctx.addPath(rdpTilePath)
    ctx.clip()
    ctx.setFillColor(rgba(0x3B82F6))
    ctx.fill(CGRect(x: 57.5, y: 41.5, width: 18.5, height: 4.7))
    ctx.restoreGState()

    ctx.setFillColor(rgba(0x93C5FD))
    ctx.fill(CGRect(x: 61.0, y: 50.0, width: 11.5, height: 2.2))
    ctx.setFillColor(rgba(0x60A5FA))
    ctx.fill(CGRect(x: 61.0, y: 54.0, width: 7.5, height: 2.2))

    // Display Stand Neck (#93C5FD) + Connected Server Hub Base (#E2EEFF)
    ctx.setFillColor(rgba(0x93C5FD))
    ctx.fill(CGRect(x: 50.5, y: 67.0, width: 7.0, height: 7.5))

    let hubBasePath = CGPath(
        roundedRect: CGRect(x: 37.0, y: 73.5, width: 34.0, height: 5.0),
        cornerWidth: 2.5,
        cornerHeight: 2.5,
        transform: nil
    )
    ctx.setFillColor(rgba(0xE2EEFF))
    ctx.addPath(hubBasePath)
    ctx.fillPath()

    // Active Server Node Pulse Core (#081328 outer + #38BDF8 inner)
    ctx.setFillColor(rgba(0x081328))
    ctx.fillEllipse(in: CGRect(x: 54.0 - 4.2, y: 76.0 - 4.2, width: 8.4, height: 8.4))
    ctx.setFillColor(rgba(0x38BDF8))
    ctx.fillEllipse(in: CGRect(x: 54.0 - 2.4, y: 76.0 - 2.4, width: 4.8, height: 4.8))

    ctx.restoreGState()

    // Crisp inner specular glass rim clipped strictly INSIDE squirclePath (zero outer alpha bleed)
    ctx.setStrokeColor(rgba(0x93C5FD, alpha: 0.34))
    ctx.setLineWidth(max(1.2, size * 0.014))
    ctx.addPath(squirclePath)
    ctx.strokePath()

    ctx.restoreGState()

    NSGraphicsContext.restoreGraphicsState()
    rep.size = NSSize(width: pointDim, height: pointDim)
    if let png = rep.representation(using: .png, properties: [:]) {
        try png.write(to: URL(fileURLWithPath: "\(outDir)/\(filename)"))
        try png.write(to: URL(fileURLWithPath: "\(appIconSetDir)/\(filename)"))
    }
}

let imagesJson = specs.map { (filename, _, pointDim, scaleStr) -> [String: String] in
    [
        "filename": filename,
        "idiom": "mac",
        "scale": scaleStr,
        "size": "\(pointDim)x\(pointDim)"
    ]
}
let rootCatalog: [String: Any] = [
    "info": ["author": "xcode", "version": 1]
]
let appIconContents: [String: Any] = [
    "images": imagesJson,
    "info": ["author": "xcode", "version": 1]
]
let rootData = try JSONSerialization.data(withJSONObject: rootCatalog, options: [.prettyPrinted, .sortedKeys])
try rootData.write(to: URL(fileURLWithPath: "\(appIconSetDir)/../Contents.json"))
let iconData = try JSONSerialization.data(withJSONObject: appIconContents, options: [.prettyPrinted, .sortedKeys])
try iconData.write(to: URL(fileURLWithPath: "\(appIconSetDir)/Contents.json"))
SWIFT_ICON

if xcrun -f actool >/dev/null 2>&1; then
    echo "==> Compiling Assets.car via xcrun actool..."
    xcrun actool \
        --output-format human-readable-text \
        --notices \
        --warnings \
        --platform macosx \
        --minimum-deployment-target 14.0 \
        --target-device mac \
        --app-icon AppIcon \
        --output-partial-info-plist "${DIST_DIR}/assetcatalog_info.plist" \
        --compile "${APP_BUNDLE}/Contents/Resources" \
        "${XCASSETS_DIR}" >/dev/null
    rm -f "${DIST_DIR}/assetcatalog_info.plist"
fi

iconutil -c icns "${ICONSET_DIR}" -o "${APP_BUNDLE}/Contents/Resources/AppIcon.icns"
rm -rf "${ICONSET_DIR}" "${XCASSETS_DIR}"

echo "==> Code-signing NexusTS.app..."
SIGN_IDENTITY="${CODESIGN_IDENTITY:--}"
SIGN_EXTRA_FLAGS=()
ENTITLEMENTS_ARGS=()
if [[ "${SIGN_IDENTITY}" != "-" ]]; then
    SIGN_EXTRA_FLAGS+=(--options runtime --timestamp)
    ENTITLEMENTS_ARGS+=(--entitlements "${MACOS_DIR}/NexusTS.entitlements")
fi

if [[ -d "${APP_BUNDLE}/Contents/Frameworks" ]]; then
    while IFS= read -r -d '' dylib_file; do
        codesign --force ${SIGN_EXTRA_FLAGS[@]+"${SIGN_EXTRA_FLAGS[@]}"} --sign "${SIGN_IDENTITY}" "${dylib_file}"
    done < <(find "${APP_BUNDLE}/Contents/Frameworks" -type f -name "*.dylib" -print0)
fi

codesign --force ${SIGN_EXTRA_FLAGS[@]+"${SIGN_EXTRA_FLAGS[@]}"} ${ENTITLEMENTS_ARGS[@]+"${ENTITLEMENTS_ARGS[@]}"} --sign "${SIGN_IDENTITY}" "${APP_BUNDLE}"
codesign --verify --deep --strict "${APP_BUNDLE}"

ARCHIVE_SUFFIX="Host"
if [[ -n "${UNIVERSAL_FLAG}" ]]; then
    ARCHIVE_SUFFIX="Universal"
fi

ZIP_OUT="${DIST_DIR}/NexusTS-macOS-${ARCHIVE_SUFFIX}.zip"
DMG_OUT="${DIST_DIR}/NexusTS-macOS-${ARCHIVE_SUFFIX}.dmg"

HAS_NOTARY_CREDENTIALS="false"
if [[ "${SIGN_IDENTITY}" != "-" && -n "${APPLE_ID:-}" && -n "${APPLE_TEAM_ID:-}" && -n "${APPLE_APP_SPECIFIC_PASSWORD:-}" ]]; then
    HAS_NOTARY_CREDENTIALS="true"
    NOTARY_UPLOAD_ZIP="${DIST_DIR}/NexusTS-notary-upload.zip"
    echo "==> Submitting NexusTS.app for Apple Developer ID Notarization & Stapling..."
    ditto -c -k --sequesterRsrc --keepParent "${APP_BUNDLE}" "${NOTARY_UPLOAD_ZIP}"
    xcrun notarytool submit "${NOTARY_UPLOAD_ZIP}" \
        --apple-id "${APPLE_ID}" \
        --team-id "${APPLE_TEAM_ID}" \
        --password "${APPLE_APP_SPECIFIC_PASSWORD}" \
        --wait
    rm -f "${NOTARY_UPLOAD_ZIP}"
    xcrun stapler staple "${APP_BUNDLE}"
    xcrun stapler validate "${APP_BUNDLE}"
fi

ditto -c -k --sequesterRsrc --keepParent "${APP_BUNDLE}" "${ZIP_OUT}"

STAGE_DMG_DIR="${DIST_DIR}/dmg-stage"
mkdir -p "${STAGE_DMG_DIR}"
ditto "${APP_BUNDLE}" "${STAGE_DMG_DIR}/NexusTS.app"
ln -s /Applications "${STAGE_DMG_DIR}/Applications"
hdiutil create -volname "NexusTS" -srcfolder "${STAGE_DMG_DIR}" -ov -format UDZO "${DMG_OUT}" >/dev/null
rm -rf "${STAGE_DMG_DIR}"

if [[ "${HAS_NOTARY_CREDENTIALS}" == "true" ]]; then
    echo "==> Submitting ${DMG_OUT} for Apple Developer ID Notarization & Stapling..."
    codesign --force --timestamp --sign "${SIGN_IDENTITY}" "${DMG_OUT}"
    xcrun notarytool submit "${DMG_OUT}" \
        --apple-id "${APPLE_ID}" \
        --team-id "${APPLE_TEAM_ID}" \
        --password "${APPLE_APP_SPECIFIC_PASSWORD}" \
        --wait
    xcrun stapler staple "${DMG_OUT}"
    xcrun stapler validate "${DMG_OUT}"
fi

echo "==> Packaged macOS artifacts:"
ls -lh "${DIST_DIR}"
lipo -archs "${APP_BUNDLE}/Contents/MacOS/NexusTS" || true
