plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (CI secrets), never from a committed
// keystore. Locally, these are unset and release builds stay unsigned - that's fine,
// only CI needs to produce an installable release APK. See .github/workflows/release.yml.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")

android {
    namespace = "dev.steinhorst.backbuttonhero"
    compileSdk = 34

    defaultConfig {
        // The one place to change on a rebrand (Android's actual "package name").
        // app_name in strings.xml is the other.
        applicationId = "dev.steinhorst.backbuttonhero"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
}

