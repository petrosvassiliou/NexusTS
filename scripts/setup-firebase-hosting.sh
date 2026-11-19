#!/usr/bin/env bash
# ==============================================================================
# NexusTS — 1-Command Firebase Hosting + Keyless GitHub OIDC Provisioner
# ==============================================================================
# Modeled 1:1 on `thingsbook-prod` (`https://thingsbook-prod.web.app/`):
#
# Usage:
#   ./scripts/setup-firebase-hosting.sh [firebase-project-id=nexusts-prod] [github-repo=petrosvassiliou/NexusTS]
#
# What this script automates in one run:
#   1. Creates (or attaches) GCP / Firebase project `nexusts-prod` & writes `.firebaserc`.
#   2. Enables Firebase Hosting + IAM Workload Identity Federation APIs.
#   3. Creates Service Account `github-action-nexusts@<project>.iam.gserviceaccount.com`
#      with `roles/firebasehosting.admin` & `roles/firebase.viewer`.
#   4. Creates Keyless GitHub OIDC Pool (`github-actions-pool`) & Provider (`github-oidc`)
#      locked strictly to `petrosvassiliou/NexusTS` (zero static JSON keys!).
#   5. Sets `FIREBASE_PROJECT_ID`, `FIREBASE_WORKLOAD_IDENTITY_PROVIDER`, and
#      `FIREBASE_SERVICE_ACCOUNT` on GitHub Actions via `gh variable set`.
#   6. Builds `chromeos-pwa/hosting-dist` and deploys live to `https://<project>.web.app/`!
# ==============================================================================
set -euo pipefail

PROJECT_ID="${1:-nexusts-prod}"
GITHUB_REPO="${2:-petrosvassiliou/NexusTS}"
POOL_ID="github-actions-pool"
PROVIDER_ID="github-oidc"
SA_NAME="github-action-nexusts"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for cmd in gcloud npx gh node; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Fehler: '${cmd}' wird benötigt, wurde aber nicht im PATH gefunden."
    exit 1
  fi
done

echo "==> [1/6] Prüfe / erstelle Google Cloud & Firebase Projekt '${PROJECT_ID}'..."
if ! gcloud projects describe "${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud projects create "${PROJECT_ID}" --name="NexusTS ChromeOS PWA"
fi
gcloud config set project "${PROJECT_ID}" >/dev/null

npx --yes firebase-tools projects:list >/dev/null 2>&1 || npx --yes firebase-tools login
npx --yes firebase-tools projects:addfirebase "${PROJECT_ID}" 2>/dev/null || true

cat > "${REPO_ROOT}/.firebaserc" <<EOF
{
  "projects": {
    "default": "${PROJECT_ID}"
  }
}
EOF

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
echo "    Projekt-Nummer: ${PROJECT_NUMBER}"

echo "==> [2/6] Aktiviere benötigte Google Cloud & Firebase Hosting APIs..."
gcloud services enable \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  cloudresourcemanager.googleapis.com \
  serviceusage.googleapis.com \
  firebase.googleapis.com \
  firebasehosting.googleapis.com \
  --project="${PROJECT_ID}"

npx --yes firebase-tools hosting:sites:create "${PROJECT_ID}" --project "${PROJECT_ID}" 2>/dev/null || true

echo "==> [3/6] Richte Deployment Service Account (${SA_EMAIL}) ein..."
if ! gcloud iam service-accounts describe "${SA_EMAIL}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${SA_NAME}" \
    --display-name="GitHub Actions Firebase Hosting Deployer (NexusTS)" \
    --project="${PROJECT_ID}"
  # Wait briefly for IAM propagation on brand-new service accounts
  sleep 5
fi

for role in roles/firebasehosting.admin roles/firebase.viewer roles/serviceusage.serviceUsageConsumer; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="${role}" \
    --condition=None \
    --quiet >/dev/null
done

echo "==> [4/6] Konfiguriere Keyless Workload Identity Federation (OIDC) für ${GITHUB_REPO}..."
if ! gcloud iam workload-identity-pools describe "${POOL_ID}" --location="global" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud iam workload-identity-pools create "${POOL_ID}" \
    --location="global" \
    --display-name="GitHub Actions Pool" \
    --project="${PROJECT_ID}"
fi

if ! gcloud iam workload-identity-pools providers describe "${PROVIDER_ID}" \
  --workload-identity-pool="${POOL_ID}" \
  --location="global" \
  --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud iam workload-identity-pools providers create-oidc "${PROVIDER_ID}" \
    --workload-identity-pool="${POOL_ID}" \
    --location="global" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" \
    --attribute-condition="assertion.repository == '${GITHUB_REPO}'" \
    --project="${PROJECT_ID}"
fi

gcloud iam service-accounts add-iam-policy-binding "${SA_EMAIL}" \
  --project="${PROJECT_ID}" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}/attribute.repository/${GITHUB_REPO}" \
  --quiet >/dev/null

WIF_PROVIDER="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}/providers/${PROVIDER_ID}"

echo "==> [5/6] Speichere OIDC-Variablen im GitHub-Repository (${GITHUB_REPO})..."
gh variable set FIREBASE_PROJECT_ID --body "${PROJECT_ID}" -R "${GITHUB_REPO}"
gh variable set FIREBASE_WORKLOAD_IDENTITY_PROVIDER --body "${WIF_PROVIDER}" -R "${GITHUB_REPO}"
gh variable set FIREBASE_SERVICE_ACCOUNT --body "${SA_EMAIL}" -R "${GITHUB_REPO}"

echo "==> [6/6] Baue NexusTS ChromeOS PWA & führe erstes Live-Deployment aus..."
if command -v cargo >/dev/null 2>&1 && rustup target list --installed 2>/dev/null | grep -q "wasm32-unknown-unknown"; then
  (
    cd "${REPO_ROOT}/chromeos-pwa/rust"
    cargo build -p nexusts-wasm --target wasm32-unknown-unknown --release
    mkdir -p ../app/wasm
    cp target/wasm32-unknown-unknown/release/nexusts_wasm.wasm ../app/wasm/nexusts_wasm.wasm
  )
fi

npm --prefix "${REPO_ROOT}/chromeos-pwa" run build:hosting
(
  cd "${REPO_ROOT}"
  npx --yes firebase-tools deploy --only hosting --project "${PROJECT_ID}"
)

echo ""
echo "=============================================================================="
echo " Fertig! NexusTS ChromeOS PWA ist live unter:"
echo "   https://${PROJECT_ID}.web.app/"
echo ""
echo " Auf ChromeOS einfach einmal im Browser öffnen -> 1 Klick auf"
echo " 'Ins Startmenü installieren' -> NexusTS liegt fest im ChromeOS-Startmenü"
echo " & aktualisiert sich bei jedem Push auf 'main' vollautomatisch!"
echo "=============================================================================="
