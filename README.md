# Promoter Tracker – Android app

Your tracker wrapped as a native Android app (WebView). The HTML lives in
`app/src/main/assets/index.html`. Data is saved on the phone (localStorage).

## Option A – Android Studio (easiest on a PC)
1. Install Android Studio and unzip this project.
2. File > Open > select the `PromoterTracker` folder, wait for Gradle sync.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Find it at `app/build/outputs/apk/debug/app-debug.apk`, send it to your phone and install.
   (Or plug in your phone with USB debugging and press Run.)

## Option B – No install, build online with GitHub (free)
1. Create a free GitHub account and a new repository.
2. Upload ALL files of this project (keep the `.github` folder).
3. Open the repo > Actions tab > "Build APK" > wait ~3 minutes.
4. Open the finished run, download the `PromoterTracker-apk` artifact, unzip, install the APK.
   (Allow "Install unknown apps" on your phone when asked.)

## Editing later
Change `app/src/main/assets/index.html` (for example the RRPs or targets), then rebuild.


## Windows desktop version

A Windows desktop build is available through the **Build Windows installer** workflow:
1. Open the repository's **Actions** tab.
2. Select **Build Windows installer** and open the latest successful run.
3. Download the `PromoterTracker-Windows-Installer` artifact and run `PromoterTracker-Setup.exe`.

The installer installs the Microsoft Edge WebView2 Runtime and the desktop app. The Windows version uses the same tracker page and saves data locally in your Windows user profile. It does not automatically sync data with Android or another device. The wider desktop layout arranges model rows in columns on larger screens. The Android APK workflow remains separate.
