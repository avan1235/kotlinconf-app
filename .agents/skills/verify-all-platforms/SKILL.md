---
name: verify-all-platforms
description: Verifies that the KotlinConf app compiles across all Bazel targets, passes all test suites, and runs cleanly on the backend server, Desktop JVM app, Android emulator, and iOS simulator. Use when asked to verify, smoke-test, or check whether the app works across all platforms, or as a post-upgrade verification step.
---

# Verify the KotlinConf app on all platforms

Verifies the full Bazel build, test suites, and live runtime behavior across every supported
platform of this repository: the Ktor backend (`//backend` and `//backend:backend_deploy.jar`),
the JVM Compose Desktop app (`//app/desktopApp`), the Android debug APK (`//app/androidApp`), and
the iOS simulator app (`//app/iosApp:KotlinConf`) plus `ios_arm64` native compilation/linking.
(The web app and Kotlin/JS admin panel UI are intentionally unsupported; see `docs/bazel.md`.)

Run every command from the repository root. Keep all logs, screenshots, temporary databases, and
unpacked artifacts inside a scratch directory so the Git working tree stays clean:

```sh
SCRATCH="$(mktemp -d)"
```

**Runner notes:**
- **Stateless shell tools:** If each command executes in a fresh shell, persist variables
  (`SCRATCH`, `PORT`, `UDID`, `CREATED_SIM`, `APK`, `ADB`, etc.) in a small env file inside
  `$SCRATCH` (or pass explicit paths/IDs) instead of assuming shell variables survive across calls.
- **Managed background tools:** If your runner provides a dedicated background-process mode instead
  of shell `&`, start long-lived processes (backend JVM, Desktop app, Android emulator) in that
  mode using `exec` (so stopping the managed PID terminates the actual JVM/emulator) and inspect
  the runner's captured logs instead of redirecting with `> ... &`.
- **No Xcode / Linux:** On Linux (or macOS without Xcode), skip the iOS build and simulator steps
  (`.bazelrc` automatically sets `--deleted_packages=app/iosApp` on Linux; pass it manually on
  macOS without Xcode) and note the skip in the report.

## 1. Build all targets and run all tests

```sh
bazel build //...
bazel test //...
bazel build //backend:backend_deploy.jar
```

- On macOS, `//...` builds the JVM libraries, backend, Desktop app, Android APK (`//:arm64-v8a`),
  and iOS simulator IPA (`//app/iosApp:KotlinConf`), and `bazel test //...` runs all test suites
  (backend including live-Sessionize `ApiTest` and `YearBasedApiTest`, `app/shared`, Bazel tooling,
  and `//app/iosApp:ipa_test`). The first cold build takes 5–10 minutes; use a generous timeout.
- On a warm Bazel cache, pass `--nocache_test_results` to `bazel test //...` when you want a fresh
  execution of every test (including live Sessionize endpoints).
- Keep all default-platform `bazel cquery` / `bazel run` steps (Sections 2–5b) before the
  `--platforms=...:ios_arm64` build in Section 5e so Bazel does not discard its analysis cache
  twice.

## 2. Verify the backend server (`//backend` & `backend_deploy.jar`)

Smoke-test the deployable fat jar from `$SCRATCH` on a dynamically chosen free port so the fallback
H2 database (`kotlinconfg.mv.db`) is written to `$SCRATCH` instead of the repository root and port
collisions cannot silently hit another server. This validates both Ktor runtime behavior and
`backend_deploy.jar` packaging in one pass (optionally also check `bazel run //backend` if launcher
wiring changed).

```sh
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
JAR="$(pwd)/$(bazel cquery --output=files //backend:backend_deploy.jar 2>/dev/null | head -1)"
(cd "$SCRATCH" && ADMIN_SECRET=test-secret java -jar "$JAR" -port="$PORT" > "$SCRATCH/backend.log" 2>&1) &
BACKEND_PID=$!

# Wait for readiness
READY=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
    READY=1; break
  fi
  sleep 1
done
[ "$READY" = "1" ] || { echo "Backend failed to start on port $PORT"; cat "$SCRATCH/backend.log"; exit 1; }
```

