#!/usr/bin/env bash
# Downloads the Vosk small models (Apache-2.0) that get bundled into the APK.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/models"
mkdir -p "$DIR"
fetch() { # name url
  if [ ! -s "$DIR/$1.zip" ]; then
    echo "Downloading $1 ..."; curl -fL --retry 3 -o "$DIR/$1.zip.tmp" "$2" && mv "$DIR/$1.zip.tmp" "$DIR/$1.zip"
  fi
}
fetch model-en https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
fetch model-cn https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip
ls -lh "$DIR"
