plugins {
    id("materialxray.jvm.library")
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.coroutines.core)
}
