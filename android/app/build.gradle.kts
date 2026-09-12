plugins {
    id "com.android.application"
    id "kotlin-android"
    id "dev.flutter.flutter-gradle-plugin"
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
            // tanpa perlu menyiapkan keystore rilis. Ganti dengan keystore sendiri
            // sebelum distribusi sungguhan.
            signingConfig = signingConfigs.debug
            minifyEnabled = false
            shrinkResources = false
        }
    }

    packagingOptions {
        // BouncyCastle menandatangani jar-nya; tanda tangan itu tidak boleh ikut
        // dikemas ulang ke APK atau pemasangan akan gagal.
        resources {
            excludes += [
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            ]
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Diperlukan mesin penandatanganan (sertifikat X.509 + blok PKCS#7 CERT.RSA).
    // Android tidak menyediakan API setara di platform SDK.
    implementation "org.bouncycastle:bcprov-jdk18on:1.81"
    implementation "org.bouncycastle:bcpkix-jdk18on:1.81"
    implementation "androidx.core:core-ktx:1.13.1"
}