Verify year-agnostic, read, static asset, DB-backed write/read, and admin endpoints:

```sh
BASE="http://127.0.0.1:$PORT"
YEAR="$(curl -fsS "$BASE/config" | python3 -c 'import sys,json; print(json.load(sys.stdin)["currentYear"])')"

# Core & read endpoints (all must return HTTP 200)
curl -fsS -o /dev/null -w "/healthz=%{http_code}\n" "$BASE/healthz"
curl -fsS -o /dev/null -w "/time=%{http_code}\n" "$BASE/time"
curl -fsS -o /dev/null -w "/config=%{http_code}\n" "$BASE/config"
curl -fsS "$BASE/$YEAR/conference" | python3 -c 'import sys,json; d=json.load(sys.stdin); s,sp=d["sessions"],d["speakers"]; assert s and sp; print(f"live sessions={len(s)} speakers={len(sp)}")'
curl -fsS "$BASE/2025/conference" | python3 -c 'import sys,json; d=json.load(sys.stdin); s=d["sessions"]; assert s; print(f"2025 sessions={len(s)}")'
curl -fsS -o /dev/null -w "/$YEAR/conference-info=%{http_code}\n" "$BASE/$YEAR/conference-info"
curl -fsS -o /dev/null -w "/$YEAR/golden-kodee=%{http_code}\n" "$BASE/$YEAR/golden-kodee"
curl -fsS -o /dev/null -w "/$YEAR/documents=%{http_code}\n" "$BASE/$YEAR/documents/code-of-conduct.md"
curl -fsS -o /dev/null -w "/$YEAR/partner-logos=%{http_code}\n" "$BASE/$YEAR/partner-logos/kotlinconf-light.svg"

# Expected 404: Kotlin/JS admin panel UI is not bundled in the Bazel build
curl -sS -o /dev/null -w "/admin/=%{http_code} (expect 404)\n" "$BASE/admin/"

# DB-backed user sign, vote, feedback, and admin endpoints (H2 + Exposed migrations + Bearer auth)
USER_ID="verify-smoke-user-$(date +%s)"
SESSION_ID="$(curl -fsS "$BASE/$YEAR/conference" | python3 -c 'import sys,json; print(json.load(sys.stdin)["sessions"][0]["id"])')"

curl -fsS -o /dev/null -w "POST sign=%{http_code}\n" -X POST -H "Content-Type: text/plain" -d "$USER_ID" "$BASE/$YEAR/sign"
curl -fsS -o /dev/null -w "GET sign=%{http_code}\n" -H "Authorization: Bearer $USER_ID" "$BASE/$YEAR/sign"
curl -fsS -o /dev/null -w "GET vote=%{http_code}\n" -H "Authorization: Bearer $USER_ID" "$BASE/$YEAR/vote"
# POST vote returns 200 after the session starts, or 477 ("Come Back Later") if run before the event
VOTE_CODE="$(curl -sS -o /dev/null -w "%{http_code}" -X POST \
  -H "Authorization: Bearer $USER_ID" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"score\":\"GOOD\"}" "$BASE/$YEAR/vote")"
echo "POST vote=$VOTE_CODE"
[ "$VOTE_CODE" = "200" ] || [ "$VOTE_CODE" = "477" ]
curl -fsS -o /dev/null -w "POST feedback=%{http_code}\n" -X POST \
  -H "Authorization: Bearer $USER_ID" -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SESSION_ID\",\"value\":\"smoke test\"}" "$BASE/$YEAR/feedback"
curl -fsS -H "Authorization: Bearer test-secret" "$BASE/$YEAR/vote/all" | \
  USER_ID="$USER_ID" SESSION_ID="$SESSION_ID" VOTE_CODE="$VOTE_CODE" python3 -c '
import os, sys, json
votes = json.load(sys.stdin)
if os.environ["VOTE_CODE"] == "200":
    assert any(v["userId"] == os.environ["USER_ID"] and v["sessionId"] == os.environ["SESSION_ID"] and v["score"] == "GOOD" for v in votes), votes
print(f"GET vote/all=200 (count={len(votes)})")
'
curl -fsS -H "Authorization: Bearer test-secret" "$BASE/$YEAR/feedback/summary" | \
  USER_ID="$USER_ID" SESSION_ID="$SESSION_ID" python3 -c '
import os, sys, json
items = json.load(sys.stdin)
assert any(f["userId"] == os.environ["USER_ID"] and f["sessionId"] == os.environ["SESSION_ID"] and f["value"] == "smoke test" for f in items), items
print(f"GET feedback/summary=200 (count={len(items)})")
'
```

