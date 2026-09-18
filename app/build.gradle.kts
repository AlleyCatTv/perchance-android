import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing. Set these environment variables (or the equivalent
// gradle properties) before running `./gradlew assembleRelease`:
//   PERCHANCE_KEYSTORE          path to a .jks/.keystore file
//   PERCHANCE_KEYSTORE_PASSWORD store password
//   PERCHANCE_KEY_ALIAS         key alias (default: perchance)
//   PERCHANCE_KEY_PASSWORD      key password (defaults to the store password)
val releaseKeystorePath: String? = System.getenv("PERCHANCE_KEYSTORE")
    ?: (project.findProperty("perchance.keystore") as String?)
val releaseKeystoreFile = releaseKeystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }
val hasReleaseKeystore = releaseKeystoreFile?.exists() == true

android {
    namespace = "org.perchance.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.perchance.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("PERCHANCE_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("perchance.keystorePassword") as String?)
                keyAlias = System.getenv("PERCHANCE_KEY_ALIAS") ?: "perchance"
                keyPassword = System.getenv("PERCHANCE_KEY_PASSWORD")
                    ?: System.getenv("PERCHANCE_KEYSTORE_PASSWORD")
                    ?: (project.findProperty("perchance.keystorePassword") as String?)
            }
        }
    }

    buildTypes {
        debug {
            // Signing debug builds with the same key as release builds keeps
            // successive APKs installable over each other (no uninstall dance).
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }

    lint {
        // A wrapper app like this can't be meaningfully "lint clean" (it
        // intentionally exposes a JS bridge, third-party cookies, etc.), and a
        // failed lint run must never be the reason a build produces no APK.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
}
