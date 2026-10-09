#!/usr/bin/env bash
# Runs on the load generator: drives the app server over SSH and measures GET /rooms/:id on the
# Rust image (`rust`; `rust-backlog` with rust-backlog.patch, which listens with the kernel's
# somaxconn instead of mio's 128), this app on the JVM (`kotlin`) and as a native image (`native`),
# each with request logging as shipped or off (`-nolog`), one app at a time on a fresh copy of the
# seed. Every closed-loop response is checked byte for byte against the Rust page.
#
#   remote-bench.sh [PHASES]   default: parity closed open
#
# Results go to /root/results/<phase>/; a one-line summary per measurement goes to stdout.
set -euo pipefail

PHASES=${*:-parity closed open}
SEED=/root/seed BIN=/root/bin OUT=/root/results
source "$SEED/addresses"
APP=$app PORT=4390 ROOM=486777696 BASE=http://$app:4390
COOKIE=$(< "$SEED/cookie")
APPS=${APPS:-"rust-nolog kotlin-nolog native-nolog rust kotlin native"}
REPS=${REPS:-3} SECS=${SECS:-15} CONCS=${CONCS:-"1 16 64 256 1024 4096"}
SOAK_MINUTES=${SOAK_MINUTES:-15} OPEN_CONNS=${OPEN_CONNS:-2048}
SSH=(ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "root@$APP")
mkdir -p "$OUT"
log() { echo "[$(date +%T)] $*" >&2; }
on_app() { "${SSH[@]}" "$@"; }

# Starts one app on a fresh copy of the seed and waits for /up. With TIMED=1 it prints the
# milliseconds from `docker run` to the first /up and to the first room page, polled on the app
# server itself.
start() {
  on_app env TIMED="${TIMED:-}" COOKIE="$COOKIE" ROOM="$ROOM" KOTLIN_WORKERS="${KOTLIN_WORKERS:-}" KOTLIN_JAVA_OPTS="'${KOTLIN_JAVA_OPTS:-}'" bash -s -- "$1" <<'REMOTE'
set -euo pipefail
app=$1
docker rm -f campfire >/dev/null 2>&1 || true
rm -rf /root/run && mkdir -p /root/run/db /root/run/files && cp /root/seed/production.sqlite3 /root/run/db/ && chown -R 1000:1000 /root/run
common=(-d --name campfire --network host --ulimit nofile=1048576:1048576 --env-file /root/seed/.env.reference
  -e RAILS_LOG_LEVEL=warn -e RAILS_MAX_THREADS=5 -e HTTP_PORT=4390 -e TARGET_PORT=4391)
case $app in *-nolog) common+=(-e LOG_REQUESTS=false) ;; esac
kotlin_env=(${KOTLIN_WORKERS:+-e KOTLIN_WORKERS=$KOTLIN_WORKERS} -e CAMPFIRE_STORAGE_PATH=/data -e CAMPFIRE_DATABASE_PATH=/data/db/production.sqlite3 -e CAMPFIRE_FILES_PATH=/data/files -v /root/run:/data)
t0=$(date +%s%N)
case ${app%-nolog} in
  rust) docker run "${common[@]}" -v /root/run/db:/rails/storage/db -v /root/run/files:/rails/storage/files campfire-rust:app >/dev/null ;;
  rust-backlog) docker run "${common[@]}" -v /root/run/db:/rails/storage/db -v /root/run/files:/rails/storage/files campfire-rust:backlog >/dev/null ;;
  kotlin) docker run "${common[@]}" "${kotlin_env[@]}" -e JAVA_OPTS="${KOTLIN_JAVA_OPTS:--XX:+UseParallelGC}" \
    -v /root/kotlin:/app:ro eclipse-temurin:25-jre /app/bin/campfire >/dev/null ;;
  native) docker run "${common[@]}" "${kotlin_env[@]}" -v /root/native:/app:ro debian:trixie-slim /app/campfire >/dev/null ;;
