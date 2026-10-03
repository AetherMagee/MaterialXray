plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.network"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:root"))

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
}
