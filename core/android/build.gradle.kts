plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.android"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.androidx.annotation)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
}
