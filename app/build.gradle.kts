plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.blockpatch.game"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.blockpatch.game"
        minSdk = 24; targetSdk = 34
        versionCode = 1; versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
