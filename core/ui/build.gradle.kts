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
    // Only for Ipv6SessionState's text mapping in ui/text.
    implementation(project(":core:network"))

    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
}
