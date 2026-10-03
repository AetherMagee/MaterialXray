plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.configviewer"
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
