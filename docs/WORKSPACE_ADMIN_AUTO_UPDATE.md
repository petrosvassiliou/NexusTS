# Google Workspace Managed Play — Zero-Flag Install & Auto-Update Guide

This guide sets up **NexusTS** on ChromeOS & Android via **Google Workspace Admin Console** with **zero developer flags**, **taskbar pinning**, and **100% automated GitHub Actions updates** on every `v*` tag push.

---

## Part 1: Initial Private APK Upload & Taskbar Pinning (One-Time)

> **Important:** Upload **`NexusTS-v1.0.0-googlebook-android-release.apk`** (the `.apk`, *not* the `.aab`). Uploading the `.apk` preserves NexusTS's production RSA-4096 signing certificate (`SHA-1: A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6`) so Google Sign-In & Google Drive Vault Sync work out of the box.

1. Download **`NexusTS-v1.0.0-googlebook-android-release.apk`** from [GitHub Releases](https://github.com/petrosvassiliou/NexusTS/releases/tag/v1.0.0).
2. In **Google Workspace Admin Console** (`https://admin.google.com`), navigate to **Apps** → **Web and mobile apps**.
3. Click **Add app** → **Add private Android app**.
4. Click **`+` (Create)** at the bottom right, enter **`NexusTS`**, upload **`NexusTS-v1.0.0-googlebook-android-release.apk`**, and click **Create** → **Select** → **Finish**.
5. Navigate to **Devices** → **Chrome** → **Apps & extensions** → **Users & browsers**.
6. Click **`+`** → **Add from Google Play** → **Private apps** → select **NexusTS** → **Select** → **Accept**.
7. Set **Installation policy** to **Force install + pin to ChromeOS taskbar** and click **Save**.

---

## Part 2: Enable Zero-Click Automated Updates from GitHub Actions (One-Time)

Once the private app exists in Admin Console, link a Google Cloud Service Account so pushing any new Git tag (e.g. `v1.0.1`, `v1.1.0`) automatically publishes the update into your Workspace Managed Google Play:

1. **Enable the API & Create a Service Account in Google Cloud Console (`https://console.cloud.google.com`):**
   - Enable **Google Play Android Developer API** (`androidpublisher.googleapis.com`).
   - Go to **IAM & Admin** → **Service Accounts** → **Create Service Account** (e.g. `nexusts-managed-play-ci`).
   - Copy its email address (`nexusts-managed-play-ci@...iam.gserviceaccount.com`), open the **Keys** tab → **Add Key** → **Create new key (`JSON`)**, and download the JSON file.
2. **Grant Release Permission on Your Private App's Managed Play Console:**
   - In **Google Workspace Admin Console** → **Apps** → **Web and mobile apps** → **Add app** → **Add private Android app**, select **NexusTS** and click **Make advanced edits** *(this opens the underlying Managed Play Console created for your Workspace domain)*.
   - In Play Console, go to **Users and permissions** → **Invite new users**, paste the Service Account email, grant **Release to production, exclude devices, and use Play App Signing** (under *Releases* / *App permissions* for **NexusTS**), and click **Invite user**.
3. **Save the JSON Secret in GitHub:**
   - In your GitHub repository (`petrosvassiliou/NexusTS`) → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:
     - **Name:** `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`
     - **Secret:** *(paste the entire contents of the Service Account `.json` key)*

---

## How Future Releases Work Automatically

Whenever you push a new release tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

1. `app/build.gradle.kts` automatically derives `versionName = "1.0.1"` and monotonic `versionCode = 11001` (`v1.1.0` → `11100`, `v2.0.0` → `2000000`).
2. `.github/workflows/build-nexusts.yml` compiles FreeRDP 3.12 + Rust `russh`, signs `NexusTS-v1.0.1-googlebook-android-release.apk` with your RSA-4096 production key, publishes the GitHub Release, and invokes `scripts/publish-managed-play.py`.
3. `scripts/publish-managed-play.py` uploads the new `.apk` directly to your Workspace Managed Google Play **`production`** track, and your Chromebook silently auto-updates in the background.

### Optional Repository Variables (`Settings -> Secrets and variables -> Actions -> Variables`)
No variables are required for the standard Workspace Private APK setup above. If you ever customize your setup:
- `GOOGLE_PLAY_PACKAGE_NAME` — defaults to `com.nexusts.app` (set e.g. `com.nexusts.app.enterprise` if you ever want a separate private enterprise ID alongside a public Play Store listing).
- `GOOGLE_PLAY_TRACK` — defaults to `production` (`internal`, `alpha`, or `beta` also supported).
- `GOOGLE_PLAY_UPLOAD_FORMAT` — defaults to `apk` (set to `aab` only if publishing to Play App Signing).
