# NexusTS Production Deployment, Native FreeRDP NDK & OAuth 2.0 Guide

This runbook documents the complete build, native cross-compilation, Google Cloud OAuth 2.0 setup, and Google Play / Managed Google Play release pipeline for **NexusTS (`com.nexusts.app`)** targeting **Googlebook OS** (native Android desktop freeform windowing & multi-monitor) and **Android Tablets/Foldables**.

---

## 1. Release Keystore & Google Cloud OAuth 2.0 Provisioning

Run the automated provisioning script to generate a 4096-bit RSA PKCS#12 release keystore (APK Signature Scheme v3/v4 compliant) and extract your SHA-1 / SHA-256 certificate fingerprints:

```bash
./scripts/provision-signing-and-oauth.sh .signing/nexusts-release.p12 nexusts-upload
```

> [!IMPORTANT]
> Never commit `.signing/` (`*.p12`, `*.jks`, `*.keystore`) to version control. `.gitignore` already excludes all signing keystores.

### Google Cloud Console Configuration (Google Drive API v3 + Credential Manager)
1. Open **Google Cloud Console -> APIs & Services -> Library** and enable **Google Drive API**.
2. Open **OAuth consent screen** and register the two non-broad zero-knowledge scopes used by `GoogleDriveAuthManager`:
   - `https://www.googleapis.com/auth/drive.appdata` (private hidden `appDataFolder` `.nxts` vaults)
   - `https://www.googleapis.com/auth/drive.file` (user-selected shared folders & Team Drives)
3. Open **Credentials -> Create Credentials -> OAuth client ID**:
   - **Client 1 — Android**:
     - Package name: `com.nexusts.app`
     - SHA-1 certificate fingerprint: copy from `provision-signing-and-oauth.sh` output (and also add Google Play App Signing SHA-1 once enrolled in Play Console).
   - **Client 2 — Web application**:
     - Copy the generated **Web Client ID** (`*.apps.googleusercontent.com`). Android `CredentialManager` (`GetGoogleIdOption.setServerClientId`) requires the **Web Client ID**.
4. Provide the Web Client ID either at build time or dynamically via Enterprise MDM:
   - **Build-time**: `export NEXUSTS_GOOGLE_WEB_CLIENT_ID="123456-abc.apps.googleusercontent.com"` (or `-Pnexusts.google.webClientId=...`), injected into `BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID`.
   - **Enterprise MDM (`app_restrictions.xml`)**: push `google_oauth_web_client_id` via Managed Google Play policies; `GoogleDriveAuthManager.resolveConfiguredWebClientId()` automatically prioritizes MDM policy over build-time defaults.

---

## 2. Cross-Compiling Native `libfreerdp3` for Android NDK (16 KB Page Aligned)

`native/CMakeLists.txt` automatically links `libfreerdp3.so`, `libfreerdp-client3.so`, and `libwinpr3.so` with `-DNEXUSTS_HAS_FREERDP3=1` when staged under `native/third_party/freerdp3/<abi>/`.

To cross-compile **OpenSSL 3.3**, **libusb 1.0** (for CCID Smartcard/YubiKey PIV redirection), and **FreeRDP 3.x** (H.264/AVC444 via Android `MediaCodec`, dynamic `DISP_DVC` resize, `CLIPRDR`, `RDPSND`, `AUDIN`, `RDPDR`) for `arm64-v8a` and `x86_64` with Android 15/16+ **16 KB page alignment** (`-Wl,-z,max-page-size=16384`):

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
./scripts/build-freerdp3-ndk.sh --abis arm64-v8a,x86_64
```

Once completed, verify the staged artifacts:
```text
native/third_party/freerdp3/
├── arm64-v8a/
│   ├── include/{freerdp3,winpr3}/
│   └── lib/{libfreerdp3.so,libfreerdp-client3.so,libwinpr3.so,libcrypto.a,libssl.a,libusb-1.0.so}
└── x86_64/
    ├── include/{freerdp3,winpr3}/
    └── lib/{libfreerdp3.so,libfreerdp-client3.so,libwinpr3.so,libcrypto.a,libssl.a,libusb-1.0.so}
```

---

## 3. Building Rust NDK Crates & Signed Release `.aab` / `.apk`

```bash
# 1. Run Rust unit tests & cross-compile libnexusts_jni.so
cargo test --manifest-path native/rust/Cargo.toml
cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release --manifest-path native/rust/Cargo.toml

# 2. Run JVM unit tests & assemble signed Android App Bundle (.aab)
./gradlew testDebugUnitTest bundleRelease assembleRelease
```

---

## 4. Google Play Store & Managed Google Play Compliance

- **Consumer / Standard Google Play**: Uses `AppUpdateManager` (`com.google.android.play:app-update-ktx`) supporting both **Flexible** (background download + top-bar restart chip) and **Immediate** (priority >= 4 critical security patches).
- **Googlebook OS Enterprise / MDM**: Uses Managed Google Play policies (`app_restrictions.xml`) and standard `PackageInstaller.Session` with strict X.509 SHA-256 certificate pinning (`enterprise_update_signer_sha256`). Zero `REQUEST_INSTALL_PACKAGES` sideloading permissions and zero untrusted `DexClassLoader` usage ensure 100% Google Play Protect compliance.
