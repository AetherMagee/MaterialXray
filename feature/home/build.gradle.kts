plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.home"
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.zxing.core)
}
