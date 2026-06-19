#!/usr/bin/env sh
set -eu

PJSIP_VERSION="${PJSIP_VERSION:-2.17}"
DEPS_DIR="${DEPS_DIR:-.deps}"
PJPROJECT_DIR="${PJPROJECT_DIR:-${DEPS_DIR}/pjproject}"
PREFIX_DIR="${PJSIP_PREFIX:-${DEPS_DIR}/pjsip-macos}"
ROOT_DIR="$(pwd)"
ABS_PJPROJECT_DIR="${ROOT_DIR}/${PJPROJECT_DIR}"
ABS_PREFIX_DIR="${ROOT_DIR}/${PREFIX_DIR}"

fail_permission() {
  echo "書き込み権限がありません: $1" >&2
  echo "Repository所有userで実行するか、所有者を修正してください。" >&2
  echo "例: sudo chown -R $(id -un):$(id -gn) ${ROOT_DIR}" >&2
  exit 1
}

ensure_writable_dir() {
  if [ -e "$1" ] && [ ! -w "$1" ]; then
    fail_permission "$1"
  fi
}

ensure_writable_file() {
  if [ -e "$1" ] && [ ! -w "$1" ]; then
    fail_permission "$1"
  fi
}

patch_swig_doxygen_flag() {
  if swig -help 2>&1 | grep -q -- "-doxygen"; then
    return
  fi
  for makefile in \
    "${ABS_PJPROJECT_DIR}/pjsip-apps/src/swig/java/Makefile" \
    "${ABS_PJPROJECT_DIR}/pjsip-apps/src/swig/python/Makefile"; do
    if [ -f "${makefile}" ]; then
      ensure_writable_file "${makefile}"
      sed -i.bak 's/^GEN_DOC[[:space:]]*=.*-doxygen.*/GEN_DOC =/' "${makefile}"
    fi
  done
  echo "SWIGが-doxygenをサポートしていないため、PJSIP SWIG MakefileのGEN_DOCを無効化しました。"
}

if ! command -v git >/dev/null 2>&1; then
  echo "gitが見つかりません。" >&2
  exit 1
fi

if ! command -v swig >/dev/null 2>&1; then
  echo "swigが見つかりません。先に scripts/bootstrap-macos-deps.sh を実行してください。" >&2
  exit 1
fi

ensure_writable_dir "${ROOT_DIR}"
ensure_writable_dir "${ROOT_DIR}/${DEPS_DIR}"
ensure_writable_dir "${ABS_PJPROJECT_DIR}"
ensure_writable_dir "${ABS_PREFIX_DIR}"
ensure_writable_file "${ABS_PJPROJECT_DIR}/pjlib/include/pj/config_site.h"

if [ ! -d "${ABS_PJPROJECT_DIR}/.git" ]; then
  mkdir -p "${ROOT_DIR}/${DEPS_DIR}"
  git clone --depth 1 --branch "${PJSIP_VERSION}" https://github.com/pjsip/pjproject.git "${ABS_PJPROJECT_DIR}"
fi

cd "${ABS_PJPROJECT_DIR}"
patch_swig_doxygen_flag

cat > pjlib/include/pj/config_site.h <<'CONFIG_SITE'
#include <pj/config_site_sample.h>

#undef PJMEDIA_HAS_VIDEO
#define PJMEDIA_HAS_VIDEO 0

#undef PJMEDIA_AUDIO_DEV_HAS_COREAUDIO
#define PJMEDIA_AUDIO_DEV_HAS_COREAUDIO 0

#undef PJMEDIA_AUDIO_DEV_HAS_NULL_AUDIO
#define PJMEDIA_AUDIO_DEV_HAS_NULL_AUDIO 1

#undef PJMEDIA_HAS_G711_CODEC
#define PJMEDIA_HAS_G711_CODEC 1
CONFIG_SITE

./configure \
  CFLAGS="-fPIC" \
  --prefix="${ABS_PREFIX_DIR}" \
  --disable-sound \
  --disable-video \
  --disable-sdl \
  --disable-ffmpeg \
  --disable-openh264 \
  --disable-vpx
make dep
if [ "${PJSIP_CLEAN:-0}" = "1" ]; then
  make clean
fi
make
make install

cd pjsip-apps/src/swig/java
if [ "${PJSIP_CLEAN:-0}" = "1" ]; then
  make clean
fi
make
make install

echo "PJSIP ${PJSIP_VERSION} macOS build completed."
echo "PJPROJECT_DIR=${ABS_PJPROJECT_DIR}"
echo "PJSIP_PREFIX=${ABS_PREFIX_DIR}"
