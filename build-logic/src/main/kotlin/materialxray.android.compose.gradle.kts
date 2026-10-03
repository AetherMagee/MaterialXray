import com.android.build.api.dsl.CommonExtension

// Applied on top of materialxray.android.library or materialxray.android.application. It does not
// apply an Android plugin itself, so the configurations are referenced by name.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<CommonExtension>("android") {
    buildFeatures.compose = true
}

dependencies {
    "implementation"(platform(libs.lib("compose-bom")))
    "implementation"(libs.lib("compose-material3"))
    "implementation"(libs.lib("compose-material3-adaptive"))
    "implementation"(libs.lib("compose-ui"))
    "implementation"(libs.lib("compose-ui-tooling-preview"))
    "implementation"(libs.lib("compose-icons-extended"))
    "debugImplementation"(libs.lib("compose-ui-tooling"))

    "implementation"(platform(libs.lib("koin-bom")))
    "implementation"(libs.lib("koin-compose"))
    "implementation"(libs.lib("koin-compose-viewmodel"))

    "implementation"(libs.lib("lifecycle-runtime-compose"))
    "implementation"(libs.lib("lifecycle-viewmodel-compose"))
}
