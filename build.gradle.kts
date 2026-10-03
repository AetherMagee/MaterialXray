plugins {
    // Puts build-logic, and with it AGP, Kotlin and the other plugins it applies, on the root
    // classpath, so every project shares one copy instead of loading its own.
    id("materialxray.android.application") apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.sentry.android) apply false
}
