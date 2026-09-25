#!/bin/sh
set -eu

mkdir -p "${JAMAL_DATA_DIR:-/data}" "${JAMAL_EXPORTS_DIR:-/data/exports}"
exec /app/web-app/bin/web-app
