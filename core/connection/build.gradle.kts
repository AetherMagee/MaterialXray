plugins {
    id("materialxray.jvm.library")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
    implementation(project(":core:root"))

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
