import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("materialxray.android.application")
    id("materialxray.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.sentry.android)
}

@CacheableTask
abstract class GenerateLegalAssets @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val projectLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyLicenses: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val xrayLicense: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xrayMetadata: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        fileSystemOperations.sync {
            into(outputDirectory)
            from(thirdPartyNotices) {
                into("legal")
            }
            from(projectLicense) {
                into("legal/licenses")
                rename { "GPL-3.0-or-later.txt" }
            }
            from(thirdPartyLicenses) {
                into("legal/licenses")
            }
            from(xrayLicense) {
                into("legal/licenses")
                rename { "MPL-2.0.txt" }
            }
            from(xrayMetadata) {
                exclude("LICENSE")
                into("legal/xray")
            }
        }
    }
}

abstract class ValidateReleaseTelemetry : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @TaskAction
    fun validate() {
        require(configured.get()) {
            "SENTRY_AUTH_TOKEN is required to build a release artifact with symbolication support"
        }
    }
}

abstract class DownloadGeoData : DefaultTask() {
    @get:Input
    abstract val geoipUrl: Property<String>

    @get:Input
    abstract val geositeUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val output = outputDirectory.get().asFile.apply { mkdirs() }
        fetch(geoipUrl.get(), File(output, "geoip.dat"))
        fetch(geositeUrl.get(), File(output, "geosite.dat"))
    }

    private fun fetch(url: String, target: File) {
        val temporary = File(target.parentFile, "${target.name}.download")
        try {
            URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }.getInputStream().use { input ->
                temporary.outputStream().use { outputStream -> input.copyTo(outputStream) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            check(temporary.length() > 1024L) { "Downloaded ${target.name} is unexpectedly small" }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            logger.lifecycle("Bundled ${target.name}: SHA-256 $actualSha256")
            check(temporary.renameTo(target)) { "Unable to install ${target.name}" }
        } finally {
            temporary.delete()
        }
    }
}

/**
 * Downloads the official Android Xray builds recorded in `third_party/xray` and unpacks each one as
 * `<abi>/libxray.so`. Both the release archive and the executable inside it must match
 * `CHECKSUMS.sha256`, so a build can only package the binaries `change-xray-ver.sh` verified.
 */
