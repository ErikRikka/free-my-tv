#!/usr/bin/env bash
# Starts a whisper.cpp server the TV can reach on port 8178, on this computer's home-network address only (it has no
# password: not on every network the computer joins). Needs: brew install whisper-cpp and a model file.
# Usage: tools/whisper_server.sh [model.bin]   (or WHISPER_MODEL=...; the default is voice-bridge's models folder)
set -euo pipefail
MODEL="${1:-${WHISPER_MODEL:-$HOME/Claude/Projects/voice-bridge/models/ggml-large-v3-turbo-q5_0.bin}}"
[ -f "$MODEL" ] || { echo "Model not found: $MODEL"; exit 1; }
HOST="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
[ -n "$HOST" ] || { echo "No home-network address (en0/en1): is Wi-Fi or Ethernet up?"; exit 1; }
echo "Whisper server on http://$HOST:8178/inference"
exec whisper-server -m "$MODEL" --host "$HOST" --port 8178 -t 8 -l ru --no-timestamps -ac 768 -bo 1 -nf -sns
