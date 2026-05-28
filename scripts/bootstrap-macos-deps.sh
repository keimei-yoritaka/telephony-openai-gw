#!/usr/bin/env sh
set -eu

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrewが見つかりません。SWIGを手動でinstallしてください。" >&2
  exit 1
fi

if ! command -v swig >/dev/null 2>&1; then
  brew install swig
fi

echo "macOS dependency check completed."

