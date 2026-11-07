#!/usr/bin/env sh
set -eu
package_name="${1:-app.englishdub.preview}"
adb shell dumpsys meminfo "$package_name"
adb shell dumpsys battery
adb shell dumpsys thermalservice