esac
if [ -n "$TIMED" ]; then
  until curl -fs -o /dev/null http://127.0.0.1:4390/up; do sleep 0.002; done; t1=$(date +%s%N)
  until curl -fs -o /dev/null -H "Cookie: $COOKIE" http://127.0.0.1:4390/rooms/$ROOM; do sleep 0.002; done; t2=$(date +%s%N)
  echo "$(( (t1 - t0) / 1000000 )) $(( (t2 - t0) / 1000000 ))"
fi
REMOTE
  for _ in $(seq 1 1500); do curl -fs -o /dev/null "$BASE/up" && return 0; sleep 0.02; done
  on_app docker logs campfire | tail -20 >&2; return 1
}
stop() { on_app "docker logs campfire > /root/last-app.log 2>&1; docker rm -f campfire >/dev/null 2>&1" || true; }
trap stop EXIT

# CPU seconds (usec) used by the app container, by the whole app server, and by this machine.
app_cpu() { on_app "docker exec campfire cat /sys/fs/cgroup/cpu.stat" | awk '$1=="usage_usec"{print $2}'; }
host_busy() { awk '/^cpu /{print ($2+$3+$4+$7+$8) * 10000}' /proc/stat; }
app_host_busy() { on_app "awk '/^cpu /{print (\$2+\$3+\$4+\$7+\$8) * 10000}' /proc/stat"; }
app_mem() { on_app "docker exec campfire sh -c 'cat /sys/fs/cgroup/memory.current; grep ^anon /sys/fs/cgroup/memory.stat'" | awk 'NR==1{c=$1} $1=="anon"{a=$2} END{printf "%d %d", c/1048576, a/1048576}'; }

# The exact-body contract from the Rust page as this load generator sees it.
contract() {
  start rust-nolog
  "$BIN/loadgen" fetch --base "$BASE" --cookie "$COOKIE" --path "/rooms/$ROOM" --out "$OUT/expected.html" >/dev/null
  stop
  python3 - "$OUT" "$SEED/production.sqlite3" "$ROOM" <<'PY'
import json, re, sqlite3, sys
out, db, room = sys.argv[1], sys.argv[2], int(sys.argv[3])
conn = sqlite3.connect(db)
ids = [r[0] for r in conn.execute("SELECT id FROM messages WHERE room_id=? ORDER BY created_at DESC LIMIT 40", (room,))][::-1]
in_ids = ",".join(map(str, ids))
bodies = dict(conn.execute(f"SELECT rowid, body FROM message_search_index WHERE rowid IN ({in_ids})"))
content = [re.findall(r"[A-Za-z0-9_]+", bodies[i]) for i in ids]
required = [f'id="message_{c}"' for (c,) in conn.execute(f"SELECT client_message_id FROM messages WHERE id IN ({in_ids})")]
body = open(f"{out}/expected.html", "rb").read()
json.dump({"kind": "room_show", "content_type": "text/html", "message_ids": ids, "message_content": content,
           "required": required, "exact_body": list(body)}, open(f"{out}/contract.json", "w"))
print(f"contract: {len(body)} bytes, {len(ids)} messages")
PY
}

# Bodies, ETags and headers (less Date, X-Request-Id, signed cookie values and expiry times) of each app against Rust's, for both
# user-agent branches and both encodings.
phase_parity() {
  local dir=$OUT/parity; mkdir -p "$dir"
  local app ua enc
  for app in rust-nolog kotlin-nolog native-nolog; do
    start "$app"
    for ua in none curl; do
      for enc in identity gzip; do
        local h=(-H "Cookie: $COOKIE" -H "Accept-Encoding: $enc")
        [ "$ua" = none ] && h+=(-H "User-Agent:") || h+=(-H "User-Agent: curl/8.7.1")
        curl -s --compressed "${h[@]}" -D "$dir/${app%-nolog}-$ua-$enc.headers" -o "$dir/${app%-nolog}-$ua-$enc.body" "$BASE/rooms/$ROOM"
        tr -d '\r' < "$dir/${app%-nolog}-$ua-$enc.headers" | grep -v -i -E '^(date|x-request-id|x-runtime):' \
          | sed -E 's/^(set-cookie: (_campfire_session|session_token)=)[^;]*/\1<signed>/; s/(expires=)[^;]*/\1<time>/' > "$dir/${app%-nolog}-$ua-$enc.norm"
      done
    done
    stop
  done
  local status=0
  for app in kotlin native; do
    for ua in none curl; do
      for enc in identity gzip; do
        if cmp -s "$dir/rust-$ua-$enc.body" "$dir/$app-$ua-$enc.body" && cmp -s "$dir/rust-$ua-$enc.norm" "$dir/$app-$ua-$enc.norm"; then
          echo "parity $app identical UA=$ua $enc $(wc -c < "$dir/$app-$ua-$enc.body") bytes, $(grep -i '^etag' "$dir/$app-$ua-$enc.norm")"
        else
          echo "parity $app DIFFERENT UA=$ua $enc"; diff "$dir/rust-$ua-$enc.norm" "$dir/$app-$ua-$enc.norm" || true; status=1
        fi
      done
    done
  done
  return $status
}

