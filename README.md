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
│   ├── vault/                       # Zero-Knowledge .nxts (Argon2id + AES-256-GCM), .rtsz/KeePass/Bitwarden/1Password/CSV Importers
│   └── gdrive-sync/                 # Dual-Engine Live Google Drive Sync (Persistent SAF + Drive API v3 OAuth 2.0), 3-Way Delta Merge
├── protocols/
│   ├── rdp/                         # FreeRDP 3.x Compose SurfaceView, DISP_DVC Window Drag Resize, USB CCID YubiKey APDU Bridge
│   ├── ssh/                         # xterm-256color/TrueColor Terminal, Port Forwards, SFTP Commander, USB-C Hardware Serial UART
│   └── vnc/                         # UltraVNC/ARD ZRLE, Kubernetes Pod & Docker Container Exec/Log Terminals, WebView, NetUtils
├── feature/
│   └── workspace/                   # Adaptive Desktop Workspace, Tree Sidebar, Property Inspector, Broadcast Drawer, Per-App i18n
└── app/                             # com.nexusts.app MainActivity, DetachedSessionActivity, GitHub/Play Updater, Compose E2E Suite
```

## Key Technical Highlights (`v1.1.0`)

1. **Googlebook OS Native Desktop Windowing**:
   - Full `PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI`, freeform `<layout>` geometry, external monitor discovery via `DisplayManager`, and tab tear-off (`DetachedSessionActivity` with `ActivityOptions.setLaunchDisplayId()`).
   - Zero legacy ChromeOS ARC++ or Crostini container assumptions.
2. **Material Design 3 Expressive**:
   - Quantum Cobalt & Cyber Mint brand palette + Dynamic Color + High-Contrast mode.
   - Distinct Spatial vs Effects spring physics (`NexusMotionScheme`) and morphing container/tab geometry (`rememberMorphingContainerShape`).
3. **Dual-Engine Live Google Drive Sync & Zero-Knowledge `.nxts` Vaults**:
   - Binary `.nxts` v2 envelope (`NXTS\x00\x02`) sealed with **Argon2id** (`64 MiB, t=3, p=4`), **HKDF-SHA256**, and **AES-256-GCM** with authenticated header AAD.
   - **Zero-Setup Persistent SAF Engine (`SafLiveGoogleDriveBridge`)**: Links directly to Google Drive (`NexusTS Vaults`) via `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION` with automatic on-save encryption & 3-way `VaultDeltaConflictResolver` vector-clock merge — zero Cloud Console setup needed for direct-installed APKs.
   - **Google Drive REST API v3 Engine (`GoogleDriveVaultSyncEngine`)**: Play Services `AuthorizationClient` OAuth 2.0 consent (`drive.appdata` + `drive.file`), Shared Team Drive advisory locks, SQLCipher offline cache, and `WorkManager` periodic delta sync (`./scripts/setup-gdrive-oauth.sh`).
4. **Multi-Format Enterprise Importer & Cascading Inheritance**:
   - Bidirectional Royal TS `.rtsz` / `.rtsx` import & export, plus native import of **KeePass 2.x XML**, **Bitwarden JSON**, **1Password `.1pux` / JSON**, and **RFC 4180 CSV** inventories (`EnterpriseVaultFormatImporter`).
   - Folder-level credential inheritance, Connection Templates, Smart Folders, Dynamic Folders, RFC 6238 TOTP (`$EffectiveTOTP$`), and Custom Properties (`$CustomProperty.*$`).
5. **Enterprise Protocols (RDP, SSH, USB-C Serial UART, Kubernetes & Docker)**:
   - **RDP (`FreeRDP 3.x` NDK)**: `/admin` Console session, HiDPI scaling (`100%–200%`), `DISP_DVC` dynamic resize, H.264/AVC444 `AMediaCodec`, bidirectional clipboard, 3-mode audio, drives, printers, serial ports, and USB CCID YubiKey PIV redirection.
   - **SSH & Out-of-Band Serial (`russh` / `UsbManager`)**: Visual `-L`/`-R`/`-D` SOCKS5 port-forwarding studio, ProxyJump Bastion chaining, asciicast v2 (`.cast`) recording, and **USB-C Hardware Serial / UART Console** (`FTDI`, `CP210x`, `PL2303`, `CH340`, `CDC-ACM`; `9600–921600 8N1`, `DTR`/`RTS`, `Send BREAK`).
   - **Cloud-Native Workloads**: Interactive **Kubernetes Pod** & **Docker Container** Exec Shell, Live Log Streamer, and Workload Discovery (`k8s://`, `kubectl://`, `docker://`, `serial://` Quick Connect URIs).
