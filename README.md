# Shop Manager — Android app

The electrical shop app (billing, stock, udhaar khata, repairs, reports) as an Android app that works **without internet**, with **automatic backup to Google Drive**.

## Get the APK — pick one way

### Option A: Android Studio (on a PC/laptop)
1. Unzip this folder.
2. Android Studio → **File › Open** → select the `ShopManager` folder. Let Gradle sync finish (first time downloads ~1 GB of Android tools).
3. **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. Click **locate** in the popup — the file is `app/build/outputs/apk/debug/app-debug.apk`.
   For a release APK instead, run `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

### Option B: GitHub builds it for you (no Android Studio needed)
1. Create a new **private** repository on github.com.
2. Upload all files of this folder (keep the folder structure, including the hidden `.github` folder).
   Easiest from a PC: GitHub Desktop, or `git init && git add . && git commit -m init && git push`.
3. Open the repo's **Actions** tab → *Build APK* runs automatically (≈4 minutes).
4. Open the finished run → download **ShopManager-APK** → unzip → `ShopManager.apk`.

## Install on the shop phone
Copy the APK to the phone (WhatsApp to yourself, Drive, or USB) → tap it → allow **Install unknown apps** when asked → Install.
Works on Android 7.0 and newer.

## Google Drive backup — how it works
- **Turn it on once:** open the app → tap **Turn on backup** (or Setup › Connect Google Drive) → in the file screen, open the ☰ menu, choose **Drive**, pick a folder → **Save**.
- After that the app rewrites `ShopBackup.json` in Drive **2 minutes after any change** and **every time you leave the app**. Setup shows the last backup time.
- The Drive app must be installed and signed in (it is on almost every Android phone). If the phone is offline, Drive uploads the file when the internet comes back.
- **New / reset phone:** install the app → Setup › **Restore from backup** → choose `ShopBackup.json` from Drive → Replace & restore.
- Extra safety net: Android's own Google backup (Settings › Google › Backup) also copies the app's data automatically.

No Google Cloud project, API key or sign-in setup is needed — the app uses Android's built-in file picker to reach Drive.

## Other phone features
- **Print bill:** works with any Android print service. For Bluetooth thermal printers (58/80 mm) install a print service app such as *RawBT* from the Play Store, then choose it in the print screen.
- **WhatsApp:** bills, repair-ready messages and udhaar reminders open WhatsApp directly.
- **Save as file:** bills and CSV reports can be saved to Drive, Downloads, etc.

## Change things
- The whole app is one file: `app/src/main/assets/index.html`. Edit it and rebuild.
- App name: `app/src/main/res/values/strings.xml`. Icon: `app/src/main/res/mipmap-*/ic_launcher.png`.
- Package ID: `applicationId` in `app/build.gradle.kts` (change before publishing).

## Publishing on Play Store (optional)
The release build is signed with the standard debug key so it installs directly. For the Play Store, create your own upload key in Android Studio (**Build › Generate Signed App Bundle**) and keep that key file safe.
