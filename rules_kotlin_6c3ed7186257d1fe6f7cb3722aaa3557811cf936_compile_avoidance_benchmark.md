# rules_kotlin 6c3ed718 migration — compile-avoidance benchmark

Migration of the kotlinconf-app `rules_kotlin` pin from `3dcd5e182d32d91a711e9639b12939b73d15f7b3`
to `6c3ed7186257d1fe6f7cb3722aaa3557811cf936` ("Compile KMP dependents against ABI jars and
Kotlin/Native header klibs"), with both opt-ins on, and its measured effect on build times.

## Summary

- The migration builds and tests green: `bazel build //...` (5705 actions) and `bazel test //...`
  (16/16) with header klibs on, and `bazel test //...` (16/16) again with header klibs off (the
  sealed-hierarchy safety run). No opt-out tag was needed, so Compose + Metro + serialization
  tolerate `-Xheader-klib-path` on this app. `kmp_manifest.json` is untouched.
- **Compile avoidance works for an edit that keeps the ABI.** After a private declaration is added
  to `core` (see [Incremental edit](#incremental-edit)), the JVM dependents stay cached:
  `//app/shared` goes from 5.3 s to 0.5–1.0 s, `//app/desktopApp` from 5.6 s to 0.6–0.8 s,
  `//backend` from 3.5 s to 0.6–1.0 s, and `//app/androidApp` from 13.1 s to 5.9–10.2 s. Action
  counts fall by the dependent compiles (4→2, 7→4, 6→4, 21→17).
- **iOS stays relink-bound.** Header klibs avoid the `shared_native` recompile (B: 3 actions vs
  4 in A/C), but the Kotlin/Native framework link reads full klibs and dominates the time at about
  210 s in every config.
- **Clean builds:** no measurable ABI-generation overhead; the action counts are identical in all
  configs and the time differences are within the noise of single runs (see Caveats).

## Configurations

| label | pin | flags |
|---|---|---|
| A | `3dcd5e18` | defaults (tree before the migration) |
| B | `6c3ed718` | delivered tree: `.bazelrc` `experimental_native_header_klibs=true` + toolchain `experimental_use_abi_jars = True` |
| C | `6c3ed718` | B plus `--@rules_kotlin//kotlin/settings:experimental_native_header_klibs=false --define=experimental_use_abi_jars=0` (post-migration defaults: only the KMP ABI jars) |

Override check (C): `bazel build //backend --define=experimental_use_abi_jars=0` produces
`core_jvm-kt.abi.jar` but no `backend_lib-kt.abi.jar`; without the define both exist. Artifact
check (B): `core_jvm-kt.abi.jar`, `shared_jvm-kt.abi.jar`, `core_android-kt.abi.jar` and
`core_native.header.klib`, `ui_components_native.header.klib`, `shared_native.header.klib` exist
in `bazel-out`.

## Incremental edit

A private declaration appended to `core/src/commonMain/kotlin/org/jetbrains/kotlinconf/EventTimeZone.kt`:

```diff
 val EVENT_TIME_ZONE: TimeZone = TimeZone.of("Europe/Berlin")
+
+// Build benchmark touch: a private declaration in a file whose facade class already exists, so the
+// ABI jar and the header klib stay the same.
+private val buildBenchmarkTouch = 42
```

The file facade class `EventTimeZoneKt` already exists, so the edit keeps the ABI: `core_jvm`'s
ABI jar was confirmed byte-identical before and after. (Adding a first top-level declaration to a
file without one would create a new public facade class, change the ABI jar and recompile every
dependent.)

## Results

Elapsed seconds (bazel `INFO: Elapsed time`) / executed actions. One run per cell.

### clean

| target | A | B | C |
|---|---|---|---|
| `//core` | 28.7 / 175 | 28.6 / 175 | 26.3 / 175 |
| `//app/ui-components:ui_components` | 33.7 / 189 | 34.1 / 189 | 32.0 / 189 |
| `//app/shared` | 38.4 / 198 | 39.8 / 198 | 38.0 / 198 |
| `//app/desktopApp` | 40.7 / 342 | 42.4 / 342 | 40.5 / 342 |
| `//backend` | 35.1 / 476 | 35.6 / 476 | 32.0 / 476 |
| `//app/androidApp` | 233.6 / 4076 | 242.3 / 4076 | 189.6 / 4076 |
| `//app/iosApp:KotlinConf` | 304.5 / 1003 | 325.8 / 1003 | 281.8 / 1003 |
| `//...` | 497.6 / 5705 | 649.7 / 5705 | 449.1 / 5705 |

### cached (no-op rebuild)

| target | A | B | C |
|---|---|---|---|
| `//core` | 0.10 | 0.09 | 0.10 |
| `//app/ui-components:ui_components` | 0.11 | 0.10 | 0.10 |
| `//app/shared` | 0.11 | 0.12 | 0.12 |
| `//app/desktopApp` | 0.12 | 0.14 | 0.13 |
| `//backend` | 0.13 | 0.13 | 0.14 |
| `//app/androidApp` | 1.35 | 1.07 | 1.10 |
| `//app/iosApp:KotlinConf` | 0.56 | 0.62 | 0.57 |
| `//...` | 1.68 | 1.71 | 1.30 |

All cached rows run 1 action.

### incremental — private declaration in `EventTimeZone.kt`

| target | A | B | C |
|---|---|---|---|
| `//core` | 0.89 / 3 | 1.05 / 3 | 0.75 / 3 |
| `//app/ui-components:ui_components` (control) | 0.13 / 1 | 0.13 / 1 | 0.09 / 1 |
| `//app/shared` | 5.32 / 4 | 1.01 / 2 | 0.55 / 2 |
| `//app/desktopApp` | 5.61 / 7 | 0.79 / 4 | 0.56 / 4 |
| `//backend` | 3.50 / 6 | 1.01 / 4 | 0.61 / 4 |
| `//app/androidApp` | 13.13 / 21 | 10.21 / 17 | 5.95 / 17 |
| `//app/iosApp:KotlinConf` | 210.1 / 4 | 241.8 / 3 | 209.9 / 4 |
| `//...` | 237.7 / 54 | 197.7 / 34 | 215.4 / 35 |

`//app/ui-components:ui_components` does not depend on `core`, so it is the no-op control.

## Attribution

- **A → C (default KMP ABI jars, no opt-in).** This is all of the JVM/Android gain. With the ABI
  unchanged, `shared_jvm`, the desktop, the backend and the Android compiles that depend on `core`
  stay cached: 2–4 fewer actions per target, and dependent rebuilds drop from seconds to well under
  1 s. In `//...`, 54 → 35 actions. No clean-build regression is visible: C clean is at or below A
  on every row, so the extra ABI-jar output costs less than the noise.
- **C → B (header klibs + ABI jars for plain JVM libraries).** Header klibs avoid the `shared_native`
  recompile after the `core` edit (iOS 4 → 3 actions). The framework relink stays, so the wall time
  does not move: the B iOS row (241.8 s) is slower than C, and that is noise around a ~210 s link,
  not a cost of the flag. The toolchain flag gives ABI jars to `backend_lib`, `desktop_lib` and the
  tests, but nothing in this benchmark depends on them through a changed library, so it does not
  move any number here. It pays off on edits inside the `backend`/desktop libraries that have
  dependents (tests). In `//...`, B runs 34 actions vs 35 in C.
- **A → B (delivered).** JVM/Android incremental builds after an ABI-neutral `core` edit: 3.5–5.6 s
  → about 1 s, Android 13.1 → 10.2 s. iOS saves one Kotlin/Native compile but stays relink-bound.
  Edits that change the ABI (an internal or public signature, a `const val`, a default argument,
  the body of an `inline` function, a new facade class) recompile the dependents, as expected.

## Caveats

- **Single run per cell, on a workstation in use.** The heavy clean rows vary by tens of seconds
  across configs. B `//...` clean (649.7 s) is a clear outlier, while C, with the identical action
  graph, took 449 s. Do not read the clean differences as effects; the action counts are identical
  in all configs.
- The A incremental rows were measured on an export of the pre-migration commit (old pin); the A
  clean and cached rows on the checkout itself before the migration.
- iOS incremental keeps the Kotlin/Native framework relink in every config: links read full klibs.
- Sealed hierarchies: `bazel test //...` passed 16/16 with header klibs on and off; no exhaustive
  `when` over a sealed type with hidden subclasses exists in this app.
- No opt-out tags (`kt_abi_plugin_incompatible`, `kt_native_header_klib_incompatible`) were needed.
- Machine: macOS arm64, 12 cores, 64 GB RAM, Bazel 9.3.1, no disk cache, shared repository cache.
  No timed log contains `Fetching`/`Downloading`.

## Reproduction

Per target, sequentially, with a fresh `--output_base`:

1. untimed `bazel fetch <target>` and `bazel build --nobuild <target>` (repositories and analysis
   warm-up), then `bazel clean`;
2. timed `bazel build <target>` (clean), then again (cached);
3. apply the patch of [Incremental edit](#incremental-edit), timed `bazel build <target>`
   (incremental), revert the patch, check that the tracked tree is unchanged, untimed rebuild;
4. `bazel shutdown` and delete the output base.

Config C adds its flags to every command:

```sh
--@rules_kotlin//kotlin/settings:experimental_native_header_klibs=false --define=experimental_use_abi_jars=0
```
