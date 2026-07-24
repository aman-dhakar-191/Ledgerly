plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom(file("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
}

android {
    namespace = "com.amandhakar.ledgerly.crypto.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:crypto-engine"))

    implementation(libs.core.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.biometric)
    implementation(libs.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
