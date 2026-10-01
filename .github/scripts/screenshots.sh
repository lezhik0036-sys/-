#!/usr/bin/env bash
# Screenshot tour: opens every screen of the debug build in preview mode
# (sample data, no lock started) and saves a PNG of each into shots/.
set -u
mkdir -p shots
i=0
for name in welcome permissions series time time_test contact promise review dashboard lock exit code failure success flex flex_plan stats settings; do
  i=$((i + 1))
  adb shell am start -W -S -n app.mama/.ui.MainActivity --es mama.preview "$name" > /dev/null
  sleep 3
  adb exec-out screencap -p > "shots/$(printf '%02d' $i)-$name.png"
  echo "shot $i $name $(stat -c %s "shots/$(printf '%02d' $i)-$name.png") bytes"
done
adb shell am force-stop app.mama
crash=$(adb logcat -d -b crash)
if [ -n "$crash" ]; then
  echo "::error::MAMA crashed during the screenshot tour"
  echo "$crash"
  exit 1
fi
