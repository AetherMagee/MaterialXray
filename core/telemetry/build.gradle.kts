plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.telemetry"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))

    implementation(libs.sentry.android)
}
