import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// Release signing comes from keystore.properties (local) or STRUMBUM_* env vars (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv("STRUMBUM_${key.uppercase()}")

android {
    namespace = "com.strumbum.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.strumbum.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Spec: ship arm64-v8a only at launch; the AAB serves each device its ABI.
            abiFilters += listOf("arm64-v8a")
        }

        val admobAppId = providers.gradleProperty("strumbum.admobAppId").get()
        val admobBannerId = providers.gradleProperty("strumbum.admobBannerId").get()
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Keep the Ogg reference tones uncompressed in the APK; SoundPool reads them directly.
        noCompress += "ogg"
    }
}

kotlin {
    jvmToolchain(17)
}

chaquopy {
    defaultConfig {
        // 3.13+ is Chaquopy's recommendation for Play's 16 KB page-size requirement.
        version = "3.13"
        providers.gradleProperty("strumbum.buildPython").orNull?.let { buildPython(it) }
        pip {
            // Pinned so a build never silently picks up a different binary from the package index.
            install("numpy==1.26.2")
        }
        pyc {
            src = true
        }
    }
    sourceSets {
        getByName("main") {
            // The pitch engine lives in /engine so it can be tested on its own with pytest.
            srcDir("../engine/src")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.play.services.ads)
    implementation(libs.ump)

    testImplementation(libs.junit)
}
