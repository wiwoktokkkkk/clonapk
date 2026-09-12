allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

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
