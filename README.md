[![JetBrains official project](http://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub) 

# KotlinConf App

This repository contains the source code of the official application for [KotlinConf](https://kotlinconf.com/).

![The KotlinConf official application](docs/header.png)

The application is written in Kotlin, sharing code between all of its platforms using [Kotlin Multiplatform](https://www.jetbrains.com/kotlin-multiplatform/).

* The client application for Android, iOS, and desktop is built with shared UI using [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/).
* The backend application is powered by the [Ktor](https://ktor.io/) server-side framework and the [Exposed](https://www.jetbrains.com/help/exposed/home.html) database library.

The app is published for the following platforms:

* Android: [available on Google Play](https://play.google.com/store/apps/details?id=com.jetbrains.kotlinconf)
* iOS: [available from the App Store](https://apps.apple.com/us/app/kotlinconf/id1299196584)

> The web version (Kotlin/Wasm and Kotlin/JS) and the admin panel UI of the backend (a Kotlin/JS page served
> under `/admin`) are no longer part of this repository: the build is Bazel-only, and the Kotlin Multiplatform
> rules for Bazel have no JS/Wasm platform. Their sources are available in the Git history before the migration
> to Bazel, at `ac7b40cc`. The backend's admin JSON API is unaffected; `/admin` answers 404 unless a bundle built
> from that revision is given to the server at deploy time, see [Unsupported](docs/bazel.md#unsupported).

## Building the project

The project is built with [Bazel](https://bazel.build) only, through the Kotlin Multiplatform rules of
rules_kotlin. Bazel (via [Bazelisk](https://github.com/bazelbuild/bazelisk), which reads `.bazelversion`)
fetches the JDK, the Android SDK and NDK, and the Kotlin compilers itself; iOS targets additionally need Xcode
on a Mac. The Kotlin Multiplatform rules come from a commit of the `kmp` branch of
[JetBrains/rules_kotlin](https://github.com/JetBrains/rules_kotlin/tree/kmp) that `MODULE.bazel` pins and Bazel
fetches itself, see [rules_kotlin](docs/bazel.md#rules_kotlin).

   * Everything the host builds, and all tests: `bazel build //... && bazel test //...`
   * Android: `bazel build //app/androidApp` (the debug APK), or `bazel mobile-install //app/androidApp --adb="$ANDROID_HOME/platform-tools/adb"` to install and start it on the connected device or emulator
   * iOS: `bazel build //app/iosApp:KotlinConf` builds the app (`KotlinConf.ipa`, for the arm64 simulator), `bazel run //app/iosApp:KotlinConf` installs and launches it in the booted simulator; no shell scripts or Xcode build phases are involved (rules_apple `ios_application`). `bazel run //app/iosApp:xcodeproj` generates an Xcode project to edit the Swift code
   * Desktop: `bazel run //app/desktopApp`
   * Backend: `bazel run //backend -- -port=8081`, or `bazel build //backend:backend_deploy.jar` for the runnable jar

To work on the Kotlin code in an IDE, use the [Bazel plugin](https://plugins.jetbrains.com/plugin/22977-bazel)
of IntelliJ IDEA and import the workspace with the `.bazelproject` file of this repository.

See [docs/bazel.md](docs/bazel.md) for the targets, the prerequisites, the versioning, the deployment of the
backend and what is not supported.
