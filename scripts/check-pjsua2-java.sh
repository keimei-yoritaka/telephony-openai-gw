#!/usr/bin/env sh
set -eu

PJSUA2_OUTPUT_DIR="${PJSUA2_OUTPUT_DIR:-.deps/pjproject/pjsip-apps/src/swig/java/output}"
PJSUA2_LIB_NAME="$(scripts/pjsua2-lib-name.sh)"
CHECK_BUILD_DIR="${CHECK_BUILD_DIR:-build/check-pjsua2-java}"

if [ ! -f "${PJSUA2_OUTPUT_DIR}/${PJSUA2_LIB_NAME}" ]; then
  echo "${PJSUA2_LIB_NAME}が見つかりません。先に利用OS向けのPJSIP build scriptを実行してください。" >&2
  exit 1
fi

if [ ! -f "${PJSUA2_OUTPUT_DIR}/org/pjsip/pjsua2/pjsua2JNI.class" ]; then
  echo "PJSUA2 Java binding classが見つかりません。先に利用OS向けのPJSIP build scriptを実行してください。" >&2
  exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "javacが見つかりません。JDKをinstallしてください。" >&2
  exit 1
fi

mkdir -p "${CHECK_BUILD_DIR}"
cat > "${CHECK_BUILD_DIR}/CheckPjsua2Java.java" <<'JAVA'
public final class CheckPjsua2Java {
    private CheckPjsua2Java() {
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.pjsip.pjsua2.pjsua2JNI");
        Class.forName("org.pjsip.pjsua2.Endpoint");
        System.out.println("PJSUA2 Java binding check completed.");
    }
}
JAVA

javac -encoding UTF-8 -cp "${PJSUA2_OUTPUT_DIR}" -d "${CHECK_BUILD_DIR}" "${CHECK_BUILD_DIR}/CheckPjsua2Java.java"
java -Djava.library.path="${PJSUA2_OUTPUT_DIR}" -cp "${PJSUA2_OUTPUT_DIR}:${CHECK_BUILD_DIR}" CheckPjsua2Java
