#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-results
collect_results() {
  adb pull /sdcard/Android/data/br.com.cinemajunto/files/ui-test ui-results/screenshots || true
  adb logcat -d > ui-results/logcat.txt || true
  adb shell dumpsys input_method > ui-results/input-method.txt || true
}
trap collect_results EXIT
adb install "$(find test-apks -name app-debug.apk -print -quit)"
adb install "$(find test-apks -name app-debug-androidTest.apk -print -quit)"
adb shell appops set br.com.cinemajunto SYSTEM_ALERT_WINDOW allow
adb shell pm grant br.com.cinemajunto android.permission.RECORD_AUDIO
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -ge 33 ]; then
  adb shell pm grant br.com.cinemajunto android.permission.POST_NOTIFICATIONS
fi
adb shell settings put secure show_ime_with_hard_keyboard 1
# Keep Android's first-use fullscreen tutorial from dimming the movie screenshots.
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell am instrument -w -r br.com.cinemajunto.test/androidx.test.runner.AndroidJUnitRunner | tee ui-results/test-output.txt
grep -Eq 'OK \([0-9]+ tests\)' ui-results/test-output.txt