# One closed-loop measurement: prints and records rps, latency, CPU (app, app server, loadgen) and memory.
closed_one() {
  local app=$1 rep=$2 conc=$3 f=$OUT/closed/$app-$rep-c$conc.json
  local a0 h0 l0 a1 h1 l1
  a0=$(app_cpu); h0=$(app_host_busy); l0=$(host_busy)
  "$BIN/loadgen" http --base "$BASE" --cookie "$COOKIE" --path "/rooms/$ROOM" --conc "$conc" --duration "$SECS" --validate "$OUT/contract.json" > "$f" 2> "$f.err" || true
  a1=$(app_cpu); h1=$(app_host_busy); l1=$(host_busy)
  python3 - "$f" "$app" "$rep" "$conc" "$a0" "$a1" "$h0" "$h1" "$l0" "$l1" $(app_mem) <<'PY'
import json, sys
f, app, rep, conc, a0, a1, h0, h1, l0, l1, mem, anon = sys.argv[1:]
r = json.load(open(f))
secs = r["secs"]
r.update(app_cpu_secs=(int(a1) - int(a0)) / 1e6, app_host_cpu_secs=(int(h1) - int(h0)) / 1e6,
         loadgen_cpu_secs=(int(l1) - int(l0)) / 1e6, mem_mb=int(mem), anon_mb=int(anon), app=app, rep=int(rep), conc=int(conc))
json.dump(r, open(f, "w"))
lat = r["latency"]
print(f"closed {app:12} rep={rep} conc={conc:>5} rps={r['rps']:>9.0f} p50={lat['p50_ms']:8.2f} p99={lat['p99_ms']:8.2f} max={lat['max_ms']:8.0f}ms "
      f"invalid={r['invalid_responses']} errors={r['errors']} cpu_us/req={r['app_cpu_secs'] / max(r['ok'], 1) * 1e6:6.1f} "
      f"app_cores={r['app_cpu_secs'] / secs:.2f} server_cores={r['app_host_cpu_secs'] / secs:.2f} loadgen_cores={r['loadgen_cpu_secs'] / secs:.2f} anon_mb={anon}", flush=True)
PY
}

warmup() {
  "$BIN/loadgen" http --base "$BASE" --cookie "$COOKIE" --path "/rooms/$ROOM" --conc 16 --duration 20 --validate "$OUT/contract.json" > /dev/null
}

reversed() { echo "$1" | awk '{for (i = NF; i > 0; i--) printf "%s ", $i}'; }

phase_closed() {
  mkdir -p "$OUT/closed"
  local rep app order
  for rep in $(seq 1 "$REPS"); do
    order=$APPS; [ $((rep % 2)) -eq 0 ] && order=$(reversed "$APPS")
    for app in $order; do
      start "$app"; warmup
      for conc in $CONCS; do closed_one "$app" "$rep" "$conc"; done
      stop
    done
  done
  summary
}

