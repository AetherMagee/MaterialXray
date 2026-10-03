plugins {
    id("materialxray.android.library")
}

android {
    namespace = "com.material.xray.core.runtime"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    // XrayProcessSupervisor's rootless launcher and the root TUN exec helper.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
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
    // Notification strings and icons.
    implementation(project(":core:ui"))

    implementation(libs.core.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
}
