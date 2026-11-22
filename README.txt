Build the APK
1. Android Studio -> Open -> select this folder, let Gradle sync.
2. Build > Build APK(s)  (debug APK lands in app/build/outputs/apk/debug/).
   Or in a terminal with Gradle 8.7+: gradle assembleDebug
3. Install: adb install app/build/outputs/apk/debug/app-debug.apk
Game files live in app/src/main/assets/index.html - replace it to update the game.
Online co-op needs your own backend (see connect() in index.html); the app is offline-only otherwise.
