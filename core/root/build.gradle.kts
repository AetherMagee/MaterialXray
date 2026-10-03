plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.root"
}

dependencies {
    implementation(project(":core:common"))
}
