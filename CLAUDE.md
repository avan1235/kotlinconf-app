# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

The official KotlinConf app — a Kotlin Multiplatform project serving Android, iOS, and JVM desktop clients, backed by a Ktor server. All client UI is shared via Compose Multiplatform.

## Build & Run Commands

The project is built with Bazel only; `ANDROID_HOME` must point at the Android SDK, and the iOS targets need Xcode on a Mac.

```bash
# Build everything the host can build, and run all tests
bazel build //... && bazel test //...

# Run desktop app
bazel run //app/desktopApp

# Run backend server
bazel run //backend -- -port=8081

# Run backend tests
bazel test //backend:backend_test

# Run shared module tests (JVM target)
bazel test //app/shared:shared_jvm_test
```

Android: `bazel mobile-install //app/androidApp --adb="$ANDROID_HOME/platform-tools/adb"` installs and starts it on the connected device. iOS: `bazel run //app/iosApp:KotlinConf` installs and launches it in the booted simulator; `bazel run //app/iosApp:xcodeproj` generates an Xcode project for the Swift code.

## Module Structure

```
:core                 — Shared data models (Conference, Session, Speaker, VoteInfo, Score, AppConfig, etc.)
                        Used by both client and backend. No UI, no platform code.

:app:ui-components    — Reusable Compose UI components (KotlinConfTheme, typography, colors, buttons, cards, etc.)
                        Has its own resource class (UiRes) separate from the main app.

:app:shared           — Main KMP client: all screens, ViewModels, navigation, ConferenceService, DI graph.
                        Targets: android, jvm, iosArm64, iosSimulatorArm64.

:app:androidApp       — Android entry point only. Wires up AndroidAppGraph.
:app:desktopApp       — JVM desktop entry point. Uses Compose Desktop.

:backend              — Ktor/Netty server. Fetches data from Sessionize, stores votes/users in DB.
```

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

The `app:shared` module defines custom intermediate source sets beyond the default hierarchy:
- `nonAndroidMain` — shared by `iosMain` and `jvmMain` (excludes Android)
- `nonWebMain` — shared by `androidMain`, `iosMain`, and `jvmMain` (has `okio` dependency)

## Backend Architecture

- **Framework**: Ktor with Netty engine
- **DI**: Koin (`diModule()` in `DiModule.kt`)
- **Database**: Exposed ORM + HikariCP. PostgreSQL in production; H2 file-based fallback when no `database.host` is configured (used for local dev and tests).
- **Migrations**: `MigrationRunner` applies SQL migrations in order.

**Route structure** (`RoutesModule.kt`):
- Year-agnostic: `/healthz`, `/time`, `/admin/*`, `/config`
- Year-prefixed: `/{year}/conference`, `/{year}/conference-info`, `/{year}/schedule`, `/{year}/vote`, `/{year}/sign`, `/{year}/golden-kodee`, `/{year}/documents/*`, etc.

**Data flow**: `SessionizeService` polls the Sessionize API on a configurable interval and caches schedule data in-memory. `KotlinConfRepository` handles all DB operations (users, votes, feedback, signed policies) using `suspendTransaction`.

## Key Conventions

**Version management**: `version.bzl` is the single source of the version (`VERSION_NAME`/`VERSION_CODE`); the Android manifest, the iOS Info.plist and `version.xml` of the About screen read from it at build time. Bump the version there only.

**Resources**: `app:shared` uses resource class `org.jetbrains.kotlinconf.generated.resources` (auto-generated). `app:ui-components` uses its own `UiRes` / `org.jetbrains.kotlinconf.ui.generated.resources`. Don't mix them.

**Backend tests**: Use `ktor-server-test-host` with `test-application.yaml` config (H2 database, no external dependencies).
