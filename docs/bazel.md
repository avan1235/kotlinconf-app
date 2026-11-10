# Building with Bazel

The repository is built with [Bazel](https://bazel.build) only, through the Kotlin Multiplatform rules
of rules_kotlin: there is no Gradle build, no Xcode project and no build script. The KMP rules live
on the `kmp` branch of [JetBrains/rules_kotlin](https://github.com/JetBrains/rules_kotlin/tree/kmp),
at the commit `MODULE.bazel` pins (see [rules_kotlin](#rules_kotlin)); they are not part of upstream
[bazel-contrib/rules_kotlin](https://github.com/bazel-contrib/rules_kotlin) yet. The platforms are the
JVM, Android and iOS; the web app and the admin panel UI of the backend, both Kotlin/JS, are not
built, see [Unsupported](#unsupported).

| Module              | Bazel targets                                                                 | Status      |
|---------------------|-------------------------------------------------------------------------------|-------------|
| `core`              | `//core` (alias), `//core:core_jvm`, `//core:core_android`, `//core:core_native` | JVM, Android and iOS build |
| `backend`           | `//backend` (server, `bazel run`), `//backend:backend_deploy.jar` (runnable jar), `//backend:tests` (9 test classes) | builds, runs, tests pass |
| `app/ui-components` | `//app/ui-components:ui_components` (alias), `:ui_components_jvm`, `:ui_components_android`, `:ui_components_native`, `:ui_components_resources` (the `UiRes` resources) | JVM, Android and iOS (`ios_arm64`, `ios_simulator_arm64`) libraries build |
| `app/shared`        | `//app/shared` (alias), `//app/shared:shared_jvm`, `:shared_android`, `:shared_native`, `:shared_resources` (the `Res` resources), `//app/shared:tests` (2 test classes) | JVM, Android and iOS libraries build, JVM tests pass |
| `app/desktopApp`    | `//app/desktopApp` (the `kt_jvm_binary`, `bazel run`), `:desktop_lib`, `:desktop_resources` | builds and runs on macOS arm64 |
| `app/androidApp`    | `//app/androidApp` (the debug APK; `bazel mobile-install //app/androidApp` installs it incrementally), `:android_lib`, `:google_services` | APK builds, debug-signed; installs and runs on an emulator with `mobile-install` too |
| `app/iosApp`        | `//app/iosApp:KotlinConf` (the app, an `ios_application` of rules_apple), `:shared` (the Kotlin/Native framework), `:compose-resources`, `:ipa_test`, `:xcodeproj` | builds an IPA for the arm64 simulator and runs there; device build needs a provisioning profile |

## Prerequisites

- Bazel 9.3.1 ([Bazelisk](https://github.com/bazelbuild/bazelisk) reads `.bazelversion`).
- Nothing to check out besides the app: Bazel fetches rules_kotlin itself, from the commit of the
  `kmp` branch that `MODULE.bazel` pins (see [rules_kotlin](#rules_kotlin)). That commit has the rules
  this migration needs: intermediate source-set support, source-set `runtime_deps` filtering, the
  `JavaInfo` of `kt_source_set` (the mobile-install of rules_android needs it to reach the deps of
  the source sets), the Kotlin/Native link dependencies pinned per release,
  `kt_native_framework.transitive_exports`, and the `kmp_maven` fixes (including
  `extra_dependencies`, dependency-only redirects and variant target naming).
- `bazel run @kmp_maven//pin`, which re-pins `kmp_manifest.json` (see [Dependencies](#dependencies)),
  downloads the bazel-kmp-resolver binary of the host from its published
  [0.4.0 release](https://github.com/avan1235/bazel-kmp-resolver/releases/tag/0.4.0) and checks it
  against the sha256 the pinned rules_kotlin commit declares. It is the only command that downloads
  the resolver: builds and tests never need it.
- Network access when the repositories are first fetched (the build itself runs without network in
  the sandbox): the JDK 21 the compilers run on, the Kotlin compiler and the Kotlin/Native
  distribution with its LLVM, the Android SDK (platform 37, build-tools 37.0.0) and the NDK (r29)
  from `hermetic_android_toolchains` (no `ANDROID_HOME` is read; the SDK licenses are accepted by
  `--repo_env` flags in `.bazelrc`), the Firebase iOS SDK sources (a shallow Git clone, see
  [`app/iosApp`](#appiosapp)), and the Go SDK that gazelle of rules_swift_package_manager needs.
  The NDK is only there because `android_binary` needs a C++ toolchain for its Android split, the
  app has no native code. `MODULE.bazel.lock` is git-ignored, so every clean clone resolves the
  module graph again. `rules_android_maven` is not pinned and may be rate-limited by the Maven
  mirrors on a cold fetch: retry.
- macOS with Xcode for the iOS targets (the Kotlin/Native linker uses its SDKs, rules_apple runs
  `actool`, `codesign` and `simctl`) and for the Swift Package Manager metadata of the Firebase
  package. On Linux `.bazelrc` deletes the `app/iosApp` package (`common:linux --deleted_packages`),
  so `bazel build //...` builds everything else; contributors without Xcode on a Mac pass
  `--deleted_packages=app/iosApp` themselves.
- `bazel run //app/desktopApp` and `bazel build //app/androidApp` use the pinned dependency
  manifests in the repository; no resolver binary or re-pinning is required.

## rules_kotlin

`MODULE.bazel` takes rules_kotlin, with its Kotlin Multiplatform rules and the `kmp_maven`
extension, from a commit of the `kmp` branch of
[JetBrains/rules_kotlin](https://github.com/JetBrains/rules_kotlin/tree/kmp) (`ad19038d`), the
way the README of rules_kotlin installs an unreleased revision ("From HEAD (Bzlmod)"):

```starlark
bazel_dep(name = "rules_kotlin", version = "2.2.0")
archive_override(
    module_name = "rules_kotlin",
    urls = ["https://github.com/JetBrains/rules_kotlin/archive/<commit>.tar.gz"],
    strip_prefix = "rules_kotlin-<commit>",
    integrity = "sha256-<base64 of the sha256 of the archive>",
)
```

The override is the only pin: a clean clone and CI fetch the same GitHub archive, Bazel rejects it
when its content does not match `integrity`, and the repository cache (`repository-cache: true` in
CI) keeps it under that hash. The archive holds the committed files of the commit only. The commit
in turn pins the resolver of the pin target,
[bazel-kmp-resolver 0.4.0](https://github.com/avan1235/bazel-kmp-resolver/releases/tag/0.4.0), with
the sha256 of the binary of each host; only `bazel run @kmp_maven//pin` downloads it.

### Local checkout of rules_kotlin

To work on the rules together with the app, clone the `kmp` branch next to the app
(`../rules_kotlin`) and build with the `local_rules_kotlin` config of `.bazelrc`, which replaces the
archive with that directory (`--override_module`):

```sh
git clone -b kmp https://github.com/JetBrains/rules_kotlin.git ../rules_kotlin
bazel build --config=local_rules_kotlin //...
bazel mod show_repo --config=local_rules_kotlin rules_kotlin   # prints the local path
```

or turn it on for every command with a `common --config=local_rules_kotlin` line in `user.bazelrc`
(git-ignored, imported by `.bazelrc`). The path of the config is relative, and Bazel resolves it
against the current directory, so run Bazel from the root of the workspace with it; for another
directory, or to run Bazel from a subdirectory, put
`common --override_module=rules_kotlin=/absolute/path/to/rules_kotlin` in `user.bazelrc` instead
(`%workspace%/../rules_kotlin` does not work: Bazel normalizes the `..` away together with
`%workspace%`). The override is opt-in: without it the build uses the pinned commit, as CI does, so
a change of the rules reaches the app only once it is pushed to the `kmp` branch and the pin is
moved to it.

### Updating rules_kotlin

1. Take the commit from the `kmp` branch:
   `git ls-remote https://github.com/JetBrains/rules_kotlin.git refs/heads/kmp` prints its head.
2. Put the commit into the URL and the `strip_prefix` of the `archive_override` in `MODULE.bazel`.
3. Compute the integrity of the archive and set `integrity` to `sha256-` followed by the output:

   ```sh
   curl -fsSL https://github.com/JetBrains/rules_kotlin/archive/<commit>.tar.gz \
       | openssl dgst -sha256 -binary | openssl base64 -A
   ```

4. `bazel mod show_repo rules_kotlin` prints the new URL; build and test with `bazel build //...` and
   `bazel test //...`. When the new commit pins another resolver release, re-pin with
   `bazel run @kmp_maven//pin` and commit `kmp_manifest.json` together with `MODULE.bazel`.

Never guess or hand-edit the integrity: compute it from the downloaded archive. GitHub does not
promise that the archives it generates stay byte-identical, so if the fetch of an unchanged pin
ever fails with an integrity mismatch, confirm the commit with `git ls-remote`, download the archive
again and recompute the value.

## Continuous integration

`.github/workflows/build-all.yml` runs `bazel build //...` and `bazel test //...` on Linux and macOS
(with the iOS app and its bundle test on macOS), builds the backend deploy jar on Linux and uploads
the APK, the IPA and the backend deploy jar. It checks out the app alone, at the root of the
workspace: Bazel fetches rules_kotlin from the commit `MODULE.bazel` pins (see
[rules_kotlin](#rules_kotlin)), so the workflow needs no second checkout and no token. It does not
run the pin target, so it never downloads the resolver. A green remote run is not claimed: the
build was checked locally against the pinned archive. The syntax is checked with
`actionlint .github/workflows/*.yml`.

## Commands

```sh
bazel build //core:core_jvm                                   # the JVM variant of a module
bazel build //core:core_android --platforms=//:arm64-v8a  # its Android variant
bazel build //core:core_native \
    --platforms=@rules_kotlin//kotlin/native/platforms:ios_simulator_arm64   # its iOS klib
```

The alias of a module (`//core`) selects the variant of its consumer: the klib under a
Kotlin/Native rule, the Android library on an Android platform, the JVM library otherwise.

```sh
bazel run //app/desktopApp                          # the Compose desktop app
bazel build //app/androidApp                        # the debug APK
bazel mobile-install //app/androidApp --adb="$ANDROID_HOME/platform-tools/adb"  # install (incrementally) and start it on the connected device/emulator
bazel run //backend -- -port=8081                   # the Ktor server
bazel build //app/iosApp:KotlinConf                 # the iOS app (IPA, arm64 simulator)
bazel run //app/iosApp:KotlinConf                   # install and launch it in the booted simulator
```

The APK is written to the output directory of the Android configuration, which `bazel cquery
--output=files //app/androidApp` prints (`bazel-out/<host>-fastbuild-ST-<hash>/bin/app/androidApp/androidApp.apk`):
`android_binary` builds itself for `--android_platforms`, so `bazel-bin/` points there only right
after a build of `//app/androidApp` alone; after `bazel build //...` it points to the host
configuration, where `bazel-bin/app/androidApp/androidApp.apk` is missing or stale. `mobile-install`
(and `cquery`) always find the current one.

```sh
bazel build //...     # everything the host builds: the JVM libraries, the backend, the tools, the APK; on macOS also the iOS app (KotlinConf.ipa)
bazel test //...      # the backend, app/shared, resources-generator and google-services tests; on macOS also the IPA test of the iOS app
```

The whole-repository build skips the targets of the other platforms as incompatible rather than
failing on them, so it is the gate to run on a change; it does include the apps, because
`android_binary` and `ios_application` build themselves and their dependencies for
`--android_platforms` and `--ios_multi_cpus` whatever the platform of the build (the APK takes about
90 s after a change to the Android toolchain, a few seconds otherwise):

- the Android libraries (`core_android`, `ui_components_android`, `shared_android`) are
  `target_compatible_with = ["@platforms//os:android"]`: they are only meaningful on an Android
  platform, where the `@kmp_maven` aliases select the Android variants (Compose, Coil); on the host
  they would get the desktop ones, and the Android-only dependencies of `app/shared` (AndroidSVG,
  `preference-ktx`, `metrox-android`) have no other variant at all. `android_binary` builds them
  for `//:arm64-v8a` (`--android_platforms` in `.bazelrc`), as does an explicit
  `--platforms=//:arm64-v8a`;
- the iOS source sets and libraries (`shared_ios`, `shared_native`, …) are compatible with the iOS
  platforms alone, and the source set of a group only below a Kotlin/Native consumer, so a build of
  a package never resolves its iOS-only dependencies (Ktor's Darwin engine) for the JVM;
  `//app/iosApp:KotlinConf` builds them under its own transition to the simulator platform;
- on macOS, `//...` therefore includes `//app/iosApp:KotlinConf` and `bazel test //...` its
  `:ipa_test` (16 test targets in all): the first build of the IPA takes about five minutes (the
  Firebase compile and the link of the Kotlin/Native framework, see [`app/iosApp`](#appiosapp)), a
  warm one a few seconds. On Linux the `app/iosApp` package is deleted (`common:linux
  --deleted_packages` in `.bazelrc`), so `//...` has no iOS target there (15 test targets); a Mac
  without Xcode passes `--deleted_packages=app/iosApp` itself.

A clean rebuild (`bazel clean --expunge`, then `bazel build //...` and `bazel test //...` with no
`user.bazelrc` and no `MODULE.bazel.lock`, against the pinned rules_kotlin archive) takes about 10
minutes on an M-series Mac when the downloads are already in Bazel's repository cache
(`~/Library/Caches/bazel/_bazel_<user>/cache/repos/v1`, which `--expunge` keeps): 5,700 actions in
all, of which the module resolution, the tools of rules_kotlin, `core`, `backend` and the JVM
libraries take the first minutes, the APK (the Go SDK the Android tools are built with, the SDK and
NDK repositories) is ready after about seven, and the IPA is the critical path (the Kotlin/Native
compile and link of the framework, the Firebase compile) at about ten; then seven seconds for the
16 tests. A build does not touch the pinned files (`kmp_manifest.json`, `maven_install.json`,
`app/iosApp/Package.resolved`), the lock file it regenerates matches the previous one for every
extension the build evaluates, and no build or test fetches the resolver binary. Without the
repository cache, add the download time of the toolchains listed under
[Prerequisites](#prerequisites).

Build the iOS variants by naming them, as below: a wildcard under `--platforms=…:ios_simulator_arm64`
also picks the `kt_jvm_test`s, which have no Java runtime toolchain for an iOS platform.

### Backend

```sh
bazel run //backend -- -port=8081                   # the Ktor server (EngineMain), as :backend:run
bazel test //backend:tests --test_output=errors     # one target per test class, 49 tests
bazel test //backend:MapsApiTest                    # a single class
```

`application.yaml` and the other main resources are on the classpath of the binary; the settings
come from the environment as under Gradle (`DB0_*`, `ADMIN_SECRET`, `CONFERENCE_BASE_URL`), and the
server falls back to a file-based H2 database (`kotlinconfg`, in the runfiles directory under
`bazel run`) without `DB0_HOST`. Each test class is its own
`kt_jvm_test` (compiled and cached independently, 60 s timeout, run in parallel); it is
an associate of the backend library, so it sees its `internal` declarations as the Gradle test
source set does, and its resources shadow the main ones of the same path. `ApiTest` and
`YearBasedApiTest` are tagged `requires-network`: they read the live Sessionize API (the conference
of the current year is synchronized from it), which the sandbox otherwise blocks. Not ported from
Gradle: the Jib container image, the Power Assert plugin (assertion messages only) and the admin
panel SPA the Gradle build copied into the resources under `admin/`, so `/admin` answers 404 (see
[Unsupported](#unsupported)).

### Compose Multiplatform resources

The Compose Gradle plugin turns the `composeResources/` directory of a module into the `Res` class
with its accessors (`Res.drawable.icon`, `Res.string.title`, ...), the `allXxxResources` collectors
and the resource tree the runtime library (`components-resources`) reads. The repo-local macro
`compose_resources` of `//bazel:compose_resources.bzl` does the same at build time with the
generator `//bazel/compose_resources:generator`, a port of the plugin's resource tasks whose output is
byte-identical to the Gradle one (it was checked against the output of the Compose Gradle plugin for
`app/shared` and `app/ui-components` while the Gradle build existed). Nothing generated is committed.

```sh
bazel build //app/shared:shared_resources //app/ui-components:ui_components_resources \
    //app/desktopApp:desktop_resources                  # the generated sources and resource trees
bazel test //bazel/compose_resources/tests:tests         # the generator and the JVM lookup
```

A `compose_resources(name, package, resources_dir, srcs, res_class, public, source_set,
multiplatform)` target (Gradle's `packageOfResClass`, `nameOfResClass`, `publicResClass`) has a
fixed set of outputs, so the Kotlin rules take them as sources without knowing the resources:

- `<name>_common_srcs` — `<Res>.kt`, one accessor file per resource type
  (`Drawable.<sourceSet>.kt`, `String.<sourceSet>.kt`, `StringArray...`, `Plurals...`, `Font...`)
  and the collectors: `ExpectResourceCollectors.kt` in a multiplatform module
  (`multiplatform = True`, the default), `ResourceCollectors.kt` with plain declarations in a
  JVM-only one like the desktop app (`multiplatform = False`, `source_set = "main"`);
- `<name>_platform_srcs` (multiplatform only) — `ActualResourceCollectors.kt`, the same `actual`
  declarations for every platform;
- `<name>_jar` — the prepared tree as a jar, for `resource_jars` of the JVM library;
- `<name>_files` — the prepared tree as files under `<name>/resources/`, for the Android assets
  (`assets = [":<name>_files"], assets_dir = "<name>/resources"`);
- the `ComposeResourcesInfo` provider of the target itself, which `compose_resources_bundle(name,
  deps)` of the same file turns into the `compose-resources` directory of an iOS app bundle: one
  directory with the trees of every module (`//app/iosApp:compose-resources`, see
  [`app/iosApp`](#appiosapp)); it fails on two modules with the same resource path.

The prepared tree keeps the layout the runtime expects, `composeResources/<package>/<dir>/<file>`:
the XML files of the `values*` directories become `<name>.<sourceSet>.cvr` files whose records the
accessors address by byte offset, every other file is copied. The wiring of a multiplatform module
is `common_srcs = [":x_common_srcs"]`, `jvm = dict(srcs = [":x_platform_srcs"], resource_jars =
[":x_jar"])`, the same `srcs` for the Android library and the native groups, and
`components-resources` among the `common_deps`; `//bazel/compose_resources/tests` compiles the
fixture that way (`fixture_lib`) and as a JVM-only library (`fixture_single_lib`), and resolves
strings (by locale), drawables (by theme), plurals, arrays and raw files through the runtime from
the jar. The lookup test runs with the desktop variant of the runtime and the Skiko native library
of the host (`@maven_kotlinconf`), which the runtime reads the system theme through.

## Toolchain and compiler plugins

The Kotlin compiler is the release rules_kotlin pins, 2.4.21, the one the former Gradle build used
(`KOTLIN` in `MODULE.bazel` names it for the artifacts that must match the compiler: the Compose
compiler plugin, the stdlib, kotlin-test); the Kotlin/Native distribution and its link dependencies
are the ones of that release too, and it is the only release the rules support for Kotlin/Native:
they pin the LLVM and the link dependencies of the framework per release, with 2.4.21 as the only
entry, and `kotlin_native.version` of another release fails fast against that table. Another Kotlin
release is therefore a change of rules_kotlin first (its table, on the `kmp` branch), then of
`KOTLIN` and the pin here; `rules_kotlin_extensions.kotlinc_version` alone moves only the JVM
compiler. The root `BUILD.bazel` declares the toolchain (JVM 11 bytecode, as the Gradle modules; the
language and API versions are the defaults of the release) and the compiler plugins of the former
Gradle build, to list in the `plugins` of a target:

- `//:serialization_plugin` — kotlinx.serialization, bundled with the Kotlin release;
- `//:compose_plugin` — the Compose compiler of the Kotlin release;
- `//:metro_plugin` — Metro (1.4.0), with the options the Metro Gradle plugin sends by default for
  Kotlin 2.4 where they differ from the defaults of the compiler plugin itself:
  `contributes-as-inject=true` (the `@ContributesIntoMap` ViewModels and `@ContributesBinding`
  storages of `app/shared` have no `@Inject`, so without it the compiler rejects them),
  `enable-top-level-function-injection=true` and `generate-contribution-hints-in-fir=true`. The
  Gradle plugin also orders Metro before the Compose compiler (`-Xcompiler-plugin-order`), which
  only matters for `@Inject @Composable` functions; here Metro simply comes first in `plugins`.

## Dependencies

Two pinned installs, both committed:

- `kmp_manifest.json` — the Kotlin Multiplatform libraries (`kmp_maven.install` in `MODULE.bazel`),
  resolved for the JVM, Android, `ios_arm64` and `ios_simulator_arm64`. A library is
  `@kmp_maven//:<group>_<artifact>` (dots and dashes as underscores), an alias that selects the
  variant like the module aliases do. After changing the artifacts, pin again with
  `bazel run @kmp_maven//pin`, which downloads the bazel-kmp-resolver release rules_kotlin pins
  (see [Prerequisites](#prerequisites)); building does not need it.
- `maven_install.json` — the JVM-only libraries (`maven.install`, hub `@maven_kotlinconf`): the
  compiler plugin jars, the backend stack and its test libraries, the desktop variants of the Compose
  resources runtime and its dependencies (`components-resources-desktop`, for the desktop app and
  the tests of the resources generator) with the Skiko native library of the host, and the desktop
  runtime (`desktop-jvm` and `kotlinx-coroutines-swing`). List a Kotlin Multiplatform library (Ktor,
  Koin, coroutines, the Compose libraries) by its `-jvm` or `-desktop` artifact: the POM of the root
  artifact has no dependencies, so the resolution would stop there. Pin again with
  `REPIN=1 bazel run @maven_kotlinconf//:pin`.

The Compose libraries needed by `app/ui-components` and `app/shared` are pinned in
`kmp_manifest.json`, including `components-resources`, for the JVM, Android and the two iOS
targets. Pinning them required four changes, released in bazel-kmp-resolver 0.4.0 (and the matching
one in rules_kotlin): requests with and without `<type>aar</type>` collapse to one dependency
per scope, AndroidX samples source jars do not displace the library source jar, a KMP umbrella
requested as an AAR does not replace its Android platform-specific variant, and a variant without
artifact is kept as a `redirect` to its dependencies. The last one mattered on Android with Compose
1.11, whose `org.jetbrains.compose.*` libraries published their Android variants as dependency-only
redirects to the `androidx.compose.*` ones, so without it their aliases had no Android branch and
fell back to the desktop jars and Skiko. Compose 1.12.1 ships an Android AAR per library again
(`runtime-android`, `foundation-android`, …, each exporting its `androidx.compose.*` counterpart),
so the manifest now has five redirects only: the JetBrains `savedstate`, `savedstate-compose` and
`navigationevent-compose` umbrellas on Android (an `android_library` exporting the AndroidX
libraries), `savedstate` on the JVM (a `kt_jvm_library` without sources) and `kotlin-stdlib-common`
to `kotlin-stdlib`. To check what an Android build gets:

```shell
bazel cquery --platforms=//:arm64-v8a 'deps(@kmp_maven//:org_jetbrains_compose_foundation_foundation, 3)'
```

lists the `org.jetbrains.compose.*-android` and `androidx.compose.*-android` AARs, no `-desktop` jar
and no `skiko`. The dependencies of `app/ui-components` (Coil, lifecycle compose, the Markdown
renderer, the OkHttp and Darwin Ktor engines) and of `app/shared` (the Metro runtime and `metrox-*`,
the Ktor client, navigation3, AboutLibraries, the settings libraries and their test double,
KMPNotifier, Okio, …) are pinned as well: 44 coordinates, 228 libraries. The variant targets of the
hub are `<lib>.jvm`, `<lib>.android`, `<lib>.native` and `<lib>.<konan target>` (a `.` never occurs
in a library name), after a fifth rules_kotlin change: with `_android` suffixes, the Android variant
of the `androidx.datastore:datastore-preferences` umbrella and the alias of the distinct
`datastore-preferences-android` library (both in the graph through KMPNotifier's Firebase
dependency) named the same target.

### `app/ui-components`

`//app/ui-components:ui_components` is a `kt_multiplatform_library` with the Compose plugin: the
common source set is `src/commonMain` plus the generated `UiRes` accessors, `src/nonAndroidMain` is
the `kt_source_set` `ui_components_non_android`, refined by the iOS group (`depends_on`) and compiled
by the JVM library (`source_sets`); the former `src/webMain` was removed with the web app (see
[Unsupported](#unsupported)). The resources are packaged per platform the way the Compose runtime
reads them:

- JVM: the `ui_components_resources` jar in `resource_jars` (`composeResources/<package>/…` on the
  classpath);
- Android: `assets` with `assets_dir`, so the AAR has
  `assets/composeResources/org.jetbrains.kotlinconf.ui.generated.resources/…` (check with
  `bazel build --platforms=//:arm64-v8a //app/ui-components:ui_components_android.aar`);
- iOS: the klib only holds the code; the `composeResources` tree goes into the `compose-resources`
  directory of the app bundle through `//app/iosApp:compose-resources`, which rules_apple packs into
  the app (see [`app/iosApp`](#appiosapp)).

```shell
bazel build //app/ui-components:ui_components_jvm
bazel build --platforms=//:arm64-v8a //app/ui-components:ui_components_android
bazel build --platforms=@rules_kotlin//kotlin/native/platforms:ios_simulator_arm64 //app/ui-components:ui_components_native
```

Three generic fixes in rules_kotlin came out of it: the `<name>_srcs` filegroup of
`kt_multiplatform_library` lists a source once when several platforms compile it (the generated
`ActualResourceCollectors.kt`); `kt_jvm_import` accepts any `JavaInfo` in `exports` (Ktor's OkHttp
engine exports OkHttp, an `aar_import` on Android); and an `aar_import` of the hub exports the
imports a redirect stands for, as `aar_import.exports` only takes imports (Coil exports the Compose
foundation redirect).

### `app/shared`

`//app/shared:shared` is a `kt_multiplatform_library` with the Metro, Compose and serialization
plugins on every platform, the same shape as `ui-components`:

- the common source set is `src/commonMain` plus the generated `Res` accessors; the `api`
  dependencies of the former Gradle file (`//core`, `//app/ui-components:ui_components`, the Compose runtime, foundation,
  animation and `components-resources`, the Ktor logging, content negotiation and JSON
  serialization, KMPNotifier) are `exports` of each platform library, the `implementation` ones are
  `common_deps` only; the Metro runtime, which the Metro Gradle plugin adds to `commonMain` itself, is
  listed explicitly;
- `src/nonAndroidMain` (the SVG decoder of the Skia platforms) is the `kt_source_set`
  `shared_non_android`, which the JVM library compiles (`source_sets`) and the iOS group refines
  (`depends_on`), so the Kotlin/Native compile gets the fragment chain
  `common → non_android → ios → native`; `src/nonWebMain` (the Okio file storage
  `OkioAssetStorage` and the `BrowserIntegration` of the non-web platforms, with Okio as its
  dependency) is the `kt_source_set` `shared_non_web`, compiled by the JVM and Android libraries
  and refined by the iOS group the same way. The source set keeps its Gradle name although the
  Bazel build has no web targets, so the sources stay where upstream has them; the Android library
  compiles `src/androidMain` on top of the common set and `shared_non_web`;
- platform dependencies as in Gradle: the OkHttp engine and `slf4j-nop` on the JVM; the OkHttp
  engine, `metrox-android`, AndroidSVG and `preference-ktx` on Android, with
  `src/androidMain/AndroidManifest.xml` and `custom_package = "org.jetbrains.kotlinconf"`; the
  Darwin engine on iOS;
- the resources as in `ui-components`: the `shared_resources` jar on the JVM classpath, Android assets
  under `assets/composeResources/org.jetbrains.kotlinconf.generated.resources/…` (strings in
  `values` and `values-de`, drawables, fonts and `files/aboutlibraries.json`);
- the former `src/webMain`, `src/jsMain` and `src/wasmJsMain` (the `WebAppGraph`, the browser
  storage) were removed with the web app (see [Unsupported](#unsupported)).

Metro generates the graphs of the platforms (`JvmAppGraph`, `IosAppGraph`; the Android one lives in
`app/androidApp`) and the `metro.hints` of the contributions, which the Android app's graph will
aggregate from the library.

The JVM tests are the Gradle `jvmTest` run: one `kt_jvm_test` per class of `src/commonTest`
(`ApplicationStorageImplMigrationTest`, 6 tests) and `src/jvmTest` (`OkioAssetStorageTest`, 4
tests), on kotlin.test over JUnit 4, with `multiplatform-settings-test` and
`kotlinx-coroutines-test`; each is an associate of `shared_jvm`, so it sees its `internal`
declarations as the Gradle test source sets do.

```shell
bazel build //app/shared:shared_jvm
bazel test //app/shared:tests --test_output=errors
bazel build --platforms=//:arm64-v8a //app/shared:shared_android
bazel build --platforms=@rules_kotlin//kotlin/native/platforms:ios_simulator_arm64 //app/shared:shared_native
```

One more generic change in rules_kotlin came out of `bazel build //app/shared/...`: the source
set of a native group of `kt_multiplatform_library` (`shared_ios`) is now only compatible below a
Kotlin/Native consumer building for a target of the group (`native_<target>`, as the alias selects),
not with the platform alone. Before, a build of the package on a host whose platform is also a konan
target, or with `--platforms` of an iOS target, analyzed the source set as a JVM consumer and failed
on its iOS-only dependencies; now it skips it, and `shared_native` still compiles it, having
transitioned itself to Kotlin/Native (`konan_targets_compatibility(native_consumer = True)` in
`kotlin/internal/kmp/multiplatform.bzl`, with the unit and analysis tests of
`src/test/starlark/internal/kmp`).

### `app/desktopApp`

`//app/desktopApp` is the `kt_jvm_binary` of the Gradle `compose.desktop.application`
(`mainClass = "org.jetbrains.kotlinconf.MainKt"`); `bazel run //app/desktopApp` replaces the
former Gradle `hotRun` task, without the hot reload. Its library, `desktop_lib`,
compiles `src/main/kotlin` with the Compose and Metro plugins against `shared_jvm` and the JVM
variants of the `@kmp_maven` aliases, plus the two JVM-only pieces of `@maven_kotlinconf`:
`compose.desktop.currentOs` is
`org.jetbrains.compose.desktop:desktop-jvm` (the Compose for Desktop runtime, with `skiko-awt`) and
the Skiko native library of the host, `skiko-awt-runtime-macos-arm64`, a `runtime_deps` of the
binary; `kotlinx-coroutines-swing` is the Swing dispatcher. Another host needs its own
`skiko-awt-runtime-<os>-<arch>` there (the Linux x64 one is already pinned for the tests of the
resources generator). The resources of the module (`src/main/composeResources`, package
`org.jetbrains.kotlinconf.generated.resources.desktop`) are a JVM-only `compose_resources`
(`multiplatform = False`, `source_set = "main"`): plain collectors instead of expect/actual ones,
and the resource tree in `resource_jars`. Not ported: the `nativeDistributions` of the Compose
Gradle plugin (DMG/MSI/DEB packaging through `jpackage`, `packageUberJarForCurrentOS`) and Compose
Hot Reload (the former `hotRun` task); `kt_jvm_binary` yields the launcher script and its runfiles,
no single jar.

```shell
bazel run //app/desktopApp
```

The app starts, migrates its storage, loads the conference data and logs
`ConferenceService init successful`, with the strings, drawables and fonts of `Res` and `UiRes`
resolved from the jars.

### `app/androidApp`

`//app/androidApp` is the debug APK of the Gradle `assembleDebug`, built on the host too (see
[Commands](#commands) for where Bazel writes it), signed with the debug key of rules_android;
`bazel mobile-install //app/androidApp` (`--adb=<path to adb>` when adb is not on the PATH) installs
it on the connected device or emulator incrementally and starts it, through the mobile-install of
rules_android, with no script. Its dexing aspect only follows targets with a `JavaInfo`, which the
`kt_source_set`s of the multiplatform libraries provide for their deps (rules_kotlin, see
`docs/kmp.md` there); the Android platform is named `arm64-v8a` because mobile-install names the
native-library directory of its split APK after the platform. The `kt_android_library`
`android_lib` compiles `src/main/kotlin` (the activity, the application and the Metro
`AndroidAppGraph`, which aggregates the contributions of `app/shared`) with the Compose and Metro
plugins, `custom_package = "com.jetbrains.kotlinconf"` (the Gradle `namespace`), the resources of
`src/main/res` and the Android-only dependencies of the former Gradle file (`activity-compose`,
`core-splashscreen`, `metrox-android`, `metrox-viewmodel-compose`, `navigation3-ui`). The
`android_binary` takes the real `src/main/AndroidManifest.xml` with the `defaultConfig` as
`manifest_values` (`applicationId`, `minSdkVersion` 28, `targetSdkVersion` 37, `versionCode` 73,
`versionName` 40.0.5, the last two from `//:version.bzl`) and merges the manifests of the libraries into it (the receiver of
`app/shared`, which exports its manifest, the Firebase components of KMPNotifier). Details that
differ from the Gradle build, all in `app/androidApp/BUILD.bazel`:

- the library links its resources against a generated manifest with `<uses-sdk
  android:minSdkVersion="28"/>`: aapt2 rejects the `<adaptive-icon>` of the launcher icon below API
  26, and `android_library` has no `manifest_values` to tell it the minimum of the app;
- dexing is the incremental pipeline of rules_android (the default): each jar is desugared
  (`--min_sdk_version=23`, the floor of the dex/desugar aspect) and then dexed, so the Kotlin 2
  lambdas of Coil, the Markdown renderer, the settings library, … become ordinary bytecode instead
  of `invoke-custom`. The monolithic dexer (`incremental_dexing = 0`) never desugars (CompatDx
  hard-codes it off), so with it those libraries either fail to dex or, with a min API of 26+,
  reach the device as `invoke-custom` that the runtime rejects. Incremental dexing sees the Maven
  libraries of a `kt_source_set` because `kt_multiplatform_library` lists those source sets in
  `runtime_deps` of the JVM/Android libraries (a conventional attribute the dex aspect follows;
  `source_sets` it does not). The published metadata of `com.mikepenz:multiplatform-markdown-renderer`
  omits Compose, which the desugar classpath check then misses: `kmp_maven.install(extra_dependencies
  = {…})` in `MODULE.bazel` adds the Compose libraries;
- rules_android strips the `uses-permission` elements of the library manifests before the merge
  unless `--merge_android_manifest_permissions` is set, which `.bazelrc` does: the APK then
  requests what the Gradle merge requests — `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM` of
  `app/shared`, `WAKE_LOCK` and `com.google.android.c2dm.permission.RECEIVE` of Firebase
  Messaging, `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` of `androidx.core` (check
  with `aapt2 dump permissions`);
- the app manifest sets `android:appComponentFactory` to Metro's `MetroAppComponentFactory` with
  `tools:replace`, which the Gradle manifest does not need: both the `metrox-android` and the
  `androidx.core` library manifests set the attribute, and rules_android merges the libraries in
  alphabetical order, so `androidx.core` comes first and the merger keeps its `CoreComponentFactory`
  with a warning ("automatically chose"), which cannot construct the constructor-injected
  `MainActivity`; with the declaration both builds produce the same manifest, and the Kotlin
  sources are the same as under Gradle;
- the Firebase resources of `google-services.json` (`google_app_id`, `google_api_key`,
  `gcm_defaultSenderId`, `project_id`, `google_storage_bucket`, …) are generated at build time by
  `//bazel:google_services.bzl` (`google_services_resources`, a port of the values of the Google
  Services Gradle plugin, tested by `//bazel/google_services/tests:generator_test`), so the JSON stays the only
  copy; the generated `values/google-services.xml` is not committed;
- the SDK is the hermetic one of `MODULE.bazel`, platform 37 and build-tools 37.0.0, the same
  `compileSdk` as Gradle (the APK reports `compileSdkVersion='37'`), so no local SDK is read; the
  NDK r29 is registered only because the Android split of `android_binary` needs a C++ toolchain.

Not ported, the release build type: `isMinifyEnabled` with `proguard-rules.pro` (the APK is the
debug variant, unshrunk, debuggable and debug-signed; a release needs `proguard_specs` and a
signing step of its own, `apksigner` with the release key), `debugImplementation(compose.ui.tooling)`
(the Android Studio preview tooling), the `packaging` excludes of `/META-INF/{AL2.0,LGPL2.1}` (nothing
to exclude: the Bazel APK has no such entries), the instrumentation runner (no Android tests in the
module) and the Google Analytics `global_tracker.xml` of the Google Services plugin (not used).

```shell
bazel build //app/androidApp
APK=$(bazel cquery --output=files //app/androidApp 2>/dev/null | grep '\.apk$' | grep -v unsigned)
$(bazel info output_base)/external/hermetic_android_toolchains++android+androidsdk_darwin/build-tools/darwin/37.0.0/aapt2 \
    dump badging "$APK"                # package, versions, permissions, launchable activity
adb install -r "$APK"                   # or: bazel mobile-install //app/androidApp --adb="$ANDROID_HOME/platform-tools/adb"
adb shell am start -W -n com.jetbrains.kotlinconf/org.jetbrains.kotlinconf.android.MainActivity
```

On an emulator (API 37, arm64) the app starts in about a second, shows the privacy notice and the
notification settings of the first launch, asks for the notification permission and then shows the
schedule with the conference data; the process stays alive. Nothing of the shared code is in
`logcat` (the `ConferenceService` messages the desktop app prints, for instance): on Android the
logger is a no-op unless the remote `debugLogging` flag is set (`AppInit.kt`), so the signals are
`adb shell pidof com.jetbrains.kotlinconf` and the resumed activity in `dumpsys activity`.

### `app/iosApp`

The iOS app is built by rules_apple, in `app/iosApp/BUILD.bazel`; there is no Xcode project in the
repository and no script or build phase involved:

- `//app/iosApp:shared` — the static framework `shared` of `app/shared`, a `kt_native_framework`
  with `exports = ["//app/shared"]` and `transitive_exports = False`: the whole dependency closure is
  linked in, but only the API of `app/shared` is in `Headers/shared.h` (`SharedMain_iosKt`, the
  notification handlers, …); with the transitive `api` exports of the module (Compose, Ktor) the
  Objective-C header would be huge. `-c opt` links it with `-opt` (several times slower), the default
  compilation mode gives the debug framework. The `framework_files` output group of the target
  feeds an `apple_static_framework_import` (`:shared_import`), which the Swift library depends on.
- `:KotlinConf_lib` — a `swift_library` of `iosApp/*.swift` (module `iosApp`, Swift 5 mode):
  `import shared` is the framework above, `FirebaseCore` and `FirebaseMessaging` come from the
  Firebase Swift package.
- `:compose-resources` — the `compose-resources` directory of the app bundle, where the iOS Compose
  resources runtime reads every module's resources from
  (`compose-resources/composeResources/<package>/…` of `Res` and `UiRes`), assembled by
  `compose_resources_bundle` from `//app/shared:shared_resources` and
  `//app/ui-components:ui_components_resources`. The target is named `compose-resources` because the
  tree artifact, as a structured resource, lands at the root of the bundle under its own name.
- `//app/iosApp:KotlinConf` — the `ios_application` (`com.kotlinconf.iosapp`, iPhone and iPad, iOS
  16.0 and later): `iosApp/Info.plist`, the entitlements, the asset catalog (the launch image), the
  Firebase `GoogleService-Info.plist`, `PrivacyInfo.xcprivacy`, the app icon (the Icon Composer
  bundle `iosApp/AppIcon.icon`, from which `actool` also renders the sizes for iOS before 26; it is
  the only icon source, as rules_apple rejects an `.icon` bundle together with an `.appiconset`) and
  the version (below). It links with `-ObjC` and `-lsqlite3`, which the Firebase static libraries
  need (Bazel compiles Objective-C without modules, so their autolink hints are absent).
- Firebase: `Package.swift` and `Package.resolved` in `app/iosApp` declare the
  `firebase-ios-sdk` package (11.15.0, the pins the old Xcode project resolved), and the
  `swift_deps` extension of rules_swift_package_manager in `MODULE.bazel` builds `FirebaseCore` and
  `FirebaseMessaging` from source (a one-time compile of about two minutes). Its repository rules run
  `swift package describe`, which needs Xcode; the `app/iosApp` package is deleted on Linux, so
  nothing requests those repositories there. To change the version, edit `Package.swift` and refresh
  `Package.resolved` with `bazel run @swift_package//:resolve`.
- `:ipa_test` — a macOS-only `sh_test` (`check_ios_app.sh`) of the IPA: bundle id, version,
  `CFBundlePackageType`, display name, background modes, icon name, an arm64 simulator Mach-O with
  the Objective-C classes of the Kotlin code (`SharedMain_iosKt`, `SharedIOSNotificationsKt`) and of
  Firebase (`FIRApp`, `FIRMessaging`), and the resources the app reads at runtime (the Compose
  resource trees of both modules, `aboutlibraries.json`, a font, `Assets.car`,
  `GoogleService-Info.plist`, `PrivacyInfo.xcprivacy`).
- `:xcodeproj` (`tags = ["manual"]`) — the Xcode project of the Bazel targets, from
  rules_xcodeproj, for working on the Swift sources.

The Kotlin/Native target needs no flag: rules_apple builds the dependencies of `ios_application` for
the platform of `--ios_multi_cpus` (`sim_arm64`, set in `.bazelrc`, or `arm64` for a device), whose
constraints the Kotlin/Native toolchains of rules_kotlin match, so the framework is linked for
`ios_simulator_arm64` or `ios_arm64` accordingly.

```shell
bazel build //app/iosApp:KotlinConf          # KotlinConf.ipa for the arm64 simulator
bazel test //app/iosApp:ipa_test             # the checks of the IPA above
bazel run //app/iosApp:KotlinConf            # install and launch in the booted simulator
BAZEL_APPLE_DEVICE_UDID=<udid> BAZEL_SIMCTL_LAUNCH_FLAGS="" bazel run //app/iosApp:KotlinConf
                                             # a chosen simulator, launched without attaching the console
bazel run //app/iosApp:xcodeproj             # generates app/iosApp/KotlinConf.xcodeproj (ignored by git)
bazel build --ios_multi_cpus=arm64 -c opt //app/iosApp:KotlinConf   # a device build, see below
bazel cquery --output=files //app/iosApp:KotlinConf                 # where the IPA is
```

The IPA is written to the output directory of the iOS configuration
(`bazel-out/ios_sim_arm64-fastbuild-…-ST-<hash>/bin/app/iosApp/KotlinConf.ipa`), so `bazel-bin/`
does not point to it: ask `cquery`. A cold build takes about five minutes on an M-series Mac, on top
of the downloads (the Kotlin/Native distribution with the LLVM toolchain, the Firebase sources): the
Firebase compile and the link of the debug framework (about four minutes); a warm one takes a few
seconds. `bazel run` boots nothing by itself: start a simulator first (or let rules_apple choose
one; it opens the Simulator app). The app launches with the privacy notice of the first launch, with
the Kodee illustration, strings and fonts from the Bazel-packaged resources, and Firebase Messaging
initialised.

A device build signs with the provisioning profile "KotlinConf iOS App for notifications" of team
`2ZEFAR8TH3` (`local_provisioning_profile` in `BUILD.bazel`, selected only for
`--ios_multi_cpus=arm64`): the profile and the signing certificate must be installed on the
machine (`~/Library/MobileDevice/Provisioning Profiles`), which is a one-time setup in Xcode. The
simulator build needs none, which is what CI builds. Not verified here: a device build (no profile on
the development machine), a `-c opt` link of the framework, and an App Store archive/upload (Bazel
builds the IPA; uploading it is a manual step, e.g. with Transporter or `xcrun altool`).

Working in Xcode: `bazel run //app/iosApp:xcodeproj` generates the project, which builds through
Bazel in its own output base (so the first Xcode build is a cold one), and `xcodebuild -project
app/iosApp/KotlinConf.xcodeproj -scheme KotlinConf -destination 'generic/platform=iOS Simulator'
build` succeeds on it. The generated project is not committed (`*.xcodeproj/` is in `.gitignore`);
Xcode's Archive action is not supported by it, use the Bazel build above for releases.

## Versioning

The version of the apps is declared once, in `version.bzl` (`VERSION_NAME`, `VERSION_CODE`):

- `//app/androidApp` takes it as `versionName` and `versionCode` in the `manifest_values` of the APK;
- `//app/iosApp:KotlinConf` puts it in the `Info.plist` as `CFBundleShortVersionString` and `CFBundleVersion`
  through `apple_bundle_version` (the checked-in `Info.plist` has no version keys);
- `//app/shared` generates the `app_version` string resource of the About screen,
  `"<name> (<code>)"`, into `values/version.xml` (`write_file :version_xml`), so there is no
  committed `version.xml`.

To release, edit `version.bzl` and rebuild; there is no `prepareRelease`-style script.

## Backend deployment

`bazel build //backend:backend_deploy.jar` produces the runnable jar of the Ktor server
(`backend` is a rules_java `java_binary` over the `kt_jvm_library`, main class
`io.ktor.server.netty.EngineMain`). `Procfile` is `web: java -jar backend_deploy.jar -port=$PORT`,
so a platform that runs a Procfile (Heroku) starts the jar that is deployed to it; the deployment
itself is to upload the jar built by Bazel (for example `heroku deploy:jar`, or the `backend`
artifact of the CI workflow), as a Gradle/buildpack build of the repository is not possible any
more. Smoke test (verified): `java -jar backend_deploy.jar -port=8089`, then
`curl -f localhost:8089/healthz` answers 200, `/config` the JSON of the current year, and `/admin/`
404 (the jar has no `admin/` resources, see [Unsupported](#unsupported) for the optional deploy-time
bundle). The settings come from the environment (`DB0_*`, `ADMIN_SECRET`, `CONFERENCE_BASE_URL`). A
live Heroku deploy and a container image are not covered.

## Unsupported

What the Gradle build had and the Bazel build deliberately does not have:

- **The web app** (`app/webApp`, the `wasmJs` and `js` targets and the `webMain`, `jsMain`,
  `wasmJsMain` source sets) and its GitHub Pages deployment: rules_kotlin has no JS/Wasm platform.
  The sources are in the Git history before the migration, at `ac7b40cc` (the last Gradle revision).
- **The admin panel UI** (`app/adminApp`, a Kotlin/JS Compose HTML single-page app the Gradle build
  bundled into the resources of the backend under `admin/`), for the same reason; its sources are at
  `ac7b40cc` too. This is the one functional difference between the Bazel-built backend and the
  Gradle-built one: the server still mounts `/admin` on the `admin/` classpath resources
  (`AdminPanelRoutes.kt`, a `staticResources` with `index.html` as index and fallback), but the
  deploy jar has no such resources, so `/admin`, `/admin/` and every path below answer 404 with an
  empty body (verified on `backend_deploy.jar`; the server starts and the other routes are not
  affected). The JSON API the panel reads, `/{year}/vote/all` and `/{year}/feedback/summary` (admin
  secret; `AdminVoteInfo`/`AdminFeedbackInfo` of `AdminDtos.kt`) and the public
  `/{year}/conference`, is built and tested (`AdminVotesApiTest`).

  Optionally, at deploy time only, the panel can be built out of tree from `ac7b40cc` and handed
  to the server as classpath resources, without a Gradle build or a generated file in this
  repository:

  ```sh
  git worktree add ../kotlinconf-gradle ac7b40cc                 # the last Gradle revision, out of tree
  (cd ../kotlinconf-gradle && ./gradlew :app:adminApp:jsBrowserDistribution)   # downloads Node.js and Yarn itself
  mkdir -p deploy/admin
  cp ../kotlinconf-gradle/app/adminApp/build/dist/js/productionExecutable/{index.html,admin.js} deploy/admin/
  java -cp backend_deploy.jar:deploy io.ktor.server.netty.EngineMain -port=8080
  ```

  With `admin/index.html` and `admin/admin.js` on the classpath, `/admin/` serves the page,
  `/admin/admin.js` the bundle and any other `/admin/...` path the page again, as the SPA expects
  (verified with placeholder files). Copying the two files into `backend/src/main/resources/admin/`
  before `bazel build //backend:backend_deploy.jar` packs them into the jar too (the resource glob
  of `//backend` takes them), but they are build outputs: do not commit them. The bundle carries its
  own copies of the wire models (`AdminVoteRow` and `AdminFeedbackRow`, mirrors of `AdminDtos.kt`,
  and `Conference` of `core`, read with `ignoreUnknownKeys`), so it fits a backend whose
  `AdminDtos.kt` and `core` models still match `ac7b40cc`; a rename or removal there makes a bundle
  of that revision stale, and the panel's sources would have to be carried along to rebuild it.
- The ProGuard configuration of the desktop release distributable (`compose-desktop.pro`): the
  desktop app is a plain `kt_jvm_binary`, unshrunk.
- The Jib container image, Compose Hot Reload, the `nativeDistributions` (DMG/MSI/DEB) of the
  desktop app, and the Power Assert plugin (assertion messages only).
- The Android release build type (R8 with `proguard-rules.pro`, the release signing key): the APK is
  the debug variant. The `proguard-rules.pro` template was deleted; add `proguard_specs` to a
  release `android_binary` together with a signing step when needed.
- A signed iOS device build in CI and the App Store upload (see [`app/iosApp`](#appiosapp)).
- `app/shared/src/commonMain/composeResources/files/aboutlibraries.json` is the committed export of
  the AboutLibraries plugin and stays a plain resource: Bazel has no generator for it, so refresh it
  by hand (run the AboutLibraries export on the dependency list of `app/shared`, e.g. from a
  checkout of the Git history that has the Gradle build, and commit the result) whenever the
  dependencies change.
- The IDE project model is the Bazel plugin's (`.bazelproject`), not Gradle's.
