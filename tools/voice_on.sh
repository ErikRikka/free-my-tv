#!/usr/bin/env bash
# Makes Free My TV the TV's assistant: the remote's mic button opens it. Google Assistant is turned off (not removed):
# tools/voice_off.sh brings it back. Usage: tools/voice_on.sh <TV IP>
set -euo pipefail
TV="${1:?usage: voice_on.sh <TV IP>}:5555"
adb connect "$TV" >/dev/null
sh() { adb -s "$TV" shell "$@"; }
sh pm grant io.freemytv android.permission.RECORD_AUDIO
sh pm disable-user --user 0 com.google.android.katniss
sh cmd role add-role-holder --user 0 android.app.role.ASSISTANT io.freemytv   # Android keeps the settings below in step with this role
sh settings put secure voice_interaction_service io.freemytv/io.freemytv.VoiceService
sh settings put secure assistant io.freemytv/io.freemytv.VoiceService
sh settings put secure voice_recognition_service io.freemytv/io.freemytv.VoiceRecognizer
echo "Done: hold the mic button on the remote and speak."