Stop the server and check `$SCRATCH/backend.log` for exceptions or migration errors:

```sh
kill "$BACKEND_PID" 2>/dev/null || true
wait "$BACKEND_PID" 2>/dev/null || true
grep -iE 'Exception|ERROR' "$SCRATCH/backend.log" || echo "No backend errors"
```

## 3. Verify the Desktop app (`//app/desktopApp`)

`//app/desktopApp` bundles `skiko-awt-runtime-macos-arm64` and runs with `JvmLogger`
(`debugLogging = true` in `Main.kt`). Generate a launcher script with `--script_path` so the
process runs inside Bazel's runfiles directory (keeping `files/store.properties` out of the repo
root), does not hold the Bazel server lock, and lets `$!` track the JVM PID directly:

```sh
bazel run --script_path="$SCRATCH/run_desktop.sh" //app/desktopApp
"$SCRATCH/run_desktop.sh" > "$SCRATCH/desktop.log" 2>&1 &
DESKTOP_PID=$!

READY=0
for _ in $(seq 1 30); do
  if grep -q 'ConferenceService init successful' "$SCRATCH/desktop.log" 2>/dev/null; then
    READY=1; break
  fi
  kill -0 "$DESKTOP_PID" 2>/dev/null || break
  sleep 1
done

[ "$READY" = "1" ] && kill -0 "$DESKTOP_PID" || { echo "Desktop app failed to initialize"; cat "$SCRATCH/desktop.log"; exit 1; }
echo "DESKTOP_ALIVE (PID $DESKTOP_PID)"
cat "$SCRATCH/desktop.log"
```

- Confirm `ConferenceService init successful` is logged, conference/asset sync completes without
  exceptions, and the process stays alive.
- On macOS with a GUI session, capture the window to verify Compose rendering (artwork, strings,
  and fonts from `Res` and `UiRes`), then open and visually inspect `$SCRATCH/desktop.png`.
  (Because `run_desktop.sh` persists `files/store.properties` inside Bazel's runfiles tree, a fresh
  environment shows the Privacy notice while repeat runs may open straight to Schedule; either
  screen confirms Compose rendering.)

```sh
WIN_ID=$(swift -e 'import CoreGraphics
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
for w in list where (w[kCGWindowName as String] as? String) == "KotlinConf" {
    if let n = w[kCGWindowNumber as String] as? Int { print(n); break }
}')
[ -n "$WIN_ID" ] && screencapture -x -l "$WIN_ID" "$SCRATCH/desktop.png"
```

Stop the Desktop app once checked:

```sh
kill "$DESKTOP_PID" 2>/dev/null || true
wait "$DESKTOP_PID" 2>/dev/null || true
```

## 4. Verify the Android app (`//app/androidApp`)

*(If Android plugin tools or `mobile-mcp` are available in the session, prefer them for emulator
lifecycle, logcat, and UI interaction; the `adb` commands below work from any terminal as a
universal fallback.)*

### 4a. Locate APK and verify badging/permissions

Always resolve the APK path via `bazel cquery` (`bazel-bin/app/androidApp/androidApp.apk` can be
missing or stale after `bazel build //...`):

