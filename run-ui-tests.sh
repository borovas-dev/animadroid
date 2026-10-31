#!/usr/bin/env bash
# Runs each AppFlowTest method in its own instrumentation process.
# Why: every test builds a fresh Hilt graph, but a DataStore for settings.json from the previous test is still alive in the
# same process ("multiple DataStores active for the same file"). Use ANDROID_SERIAL to pick a device and
# -PqaSuffix=.qa (second arg) to match the installed applicationId suffix.
set -euo pipefail
SUFFIX="${1:-}"
./gradlew ${SUFFIX:+-PqaSuffix=$SUFFIX} :app:assembleDebug :app:assembleDebugAndroidTest -q
APP="com.borovas.animadroid$SUFFIX"
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
rc=0
for t in settingsTogglePersists homeRendersContinueWatching cloudflareErrorOffersVerification; do
  echo "== $t"
  timeout 120 adb shell am instrument -w -e class "com.borovas.animadroid.AppFlowTest#$t" "$APP.test/com.borovas.animadroid.HiltTestRunner" | tail -4 || rc=1
  adb shell am force-stop "$APP.test"; adb shell am force-stop "$APP"
done
exit $rc
