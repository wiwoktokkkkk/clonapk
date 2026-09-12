allprojects {
    repositories {
        google()
        mavenCentral()
        // FreeReflection (dipakai mesin BlackBox) didistribusikan lewat JitPack.
        maven("https://www.jitpack.io")
    }
}

// Nilai yang dibaca modul BlackBox (Groovy) via rootProject.ext.
extra["compileSdkVersion"] = 36
extra["targetSdkVersion"] = 36
extra["minSdk"] = 24
extra["versionCode"] = 1
extra["versionName"] = "1.0"
extra["xVersion"] = "1.1.0"
extra["hiddenApiBypass"] = "4.3"

// Seluruh keluaran build diarahkan ke <proyek>/build seperti template Flutter,
// supaya `flutter clean` dan .gitignore bekerja sebagaimana mestinya.
val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