6. **Play-Protect-Compliant Distribution & Automated Compose UI E2E Suite**:
   - Direct-install **GitHub Releases Update Checker** (`GitHubReleaseUpdateChecker`) + Google Play `AppUpdateManager` / Managed Play `PackageInstaller` with **zero** `REQUEST_INSTALL_PACKAGES` permissions and **zero** untrusted dynamic code loading.
   - Headless Robolectric Compose UI E2E tests (`NexusWorkspaceComposeE2ETest`, `SafLiveDriveSyncTest`, `EnterpriseVaultImporterTest`) + Android Instrumentation (`NexusWorkspaceInstrumentedTest`), plus 100% parity across 6 languages (**EN, DE, IT, ES, EL, FR**).

## Build, NDK Cross-Compilation & Release Quickstart

See [`docs/PRODUCTION_DEPLOYMENT.md`](docs/PRODUCTION_DEPLOYMENT.md) for the full runbook:

```bash
# 1. Generate 4096-bit PKCS#12 release keystore + Google Cloud OAuth 2.0 & MDM config
./scripts/provision-signing-and-oauth.sh .signing/nexusts-release.p12 nexusts-upload
./scripts/setup-gdrive-oauth.sh .signing/nexusts-release.p12 nexusts-upload

# 2. (Optional) Cross-compile OpenSSL 3.4 + libusb 1.0 + FreeRDP 3.12 for NDK r28 (16 KB page aligned)
./scripts/build-freerdp3-ndk.sh --abis arm64-v8a,x86_64

# 3. Run Rust Cargo tests, Headless Compose/Robolectric E2E tests, & build signed Release .apk/.aab
cargo test --manifest-path native/rust/Cargo.toml
./gradlew testDebugUnitTest assembleDebugAndroidTest assembleRelease bundleRelease
```

---

## Native macOS Edition (`macos/` — SwiftUI & AppKit, macOS 14 Sonoma+)

On the `macos` branch, **NexusTS for Mac** (`com.nexusts.app.macos`) delivers a 100% native Swift + SwiftUI / AppKit desktop experience built strictly to modern **Apple Human Interface Guidelines (HIG)** while sharing the exact same Rust & C++20 engines and binary-compatible `.nxts` v2 vaults:

- **Modern Apple HIG Workspace**:
  - Three-column `NavigationSplitView` with vibrancy sidebar (`.listStyle(.sidebar)`), recursive BSP split-pane workspace (`HSplitView` / `VSplitView`), Safari/Xcode-style tab strips with lossless multi-window tear-off & re-dock (`WindowGroup("Detached Session")`), and trailing property inspector (`.inspector` `⌥⌘I`).
  - Unified macOS window toolbar (`.windowToolbarStyle(.unified)`), Spotlight-style `⌘K` Quick Connect overlay, native `Settings` window (`⌘,`), `MenuBarExtra` status bar quick-connect agent, full macOS menu bar `Commands`, and `CoreSpotlight` system-wide connection indexing.
