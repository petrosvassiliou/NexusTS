# NexusTS (`com.nexusts.app`)

**NexusTS** is an enterprise-grade Remote Management, Terminal, File Transfer, and Connection Vault application modeled after **Royal TS for macOS**, architected natively for **Googlebook OS** (Android Desktop Windowing, Freeform Multi-Window, Multi-Monitor) and **Android Tablets & Foldables**.

---

## Architecture Overview

```text
NexusTS/
├── build-logic/                     # Custom Gradle Convention Plugins (Compose, Android Lib, Cargo-NDK 16KB Page Align)
├── gradle/libs.versions.toml        # Centralized Version Catalog
├── native/
│   ├── CMakeLists.txt               # C++20 FreeRDP 3.x + AMediaCodec H.264/AVC444 + AAudio + 16KB ELF Page Alignment
│   ├── cpp/                         # FreeRDP 3.x Session, DISP_DVC Dynamic Resize, GDI Surface, Virtual Channels, JNI
│   └── rust/                        # Cargo Workspace (nexusts-crypto, nexusts-terminal, nexusts-transfer, nexusts-vnc, nexusts-netutils, nexusts-jni)
├── core/
│   ├── ui/                          # Material 3 Expressive Theme, Spring Physics, Morphing Shapes, BSP Tiling, Shortcut Engine
│   ├── vault/                       # Zero-Knowledge .nxts (Argon2id + AES-256-GCM), .rtsz Importer, Inheritance, KeePass/1P/Bitwarden/FIDO2
│   └── gdrive-sync/                 # Google Drive API v3 (appDataFolder & Team Drives), SQLCipher Cache, Vector-Clock Delta Sync
├── protocols/
│   ├── rdp/                         # FreeRDP 3.x Compose SurfaceView, DISP_DVC Window Drag Resize, USB CCID YubiKey APDU Bridge
│   ├── ssh/                         # xterm-256color/TrueColor Terminal Canvas, SOCKS5/Local Port Forwards, Dual-Pane SFTP/FTPS/SCP Commander
│   └── vnc/                         # UltraVNC/TightVNC/ARD ZRLE Viewport, Isolated WebView (Auto-Fill & Cert Pin), Cloud Hypervisors, NetUtils
├── feature/
│   └── workspace/                   # Adaptive Desktop Workspace, Tree Sidebar, Property Inspector, Broadcast Drawer, Per-App i18n
└── app/                             # com.nexusts.app MainActivity, DetachedSessionActivity, Play Protect Updater, 6-Language Bundles
```

## Key Technical Highlights

1. **Googlebook OS Native Desktop Windowing**:
   - Full `PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI`, freeform `<layout>` geometry, external monitor discovery via `DisplayManager`, and tab tear-off (`DetachedSessionActivity` with `ActivityOptions.setLaunchDisplayId()`).
   - Zero legacy ChromeOS ARC++ or Crostini container assumptions.
2. **Material Design 3 Expressive**:
   - Quantum Cobalt & Cyber Mint brand palette + Dynamic Color + High-Contrast mode.
   - Distinct Spatial vs Effects spring physics (`NexusMotionScheme`) and morphing container/tab geometry (`rememberMorphingContainerShape`).
3. **Zero-Knowledge `.nxts` Vault & `.rtsz` Compatibility**:
   - Binary `.nxts` v2 envelope (`NXTS\x00\x02`) sealed with **Argon2id** (`64 MiB, t=3, p=4`), **HKDF-SHA256**, and **AES-256-GCM** with authenticated header AAD.
   - Bidirectional Royal TS `.rtsz` / `.rtsx` import & export, RFC 4180 `.csv` inventory export, Connection Templates, Smart Folders, Dynamic Folders, RFC 6238 TOTP (`$EffectiveTOTP$`), Custom Properties (`$CustomProperty.*$`), and cascading Property Inheritance.
4. **Power-User SSH & RDP Engines**:
   - **RDP (`FreeRDP 3.x` NDK)**: Folder-level credential inheritance, `/admin` Console session, HiDPI scaling (`100%–200%`), `DISP_DVC` dynamic resize, H.264/AVC444 `AMediaCodec`, bidirectional clipboard, 3-mode audio, drives, printers, serial ports, and USB CCID YubiKey PIV redirection.
   - **SSH (`russh` / NDK)**: Visual `-L` Local, `-R` Remote, and `-D` Dynamic SOCKS5 port-forwarding studio, ProxyJump Bastion host chaining, X11/Agent forwarding, and **asciicast v2 (`.cast`)** session recording & variable-speed playback.
5. **Google Play Protect Compliant Auto-Updates**:
   - Uses official Google Play In-App Updates (`AppUpdateManager` Immediate & Flexible flows) and Managed Google Play `RestrictionsManager` + APK Signature Scheme v3/v4 verified `PackageInstaller` sessions. Zero `REQUEST_INSTALL_PACKAGES` sideloading or untrusted `DexClassLoader` usage.
6. **6 Built-in Languages (Zero Hardcoded UI Strings)**:
   - English (`en`), German (`de`), Italian (`it`), Spanish (`es`), Greek (`el`), and French (`fr`) wired to Android 13+ Per-App Language Preferences (`locales_config.xml` + `AppCompatDelegate.setApplicationLocales`).

## Build, NDK Cross-Compilation & Release Quickstart

See [`docs/PRODUCTION_DEPLOYMENT.md`](docs/PRODUCTION_DEPLOYMENT.md) for the full runbook:

```bash
# 1. Generate 4096-bit PKCS#12 release keystore + Google Cloud OAuth 2.0 & MDM config
./scripts/provision-signing-and-oauth.sh .signing/nexusts-release.p12 nexusts-upload

# 2. (Optional) Cross-compile OpenSSL 3.4 + libusb 1.0 + FreeRDP 3.12 for NDK r28 (16 KB page aligned)
./scripts/build-freerdp3-ndk.sh --abis arm64-v8a,x86_64

# 3. Run Rust Cargo tests & Gradle unit tests + build signed Release .aab/.apk
cargo test --manifest-path native/rust/Cargo.toml
./gradlew testDebugUnitTest assembleDebug bundleRelease
```

