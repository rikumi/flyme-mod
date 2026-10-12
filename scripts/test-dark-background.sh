#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 "$repo_dir/tests/theme-color/test_custom_system_theme_color_removed.py"
python3 "$repo_dir/tests/theme-color/test_battery_background.py"
python3 "$repo_dir/tests/theme-color/test_anr_dialog.py"
