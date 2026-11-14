#!/usr/bin/env bash
# ==============================================================================
# NexusTS (com.nexusts.app.macos) — Universal2 OpenSSL 3.4 + FreeRDP 3.12 Builder
# ==============================================================================
# Compiles OpenSSL 3.4.1 (static PIC) and FreeRDP 3.12.0 (Universal2 arm64 + x86_64
# @rpath dylibs with builtin DISP_DVC, RDPDR drive redirection, CLIPRDR, RDPSND)
# for macOS 14 Sonoma+.
#
# Staged output matches native/CMakeLists.txt & macos/Package.swift contract:
#   native/third_party/freerdp3/macos/
#     ├── include/freerdp3/
#     ├── include/winpr3/
#     └── lib/{libfreerdp3.dylib,libfreerdp-client3.dylib,libwinpr3.dylib,...}
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${NEXUSTS_FREERDP_MAC_WORK_DIR:-${RUNNER_TEMP:-/tmp}/nexusts-freerdp-macos-work}"
STAGE_ROOT="${REPO_ROOT}/native/third_party/freerdp3/macos"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.4.1}"
FREERDP_VERSION="${FREERDP_VERSION:-3.12.0}"
MACOS_MIN_VERSION="${MACOSX_DEPLOYMENT_TARGET:-14.0}"
ARCHS_CSV="arm64,x86_64"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --archs)
      ARCHS_CSV="${2:?Missing value for --archs}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      cat <<EOF
Usage: ./scripts/build-freerdp3-macos.sh [options]

Options:
  --archs <csv>    Comma-separated macOS architectures (default: arm64,x86_64)
  --dry-run        Validate configuration & print planned build steps without downloading
  -h, --help       Show this help text
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "[Dry Run] NexusTS macOS Universal2 FreeRDP ${FREERDP_VERSION} + OpenSSL ${OPENSSL_VERSION}"
  echo "  Target Archs : ${ARCHS_CSV} (macOS ${MACOS_MIN_VERSION}+)"
  echo "  Stage Root   : ${STAGE_ROOT}/{include,lib}"
  exit 0
fi

export MACOSX_DEPLOYMENT_TARGET="${MACOS_MIN_VERSION}"
mkdir -p "$WORK_DIR/src"

download_and_extract() {
  local url="$1"
  local archive="$WORK_DIR/src/$2"
  local dest_dir="$WORK_DIR/src/$3"
  if [[ ! -d "$dest_dir" ]]; then
    echo "==> Downloading $url"
    curl -fsSL --retry 3 -o "$archive" "$url"
    mkdir -p "$dest_dir"
    tar -xf "$archive" -C "$dest_dir" --strip-components=1
  fi
}

download_and_extract \
  "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
  "openssl-${OPENSSL_VERSION}.tar.gz" \
  "openssl-${OPENSSL_VERSION}"

download_and_extract \
  "https://github.com/FreeRDP/FreeRDP/releases/download/${FREERDP_VERSION}/freerdp-${FREERDP_VERSION}.tar.gz" \
  "freerdp-${FREERDP_VERSION}.tar.gz" \
  "freerdp-${FREERDP_VERSION}"

IFS=',' read -r -a TARGET_ARCHS <<< "$ARCHS_CSV"

CMAKE_GEN_ARGS=()
if command -v ninja >/dev/null 2>&1; then
  CMAKE_GEN_ARGS=(-G Ninja)
fi

