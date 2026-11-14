import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// :app is the single sideload APK: Leanback launcher entry point, login/enable-IME/mic-permission
// flows and diagnostics. The IME service itself comes from :ime (manifest merged into this module).

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// Version numbers are derived once by the root project (version.properties + -P overrides).
val murmurVersionName: String = rootProject.extra["murmurVersionName"] as String
val murmurVersionCode: Int = rootProject.extra["murmurVersionCode"] as Int

// Build metadata injected into BuildConfig. Nothing secret is ever compiled into the APK.
val buildChannel: String = providers.gradleProperty("channel").orElse("dev").get()
val gitSha: String = providers.environmentVariable("GIT_SHA").orElse("local").get()

// Release signing material always comes from the environment; keystores and passwords are never
// committed. When any of the four variables is missing the release build stays unsigned and the
// checkReleaseSigning gate below stops `assembleRelease` / `bundleRelease`.
// Local names are prefixed with `release` so they never shadow the SigningConfig properties of
// the same meaning inside the `signingConfigs { create(...) }` block below.
val releaseStorePath: String? =
    providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? =
    providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? =
    providers.environmentVariable("ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? =
    providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }

val hasReleaseSigning =
    listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

// Escape hatch for CI jobs that only need a compile check of a release variant (macrobenchmark
// target APK, lint smoke runs) and have no release keystore. Signing stays mandatory by default.
val allowUnsignedRelease: Boolean =
    providers.gradleProperty("allowUnsignedRelease").orNull?.toBoolean() ?: false

android {
    namespace = "com.doubao.murmur.tv.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.doubao.murmur.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = murmurVersionCode
        versionName = murmurVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Protocol constants are owned by :asr; these copies exist so that non-ASR code
        // (diagnostics, about screen) can display them without touching the network layer.
        buildConfigField(
            "String",
            "ASR_WSS_ENDPOINT",
            "\"wss://ws-samantha.doubao.com/samantha/audio/asr\"",
        )
        buildConfigField("String", "DOUBAO_ORIGIN", "\"https://www.doubao.com\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_CHANNEL", "\"$buildChannel\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // TV channels sideload the APK/AAB directly, so the split APK must keep every language
    // resource: an install without the matching split would show raw resource ids.
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
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
    implementation(project(":ime"))
    implementation(project(":ui"))
    implementation(project(":asr"))
    implementation(project(":audio"))
    implementation(project(":auth"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// Release artifacts must be signed: fail fast and tell the operator exactly which environment
// variables are missing instead of silently producing an unsigned bundle.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Verifies that the release signing environment variables are present."
    doLast {
        val missing = buildList {
            if (releaseStorePath == null) add("ANDROID_KEYSTORE_PATH")
            if (releaseStorePassword == null) add("ANDROID_KEYSTORE_PASSWORD")
            if (releaseKeyAlias == null) add("ANDROID_KEY_ALIAS")
            if (releaseKeyPassword == null) add("ANDROID_KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            if (allowUnsignedRelease) {
                logger.warn(
                    "Release signing is not configured (missing: ${missing.joinToString(", ")}); " +
                        "continuing because -PallowUnsignedRelease=true was passed. " +
                        "The resulting artifact is NOT installable on a device.",
                )
            } else {
                throw GradleException(
                    "Release signing is not configured. Missing environment variable(s): " +
                        "${missing.joinToString(", ")}. " +
                        "Export all four variables, run assembleDebug for local checks, or pass " +
                        "-PallowUnsignedRelease=true for a compile-only build.",
                )
            }
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(checkReleaseSigning)
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
