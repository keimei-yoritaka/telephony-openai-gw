#!/usr/bin/env sh
set -eu

if ! command -v dnf >/dev/null 2>&1; then
  echo "dnfが見つかりません。RHEL 8.10上で実行してください。" >&2
  exit 1
fi

DNF="${DNF:-dnf}"
if [ "$(id -u)" -eq 0 ]; then
  SUDO="${SUDO:-}"
else
  SUDO="${SUDO:-sudo}"
fi

${SUDO} "${DNF}" install -y \
  git \
  gcc \
  gcc-c++ \
  make \
  autoconf \
  automake \
  libtool \
  swig \
  java-21-openjdk-devel

echo "RHEL dependency installation completed."
