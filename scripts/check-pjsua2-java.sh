#!/usr/bin/env sh
set -eu

PJSUA2_OUTPUT_DIR="${PJSUA2_OUTPUT_DIR:-.deps/pjproject/pjsip-apps/src/swig/java/output}"
PJSUA2_LIB_NAME="$(scripts/pjsua2-lib-name.sh)"

if [ ! -f "${PJSUA2_OUTPUT_DIR}/${PJSUA2_LIB_NAME}" ]; then
  echo "${PJSUA2_LIB_NAME}が見つかりません。先に利用OS向けのPJSIP build scriptを実行してください。" >&2
  exit 1
fi

if [ ! -f "${PJSUA2_OUTPUT_DIR}/test.class" ]; then
  echo "PJSUA2 Java test.classが見つかりません。先に利用OS向けのPJSIP build scriptを実行してください。" >&2
  exit 1
fi

java -Djava.library.path="${PJSUA2_OUTPUT_DIR}" -cp "${PJSUA2_OUTPUT_DIR}" test
