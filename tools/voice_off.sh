#!/usr/bin/env bash
# Gives the mic button back to Google Assistant. Usage: tools/voice_off.sh <TV IP>
set -euo pipefail
TV="${1:?usage: voice_off.sh <TV IP>}:5555"
adb connect "$TV" >/dev/null
sh() { adb -s "$TV" shell "$@"; }
K=com.google.android.katniss
sh pm enable $K
sh cmd role add-role-holder --user 0 android.app.role.ASSISTANT $K
sh cmd role remove-role-holder --user 0 android.app.role.ASSISTANT io.freemytv || true
sh settings put secure assistant $K/.search.serviceapi.KatnissVoiceInteractionService
sh settings put secure voice_interaction_service $K/.search.serviceapi.KatnissVoiceInteractionService
sh settings put secure voice_recognition_service $K/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService
echo "Done: Google Assistant is back."
