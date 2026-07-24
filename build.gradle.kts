// Intentionally empty: each subproject declares its own plugins with explicit
// versions (rather than the root `apply false` convention) so that building a
// single pure-Kotlin module (e.g. :core:model) does not force configuration —
// and therefore plugin resolution — of the Android modules alongside it.
