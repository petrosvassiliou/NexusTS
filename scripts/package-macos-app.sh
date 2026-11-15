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
printf "APPL????" > "${APP_BUNDLE}/Contents/PkgInfo"

FREERDP_MAC_LIB_DIR="${REPO_ROOT}/native/third_party/freerdp3/macos/lib"
if [[ ! -f "${FREERDP_MAC_LIB_DIR}/libfreerdp3.a" && -e "${FREERDP_MAC_LIB_DIR}/libfreerdp3.dylib" ]]; then
    echo "==> Embedding Universal2 FreeRDP 3.x dynamic libraries..."
    mkdir -p "${APP_BUNDLE}/Contents/Frameworks"
    cp -a "${FREERDP_MAC_LIB_DIR}/"*.dylib "${APP_BUNDLE}/Contents/Frameworks/"
else
    echo "==> FreeRDP 3.x + OpenSSL 3.4 statically linked into Contents/MacOS/NexusTS (zero external dylibs)."
fi

echo "==> Generating Apple HIG squircle AppIcon.icns matching Android NexusTS icon..."
ICONSET_DIR="${DIST_DIR}/AppIcon.iconset"
mkdir -p "${ICONSET_DIR}"

swift - "${ICONSET_DIR}" <<'SWIFT_ICON'
import AppKit
import CoreGraphics
import Foundation

let outDir = CommandLine.arguments[1]
let specs: [(String, Int)] = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024)
]

func rgba(_ hex: UInt32, alpha: CGFloat = 1.0) -> CGColor {
    let r = CGFloat((hex >> 16) & 0xFF) / 255.0
    let g = CGFloat((hex >> 8) & 0xFF) / 255.0
    let b = CGFloat(hex & 0xFF) / 255.0
    return CGColor(red: r, green: g, blue: b, alpha: alpha)
}

for (filename, px) in specs {
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

    // Flip to top-down coordinate system matching Android VectorDrawable (0,0 = top-left)
    ctx.translateBy(x: 0, y: size)
    ctx.scaleBy(x: 1, y: -1)

    // Standard macOS Big Sur / Sonoma / Sequoia squircle inset (824/1024 ≈ 80.5%)
    let inset = size * 0.095
    let squircleRect = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
    let squircleRadius = squircleRect.width * 0.224
    let squirclePath = CGPath(
        roundedRect: squircleRect,
        cornerWidth: squircleRadius,
        cornerHeight: squircleRadius,
        transform: nil
    )

    // Subtle macOS Dock drop shadow for >= 64px icons
    if px >= 64 {
        ctx.saveGState()
        ctx.setShadow(
            offset: CGSize(width: 0, height: size * 0.018),
            blur: size * 0.032,
            color: CGColor(red: 0, green: 0, blue: 0, alpha: 0.32)
        )
        ctx.setFillColor(rgba(0x081124))
        ctx.addPath(squirclePath)
        ctx.fillPath()
        ctx.restoreGState()
    }

    ctx.saveGState()
    ctx.addPath(squirclePath)
    ctx.clip()

    // Map Android Adaptive Icon visible viewport (x=16..92, y=16..92 of 108x108) into macOS squircle
    let cropOrigin: CGFloat = 16.0
    let cropSpan: CGFloat = 76.0
    let scale = squircleRect.width / cropSpan
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

    // Ambient Royal Sapphire Glow (#173673, alpha 0.55)
    let glow = CGMutablePath()
    glow.move(to: CGPoint(x: 54, y: 12))
    glow.addCurve(to: CGPoint(x: 98, y: 52), control1: CGPoint(x: 82, y: 12), control2: CGPoint(x: 98, y: 28))
    glow.addCurve(to: CGPoint(x: 54, y: 78), control1: CGPoint(x: 98, y: 72), control2: CGPoint(x: 78, y: 84))
    glow.addCurve(to: CGPoint(x: 24, y: 34), control1: CGPoint(x: 30, y: 72), control2: CGPoint(x: 18, y: 54))
    glow.addCurve(to: CGPoint(x: 54, y: 12), control1: CGPoint(x: 29, y: 18), control2: CGPoint(x: 40, y: 12))
    glow.closeSubpath()
    ctx.setFillColor(rgba(0x173673, alpha: 0.55))
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

    // Subtle glass rim highlight matching NexusBrandEmblem border
    ctx.saveGState()
    ctx.setStrokeColor(rgba(0x60A5FA, alpha: 0.28))
    ctx.setLineWidth(max(0.75, size * 0.008))
    ctx.addPath(squirclePath)
    ctx.strokePath()
    ctx.restoreGState()

    NSGraphicsContext.restoreGraphicsState()
    let pointDim = filename.contains("@2x") ? (px / 2) : px
    rep.size = NSSize(width: pointDim, height: pointDim)
    if let png = rep.representation(using: .png, properties: [:]) {
        try png.write(to: URL(fileURLWithPath: "\(outDir)/\(filename)"))
    }
}
SWIFT_ICON

iconutil -c icns "${ICONSET_DIR}" -o "${APP_BUNDLE}/Contents/Resources/AppIcon.icns"
rm -rf "${ICONSET_DIR}"

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

ditto -c -k --sequesterRsrc --keepParent "${APP_BUNDLE}" "${ZIP_OUT}"

STAGE_DMG_DIR="${DIST_DIR}/dmg-stage"
mkdir -p "${STAGE_DMG_DIR}"
ditto "${APP_BUNDLE}" "${STAGE_DMG_DIR}/NexusTS.app"
ln -s /Applications "${STAGE_DMG_DIR}/Applications"
hdiutil create -volname "NexusTS" -srcfolder "${STAGE_DMG_DIR}" -ov -format UDZO "${DMG_OUT}" >/dev/null
rm -rf "${STAGE_DMG_DIR}"

if [[ "${SIGN_IDENTITY}" != "-" && -n "${APPLE_ID:-}" && -n "${APPLE_TEAM_ID:-}" && -n "${APPLE_APP_SPECIFIC_PASSWORD:-}" ]]; then
    echo "==> Submitting ${DMG_OUT} for Apple Developer ID Notarization..."
    codesign --force --timestamp --sign "${SIGN_IDENTITY}" "${DMG_OUT}"
    xcrun notarytool submit "${DMG_OUT}" \
        --apple-id "${APPLE_ID}" \
        --team-id "${APPLE_TEAM_ID}" \
        --password "${APPLE_APP_SPECIFIC_PASSWORD}" \
        --wait
    xcrun stapler staple "${DMG_OUT}"
fi

echo "==> Packaged macOS artifacts:"
ls -lh "${DIST_DIR}"
lipo -archs "${APP_BUNDLE}/Contents/MacOS/NexusTS" || true
