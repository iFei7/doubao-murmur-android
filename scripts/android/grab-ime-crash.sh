#!/usr/bin/env bash
# Grab the IME crash stack from a connected device/emulator.
#
# Usage (device connected via adb, USB debugging enabled):
#   scripts/android/grab-ime-crash.sh
#
# It reads what has ALREADY been recorded (crash buffer + DropBox), so you do not
# need to reproduce the crash while the script is running. Reproduce first, then run.
# Output is written next to the current directory as ime-crash-<timestamp>.log.
set -euo pipefail

# Resolve adb: PATH first, then ANDROID_HOME, then the local dev-tools SDK layout.
if command -v adb >/dev/null 2>&1; then
  ADB=adb
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
elif [ -x "/c/Users/${USERNAME:-}/dev-tools/android-sdk/platform-tools/adb.exe" ]; then
  ADB="/c/Users/${USERNAME:-}/dev-tools/android-sdk/platform-tools/adb.exe"
else
  echo "adb not found. Add \$ANDROID_HOME/platform-tools to PATH." >&2
  exit 1
fi

DEVICE_COUNT=$("$ADB" devices | grep -c $'\tdevice$' || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "No adb device connected. Connect the TV/phone and enable USB debugging." >&2
  exit 1
fi

OUT="ime-crash-$(date +%Y%m%d-%H%M%S).log"
{
  echo "===== device ====="
  "$ADB" shell getprop ro.product.model
  "$ADB" shell getprop ro.build.version.release
  echo
  echo "===== installed murmur packages ====="
  "$ADB" shell pm list packages | grep -i 'doubao\|murmur' || true
  echo
  echo "===== installed version ====="
  "$ADB" shell dumpsys package com.doubao.murmur.tv | grep -E 'versionName|versionCode' || true
  echo
  echo "===== FATAL EXCEPTION (crash buffer) ====="
  "$ADB" logcat -d -b crash -v time | tail -n 400 || true
  echo
  echo "===== DropBox app crash entries ====="
  "$ADB" shell dumpsys dropbox --print data_app_crash 2>/dev/null | tail -n 400 || true
  echo
  echo "===== ANR / watchdog clues ====="
  "$ADB" logcat -d -v time | grep -i -E 'ANR in|ActivityManager:.*ANR|Watchdog|blocked in|Input dispatching timed out' | tail -n 100 || true
  echo
  echo "===== IME lifecycle lines ====="
  "$ADB" logcat -d -v time | grep -i -E 'InputMethodService|MurmurIme|murmur.tv.ime' | tail -n 150 || true
} > "$OUT" 2>&1

echo "Saved to $OUT"
echo "Send this file back, or paste the section containing 'FATAL EXCEPTION' / 'Caused by'."
