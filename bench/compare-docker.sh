#!/usr/bin/env bash
# Compares GET /rooms/:id on the Rust port and this app inside Docker's Linux VM, one app at a time
# on a fresh copy of the seed, the app pinned to APP_CPUS and the loadgen to LOADGEN_CPUS. Every
# response is checked byte for byte against the Rust page (the shared loadgen's exact_body contract).
#
#   bench/compare-docker.sh [reps] [secs] [concs]
#
# Apps (APPS): rust, rust-nolog (the Rust image, as built in once-campfire-rust/tmp/spike/linux as
# spike-rust), kotlin, kotlin-nolog (the JVM distribution on eclipse-temurin:25-jre), native,
# native-nolog (build/native/$NATIVE, default oracle-pgo-native). `-nolog` sets LOG_REQUESTS=false.
# Needs the spike-loadgen image (the verification loadgen) and $SEED/$COOKIE.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUST=${RUST_ROOT:-$ROOT/../once-campfire-rust}
REPS=${1:-3} SECS=${2:-10} CONCS=${3:-"1 16 64 256 1024"}
APP_CPUS=${APP_CPUS:-0-1} LOADGEN_CPUS=${LOADGEN_CPUS:-2-3}
APPS=${APPS:-"rust-nolog kotlin-nolog native-nolog"}
NATIVE=${NATIVE:-oracle-pgo-native} JAVA_OPTS_EXTRA=${JAVA_OPTS_EXTRA:--XX:+UseParallelGC}
SEED=${SEED:-$ROOT/tmp/seed.sqlite3} COOKIE=$(< "${COOKIE_FILE:-$ROOT/tmp/cookie}")
PORT=4390 ROOM=486777696 NAME=compare-app
OUT=$ROOT/tmp/compare/$(date +%Y%m%d-%H%M%S); mkdir -p "$OUT/seed"; cp "$SEED" "$OUT/seed/production.sqlite3"
echo "APPS=$APPS APP_CPUS=$APP_CPUS LOADGEN_CPUS=$LOADGEN_CPUS NATIVE=$NATIVE JAVA_OPTS=$JAVA_OPTS_EXTRA" > "$OUT/config.txt"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

lg() { docker run --rm --network host --cpuset-cpus "$LOADGEN_CPUS" -v "$OUT":/out spike-loadgen loadgen "$@"; }

start() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  local common=(-d --name "$NAME" --network host --cpuset-cpus "$APP_CPUS" --env-file "$RUST/parity/.env.reference"
    -e RAILS_LOG_LEVEL=warn -e RAILS_MAX_THREADS=5 -e HTTP_PORT=$PORT -e TARGET_PORT=$((PORT + 1))
    -e CAMPFIRE_STORAGE_PATH=/data -e CAMPFIRE_DATABASE_PATH=/data/db/production.sqlite3 -e CAMPFIRE_FILES_PATH=/data/files
    -v "$OUT/seed":/seed:ro)
  case $1 in *-nolog) common+=(-e LOG_REQUESTS=false) ;; esac
  local copy='mkdir -p /data/db /data/files && cp /seed/production.sqlite3 /data/db/ && exec "$@"'
  case ${1%-nolog} in
    rust) docker run "${common[@]}" spike-rust sh -c "$copy" sh campfire >/dev/null ;;
    kotlin) docker run "${common[@]}" -e JAVA_OPTS="$JAVA_OPTS_EXTRA" -v "$ROOT/campfire/build/install/campfire":/app:ro \
      eclipse-temurin:25-jre sh -c "$copy" sh /app/bin/campfire >/dev/null ;;
    native) docker run "${common[@]}" -v "$ROOT/build/native/$NATIVE":/app:ro debian:trixie-slim sh -c "$copy" sh /app/campfire >/dev/null ;;
  esac
  docker run --rm --network host spike-loadgen bash -c 'for i in $(seq 2000); do (exec 3<>/dev/tcp/127.0.0.1/'$PORT') 2>/dev/null && exit 0; sleep 0.01; done; exit 1'
}
stop() { docker logs "$NAME" 2>&1 | tail -3 > "$OUT/$1.log" || true; docker rm -f "$NAME" >/dev/null 2>&1 || true; }
cg() { docker exec "$NAME" cat "/sys/fs/cgroup/$1"; }
cpu_usec() { cg cpu.stat | awk '$1=="usage_usec"{print $2}'; }
mem_mb() { cg memory.current | awk '{printf "%d", $1/1048576}'; }

