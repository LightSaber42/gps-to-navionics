plugins {
    id("com.android.application")
}

android {
    namespace = "com.jaco.navionicsgpsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaco.navionicsgpsbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
