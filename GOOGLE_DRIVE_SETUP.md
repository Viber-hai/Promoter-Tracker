# Google Drive backup — one-time setup

Promoter Tracker keeps its existing local encrypted backups. Google Drive is optional and uses the **same automatic backup frequency** (daily, weekly, monthly, or yearly) as the local destination. Each destination has its own enable/disable switch.

## Your Google Cloud steps (no passwords or signing keys to share)

1. Open https://console.cloud.google.com/ and create a project (for example **Promoter Tracker Backup**).
2. Go to **APIs & Services → Library**. Search for **Google Drive API** and select **Enable**.
3. Go to **Google Auth Platform** (or **APIs & Services → OAuth consent screen**) and configure the app name and support email.
   - For personal testing, choose **External** and add your Google account as a **test user** if the project is in Testing.
   - Add the Drive **appDataFolder** scope: `https://www.googleapis.com/auth/drive.appdata`. This is limited to private app-created Drive data, not all Drive files.
4. Go to **Clients → Create client → Android**.
   - Package name: `com.promoter.tracker`
   - SHA-1: use the **SHA-1 certificate fingerprint from the signed release APK** in the GitHub Actions build's **Verify APK signature** step. Use the **original release signing certificate**, not a debug keystore. The workflow uses the existing release signing secrets.
5. Save the Android OAuth client. You do **not** need to copy your keystore, passwords, or any Google account password into GitHub or ChatGPT.
6. Install the newly built release APK, open **Backup & Restore → Connect Google Drive**, and approve access with the Google account listed as a test user.
7. Set a **new-phone recovery password** before making a Drive backup if you may need to restore on another device. Google Drive storage does not replace this encryption password.
8. Test **Back up to Drive → Find Drive backups → Restore selected Drive backup**. Then enable **Include Google Drive in automatic backups** and save the backup settings.

## Important behavior

- Drive backups are encrypted `.ptbackup` files stored in the private Drive **appDataFolder**. They will not appear as ordinary files in My Drive.
- Drive and local automatic backups share the same frequency, but each can be switched on or off separately.
- Android WorkManager runs scheduled backups opportunistically; battery management, connectivity, and Google authentication can delay or prevent a background upload. Check the **Google Drive backup status** in the app and test a manual upload first.
- Google OAuth must be configured in the Google Cloud project before Google sign-in works. Until then, local backups remain available.
- The Google account may need to reauthorize Drive access if Google revokes the token or changes the account state.
- This feature requires a real Android device with Google Play services; the browser HTML preview cannot access Google sign-in.
