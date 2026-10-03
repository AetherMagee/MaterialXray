plugins {
    id("materialxray.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.material.xray.core.data"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:xray"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))

    // SettingsRepository's setters return the edited Preferences, so DataStore is part of the API.
    api(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
