import java.util.Properties

// Root build script: declares plugin versions once and derives the app version from
// version.properties so that a single bump covers every module.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

// ---------------------------------------------------------------------------------------------
// Version derivation
// ---------------------------------------------------------------------------------------------
val versionPropertiesFile = layout.projectDirectory.file("version.properties").asFile
if (!versionPropertiesFile.isFile) {
    throw GradleException(
        "version.properties is missing at ${versionPropertiesFile.path}. " +
            "It must contain versionMajor/versionMinor/versionPatch/versionCodeOffset.",
    )
}

val versionProperties = Properties().apply {
    versionPropertiesFile.inputStream().use { load(it) }
}

fun readVersionPart(key: String): Int {
    val raw = versionProperties.getProperty(key)
        ?: throw GradleException("version.properties does not define '$key'")
    return raw.trim().toIntOrNull()
        ?: throw GradleException("version.properties entry '$key' must be an integer but was '$raw'")
}

val versionMajor = readVersionPart("versionMajor")
val versionMinor = readVersionPart("versionMinor")
val versionPatch = readVersionPart("versionPatch")
val versionCodeOffset = readVersionPart("versionCodeOffset")

// versionCode = major * 10000 + minor * 100 + patch + offset.
// minor and patch are clamped below 100 so the three fields never overlap in the digits.
if (versionMinor !in 0..99) {
    throw GradleException(
        "version.properties: versionMinor must be within 0..99 but was $versionMinor " +
            "(it occupies the two middle digits of versionCode).",
    )
}
if (versionPatch !in 0..99) {
    throw GradleException(
        "version.properties: versionPatch must be within 0..99 but was $versionPatch " +
            "(it occupies the two lowest digits of versionCode).",
    )
}
if (versionCodeOffset < 0) {
    throw GradleException("version.properties: versionCodeOffset must be >= 0 but was $versionCodeOffset")
}

val derivedVersionName = "$versionMajor.$versionMinor.$versionPatch"
val derivedVersionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch + versionCodeOffset

// CI may override both values: -PversionName=1.2.3 -PversionCode=10203
val versionNameOverride = providers.gradleProperty("versionName").orNull?.trim()?.takeIf { it.isNotEmpty() }
val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.trim()?.takeIf { it.isNotEmpty() }

val resolvedVersionName = versionNameOverride ?: derivedVersionName
val resolvedVersionCode = if (versionCodeOverride == null) {
    derivedVersionCode
} else {
    versionCodeOverride.toIntOrNull()
        ?: throw GradleException("-PversionCode must be an integer but was '$versionCodeOverride'")
}

allprojects {
    group = "com.doubao.murmur.tv"
    version = resolvedVersionName
}

// Exposed so that :app (and CI tasks) can read the same numbers without recomputing them.
extra["murmurVersionName"] = resolvedVersionName
extra["murmurVersionCode"] = resolvedVersionCode
