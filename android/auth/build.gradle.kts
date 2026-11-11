import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// :auth extracts Doubao web credentials (Cookie + localStorage) through a WebView and persists
// them with EncryptedSharedPreferences backed by a Keystore MasterKey. No plaintext on disk.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.doubao.murmur.tv.auth"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // CredentialStore exposes AsrCredentials, therefore :asr must stay on the api surface.
    api(project(":asr"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)

    // Declared up front so pure JVM tests (URL / cookie-header parsing) need no build-file change.
    testImplementation(libs.junit4)
    // Real org.json for the JVM: the android.jar stub throws on every call, so decodePayload()
    // could not be exercised in a unit test without it. Test scope only - production keeps the
    // platform implementation.
    testImplementation(libs.json)
}

configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.files("detekt.yml"))
    source.setFrom(fileTree("src/main/kotlin") { include("**/*.kt") })
}

tasks.withType<Detekt>().configureEach {
    ignoreFailures = true
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}
