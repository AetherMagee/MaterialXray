plugins {
    id("materialxray.android.library")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.material.xray.core.xray"
}

protobuf {
    protoc {
        // Match grpc-protobuf-lite's 3.x javalite runtime.
        artifact = "com.google.protobuf:protoc:3.25.8"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    compileOnly(libs.tomcat.annotations.api)
}
