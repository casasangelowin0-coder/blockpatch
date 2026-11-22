Blockpatch (native Android, Kotlin)
Build: Android Studio -> Open this folder -> Build > Build APK(s), or `gradle assembleDebug` (Gradle 8.7+, JDK 17).
Install: adb install app/build/outputs/apk/debug/app-debug.apk
Assets: app/src/main/assets/img/tiles.png (16px sprite atlas), assets/snd/*.wav (effects + music loop).
Worlds are saved in the app's private storage; player data in SharedPreferences.
Online co-op is not included (needs your own server); chat, worlds and notifications work offline.
