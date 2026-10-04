plugins {
    id("materialxray.jvm.library")
}

// Downloading and installing Xray cores. Kept out of every other module so a store build that may
// not fetch executables can drop it, with :feature:xraycore, from settings.gradle.kts and :app.
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))

    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
}
