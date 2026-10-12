#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null; then
  echo "installing gradle via brew"
  brew install gradle
fi
gradle wrapper --gradle-version 8.14
