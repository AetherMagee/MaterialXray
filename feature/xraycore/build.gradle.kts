plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.xraycore"
}

// The Xray core page and its periodic update checks; see :core:xraycore.
dependencies {
    implementation(project(":core:xraycore"))

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.work.runtime.ktx)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
}
