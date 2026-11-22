#!/usr/bin/env python3
"""
Automated Google Workspace Managed Google Play (Private App) & Play Store Publisher.

Designed for zero-click ChromeOS & Android enterprise updates from GitHub Actions:
- Defaults to uploading the signed `.apk` (`edits.apks.upload`) directly to the `production` track,
  matching Google Workspace Admin Console Private Apps (`Apps -> Web and mobile apps -> Add private Android app`)
  so your RSA-4096 signing certificate (`SHA-1: A6:AF:15:A9:...`) is preserved for Google Drive OAuth.
- Also supports `.aab` bundles (`edits.bundles.upload`) and custom tracks (`internal`, `alpha`, `beta`, `production`).
- Gracefully handles Workspace Private App locale mismatches (falls back cleanly if `en-US` release notes
  are not enabled on the private store listing) and `changesNotSentForReview` commit requirements.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
from typing import Any


ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"


def load_service_account_info(raw_value: str) -> dict[str, Any]:
    candidate = raw_value.strip()
    if not candidate:
        raise ValueError("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is empty.")

    if candidate.startswith("{"):
        data = json.loads(candidate)
    else:
        path = pathlib.Path(candidate)
        if not path.is_file():
            raise ValueError("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON is neither valid JSON nor an existing file path.")
        data = json.loads(path.read_text(encoding="utf-8"))

    for required_key in ("client_email", "private_key", "token_uri"):
        if not data.get(required_key):
            raise ValueError(f"Service account JSON is missing required field '{required_key}'.")
    return data


def publish_release_with_service(
    service: Any,
    media_factory: Any,
    *,
    package_name: str,
    artifact_path: pathlib.Path,
    track: str = "production",
    release_tag: str = "",
) -> dict[str, Any]:
    if not artifact_path.is_file():
        raise FileNotFoundError(f"Release artifact not found: {artifact_path}")

    ext = artifact_path.suffix.lower()
    if ext not in (".apk", ".aab"):
        raise ValueError(f"Unsupported artifact extension '{ext}' (expected .apk or .aab).")

    edits = service.edits()
    edit = edits.insert(body={}, packageName=package_name).execute()
    edit_id = edit["id"]

    if ext == ".apk":
        media = media_factory(str(artifact_path), mimetype="application/vnd.android.package-archive", resumable=True)
        upload_resp = edits.apks().upload(packageName=package_name, editId=edit_id, media_body=media).execute()
    else:
        media = media_factory(str(artifact_path), mimetype="application/octet-stream", resumable=True)
        upload_resp = edits.bundles().upload(packageName=package_name, editId=edit_id, media_body=media).execute()

    version_code = int(upload_resp["versionCode"])
    release_name = release_tag or str(version_code)
    note_text = f"NexusTS {release_name} automated release ({ext.lstrip('.').upper()} versionCode {version_code})."

    release_entry_with_notes = {
        "name": release_name,
        "versionCodes": [str(version_code)],
        "status": "completed",
        "releaseNotes": [{"language": "en-US", "text": note_text}],
    }

    try:
        edits.tracks().update(
            packageName=package_name,
            editId=edit_id,
            track=track,
            body={"track": track, "releases": [release_entry_with_notes]},
        ).execute()
    except Exception as exc:
        # Workspace Private Apps created under a non-en-US admin locale reject `en-US` releaseNotes;
        # retry cleanly without `releaseNotes` so the binary update still rolls out immediately.
        err_text = str(exc).lower()
        if "language" in err_text or "releasenotes" in err_text or "localization" in err_text or "invalid" in err_text:
            release_entry_plain = {
                "name": release_name,
                "versionCodes": [str(version_code)],
                "status": "completed",
            }
            edits.tracks().update(
                packageName=package_name,
                editId=edit_id,
                track=track,
                body={"track": track, "releases": [release_entry_plain]},
            ).execute()
        else:
            raise

    try:
        commit_resp = edits.commit(packageName=package_name, editId=edit_id).execute()
    except Exception as exc:
        if "changesnotsentforreview" in str(exc).lower():
            commit_resp = edits.commit(
                packageName=package_name,
                editId=edit_id,
                changesNotSentForReview=True,
            ).execute()
        else:
            raise

    return {
        "packageName": package_name,
        "track": track,
        "format": ext.lstrip("."),
        "versionCode": version_code,
        "releaseName": release_name,
        "editId": commit_resp.get("id", edit_id),
    }


def _run_self_test() -> None:
    import tempfile

    calls: list[tuple[str, Any]] = []

    class _Req:
        def __init__(self, fn):
            self._fn = fn

        def execute(self):
            return self._fn()

    class _FakeApks:
        def upload(self, **kwargs):
            calls.append(("apks.upload", kwargs["editId"]))
            return _Req(lambda: {"versionCode": 11001})

    class _FakeBundles:
        def upload(self, **kwargs):
            calls.append(("bundles.upload", kwargs["editId"]))
            return _Req(lambda: {"versionCode": 11002})

    class _FakeTracks:
        def __init__(self):
            self.attempts = 0

        def update(self, **kwargs):
            self.attempts += 1
            body = kwargs["body"]
            calls.append(("tracks.update", kwargs["track"], "releaseNotes" in body["releases"][0]))
            if self.attempts == 1:
                raise RuntimeError("Invalid request: releaseNotes language en-US is not enabled for private app")
            return _Req(lambda: body)

    class _FakeEdits:
        def __init__(self):
            self._tracks = _FakeTracks()
            self._commits = 0

        def insert(self, **kwargs):
            calls.append(("edits.insert", kwargs["packageName"]))
            return _Req(lambda: {"id": "edit-42"})

        def apks(self):
            return _FakeApks()

        def bundles(self):
            return _FakeBundles()

        def tracks(self):
            return self._tracks

        def commit(self, **kwargs):
            self._commits += 1
            calls.append(("edits.commit", kwargs.get("changesNotSentForReview", False)))
            if self._commits == 1:
                raise RuntimeError("Please set changesNotSentForReview to true")
            return _Req(lambda: {"id": kwargs["editId"]})

    class _FakeService:
        def __init__(self):
            self._edits = _FakeEdits()

        def edits(self):
            return self._edits

    with tempfile.TemporaryDirectory() as tmp:
        apk_file = pathlib.Path(tmp) / "NexusTS-v1.0.1-googlebook-android-release.apk"
        apk_file.write_bytes(b"PK\x03\x04fake-apk")
        result = publish_release_with_service(
            _FakeService(),
            lambda path, mimetype, resumable: (path, mimetype, resumable),
            package_name="com.nexusts.app",
            artifact_path=apk_file,
            track="production",
            release_tag="v1.0.1",
        )
        assert result["versionCode"] == 11001, result
        assert result["track"] == "production", result
        assert ("apks.upload", "edit-42") in calls, calls
        assert ("tracks.update", "production", False) in calls, calls
        assert ("edits.commit", True) in calls, calls

    sa = load_service_account_info(
        json.dumps(
            {
                "client_email": "ci@nexusts.iam.gserviceaccount.com",
                "private_key": "-----BEGIN PRIVATE KEY-----\nMIIB...\n-----END PRIVATE KEY-----\n",
                "token_uri": "https://oauth2.googleapis.com/token",
            }
        )
    )
    assert sa["client_email"] == "ci@nexusts.iam.gserviceaccount.com"
    print("[OK] publish-managed-play.py self-test passed.")


def main() -> int:
    if "--self-test" in sys.argv:
        _run_self_test()
        return 0

    raw_sa = os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")
    package_name = (os.environ.get("GOOGLE_PLAY_PACKAGE_NAME") or "com.nexusts.app").strip()
    track = (os.environ.get("GOOGLE_PLAY_TRACK") or "production").strip()
    artifact_str = (os.environ.get("GOOGLE_PLAY_ARTIFACT_PATH") or "").strip()
    release_tag = (os.environ.get("GOOGLE_PLAY_RELEASE_TAG") or "").strip()

    if not artifact_str:
        raise SystemExit("ERROR: GOOGLE_PLAY_ARTIFACT_PATH environment variable is required.")

    sa_info = load_service_account_info(raw_sa)

    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload

    credentials = service_account.Credentials.from_service_account_info(
        sa_info,
        scopes=[ANDROID_PUBLISHER_SCOPE],
    )
    service = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)

    summary = publish_release_with_service(
        service,
        MediaFileUpload,
        package_name=package_name,
        artifact_path=pathlib.Path(artifact_str),
        track=track,
        release_tag=release_tag,
    )

    print(json.dumps(summary, indent=2))

    step_summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary_path:
        with open(step_summary_path, "a", encoding="utf-8") as fh:
            fh.write(
                "\n### Managed Google Play / Play Store Auto-Publish\n"
                f"- **Package ID:** `{summary['packageName']}`\n"
                f"- **Track:** `{summary['track']}`\n"
                f"- **Format:** `{summary['format'].upper()}`\n"
                f"- **Version Code:** `{summary['versionCode']}` (`{summary['releaseName']}`)\n"
                f"- **Committed Edit ID:** `{summary['editId']}`\n"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
