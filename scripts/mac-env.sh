#!/usr/bin/env bash
# Source from a Mac terminal before Gradle or device commands.
# Existing choices win; no global shell configuration is changed.
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# The 37.0.1 adb server crashes in libunwind on the handoff Mac. Keep a
# separately installed fallback out of the SDK; callers can select another one.
dub_adb_dir="${DUB_ADB_DIR:-$ANDROID_HOME/platform-tools}"
if [[ -z "${DUB_ADB_DIR:-}" && -x "$HOME/.local/share/englishdub-tools/platform-tools-35.0.2/platform-tools/adb" ]]; then
  dub_adb_dir="$HOME/.local/share/englishdub-tools/platform-tools-35.0.2/platform-tools"
fi
export PATH="$JAVA_HOME/bin:$dub_adb_dir:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$HOME/.local/bin:$PATH"
export AUTONOM_ADB="$dub_adb_dir/adb"
unset dub_adb_dir
