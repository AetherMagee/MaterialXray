plugins {
    id("materialxray.android.library")
    id("materialxray.android.compose")
}

android {
    namespace = "com.material.xray.core.ui"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:runtime"))
}