# Medians per app and connection count, with each app's throughput as a share of Rust's (same logging).
summary() {
  python3 - "$OUT/closed" <<'PY'
import collections, glob, json, statistics, sys
runs = collections.defaultdict(list)
for f in glob.glob(f"{sys.argv[1]}/*-c*.json"):
    try: r = json.load(open(f))
    except Exception: continue
    if "app" in r: runs[(r["app"], r["conc"])].append(r)
apps = list(dict.fromkeys(a for a, _ in sorted(runs)))
med = lambda rs, k: statistics.median(k(r) for r in rs)
print("\nmedians: rps (share of rust) / cpu us per req / p99 ms / anon MB; invalid responses summed")
for conc in sorted({c for _, c in runs}):
    for app in apps:
        rs = runs.get((app, conc))
        if not rs: continue
        rps = med(rs, lambda r: r["rps"])
        base = runs.get(("rust-nolog" if app.endswith("-nolog") else "rust", conc))
        share = f" ({rps / med(base, lambda r: r['rps']) * 100:.0f}%)" if base else ""
        print(f"conc {conc:>5} {app:12} {rps:>8.0f}{share:7} / {med(rs, lambda r: r['app_cpu_secs'] / max(r['ok'], 1) * 1e6):5.1f} / "
              f"{med(rs, lambda r: r['latency']['p99_ms']):7.1f} / {med(rs, lambda r: r['anon_mb']):4.0f}  invalid={sum(r['invalid_responses'] for r in rs)} errors={sum(r['errors'] for r in rs)}")
PY
}

# Each app's capacity: its median closed-loop rps at 64 connections.
capacity() {
  python3 - "$OUT/closed" "$1" <<'PY'
import glob, json, statistics, sys
runs = [json.load(open(f))["rps"] for f in glob.glob(f"{sys.argv[1]}/{sys.argv[2]}-*-c64.json")]
print(int(statistics.median(runs)) if runs else 0)
PY
}

open_one() {
  local app=$1 rep=$2 rate=$3 secs=$4 f=$5
  local a0 a1 h0 h1 l0 l1
  a0=$(app_cpu); h0=$(app_host_busy); l0=$(host_busy)
  "$BIN/oha" --no-tui --output-format json --latency-correction --disable-compression -q "$rate" -z "${secs}s" -c "$OPEN_CONNS" \
    -H "Cookie: $COOKIE" -H "Accept-Encoding: gzip" -H "User-Agent: curl/8.7.1" "$BASE/rooms/$ROOM" > "$f" 2> "$f.err" || true
  a1=$(app_cpu); h1=$(app_host_busy); l1=$(host_busy)
  python3 - "$f" "$app" "$rep" "$rate" "$secs" "$a0" "$a1" "$h0" "$h1" "$l0" "$l1" $(app_mem) <<'PY'
import json, sys
f, app, rep, rate, secs, a0, a1, h0, h1, l0, l1, mem, anon = sys.argv[1:]
r = json.load(open(f))
secs = float(secs)
ok = r.get("statusCodeDistribution", {}).get("200", 0)
other = {k: v for k, v in r.get("statusCodeDistribution", {}).items() if k != "200"}
p = r["latencyPercentiles"]
r.update(app=app, rep=int(rep), offered=int(rate), served=ok / secs, app_cpu_secs=(int(a1) - int(a0)) / 1e6,
         app_host_cpu_secs=(int(h1) - int(h0)) / 1e6, loadgen_cpu_secs=(int(l1) - int(l0)) / 1e6, mem_mb=int(mem), anon_mb=int(anon))
json.dump(r, open(f, "w"))
ms = lambda v: (v or 0) * 1000
print(f"open   {app:12} rep={rep} offered={int(rate):>6} served={ok / secs:8.0f}/s p50={ms(p['p50']):8.1f} p99={ms(p['p99']):8.1f} "
      f"p99.9={ms(p['p99.9']):8.1f}ms non200={other or 0} errors={r.get('errorDistribution', {})} "
      f"server_cores={r['app_host_cpu_secs'] / secs:.2f} loadgen_cores={r['loadgen_cpu_secs'] / secs:.2f} anon_mb={anon}", flush=True)
PY
}

