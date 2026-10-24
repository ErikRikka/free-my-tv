#!/usr/bin/env bash
# Makes Free My TV the TV's assistant: the remote's mic button opens it. Google Assistant is turned off (not removed):
# tools/voice_off.sh brings it back. Usage: tools/voice_on.sh <TV IP>
set -euo pipefail
TV="${1:?usage: voice_on.sh <TV IP>}:5555"
adb connect "$TV" >/dev/null
sh() { adb -s "$TV" shell "$@"; }
SDK=$(sh getprop ro.build.version.sdk | tr -d '\r')
sh pm grant io.freemytv android.permission.RECORD_AUDIO
# Ours first, Google's off last: if anything fails on the way, the mic button still has an assistant.
if [ "$SDK" -ge 29 ]; then   # Android 9 has no roles (no `cmd role`): the settings alone do it there
  sh cmd role add-role-holder --user 0 android.app.role.ASSISTANT io.freemytv   # Android keeps the settings below in step
fi
sh settings put secure voice_interaction_service io.freemytv/io.freemytv.VoiceService
sh settings put secure assistant io.freemytv/io.freemytv.VoiceService
sh settings put secure voice_recognition_service io.freemytv/io.freemytv.VoiceRecognizer
sh pm disable-user --user 0 com.google.android.katniss
echo "Done: press the mic button on the remote and speak; press it again to end."
