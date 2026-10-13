#!/usr/bin/env sh
set -eu
JAVA_DEPS="$(sh scripts/java-deps-classpath.sh)"
BUILD_DIR=build/check-voice
mkdir -p "${BUILD_DIR}"
find src/main/java src/check/java -name '*.java' > "${BUILD_DIR}/sources.list"
javac -encoding UTF-8 -cp "${JAVA_DEPS}" -d "${BUILD_DIR}" @"${BUILD_DIR}/sources.list"
java -cp "${BUILD_DIR}:${JAVA_DEPS}" com.example.telephonygw.openai.VoiceIntegrationCheck
