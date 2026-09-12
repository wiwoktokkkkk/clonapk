pluginManagement {
    val flutterSdkPath: String = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
            ?: throw GradleException("flutter.sdk tidak diatur di local.properties")
    }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")

// Mesin virtual gaya Parallel Space (BlackBox, Apache-2.0 — lihat
// android/LICENSE-BLACKBOX). Bcore = inti engine; black-reflection &
// compiler = pustaka bantu refleksinya.
include(":Bcore")
include(":black-reflection")
include(":compiler")
