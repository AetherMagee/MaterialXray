plugins {
    id("materialxray.android.library")
    id("materialxray.android.compose")
}

android {
    namespace = "com.material.xray.core.navigation"
}

dependencies {
    implementation(project(":core:ui"))

    api(libs.navigation3.runtime)
    api(libs.navigation3.ui)
    api(libs.lifecycle.viewmodel.navigation3)
}
