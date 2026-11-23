# Reddit Launch Post — NexusTS `v1.0.0`

> **Recommended Subreddits:** `r/selfhosted`, `r/sysadmin`, `r/homelab`, `r/Android`, `r/chromeos`, `r/macapps`  
> **Post Format:** Create an **Image & Video** gallery post on Reddit using the 3 high-resolution (`3200×2000`) PNG screenshots in `docs/screenshots/` (or paste the Markdown body directly into a **Text** post—all GitHub raw screenshot links are pre-wired for `v1.0.0`).

---

## Title Options

1. **(Recommended — `r/selfhosted` / `r/sysadmin` / `r/homelab`):**  
   `I got tired of how bad every Android RDP manager is on desktop/laptop hardware, so I built NexusTS 1.0 — a Royal TS-inspired RDP, SSH, SFTP & Zero-Knowledge Vault workspace for Googlebooks/Android, ChromeOS, and macOS`
2. **(`r/Android` / `r/chromeos`):**  
   `Every Android RDP client felt like a phone app stretched onto a monitor—so I built NexusTS 1.0 (FreeRDP 3.12, dynamic window resize, real Files app copy/paste, Managed Play taskbar pinning & live Google Drive sync)`

---

## High-Resolution Screenshots (`3200×2000` Retina Gallery in `docs/screenshots/`)

1. `docs/screenshots/nexusts-googlebook-android-rdp-split.png` — Googlebook OS, ChromeOS & Android Split-Pane FreeRDP 3.12 + `russh` SSHv2/SFTP
2. `docs/screenshots/nexusts-macos-universal-workspace.png` — macOS Universal2 (Apple Silicon + Intel) Unified Workspace & Cascading Folder Inheritance Inspector
3. `docs/screenshots/nexusts-settings-sync-importers.png` — Unified 8-Tab Settings, Zero-Knowledge Argon2id + AES-256-GCM Google Drive Sync & Royal TS / Bitwarden / 1Password Importers

---

## Copy-Paste Reddit Post Body (Markdown)

