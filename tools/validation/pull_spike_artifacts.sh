#!/usr/bin/env bash
# Copies the signing-spike artefacts from the emulator's external files directory to $1.
# Run inside the emulator session (android-emulator-runner script). Never fails the job by itself:
# a missing directory is reported here and the validator then marks every check NOT RUN.
set -u
dest="${1:-spike-out}"
mkdir -p "$dest"
dir=$(adb shell find /sdcard/Android/data -type d -name signing-spike 2>/dev/null | tr -d '\r' | head -1)
if [ -z "$dir" ]; then
  echo "signing-spike directory not found on the device" | tee "$dest/PULL_FAILED.txt"
  exit 0
fi
echo "pulling $dir"
adb pull "$dir/." "$dest" || echo "adb pull failed" | tee "$dest/PULL_FAILED.txt"
ls -l "$dest"
exit 0
