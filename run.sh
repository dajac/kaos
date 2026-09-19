#!/usr/bin/env bash
# Builds the shaded jar and runs Kaos.
# Usage: ./run.sh [bootstrap-server] [port]
set -euo pipefail
cd "$(dirname "$0")"

BOOTSTRAP_SERVER="${1:-localhost:9092}"
PORT="${2:-8081}"

./mvnw -q package -DskipTests

# The shade plugin keeps the unshaded jar as original-kaos-server-*.jar,
# so this pattern matches the runnable jar only.
JAR="$(ls kaos-server/target/kaos-server-*.jar | head -n 1)"

exec java -jar "$JAR" --bootstrap-server "$BOOTSTRAP_SERVER" --port "$PORT"
