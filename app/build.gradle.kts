plugins { id("com.android.application") }
android {
    namespace = "com.blockpatch.game"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.blockpatch.game"
        minSdk = 24; targetSdk = 37
        versionCode = 2; versionName = "2.0"
    }
    androidResources { noCompress += listOf("wav", "png") }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