# Fixed offered rates (fractions of the best -nolog app's closed-loop capacity) for the -nolog apps.
phase_open() {
  mkdir -p "$OUT/open"
  local top=0 app cap apps
  apps=$(for app in $APPS; do case $app in *-nolog) printf '%s ' "$app" ;; esac; done)
  for app in $apps; do cap=$(capacity "$app"); [ "$cap" -gt "$top" ] && top=$cap; done
  [ "$top" -gt 0 ] || { log "open: no closed-loop capacity yet; run the closed phase first"; return 1; }
  local rates; rates=$(python3 -c "import sys; t=int(sys.argv[1]); print(' '.join(str(int(t*f/500)*500) for f in (0.5,0.7,0.8,0.9,1.0,1.1,1.2,1.4)))" "$top")
  log "open: rates $rates (top closed-loop capacity $top)"
  local rep order rate
  for rep in 1 2; do
    order=$apps; [ "$rep" -eq 2 ] && order=$(reversed "$apps")
    for app in $order; do
      start "$app"; warmup
      for rate in $rates; do open_one "$app" "$rep" "$rate" "$SECS" "$OUT/open/$app-$rep-$rate.json"; done
      stop
    done
  done
}

phase_soak() {
  mkdir -p "$OUT/soak"
  local app cap rate minute
  for app in rust-nolog kotlin-nolog native-nolog; do
    cap=$(capacity "$app"); rate=$((cap * 7 / 10 / 500 * 500))
    log "soak: $app at $rate req/s for $SOAK_MINUTES minutes"
    start "$app"; warmup
    for minute in $(seq 1 "$SOAK_MINUTES"); do open_one "$app" "$minute" "$rate" 60 "$OUT/soak/$app-$minute.json"; done
    stop
  done
}

phase_summary() { summary; }

# Time from `docker run` to the first /up and the first room page, and memory idle after start and
# after the warmup, 5 starts per app; plus a bare container's start for Docker's own share.
phase_startup() {
  local dir=$OUT/startup app i; mkdir -p "$dir"
  for i in 1 2 3 4 5; do
    echo "docker-baseline $(on_app 'a=$(date +%s%N); docker run --rm debian:trixie-slim true; echo $(( ($(date +%s%N) - a) / 1000000 ))')" | tee -a "$dir/startup.txt"
  done
  for app in $APPS; do
    for i in 1 2 3 4 5; do
      local times; times=$(TIMED=1 start "$app")
      sleep 2
      local idle; idle=$(app_mem)
      warmup
      sleep 5
      echo "$app start_ms=${times% *} first_page_ms=${times#* } idle_mb=${idle} warm_idle_mb=$(app_mem)" | tee -a "$dir/startup.txt"
      stop
    done
  done
}

# The JVM's open-loop tail at low rates: the open phase's rates 4,000 and 6,000/s again, after an
# extra warmup with oha itself (the same requests, headers and connection count as the measurement).
phase_jit() {
  mkdir -p "$OUT/jit"
  local app rep rate
  for app in kotlin-nolog rust-nolog; do
    for rep in 1 2; do
      start "$app"; warmup
      "$BIN/oha" --no-tui --disable-compression -q 4000 -z 20s -c "$OPEN_CONNS" \
        -H "Cookie: $COOKIE" -H "Accept-Encoding: gzip" -H "User-Agent: curl/8.7.1" "$BASE/rooms/$ROOM" > /dev/null 2>&1 || true
      for rate in 4000 6000; do open_one "$app" "$rep" "$rate" "$SECS" "$OUT/jit/$app-$rep-$rate.json"; done
      stop
    done
  done
}

log "phases: $PHASES (apps: $APPS, reps: $REPS, ${SECS}s per step)"
on_app "nproc; lscpu | grep -E 'Model name|Thread|Core|Socket'" > "$OUT/app-server.txt"
lscpu | grep -E 'Model name|Thread|Core|Socket' > "$OUT/loadgen-server.txt"
[ -f "$OUT/contract.json" ] || contract
for phase in $PHASES; do
  log "phase $phase"
  "phase_$phase"
done
log "done"
