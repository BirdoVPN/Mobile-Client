# Azure Key Vault — Android Signing Setup Guide

The `release` and `release-aab` jobs in `android.yml` retrieve the Android
release keystore and all signing credentials from **Azure Key Vault** using
OIDC federation. No keystore bytes or passwords are ever stored in GitHub secrets.

---

## Architecture

```
Git tag push (android-v*)  →  GitHub Actions
  → lint + test pass

  → release job
      → azure/login (OIDC — no stored credentials)
      → az keyvault secret show ×4  (keystore, passwords, alias)
      → Gradle assembleRelease  (keystore used in-memory via env vars)
      → rm -f $RUNNER_TEMP/birdo-release.jks
      → cosign sign-blob  (Sigstore provenance layer)
      → upload APK + .sigstore bundles

  → release-aab job  (same pattern → bundleRelease → .aab)
```

---

## Step 1 — Create Azure Key Vault

```bash
RESOURCE_GROUP="birdovpn-rg"
LOCATION="eastus"
VAULT_NAME="birdovpn-keyvault"

# Create resource group (skip if it already exists)
az group create --name "$RESOURCE_GROUP" --location "$LOCATION"

# Create the Key Vault
az keyvault create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$VAULT_NAME" \
  --location "$LOCATION" \
  --sku standard \
  --enable-rbac-authorization true
```

> `--enable-rbac-authorization true` uses Azure RBAC for all access control
> (modern, auditable). Access policies are NOT used.

---

## Step 2 — Upload Keystore and Credentials

You need your release `.jks` keystore plus the associated passwords.

```bash
VAULT_NAME="birdovpn-keyvault"
KEYSTORE_PATH="/path/to/birdo-release.jks"

# Encode keystore as base64
KS_B64=$(base64 -w 0 "$KEYSTORE_PATH")

# Upload secrets
az keyvault secret set --vault-name "$VAULT_NAME" \
  --name "android-keystore-base64" --value "$KS_B64"

az keyvault secret set --vault-name "$VAULT_NAME" \
  --name "android-store-password" --value "<your-store-password>"

az keyvault secret set --vault-name "$VAULT_NAME" \
  --name "android-key-alias" --value "<your-key-alias>"

az keyvault secret set --vault-name "$VAULT_NAME" \
  --name "android-key-password" --value "<your-key-password>"
```

> After uploading, securely delete the local keystore and any notes containing
> plaintext passwords. The source of truth is now Azure Key Vault.

---

## Step 3 — Register an Azure AD App (Service Principal)

> **Skip if you already created one for Windows Trusted Signing.** You can reuse
> the same app registration across both repos — just add extra federated credentials.

```bash
APP_ID=$(az ad app create \
  --display-name "BirdoVPN GitHub Actions" \
  --query appId --output tsv)

az ad sp create --id "$APP_ID"

echo "Client ID: $APP_ID"
echo "Tenant ID: $(az account show --query tenantId --output tsv)"
```

---

## Step 4 — Add OIDC Federated Credentials

```bash
ORG_REPO="BirdoVPN/birdo-client-mobile"   # ← replace with actual org/repo

# For main-branch builds
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters "{
    \"name\": \"android-main\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"repo:${ORG_REPO}:ref:refs/heads/main\",
    \"audiences\": [\"api://AzureADTokenExchange\"]
  }"

# For tag-triggered release builds
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters "{
    \"name\": \"android-tags\",
    \"issuer\": \"https://token.actions.githubusercontent.com\",
    \"subject\": \"repo:${ORG_REPO}:ref:refs/tags/*\",
    \"audiences\": [\"api://AzureADTokenExchange\"]
  }"
```

---

## Step 5 — Grant Key Vault Access

```bash
SP_OBJECT_ID=$(az ad sp show --id "$APP_ID" --query id --output tsv)

VAULT_ID=$(az keyvault show \
  --resource-group "$RESOURCE_GROUP" \
  --name "$VAULT_NAME" \
  --query id --output tsv)

# Grant read-only access to secrets
az role assignment create \
  --role "Key Vault Secrets User" \
  --assignee-object-id "$SP_OBJECT_ID" \
  --assignee-principal-type ServicePrincipal \
  --scope "$VAULT_ID"
```

> "Key Vault Secrets User" allows reading secret values but NOT listing,
> modifying, or deleting secrets. Least-privilege.

---

## Step 6 — Configure GitHub Repository

### Secrets (Settings -> Secrets and variables -> Actions -> Secrets)

| Secret name | Value |
|---|---|
| `AZURE_TENANT_ID` | Azure AD tenant ID |
| `AZURE_CLIENT_ID` | App registration client ID |

### Variables (Settings -> Secrets and variables -> Actions -> Variables)

| Variable name | Example value |
|---|---|
| `AZURE_KEY_VAULT_NAME` | `birdovpn-keyvault` |

---

## Step 7 — Remove birdo-release.jks from Git

The file `birdo-release.jks` was previously committed to the repository.
Now that the keystore is stored in Azure Key Vault, it must be removed:

```bash
# Remove from tracking (the .gitignore *.jks rule prevents re-adding)
git rm --cached birdo-release.jks
git commit -m "chore: remove committed keystore (now in Azure Key Vault)"
git push
```

To also remove it from git history (recommended — contact maintainer for a
`git filter-repo` run if the JKS was ever accessible to external contributors):

```bash
# Install git-filter-repo first: pip install git-filter-repo
git filter-repo --invert-paths --path birdo-release.jks
git push --force-with-lease
```

> The `.gitignore` already contains `*.jks` which prevents future accidental commits.

---

## Two-Layer Signing Model

| Layer | What signs | Verified by |
|---|---|---|
| **Android Keystore** (from Azure KV) | APK / AAB content | Android OS on install |
| **Google Play App Signing** | Final distributed APK | Google Play store |
| **Sigstore (cosign)** | APK / AAB artefact | End-users via `cosign verify-blob` |

---

## Triggering a Signed Release

```bash
git tag android-v1.0.0
git push origin android-v1.0.0
```

The pipeline builds a signed release APK **and** AAB, adds Sigstore bundles,
and uploads them as GitHub Actions artifacts.

---

## Verifying the Release APK Signature

```bash
# Verify Android signing key
apksigner verify --verbose BirdoVPN-release.apk

# Verify Sigstore provenance
cosign verify-blob \
  --bundle BirdoVPN-release.apk.sigstore \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "github.com/BirdoVPN/" \
  BirdoVPN-release.apk
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `AADSTS70021: No matching federated identity record` | Subject claim mismatch | Verify the federated credential subject matches the exact ref triggered |
| `SecretNotFound` | Wrong secret name | Secret names are case-sensitive; check the four exact names in Step 2 |
| `AuthorizationFailed on Key Vault` | Missing role assignment | Re-run Step 5; wait 2–5 min for RBAC propagation |
| Gradle: `keystore file not found` | `RELEASE_STORE_FILE` env var missing | Check that the Key Vault step completed without error |
| APK installs as "Unknown sources" warning | Expected — direct APK install | Install from Google Play to avoid; direct APK needs user to allow unknown sources |
