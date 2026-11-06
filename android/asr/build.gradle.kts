import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

// :asr is a plain Kotlin/JVM module on purpose: the Doubao WSS protocol layer has zero Android
// dependencies, which keeps `gradle :asr:test` runnable on a bare CI runner.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // okhttp and coroutines are part of the published surface (DoubaoAsrEngine takes an
    // OkHttpClient and exposes Flow), so they must stay on the compile classpath of consumers.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.logging.interceptor)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.files("detekt.yml"))
    source.setFrom(fileTree("src/main/kotlin") { include("**/*.kt") })
}

tasks.withType<Detekt>().configureEach {
    // :asr is pure logic and fully unit tested, so detekt is a hard gate here.
    ignoreFailures = false
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}
