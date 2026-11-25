---
name: update-rules-kotlin
description: Moves the rules_kotlin pin of this repository (the archive_override in MODULE.bazel) to the last pushed commit of the local checkout in ../rules_kotlin, reviews the commits in between, applies their changes and benefits to the app, verifies build/tests/runtime on all platforms, and commits the update in the style of the previous pin bumps. Use when asked to bump, update or sync rules_kotlin.
allowed-tools: Bash
---

# Update the rules_kotlin pin

The app consumes rules_kotlin (the `kmp` branch of github.com/JetBrains/rules_kotlin) as a GitHub
archive pinned by commit and integrity in the `archive_override` of `MODULE.bazel`. The local
checkout lives next to the app, in `../rules_kotlin` (relative to the root of this repository).
The canonical manual recipe is "Updating rules_kotlin" in `docs/bazel.md`; this skill extends it
with the review of the commits in between.

Run every command from the root of this repository unless stated otherwise. `<old>` is the commit
currently pinned, `<new>` the target commit (full 40-character SHAs; short forms only in prose).

## 1. Find the target commit (last pushed commit of ../rules_kotlin)

```sh
git -C ../rules_kotlin fetch origin
git -C ../rules_kotlin status -sb | head -1           # branch and ahead/behind state
git -C ../rules_kotlin log -1 --format='%H %ci %s' '@{push}' 2>/dev/null \
  || git -C ../rules_kotlin log -1 --format='%H %ci %s' '@{u}'
```

- Use the pushed commit (`@{push}`, or the upstream `@{u}`), **not** the local `HEAD`: local
  unpushed commits are not downloadable from GitHub, so the archive fetch would fail.
- If the checkout is on another branch than the one the app tracks (normally `kmp`), use
  `origin/kmp` explicitly. When in doubt, confirm with
  `git ls-remote https://github.com/JetBrains/rules_kotlin.git refs/heads/kmp`.
- Uncommitted or unpushed local work is not part of the update; mention it in the report.

## 2. Find the current pin

```sh
grep -n -A5 'archive_override' MODULE.bazel           # <old> is in urls / strip_prefix
```

If `<old>` equals `<new>`, report that the pin is already up to date and stop.

## 3. Update MODULE.bazel

In the `archive_override(module_name = "rules_kotlin", ...)`:

- `urls = ["https://github.com/JetBrains/rules_kotlin/archive/<new>.tar.gz"]`
- `strip_prefix = "rules_kotlin-<new>"`
- `integrity = "sha256-<base64>"`, computed from the downloaded archive (never guess or hand-edit
  it), in the scratch/temp directory:

```sh
curl -fsSL -o rk.tar.gz https://github.com/JetBrains/rules_kotlin/archive/<new>.tar.gz
echo "sha256-$(openssl dgst -sha256 -binary rk.tar.gz | openssl base64 -A)"
tar tzf rk.tar.gz | head -1                           # must be rules_kotlin-<new>/ (the strip_prefix)
```

Then replace every other reference to the old commit in the repository (docs mention the short SHA):

```sh
git grep -n -e '<old-short>' -e '<old>' -- ':!*.lock' ':!bazel-*'
```

Typically `docs/bazel.md` references the pinned short SHA in several places; update all of them.

## 4. Review the commits between the old and the new pin

```sh
git -C ../rules_kotlin log --format='=== %h %s%n%b' <old>..<new>
git -C ../rules_kotlin diff --stat <old>..<new>
```

Read the commit messages and diffs, and classify each change against how this app uses the rules.
Focus on:

- **Public API / attributes / providers** removed, renamed or added (`kotlin/*.bzl`,
  `src/main/starlark/**`): grep the app (`BUILD.bazel`, `**/BUILD.bazel`, `bazel/*.bzl`,
  `MODULE.bazel`) for each removed or renamed symbol and adapt usages.
- **New validations / analysis-time failures** (e.g. option formats, names, missing srcs): check
  whether any app target would now be rejected.
- **Toolchain and version pins**: Kotlin JVM compiler, Kotlin/Native release, KSP, the KMP resolver
  (`src/main/starlark/core/repositories/`, rules_kotlin `MODULE.bazel`). If the Kotlin/Native
  release moves, compiler plugin jars pinned for it in `MODULE.bazel` (`KOTLIN_NATIVE`, the dev
  Maven plugin files) and the Metro adapter in `bazel/metro_compat.bzl` may need updating. If the
  resolver release changes, re-pin with `bazel run @kmp_maven//pin` and commit `kmp_manifest.json`.
