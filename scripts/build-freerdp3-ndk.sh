#!/usr/bin/env bash
# ==============================================================================
# NexusTS (com.nexusts.app) — FreeRDP 3.x + OpenSSL 3.4 + libusb NDK Builder
# ==============================================================================
# Cross-compiles OpenSSL 3.4.1, libusb 1.0.27 (CCID Smartcard/YubiKey PIV), and
# FreeRDP 3.12.0 for Googlebook OS & Android Tablets (arm64-v8a, x86_64) with
# mandatory 16 KB ELF page alignment (-Wl,-z,max-page-size=16384).
#
# Staged output matches native/CMakeLists.txt contract:
#   native/third_party/freerdp3/<ABI>/
#     ├── include/freerdp3/
#     ├── include/winpr3/
#     └── lib/{libfreerdp3.so,libfreerdp-client3.so,libwinpr3.so,...}
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${NEXUSTS_FREERDP_WORK_DIR:-${RUNNER_TEMP:-/tmp}/nexusts-freerdp-ndk-work}"
STAGE_ROOT="$REPO_ROOT/native/third_party/freerdp3"
JNILIBS_ROOT="$REPO_ROOT/protocols/rdp/src/main/jniLibs"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.4.1}"
LIBUSB_VERSION="${LIBUSB_VERSION:-1.0.27}"
FREERDP_VERSION="${FREERDP_VERSION:-3.12.0}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-29}"
ABIS_CSV="arm64-v8a,x86_64"
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abis)
      ABIS_CSV="${2:?Missing value for --abis}"
      shift 2
      ;;
    --api)
      ANDROID_API_LEVEL="${2:?Missing value for --api}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      cat <<EOF
Usage: ./scripts/build-freerdp3-ndk.sh [options]

Options:
  --abis <csv>     Comma-separated Android ABIs (default: arm64-v8a,x86_64)
  --api <level>    Android minimum API level (default: 29)
  --dry-run        Validate configuration & print planned build steps without downloading
  -h, --help       Show this help text

Environment:
  ANDROID_NDK_HOME Path to Android NDK r27/r28 (e.g. \$ANDROID_HOME/ndk/28.0.13004108)
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" && -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
  NDK_ROOT="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1 || true)"
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "[Dry Run] NexusTS FreeRDP ${FREERDP_VERSION} + OpenSSL ${OPENSSL_VERSION} + libusb ${LIBUSB_VERSION}"
  echo "  Target ABIs : ${ABIS_CSV} (API ${ANDROID_API_LEVEL}, 16 KB page alignment)"
  echo "  Stage Root  : ${STAGE_ROOT}/<abi>/{include,lib}"
  echo "  NDK Root    : ${NDK_ROOT:-<set ANDROID_NDK_HOME before full build>}"
  exit 0
fi

if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or directory does not exist." >&2
  echo "Expected Android NDK r28 (e.g. export ANDROID_NDK_HOME=\$ANDROID_HOME/ndk/28.0.13004108)." >&2
  exit 1
fi

HOST_TAG=""
for candidate in linux-x86_64 darwin-x86_64 darwin-arm64; do
  if [[ -d "$NDK_ROOT/toolchains/llvm/prebuilt/$candidate" ]]; then
    HOST_TAG="$candidate"
    break
  fi
done
if [[ -z "$HOST_TAG" ]]; then
  echo "ERROR: Could not locate LLVM prebuilt toolchain inside $NDK_ROOT" >&2
  exit 1
fi

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
CMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake"
PAGE_ALIGN_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

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
  "https://github.com/libusb/libusb/releases/download/v${LIBUSB_VERSION}/libusb-${LIBUSB_VERSION}.tar.bz2" \
  "libusb-${LIBUSB_VERSION}.tar.bz2" \
  "libusb-${LIBUSB_VERSION}"

download_and_extract \
  "https://github.com/FreeRDP/FreeRDP/releases/download/${FREERDP_VERSION}/freerdp-${FREERDP_VERSION}.tar.gz" \
  "freerdp-${FREERDP_VERSION}.tar.gz" \
  "freerdp-${FREERDP_VERSION}"

