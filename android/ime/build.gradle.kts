import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// :ime hosts DoubaoVoiceInputMethodService and the remote-friendly keyboard UI. It is delivered
// as a library inside :app so that a single APK keeps one versionCode and one distribution path.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.doubao.murmur.tv.ime"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
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
    // Project dependencies are `api` so that :app sees the shared types these modules expose.
    api(project(":ui"))
    api(project(":asr"))
    api(project(":audio"))
    api(project(":auth"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.tv.material)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ImeComposeView uses SavedStateRegistryOwner / setViewTreeSavedStateRegistryOwner directly.
    // It is already reachable through activity -> lifecycle-viewmodel-savedstate -> savedstate,
    // but a directly imported API must be declared directly (same 1.2.1 version, no conflict).
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
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
