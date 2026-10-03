import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JAVA_VERSION
    targetCompatibility = JAVA_VERSION
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(JAVA_VERSION.toString()))
    }
}

configureKoin()
configureQuality()
configureUnitTests()

dependencies {
    implementation(libs.lib("coroutines-core"))
    implementation(libs.lib("serialization-json"))
}
