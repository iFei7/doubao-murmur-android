# R8 rules for the release build (isMinifyEnabled = true).
# Keep this file intentionally small: every rule that is not strictly required hides shrinker
# regressions during the first real CI release build.

# ---------------------------------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------------------------------
# OkHttp ships its own rules for the happy path; these cover the optional platform integrations
# that reference classes missing on Android and are stripped otherwise.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------------------------
# Serializers for @Serializable classes are generated at compile time. The plugin adds companion
# serializers, so nothing reflective has to be kept here; the rules below only protect the
# runtime entry points used by the generated code.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.doubao.murmur.tv.**$$serializer { *; }
-keepclassmembers class com.doubao.murmur.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.doubao.murmur.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------------------------
# AndroidX Security / Keystore
# ---------------------------------------------------------------------------------------------
# Tink (pulled in by security-crypto) resolves provider classes reflectively.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---------------------------------------------------------------------------------------------
# Kotlin metadata used by kotlinx.coroutines debugging
# ---------------------------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Input method service is instantiated by the system through the manifest entry.
-keep class com.doubao.murmur.tv.ime.DoubaoVoiceInputMethodService { *; }
