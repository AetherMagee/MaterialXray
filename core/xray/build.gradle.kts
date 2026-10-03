plugins {
    id("materialxray.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.protobuf)
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
            // A JVM project already has the java builtin; it only needs the lite flavour.
            task.builtins {
                named("java") {
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
    implementation(project(":core:root"))

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    compileOnly(libs.tomcat.annotations.api)
}