for ARCH in "${TARGET_ARCHS[@]}"; do
  ARCH="$(printf '%s' "$ARCH" | tr -d '[:space:]')"
  case "$ARCH" in
    arm64)
      OPENSSL_TARGET="darwin64-arm64-cc"
      ;;
    x86_64)
      OPENSSL_TARGET="darwin64-x86_64-cc"
      ;;
    *)
      echo "ERROR: Unsupported macOS arch '$ARCH' (supported: arm64, x86_64)" >&2
      exit 1
      ;;
  esac

  ARCH_PREFIX="$WORK_DIR/stage/$ARCH"
  ARCH_BUILD="$WORK_DIR/build/$ARCH"
  rm -rf "$ARCH_PREFIX" "$ARCH_BUILD"
  mkdir -p "$ARCH_PREFIX/lib" "$ARCH_PREFIX/include" "$ARCH_BUILD"

  echo "======================================================================"
  echo "==> [1/2] Building OpenSSL ${OPENSSL_VERSION} (${ARCH}, macOS ${MACOS_MIN_VERSION})"
  echo "======================================================================"
  cp -a "$WORK_DIR/src/openssl-${OPENSSL_VERSION}" "$ARCH_BUILD/openssl"
  (
    cd "$ARCH_BUILD/openssl"
    ./Configure "$OPENSSL_TARGET" \
      "-mmacosx-version-min=${MACOS_MIN_VERSION}" \
      -fPIC \
      no-shared \
      no-apps \
      no-tests \
      no-docs \
      --prefix="$ARCH_PREFIX" \
      --libdir=lib \
      --openssldir="$ARCH_PREFIX/ssl"
    make -j"$JOBS"
    make install_sw
  )

  echo "======================================================================"
  echo "==> [2/2] Building FreeRDP ${FREERDP_VERSION} (${ARCH}, @rpath Universal Slice)"
  echo "======================================================================"
  PKG_CONFIG_PATH="" \
  PKG_CONFIG_LIBDIR="$ARCH_PREFIX/lib/pkgconfig" \
  cmake -S "$WORK_DIR/src/freerdp-${FREERDP_VERSION}" \
    -B "$ARCH_BUILD/freerdp" \
    ${CMAKE_GEN_ARGS[@]+"${CMAKE_GEN_ARGS[@]}"} \
    -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="${MACOS_MIN_VERSION}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$ARCH_PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_PREFIX_PATH="$ARCH_PREFIX" \
    -DCMAKE_IGNORE_PREFIX_PATH="/opt/homebrew;/usr/local" \
    -DCMAKE_INSTALL_NAME_DIR="@rpath" \
    -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON \
    -DUSE_VERSION_FROM_GIT_TAG=OFF \
    -DWITH_LIBRARY_VERSIONING=ON \
    -DWITH_INTERNAL_RC4=ON \
    -DWITH_INTERNAL_MD4=ON \
    -DWITH_INTERNAL_MD5=ON \
    -DOPENSSL_ROOT_DIR="$ARCH_PREFIX" \
    -DOPENSSL_INCLUDE_DIR="$ARCH_PREFIX/include" \
    -DOPENSSL_CRYPTO_LIBRARY="$ARCH_PREFIX/lib/libcrypto.a" \
    -DOPENSSL_SSL_LIBRARY="$ARCH_PREFIX/lib/libssl.a" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILTIN_CHANNELS=ON \
    -DBUILD_TESTING=OFF \
    -DWITH_UNICODE_BUILTIN=ON \
    -DWITH_CLIENT=ON \
    -DWITH_CLIENT_COMMON=ON \
    -DWITH_CLIENT_SDL=OFF \
    -DWITH_CLIENT_MAC=OFF \
    -DWITH_MACAUDIO=OFF \
    -DWITH_SERVER=OFF \
    -DWITH_SAMPLE=OFF \
    -DWITH_SHADOW=OFF \
    -DWITH_PROXY=OFF \
    -DWITH_PLATFORM_SERVER=OFF \
    -DWITH_MANPAGES=OFF \
    -DWITH_X11=OFF \
    -DWITH_WAYLAND=OFF \
    -DWITH_CUPS=OFF \
    -DWITH_PCSC=OFF \
    -DWITH_FUSE=OFF \
    -DWITH_KRB5=OFF \
    -DWITH_PKCS11=OFF \
    -DWITH_AAD=OFF \
    -DWITH_CJSON_REQUIRED=OFF \
    -DWITH_WINPR_JSON=OFF \
    -DWITH_URIPARSER=OFF \
    -DWITH_ZSTD=OFF \
    -DWITH_JPEG=OFF \
    -DWITH_WEBP=OFF \
    -DWITH_CAIRO=OFF \
    -DWITH_OPENH264=OFF \
    -DWITH_FFMPEG=OFF \
    -DWITH_VIDEO_FFMPEG=OFF \
    -DWITH_DSP_FFMPEG=OFF \
    -DWITH_SWSCALE=OFF \
    -DWITH_OPUS=OFF \
    -DWITH_LAME=OFF \
    -DWITH_FAAD2=OFF \
    -DWITH_FAAC=OFF \
    -DWITH_SOXR=OFF \
    -DWITH_GSM=OFF \
    -DWITH_WEBVIEW=OFF \
    -DWITH_CHANNELS=ON \
    -DCHANNEL_URBDRC=OFF \
    -DCHANNEL_DISP=ON \
    -DCHANNEL_CLIPRDR=ON \
    -DCHANNEL_RDPDR=ON \
    -DCHANNEL_RDPSND=ON \
    -DCHANNEL_AUDIN=ON \
    -DCHANNEL_GFXREDIR=ON

  cmake --build "$ARCH_BUILD/freerdp" --parallel "$JOBS"
  cmake --install "$ARCH_BUILD/freerdp"