```markdown
Hey everyone,

Today I’m releasing **NexusTS v1.0.0** — a desktop-class Remote Management, RDP, SSH, SFTP, VNC, and Zero-Knowledge Connection Vault workspace built natively for **Googlebooks / Android**, **ChromeOS (Workspace Managed Play)**, and **macOS (Universal2)**.

### Why I built this: Android RDP managers just aren't good enough

On macOS, tools like Royal TS make managing dozens or hundreds of Windows & Linux servers effortless. When I moved part of my daily sysadmin workflow onto a Googlebook / Android desktop & Chromebook setup, **every single existing Android RDP client fell apart in real-world use**:

* **Stuck in a phone mindset:** Clunky floating touch overlays covering remote windows, awkward panning instead of real desktop mouse/trackpad precision, and fixed resolutions instead of live dynamic resizing when you drag a window border.
* **Broken file & folder copy/paste:** Copying a file or folder from an RDP session and pressing `Ctrl+V` in the local `Files` app throws *"File operation is not supported"*, and copying local files into Windows Explorer does nothing.
* **Broken keyboard & mouse workflows:** Physical `Win`, `Alt`, `F1–F12`, and `Ctrl+V` shortcuts get swallowed, and right-clicking in Windows Explorer misses newly copied host clipboard items.
* **Zero fleet management:** No split-pane RDP + SSH tiling, no multi-monitor tab tear-off, no cascading folder credential inheritance, no `.rtsz` import, and no encrypted cross-platform vault sync with macOS.

So I built **NexusTS** from the ground up with a shared **C++20 FreeRDP 3.12** + **Rust (`russh`, `argon2`, `aes-gcm`)** core and **1:1 UI & vault parity** across all platforms.

---

### High-Resolution Screenshots (3200×2000)

1. **Googlebook OS, ChromeOS & Android (Split-Pane FreeRDP 3.12 + SSHv2/SFTP):** https://raw.githubusercontent.com/petrosvassiliou/NexusTS/v1.0.0/docs/screenshots/nexusts-googlebook-android-rdp-split.png
2. **macOS Universal2 (Overview + Auto Touch ID + Cascading Inspector):** https://raw.githubusercontent.com/petrosvassiliou/NexusTS/v1.0.0/docs/screenshots/nexusts-macos-universal-workspace.png
3. **Unified 8-Tab Settings, Cloud Sync & Royal TS / 1Password / Bitwarden Importers:** https://raw.githubusercontent.com/petrosvassiliou/NexusTS/v1.0.0/docs/screenshots/nexusts-settings-sync-importers.png

---

### What’s inside NexusTS 1.0

#### 1. Desktop-Grade RDP (`FreeRDP 3.12` C++20)
* **Adaptive `RDPGFX` H.264 / `AVC444` Chroma** hardware decoding + **`DISP_DVC` dynamic resize** (drag the window and Windows resizes live with zero reconnect) + **HiDPI scaling (`100%–200%`)** across Googlebooks, ChromeOS, Android & macOS.
* **True Bidirectional File, Recursive Folder & Text Copy/Paste (`CLIPRDR`):** Implements a scoped Android `DocumentsProvider` with AOSP `DocumentsUI` copy metadata + window-focus & pre-paste staging—so `Ctrl+C` / `Ctrl+V`, right-click **Paste**, and **Drag & Drop** of files and nested folders work seamlessly between Windows Explorer and the local `Files` app (just like Finder on macOS).
* **Enterprise Redirection & Hyper-V:** `/admin` console sessions, RD Gateway, NLA, Restricted Admin, **Hyper-V Enhanced Session (`VMConnect` PCB on port 2179)**, `\\tsclient\NexusShare` drive redirection, 3-mode audio & mic redirection, and **USB CCID Smartcard / YubiKey PIV** passthrough.
* **Unobstructed Viewport:** The telemetry/action bar sits strictly *outside* the remote desktop canvas so it never blocks remote titlebars or clicks.

#### 2. Rust `russh` SSHv2, Dual-Pane SFTP v3, VNC, Serial & Cloud Workloads
* **SSHv2 & Bastions:** `xterm-256color` / TrueColor terminal, Bastion `ProxyJump` chaining, visual `-L` / `-R` / `-D` **SOCKS5 port-forwarding studio**, Broadcast Input across split panes (`Ctrl+Shift+B`), OpenSSH **Ed25519 key generator**, and asciicast v2 (`.cast`) session recording.
* **Dual-Pane SFTP v3 & FTP File Commander** with streaming transfers.
* **VNC / Apple Screen Sharing (`ARD`)**, **USB-C Hardware Serial / UART Console** (`FTDI`, `CP210x`, `PL2303`, `CH340`, `CDC-ACM` up to `921600 8N1` with `Send BREAK`), **Kubernetes Pod / Docker Container** interactive exec & live logs, plus built-in **TCP Ping, Concurrent Port Scanner & Wake-on-LAN**.

#### 3. Royal TS-Style Hierarchy, Cascading Inheritance & 1-Click Migration
* **Cascading Folder Inheritance:** Assign credentials, RD Gateways, or SSH jump hosts at a parent folder and let child folders/servers inherit them automatically.
* **Smart Folders, Dynamic Folders, Connection Templates & Live RFC 6238 TOTP** codes right in the pinned Credentials vault and Property Inspector.
* **1-Click Non-Destructive Importers:** Import & export **Royal TS (`.rtsz` / `.rtsx`)**, plus **KeePass 2.x XML**, **Bitwarden JSON**, **1Password (`.1pux`)**, and **CSV**.

#### 4. Zero-Knowledge `.nxts` v2 Vault & Frictionless Live Google Drive Sync
* Every vault is sealed locally with **RFC 9106 Argon2id (`64 MiB, t=3, p=4`) + HKDF-SHA256 + AES-256-GCM** with authenticated header AAD—100% byte-compatible across Googlebooks, ChromeOS, Android, and macOS.
* **Live 3-Way Vector-Clock Sync** over **Google Drive** (and **iCloud Drive** on macOS): works out-of-the-box with **zero master-password prompts**, or with a custom Master Passphrase backed by **automatic hardware Touch ID / Fingerprint sensor detection**.

#### 5. Native Across Every Platform (Unified Crisp Light-First UI, 6 Languages)
* **Googlebook OS, ChromeOS (Managed Play) & Android (`APK` / `AAB`):** Full freeform desktop windowing, multi-instance support, zero-flag Workspace Admin Console taskbar pinning & silent CI auto-updates, and 1-click tab tear-off onto external monitors (`DetachedSessionActivity`), 16 KB ELF page-aligned.
* **macOS 14+ (`Universal2 DMG`):** Native Apple Silicon + Intel build with local `/bin/zsh` PTY, automatic Touch ID sensor detection, and `MenuBarExtra`.
* Fully localized in **English, German, Italian, Spanish, Greek, and French**.

---

### Links & Downloads

* **GitHub Repository & Source:** https://github.com/petrosvassiliou/NexusTS
* **v1.0.0 Signed Releases (Googlebook/ChromeOS/Android APK & macOS Universal2 DMG):** https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.0.0
* **ChromeOS Zero-Flag Workspace Managed Play Guide:** https://github.com/petrosvassiliou/NexusTS/blob/main/docs/WORKSPACE_ADMIN_AUTO_UPDATE.md

Would love to hear your feedback—especially from anyone running a Googlebook, Chromebook, Android tablet + dock, or mixed Mac/Android homelab & sysadmin setup!
```
