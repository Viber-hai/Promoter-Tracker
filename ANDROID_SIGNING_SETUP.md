# Promoter Tracker — permanent APK signing setup

This project uses one permanent Android release key so future APKs can update the installed app without uninstalling it or clearing its app data.

## 1. Create the release keystore once

On a trusted computer with Java's `keytool` installed, run:

```bash
keytool -genkeypair -v -keystore promoter-tracker-release.jks -alias promoter-tracker -keyalg RSA -keysize 2048 -validity 10000
```

Choose strong passwords and keep them in a password manager. Do not upload the keystore or passwords into the repository, an issue, a pull request, or this chat. Keep at least two secure offline backups of the `.jks` file. Losing this key can prevent future updates to the app signed with it.

## 2. Encode the keystore as Base64

Linux:

```bash
base64 -w 0 promoter-tracker-release.jks > promoter-tracker-release.jks.b64
```

macOS:

```bash
base64 promoter-tracker-release.jks | tr -d '\n' > promoter-tracker-release.jks.b64
```

Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("promoter-tracker-release.jks")) | Set-Content -NoNewline promoter-tracker-release.jks.b64
```

## 3. Add four GitHub Actions secrets

Open **Settings → Secrets and variables → Actions → New repository secret**. Add:

| Secret name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Entire contents of the generated `.b64` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password chosen during key generation |
| `ANDROID_KEY_ALIAS` | `promoter-tracker` |
| `ANDROID_KEY_PASSWORD` | Password for the key alias |

Never commit either the `.jks` or `.b64` file. The workflow decodes the key only into the temporary GitHub runner and does not upload it as an artifact.

## 4. Build and install

After all four secrets exist, run the **Build APK** workflow. Release APKs use the same key each time. The workflow number supplies an increasing Android versionCode.

## Important one-time migration

Earlier APKs were built using GitHub runner debug signing. A fresh hosted runner can have a different debug certificate, so Android may not allow the first permanently signed APK to update the currently installed debug-signed app.

Before that first migration, create and verify an in-app local backup and copy it somewhere safe outside the app. If Android reports a signature conflict, uninstall the old app only once, install the permanently signed APK, and restore the backup. After this one-time migration, keep the keystore and all four secrets safe; future APKs signed with this same key should install as updates without deleting app data.

## Versioning

The Gradle build uses the GitHub Actions run number as `versionCode` for CI builds. Local builds use versionCode 1. Do not reset or replace the permanent signing key after publishing the permanently signed APK.
