plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.root"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.coroutines.core)
}
