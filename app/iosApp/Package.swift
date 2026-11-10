// swift-tools-version: 5.9
// The Swift packages of the iOS app, which rules_swift_package_manager builds from source for the
// Bazel targets of BUILD.bazel (the Xcode project resolved the same package): Firebase, for the
// push notifications (FirebaseCore and FirebaseMessaging). Package.resolved pins the versions; after
// changing this file, re-resolve it with `bazel run @swift_package//:resolve`.
import PackageDescription

let package = Package(
    name: "KotlinConfSwiftDeps",
    dependencies: [
        .package(url: "https://github.com/firebase/firebase-ios-sdk", from: "11.15.0"),
    ]
)
