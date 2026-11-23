<div align="center">

<img src="docs/icons/icon-192.png" alt="NexusTS Logo" width="96" height="96" />

# NexusTS `v1.0.0`

**Desktop-Class Remote Management, FreeRDP 3.12, SSHv2, SFTP & Zero-Knowledge `.nxts` Connection Workspace**  
*Native for **Googlebook OS, ChromeOS (Managed Play) & Android**, and **macOS Universal2 (Apple Silicon + Intel)***

[![Release v1.0.0](https://img.shields.io/badge/release-v1.0.0-1d4ed8?style=flat-square)](https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.0.0)
[![Platforms](https://img.shields.io/badge/platforms-Googlebook%20%7C%20ChromeOS%20%7C%20Android%20%7C%20macOS-059669?style=flat-square)](https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.0.0)
[![Vault Crypto](https://img.shields.io/badge/vault-Argon2id%20%2B%20AES--256--GCM-7c3aed?style=flat-square)](#zero-knowledge-nxts-v2-vault--live-cloud-sync)
[![ChromeOS Managed Play](https://img.shields.io/badge/ChromeOS-Workspace%20Managed%20Play-0284c7?style=flat-square)](docs/WORKSPACE_ADMIN_AUTO_UPDATE.md)

[**Download Latest Release (`v1.0.0`)**](https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.0.0) · [**ChromeOS Managed Play Guide**](docs/WORKSPACE_ADMIN_AUTO_UPDATE.md) · [**Production Deployment Guide**](docs/PRODUCTION_DEPLOYMENT.md)

</div>

---

## Why NexusTS Exists

Existing Android-based RDP and remote desktop managers were designed around phone touchscreens—not real desktop keyboards, multi-monitor docking stations, or large enterprise server fleets. On **Googlebooks**, **Chromebooks**, **Android desktop windowing**, and **tablets with trackpads**, traditional Android RDP clients fall short:

- Fixed resolutions instead of live **`DISP_DVC` dynamic window-drag resizing** and **HiDPI scaling**.
- Broken or non-existent **bidirectional file & folder copy/paste** with the system `Files` app (*"File operation is not supported"*).
- Swallowed hardware keyboard shortcuts (`Win`, `Alt+Tab`, `F1–F12`, `Ctrl+V`) and laggy right-click context menus.
- Isolated single-connection screens with **no split-pane tiling**, **no cascading folder credential inheritance**, **no Royal TS `.rtsz` compatibility**, and **no zero-knowledge cross-platform vault sync**.

**NexusTS `1.0.0`** solves this from the ground up: a unified, light-first desktop remote management workspace inspired by **Royal TS**, powered by **C++20 FreeRDP 3.12** and **Rust (`russh`, `argon2`, `aes-gcm`)**, sharing a single byte-identical encrypted `.nxts` v2 vault across **Googlebook OS / ChromeOS / Android** and **macOS**.

---

## High-Resolution Platform Screenshots (`3200×2000` Retina)

### 1. Googlebook OS, ChromeOS & Android (`com.nexusts.app` — Native FreeRDP 3.12 + `russh` Split Workspace)
*Freeform desktop windowing, side-by-side FreeRDP 3.12 (`AVC444`, `/admin`, `Auto-Fit DISP`, `HiDPI 200%`), bidirectional `DocumentsProvider` file & recursive folder copy/paste with `Files`, `russh` SSHv2 + Dual-Pane SFTP v3 Commander, and multi-monitor tab tear-off.*

![NexusTS on Googlebook OS, ChromeOS & Android](docs/screenshots/nexusts-googlebook-android-rdp-split.png)

### 2. macOS Universal2 (`com.nexusts.app.macos` — Apple Silicon & Intel, macOS 14+)
*Unified workspace UI with native macOS Sonoma/Sequoia window chrome, automatic Touch ID hardware sensor detection, Vault Overview Dashboard, `⌘K` Quick Connect, and `⌥⌘I` Cascading Folder Inheritance Inspector.*

![NexusTS on macOS Universal2](docs/screenshots/nexusts-macos-universal-workspace.png)

### 3. Unified 8-Tab Settings, Zero-Knowledge Google Drive Sync & 1-Click Migration Studio
*Zero-prompt passwordless or Master-Passphrase Argon2id + AES-256-GCM live Google Drive sync, 1-click non-destructive import from Royal TS (`.rtsz`/`.rtsx`), KeePass XML, Bitwarden, 1Password (`.1pux`), and CSV, localized across 6 languages (`EN`, `DE`, `IT`, `ES`, `EL`, `FR`).*

![NexusTS Unified Settings, Cloud Sync & Importers](docs/screenshots/nexusts-settings-sync-importers.png)

---

## Feature Matrix (`v1.0.0`)

| Capability | Googlebook OS, ChromeOS & Android (`APK` / `AAB`) | macOS Universal2 (`DMG` / `ZIP`) |
| :--- | :---: | :---: |
| **Unified Light-First Workspace & Vault Overview** | Yes (Jetpack Compose M3 Expressive) | Yes (SwiftUI + AppKit) |
| **Recursive Split-Pane Tiling & Tab Tear-Off** | Left/Right, Top/Bottom, External Display Tear-Off | `HSplitView`/`VSplitView` + Detached Windows |
| **RDP Engine** | FreeRDP 3.12 C++20 NDK (`AVC444`, `DISP_DVC`) | FreeRDP 3.12 Universal2 (`AVC444`, `DISP_DVC`) |
| **Bidirectional RDP Clipboard** | Files, Nested Folders (`DocumentsProvider`) & Text | Files, Folders (`NSPasteboard`) & Text |
| **Hyper-V Enhanced Session (`VMConnect` 2179)** | Yes (Preconnection Blob PCB + Auto-Heal) | Yes (Preconnection Blob PCB + Auto-Heal) |
| **SSHv2, Bastion ProxyJump & Port Forwarding** | Rust `russh` (`-L`, `-R`, `-D` SOCKS5, `.cast`) | Rust `russh` + Local `/bin/zsh` PTY |
| **VNC / Apple Remote Desktop & Web Sessions** | RFB / ZRLE + Isolated Web Sessions | RFB + 1-Click `Screen Sharing.app` + `WKWebView` |
| **Hardware USB-C Serial UART & YubiKey PIV** | `UsbManager` UART (`9600–921600`) + CCID APDU | IOKit `/dev/cu.*` + Smartcard Redirection |
| **Zero-Knowledge `.nxts` v2 Live Cloud Sync** | Google Drive SAF + Drive REST API v3 | Google Drive + iCloud Drive |
| **Automatic Biometric Unlock** | Biometric / Fingerprint Auto-Prompt | Automatic Touch ID & Apple Watch Detection |
| **Royal TS, KeePass, Bitwarden & 1Password Import** | `.rtsz`, `.rtsx`, `.xml`, `.json`, `.1pux`, `.csv` | `.rtsz`, `.rtsx`, `.xml`, `.json`, `.1pux`, `.csv` |

---

## Downloads & Installation (`v1.0.0`)

All signed release artifacts and `SHA256SUMS` manifests are published on [**GitHub Releases (`v1.0.0`)**](https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.0.0):

1. **Googlebook OS & Android (`arm64-v8a` + `x86_64`, 16 KB ELF Page-Aligned)**:
   - Download `NexusTS-v1.0.0-googlebook-android-release.apk` (signed with APK Signature Scheme v2/v3/v4, RSA-4096, Play-Protect compliant with built-in GitHub & Play updater).
2. **ChromeOS (Google Workspace Managed Play — Zero-Flag Taskbar Pinning & Auto-Update)**:
   - Upload `NexusTS-v1.0.0-googlebook-android-release.apk` as a private Android app in **Google Workspace Admin Console (`admin.google.com`)**, set installation policy to **Force install + pin to ChromeOS taskbar**, and optionally link GitHub Actions for zero-click tag updates — see [**Workspace Managed Play Guide**](docs/WORKSPACE_ADMIN_AUTO_UPDATE.md).
3. **macOS 14 Sonoma / macOS 15 Sequoia (`arm64` + `x86_64` Universal2)**:
   - Download `NexusTS-v1.0.0-macOS-Universal.dmg` (or `.zip`) and drag **NexusTS.app** to `/Applications`.

---

## Zero-Knowledge `.nxts` v2 Vault & Live Cloud Sync

Every platform reads and writes the exact same binary **`.nxts` v2 (`NXTS\x00\x02`)** container:

- **Authenticated Cryptography:** Key derivation via **RFC 9106 Argon2id** (`64 MiB, t=3, p=4`), subkey expansion via **HKDF-SHA256**, and payload encryption via **AES-256-GCM** with header Additional Authenticated Data (AAD).
- **Frictionless or Master-Passphrase Modes:** Sync across Google Drive out of the box with **zero master-password prompts** using NexusTS's transparent authenticated envelope, or lock your vault behind a custom Master Passphrase with **automatic Touch ID / Fingerprint hardware detection**.
- **Conflict-Free Multi-Device Sync:** Concurrent edits on a Mac, a Googlebook, and a Chromebook are merged deterministically at the object level using **3-way vector-clock delta resolution** (`VaultDeltaConflictResolver`).

---

## Repository Architecture

```text
NexusTS/
├── app/                             # Android, Googlebook OS & ChromeOS MainActivity, DetachedSessionActivity, E2E Suite
├── core/
│   ├── ui/                          # Material 3 Expressive Light-First Theme, BSP Split Tiling, Desktop Shortcut Engine
│   ├── vault/                       # .nxts v2 Crypto, Cascading Folder Inheritance, .rtsz/KeePass/Bitwarden/1Password Importers
│   └── gdrive-sync/                 # Persistent SAF & Google Drive REST v3 Live Sync + 3-Way Vector-Clock Merge
├── protocols/
│   ├── rdp/                         # FreeRDP 3.12 TextureView, DISP_DVC Resize, NexusRdpClipboardDocumentsProvider, CCID Bridge
│   ├── ssh/                         # xterm-256color Terminal, Port-Forwarding Studio, Dual-Pane SFTP v3, USB-C Serial UART
│   └── vnc/                         # RFB/ZRLE VNC, Kubernetes Pod & Docker Exec/Logs, Isolated Web Sessions, NetUtils
├── feature/workspace/               # Unified Desktop Workspace, Overview Dashboard, Sidebar Tree, 8-Tab Settings, 6-Locale i18n
├── native/
│   ├── cpp/                         # C++20 FreeRDP 3.12 Session, RDPGFX/AVC444, CLIPRDR File Streams, DISP_DVC, AAudio/CoreAudio
│   └── rust/                        # Rust Workspace (nexusts-crypto, nexusts-terminal, nexusts-transfer, nexusts-vnc, nexusts-netutils)
├── macos/                           # Native Swift + SwiftUI/AppKit macOS 14+ App (Universal2 arm64 + x86_64)
└── docs/screenshots/                # High-Resolution 3200x2000 Retina Platform Screenshots
```

---

## Build & Test Quickstart

### Googlebook OS, ChromeOS & Android
```bash
cargo test --manifest-path native/rust/Cargo.toml
./gradlew testDebugUnitTest assembleRelease bundleRelease
```

### macOS Universal2 (`arm64` + `x86_64`)
```bash
./scripts/build-macos-native.sh --universal
swift test --package-path macos
./scripts/package-macos-app.sh --universal
```
