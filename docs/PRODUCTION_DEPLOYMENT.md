# NexusTS Production Deployment, Native FreeRDP NDK & OAuth 2.0 Guide

This runbook documents the live production signing identity, native FreeRDP 3.12 cross-compilation, Google Cloud OAuth 2.0 setup, and Google Play / Managed Google Play release pipeline for **NexusTS (`com.nexusts.app`)** targeting **Googlebook OS** (native Android desktop freeform windowing & multi-monitor) and **Android Tablets/Foldables**.

---

## 1. Live Production Signing Identity & Fingerprints

A 4096-bit RSA PKCS#12 release upload key (`nexusts-upload`, APK Signature Scheme v2/v3/v4 compliant) is configured in GitHub Actions Encrypted Secrets (`NEXUSTS_KEYSTORE_BASE64`, `NEXUSTS_KEYSTORE_PASSWORD`, `NEXUSTS_KEY_ALIAS`, `NEXUSTS_KEY_PASSWORD`), with its public X.509 certificate published at `certs/nexusts-upload-certificate.pem` and `certs/oauth-and-mdm-registration.json`:

| Field | Value |
| :--- | :--- |
| **Application ID** | `com.nexusts.app` |
| **Key Alias / Algorithm** | `nexusts-upload` (`RSA-4096` / `SHA384withRSA`) |
| **SHA-1 Fingerprint** | `A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6` |
| **SHA-256 Fingerprint** | `4A:BD:80:F1:20:14:B9:88:09:1C:97:92:2F:87:02:D1:BF:B7:93:9C:73:B6:9A:DB:1A:9B:76:30:6A:30:27:82` |
| **MDM Pin (`enterprise_update_signer_sha256`)** | `4abd80f12014b988091c97922f8702d1bfb7939c73b69adb1a9b76306a302782` |

To rotate or generate an alternate enterprise keystore locally:
```bash
./scripts/provision-signing-and-oauth.sh .signing/nexusts-release.p12 nexusts-upload
```

> [!IMPORTANT]
> Never commit `.signing/` (`*.p12`, `*.jks`, `*.keystore`) to version control. `.gitignore` excludes all private keystores.

---

## 2. Google Cloud Console Configuration (Google Drive API v3 + Credential Manager)

1. Open **Google Cloud Console -> APIs & Services -> Library** and enable **Google Drive API** (`drive.googleapis.com`).
2. Open **OAuth consent screen** and register the two non-broad zero-knowledge scopes used by `GoogleDriveAuthManager`:
   - `https://www.googleapis.com/auth/drive.appdata` (private hidden `appDataFolder` `.nxts` vaults)
   - `https://www.googleapis.com/auth/drive.file` (user-selected shared folders & Team Drives)
3. Open **Credentials -> Create Credentials -> OAuth client ID**:
   - **Client 1 — Android**:
     - Package name: `com.nexusts.app`
     - SHA-1 certificate fingerprint: `A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6` (and add Google Play App Signing SHA-1 once enrolled in Play Console).
   - **Client 2 — Web application**:
     - Copy the generated **Web Client ID** (`*.apps.googleusercontent.com`). Android `CredentialManager` (`GetGoogleIdOption.setServerClientId`) requires this **Web Client ID**.
4. Provide the Web Client ID via any of NexusTS's 3-tier precedence levels (`GoogleDriveAuthManager.resolveConfiguredWebClientId`):
   - **Tier 1 — Enterprise MDM (`app_restrictions.xml`)**: Push `google_oauth_web_client_id` and optional `managed_team_drive_id` via Managed Google Play policies.
   - **Tier 2 — In-App Runtime Provisioning**: Paste the Web Client ID and optional Shared Drive Folder ID directly inside **NexusTS -> Drive Sync Studio -> Google Cloud OAuth 2.0 & Credential Manager Provisioning**.
   - **Tier 3 — Build-Time Default**: Set GitHub repository secret `NEXUSTS_GOOGLE_WEB_CLIENT_ID` (or `-Pnexusts.google.webClientId=...`), injected into `BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID`.

---

## 3. Cross-Compiling Native `libfreerdp3` for Android NDK (16 KB Page Aligned)

`native/CMakeLists.txt` automatically links `libfreerdp3.so`, `libfreerdp-client3.so`, and `libwinpr3.so` with `NEXUSTS_HAS_FREERDP3_BINARY=1` when staged under `native/third_party/freerdp3/<abi>/`.

To cross-compile **OpenSSL 3.4.1**, **libusb 1.0.27** (CCID Smartcard/YubiKey PIV redirection), and **FreeRDP 3.12.0** (H.264/AVC444 via Android `MediaCodec`, dynamic `DISP_DVC` resize, `CLIPRDR`, `RDPSND`, `AUDIN`, `RDPDR`) for `arm64-v8a` and `x86_64` with Android 15/16+ & Googlebook OS **16 KB page alignment** (`-Wl,-z,max-page-size=16384`):

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
./scripts/build-freerdp3-ndk.sh --abis arm64-v8a,x86_64
```

In GitHub Actions (`.github/workflows/build-nexusts.yml`), FreeRDP 3.12.0 cross-compilation runs automatically on `v*` release tags or whenever `compile_full_freerdp3=true` is selected on `workflow_dispatch` (cached across runs via `actions/cache@v4`).

---

## 4. Building Rust NDK Crates, Automated Binary QA & Release Publishing

```bash
# 1. Run Rust unit tests & cross-compile 16 KB aligned libnexusts_jni.so
cargo test --manifest-path native/rust/Cargo.toml
cargo ndk -t arm64-v8a -t x86_64 --platform 29 -o app/src/main/jniLibs build --release --manifest-path native/rust/Cargo.toml

# 2. Run JVM unit tests & assemble signed Android App Bundle (.aab) + Release APK (.apk)
./gradlew testDebugUnitTest assembleDebug bundleRelease assembleRelease
```

Every CI run executes automated **Device & Play Protect Binary QA** against `app-release.apk`:
- `apksigner verify --verbose --print-certs`: Asserts valid APK Signature Scheme v2 & v3 signatures matching our RSA-4096 production upload certificate.
- `aapt2 dump badging` & `aapt2 dump permissions`: Verifies `com.nexusts.app`, all 6 bundled locales (`en`, `de`, `it`, `es`, `el`, `fr`), and zero `REQUEST_INSTALL_PACKAGES` permissions (100% Google Play Protect compliance).
- `llvm-readelf -l`: Inspects every `.so` inside `app-release.apk` (`arm64-v8a` and `x86_64`) and verifies `LOAD` segment alignment >= `0x4000` (16 KB page size compliance for Googlebook OS & Android 15/16+).
