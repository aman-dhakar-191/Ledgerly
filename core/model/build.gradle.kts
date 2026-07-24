plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

kotlin {
    // Not jvmToolchain(21): that pins the JDK *running the compiler*, which this sandbox doesn't
    // have installed and can't download (no network to the toolchain resolver). jvmTarget alone
    // just tells whichever JDK is available (21 here, 17 in CI) to emit JVM 17 bytecode — the
    // same target :app itself uses, which matters because :app's KSP-generated Hilt factories are
    // compiled by plain javac against this module's jar, and javac refuses to read a class file
    // whose major version is newer than its own target.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// kotlin("jvm") brings its own (unused, no Java sources) compileJava/compileTestJava tasks, whose
// target Gradle otherwise infers from whichever JDK happens to be running the build — 21 here,
// 17 in CI. Pinning it keeps that in sync with compileKotlin above regardless of which JDK built it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

detekt {
    config.setFrom(file("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.truth)
}

tasks.test {
    useJUnitPlatform()
}
