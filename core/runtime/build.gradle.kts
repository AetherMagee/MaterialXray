plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.runtime"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:root"))
    implementation(project(":core:network"))
    implementation(project(":core:connection"))
    implementation(project(":core:android"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:telemetry"))
}
