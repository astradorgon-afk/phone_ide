#!/usr/bin/env bash
#
# Runs the on-device verification of the Phase 2 execution mechanism.
#
# This is the gate described in ROADMAP.md Phase 2a. It answers one question with a
# measurement rather than an inference:
#
#   Can this app execute a binary from its own data directory via the system linker,
#   on this device, at this target SDK?
#
# It needs a connected device or a running emulator (adb devices must show one). No
# bootstrapped toolchain is required — the test copies /system/bin/sh, which exists everywhere.
#
# Usage:  bash tools/verify-runtime.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Checking for a connected device"
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  cat >&2 <<'MSG'
ERROR: no device or emulator is connected.

  Physical device: enable USB debugging and reconnect.
  Emulator:        sdkmanager --install "emulator" "system-images;android-34;google_apis;x86_64"
                   avdmanager create avd -n mf34 -k "system-images;android-34;google_apis;x86_64"
                   emulator -avd mf34 -no-window -no-audio &

Until this runs, system-linker exec is researched and unit-tested but NOT measured.
MSG
  exit 1
fi
echo "    devices: $(echo "$DEVICES" | tr '\n' ' ')"

echo "==> Clearing previous verification logs"
adb logcat -c || true

echo "==> Building and running ExecMechanismVerificationTest"
set +e
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.mobileforge.runtime.ExecMechanismVerificationTest
GRADLE_STATUS=$?
set -e

echo
echo "==> Verification log (MF.ExecVerify)"
# The test logs its findings regardless of pass/fail, so this is useful either way.
adb logcat -d -s MF.ExecVerify || true

echo
if [ $GRADLE_STATUS -eq 0 ]; then
  cat <<'MSG'
==> RESULT: PASS

  Direct execution from app storage is blocked, and execution through the system
  linker succeeds. ADR-002 holds on this device.

  Update RISKS.md RISK-001 to Verified, and record the device and API level.
MSG
else
  cat <<'MSG'
==> RESULT: FAIL

  Read the log above before changing any code. The two failure modes mean different things:

    directExecIsBlocked failed
        Direct execution SUCCEEDED. W^X does not apply on this device/target, so the
        linker workaround is unnecessary here. Not a bug - record it.

    systemLinkerExecSucceeds failed
        The workaround does NOT work on this device. Phase 2 cannot proceed as designed;
        see the fallback chain in docs/adr/ADR-002-runtime-strategy.md.
MSG
fi

exit $GRADLE_STATUS
