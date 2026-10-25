plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "pt.tiago.neonline"
    // API 37.1: matches the reference games in the series and the CI SDK mirror.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "pt.tiago.neonline"
        minSdk = 26
        targetSdk = 36
        versionCode = 115
        versionName = "1.04.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Release signing is read from the developer's ~/.gradle/gradle.properties and never committed.
    // Without these properties (CI) the release build is produced unsigned.
    val releaseStoreFile = providers.gradleProperty("neonLine.release.storeFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = providers.gradleProperty("neonLine.release.storePassword").get()
            keyAlias = providers.gradleProperty("neonLine.release.keyAlias").get()
            keyPassword = providers.gradleProperty("neonLine.release.keyPassword").get()
        }
    }
    buildTypes {
        release {
            // R8 shrinks and optimizes the published APK; the benchmark build inherits it, so it can be
            // checked on the phone before a release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Keep the git revision out of the APK so release builds are reproducible from source.
            vcsInfo.include = false
        }
        // Declared after release so it copies the final release setup (R8 included).
        // Release-like speed for testing on the phone: not debuggable, signed with the debug key so it
        // installs over the debug build and keeps its data.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    // The dependency metadata block is encrypted for Google Play only; F-Droid rejects it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources {
        // English (default) and Portuguese only: library strings follow the same two languages, and
        // Android 13+ lists them in the system per-app language setting.
        localeFilters += listOf("en", "pt")
        generateLocaleConfig = true
    }
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("generated/neonAssets").get().asFile.path)
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.datastore)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// The packaged catalog is produced and verified by :leveltool; it is wired in as soon as content
// exists, so app-only builds keep working while content is being authored.
if (rootProject.file("content/catalog.json").exists()) {
    tasks.named("preBuild") { dependsOn(":leveltool:exportGameAssets") }
}