contract() {
  start rust-nolog
  lg fetch --base "http://127.0.0.1:$PORT" --cookie "$COOKIE" --path "/rooms/$ROOM" --out /out/expected.html >/dev/null
  stop contract
  python3 - "$OUT" "$OUT/seed/production.sqlite3" "$ROOM" <<'PY'
import json, re, sqlite3, sys
out, db, room = sys.argv[1], sys.argv[2], int(sys.argv[3])
conn = sqlite3.connect(db)
ids = [r[0] for r in conn.execute("SELECT id FROM messages WHERE room_id=? ORDER BY created_at DESC LIMIT 40", (room,))][::-1]
in_ids = ",".join(map(str, ids))
bodies = dict(conn.execute(f"SELECT rowid, body FROM message_search_index WHERE rowid IN ({in_ids})"))
required = [f'id="message_{c}"' for (c,) in conn.execute(f"SELECT client_message_id FROM messages WHERE id IN ({in_ids})")]
body = open(f"{out}/expected.html", "rb").read()
json.dump({"kind": "room_show", "content_type": "text/html", "message_ids": ids,
           "message_content": [re.findall(r"[A-Za-z0-9_]+", bodies[i]) for i in ids], "required": required,
           "exact_body": list(body)}, open(f"{out}/contract.json", "w"))
PY
}

run() {
  local app=$1 rep=$2
  start "$app"; sleep 1
  echo "$app rep $rep: idle_mem_mb=$(mem_mb)"
  lg http --base "http://127.0.0.1:$PORT" --cookie "$COOKIE" --path "/rooms/$ROOM" --conc 16 --duration 20 --validate /out/contract.json > /dev/null
  for conc in $CONCS; do
    local c0 c1; c0=$(cpu_usec)
    lg http --base "http://127.0.0.1:$PORT" --cookie "$COOKIE" --path "/rooms/$ROOM" --conc "$conc" --duration "$SECS" --validate /out/contract.json > "$OUT/$app-$rep-c$conc.json" 2>/dev/null
    c1=$(cpu_usec)
    python3 - "$OUT/$app-$rep-c$conc.json" "$app" "$rep" "$conc" "$c0" "$c1" "$(mem_mb)" <<'PY'
import json, sys
f, app, rep, conc, c0, c1, mem = sys.argv[1:]
r = json.load(open(f))
cpu = (int(c1) - int(c0)) / 1e6
r.update(app=app, rep=int(rep), conc=int(conc), app_cpu_secs=cpu, mem_mb=int(mem))
json.dump(r, open(f, "w"))
lat = r["latency"]
print(f"  {app:13} conc={conc:>5} rps={r['rps']:>9.0f} p50={lat['p50_ms']:7.2f} p99={lat['p99_ms']:7.2f} max={lat['max_ms']:6.0f} "
      f"invalid={r['invalid_responses']} errors={r['errors']} cpu_us/req={cpu / max(r['ok'], 1) * 1e6:6.1f} mem_mb={mem}", flush=True)
PY
  done
  stop "$app-$rep"
}

contract
for rep in $(seq 1 "$REPS"); do
  order=$APPS; [ $((rep % 2)) -eq 0 ] && order=$(echo "$APPS" | awk '{for (i = NF; i > 0; i--) printf "%s ", $i}')
  for app in $order; do run "$app" "$rep"; done
done
python3 - "$OUT" <<'PY'
import glob, json, statistics, sys, collections
runs = collections.defaultdict(list)
for f in glob.glob(f"{sys.argv[1]}/*-c*.json"):
    r = json.load(open(f)); runs[(r["app"], r["conc"])].append(r)
apps = list(dict.fromkeys(a for a, _ in sorted(runs, key=lambda k: k[0])))
print("\nmedians (rps / cpu us per req / p99 ms / mem MB):")
for conc in sorted({c for _, c in runs}):
    cells = []
    for app in apps:
        rs = runs.get((app, conc), [])
        if rs:
            m = lambda k: statistics.median(k(r) for r in rs)
            cells.append(f"{app} {m(lambda r: r['rps']):.0f} / {m(lambda r: r['app_cpu_secs'] / max(r['ok'], 1) * 1e6):.0f} / {m(lambda r: r['latency']['p99_ms']):.1f} / {m(lambda r: r['mem_mb']):.0f}")
    print(f"conc {conc:>5}: " + " | ".join(cells))
PY
echo "results: $OUT"
