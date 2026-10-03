plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.android"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))

    implementation(libs.androidx.annotation)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp)
}
