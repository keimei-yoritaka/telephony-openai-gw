#!/usr/bin/env sh
set -eu

# Keep direct javac scripts and Gradle on the same pinned JSON dependency.
GSON_VERSION=2.13.2
GSON_SHA256=dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0
JAR_PATH=".deps/java/gson-${GSON_VERSION}.jar"
mkdir -p .deps/java
if [ ! -f "${JAR_PATH}" ]; then
  TEMP_JAR="$(mktemp .deps/java/gson-download.XXXXXX)"
  trap 'rm -f "${TEMP_JAR}"' EXIT HUP INT TERM
  curl -fLsS --connect-timeout 10 --max-time 60 \
    "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/${GSON_VERSION}/gson-${GSON_VERSION}.jar" \
    -o "${TEMP_JAR}"
  ACTUAL_SHA="$(openssl dgst -sha256 "${TEMP_JAR}" | awk '{print $NF}')"
  if [ "${ACTUAL_SHA}" != "${GSON_SHA256}" ]; then
    echo "Gsonのchecksumが一致しません。" >&2
    exit 1
  fi
  mv "${TEMP_JAR}" "${JAR_PATH}"
fi
ACTUAL_SHA="$(openssl dgst -sha256 "${JAR_PATH}" | awk '{print $NF}')"
if [ "${ACTUAL_SHA}" != "${GSON_SHA256}" ]; then
  echo "Gsonのchecksumが一致しません: ${JAR_PATH}" >&2
  exit 1
fi
printf '%s\n' "${JAR_PATH}"
