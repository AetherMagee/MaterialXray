plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.data"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
    implementation(project(":core:android"))
    implementation(project(":core:database"))
}
