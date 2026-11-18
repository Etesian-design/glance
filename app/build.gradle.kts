plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bompotis.etesian.glance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bompotis.etesian.glance"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Etesian builds sign with the ROM's platform key, like the launcher and Feeds: their signature permissions
    // (the launcher reads Glance, Glance reads Feeds) and the platform's (app shortcuts, notification access) then
    // apply. Without the key (any other phone) Glance still works, with those parts left out.
    val platformKey = file(System.getProperty("user.home") + "/android/etesian/keys/platform.p12")
    signingConfigs {
        if (platformKey.exists()) create("platform") {
            storeFile = platformKey
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            if (platformKey.exists()) signingConfig = signingConfigs.getByName("platform")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.swiperefresh)
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.json)
}
