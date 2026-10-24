#!/usr/bin/env bash
# Gives the mic button back to Google Assistant. Usage: tools/voice_off.sh <TV IP>
set -euo pipefail
TV="${1:?usage: voice_off.sh <TV IP>}:5555"
adb connect "$TV" >/dev/null
sh() { adb -s "$TV" shell "$@"; }
SDK=$(sh getprop ro.build.version.sdk | tr -d '\r')
K=com.google.android.katniss
sh pm enable $K
if [ "$SDK" -ge 29 ]; then   # Android 9 has no roles (no `cmd role`): the settings below are all there is
  sh cmd role add-role-holder --user 0 android.app.role.ASSISTANT $K || true
  sh cmd role remove-role-holder --user 0 android.app.role.ASSISTANT io.freemytv || true
fi
sh settings put secure assistant $K/.search.serviceapi.KatnissVoiceInteractionService
sh settings put secure voice_interaction_service $K/.search.serviceapi.KatnissVoiceInteractionService
sh settings put secure voice_recognition_service $K/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService
echo "Done: Google Assistant is back."