```sh
APK="$(bazel cquery --output=files //app/androidApp 2>/dev/null | grep '\.apk$' | grep -v unsigned | head -1)"
AAPT2="$(ls -1 "$(bazel info output_base)"/external/*androidsdk*/build-tools/*/*/aapt2 | head -1)"
"$AAPT2" dump badging "$APK" | grep -E '^(package:|minSdkVersion:|sdkVersion:|targetSdkVersion:|launchable-activity:|native-code:)'
"$AAPT2" dump permissions "$APK"
```

Confirm package `com.jetbrains.kotlinconf`, `versionName`/`versionCode` matching `version.bzl`,
launchable activity `org.jetbrains.kotlinconf.android.MainActivity`, ABI `arm64-v8a`, and merged
permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`,
Firebase `c2dm`).

### 4b. Connect or boot an arm64 emulator

`//app/androidApp` builds for `//:arm64-v8a` (`.bazelrc`). Find `adb` and check for a booted
device/emulator; if none is attached, cold-boot an `arm64-v8a` AVD with host GPU and snapshots
disabled (restored snapshots frequently wedge `screencap`/`uiautomator` or `mobile-install`).
*(Polling `sleep` below is only for the raw `adb`/`emulator` fallback when Android plugin tools are
not available.)*

```sh
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}"
[ -x "$ADB" ] || ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
EMULATOR="${ANDROID_HOME:+$ANDROID_HOME/emulator/emulator}"
[ -x "$EMULATOR" ] || EMULATOR="$(command -v emulator || echo "$HOME/Library/Android/sdk/emulator/emulator")"

"$ADB" devices -l
```

If no `arm64-v8a` emulator/device is running, pick an `arm64-v8a` AVD (resolving `path=` from its
`.ini` file) and boot it:

```sh
AVD="$(
  for name in $("$EMULATOR" -list-avds); do
    avd_dir="$(sed -n 's/^path=//p' "$HOME/.android/avd/$name.ini" 2>/dev/null | head -1)"
    [ -n "$avd_dir" ] || avd_dir="$HOME/.android/avd/$name.avd"
    if grep -q '^abi\.type=arm64-v8a' "$avd_dir/config.ini" 2>/dev/null; then
      echo "$name"; break
    fi
  done
)"
[ -n "$AVD" ] || AVD="$("$EMULATOR" -list-avds | head -1)"
"$EMULATOR" -avd "$AVD" -gpu host -no-snapshot-load -no-snapshot-save -no-audio -no-boot-anim > "$SCRATCH/emulator.log" 2>&1 &
EMU_PID=$!

"$ADB" wait-for-device
BOOTED=0
for _ in $(seq 1 90); do
  if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    BOOTED=1; break
  fi
  sleep 2
done
[ "$BOOTED" = "1" ] || { echo "Android emulator failed to boot"; tail -n 40 "$SCRATCH/emulator.log"; exit 1; }
"$ADB" shell settings put global hide_error_dialogs 1
```

### 4c. Install, launch, and exercise the UI

Install with `adb install -r` (or `bazel mobile-install //app/androidApp --adb="$ADB"` for
incremental deploys on an already-responsive emulator), reset app state for a clean first launch,
and start the activity:

```sh
"$ADB" install -r "$APK"
"$ADB" shell pm clear com.jetbrains.kotlinconf
"$ADB" logcat -c
"$ADB" shell am start -W -n com.jetbrains.kotlinconf/org.jetbrains.kotlinconf.android.MainActivity
```

- **Logcat caveat:** On Android, `ConferenceService` logs are a no-op unless remote `debugLogging`
  is enabled (`AppInit.kt`). Verify health via `pidof`, resumed activity, UI hierarchy/screenshots,
  and absence of errors in logcat — not by searching for `ConferenceService init successful`.
- Dump the UI hierarchy to `/data/local/tmp/window_dump.xml` (more reliable than `/sdcard/` on
  restricted emulators). Always delete the previous dump file and retry if `uiautomator dump`
  transiently returns `null root node` during a screen transition, so you never read stale XML:

