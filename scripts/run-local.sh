#!/usr/bin/env sh
set -eu

CONFIG_PATH="${GATEWAY_CONFIG:-config/gateway.example.yaml}"

if [ -x "./gradlew" ]; then
  exec ./gradlew run --args="--config ${CONFIG_PATH}"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle run --args="--config ${CONFIG_PATH}"
fi

BUILD_DIR="build/classes"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
find src/main/java -name '*.java' > build/sources.list
javac -encoding UTF-8 -d "${BUILD_DIR}" @build/sources.list
exec java -cp "${BUILD_DIR}" com.example.telephonygw.Main --config "${CONFIG_PATH}"

