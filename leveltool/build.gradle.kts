plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
application { mainClass.set("pt.tiago.neonline.leveltool.MainKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    maxHeapSize = "1g"
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    // The pipeline tests read content/ from the project root, like the run task does.
    workingDir = rootProject.projectDir
}

// Content pipeline: the build validates the catalog, replays every certificate and only then
// exports the verified content into the app's assets.
val contentRoot = rootProject.layout.projectDirectory.dir("content")
val assetOutput = rootProject.layout.projectDirectory.dir("app/build/generated/neonAssets")

val validateCatalog = tasks.register<JavaExec>("validateCatalog") {
    group = "verification"
    description = "Validates the published catalog (structure, rules, counts)."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "1g"
    args("validate", "--root", "content", "--strict")
    inputs.dir(contentRoot)
    outputs.upToDateWhen { false }
}

val verifyCertificates = tasks.register<JavaExec>("verifyCertificates") {
    group = "verification"
    description = "Replays every certificate with the current engine."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "1g"
    args("verify-certificates", "--root", "content")
    inputs.dir(contentRoot)
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("exportGameAssets") {
    group = "build"
    description = "Exports the validated catalog into Android assets with a sha256 manifest."
    dependsOn(validateCatalog, verifyCertificates)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootProject.projectDir
    maxHeapSize = "1g"
    args("export-assets", "--root", "content", "--output", assetOutput.asFile.absolutePath)
    inputs.dir(contentRoot)
    outputs.dir(assetOutput)
}
