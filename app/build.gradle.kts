plugins {
    id("com.android.application")
}

android {
    namespace = "com.villageshop.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.villageshop.manager"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the standard debug key so the APK installs directly on a phone.
            // For the Play Store, create your own key in Android Studio (Build > Generate Signed Bundle).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
