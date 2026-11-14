#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENDOR_LIB_DIR="${REPO_ROOT}/macos/Vendor/lib"
CNATIVE_INC_DIR="${REPO_ROOT}/macos/Sources/CNexusTSNative/include"

UNIVERSAL=0
if [[ "${1:-}" == "--universal" ]]; then
    UNIVERSAL=1
fi

mkdir -p "${VENDOR_LIB_DIR}" "${CNATIVE_INC_DIR}"

echo "==> Syncing C-ABI headers into CNexusTSNative..."
cp "${REPO_ROOT}/native/rust/crates/nexusts-ffi/include/nexusts_ffi.h" "${CNATIVE_INC_DIR}/nexusts_ffi.h"
cp "${REPO_ROOT}/native/cpp/include/nexusts_rdp_macos.h" "${CNATIVE_INC_DIR}/nexusts_rdp_macos.h"

export MACOSX_DEPLOYMENT_TARGET="14.0"

if [[ "${UNIVERSAL}" -eq 1 ]]; then
    echo "==> Building Universal2 (arm64 + x86_64) Rust nexusts-ffi static library..."
    rustup target add aarch64-apple-darwin x86_64-apple-darwin >/dev/null 2>&1 || true

    cargo build --manifest-path "${REPO_ROOT}/native/rust/Cargo.toml" \
        --release -p nexusts-ffi --target aarch64-apple-darwin
    cargo build --manifest-path "${REPO_ROOT}/native/rust/Cargo.toml" \
        --release -p nexusts-ffi --target x86_64-apple-darwin

    lipo -create \
        "${REPO_ROOT}/native/rust/target/aarch64-apple-darwin/release/libnexusts_ffi.a" \
        "${REPO_ROOT}/native/rust/target/x86_64-apple-darwin/release/libnexusts_ffi.a" \
        -output "${VENDOR_LIB_DIR}/libnexusts_ffi.a"

    echo "==> Building Universal2 (arm64 + x86_64) C++20 FreeRDP 3.x macOS bridge..."
    CMAKE_BUILD_DIR="${REPO_ROOT}/native/build-macos-universal"
    cmake -S "${REPO_ROOT}/native" -B "${CMAKE_BUILD_DIR}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 \
        -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64"
    cmake --build "${CMAKE_BUILD_DIR}" --config Release --parallel
    cp "${CMAKE_BUILD_DIR}/libnexusts_rdp_macos.a" "${VENDOR_LIB_DIR}/libnexusts_rdp_macos.a"
else
    echo "==> Building host-native Rust nexusts-ffi static library..."
    cargo build --manifest-path "${REPO_ROOT}/native/rust/Cargo.toml" \
        --release -p nexusts-ffi
    cp "${REPO_ROOT}/native/rust/target/release/libnexusts_ffi.a" "${VENDOR_LIB_DIR}/libnexusts_ffi.a"

    echo "==> Building host-native C++20 FreeRDP 3.x macOS bridge..."
    CMAKE_BUILD_DIR="${REPO_ROOT}/native/build-macos-host"
    cmake -S "${REPO_ROOT}/native" -B "${CMAKE_BUILD_DIR}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0
    cmake --build "${CMAKE_BUILD_DIR}" --config Release --parallel
    cp "${CMAKE_BUILD_DIR}/libnexusts_rdp_macos.a" "${VENDOR_LIB_DIR}/libnexusts_rdp_macos.a"
fi

echo "==> Staged macOS native archives in ${VENDOR_LIB_DIR}:"
ls -lh "${VENDOR_LIB_DIR}"
lipo -info "${VENDOR_LIB_DIR}/libnexusts_ffi.a" "${VENDOR_LIB_DIR}/libnexusts_rdp_macos.a" || true