```sh
dump_ui() {
  local out=""
  for _ in 1 2 3; do
    "$ADB" shell rm -f /data/local/tmp/window_dump.xml
    out="$("$ADB" shell uiautomator dump /data/local/tmp/window_dump.xml 2>&1 || true)"
    case "$out" in
      *"dumped to:"*) break ;;
    esac
    sleep 1
  done
  case "$out" in
    *"dumped to:"*) ;;
    *) echo "uiautomator dump failed: $out" >&2; return 1 ;;
  esac
  "$ADB" shell cat /data/local/tmp/window_dump.xml | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
for el in root.iter("node"):
    t, d, c, b = el.attrib.get("text",""), el.attrib.get("content-desc",""), el.attrib.get("clickable",""), el.attrib.get("bounds","")
    if t or d or c == "true":
        print(f"clickable={c} text={t!r} desc={d!r} bounds={b}")
'
}
```

Inspect bounds from `dump_ui` before every tap, compute the center `(x, y)` of `[left,top][right,bottom]`,
tap with `"$ADB" shell input tap <x> <y>`, and capture screenshots with
`"$ADB" exec-out screencap -p > "$SCRATCH/<name>.png"`. Walk through the core screens:

1. **Privacy notice** → tap **Accept**.
2. **Notifications onboarding** → tap **Let's get started!**.
3. **Notification permission dialog** → tap **Allow**.
4. **Schedule** → confirm conference sessions render (if emulator networking just initialized and
   the retry state appears, tap **Reload** and confirm live sessions populate).
5. Bottom tabs: open **Speakers** (list + Coil photos), **Golden Kodee** (awards/nominees),
   **Map** (AndroidSVG floorplan renders without crashing), and **Info** (conference info,
   partners, settings).
6. Confirm the app stayed alive with zero fatal/app errors (note: `dumpsys window` works on
   Android 16+ where `dumpsys window windows` omits `mCurrentFocus`; a single system
   `ashmem: Pinning is deprecated` line under `*:E` is benign):

```sh
PID="$("$ADB" shell pidof com.jetbrains.kotlinconf | tr -d '\r')"
[ -n "$PID" ] || { echo "Android app is not running"; exit 1; }
echo "ANDROID_PID=$PID"
"$ADB" shell dumpsys window | awk '/mCurrentFocus|mFocusedApp/'
"$ADB" logcat -d --pid="$PID" *:E
"$ADB" logcat -d AndroidRuntime:E *:S
```

If this verification booted an emulator (`EMU_PID`), stop it once done (`"$ADB" emu kill` or
`kill "$EMU_PID"`).

## 5. Verify the iOS app (`//app/iosApp:KotlinConf`, macOS with Xcode)

`//app/iosApp:ipa_test` (Step 1) already verifies the IPA's `Info.plist`, arm64 simulator Mach-O,
linked Objective-C classes (`SharedMain_iosKt`, `SharedIOSNotificationsKt`, `FIRApp`,
`FIRMessaging`), and bundled Compose/Firebase/asset-catalog resources. Now verify runtime execution
on an iOS Simulator.

### 5a. Reuse or create a simulator

Reuse a booted iOS iPhone simulator if one is already running; otherwise create and boot a temporary
iPhone simulator on the latest available iOS runtime:

```sh
UDID="$(xcrun simctl list devices booted -j | python3 -c '
import sys, json
for rt, devs in json.load(sys.stdin)["devices"].items():
    if not rt.startswith("com.apple.CoreSimulator.SimRuntime.iOS-"):
        continue
    for d in devs:
        if d.get("state") == "Booted" and "iPhone" in d.get("name", ""):
            print(d["udid"]); raise SystemExit
' 2>/dev/null || true)"
CREATED_SIM=0

if [ -z "$UDID" ]; then
  read -r RUNTIME DEVTYPE <<EOF
$(xcrun simctl list runtimes available -j | python3 -c '
import sys, json
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
rt = max(ios, key=lambda r: tuple(int(x) for x in r["version"].split(".")))
iphone = next(d for d in rt.get("supportedDeviceTypes", []) if "iPhone" in d["name"])
print(rt["identifier"], iphone["identifier"])
')
EOF
  UDID="$(xcrun simctl create "KotlinConf-Verify" "$DEVTYPE" "$RUNTIME")"
  CREATED_SIM=1
  xcrun simctl boot "$UDID"
  xcrun simctl bootstatus "$UDID" -b
fi
```

