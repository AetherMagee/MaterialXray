plugins {
    id("materialxray.jvm.library")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))

    // SettingsRepository's setters return the edited Preferences, and the stores are injected, so
    // DataStore is part of the API.
    api(libs.datastore.preferences.core)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

// WebsiteDocsTest compares the website's provider docs against the parsers.
tasks.test {
    inputs.dir("../../website/src/content/docs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("websiteDocs")
}
