#!/usr/bin/env bash
# Validates the iOS app of //app/iosApp (the ipa_test of BUILD.bazel): the app of the IPA has the
# bundle id and version, an arm64 executable for the iOS simulator that links the Objective-C
# classes of the Kotlin code of app/shared and of Firebase, and the resources the app reads at
# runtime (the Compose resources, the Firebase configuration, the asset catalog). macOS only.
# Usage: check_ios_app.sh <ipa> <bundle id> <short version> <build version>
set -euo pipefail

ipa="$1"
bundle_id="$2"
short_version="$3"
build_version="$4"

fail() { echo "FAIL: $*"; exit 1; }

[[ -f "$ipa" ]] || fail "IPA not found: $ipa"
dir="$(mktemp -d "${TEST_TMPDIR:-${TMPDIR:-/tmp}}/ipa.XXXXXX")"
unzip -q "$ipa" -d "$dir"
apps=("$dir"/Payload/*.app)
app="${apps[0]}"
[[ -d "$app" ]] || fail "no app in $ipa"

plist="$app/Info.plist"
[[ -f "$plist" ]] || fail "no Info.plist in $app"
plist_value() { /usr/libexec/PlistBuddy -c "Print :$1" "$plist"; }
expect_plist() {
  local actual
  actual="$(plist_value "$1")"
  [[ "$actual" == "$2" ]] || fail "$1 of $plist is '$actual', not '$2'"
}
expect_plist CFBundleIdentifier "$bundle_id"
expect_plist CFBundleShortVersionString "$short_version"
expect_plist CFBundleVersion "$build_version"
expect_plist CFBundlePackageType APPL
expect_plist CFBundleDisplayName KotlinConf
expect_plist "UIBackgroundModes:0" remote-notification
expect_plist "CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconName" AppIcon

executable="$(plist_value CFBundleExecutable)"
binary="$app/$executable"
[[ -f "$binary" ]] || fail "no executable $executable in $app"
info="$(lipo -info "$binary")"
[[ "$info" == *"architecture: arm64" ]] || fail "not an arm64 executable: $info"
# The LC_BUILD_VERSION platform of the executable, by number or by name (see mach-o/loader.h).
built_for="$(otool -l "$binary" | awk '/cmd LC_BUILD_VERSION/ { found = 1 } found && $1 == "platform" { print $2; found = 0 }' | sort -u)"
[[ "$built_for" =~ ^(7|IOSSIMULATOR)$ ]] || fail "$binary is built for platform '$built_for', not the iOS simulator"

symbols="$(nm "$binary")"
for class in SharedMain_iosKt SharedIOSNotificationsKt FIRApp FIRMessaging; do
  grep -qE "[[:space:]]_OBJC_CLASS_\\\$_${class}$" <<<"$symbols" || fail "$binary does not link the Objective-C class $class"
done

for resource in \
    GoogleService-Info.plist \
    PrivacyInfo.xcprivacy \
    Assets.car \
    compose-resources/composeResources/org.jetbrains.kotlinconf.generated.resources/values/strings.commonMain.cvr \
    compose-resources/composeResources/org.jetbrains.kotlinconf.generated.resources/files/aboutlibraries.json \
    compose-resources/composeResources/org.jetbrains.kotlinconf.ui.generated.resources/values/strings.commonMain.cvr; do
  [[ -f "$app/$resource" ]] || fail "$resource is missing from $app"
done
[[ -n "$(find "$app/compose-resources" -name '*.ttf' | head -1)" ]] || fail "no font in $app/compose-resources"

echo "OK: $(basename "$app" .app) $short_version ($build_version) of $ipa"
