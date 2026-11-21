#!/usr/bin/env bash
exec bash "$(dirname "${BASH_SOURCE[0]}")/run-zrlog-build.sh" core/package-native.sh "$@"