done

echo "======================================================================"
echo "==> Merging FreeRDP ${FREERDP_VERSION} into Universal2 Stage: ${STAGE_ROOT}"
echo "======================================================================"
rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE_ROOT/include" "$STAGE_ROOT/lib"

PRIMARY_ARCH="${TARGET_ARCHS[0]}"
cp -a "$WORK_DIR/stage/$PRIMARY_ARCH/include/." "$STAGE_ROOT/include/"

for item in "$WORK_DIR/stage/$PRIMARY_ARCH/lib/"*.dylib; do
  [[ -e "$item" || -L "$item" ]] || continue
  base="$(basename "$item")"
  if [[ -L "$item" ]]; then
    ln -sf "$(readlink "$item")" "$STAGE_ROOT/lib/$base"
  else
    LIPO_INPUTS=()
    for ARCH in "${TARGET_ARCHS[@]}"; do
      ARCH="$(printf '%s' "$ARCH" | tr -d '[:space:]')"
      LIPO_INPUTS+=("$WORK_DIR/stage/$ARCH/lib/$base")
    done
    if [[ "${#LIPO_INPUTS[@]}" -gt 1 ]]; then
      lipo -create -output "$STAGE_ROOT/lib/$base" "${LIPO_INPUTS[@]}"
    else
      cp -f "${LIPO_INPUTS[0]}" "$STAGE_ROOT/lib/$base"
    fi
    chmod 755 "$STAGE_ROOT/lib/$base"
    strip -x "$STAGE_ROOT/lib/$base" || true
  fi
done

for required_dylib in libfreerdp3.dylib libfreerdp-client3.dylib libwinpr3.dylib; do
  if [[ ! -e "$STAGE_ROOT/lib/$required_dylib" ]]; then
    echo "ERROR: Expected $STAGE_ROOT/lib/$required_dylib was not staged!" >&2
    exit 1
  fi
  echo "  Verified $(basename "$required_dylib"): $(lipo -archs "$STAGE_ROOT/lib/$required_dylib")"
  otool -L "$STAGE_ROOT/lib/$required_dylib"
  if otool -L "$STAGE_ROOT/lib/$required_dylib" | tail -n +2 | grep -E '(/opt/homebrew|/usr/local)' >/dev/null 2>&1; then
    echo "ERROR: Non-portable Homebrew/local dylib dependency leaked into $required_dylib!" >&2
    exit 1
  fi
done

rm -rf "$WORK_DIR/build"
echo "SUCCESS: Staged Universal2 FreeRDP ${FREERDP_VERSION} -> $STAGE_ROOT"
