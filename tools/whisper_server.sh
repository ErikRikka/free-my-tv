#!/usr/bin/env bash
# Starts a whisper.cpp server the TV can reach (port 8178, all network interfaces). Needs: brew install whisper-cpp
# and the model file (default: voice-bridge's models folder). Usage: tools/whisper_server.sh [model.bin]
set -euo pipefail
MODEL="${1:-$HOME/Claude/Projects/voice-bridge/models/ggml-large-v3-turbo-q5_0.bin}"
[ -f "$MODEL" ] || { echo "Model not found: $MODEL"; exit 1; }
echo "Whisper server on http://$(ipconfig getifaddr en0 2>/dev/null || echo '<this computer>'):8178/inference"
exec whisper-server -m "$MODEL" --host 0.0.0.0 --port 8178 -t 8 -l ru --no-timestamps -ac 768 -bo 1 -nf -sns
