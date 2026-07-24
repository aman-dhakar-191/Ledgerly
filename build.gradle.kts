// Every "org.jetbrains.kotlin.*" plugin used anywhere in the project is declared here with
// `apply false`, even kotlin-android/kotlin-compose which only Android modules actually apply.
// They all resolve to the same underlying Kotlin Gradle Plugin jar; declaring only a subset (e.g.
// just kotlin.jvm, for the pure-Kotlin modules) causes it to be loaded once from here and once
// again per-subproject with an explicit version, which Gradle rejects with "plugin is already on
// the classpath with an unknown version, so compatibility cannot be checked." None of these
// plugin IDs need AGP to resolve — just the Kotlin Gradle Plugin artifact (Maven Central) — so
// this doesn't reintroduce the AGP problem below.
//
// The Android plugins themselves (com.android.application, com.android.library) and ksp/hilt
// deliberately stay OUT of this file and are declared directly, with explicit versions, in each
// Android module's own build.gradle.kts — putting them here would force root-project
// configuration to resolve AGP even when building only a pure-Kotlin module, and AGP cannot be
// resolved in this sandbox (network access to Google's Maven host is blocked; see the crypto
// commit for details). CI (real network/SDK) doesn't have that constraint.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}
