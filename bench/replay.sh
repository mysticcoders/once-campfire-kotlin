#!/usr/bin/env bash
# Sends each request in REQUESTS_FILE (the parity-requests.sh format) to BASE once, discarding the
# responses: for exercising code paths, e.g. under native-image's tracing agent.
#   bench/replay.sh BASE COOKIE_FILE REQUESTS_FILE
set -euo pipefail
BASE=$1 COOKIE=$(< "$2") REQUESTS=$3
while IFS= read -r line; do
  [ -z "$line" ] || [ "${line:0:1}" = "#" ] && continue
  IFS='|' read -r -a fields <<< "$line"
  read -r method path <<< "${fields[0]}"
  args=(-s -o /dev/null -X "$method")
  for header in "${fields[@]:1}"; do
    header=$(echo "$header" | sed -e 's/^ *//' -e 's/ *$//')
    args+=(-H "${header//\$COOKIE/$COOKIE}")
  done
  curl "${args[@]}" "$BASE$path" || true
done < "$REQUESTS"
