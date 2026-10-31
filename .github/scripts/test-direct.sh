#!/usr/bin/env bash
set -euo pipefail
package=br.com.cinemajunto.direto19
mkdir -p direct-results
trap 'adb logcat -d > direct-results/logcat.txt; adb pull /sdcard/Android/data/$package/files/direct-test direct-results/screenshots || true' EXIT
adb install "$(find test-apks -name app-debug.apk -print -quit)"
adb install "$(find test-apks -name app-debug-androidTest.apk -print -quit)"
adb shell am instrument -w -r -e class br.com.cinemajunto.DirectTransportTest "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee direct-results/tests.txt
grep -Eq 'OK \([0-9]+ tests\)' direct-results/tests.txt
! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' direct-results/tests.txt
