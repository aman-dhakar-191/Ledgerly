// Intentionally empty: each subproject declares its own plugins with explicit versions (rather
// than the root `apply false` convention) so that building a single pure-Kotlin module (e.g.
// :core:model) does not force configuration — and therefore plugin resolution — of the Android
// modules alongside it, and AGP cannot be resolved in this sandbox (network access to Google's
// Maven host is blocked).
//
// Declaring any org.jetbrains.kotlin.* plugin here as `apply false` looks like the right fix for
// Gradle's "Kotlin Gradle plugin was loaded multiple times" warning, but it isn't: the first real
// CI run showed that root pre-loading kotlin.jvm collides with :app's explicit-version
// kotlin.android declaration ("plugin is already on the classpath with an unknown version"), and
// pre-loading kotlin.android itself at root fails a different way — it eagerly instantiates
// KotlinAndroidTarget, which needs AGP classes that don't exist in root's own classpath since
// root itself never applies com.android.*, so it dies with
// `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`. The duplicate-plugin-load
// warning is cosmetic; both of those failures are not. Leave this file alone.
