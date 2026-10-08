#!/usr/bin/env bash
# Records a Flight Recorder profile of the JVM build serving the room page in Docker (app on 2 CPUs,
# 16 connections), and prints the hottest methods by self and inclusive time.
#   bench/profile-jvm.sh [TOP]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUST=${RUST_ROOT:-$ROOT/../once-campfire-rust}
S=$ROOT/tmp/prof; rm -rf "$S"; mkdir -p "$S/db"; cp "$ROOT/tmp/seed.sqlite3" "$S/db/production.sqlite3"
COOKIE=$(< "$ROOT/tmp/cookie")
docker rm -f kprof >/dev/null 2>&1 || true
docker run -d --name kprof --network host --cpuset-cpus 0-1 --env-file "$RUST/parity/.env.reference" -e LOG_REQUESTS=false \
  -e RAILS_LOG_LEVEL=warn -e HTTP_PORT=4390 -e TARGET_PORT=4391 -e CAMPFIRE_DATABASE_PATH=/data/db/production.sqlite3 \
  -e JAVA_OPTS="-XX:+UseParallelGC -XX:StartFlightRecording=delay=25s,duration=15s,filename=/data/rec.jfr,settings=profile" \
  -v "$S":/data -v "$ROOT/campfire/build/install/campfire":/app:ro eclipse-temurin:25-jre /app/bin/campfire >/dev/null
docker run --rm --network host spike-loadgen bash -c 'for i in $(seq 1000); do (exec 3<>/dev/tcp/127.0.0.1/4390) 2>/dev/null && exit 0; sleep 0.02; done'
docker run --rm --network host --cpuset-cpus 2-3 spike-loadgen loadgen http --base http://127.0.0.1:4390 --cookie "$COOKIE" --path /rooms/486777696 --conc 16 --duration 45 >/dev/null
sleep 3; docker rm -f kprof >/dev/null
TOP=${1:-30} python3 "$RUST/tmp/spike/profile.py" "$S/rec.jfr"
