plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.logs"
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
}
