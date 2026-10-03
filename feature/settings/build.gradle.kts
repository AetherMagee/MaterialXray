plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.settings"
}

dependencies {
    implementation(libs.activity.compose)
    // AppCompatDelegate's per-app locale APIs.
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
}
