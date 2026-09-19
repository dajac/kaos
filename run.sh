#!/usr/bin/env bash
# Builds the shaded jar and runs Kaos. All arguments are passed to the
# application unchanged; run "./run.sh --help" to list them.
# Usage: ./run.sh [options]
set -euo pipefail
cd "$(dirname "$0")"

./mvnw -q package -DskipTests

# The shade plugin keeps the unshaded jar as original-kaos-server-*.jar,
# so this pattern matches the runnable jar only.
JAR="$(ls kaos-server/target/kaos-server-*.jar | head -n 1)"

exec java -jar "$JAR" "$@"
