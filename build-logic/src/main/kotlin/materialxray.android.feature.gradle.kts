plugins {
    id("materialxray.android.library")
    id("materialxray.android.compose")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
    implementation(project(":core:connection"))
    implementation(project(":core:android"))
    implementation(project(":core:data"))
    implementation(project(":core:runtime"))
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
}
