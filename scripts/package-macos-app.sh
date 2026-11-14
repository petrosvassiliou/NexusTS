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
    swift build --package-path "${MACOS_DIR}" -c release --arch arm64 --arch x86_64
    BIN_PATH="${MACOS_DIR}/.build/apple/Products/Release/NexusTS"
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

echo "==> Generating Apple HIG squircle AppIcon.icns..."
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
    let inset = size * 0.08
    let rect = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
    let path = CGPath(roundedRect: rect, cornerWidth: size * 0.22, cornerHeight: size * 0.22, transform: nil)
    ctx.addPath(path)
    ctx.clip()

    let colors = [
        CGColor(red: 0.05, green: 0.16, blue: 0.42, alpha: 1.0),
        CGColor(red: 0.04, green: 0.52, blue: 1.00, alpha: 1.0)
    ] as CFArray
    let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0.0, 1.0])!
    ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: 0), end: CGPoint(x: size, y: size), options: [])

    // Terminal prompt chevron ">_"
    ctx.setStrokeColor(CGColor(red: 0.35, green: 0.96, blue: 0.76, alpha: 1.0))
    ctx.setLineWidth(max(2.0, size * 0.07))
    ctx.setLineCap(.round)
    ctx.setLineJoin(.round)
    ctx.move(to: CGPoint(x: size * 0.28, y: size * 0.64))
    ctx.addLine(to: CGPoint(x: size * 0.46, y: size * 0.50))
    ctx.addLine(to: CGPoint(x: size * 0.28, y: size * 0.36))
    ctx.strokePath()

    ctx.setStrokeColor(CGColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 0.95))
    ctx.move(to: CGPoint(x: size * 0.52, y: size * 0.36))
    ctx.addLine(to: CGPoint(x: size * 0.73, y: size * 0.36))
    ctx.strokePath()

    NSGraphicsContext.restoreGraphicsState()
    if let png = rep.representation(using: .png, properties: [:]) {
        try png.write(to: URL(fileURLWithPath: "\(outDir)/\(filename)"))
    }
}
SWIFT_ICON

iconutil -c icns "${ICONSET_DIR}" -o "${APP_BUNDLE}/Contents/Resources/AppIcon.icns"
rm -rf "${ICONSET_DIR}"

echo "==> Code-signing NexusTS.app..."
SIGN_IDENTITY="${CODESIGN_IDENTITY:--}"
codesign --force --deep --entitlements "${MACOS_DIR}/NexusTS.entitlements" --sign "${SIGN_IDENTITY}" "${APP_BUNDLE}"
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
cp -R "${APP_BUNDLE}" "${STAGE_DMG_DIR}/NexusTS.app"
ln -s /Applications "${STAGE_DMG_DIR}/Applications"
hdiutil create -volname "NexusTS" -srcfolder "${STAGE_DMG_DIR}" -ov -format UDZO "${DMG_OUT}" >/dev/null
rm -rf "${STAGE_DMG_DIR}"

echo "==> Packaged macOS artifacts:"
ls -lh "${DIST_DIR}"
lipo -archs "${APP_BUNDLE}/Contents/MacOS/NexusTS" || true