- **Native Apple Security & Cloud Sync**:
  - **Touch ID & Apple Watch** (`LocalAuthentication`) + macOS Keychain integration for zero-knowledge Argon2id + AES-256-GCM `.nxts` v2 vault unlock.
  - Live 3-way vector-clock delta sync (`VaultDeltaConflictResolver`) across **iCloud Drive**, **Google Drive for Desktop** (security-scoped bookmarks), and **Google Drive REST API v3** — 100% interoperable with NexusTS on Android & Googlebook OS.
- **Full Protocol & Studio Parity**:
  - Local macOS `/bin/zsh` login shells (`forkpty`), `russh` SSHv2 + Bastion ProxyJump + `-L`/`-D` SOCKS5 tunnels + Asciicast `.cast` recorder, Dual-Pane Finder/SFTP v3 Commander, FreeRDP 3.x (`DISP_DVC` Retina dynamic resize), VNC & 1-click native macOS `Screen Sharing.app` (`ARD`) handoff, IOKit `/dev/cu.*` hardware USB-Serial UART (`IOSSIOSPEED`), isolated `WKWebView` (`WKWebsiteDataStore(forIdentifier:)`), Kubernetes/Docker (`/var/run/docker.sock`), Cloud Hypervisors (Proxmox, vSphere, AWS EC2 SigV4, Azure), and 6-tool Network Diagnostics.

### macOS Build, Test & Universal2 DMG Packaging

```bash
# 1. Build Universal2 (arm64 + x86_64) Rust FFI + C++20 FreeRDP static libraries & run XCTests
./scripts/build-macos-native.sh --universal
swift test --package-path macos

# 2. Package Universal2 NexusTS.app + NexusTS-macOS-Universal.dmg
./scripts/package-macos-app.sh --universal
```

---

## ChromeOS Offline PWA & Isolated Web App Edition (`chromeos-pwa/` — `.swbn` & Offline Service Worker)

**NexusTS for ChromeOS** (`com.nexusts.app.chromeos`) delivers 1:1 feature parity and 100% offline operation from a single Dual-Build codebase:

- **Signed Isolated Web App (`NexusTS-v*-ChromeOS-IWA.swbn`)**:
  - Uses native ChromeOS **`Direct Sockets API` (`TCPSocket` / `UDPSocket`)**, **`Controlled Frame` (`<controlledframe>`)**, and **`Web Serial` (`navigator.serial`)** for 100% standalone offline raw TCP/UDP connections (RDP, SSHv2, SFTP v3, VNC, FTP, Telnet, Wake-on-LAN, TCP Ping & Port Scanner) with **zero companion binary**.
  - Installable via Chrome Enterprise Policy (`IsolatedWebAppInstallForceList` with `NexusTS-v*-ChromeOS-IWA-update-manifest.json`) or on ChromeOS via `chrome://web-app-internals` (*Install IWA from Signed Web Bundle* with `#enable-isolated-web-apps-in-renderer` & `#enable-isolated-web-app-dev-mode`).
- **Standard Offline PWA (`NexusTS-v*-ChromeOS-Offline-PWA.zip` + `nexusts-local-relay`)**:
  - Precaches every HTML, CSS, ES module, icon, and the `nexusts_wasm.wasm` Rust Argon2id + AES-256-GCM & RFB/RDP accelerator via `sw.js` for 100% offline startup on any unmanaged Chromebook, with optional single-binary `nexusts-local-relay` (`x86_64` / `arm64`) inside ChromeOS Linux (Crostini).
- **100% `.nxts` v2 & Google Drive Offline Sync Interoperability**:
  - Shares the exact `NXTS\x00\x02` Argon2id + HKDF-SHA256 + AES-256-GCM wire format and 3-way `VaultDeltaConflictResolver` with macOS and Android, syncing automatically on startup, local edits, and remote changes via persistent ChromeOS `FileSystemFileHandle` in Google Drive ("Meine Ablage" / "My Drive" & Shared Drives, including offline-pinned files).

### ChromeOS Build & Test

```bash
cd chromeos-pwa
npm test
npm run build
```



