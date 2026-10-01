#!/usr/bin/env bash
# Installs the debug APK on the running emulator, opens MAMA like a tap on its
# icon, and fails with the crash stack trace if the app dies during startup.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.mama/.ui.MainActivity
sleep 10
crash=$(adb logcat -d -b crash)
pid=$(adb shell pidof app.mama | tr -d '\r')
focus=$(adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | tr -d '\r')
echo "pid: ${pid:-none}"
echo "$focus"
if [ -n "$crash" ] || [ -z "$pid" ] || ! echo "$focus" | grep -q 'app.mama'; then
  echo "::error::MAMA did not stay open after launch"
  echo "----- crash buffer -----"
  echo "$crash"
  echo "----- AndroidRuntime -----"
  adb logcat -d | grep -A 60 'FATAL EXCEPTION' || true
  exit 1
fi
echo "MAMA launched and stayed in the foreground."
