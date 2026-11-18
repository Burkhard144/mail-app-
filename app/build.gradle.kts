plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.burkhard.mail"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.burkhard.mail"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        // Nur Handy-/Tablet-Prozessoren (ARM) – PC-Emulator-Teile (x86) machen die APK nur größer
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/mail.jks")
            storePassword = "mailapp2026"
            keyAlias = "mail"
            keyPassword = "mailapp2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // Bibliotheken gepackt speichern – APK zum Herunterladen deutlich kleiner
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/NOTICE.txt", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/LICENSE", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module"
            )
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    // Übersetzen direkt auf dem Handy (kostenlos, Sprachpaket wird einmalig geladen)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-tasks:18.1.0")
}
