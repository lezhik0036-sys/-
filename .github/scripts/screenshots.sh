#!/usr/bin/env bash
# Screenshot tour: opens every screen of the debug build in preview mode
# (sample data, no lock started) and saves a PNG of each into shots/.
set -u
mkdir -p shots
i=0
for name in welcome permissions series time time_test contact promise commitment review dashboard edit cancel_launch dashboard_active needs_attention restart_scheduled lock emergency exit code failure restart_time restart_commitment success flex flex_plan stats settings \
    ds_palette ds_type ds_buttons ds_cards ds_notes ds_light ds_scenic ds_recovery ds_lock_shell ds_sheet ds_test ds_scenes \
    progress_early progress_middle progress_final motivation stats_history stats_empty failure_path path; do
  i=$((i + 1))
  adb shell am start -W -S -n app.mama/.ui.MainActivity --es mama.preview "$name" > /dev/null
  sleep 3
  adb exec-out screencap -p > "shots/$(printf '%02d' $i)-$name.png"
  echo "shot $i $name $(stat -c %s "shots/$(printf '%02d' $i)-$name.png") bytes"
done
# The welcome once more on a narrower phone: 720x1440 px at 320 dpi (360 x 720 dp).
adb shell wm size 720x1440
adb shell wm density 320
sleep 2
i=$((i + 1))
adb shell am start -W -S -n app.mama/.ui.MainActivity --es mama.preview welcome > /dev/null
sleep 3
adb exec-out screencap -p > "shots/$(printf '%02d' $i)-welcome_narrow.png"
echo "shot $i welcome_narrow $(stat -c %s "shots/$(printf '%02d' $i)-welcome_narrow.png") bytes"
adb shell wm size reset
adb shell wm density reset
sleep 2
adb shell am force-stop app.mama
# Only MAMA's own crashes count (system apps on emulator images crash too).
crash=$(adb logcat -d -b crash | grep -E -A 40 "Process: app\.mama|>>> app\.mama <<<" || true)
if [ -n "$crash" ]; then
  echo "::error::MAMA crashed during the screenshot tour"
  echo "$crash"
  exit 1
fi
