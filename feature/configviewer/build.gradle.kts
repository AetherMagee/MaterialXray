plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.configviewer"
}

dependencies {
    // ConfigViewerViewModel keeps the edited ServerEntity.
    implementation(project(":core:database"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
