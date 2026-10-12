#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d /tmp/flyme-theme-test.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
"${java_bin}javac" -d "$test_dir" \
    "$repo_dir/app/src/main/java/dev/rikumi/flymemod/DarkBackgroundPolicy.java" \
    "$repo_dir/app/src/main/java/dev/rikumi/flymemod/ThemeColorPalette.java" \
    "$repo_dir/app/src/main/java/dev/rikumi/flymemod/ThemeColorTargets.java" \
    "$repo_dir/app/src/main/java/dev/rikumi/flymemod/ThemeTabPolicy.java" \
    "$repo_dir/app/src/main/java/dev/rikumi/flymemod/ThemeIconPolicy.java" \
    "$repo_dir/app/src/main/java/dev/rikumi/flymemod/ScopeRequirements.java" \
    "$repo_dir/tests/theme-color/ThemeColorPaletteTest.java"
"${java_bin}java" -cp "$test_dir" dev.rikumi.flymemod.ThemeColorPaletteTest
