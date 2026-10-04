# AGENTS.md

## Commands
- Run Gradle from the repo root with the wrapper: `./gradlew :app:assembleDebug`.
- Install the prek-managed Git hook with `prek install`; run all hook checks explicitly with `prek run --all-files`.
- Unit tests for every module: `./gradlew test`. JVM modules have a plain `test` task; in Android modules `test` runs `testDebugUnitTest`.
- One module: `./gradlew :core:xray:test` or `./gradlew :feature:home:testDebugUnitTest`.
- Single test class: `./gradlew :core:xray:test --tests com.material.xray.core.xray.ConfigGeneratorTest`.
- Single Kotlin backtick-named test: `./gradlew :core:xray:test --tests "com.material.xray.core.xray.ConfigGeneratorTest.generates TUN inbound with correct name and MTU"`.
- Type-resolving static analysis: `./gradlew detektMain detektTest -x detektRelease`. In Android modules `detektMain` covers every variant, and the release one only adds a second compilation of the same sources, hence the exclusion. These tasks compile the sources first. prek's plain `detekt` is quicker, but it silently skips every rule that needs types.
- Final lint: `./gradlew :app:lintDebug`. `:app` has `lint.checkDependencies = true`, so that one task lints every module as one project; a library's own lint task is not meaningful on its own.
- Formatting is not applied by building. `prek` verifies it with `ktlintCheck`; fix it with `./gradlew ktlintFormat`.
- Iterate with targeted unit tests. Run the full unit-test suite before final QA, then defer assembly and lint until source edits are finished because they rerun expensive dexing and analysis work.
- Device-only flows need a connected device/emulator: `./gradlew :app:installDebug`. The only instrumentation test is `./gradlew :core:android:connectedDebugAndroidTest`.
- Release signing is read from env vars, Gradle properties, then `local.properties`: `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `RELEASE_STORE_PASSWORD`.

## Project Shape
- Multi-module build. `settings.gradle.kts` lists the modules; `build-logic/` is an included build with the convention plugins every module applies by id: `materialxray.jvm.library`, `materialxray.android.library`, `materialxray.android.compose`, `materialxray.android.feature`, `materialxray.android.application`. SDK levels, Java 11, detekt/ktlint, Koin and the test dependencies are configured once in `build-logic/src/main/kotlin/MaterialXrayConventions.kt`.
- Kotlin packages follow modules: `:core:foo` is `com.material.xray.core.foo.*`, `:feature:foo` is `com.material.xray.feature.foo.*`, `:app` is `com.material.xray.*`. The one exception is `com.material.xray.service` in `:core:runtime`: `XrayService`, `XrayTileService`, `BootReceiver` and the WorkManager workers keep that package because Android (always-on VPN, QS tile) and WorkManager persist their class names on the device; do not move them. The JNI symbols in `xray_launcher.c` encode the package of `AndroidUserXrayProcessLauncher`, and Room's schema directory is named after `AppDatabase`'s fully qualified name, so moving either means renaming those too.
- Platform-free modules are plain `kotlin("jvm")` modules, so the compiler is what keeps `android.*` out of them: `:core:model`, `:core:common`, `:core:root`, `:core:xray`, `:core:network`, `:core:connection`, `:core:database`, `:core:data`, `:core:telemetry`, `:core:xraycore`. Anything they need from the platform is an interface in the JVM module (`AppLogger`, `PlatformInfo`, `MonotonicClock`, `XrayPaths`, `LocalSockets`, `PlatformDns`, `VpnTransportProbe`, `NetworkLinkProbe`, `SubscriptionDeviceIdentity`, `TelemetryClient`, …) with the Android implementation in `:core:android` under `com.material.xray.core.android.*`, bound with `@Singleton(binds = [Interface::class])`.
- Android library modules: `:core:android` (platform implementations including the Sentry client, app inventory, launcher, locale), `:core:runtime` (`XrayService`, the Android wiring of `ConnectionManager`, process supervisors, workers, tile, boot receiver, the native launcher, and its own `AndroidManifest.xml` with the service/receiver entries and permissions), `:core:ui` (theme, components, adaptive layout, every string and drawable resource; its `R` is the app's `R`), `:core:navigation` (`NavKey`s, `Navigator`, scene strategies), and `:feature:home`, `:feature:routing`, `:feature:logs`, `:feature:settings`, `:feature:configviewer`, `:feature:xraycore` (one Compose screen tree each, with their view models).
- `:app` is the thin shell: `MaterialXrayApp`, `MainActivity`, `navigation` (the `NavDisplay` root), `di/AppModule` and `di/DatabaseModule`, the launcher icons and manifest, the Xray download/build tasks, legal and geodata assets, signing and the Sentry plugin. The application id and namespace are `com.material.xray`.
- Dependency direction is strictly downwards: features depend on core modules and never on each other or on `:app`; `:core:runtime` depends on `:core:ui` only for `R`; `:core:data` depends on nothing Android. The `materialxray.android.feature` convention wires the standard feature dependency set, so a feature's `build.gradle.kts` lists only its extras.
- Downloading, sideloading and auto-updating Xray cores lives only in `:core:xraycore` and `:feature:xraycore`, because a store build may not fetch executables. Nothing else depends on them: `:app` includes their Koin modules and hands the page to Settings as an `OptionalSettingsPage`, so dropping both modules (their `include`s in `settings.gradle.kts`, the dependencies in `app/build.gradle.kts`, the `AppModule` includes and `xrayCoreSettingsPage` in `AppEntries.kt`) and `strings_xray_core.xml` removes the feature. `:core:xray` only runs a core already installed under `files/cores`. Keep new core-manager code inside those two modules.
- Navigation is Navigation 3: `MainNavigation` in `:app` hosts one `NavDisplay`; the keys and `Navigator` live in `:core:navigation`; `navigation/AppEntries.kt` in `:app` maps each key to a feature screen. Tab chrome (rail, bars, side sheets) is drawn inside the tab entries and shared across them with `sharedBounds`.
- App startup is `MaterialXrayApp` for Koin and scheduled subscription refresh, then `MainActivity` -> `MaterialXrayTheme` -> `MainNavigation`.

## Native Assets
- Builds produce a universal APK plus one per ABI: arm64-v8a, x86_64 and armeabi-v7a. Releases publish all four; the updater picks the one for the device's primary ABI. Both modes run an Android Xray build as `libxray.so` from `nativeLibraryDir`.
- No Xray binary is committed, so building an APK needs network access once per Xray version:
  - `downloadXray` fetches upstream's arm64-v8a and x86_64 Android builds for `third_party/xray/VERSION` and fails unless the archive and executable match `third_party/xray/CHECKSUMS.sha256`.
  - `buildXray` compiles armeabi-v7a, which upstream does not publish for Android, from `third_party/xray/COMMIT` with upstream's Android flags and the Go toolchain in `third_party/xray/GO_TOOLCHAIN`. It needs Go 1.21+ on `PATH` (Go fetches the pinned toolchain) and takes about a minute on a cold cache.
  - Both tasks are cacheable, so a `clean` restores them from the build cache.
- Switch Xray versions with `./scripts/change-xray-ver.sh <tag>`; it verifies the upstream digests and rewrites the version, commit, Go toolchain, license and checksums under `third_party/xray/`.
- The Android build only adopts a TUN as an open fd (`xray.tun.fd`). The native sources live in `core/runtime/src/main/cpp`. Rootless mode starts Xray through `xray_launcher.c` (`System.loadLibrary("xray_launcher")`) with the VpnService fd; root TUN mode execs it through `xray_tun_exec.c`, built as the executable `libxraytun.so` so the installer extracts it. Native changes need an Android build, not only JVM tests.

## Data And Generated Code
- Room lives in `:core:database`, a JVM module on Room's KMP driver API (`androidx.sqlite`). Migrations receive an `SQLiteConnection`; transactions go through `withWriteTransaction`; tests use the bundled SQLite driver. `:app`'s `di/DatabaseModule` opens the real file with `AndroidSQLiteDriver`.
- Room schema version is in `AppDatabase`. When changing entities, bump that version and append the SQL for the new step to `DatabaseMigrations.sqlByStartVersion`; `DatabaseModule` registers the whole chain, so nothing else needs editing.
- Room exports one JSON schema per version into `core/database/schemas`; commit the new file after building. `DatabaseMigrationChainTest` replays every migration and compares the result against it, so a migration that drifts from the entities fails `:core:database:test`.
- Only a downgrade falls back to recreating the tables. A failed upgrade throws instead of wiping the user's data, so a broken migration must be fixed rather than absorbed.
- Room uses KSP from `core/database/build.gradle.kts`; prefer Gradle tasks for verification so generated code is produced.
- Dependency injection is Koin with the Koin Compiler Plugin. Annotate classes with `@Singleton`, `@Factory`, `@KoinViewModel` or `@KoinWorker` (Koin's annotations, not `javax.inject`). Every module has a `di/<Module>Module` class with `@Module @ComponentScan` over its own packages; `:app`'s `AppModule` includes them all and `@KoinApplication` on `MaterialXrayApp` assembles the graph. A new module needs its `@Module` class added to `AppModule`'s `includes`. Third-party types, and classes the graph must build through a secondary constructor because the primary one is a test seam, get a provider function in the owning module's `@Module` class or in `:app`'s `di/AppModule.kt` / `di/DatabaseModule.kt`.
- The compiler plugin fails the build on a missing binding, but only in a compilation that includes `MaterialXrayApp`; an incremental compile that skips it prints `compile-safety validation skipped`. `KoinGraphTest` in `:app` checks the parts that only resolve at runtime, including every interface-to-Android-implementation binding; add a pair there when you add a seam.
- `local.properties`, Gradle outputs, `.cxx`, and most local IDE state are gitignored; do not depend on local-only values except SDK path or local signing credentials.

## CI
- `.github/workflows/ci.yml` only builds and uploads the debug APK, on pull requests and on pushes to master.
- `.github/workflows/release.yml` manually builds, signs, uploads, and publishes a release APK.

## QA
- Finish all source edits, then run `./gradlew test :app:assembleDebug detektMain detektTest -x detektRelease` first.
- After those checks pass and no further source changes are planned, run the expensive final lint with `./gradlew :app:lintDebug`.
- Run `prek run --all-files` last so ktlint and detekt validate the final worktree.
- Do not call a repository change truly 'done' until all three QA commands succeed. These build-dependent checks intentionally run locally rather than in CI or prek.
- If a required check cannot run, report that explicitly.
- Run one Gradle invocation at a time and do not override `kotlin.daemon.jvmargs`; parallel builds in several worktrees have exhausted the machine's memory before.

## Agent Workflow
- After implementing a change, you should check whether a device is attached over ADB. If it is, you should install the debug build of the app on the device to let the User verify the change.
- If a `review` subagent or similar is available to you and the change you made is significant/big, run it with the instruction to review your diff. If unsure about the significance - ask the User whether you should run the review.
- When asked to commit your changes, follow conventional commits and try to keep your commits atomic - one commit is one 'thing done'.
