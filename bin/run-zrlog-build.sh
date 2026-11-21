#!/usr/bin/env bash
set -e

coreDir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
buildDir="${ZRLOG_BUILD_DIR:-${coreDir}/.zrlog-build}"
if [[ -z "${ZRLOG_BUILD_DIR:-}" && ! -d "${buildDir}" ]]; then
  buildDir="${coreDir}/../zrlog-build"
fi

scriptPath="${buildDir}/scripts/plugin/${1:?build script is required}"
shift
if [[ ! -f "${scriptPath}" ]]; then
  printf 'Build script not found at %s. Set ZRLOG_BUILD_DIR to the zrlog-build checkout.\n' "${scriptPath}" >&2
  exit 1
fi

exec bash "${scriptPath}" "$@"
