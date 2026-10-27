#!/usr/bin/env bash
# Run Relay Inbox on the Light SDK emulator: an Android 14 AVD with the LightOS emulator app installed as a
# system app (docs/system_app/README.md).
#
#   scripts/emulator.sh boot      start the AVD with a writable /system (needed on every boot) and wait for it
#   scripts/emulator.sh lightos   build :sdk:emulator and push it into /system/priv-app (first time: needs
#                                 sdk/emulator/keys/platform.jks, see the README), then reboot
#   scripts/emulator.sh update    rebuild :sdk:emulator and adb install -r it (keeps uid 1000)
#   scripts/emulator.sh tool      build Relay Inbox for the emulator, install it with -r, and launch it
#   scripts/emulator.sh shot F    save a screenshot of the emulator to F
#
# Settings (environment): AVD (default lp3), ANDROID_HOME (default ~/Projects/Android, else ~/Library/Android/sdk),
# JAVA_HOME (default ~/.jdks/jbr-21.0.11 if it exists). It builds whichever checkout you run it from.
# Nothing here uninstalls anything or touches a phone: every adb call names the emulator.
set -euo pipefail

PACKAGE=com.thelightphone.relayinbox
LIGHTOS=com.thelightphone.sdk.emulator
AVD=${AVD:-lp3}
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "$HOME/Projects/Android" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$d" ] && { ANDROID_HOME=$d; break; }
  done
fi
export ANDROID_HOME
[ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.jdks/jbr-21.0.11" ] && export JAVA_HOME=$HOME/.jdks/jbr-21.0.11
EMULATOR=$ANDROID_HOME/emulator/emulator
ADB_BIN=$ANDROID_HOME/platform-tools/adb
cd "$(git rev-parse --show-toplevel)"

serial() {  # the running emulator for $AVD
  local s
  for s in $("$ADB_BIN" devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/{print $1}'); do
    [ "$("$ADB_BIN" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] && { echo "$s"; return; }
  done
  return 1
}
adb_e() { "$ADB_BIN" -s "$(serial || { echo "emulator.sh: $AVD isn't running; try: scripts/emulator.sh boot" >&2; exit 1; })" "$@"; }

wait_boot() {
  local i
  for i in $(seq 120); do
    s=$(serial 2>/dev/null) && [ "$("$ADB_BIN" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && return
    sleep 2
  done
  echo "emulator.sh: $AVD didn't finish booting" >&2; exit 1
}

root_remount() {
  adb_e root >/dev/null; sleep 2; adb_e wait-for-device
  local out
  out=$(adb_e remount 2>&1); echo "$out"
  if grep -q "reboot" <<<"$out"; then  # the first remount turns verity off and wants a reboot
    adb_e reboot; sleep 5; wait_boot
    adb_e root >/dev/null; sleep 2; adb_e wait-for-device; adb_e remount
  fi
}

# Build with tool/lighttool.toml pointing at the emulator's LightOS, then put the committed file back whatever
# happens: Light builds releases from it, and it must say serverPackage = "com.lightos".
emulator_build() {
  local toml=tool/lighttool.toml backup
  backup=$(mktemp); cp "$toml" "$backup"
  trap 'cp "$backup" "'"$toml"'"; rm -f "$backup"' EXIT
  sed 's/^serverPackage = .*/serverPackage = "'"$LIGHTOS"'"/' "$backup" > "$toml"
  grep -qx "serverPackage = \"$LIGHTOS\"" "$toml" \
    || { echo "emulator.sh: tool/lighttool.toml needs a serverPackage line" >&2; exit 1; }
  ./gradlew "$@"
  cp "$backup" "$toml"; rm -f "$backup"; trap - EXIT
}

case "${1:-}" in
  boot)
    if serial >/dev/null 2>&1; then echo "$AVD is already running ($(serial)); writable /system only if it was booted with -writable-system"; exit 0; fi
    mkdir -p "$HOME/.cache/$AVD"
    # Public DNS: the host resolver can fail silently inside the emulator.
    setsid nohup "$EMULATOR" -avd "$AVD" -writable-system -no-snapshot -no-boot-anim -no-audio ${EMULATOR_FLAGS:--no-window} \
      -dns-server 1.1.1.1,8.8.8.8 > "$HOME/.cache/$AVD/emulator.log" 2>&1 < /dev/null &
    sleep 5; wait_boot; echo "$AVD booted as $(serial) (log: ~/.cache/$AVD/emulator.log)" ;;
  lightos)
    [ -f sdk/emulator/keys/platform.jks ] || { echo "emulator.sh: make sdk/emulator/keys/platform.jks first (docs/system_app/README.md, step 3)" >&2; exit 1; }
    ./gradlew :sdk:emulator:assembleDebug
    root_remount
    adb_e shell mkdir -p /system/priv-app/LightOSEmulator
    adb_e push sdk/emulator/build/outputs/apk/debug/emulator-debug.apk /system/priv-app/LightOSEmulator/LightOSEmulator.apk
    adb_e reboot; sleep 5; wait_boot
    adb_e shell cmd package set-home-activity "$LIGHTOS/.MainActivity" >/dev/null
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb_e shell settings put global $k 0; done
    adb_e shell pm path "$LIGHTOS"
    adb_e shell dumpsys package "$LIGHTOS" | grep -m1 "uid=" ;;
  update)
    ./gradlew :sdk:emulator:assembleDebug
    adb_e install -r sdk/emulator/build/outputs/apk/debug/emulator-debug.apk ;;
  tool)
    emulator_build :tool:assembleDebug
    adb_e install -r tool/build/outputs/apk/debug/tool-debug.apk
    adb_e shell monkey -p "$PACKAGE" 1 >/dev/null
    echo "launched $PACKAGE on $(serial)" ;;
  shot)
    [ -n "${2:-}" ] || { echo "usage: scripts/emulator.sh shot FILE.png" >&2; exit 1; }
    adb_e exec-out screencap -p > "$2"; echo "$2" ;;
  *)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
