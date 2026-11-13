#!/usr/bin/env bash
# =============================================================================
# NexusTS — Optional Google Cloud Console OAuth 2.0 Provisioning Helper (v1.1)
# =============================================================================
# Note: Direct-installed NexusTS APKs ALREADY support Zero-Setup Live Google
# Drive Sync out-of-the-box via persistent SAF (`SafLiveGoogleDriveBridge`)!
#
# Use this script ONLY if you also want native Google Drive REST API v3
# (`appDataFolder` + multi-user advisory locks + cloud revision history) via
# Google Identity Services (`AuthorizationClient`).
# =============================================================================
set -euo pipefail

PROJECT_ID="${1:-}"
PACKAGE_NAME="com.nexusts.app"
RELEASE_SHA1="A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6"
DEFAULT_DRIVE_FOLDER_ID="17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS"

if [[ "${PROJECT_ID}" == "--dry-run" || -z "${PROJECT_ID}" ]]; then
  cat <<EOF
NexusTS Google Drive Sync Provisioning Guide
============================================
1. Zero-Setup Mode (Recommended for Direct APK Installs — Works Immediately!):
   - Open NexusTS -> Drive Sync -> click "Create Vault in Google Drive" or "Link Existing Drive .nxts".
   - Select your "NexusTS Vaults" folder in Google Drive (Folder ID: ${DEFAULT_DRIVE_FOLDER_ID}).
   - NexusTS persists read/write access and performs automatic 3-way Vector-Clock merges!

2. Google Cloud OAuth 2.0 REST API v3 Mode (Optional):
   Usage: ./scripts/setup-gdrive-oauth.sh <GCP_PROJECT_ID>
   - Enables drive.googleapis.com on <GCP_PROJECT_ID>
   - Prints exact Android OAuth 2.0 Client parameters:
       Package name : ${PACKAGE_NAME}
       SHA-1 cert   : ${RELEASE_SHA1}
       Scopes       : https://www.googleapis.com/auth/drive.appdata
                      https://www.googleapis.com/auth/drive.file
EOF
  exit 0
fi

if ! command -v gcloud >/dev/null 2>&1; then
  echo "ERROR: gcloud CLI is not installed. Install Google Cloud SDK first." >&2
  exit 1
fi

echo "==> Setting active Google Cloud project to: ${PROJECT_ID}"
gcloud config set project "${PROJECT_ID}"

echo "==> Enabling Google Drive API v3 (drive.googleapis.com)..."
gcloud services enable drive.googleapis.com --project "${PROJECT_ID}"

cat <<EOF

[OK] Google Drive API v3 enabled on project '${PROJECT_ID}'!

Final Step (1 minute in Google Cloud Console):
1. Open: https://console.cloud.google.com/apis/credentials?project=${PROJECT_ID}
2. Click "+ CREATE CREDENTIALS" -> "OAuth client ID" -> Application type: "Android"
   - Name                 : NexusTS Android Release
   - Package name         : ${PACKAGE_NAME}
   - SHA-1 fingerprint    : ${RELEASE_SHA1}
3. (Optional) Create a "Web application" OAuth client ID and paste it into:
   NexusTS -> Drive Sync -> OAuth 2.0 Web Client ID (or GitHub Secret NEXUSTS_GOOGLE_WEB_CLIENT_ID).
   Note: Android AuthorizationClient works for Drive v3 even without a Web Client ID!
EOF
