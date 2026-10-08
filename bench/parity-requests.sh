#!/usr/bin/env bash
# Byte parity for arbitrary requests against the Rust port: like parity.sh, but each line of
# Last-Modified is masked too: static files carry each build's own time.
# REQUESTS_FILE is `METHOD PATH [Header: value]...` separated by ` | `, with $COOKIE standing for the
# session cookie. Status, decoded body and headers (normalized as parity.sh does) must match.
#   bench/parity-requests.sh DB COOKIE_FILE REQUESTS_FILE
# KOTLIN_EXTERNAL=1 uses a Kotlin app already listening on 4490 (a native image in Docker, say).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-$(cd "$ROOT" && mise where java)}
RUST=${RUST_ROOT:-$ROOT/../once-campfire-rust}
DB=$1 COOKIE=$(< "$2") REQUESTS=$3
WORK=$ROOT/tmp/parity-requests; rm -rf "$WORK"; mkdir -p "$WORK/rust/db" "$WORK/rust/files" "$WORK/kotlin/db"
cp "$DB" "$WORK/rust/db/production.sqlite3"; cp "$DB" "$WORK/kotlin/db/production.sqlite3"
set -a; source "$RUST/parity/.env.reference"; set +a
export RAILS_LOG_LEVEL=warn
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
CAMPFIRE_STORAGE_PATH=$WORK/rust CAMPFIRE_DATABASE_PATH=$WORK/rust/db/production.sqlite3 CAMPFIRE_FILES_PATH=$WORK/rust/files \
  HTTP_PORT=4390 TARGET_PORT=4391 "$RUST/target/release/campfire" > "$WORK/rust.log" 2>&1 & pids+=($!)
if [ -z "${KOTLIN_EXTERNAL:-}" ]; then
  CAMPFIRE_DATABASE_PATH=$WORK/kotlin/db/production.sqlite3 HTTP_PORT=4490 TARGET_PORT=4491 \
    "$ROOT/campfire/build/install/campfire/bin/campfire" > "$WORK/kotlin.log" 2>&1 & pids+=($!)
fi
for port in 4390 4490; do
  for _ in $(seq 1 200); do curl -fs -o /dev/null "localhost:$port/up" && break; sleep 0.05; done
done
normalized() {
  tr -d '\r' < "$1" | grep -v -i -E '^(date|x-request-id|x-runtime|last-modified):' \
    | sed -E 's/^(set-cookie: (_campfire_session|session_token)=)[^;]*/\1<signed>/; s/(expires=)[^;]*/\1<time>/'
}
checks=0 differences=0
while IFS= read -r line; do
  [ -z "$line" ] || [ "${line:0:1}" = "#" ] && continue
  IFS='|' read -r -a fields <<< "$line"
  read -r method path <<< "${fields[0]}"
  args=(-s --compressed -X "$method" -H 'Host: localhost:4390')
  [ "$method" = HEAD ] && args=(-s --head -H 'Host: localhost:4390')
  for header in "${fields[@]:1}"; do
    header=$(echo "$header" | sed -e 's/^ *//' -e 's/ *$//')
    args+=(-H "${header//\$COOKIE/$COOKIE}")
  done
  for port in 4390 4490; do
    if [ "$method" = HEAD ]; then curl "${args[@]}" -D "$WORK/$port.h" -o /dev/null "localhost:$port$path" || true; : > "$WORK/$port.body"
    else curl "${args[@]}" -D "$WORK/$port.h" -o "$WORK/$port.body" "localhost:$port$path" || true; fi
  done
  checks=$((checks + 1))
  if cmp -s "$WORK/4390.body" "$WORK/4490.body" && diff -q <(normalized "$WORK/4390.h") <(normalized "$WORK/4490.h") >/dev/null; then
    echo "identical  $(head -1 "$WORK/4490.h" | tr -d '\r' | cut -d' ' -f2) ${line:0:90}"
  else
    differences=$((differences + 1))
    echo "DIFFERENT  ${line:0:90}"
    diff <(normalized "$WORK/4390.h") <(normalized "$WORK/4490.h") | head -10 || true
    cmp "$WORK/4390.body" "$WORK/4490.body" | head -2 || true
  fi
done < "$REQUESTS"
echo "$checks requests: $differences different"
[ "$differences" = 0 ]
