#!/usr/bin/env sh
set -eu

ADB="${ADB:-adb}"

adb_device() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    "${ADB}" -s "${ANDROID_SERIAL}" "$@"
  else
    "${ADB}" "$@"
  fi
}

print_adb_diagnostics() {
  echo "ADB diagnostics:"
  "${ADB}" devices -l || true

  if [ -n "${ANDROID_SERIAL:-}" ]; then
    adb_device get-state || true
    adb_device shell getprop sys.boot_completed || true
    adb_device shell getprop ro.build.version.release || true
    adb_device shell getprop ro.build.version.sdk || true
  fi
}

on_exit() {
  status=$?
  if [ "${status}" -ne 0 ]; then
    print_adb_diagnostics
  fi
  exit "${status}"
}

trap on_exit EXIT

select_emulator() {
  serial="$("${ADB}" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
  if [ -n "${serial}" ]; then
    export ANDROID_SERIAL="${serial}"
    return 0
  fi

  return 1
}

probe_full_getprop() {
  probe_file="${RUNNER_TEMP:-/tmp}/qksms-getprop.txt"
  rm -f "${probe_file}"

  # Gradle/ddmlib probes compatibility with a full getprop call; wait until
  # that path is responsive so the emulator is not reported as Unknown API Level.
  if command -v timeout >/dev/null 2>&1; then
    timeout 3 "${ADB}" -s "${ANDROID_SERIAL}" shell getprop > "${probe_file}" 2>/dev/null
  else
    "${ADB}" -s "${ANDROID_SERIAL}" shell getprop > "${probe_file}" 2>/dev/null
  fi

  grep -Eq '^\[ro\.build\.version\.sdk\]: \[[0-9]+\]' "${probe_file}"
}

wait_for_emulator_ready() {
  deadline_epoch=$(( $(date +%s) + 600 ))
  stable_property_reads=0

  while [ "$(date +%s)" -lt "${deadline_epoch}" ]; do
    "${ADB}" start-server >/dev/null

    if ! select_emulator; then
      "${ADB}" reconnect offline >/dev/null 2>&1 || true
      sleep 5
      continue
    fi

    adb_device wait-for-device || true
    state="$(adb_device get-state 2>/dev/null | tr -d '\r' || true)"
    boot="$(adb_device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    sdk="$(adb_device shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"

    if [ "${state}" = "device" ] && [ "${boot}" = "1" ] && echo "${sdk}" | grep -Eq '^[0-9]+$'; then
      if probe_full_getprop && adb_device shell cmd package list packages >/dev/null 2>&1; then
        stable_property_reads=$((stable_property_reads + 1))
      else
        stable_property_reads=0
      fi

      if [ "${stable_property_reads}" -ge 2 ]; then
        echo "Using emulator ${ANDROID_SERIAL}"
        echo "Emulator ready (sdk=${sdk})"
        "${ADB}" devices -l
        adb_device shell getprop ro.build.version.release || true
        adb_device shell getprop ro.build.version.sdk || true
        return 0
      fi
    else
      stable_property_reads=0
    fi

    # Recover from transient adb/device stalls seen in CI.
    if [ "${state}" != "device" ]; then
      "${ADB}" reconnect offline >/dev/null 2>&1 || true
    fi
    sleep 5
  done

  echo "Timed out waiting for emulator readiness"
  return 1
}

run_connected_tests() {
  # Restart ADB server so AGP 8.x UTP/ddmlib gets a fresh connection and reads
  # the correct API level (stale ddmlib cache causes "API level=1" errors).
  "${ADB}" kill-server 2>/dev/null || true
  "${ADB}" start-server
  serial="${ANDROID_SERIAL:-}"
  if [ -n "${serial}" ]; then
    "${ADB}" -s "${serial}" wait-for-device 2>/dev/null || true
  else
    "${ADB}" wait-for-device 2>/dev/null || true
  fi
  sleep 3
  ./gradlew :presentation:connectedNoAnalyticsDebugAndroidTest \
    --stacktrace --no-daemon
}

wait_for_emulator_ready
run_connected_tests
