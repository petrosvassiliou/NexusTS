#!/usr/bin/env bash
# ==============================================================================
# NexusTS (com.nexusts.app) — Release Signing & Google Cloud OAuth Provisioner
# ==============================================================================
# Generates a PKCS#12 release keystore compatible with APK Signature Scheme v3/v4,
# extracts SHA-1 and SHA-256 certificate fingerprints, and outputs:
#   1. Google Cloud Console OAuth 2.0 registration parameters (Credential Manager + Drive v3)
#   2. Managed Google Play / MDM app_restrictions JSON (enterprise_update_signer_sha256)
#   3. GitHub Actions CI secret commands (gh secret set ...)
#
# Usage:
#   ./scripts/provision-signing-and-oauth.sh [output_keystore.p12] [key_alias]
# ==============================================================================
set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
Usage: ./scripts/provision-signing-and-oauth.sh [KEYSTORE_PATH] [KEY_ALIAS]

Environment overrides:
  NEXUSTS_KEYSTORE_PASSWORD   Password for the PKCS#12 keystore (prompted or generated if unset)
  NEXUSTS_DNAME               Distinguished Name (default: CN=NexusTS Release, OU=Security, O=NexusTS, C=US)
  NEXUSTS_GOOGLE_WEB_CLIENT_ID Optional Web Client ID to include in printed CI commands
EOF
  exit 0
fi

KEYSTORE_PATH="${1:-$(pwd)/.signing/nexusts-release.p12}"
KEY_ALIAS="${2:-nexusts-upload}"
DNAME="${NEXUSTS_DNAME:-CN=NexusTS Enterprise Release, OU=Platform Security, O=NexusTS, C=US}"

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: 'keytool' (JDK 17+) not found on PATH." >&2
  exit 1
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"

if [[ -z "${NEXUSTS_KEYSTORE_PASSWORD:-}" ]]; then
  if command -v openssl >/dev/null 2>&1; then
    STORE_PASS="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 28)"
  else
    STORE_PASS="NexusTS-$(date +%s)-ChangeMe"
  fi
else
  STORE_PASS="$NEXUSTS_KEYSTORE_PASSWORD"
fi

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "==> Generating 4096-bit RSA PKCS#12 release keystore at: $KEYSTORE_PATH"
  keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 9125 \
    -storepass "$STORE_PASS" \
    -keypass "$STORE_PASS" \
    -dname "$DNAME" >/dev/null
else
  echo "==> Using existing keystore at: $KEYSTORE_PATH"
fi

CERT_INFO="$(keytool -list -v -keystore "$KEYSTORE_PATH" -storepass "$STORE_PASS" -alias "$KEY_ALIAS")"
SHA1_COLON="$(printf '%s\n' "$CERT_INFO" | awk '/SHA1:/ {print $2; exit}')"
SHA256_COLON="$(printf '%s\n' "$CERT_INFO" | awk '/SHA256:/ {print $2; exit}')"
SHA256_HEX_LOWER="$(printf '%s' "$SHA256_COLON" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
KEYSTORE_B64="$(base64 -w 0 "$KEYSTORE_PATH" 2>/dev/null || base64 "$KEYSTORE_PATH" | tr -d '\n')"

cat <<EOF

================================================================================
NexusTS Production Signing & OAuth 2.0 Provisioning Summary
================================================================================
Application ID       : com.nexusts.app
Keystore Path        : $KEYSTORE_PATH
Key Alias            : $KEY_ALIAS
SHA-1 Fingerprint    : $SHA1_COLON
SHA-256 Fingerprint  : $SHA256_COLON
MDM Signer SHA-256   : $SHA256_HEX_LOWER

1) Google Cloud Console -> APIs & Services -> Credentials:
   a) Enable "Google Drive API".
   b) Create OAuth 2.0 Client ID (Application type: Android):
      - Package name : com.nexusts.app
      - SHA-1        : $SHA1_COLON
   c) Create OAuth 2.0 Client ID (Application type: Web application):
      - Copy the generated Web Client ID (e.g. 123456-abc.apps.googleusercontent.com)
      - Pass it to Gradle via NEXUSTS_GOOGLE_WEB_CLIENT_ID or MDM 'google_oauth_web_client_id'.
   d) Configure OAuth Consent Screen scopes:
      - https://www.googleapis.com/auth/drive.appdata
      - https://www.googleapis.com/auth/drive.file

2) Managed Google Play / Enterprise MDM Managed Configuration JSON:
{
  "allow_in_app_updates": true,
  "enterprise_update_signer_sha256": "$SHA256_HEX_LOWER",
  "managed_team_drive_id": "",
  "google_oauth_web_client_id": "${NEXUSTS_GOOGLE_WEB_CLIENT_ID:-YOUR_WEB_CLIENT_ID.apps.googleusercontent.com}"
}

3) GitHub Actions Repository Secrets (run once in repo root):
   printf '%s' '$KEYSTORE_B64' | gh secret set NEXUSTS_KEYSTORE_BASE64
   printf '%s' '$STORE_PASS'   | gh secret set NEXUSTS_KEYSTORE_PASSWORD
   printf '%s' '$KEY_ALIAS'    | gh secret set NEXUSTS_KEY_ALIAS
   printf '%s' '$STORE_PASS'   | gh secret set NEXUSTS_KEY_PASSWORD
   printf '%s' '${NEXUSTS_GOOGLE_WEB_CLIENT_ID:-YOUR_WEB_CLIENT_ID.apps.googleusercontent.com}' | gh secret set NEXUSTS_GOOGLE_WEB_CLIENT_ID
================================================================================
EOF