@CacheableTask
abstract class DownloadXray : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    /** Release archive name without the `.zip` extension, by ABI. */
    @get:Input
    abstract val archives: MapProperty<String, String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksums: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val expectedSha256 = checksums.get().asFile.readLines()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .filter { it.size == 2 }
            .associate { (sha256, name) -> name to sha256 }
        fun expected(name: String) = requireNotNull(expectedSha256[name]) {
            "third_party/xray/CHECKSUMS.sha256 has no entry for $name; run scripts/change-xray-ver.sh"
        }

        val output = outputDirectory.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        archives.get().forEach { (abi, archiveName) ->
            val archive = File(temporaryDir, "$archiveName.zip")
            try {
                val url = "https://github.com/XTLS/Xray-core/releases/download/${version.get()}/$archiveName.zip"
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }.getInputStream().use { input ->
                    archive.outputStream().use { outputStream -> input.copyTo(outputStream) }
                }
                check(sha256(archive) == expected("$archiveName-${version.get()}.zip")) {
                    "SHA-256 mismatch for $archiveName.zip"
                }
                val binary = File(output, "$abi/libxray.so").apply { parentFile.mkdirs() }
                ZipFile(archive).use { zip ->
                    val entry = checkNotNull(zip.getEntry("xray")) { "$archiveName.zip has no xray executable" }
                    zip.getInputStream(entry).use { input ->
                        binary.outputStream().use { outputStream -> input.copyTo(outputStream) }
                    }
                }
                check(sha256(binary) == expected("lib/$abi/libxray.so")) {
                    "SHA-256 mismatch for the xray executable in $archiveName.zip"
                }
                binary.setExecutable(true)
            } finally {
                archive.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Builds Xray as `<abi>/libxray.so` for an ABI upstream publishes no Android build for. It checks
 * out the commit recorded in `third_party/xray/COMMIT` (a Git commit id pins its whole tree) and
 * builds it the way upstream's release workflow builds the Android ones: cgo through the NDK's clang,
 * with upstream's Go toolchain and flags. Needs Go 1.21 or newer on `PATH`, which fetches the pinned
 * toolchain itself.
 */
@CacheableTask
abstract class BuildXray @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val commit: Property<String>

    @get:Input
    abstract val goToolchain: Property<String>

    @get:Input
    abstract val abi: Property<String>

    /** `GOARCH`, `GOARM` and the like for [abi]. */
    @get:Input
    abstract val goEnvironment: MapProperty<String, String>

    /** The NDK clang driver for [abi], such as `armv7a-linux-androideabi24-clang`. */
    @get:Input
    abstract val clang: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    // Located by ndkVersion, which is the cache key; the path differs between machines.
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val source = File(temporaryDir, "source").apply {
            deleteRecursively()
            mkdirs()
        }
        fun git(vararg args: String) {
            execOperations.exec {
                workingDir = source
                commandLine("git", *args)
            }
        }
        git("init", "--quiet")
        git("fetch", "--quiet", "--depth", "1", "https://github.com/XTLS/Xray-core.git", commit.get())
        git("checkout", "--quiet", "FETCH_HEAD")

        val output = outputDirectory.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val binary = File(output, "${abi.get()}/libxray.so")
        val os = System.getProperty("os.name").lowercase()
        val hostTag = when {
            os.startsWith("windows") -> "windows-x86_64"
            os.startsWith("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val clangPath = ndkDirectory.get().asFile
            .resolve("toolchains/llvm/prebuilt/$hostTag/bin/${clang.get()}${if (hostTag.startsWith("windows")) ".cmd" else ""}")
        check(clangPath.isFile) { "NDK clang not found at $clangPath" }
        try {
            execOperations.exec {
                workingDir = source
                environment(goEnvironment.get())
                environment("GOOS", "android")
                environment("CGO_ENABLED", "1")
                environment("CC", clangPath.absolutePath)
                environment("GOTOOLCHAIN", goToolchain.get())
                // Settings that would change the binary without changing the cache key. Empty
                // CGO_*FLAGS fall back to Go's defaults.
                listOf("GOFLAGS", "GOEXPERIMENT", "GOFIPS140", "CGO_CFLAGS", "CGO_CPPFLAGS", "CGO_LDFLAGS")
                    .forEach { environment(it, "") }
                commandLine(
                    "go", "build",
                    "-o", binary.absolutePath,
                    "-trimpath",
                    "-buildvcs=false",
                    "-gcflags=all=-l=4",
                    "-ldflags=-X github.com/xtls/xray-core/core.build=${commit.get().take(7)} -s -w -buildid= -checklinkname=0",
                    "./main",
                )
            }
        } finally {
            source.deleteRecursively()
        }
        logger.lifecycle("Built ${abi.get()} Xray from ${commit.get()} with ${goToolchain.get()}")
    }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? = localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH")
    .orElse(providers.gradleProperty("releaseKeystorePath"))
    .orNull
    ?: localProperty("releaseKeystorePath")
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS")
    .orElse(providers.gradleProperty("releaseKeyAlias"))
    .orNull
    ?: localProperty("releaseKeyAlias")
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD")
    .orElse(providers.gradleProperty("releaseKeyPassword"))
    .orNull
    ?: localProperty("releaseKeyPassword")
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD")
    .orElse(providers.gradleProperty("releaseStorePassword"))
    .orNull
    ?: localProperty("releaseStorePassword")
val sentryAuthToken = providers.environmentVariable("SENTRY_AUTH_TOKEN").filter { it.isNotBlank() }
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeyAlias,
    releaseKeyPassword,
    releaseStorePassword,
).all { !it.isNullOrBlank() }
val xrayMetadataDirectory = rootProject.layout.projectDirectory.dir("third_party/xray")
val generateLegalAssets = tasks.register<GenerateLegalAssets>("generateLegalAssets") {
    projectLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
    thirdPartyNotices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    thirdPartyLicenses.set(rootProject.layout.projectDirectory.dir("third_party/licenses"))
    xrayLicense.set(rootProject.layout.projectDirectory.file("third_party/xray/LICENSE"))
    xrayMetadata.set(xrayMetadataDirectory)
    outputDirectory.set(layout.buildDirectory.dir("generated/legalAssets"))
}
val downloadGeoData = tasks.register<DownloadGeoData>("downloadGeoData") {
    geoipUrl.set("https://github.com/v2fly/geoip/releases/latest/download/geoip.dat")
    geositeUrl.set("https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat")
    outputDirectory.set(layout.buildDirectory.dir("generated/geodataAssets"))
    outputs.upToDateWhen { false }
}
val downloadXray = tasks.register<DownloadXray>("downloadXray") {
    version.set(providers.fileContents(xrayMetadataDirectory.file("VERSION")).asText.map(String::trim))
    archives.put("arm64-v8a", "Xray-android-arm64-v8a")
    archives.put("x86_64", "Xray-android-amd64")
    checksums.set(xrayMetadataDirectory.file("CHECKSUMS.sha256"))
    outputDirectory.set(layout.buildDirectory.dir("generated/xrayJniLibs/download"))
}
val buildXray = tasks.register<BuildXray>("buildXray") {
    commit.set(providers.fileContents(xrayMetadataDirectory.file("COMMIT")).asText.map(String::trim))
    goToolchain.set(providers.fileContents(xrayMetadataDirectory.file("GO_TOOLCHAIN")).asText.map(String::trim))
    abi.set("armeabi-v7a")
    goEnvironment.put("GOARCH", "arm")
    goEnvironment.put("GOARM", "7")
    clang.set("armv7a-linux-androideabi${android.defaultConfig.minSdk}-clang")
    ndkVersion.set(android.ndkVersion)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    outputDirectory.set(layout.buildDirectory.dir("generated/xrayJniLibs/build"))
}
val validateReleaseTelemetry = tasks.register<ValidateReleaseTelemetry>("validateReleaseTelemetry") {
    configured.set(sentryAuthToken.map { true }.orElse(false))
}

android {
    namespace = "com.material.xray"

    // Strings and drawables live in :core:ui, but only :app sees every module that uses them.
    // Linting the dependencies as one project keeps UnusedResources and similar cross-module
    // checks working; a library's own lint skips them.
    lint.checkDependencies = true

    defaultConfig {
        applicationId = "com.material.xray"
        targetSdk = 36
        versionCode = 940
        versionName = "0.9.4"
    }

    // A universal APK plus one per ABI, all with the same versionCode so an install can move
    // between them. The release workflow names them so GitHub lists the universal one first.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Xray ships stripped; packaging it untouched keeps the APK's copy byte-identical to
            // the official executable CHECKSUMS.sha256 lists.
            keepDebugSymbols += "**/libxray.so"
            // Splits do not filter the universal APK. Libraries that bring an x86 build would add a
            // lib/x86 without Xray, which an x86 device would pick over the ARM translation layer.
            excludes += "lib/x86/**"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

sentry {
    org.set("materialxray")
    projectName.set("materialxray")
    // Debug builds do not upload mappings, and Sentry's AGP 9.4 optimization probe logs a stack trace.
    ignoredBuildTypes.add("debug")
    authToken.set(sentryAuthToken)
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
    autoUploadProguardMapping.set(sentryAuthToken.map { true }.orElse(false))
    includeSourceContext.set(sentryAuthToken.map { true }.orElse(false))
    uploadNativeSymbols.set(sentryAuthToken.map { true }.orElse(false))
    includeNativeSources.set(sentryAuthToken.map { true }.orElse(false))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateLegalAssets,
            GenerateLegalAssets::outputDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadGeoData,
            DownloadGeoData::outputDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            downloadXray,
            DownloadXray::outputDirectory,
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildXray,
            BuildXray::outputDirectory,
        )
    }
}

// Only the release artifacts, not every release task: `packageReleaseResources` is also part of
// the release compilation that detekt's type resolution and lint run on.
tasks.matching { task ->
    task.name == "assembleRelease" ||
        task.name == "bundleRelease" ||
        task.name == "packageRelease" ||
        task.name == "packageReleaseBundle"
}.configureEach {
    dependsOn(validateReleaseTelemetry)
}

// SubscriptionDeepLinkTest runs the website's deeplink examples through the app's parser.
tasks.withType<Test>().configureEach {
    inputs.file("../website/src/content/docs/docs/providers/deeplinks.md")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("websiteDeeplinksDoc")
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
    implementation(project(":core:runtime"))
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":feature:home"))
    implementation(project(":feature:routing"))
    implementation(project(":feature:logs"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:configviewer"))
    implementation(project(":core:xraycore"))
    implementation(project(":feature:xraycore"))

    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.work.runtime.ktx)
    // DatabaseModule opens the database with the platform SQLite engine.
    implementation(libs.sqlite.framework)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.sentry.android)
}
