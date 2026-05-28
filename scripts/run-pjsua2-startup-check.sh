#!/usr/bin/env sh
set -eu

CONFIG_PATH="${1:-config/gateway.pjsua2.example.yaml}"
PJSUA2_OUTPUT_DIR="${PJSUA2_OUTPUT_DIR:-.deps/pjproject/pjsip-apps/src/swig/java/output}"
BUILD_DIR="build/classes"

if [ ! -f "${PJSUA2_OUTPUT_DIR}/libpjsua2.jnilib" ]; then
  echo "libpjsua2.jnilibが見つかりません。先に scripts/build-pjsip-macos.sh を実行してください。" >&2
  exit 1
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
find src/main/java -name '*.java' > build/sources.list
javac -encoding UTF-8 -d "${BUILD_DIR}" @build/sources.list

exec java \
  -Djava.library.path="${PJSUA2_OUTPUT_DIR}" \
  -cp "${BUILD_DIR}:${PJSUA2_OUTPUT_DIR}" \
  com.example.telephonygw.Main \
  --config "${CONFIG_PATH}" \
  --startup-check

