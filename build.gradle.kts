// Only the plugins used by the pure-Kotlin modules (:core:model, :core:crypto-engine) are
// declared here with `apply false`, so Gradle loads the Kotlin plugin once and shares it instead
// of loading it once per subproject. The Android plugins (com.android.*, kotlin-android, hilt,
// ksp) deliberately stay OUT of this file and are declared directly, with explicit versions, in
// each Android module's own build.gradle.kts — putting them here would force root-project
// configuration to resolve AGP even when building only a pure-Kotlin module, and AGP cannot be
// resolved in this sandbox (network access to Google's Maven host is blocked; see the crypto
// commit for details).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}
