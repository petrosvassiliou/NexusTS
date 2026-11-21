#!/usr/bin/env bash
# ==============================================================================
# NexusTS — Zero-Prompt Firebase Hosting + Keyless GitHub OIDC Provisioner
# ==============================================================================
# Works directly in Google Cloud Shell (https://shell.cloud.google.com) on
# ChromeOS with ZERO local installs and ZERO extra `firebase login` prompts,
# as well as on macOS / Linux!
#
# Usage:
#   ./scripts/setup-firebase-hosting.sh [firebase-project-id=nexusts-prod]
# ==============================================================================
set -euo pipefail

PROJECT_ID="${1:-nexusts-prod}"
GITHUB_REPO="${2:-petrosvassiliou/NexusTS}"
POOL_ID="github-actions-pool"
PROVIDER_ID="github-oidc"
SA_NAME="github-action-nexusts"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
if [[ -n "${BASH_SOURCE[0]:-}" ]]; then
  REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
else
  REPO_ROOT="${PWD}"
fi

if ! command -v gcloud >/dev/null 2>&1; then
  echo "Error: 'gcloud' not found. Tip: Run this directly in Google Cloud Shell (https://shell.cloud.google.com)!"
  exit 1
fi

echo "==> [1/5] Creating or attaching Google Cloud project '${PROJECT_ID}'..."
if ! gcloud projects describe "${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud projects create "${PROJECT_ID}" --name="NexusTS ChromeOS PWA"
fi
gcloud config set project "${PROJECT_ID}" >/dev/null

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
echo "    Project ID:     ${PROJECT_ID}"
echo "    Project Number: ${PROJECT_NUMBER}"

echo "==> [2/5] Enabling Firebase Hosting & Keyless OIDC IAM APIs..."
gcloud services enable \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  cloudresourcemanager.googleapis.com \
  serviceusage.googleapis.com \
  firebase.googleapis.com \
  firebasehosting.googleapis.com \
  --project="${PROJECT_ID}"

ACCESS_TOKEN="$(gcloud auth print-access-token)"

echo "==> [3/5] Provisioning Firebase & Hosting site (https://${PROJECT_ID}.web.app/)..."
curl -sS -X POST \
  "https://firebase.googleapis.com/v1beta1/projects/${PROJECT_ID}:addFirebase" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "x-goog-user-project: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '{}' >/dev/null || true

# Poll briefly if addFirebase LRO is still completing
sleep 6

curl -sS -X POST \
  "https://firebasehosting.googleapis.com/v1beta1/projects/${PROJECT_ID}/sites?siteId=${PROJECT_ID}" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "x-goog-user-project: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d '{}' >/dev/null || true

if [[ -f "${REPO_ROOT}/firebase.json" ]]; then
  cat > "${REPO_ROOT}/.firebaserc" <<EOF
{
  "projects": {
    "default": "${PROJECT_ID}"
  }
}
EOF
fi

echo "==> [4/5] Configuring Keyless GitHub Actions OIDC (${SA_EMAIL})..."
if ! gcloud iam service-accounts describe "${SA_EMAIL}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${SA_NAME}" \
    --display-name="GitHub Actions Firebase Hosting Deployer (NexusTS)" \
    --project="${PROJECT_ID}"
  sleep 5
fi

for role in roles/firebasehosting.admin roles/firebase.viewer roles/serviceusage.serviceUsageConsumer; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="${role}" \
    --condition=None \
    --quiet >/dev/null
done

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

echo "==> [5/5] Linking GitHub Actions..."
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh variable set FIREBASE_PROJECT_ID --body "${PROJECT_ID}" -R "${GITHUB_REPO}"
  gh variable set FIREBASE_WORKLOAD_IDENTITY_PROVIDER --body "${WIF_PROVIDER}" -R "${GITHUB_REPO}"
  gh variable set FIREBASE_SERVICE_ACCOUNT --body "${SA_EMAIL}" -R "${GITHUB_REPO}"
  gh workflow run deploy-chromeos-pwa.yml -R "${GITHUB_REPO}" --ref main
  echo "    GitHub variables set & Firebase Hosting deployment triggered!"
else
  echo ""
  echo "=============================================================================="
  echo " Google Cloud + Firebase Hosting + Keyless OIDC is READY!"
  echo " Copy and paste these 2 lines into the Antigravity chat so I can trigger"
  echo " your live deployment to https://${PROJECT_ID}.web.app/ immediately:"
  echo ""
  echo "   FIREBASE_PROJECT_ID=${PROJECT_ID}"
  echo "   FIREBASE_PROJECT_NUMBER=${PROJECT_NUMBER}"
  echo "=============================================================================="
fi
