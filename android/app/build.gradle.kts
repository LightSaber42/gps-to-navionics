plugins {
    id("com.android.application")
}

val uploadStoreFile = providers.environmentVariable("ANCHOR_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("ANCHOR_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("ANCHOR_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("ANCHOR_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStoreFile,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.jaco.navionicsgpsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaco.navionicsgpsbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.0.0"
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("playUpload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
            isMinifyEnabled = true
            isShrinkResources = true
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

tasks.register("verifyPlayUploadSigning") {
    doLast {
        check(hasUploadSigning) {
            "Play upload signing is not configured. Run ./build-release.sh."
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn("verifyPlayUploadSigning")
}

dependencies {
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.google.zxing:core:3.5.4")

    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}
