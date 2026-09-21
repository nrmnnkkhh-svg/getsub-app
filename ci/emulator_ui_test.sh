#!/bin/bash
# Emulator UI-test wrapper (called by reactivecircus/android-emulator-runner).
# Ensures the artifacts dir exists, runs the Python driver, and captures
# logcat + package info for post-mortem regardless of the outcome.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p ci-artifacts

adb wait-for-device
echo "device: $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

python3 ci/emulator_ui_test.py
status=$?

adb logcat -d -v time > ci-artifacts/logcat.txt 2>/dev/null
adb logcat -d -b crash > ci-artifacts/crash-log.txt 2>/dev/null
adb shell "dumpsys package com.getsub.share | head -n 60" > ci-artifacts/package-info.txt 2>/dev/null
exit $status
