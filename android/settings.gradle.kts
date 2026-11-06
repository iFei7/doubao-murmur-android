// Gradle settings for the Android TV build of doubao-murmur.
// Single APK delivery: :ime is packaged as a library inside :app.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every repository must be declared here; modules are not allowed to add their own.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "doubao-murmur-tv"

include(":app")
include(":ime")
include(":asr")
include(":audio")
include(":auth")
include(":ui")
include(":benchmark")
