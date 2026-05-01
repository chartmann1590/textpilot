#!/usr/bin/env sh
set -eu

wait_for_emulator_ready() {
  deadline_epoch=$(( $(date +%s) + 420 ))
  while [ "$(date +%s)" -lt "${deadline_epoch}" ]; do
    adb wait-for-device || true
    boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    sdk="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"

    if [ "${boot}" = "1" ] && echo "${sdk}" | grep -Eq '^[0-9]+$'; then
      if adb shell cmd package list packages >/dev/null 2>&1; then
        echo "Emulator ready (sdk=${sdk})"
        adb devices -l
        adb shell getprop ro.build.version.release || true
        adb shell getprop ro.build.version.sdk || true
        return 0
      fi
    fi

    # Recover from transient adb/device stalls seen in CI.
    adb reconnect offline >/dev/null 2>&1 || true
    sleep 5
  done

  echo "Timed out waiting for emulator readiness"
  adb devices -l || true
  return 1
}

run_connected_tests() {
  ./gradlew :presentation:connectedNoAnalyticsDebugAndroidTest \
    -Pandroid.experimental.androidTest.useUnifiedTestPlatform=false \
    --stacktrace --no-daemon
}

wait_for_emulator_ready
run_connected_tests || {
  echo "First test invocation failed; restarting adb and retrying once in-attempt"
  adb kill-server || true
  sleep 2
  adb start-server
  wait_for_emulator_ready
  run_connected_tests
}