IFS=',' read -r -a TARGET_ABIS <<< "$ABIS_CSV"

for ABI in "${TARGET_ABIS[@]}"; do
  ABI="$(printf '%s' "$ABI" | tr -d '[:space:]')"
  case "$ABI" in
    arm64-v8a)
      OPENSSL_TARGET="android-arm64"
      AUTOCONF_HOST="aarch64-linux-android"
      ;;
    x86_64)
      OPENSSL_TARGET="android-x86_64"
      AUTOCONF_HOST="x86_64-linux-android"
      ;;
    *)
      echo "ERROR: Unsupported ABI '$ABI' (supported: arm64-v8a, x86_64)" >&2
      exit 1
      ;;
  esac

  ABI_PREFIX="$STAGE_ROOT/$ABI"
  ABI_BUILD="$WORK_DIR/build/$ABI"
  mkdir -p "$ABI_PREFIX/lib" "$ABI_PREFIX/include" "$ABI_BUILD" "$JNILIBS_ROOT/$ABI"

  echo "======================================================================"
  echo "==> [1/3] Building OpenSSL ${OPENSSL_VERSION} for ${ABI} (API ${ANDROID_API_LEVEL})"
  echo "======================================================================"
  rm -rf "$ABI_BUILD/openssl"
  cp -a "$WORK_DIR/src/openssl-${OPENSSL_VERSION}" "$ABI_BUILD/openssl"
  (
    cd "$ABI_BUILD/openssl"
    export ANDROID_NDK_ROOT="$NDK_ROOT"
    PATH="$TOOLCHAIN/bin:$PATH" \
      ./Configure "$OPENSSL_TARGET" \
      -D__ANDROID_API__="${ANDROID_API_LEVEL}" \
      -fPIC \
      no-shared \
      no-tests \
      --prefix="$ABI_PREFIX" \
      --libdir=lib \
      --openssldir="$ABI_PREFIX/ssl"
    PATH="$TOOLCHAIN/bin:$PATH" make -j"$JOBS"
    PATH="$TOOLCHAIN/bin:$PATH" make install_sw
  )

  echo "======================================================================"
  echo "==> [2/3] Building libusb ${LIBUSB_VERSION} for ${ABI} (Smartcard/YubiKey CCID)"
  echo "======================================================================"
  rm -rf "$ABI_BUILD/libusb"
  cp -a "$WORK_DIR/src/libusb-${LIBUSB_VERSION}" "$ABI_BUILD/libusb"
  (
    cd "$ABI_BUILD/libusb"
    autoreconf -fi
    CC="$TOOLCHAIN/bin/${AUTOCONF_HOST}${ANDROID_API_LEVEL}-clang" \
    CXX="$TOOLCHAIN/bin/${AUTOCONF_HOST}${ANDROID_API_LEVEL}-clang++" \
    AR="$TOOLCHAIN/bin/llvm-ar" \
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
    STRIP="$TOOLCHAIN/bin/llvm-strip" \
    CFLAGS="-fPIC -O2" \
    LDFLAGS="$PAGE_ALIGN_LDFLAGS" \
      ./configure \
      --host="$AUTOCONF_HOST" \
      --prefix="$ABI_PREFIX" \
      --with-pic \
      --disable-udev \
      --disable-shared \
      --enable-static
    make -j"$JOBS"
    make install
  )

  echo "======================================================================"
  echo "==> [3/3] Building FreeRDP ${FREERDP_VERSION} for ${ABI} (16 KB Page Aligned)"
  echo "======================================================================"
  CMAKE_GEN_ARGS=()
  if command -v ninja >/dev/null 2>&1; then
    CMAKE_GEN_ARGS=(-G Ninja)
  fi
  rm -rf "$ABI_BUILD/freerdp"
  PKG_CONFIG_LIBDIR="$ABI_PREFIX/lib/pkgconfig" \
  cmake -S "$WORK_DIR/src/freerdp-${FREERDP_VERSION}" \
    -B "$ABI_BUILD/freerdp" \
    "${CMAKE_GEN_ARGS[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$CMAKE_TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${ANDROID_API_LEVEL}" \
    -DANDROID_STL="c++_shared" \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$ABI_PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_PREFIX_PATH="$ABI_PREFIX" \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
    -DCMAKE_SHARED_LINKER_FLAGS="$PAGE_ALIGN_LDFLAGS" \
    -DUSE_VERSION_FROM_GIT_TAG=OFF \
    -DWITH_LIBRARY_VERSIONING=ON \
    -DWITH_INTERNAL_RC4=ON \
    -DWITH_INTERNAL_MD4=ON \
    -DWITH_INTERNAL_MD5=ON \
    -DOPENSSL_ROOT_DIR="$ABI_PREFIX" \
    -DOPENSSL_INCLUDE_DIR="$ABI_PREFIX/include" \
    -DOPENSSL_CRYPTO_LIBRARY="$ABI_PREFIX/lib/libcrypto.a" \
    -DOPENSSL_SSL_LIBRARY="$ABI_PREFIX/lib/libssl.a" \
    -DLIBUSB_1_INCLUDE_DIR="$ABI_PREFIX/include/libusb-1.0" \
    -DLIBUSB_1_LIBRARY="$ABI_PREFIX/lib/libusb-1.0.a" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TESTING=OFF \
    -DWITH_UNICODE_BUILTIN=ON \
    -DWITH_CLIENT=ON \
    -DWITH_CLIENT_COMMON=ON \
    -DWITH_CLIENT_SDL=OFF \
    -DWITH_SERVER=OFF \
    -DWITH_SAMPLE=OFF \
    -DWITH_SHADOW=OFF \
    -DWITH_PROXY=OFF \
    -DWITH_PLATFORM_SERVER=OFF \
    -DWITH_MANPAGES=OFF \
    -DWITH_X11=OFF \
    -DWITH_WAYLAND=OFF \
    -DWITH_ALSA=OFF \
    -DWITH_PULSE=OFF \
    -DWITH_OSS=OFF \
    -DWITH_CUPS=OFF \
    -DWITH_PCSC=OFF \
    -DWITH_FUSE=OFF \
    -DWITH_KRB5=OFF \
    -DWITH_PKCS11=OFF \
    -DWITH_AAD=OFF \
    -DWITH_CJSON_REQUIRED=OFF \
    -DWITH_CAIRO=OFF \
    -DWITH_OPENH264=OFF \
    -DWITH_FFMPEG=OFF \
    -DWITH_VIDEO_FFMPEG=OFF \
    -DWITH_DSP_FFMPEG=OFF \
    -DWITH_SWSCALE=OFF \
    -DWITH_VAAPI=OFF \
    -DWITH_WEBVIEW=OFF \
    -DWITH_MEDIACODEC=ON \
    -DWITH_OPENSLES=ON \
    -DWITH_CHANNELS=ON \
    -DCHANNEL_URBDRC=ON \
    -DCHANNEL_URBDRC_CLIENT=ON \
    -DCHANNEL_DISP=ON \
    -DCHANNEL_CLIPRDR=ON \
    -DCHANNEL_RDPDR=ON \
    -DCHANNEL_RDPSND=ON \
    -DCHANNEL_AUDIN=ON \
    -DCHANNEL_GFXREDIR=ON

  cmake --build "$ABI_BUILD/freerdp" --parallel "$JOBS"
  cmake --install "$ABI_BUILD/freerdp"

  for so_file in libfreerdp3.so libfreerdp-client3.so libwinpr3.so; do
    if [[ -f "$ABI_PREFIX/lib/$so_file" ]]; then
      "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$ABI_PREFIX/lib/$so_file" || true
      cp -f "$ABI_PREFIX/lib/$so_file" "$JNILIBS_ROOT/$ABI/$so_file"
    else
      echo "ERROR: Expected shared library $ABI_PREFIX/lib/$so_file was not produced!" >&2
      exit 1
    fi
  done

  echo "==> Staged FreeRDP ${FREERDP_VERSION} (${ABI}) -> $ABI_PREFIX"
done

echo "SUCCESS: All FreeRDP 3.x NDK libraries staged under $STAGE_ROOT"
