import com.android.build.api.dsl.CommonExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

/** The bytecode level every module targets: AGP's default, which `:app` has always compiled to. */
internal val JAVA_VERSION = JavaVersion.VERSION_11

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

/** Settings every Android module shares, application or library. */
internal fun Project.configureAndroidCommon() {
    extensions.configure<CommonExtension>("android") {
        compileSdk = 37
        compileSdkMinor = 0
        buildToolsVersion = "37.0.0"
        // :core:runtime builds its launcher with it and :app's buildXray compiles Xray with its clang.
        ndkVersion = "30.0.16248370"

        defaultConfig.minSdk = 24

        compileOptions.sourceCompatibility = JAVA_VERSION
        compileOptions.targetCompatibility = JAVA_VERSION
        compileOptions.isCoreLibraryDesugaringEnabled = true

        // Analysing the test sources costs more than the rest of the build put together, and it
        // reruns on every main-source edit because the test classes depend on them. detekt and
        // ktlint already cover the test sources, and the Android-specific checks lint adds are
        // about shipped code.
        lint.ignoreTestSources = true
    }
    dependencies.add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs-nio"))
}

/** detekt and ktlint, configured from the root `config/detekt/detekt.yml` and `.editorconfig`. */
internal fun Project.configureQuality() {
    pluginManager.apply("dev.detekt")
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        basePath.set(rootDir)
    }

    extensions.configure<KtlintExtension> {
        ignoreFailures.set(false)
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.HTML)
        }
        filter {
            // KSP (Room) and protobuf/grpc write Kotlin/Java into build/generated;
            // those are attached to the source sets, so exclude them from linting.
            exclude { element -> element.file.path.contains("/generated/") }
        }
    }

    // Formatting is a source mutation, so it does not belong on the path that compiles the sources:
    // rewriting a file mid-build invalidates the up-to-date checks of everything downstream, and it
    // races an editor that has the same file open. The prek hook formats on commit, and `check`
    // still enforces it here.
    tasks.named("check") {
        dependsOn("ktlintFormat")
    }
}

/** The Koin compiler plugin and the annotations it reads. */
internal fun Project.configureKoin() {
    pluginManager.apply("io.insert-koin.compiler.plugin")
    dependencies.add("implementation", dependencies.platform(libs.lib("koin-bom")))
    dependencies.add("implementation", libs.lib("koin-core"))
    dependencies.add("implementation", libs.lib("koin-annotations"))
}

/** JUnit 4 and coroutines-test, which every module's unit tests use. */
internal fun Project.configureUnitTests() {
    dependencies.add("testImplementation", libs.lib("junit"))
    dependencies.add("testImplementation", libs.lib("coroutines-test"))
}
