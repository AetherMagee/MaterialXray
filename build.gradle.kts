plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.sentry.android) apply false
}
