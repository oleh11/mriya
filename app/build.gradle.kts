import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: the keystore and its password live OUTSIDE the repo, in ~/.mriya-signing/keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword). CI can set MRIYA_KEYSTORE_PROPERTIES to another path.
// Without the file, release builds are simply unsigned.
// Optional Gmail connection: the builder's own Google OAuth client id (docs/GMAIL_SETUP.md), from the environment or the
// git-ignored local.properties. Without it the app builds and Gmail simply shows as unavailable.
val gmailClientId: String = System.getenv("MRIYA_GMAIL_CLIENT_ID")
    ?: Properties().apply { val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) } }.getProperty("gmail.clientId", "")

val signingProps = Properties().apply {
    val path = System.getenv("MRIYA_KEYSTORE_PROPERTIES") ?: (System.getProperty("user.home") + "/.mriya-signing/keystore.properties")
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.mriya"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mriya"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.1"
        buildConfigField("String", "GMAIL_CLIENT_ID", "\"$gmailClientId\"")
        manifestPlaceholders["gmailRedirectScheme"] =
            if (gmailClientId.isBlank()) "dev.mriya.gmail.unconfigured" else "com.googleusercontent.apps." + gmailClientId.removeSuffix(".apps.googleusercontent.com")
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")

    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Boarding-pass barcodes: ZXing is open source, pure Java, offline and free of telemetry
    // (unlike Google ML Kit, which GrapheneOS/F-Droid users avoid).
    implementation("com.google.zxing:core:3.5.3")
    // On-device screenshot OCR: open source Tesseract 5 (Apache-2.0), 100% offline, 16 KB page-size compliant.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Streaming JSON reader for Google Takeout files that can be gigabytes (pure Java, Apache-2.0, no network).
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
