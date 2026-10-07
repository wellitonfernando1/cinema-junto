#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-results
adb install "$(find test-apks -name app-debug.apk -print -quit)"
adb install "$(find test-apks -name app-debug-androidTest.apk -print -quit)"
adb shell appops set br.com.cinemajunto SYSTEM_ALERT_WINDOW allow
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -ge 33 ]; then
  adb shell pm grant br.com.cinemajunto android.permission.POST_NOTIFICATIONS
fi
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell am instrument -w -r br.com.cinemajunto.test/androidx.test.runner.AndroidJUnitRunner | tee ui-results/test-output.txt
adb pull /sdcard/Android/data/br.com.cinemajunto/files/ui-test ui-results/screenshots || true
grep -q 'OK (4 tests)' ui-results/test-output.txt
