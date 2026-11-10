"""The settings every module of the app shares: kmp_conventions() bound once, loaded as CONVENTIONS.

The Android knobs mirror the Gradle build: the app compiles against the android-37.0 platform of
the SDK (compileSdk 37) and its libraries have minSdk 28 — setting both also declares the `<name>_aar`
targets of the Android modules (see kmp.bzl:698-711). The source set groups are the intermediate
source sets of the Gradle build that the default hierarchy template of rules_kmp does not know:
nonAndroidMain (ios+jvm) and nonWebMain (android+ios+jvm, okio). webMain is dropped: this build has
no js/wasm targets (the webApp/adminApp of the Gradle build are gone).
"""

load("@rules_kmp//kotlin:defs.bzl", "kmp_conventions")

CONVENTIONS = kmp_conventions(
    android_jar = "@androidsdk//:platforms/android-37.0/android.jar",
    android_min_sdk = "28",
    android_compile_sdk = "37.0",
    source_set_groups = {
        "nonAndroid": ["jvm", "ios_arm64", "ios_simulator_arm64"],
        "nonWeb": ["android", "jvm", "ios_arm64", "ios_simulator_arm64"],
    },
)
