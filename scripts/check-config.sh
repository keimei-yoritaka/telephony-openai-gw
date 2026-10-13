#!/usr/bin/env sh
set -eu

CONFIG_PATH="${1:-config/gateway.example.yaml}"
BUILD_DIR="build/classes"
JAVA_DEPS="$(sh scripts/java-deps-classpath.sh)"

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
find src/main/java -name '*.java' > build/sources.list
javac -encoding UTF-8 -cp "${JAVA_DEPS}" -d "${BUILD_DIR}" @build/sources.list
java -cp "${BUILD_DIR}:${JAVA_DEPS}" com.example.telephonygw.Main --config "${CONFIG_PATH}" --check-config

