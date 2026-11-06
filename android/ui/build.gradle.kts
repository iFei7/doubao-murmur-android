import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// :ui is the Compose for TV design system: theme, overscan-safe scaffolding, focus-scaled
// components. Compose artifacts are exposed as `api` so that :ime and :app can consume the same
// design system without re-declaring versions.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.doubao.murmur.tv.ui"
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
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.tv.material)
    api(libs.compose.material.icons.extended)

    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    debugImplementation(libs.compose.ui.tooling)
}

configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.files("detekt.yml"))
    source.setFrom(fileTree("src/main/kotlin") { include("**/*.kt") })
}

tasks.withType<Detekt>().configureEach {
    // UI modules are iterated on quickly; report findings without blocking the build.
    ignoreFailures = true
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}