- **Module extensions** (`kotlin_native`, `kmp_maven`, ...): changed tags or attributes used in
  `MODULE.bazel`.
- **New features or fixes** that the app can benefit from: e.g. workarounds in the app that the new
  rules make unnecessary (search the app's comments/docs for mentions of the limitation), new
  attributes that simplify app targets, changed default behaviour worth documenting.
- **Docs/comments-only and test-only commits**: note them, nothing to apply.

```sh
git -C ../rules_kotlin diff <old>..<new> -- kotlin/ src/main/starlark/ MODULE.bazel | grep -E '^[+-]' | grep -v -E '^[+-]\s*#'
```

Apply the changes that matter (minimal, following the app's code and comment style), and update
`docs/bazel.md` where described behaviour changed. Do not adopt features the app has no use for.

## 5. Verify

First confirm that Bazel resolved the new rules_kotlin pin and integrity:

```sh
bazel mod show_repo rules_kotlin | grep -E 'urls|integrity'   # the new URL and integrity are used
```

Then run the **`$verify-all-platforms`** skill (`.junie/skills/verify-all-platforms/SKILL.md`) to
verify the upgrade end to end:

- full workspace build (`bazel build //...`), `//backend:backend_deploy.jar`, `ios_arm64` native
  targets (on macOS), and all test suites (`bazel test //...`);
- live runtime verification of the backend server, Desktop JVM app, Android app on an emulator,
  and iOS app on a simulator (skipping iOS only on hosts without Xcode);
- if the `rules_kotlin` commit range touches Kotlin/Native compilation or header klibs, also run
  the `--@rules_kotlin//kotlin/settings:experimental_native_header_klibs=false` test pass noted
  in `$verify-all-platforms`.

Additional rules_kotlin upgrade checks:

- `MODULE.bazel.lock`, `kmp_manifest.json` and `maven_install.json` should stay unchanged unless
  a re-pin was intentionally needed; if the lock file changes, keep it consistent with `MODULE.bazel`.
- After `$verify-all-platforms` cleanup finishes, run `git status --short` and verify only the
  intended update files are modified.
- If any build, test, or runtime failure appears, assume it comes from the new rules: check the
  commit range for the cause and fix the app (or report a regression in rules_kotlin) instead of
  weakening the build.
- Optionally compare against the local checkout with `--config=local_rules_kotlin` (`.bazelrc`).

## 6. Commit

Once build, tests, and `$verify-all-platforms` pass, commit the update automatically (only when
all checks pass; otherwise stop and report). First read the previous pin bumps and match their
style:

```sh
git log --format='--- %h%n%B' -i --grep='move rules_kotlin to' -3
```

The convention of those commits:

- **Subject:** `move rules_kotlin to <new-short7>` in lowercase with no trailing period. When the
  bump also changes how the app builds, add a short clause, e.g.
  `move rules_kotlin to c3bf80a and build through the Build Tools API`.
- **Body:** wrapped at about 72 columns, with code, paths, targets and SHAs in backticks. Write it
  in this order:
  1. `Bump the rules_kotlin pin from <old-short7> to <new-short7> (kmp branch), ...`, then what
     the commits in the range bring.
  2. `The integrity is computed from the downloaded GitHub archive with the recipe of
     docs/bazel.md, and the pinned Kotlin and resolver releases stay unchanged, so
     kmp_manifest.json stays as it is.` Adapt this sentence if a re-pin was needed.
  3. One paragraph per change applied to the app (migrated targets, removed workarounds).
  4. `Update the <new-short8> pin reference ... in docs/bazel.md` and any other changed docs.
  5. Optionally a `Look closely:` list of follow-ups.
  6. A `Tests:` list with the commands, counts, and verified runtime platforms, e.g.
     `bazel build //... (N targets) and bazel test //... (N/N) pass against the pinned archive;
     verified backend, Desktop, Android emulator, and iOS simulator.`
- Stage only the files of the update, never `bazel-*`, scratch files or `.junie/` (it is
  git-ignored).
- Pass the message from a file in the scratch/temp directory and add the co-author trailer:

```sh
git add MODULE.bazel docs/bazel.md        # plus any other file changed by the update
git commit -F <scratch>/commit_msg.txt --trailer "Co-authored-by: Junie <junie@jetbrains.com>"
git log -1 --stat
```

Do not push.

## 7. Report

Summarize:

- old → new commit (short SHAs, commit subject and date) and the new integrity;
- the files changed and the commit created (hash and subject);
- build, test, and cross-platform runtime verification results (from `$verify-all-platforms`);
- the impact on the app per commit (or per topic): what was applied, what needed no change and
  why, and anything left as a follow-up.
