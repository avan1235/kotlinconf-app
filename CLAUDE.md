# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

The official KotlinConf app — a Kotlin Multiplatform project serving Android, iOS and JVM desktop clients, backed by a Ktor server. All client UI is shared via Compose Multiplatform. The former web client (Kotlin/Wasm, Kotlin/JS) and the Kotlin/JS admin panel UI of the backend are not part of the repository any more; their sources are in the Git history before the migration to Bazel, at `ac7b40cc`.

## Build & Run Commands

The build is [Bazel](https://bazel.build) only (no Gradle, no Xcode project), through the Kotlin Multiplatform rules of rules_kotlin; `docs/bazel.md` is the reference. Bazel fetches the JDK, the Kotlin compilers, the Android SDK/NDK and rules_kotlin itself (a pinned commit of the `kmp` branch of JetBrains/rules_kotlin, see `MODULE.bazel`).

```bash
# Build everything the host builds, run all tests
bazel build //... && bazel test //...

# Run desktop app
bazel run //app/desktopApp

# Build the Android debug APK / install and start it on the connected device or emulator
bazel build //app/androidApp
bazel mobile-install //app/androidApp --adb="$ANDROID_HOME/platform-tools/adb"

# Build the iOS app (arm64 simulator, macOS with Xcode) / install and launch it in the booted simulator
bazel build //app/iosApp:KotlinConf
bazel run //app/iosApp:KotlinConf

# Run backend server
bazel run //backend -- -port=8081

# Run backend tests (one target per test class; or a single one, e.g. //backend:MapsApiTest)
bazel test //backend:tests

# Run shared module tests (JVM target)
bazel test //app/shared:tests

# Re-pin the dependency manifests after changing the artifacts in MODULE.bazel
bazel run @kmp_maven//pin                    # kmp_manifest.json (Kotlin Multiplatform libraries)
REPIN=1 bazel run @maven_kotlinconf//:pin    # maven_install.json (JVM-only libraries)
```

The IDE project model is the Bazel plugin's (`.bazelproject`), not Gradle's. `bazel run //app/iosApp:xcodeproj` generates an Xcode project (git-ignored) for the Swift sources.

## Module Structure

```
//core                — Shared data models (Conference, Session, Speaker, VoteInfo, Score, AppConfig, etc.)
                        Used by both client and backend. No UI, no platform code.

//app/ui-components   — Reusable Compose UI components (KotlinConfTheme, typography, colors, buttons, cards, etc.)
                        Has its own resource class (UiRes) separate from the main app.

//app/shared          — Main KMP client: all screens, ViewModels, navigation, ConferenceService, DI graph.
                        Targets: android, jvm, iosArm64, iosSimulatorArm64.

//app/androidApp      — Android entry point only (android_binary). Wires up AndroidAppGraph.
//app/desktopApp      — JVM desktop entry point (kt_jvm_binary). Uses Compose Desktop.
//app/iosApp          — iOS app (rules_apple ios_application): the Swift sources, the Kotlin/Native framework
                        `shared` of //app/shared and the Firebase Swift package.

//backend             — Ktor/Netty server. Fetches data from Sessionize, stores votes/users in DB.

//bazel               — Build tooling of this repository: the Compose resources generator
                        (compose_resources.bzl) and the google-services.json resources (google_services.bzl).
```

Each multiplatform module is a `kt_multiplatform_library`: `//core` is an alias that selects the variant of its consumer (`//core:core_jvm`, `:core_android`, `:core_native`); same for `//app/ui-components:ui_components` and `//app/shared`. Kotlin Multiplatform libraries come from `@kmp_maven//:<group>_<artifact>` (pinned in `kmp_manifest.json`), JVM-only ones from `@maven_kotlinconf//:...` (pinned in `maven_install.json`).

## Client Architecture

**Dependency Injection — Metro**

The app uses [Metro](https://github.com/ZacSweers/metro) (not Koin) for DI on the client. Key interfaces:

- `AppGraph` — top-level DI graph (`AppScope`), one per app process. Holds `ConferenceService`, `FlagsManager`, `TimeProvider`, etc.
- `YearGraph` — a *scoped sub-graph* (`YearScope`) created per active conference year. Holds `YearlyApi` and `YearlyStorage`. Created by `YearGraph.Factory` inside `ConferenceService`.
- Platform-specific graph (e.g. `AndroidAppGraph`) — annotated `@DependencyGraph(AppScope::class)`, created at app startup, implements `AppGraph`.

ViewModels are registered into Metro's map multibinding:
```kotlin
@ContributesIntoMap(AppScope::class)
@ViewModelKey
class ScheduleViewModel(...) : ViewModel()
```

**ConferenceService**

`ConferenceService` is the central app singleton. It:
- Fetches `AppConfig` (current year) from the backend on startup and stores it locally.
- Creates a `YearGraph` for the active year, giving access to that year's `YearlyApi` and `YearlyStorage`.
- Exposes `StateFlow`s: `agenda`, `speakers`, `conferenceInfo`, `goldenKodeeData`, `votes`, `currentYear`.
- Handles favorites, voting, policy acceptance, notification scheduling, and asset caching.

**Navigation**

Uses `androidx.navigation3`. Routes are `@Serializable sealed interface AppRoute` types defined in `Routes.kt`. `TopLevelRoute` marks bottom-nav destinations. `NavHost.kt` maps routes to screen composables.

**Screens / ViewModels pattern**

Each screen has a corresponding `*ViewModel` that takes `ConferenceService` as a dependency, maps service `StateFlow`s into `uiState: StateFlow<ErrorLoadingState<...>>`, and exposes user-action functions. The screen Composable receives the ViewModel via Metro's ViewModel factory.

**Flags**

`Flags` is a `@Serializable data class` stored via `FlagsManager`. Compose screens access it via `LocalFlags`. Includes developer-mode options like `useFakeTime`, `debugLogging`, and `useFakeGoldenKodeeData`.

**Source Set Hierarchy**

The `app/shared` module defines custom intermediate source sets beyond the default hierarchy, each a `kt_source_set` in `app/shared/BUILD.bazel` that the JVM and Android libraries compile (`source_sets`) and the iOS group refines (`depends_on`):
- `nonAndroidMain` — shared by `iosMain` and `jvmMain` (excludes Android)
- `nonWebMain` — shared by `androidMain`, `iosMain`, and `jvmMain` (has `okio` dependency; kept from the time the web targets existed)

`app/ui-components` has `nonAndroidMain` the same way.

## Backend Architecture

- **Framework**: Ktor with Netty engine
- **DI**: Koin (`diModule()` in `DiModule.kt`)
- **Database**: Exposed ORM + HikariCP. PostgreSQL in production; H2 file-based fallback when no `database.host` is configured (used for local dev and tests).
- **Migrations**: `MigrationRunner` applies SQL migrations in order.
- **Admin panel**: `AdminPanelRoutes.kt` mounts `/admin` on the `admin/` classpath resources (`staticResources`, `index.html` as index and fallback). The page itself, a Kotlin/JS Compose HTML app (`app/adminApp` at `ac7b40cc`), is not built by Bazel and the resources are absent, so `/admin` and everything below answer 404 (verified); this is the one functional difference from the Gradle-built backend. The JSON endpoints the page reads (`/{year}/vote/all`, `/{year}/feedback/summary` with the admin secret, DTOs in `AdminDtos.kt`; the public `/{year}/conference`) are built and tested (`AdminVotesApiTest`). An optional deploy-time recipe for a bundle built out of tree from `ac7b40cc` is in `docs/bazel.md` (Unsupported); never commit such a bundle.

**Route structure** (`RoutesModule.kt`):
- Year-agnostic: `/healthz`, `/time`, `/admin/*`, `/config`
- Year-prefixed: `/{year}/conference`, `/{year}/conference-info`, `/{year}/schedule`, `/{year}/vote`, `/{year}/sign`, `/{year}/golden-kodee`, `/{year}/documents/*`, etc.

**Data flow**: `SessionizeService` polls the Sessionize API on a configurable interval and caches schedule data in-memory. `KotlinConfRepository` handles all DB operations (users, votes, feedback, signed policies) using `suspendTransaction`.

## Key Conventions

**Version management**: The version is declared once, in `version.bzl` (`VERSION_NAME`, `VERSION_CODE`); the Android manifest values, the iOS `Info.plist` (`apple_bundle_version`) and the `app_version` string resource of `app/shared` are derived from it at build time. Edit `version.bzl` only; there is no `prepareRelease`. `aboutlibraries.json` (the AboutLibraries export in `app/shared/src/commonMain/composeResources/files/`) is a committed resource refreshed by hand when dependencies change.

**Dependency versions**: Declared in `MODULE.bazel` (`KOTLIN`, `COMPOSE`, `KTOR`, `METRO`, ...), not in a version catalog. After changing them, re-pin `kmp_manifest.json` and/or `maven_install.json` (commands above) and commit the result. The Kotlin compiler is the release rules_kotlin pins (2.4.21).

**Resources**: Compose resources are generated at build time by `//bazel:compose_resources.bzl` (nothing generated is committed). `app/shared` uses resource class `Res` / `org.jetbrains.kotlinconf.generated.resources`, `app/ui-components` its own `UiRes` / `org.jetbrains.kotlinconf.ui.generated.resources`. Don't mix them.

**Compiler plugins**: `//:compose_plugin`, `//:serialization_plugin` and `//:metro_plugin` (root `BUILD.bazel`) are listed in the `plugins` of the targets that need them; Metro comes before Compose.

**Backend tests**: Use `ktor-server-test-host` with `test-application.yaml` config (an H2 database, no external service except for the two network tests below). Each test class is its own `kt_jvm_test` target listed in `TEST_CLASSES` of `backend/BUILD.bazel`: add new classes there. `ApiTest` and `YearBasedApiTest` read the live Sessionize API and are tagged `requires-network` (listed in `NETWORK_TEST_CLASSES`); the other seven run offline in the sandbox.
