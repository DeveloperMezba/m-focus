plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.focusguard.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.focusguard.phone"
        minSdk = 27
        targetSdk = 35
        versionCode = 5
        versionName = "2.6"
    }
    // Same signing key for every build, so a new APK installs over the old one.
    // The key comes from GitHub secrets (see workflow). Without them the default debug key is used.
    signingConfigs {
        create("shared") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            if (System.getenv("KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
