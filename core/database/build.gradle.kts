plugins {
    id("materialxray.android.library")
    id("com.google.devtools.ksp")
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

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.sqlite.jdbc)
}
