#!/usr/bin/env sh
set -eu

CONFIG_PATH="${1:-config/gateway.pjsua2.example.yaml}"
PJSUA2_OUTPUT_DIR="${PJSUA2_OUTPUT_DIR:-.deps/pjproject/pjsip-apps/src/swig/java/output}"
BUILD_DIR="build/classes"
PJSUA2_LIB_NAME="$(scripts/pjsua2-lib-name.sh)"

if [ ! -f "${PJSUA2_OUTPUT_DIR}/${PJSUA2_LIB_NAME}" ]; then
  echo "${PJSUA2_LIB_NAME}が見つかりません。先に利用OS向けのPJSIP build scriptを実行してください。" >&2
  exit 1
fi

if ! awk '
  $1 == "backend:" && tolower($2) == "pjsua2" { found = 1 }
  $1 == "sip.backend:" && tolower($2) == "pjsua2" { found = 1 }
  END { exit found ? 0 : 1 }
' "${CONFIG_PATH}"; then
  echo "PJSUA2通常起動には sip.backend または session.*.sip.backend が pjsua2 の設定ファイルを指定してください: ${CONFIG_PATH}" >&2
  exit 1
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
find src/main/java -name '*.java' > build/sources.list
if [ -d src/pjsua2/java ]; then
  find src/pjsua2/java -name '*.java' >> build/sources.list
fi
javac -encoding UTF-8 -cp "${PJSUA2_OUTPUT_DIR}" -d "${BUILD_DIR}" @build/sources.list

exec java \
  -Djava.library.path="${PJSUA2_OUTPUT_DIR}" \
  -cp "${BUILD_DIR}:${PJSUA2_OUTPUT_DIR}" \
  com.example.telephonygw.Main \
  --config "${CONFIG_PATH}"