### 5b. Install, launch, and verify onboarding + background sync

Install and launch the already-built IPA directly via `simctl` (fast, captures the exact PID, and
does not steal focus with Simulator.app; alternatively run
`BAZEL_APPLE_DEVICE_UDID="$UDID" BAZEL_SIMCTL_LAUNCH_FLAGS="" bazel run //app/iosApp:KotlinConf`).
Wait until `conferenceCache` appears in `com.kotlinconf.iosapp.plist` (not just until the plist
file is created, which happens during splash launch before conference sync or the first Compose
frame finishes):

```sh
IPA="$(bazel cquery --output=files //app/iosApp:KotlinConf 2>/dev/null | grep '\.ipa$' | head -1)"
rm -rf "$SCRATCH/ios_ipa" && unzip -q "$IPA" -d "$SCRATCH/ios_ipa"
xcrun simctl install "$UDID" "$SCRATCH/ios_ipa/Payload/KotlinConf.app"

LAUNCH_OUT="$(xcrun simctl launch "$UDID" com.kotlinconf.iosapp)"
IOS_PID="$(echo "$LAUNCH_OUT" | awk '{print $2}')"
DATA_DIR="$(xcrun simctl get_app_container "$UDID" com.kotlinconf.iosapp data)"
PLIST="$DATA_DIR/Library/Preferences/com.kotlinconf.iosapp.plist"
CACHED=0
for _ in $(seq 1 30); do
  if [ -s "$PLIST" ] && plutil -p "$PLIST" 2>/dev/null | grep -q 'conferenceCache'; then
    CACHED=1; break
  fi
  kill -0 "$IOS_PID" 2>/dev/null || break
  sleep 1
done
[ "$CACHED" = "1" ] && kill -0 "$IOS_PID" || { echo "iOS app failed to sync or exited early"; exit 1; }
sleep 2
echo "IOS_ONBOARDING_ALIVE (PID $IOS_PID)"
xcrun simctl io "$UDID" screenshot "$SCRATCH/ios_onboarding.png"
```

Inspect `$SCRATCH/ios_onboarding.png` to confirm the Privacy notice renders with Kodee artwork,
strings, and fonts from `compose-resources` (not the launch splash screen). Then verify logs, crash
reports, Firebase Messaging initialization, and live cached conference data in the app data
container:

```sh
xcrun simctl spawn "$UDID" log show --last 5m --style compact \
  --predicate 'process == "KotlinConf" AND (messageType == error OR messageType == fault OR eventMessage CONTAINS[c] "exception")' \
  | awk 'NR <= 40'
ls -lt ~/Library/Logs/DiagnosticReports/KotlinConf* 2>/dev/null | head -n 5 || true

find "$DATA_DIR/Library/Application Support" -maxdepth 4 \
  \( -name 'rmq2.sqlite' -o -path '*/files/*/*' \) 2>/dev/null | sort
plutil -convert xml1 -o - "$PLIST" | python3 -c '
import sys, plistlib
p = plistlib.loads(sys.stdin.buffer.read())
for k, v in sorted(p.items()):
    print(f"  {k}: len={len(str(v))}")
'
```

Confirm:
- No `KotlinConf` crash reports and no Compose/Firebase/Kotlin errors in unified logs (routine
  simulator `libCoreFSCache` or `app_launch_measurement` messages are benign).
- `Application Support` contains Firebase Messaging storage (`rmq2.sqlite`) and Okio-cached
  documents (`.md`) and maps (`.svg`).
- `com.kotlinconf.iosapp.plist` contains populated conference/config/info/Golden Kodee cache keys.

