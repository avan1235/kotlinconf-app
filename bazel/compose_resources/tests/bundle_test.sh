#!/bin/bash
# Checks the compose-resources directory a compose_resources_bundle assembles from the two fixtures
# (//bazel/compose_resources/tests:bundle): the tree of each under its own package, the XML of the
# values directories as .cvr records, every other file copied unchanged, nothing else.
#
# Arguments: the bundle directory, then a file of the fixture's composeResources directory, two
# levels below it (drawable/icon.xml).
set -euo pipefail

bundle="$1"
fixture=$(dirname "$(dirname "$2")")

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

multiplatform="$bundle/composeResources/org.example.fixture.generated.resources"
single="$bundle/composeResources/org.example.fixture.single"
[ -d "$multiplatform" ] || fail "no tree of the multiplatform fixture: $multiplatform"
[ -d "$single" ] || fail "no tree of the JVM-only fixture: $single"

# The values become .cvr files named after the source set of each module; no XML survives there.
for cvr in "$multiplatform/values/strings.commonMain.cvr" "$multiplatform/values-de/strings.commonMain.cvr" \
    "$single/values/strings.main.cvr" "$single/values-de/strings.main.cvr"; do
  [ -f "$cvr" ] || fail "missing $cvr"
  [ "$(head -n 1 "$cvr")" = "version:0" ] || fail "$cvr does not start with the version record"
done
# (-L: in the runfiles, the bundle is a symlink to the directory.)
if find -L "$bundle" -path '*/values*/*.xml' | grep -q .; then
  fail "XML value resources in the bundle: $(find -L "$bundle" -path '*/values*/*.xml')"
fi

# The drawables, fonts and raw files are the sources, byte for byte, in both trees.
for path in drawable/icon.xml drawable/arrow-down.xml drawable-dark/icon.xml font/body.ttf files/data.json files/nested/note.txt; do
  for tree in "$multiplatform" "$single"; do
    [ -f "$tree/$path" ] || fail "missing $tree/$path"
    cmp -s "$fixture/$path" "$tree/$path" || fail "$tree/$path differs from the source"
  done
done

# Nothing else: 8 files per fixture (6 copied, 2 .cvr).
count=$(find -L "$bundle" -type f | wc -l | tr -d ' ')
[ "$count" = "16" ] || fail "expected 16 files in the bundle, found $count: $(find -L "$bundle" -type f)"

echo "PASS: $count files under $bundle"
