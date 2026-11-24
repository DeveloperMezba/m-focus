import org.gradle.api.GradleException
import java.io.File
import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds must never silently fall back to Android's debug signing key.
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':').lowercase() }
val releaseBuildRequested = requestedTaskNames.any {
    it == "assemblerelease" ||
        it == "bundlerelease" ||
        it == "packagerelease" ||
        it == "installrelease" ||
        it == "assemble" ||
        it == "bundle" ||
        it == "build"
}

val keystorePath = providers.environmentVariable("KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
val keystoreFile = keystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }

val signingMaterialsPresent =
    !keystorePassword.isNullOrBlank() && !signingKeyAlias.isNullOrBlank() && !signingKeyPassword.isNullOrBlank()

/** The keystore must really open with the given password and alias; a corrupt key is an error, not a fallback. */
fun keystoreOpens(file: File, password: String, alias: String): Boolean = runCatching {
    for (type in listOf("PKCS12", "JKS")) {
        val store = KeyStore.getInstance(type)
        file.inputStream().use { store.load(it, password.toCharArray()) }
        if (store.containsAlias(alias)) return@runCatching true
    }
    false
}.getOrDefault(false)

val keystoreUsable =
    keystoreFile?.isFile == true &&
        keystorePassword != null &&
        signingKeyAlias != null &&
        keystoreOpens(keystoreFile, keystorePassword, signingKeyAlias)

// Signing secrets exist but the keystore cannot be opened: fail loudly instead of silently
// producing a Debug-key APK that can never update the installed app.
if (signingMaterialsPresent && !keystoreUsable) {
    throw GradleException(
        "KEYSTORE_FILE points to a keystore that cannot be opened with KEYSTORE_PASSWORD and KEY_ALIAS. " +
            "Fix the signing secrets; the build will not fall back to the debug key."
    )
}

val hasSharedSigning = signingMaterialsPresent && keystoreUsable

val releaseVersionName = providers.environmentVariable("APP_VERSION_NAME").orNull
val releaseVersionCode = providers.environmentVariable("APP_VERSION_CODE").orNull?.toIntOrNull()

if (releaseBuildRequested) {
    if (!hasSharedSigning) {
        throw GradleException(
            "Release signing is not configured. Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD."
        )
    }
    if (releaseVersionName.isNullOrBlank() || releaseVersionCode == null || releaseVersionCode <= 0) {
        throw GradleException(
            "Release version is missing. Set APP_VERSION_NAME and a positive APP_VERSION_CODE from the release tag."
        )
    }
}

android {
    namespace = "com.focusguard.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.focusguard.phone"
        minSdk = 27
        targetSdk = 34

        // Latest distributed build is v2.9 (versionCode 8). Any new distributed APK must carry a
        // higher versionCode, otherwise the in-app updater rejects it ("not newer than installed").
        versionCode = releaseVersionCode ?: 10
        versionName = releaseVersionName?.takeIf { it.isNotBlank() } ?: "2.9.1"
    }

    signingConfigs {
        create("shared") {
            if (hasSharedSigning) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasSharedSigning) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }

        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation(kotlin("test"))
}
