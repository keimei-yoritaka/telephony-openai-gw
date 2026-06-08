#!/usr/bin/env sh
set -eu

case "$(uname -s)" in
  Darwin)
    printf '%s\n' "libpjsua2.jnilib"
    ;;
  Linux)
    printf '%s\n' "libpjsua2.so"
    ;;
  *)
    echo "Unsupported OS for PJSUA2 Java native library: $(uname -s)" >&2
    exit 1
    ;;
esac