### 5c. Verify the Schedule screen

`xcrun simctl` has no built-in tap command, so verify the main Schedule UI by setting
`onboardingComplete` in `NSUserDefaults`, relaunching, confirming the process stays alive, and
inspecting the screenshot:

```sh
xcrun simctl terminate "$UDID" com.kotlinconf.iosapp || true
xcrun simctl spawn "$UDID" defaults write com.kotlinconf.iosapp onboardingComplete -bool true
LAUNCH_OUT="$(xcrun simctl launch "$UDID" com.kotlinconf.iosapp)"
IOS_PID="$(echo "$LAUNCH_OUT" | awk '{print $2}')"
sleep 3
kill -0 "$IOS_PID" || { echo "iOS Schedule launch exited early"; exit 1; }
echo "IOS_SCHEDULE_ALIVE (PID $IOS_PID)"
xcrun simctl io "$UDID" screenshot "$SCRATCH/ios_schedule.png"
```

Inspect `$SCRATCH/ios_schedule.png` to confirm live Schedule sessions, bottom tabs, and typography
render cleanly.

### 5d. Clean up iOS simulator

```sh
xcrun simctl terminate "$UDID" com.kotlinconf.iosapp 2>/dev/null || true
if [ "$CREATED_SIM" = "1" ]; then
  xcrun simctl shutdown "$UDID" 2>/dev/null || true
  xcrun simctl delete "$UDID" 2>/dev/null || true
fi
```

(If an earlier interrupted run left stale `KotlinConf-Verify` simulators behind, inspect
`xcrun simctl list devices | grep KotlinConf-Verify` and remove them too.)

### 5e. Verify `ios_arm64` native compilation and framework linking (macOS)

Run this after the default-platform `bazel cquery` / `bazel run` steps above to avoid discarding
Bazel's analysis cache twice. This checks `ios_arm64` device compilation and Kotlin/Native
framework linking without requiring a provisioning profile (building `//app/iosApp:KotlinConf`
with `--ios_multi_cpus=arm64` additionally requires the team provisioning profile installed in
Xcode):

```sh
bazel build --platforms=@rules_kotlin//kotlin/native/platforms:ios_arm64 \
  //core:core_native \
  //app/ui-components:ui_components_native \
  //app/shared:shared_native \
  //app/iosApp:shared
```

- When verifying changes to sealed class hierarchies or header-klib behavior, also run:

```sh
bazel test //... --@rules_kotlin//kotlin/settings:experimental_native_header_klibs=false
```

## 6. Cleanup and workspace hygiene

- Ensure no verification background processes remain (backend JVM, Desktop app, spawned Android
  emulator, temporary iOS simulator). If verification started the `adb` server and nothing else is
  using it, stop it with `"$ADB" kill-server`.
- Remove `$SCRATCH` after inspecting all screenshots.
- Verify the working tree was not dirtied by stray runtime files (`kotlinconfg.mv.db`, `files/`,
  `store.properties`):

```sh
rm -rf "$SCRATCH"
git status --short
```

## 7. Report

Summarize the verification results per area with concrete evidence:

- **Build & tests:** `bazel build //...` target count, `backend_deploy.jar`, `ios_arm64` native
  targets (on macOS), and `bazel test //...` pass count.
- **Backend:** endpoints verified (live + archived conference, assets, DB sign/vote/feedback +
  admin payload check, expected `/admin/` 404) and log error check.
- **Desktop:** startup/init log (`ConferenceService init successful`), data sync, and rendered UI.
- **Android:** APK badging/version/ABI/permissions, emulator API/ABI, screens verified (onboarding,
  Schedule, Speakers, Golden Kodee, Map, Info), and logcat error check.
- **iOS:** simulator runtime/device, crash/log check, Firebase & cached container verification, and
  onboarding + Schedule UI screenshots.
- **Skipped checks (if any):** state explicitly (e.g., iOS on Linux, or signed `ios_arm64` device
  IPA requiring a local provisioning profile).
