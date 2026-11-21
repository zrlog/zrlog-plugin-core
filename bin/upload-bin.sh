#!/usr/bin/env bash
export AWS_ACCESS_KEY_ID="${1:?object-storage access key is required}"
export AWS_SECRET_ACCESS_KEY="${2:?object-storage secret key is required}"
export ARTIFACT_BUCKET="${3:?object-storage bucket is required}"
export ARTIFACT_ENDPOINT="${6:?object-storage endpoint is required}"
exec bash "$(dirname "${BASH_SOURCE[0]}")/run-zrlog-build.sh" upload-bin.sh "$4" "$5"
