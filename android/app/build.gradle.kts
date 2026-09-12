plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.clonapk.app"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        applicationId = "com.clonapk.app"
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // Kunci debug dipakai supaya `flutter build apk --release` langsung jalan
            // tanpa menyiapkan keystore rilis. Ganti dengan keystore sendiri sebelum
            // distribusi sungguhan.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        // BouncyCastle menandatangani jar-nya; berkas tanda tangan itu tidak boleh
        // ikut dikemas ulang ke APK atau pemasangan akan gagal.
        resources {
            excludes += listOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                // BouncyCastle & jspecify sama-sama membawa manifest OSGi;
                // hanya satu yang boleh masuk APK.
                "META-INF/**/OSGI-INF/**"
            )
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Diperlukan mesin penandatanganan (sertifikat X.509 + blok PKCS#7 CERT.RSA).
    // Platform Android tidak menyediakan API setara.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    // Pustaka penandatanganan resmi (v1+v2) yang sama dengan AGP/apksigner.
    implementation("com.android.tools.build:apksig:8.11.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
