#!/usr/bin/env bash
set -euo pipefail
package=br.com.cinemajunto.direto19
mkdir -p direct-results
node server/server.mjs > direct-results/signaling.log 2>&1 &
server_pid=$!
node server/cloud-broker-fixture.mjs > direct-results/cloud-broker.log 2>&1 &
broker_pid=$!
adb reverse tcp:10001 tcp:10001
adb reverse tcp:10000 tcp:10000
trap 'kill $server_pid $broker_pid || true; adb logcat -d > direct-results/logcat.txt; adb pull /sdcard/Android/data/$package/files/direct-test direct-results/screenshots || true' EXIT
adb install "$(find test-apks -name app-debug.apk -print -quit)"
adb install "$(find test-apks -name app-debug-androidTest.apk -print -quit)"
adb shell am instrument -w -r -e class br.com.cinemajunto.DirectTransportTest,br.com.cinemajunto.DirectRoomTest "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee direct-results/tests.txt
grep -Eq 'OK \([0-9]+ tests\)' direct-results/tests.txt
! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' direct-results/tests.txt
