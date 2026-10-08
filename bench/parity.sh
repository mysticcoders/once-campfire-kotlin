#!/usr/bin/env bash
# Byte parity against the Rust port: runs ../once-campfire-rust's release binary and this app on
# copies of one database, requests the room page from both with each user agent and encoding, and
# compares the decoded bodies and ETags.
#   bench/parity.sh DB COOKIE_FILE [ROOM]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUST=${RUST_ROOT:-$ROOT/../once-campfire-rust}
DB=$1 COOKIE=$(< "$2")
read -r -a ROOMS <<< "${3:-486777696}"
WORK=$ROOT/tmp/parity; rm -rf "$WORK"; mkdir -p "$WORK/rust/db" "$WORK/rust/files" "$WORK/kotlin/db"
cp "$DB" "$WORK/rust/db/production.sqlite3"; cp "$DB" "$WORK/kotlin/db/production.sqlite3"
set -a; source "$RUST/parity/.env.reference"; set +a
export RAILS_LOG_LEVEL=warn
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
CAMPFIRE_STORAGE_PATH=$WORK/rust CAMPFIRE_DATABASE_PATH=$WORK/rust/db/production.sqlite3 CAMPFIRE_FILES_PATH=$WORK/rust/files \
  HTTP_PORT=4390 TARGET_PORT=4391 "$RUST/target/release/campfire" > "$WORK/rust.log" 2>&1 & pids+=($!)
CAMPFIRE_DATABASE_PATH=$WORK/kotlin/db/production.sqlite3 HTTP_PORT=4490 TARGET_PORT=4491 \
  "$ROOT/campfire/build/install/campfire/bin/campfire" > "$WORK/kotlin.log" 2>&1 & pids+=($!)
for port in 4390 4490; do
  for _ in $(seq 1 200); do curl -fs -o /dev/null "localhost:$port/up" && break; sleep 0.05; done
done
status=0 differences=0 checks=0
normalized() {
  tr -d '\r' < "$1" | grep -v -i -E '^(date|x-request-id|x-runtime):' \
    | sed -E 's/^(set-cookie: (_campfire_session|session_token)=)[^;]*/\1<signed>/; s/(expires=)[^;]*/\1<time>/'
}
uas=('' 'curl/8.7.1')
if [ -n "${PARITY_UAS:-}" ]; then mapfile -t uas < "$PARITY_UAS"; fi
for ROOM in "${ROOMS[@]}"; do
for ua in "${uas[@]}"; do
  for enc in identity gzip; do
    for port in 4390 4490; do
      curl -s --compressed -H "User-Agent: $ua" -H 'Host: localhost:4390' -H "Cookie: $COOKIE" -H "Accept-Encoding: $enc" \
        -D "$WORK/$port.h" -o "$WORK/$port.html" "localhost:$port/rooms/$ROOM"
    done
    rs=$(head -1 "$WORK/4390.h" | cut -d' ' -f2); ks=$(head -1 "$WORK/4490.h" | cut -d' ' -f2)
    re=$(grep -i '^etag' "$WORK/4390.h" | tr -d '\r' || true); ke=$(grep -i '^etag' "$WORK/4490.h" | tr -d '\r' || true)
    checks=$((checks + 1))
    rl=$(grep -i '^location' "$WORK/4390.h" | tr -d '\r' || true); kl=$(grep -i '^location' "$WORK/4490.h" | tr -d '\r' || true)
    headers_same=1
    if [ -z "${BODY_ONLY:-}" ] && ! diff -q <(normalized "$WORK/4390.h") <(normalized "$WORK/4490.h") >/dev/null; then headers_same=0; fi
    if [ "$rs" = "$ks" ] && [ "$ks" != 501 ] && cmp -s "$WORK/4390.html" "$WORK/4490.html" && [ "$re" = "$ke" ] && [ "$rl" = "$kl" ] && [ "$headers_same" = 1 ]; then
      [ -n "${QUIET:-}" ] || echo "identical  room=$ROOM status=$ks UA='${ua:0:60}' $enc  $(wc -c < "$WORK/4490.html") bytes  $ke"
    else
      differences=$((differences + 1))
      echo "DIFFERENT  room=$ROOM UA='${ua:0:60}' $enc  status rust=$rs kotlin=$ks"; status=1
      [ "$headers_same" = 0 ] && diff <(normalized "$WORK/4390.h") <(normalized "$WORK/4490.h") | head -8 || true
      [ -n "${KEEP:-}" ] && cp "$WORK/4390.html" "$WORK/diff-rust-$ROOM.html" && cp "$WORK/4490.html" "$WORK/diff-kotlin-$ROOM.html"
    fi
  done
done
done
echo "${#ROOMS[@]} rooms x ${#uas[@]} user agents x 2 encodings: $checks checks, $differences different"
exit $status
