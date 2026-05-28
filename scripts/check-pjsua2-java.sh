#!/usr/bin/env sh
set -eu

PJSUA2_OUTPUT_DIR="${PJSUA2_OUTPUT_DIR:-.deps/pjproject/pjsip-apps/src/swig/java/output}"

if [ ! -f "${PJSUA2_OUTPUT_DIR}/libpjsua2.jnilib" ]; then
  echo "libpjsua2.jnilibが見つかりません。先に scripts/build-pjsip-macos.sh を実行してください。" >&2
  exit 1
fi

if [ ! -f "${PJSUA2_OUTPUT_DIR}/test.class" ]; then
  echo "PJSUA2 Java test.classが見つかりません。先に scripts/build-pjsip-macos.sh を実行してください。" >&2
  exit 1
fi

java -Djava.library.path="${PJSUA2_OUTPUT_DIR}" -cp "${PJSUA2_OUTPUT_DIR}" test

