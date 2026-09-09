#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${SCADA_JAVA_HOME:-}" ]]; then
  exec sbt -java-home "$SCADA_JAVA_HOME" -Dhttp.address=127.0.0.1 "run ${SCADA_HTTP_PORT:-9000}"
fi
exec sbt -Dhttp.address=127.0.0.1 "run ${SCADA_HTTP_PORT:-9000}"
