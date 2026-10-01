# AGENTS.md

## Commands
- Run Gradle from the repo root with the wrapper: `./gradlew :app:assembleDebug`.
- Install the prek-managed Git hook with `prek install`; run all hook checks explicitly with `prek run --all-files`.
- Unit tests: `./gradlew :app:testDebugUnitTest`.
- Single test class: `./gradlew :app:testDebugUnitTest --tests com.material.xray.core.xray.ConfigGeneratorTest`.
- Single Kotlin backtick-named test: `./gradlew :app:testDebugUnitTest --tests "com.material.xray.core.xray.ConfigGeneratorTest.generates TUN inbound with correct name and MTU"`.
- Final lint and broader local verification: `./gradlew :app:lintDebug` and `./gradlew :app:check`.
- Formatting is not applied by building. `prek` verifies it with `ktlintCheck`; fix it with `./gradlew :app:ktlintFormat`.
- Iterate with targeted unit tests. Run the full unit-test suite before final QA, then defer assembly and lint until source edits are finished because they rerun expensive dexing and analysis work.
- Device-only flows need a connected device/emulator: `./gradlew :app:installDebug` and `./gradlew :app:connectedDebugAndroidTest`.
- Release signing is read from env vars, Gradle properties, then `local.properties`: `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `RELEASE_STORE_PASSWORD`.

## Project Shape
- This is a single-module Android app; `settings.gradle.kts` includes only `:app` and the namespace/application id is `com.material.xray`.
- App startup is `MaterialXrayApp` for Koin and scheduled subscription refresh, then `MainActivity` -> `MaterialXrayTheme` -> `MainNavigation` for Compose tabs.
- The runtime service is `service/XrayService.kt`, a `VpnService` that handles both root-managed service mode and rootless Android `VpnService` mode.
- Main boundaries: `core/xray` builds Xray config/TUN/routing, `core/root` wraps root shell execution, `data` holds Room/repositories/subscription parsing, and `ui` holds Compose screens.

## Native Assets
- Only arm64 is wired: `abiFilters += "arm64-v8a"`. Both modes run the official Android Xray build, `app/src/main/jniLibs/arm64-v8a/libxray.so`, from `nativeLibraryDir`.
- Update the Xray binary with `./scripts/download-xray.sh` for the recorded version or pass another Xray tag; the script writes the arm64 destination and fixes permissions.
- The Android build only adopts a TUN as an open fd (`xray.tun.fd`). Rootless mode starts Xray through `app/src/main/cpp/xray_launcher.c` (`System.loadLibrary("xray_launcher")`) with the VpnService fd; root TUN mode execs it through `app/src/main/cpp/xray_tun_exec.c`, built as the executable `libxraytun.so` so the installer extracts it. Native changes need an Android build, not only JVM tests.

## Data And Generated Code
- Room schema version is in `AppDatabase`. When changing entities, bump that version and append the SQL for the new step to `DatabaseMigrations.sqlByStartVersion`; `DatabaseModule` registers the whole chain, so nothing else needs editing.
- Room exports one JSON schema per version into `app/schemas`; commit the new file after building. `DatabaseMigrationChainTest` replays every migration and compares the result against it, so a migration that drifts from the entities fails `testDebugUnitTest`.
- Only a downgrade falls back to recreating the tables. A failed upgrade throws instead of wiping the user's data, so a broken migration must be fixed rather than absorbed.
- Room uses KSP from `app/build.gradle.kts`; prefer Gradle tasks for verification so generated code is produced.
- Dependency injection is Koin with the Koin Compiler Plugin. Annotate classes with `@Singleton`, `@Factory`, `@KoinViewModel` or `@KoinWorker` (Koin's annotations, not `javax.inject`); `AppModule`'s `@ComponentScan` picks them up and `@KoinApplication` on `MaterialXrayApp` assembles the graph. Third-party types, and classes the graph must build through a secondary constructor because the primary one is a test seam, get a provider function in `di/AppModule.kt` or `di/DatabaseModule.kt`.
- The compiler plugin fails the build on a missing binding, but only in a compilation that includes `MaterialXrayApp`; an incremental compile that skips it prints `compile-safety validation skipped`. `KoinGraphTest` checks the parts that only resolve at runtime.
- `local.properties`, Gradle outputs, `.cxx`, and most local IDE state are gitignored; do not depend on local-only values except SDK path or local signing credentials.

## CI
- `.github/workflows/ci.yml` only builds and uploads the debug APK, on pull requests and on pushes to master.
- `.github/workflows/release.yml` manually builds, signs, uploads, and publishes a release APK.

## QA
- Finish all source edits, then run `./gradlew :app:testDebugUnitTest :app:assembleDebug` first.
- After those checks pass and no further source changes are planned, run the expensive final lint with `./gradlew :app:lintDebug`.
- Run `prek run --all-files` last so ktlint and detekt validate the final worktree.
- Do not call a repository change truly 'done' until all three QA commands succeed. These build-dependent checks intentionally run locally rather than in CI or prek.
- If a required check cannot run, report that explicitly.

## Agent Workflow
- After implementing a change, you should check whether a device is attached over ADB. If it is, you should install the debug build of the app on the device to let the User verify the change.
- If a `review` subagent or similar is available to you and the change you made is significant/big, run it with the instruction to review your diff. If unsure about the significance - ask the User whether you should run the review.
- When asked to commit your changes, follow conventional commits and try to keep your commits atomic - one commit is one 'thing done'.
