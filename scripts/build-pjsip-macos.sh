#!/usr/bin/env sh
set -eu

PJSIP_VERSION="${PJSIP_VERSION:-2.17}"
DEPS_DIR="${DEPS_DIR:-.deps}"
PJPROJECT_DIR="${PJPROJECT_DIR:-${DEPS_DIR}/pjproject}"
PREFIX_DIR="${PJSIP_PREFIX:-${DEPS_DIR}/pjsip-macos}"
ROOT_DIR="$(pwd)"
ABS_PJPROJECT_DIR="${ROOT_DIR}/${PJPROJECT_DIR}"
ABS_PREFIX_DIR="${ROOT_DIR}/${PREFIX_DIR}"

if ! command -v git >/dev/null 2>&1; then
  echo "gitが見つかりません。" >&2
  exit 1
fi

if ! command -v swig >/dev/null 2>&1; then
  echo "swigが見つかりません。先に scripts/bootstrap-macos-deps.sh を実行してください。" >&2
  exit 1
fi

if [ ! -d "${ABS_PJPROJECT_DIR}/.git" ]; then
  mkdir -p "${DEPS_DIR}"
  git clone --depth 1 --branch "${PJSIP_VERSION}" https://github.com/pjsip/pjproject.git "${ABS_PJPROJECT_DIR}"
fi

cd "${ABS_PJPROJECT_DIR}"

cat > pjlib/include/pj/config_site.h <<'CONFIG_SITE'
#define PJMEDIA_HAS_VIDEO 0
#define PJMEDIA_HAS_G711_CODEC 1
#include <pj/config_site_sample.h>
CONFIG_SITE

./configure CFLAGS="-fPIC" --prefix="${ABS_PREFIX_DIR}"
make dep
make
make install

cd pjsip-apps/src/swig/java
make
make install

echo "PJSIP ${PJSIP_VERSION} macOS build completed."
echo "PJPROJECT_DIR=${ABS_PJPROJECT_DIR}"
echo "PJSIP_PREFIX=${ABS_PREFIX_DIR}"
