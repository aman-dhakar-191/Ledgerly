plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

kotlin {
    // See core/model/build.gradle.kts for why this is jvmTarget, not jvmToolchain(21).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

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
