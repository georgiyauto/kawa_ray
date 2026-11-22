plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "pro.vinnikov.kawavpn"
    compileSdk = 37
    defaultConfig {
        applicationId = "pro.vinnikov.kawavpn"
        minSdk = 26; targetSdk = 37
        versionCode = 438; versionName = "0.4.38"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    val keyPath = providers.environmentVariable("KAWA_ANDROID_KEYSTORE").orNull
    val passwordPath = providers.environmentVariable("KAWA_ANDROID_PASSWORD_FILE").orNull
    if (keyPath != null && passwordPath != null) {
        signingConfigs.create("kawaRelease") {
            storeFile = file(keyPath)
            storePassword = file(passwordPath).readText().trim()
            keyAlias = "kawa-android"
            keyPassword = storePassword
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("kawaRelease") } }
}
if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } &&
    (providers.environmentVariable("KAWA_ANDROID_KEYSTORE").orNull.isNullOrBlank() || providers.environmentVariable("KAWA_ANDROID_PASSWORD_FILE").orNull.isNullOrBlank())) {
    throw GradleException("Release APK requires a persistent Kawa signing key. See android/README.md; keys must remain outside source.")
}
// Also cover task aliases and aggregate tasks (for example `build`), whose
// requested names do not contain "Release". No unsigned release may be emitted.
val verifyKawaReleaseSigning = tasks.register("verifyKawaReleaseSigning") {
    doLast {
        check(!providers.environmentVariable("KAWA_ANDROID_KEYSTORE").orNull.isNullOrBlank() &&
            !providers.environmentVariable("KAWA_ANDROID_PASSWORD_FILE").orNull.isNullOrBlank()) {
            "Release APK requires both persistent Kawa signing inputs; see android/README.md"
        }
        check(android.buildTypes.getByName("release").signingConfig != null) { "Release signing configuration is missing" }
    }
}
tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease", "assembleRelease")) dependsOn(verifyKawaReleaseSigning)
}
dependencies {
    implementation(project(":domain"))
    implementation(files("libs/libXray.aar"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("io.github.kyant0:backdrop:2.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
