#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

BOOTSTRAP_SERVER="${1:-localhost:9092}"
PORT="${2:-8081}"

./mvnw package -q
echo "Starting Kaos on http://localhost:${PORT} (bootstrap: ${BOOTSTRAP_SERVER})"
java -jar target/kaos-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --bootstrap-server "$BOOTSTRAP_SERVER" \
  --port "$PORT"
