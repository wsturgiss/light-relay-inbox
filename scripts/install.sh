#!/usr/bin/env bash
# Install the debug build on the phone, in colour.
#
# Grants WRITE_SECURE_SETTINGS so the app can lift LightOS's greyscale filter
# while it's in front (see LightDisplayColor), and re-locks the phone to
# portrait. Doesn't launch the app: that would take over the screen.
#
#   scripts/install.sh [path/to/tool-debug.apk]
set -euo pipefail

PACKAGE=com.thelightphone.relayinbox
cd "$(dirname "$0")/.."
APK=${1:-tool/build/outputs/apk/debug/tool-debug.apk}
ADB=${ADB:-$(command -v adb || echo /home/will/Projects/Android/platform-tools/adb)}

"$ADB" install -r "$APK"
"$ADB" shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS
echo "Colour: granted"

if [ "$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')" != 0 ]; then
    echo "Auto-rotate was on; locking to portrait"
fi
"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0
