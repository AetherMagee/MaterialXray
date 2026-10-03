plugins {
    id("materialxray.android.library")
    id("com.google.devtools.ksp")
    // DatabaseMigrationChainTest decodes the exported schema JSON.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.material.xray.core.database"
}

// Room exports one JSON schema per database version. They are committed so that
// DatabaseMigrationChainTest can replay the migration chain and compare the result against the
// schema Room generates from the entities.
val roomSchemaDirectory = layout.projectDirectory.dir("schemas")

ksp {
    arg("room.schemaLocation", roomSchemaDirectory.asFile.path)
}

tasks.withType<Test>().configureEach {
    systemProperty("room.schemaLocation", roomSchemaDirectory.asFile.path)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // AppDatabase extends RoomDatabase and the DAOs return Flows, so both are part of the API.
    api(libs.room.runtime)
    api(libs.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(libs.serialization.json)
    testImplementation(libs.sqlite.jdbc)
}
