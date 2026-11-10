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

> The web client (Kotlin/Wasm and Kotlin/JS) and the admin panel UI of the backend (a Kotlin/JS page served
> under `/admin`) are no longer part of this repository: the build is Bazel-only, and its Kotlin Multiplatform
> rules have no JS/Wasm platform. The backend's admin JSON API is unaffected; without a bundle, `/admin` answers
> 404.

## Building the project

The project is built with [Bazel](https://bazel.build) only, through the Kotlin Multiplatform rules of
[rules_kmp](https://github.com/JetBrains/rules_kotlin/tree/kmp). Bazel (via [Bazelisk](https://github.com/bazelbuild/bazelisk),
which reads `.bazelversion`) fetches the JDK and the Kotlin compilers itself; the Android build reads the
Android SDK and NDK from `ANDROID_HOME`, and iOS targets additionally need Xcode on a Mac.

To build the project locally:

1. [Set up your environment](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-setup.html) for Kotlin Multiplatform development, with `ANDROID_HOME` pointing at the Android SDK.
2. Open the project in IntelliJ IDEA or Android Studio.
3. Run the desired platform
   * Everything the host builds, and all tests: `bazel build //... && bazel test //...`
   * Android: `bazel build //app/androidApp` (the debug APK), or `bazel mobile-install //app/androidApp --adb="$ANDROID_HOME/platform-tools/adb"` to install and start it on the connected device or emulator
   * iOS: `bazel run //app/iosApp:KotlinConf` installs and launches the app in the booted simulator; `bazel run //app/iosApp:xcodeproj` generates an Xcode project to edit the Swift code
   * Desktop: `bazel run //app/desktopApp`
   * Backend: `bazel run //backend -- -port=8081`, or `bazel build //backend:backend_bin_deploy.jar` for the runnable jar
