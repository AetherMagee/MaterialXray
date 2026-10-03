plugins {
    id("materialxray.android.feature")
}

android {
    namespace = "com.material.xray.feature.routing"
}

dependencies {
    implementation(project(":core:database"))

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
