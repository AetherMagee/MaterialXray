// JVM in the target graph, but it depends on :core:xray and :core:network, which stay Android
// libraries until Phase 3, and a JVM module cannot consume an Android one. It moves to
// materialxray.jvm.library together with them.
plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.connection"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
}
