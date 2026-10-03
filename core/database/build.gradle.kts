plugins {
    id("materialxray.jvm.library")
    id("com.google.devtools.ksp")
}

// Room exports one JSON schema per database version. They are committed so that
// DatabaseMigrationChainTest can replay the migration chain and compare the result against the
// schema Room generates from the entities.
val roomSchemaDirectory = layout.projectDirectory.dir("schemas")

ksp {
    arg("room.schemaLocation", roomSchemaDirectory.asFile.path)
}

// KSP adds Room's generated implementations to the main source set; only hand-written code is analysed.
tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude { it.file.path.contains("/build/generated/") }
}

tasks.withType<Test>().configureEach {
    systemProperty("room.schemaLocation", roomSchemaDirectory.asFile.path)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // AppDatabase extends RoomDatabase, the DAOs return Flows and the migrations and callbacks
    // receive driver connections, so all three are part of the API.
    api(libs.room.runtime)
    api(libs.sqlite)
    api(libs.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(libs.sqlite.bundled)
}
