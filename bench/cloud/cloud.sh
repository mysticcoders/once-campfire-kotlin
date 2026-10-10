#!/usr/bin/env bash
# Runs the room-page comparison on Hetzner Cloud: a dedicated 2-vCPU app server (APP_TYPE) and a
# larger load generator (LG_TYPE) on one private network, everything labelled campfire-bench.
# Compares the Rust port's production image with this app on the JVM and as a native image.
#
#   cloud.sh up         create the SSH key, network, firewall and both servers, wait for cloud-init
#   cloud.sh provision  upload sources, build the Rust image (and one with rust-backlog.patch),
#                       SQLite, loadgen, oha and the native image on the load generator, and install
#                       the apps and seed on the app server. The native image's PGO profile is
#                       recorded there from an instrumented build of the same jars under the room-page
#                       load, unless PGO_PROFILE names one to use instead; it is kept in tmp/cloud/.
#                       Ends with `natives`.
#   cloud.sh natives    build the other native images on the load generator and install them on the
#                       app server: Oracle GraalVM with PGO, -O3 and G1 (`native-g1`), and GraalVM
#                       Community with -O2 and Serial (`native-ce`, from CE_IMAGE)
#   cloud.sh run [ARGS] run remote-bench.sh on the load generator (in the background; see `logs`)
#   cloud.sh logs       follow the run's log
#   cloud.sh fetch      copy the results to tmp/cloud/results/
#   cloud.sh ssh lg|app open a shell
#   cloud.sh status     list the labelled resources
#   cloud.sh down       delete every labelled resource (servers, network, firewall, SSH key)
#
# HCLOUD_TOKEN comes from the environment or the Rust port's .env. State (the throwaway SSH key,
# addresses) lives in the ignored tmp/cloud/.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
HERE=$ROOT/bench/cloud
STATE=$ROOT/tmp/cloud
RUST=${RUST_ROOT:-$ROOT/../once-campfire-rust}
VERIFICATION=${VERIFICATION_ROOT:-$ROOT/../once-campfire-verification}
SQLITE_SRC=${SQLITE_SRC:-$HOME/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/libsqlite3-sys-0.38.2/sqlite3}
GRAAL_IMAGE=${GRAAL_IMAGE:-container-registry.oracle.com/graalvm/native-image:25i4}
CE_IMAGE=${CE_IMAGE:-ghcr.io/graalvm/native-image-community:25}
LOCATION=${LOCATION:-ash} APP_TYPE=${APP_TYPE:-ccx13} LG_TYPE=${LG_TYPE:-ccx33} IMAGE=${IMAGE:-ubuntu-24.04}
NAME=campfire-bench LABEL=purpose=campfire-bench
mkdir -p "$STATE"
export HCLOUD_TOKEN=${HCLOUD_TOKEN:-$(sed -n 's/^HCLOUD_TOKEN=//p' "$ROOT/.env" "$RUST/.env" 2>/dev/null | head -1 | tr -d "\"' \r")}
[ -n "$HCLOUD_TOKEN" ] || { echo "no HCLOUD_TOKEN in the environment or $RUST/.env" >&2; exit 1; }

KEY=$STATE/id_ed25519
SSH_OPTS=(-i "$KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ServerAliveInterval=30)
public_ip() { hcloud server ip "$NAME-$1"; }
private_ip() { hcloud server describe "$NAME-$1" -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["private_net"][0]["ip"])'; }
on() { local host=$1; shift; ssh "${SSH_OPTS[@]}" "root@$(public_ip "$host")" "$@"; }
sync_to() { local host=$1; shift; rsync -az -e "ssh ${SSH_OPTS[*]}" "$@" "root@$(public_ip "$host"):${DEST:?}"; }
log() { echo "[$(date +%T)] $*" >&2; }

up() {
  [ -f "$KEY" ] || ssh-keygen -q -t ed25519 -N '' -C "$NAME" -f "$KEY"
  hcloud ssh-key describe "$NAME" >/dev/null 2>&1 || hcloud ssh-key create --name "$NAME" --public-key-from-file "$KEY.pub" --label "$LABEL" >/dev/null
  if ! hcloud network describe "$NAME" >/dev/null 2>&1; then
    hcloud network create --name "$NAME" --ip-range 10.0.0.0/16 --label "$LABEL" >/dev/null
    hcloud network add-subnet "$NAME" --network-zone "$(hcloud location describe "$LOCATION" -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)["network_zone"])')" --type cloud --ip-range 10.0.1.0/24 >/dev/null
  fi
  if ! hcloud firewall describe "$NAME" >/dev/null 2>&1; then
    local me; me=$(curl -fsS https://api.ipify.org)
    printf '[{"direction":"in","protocol":"tcp","port":"22","source_ips":["%s/32"]}]' "$me" > "$STATE/firewall.json"
    hcloud firewall create --name "$NAME" --rules-file "$STATE/firewall.json" --label "$LABEL" >/dev/null
    log "firewall: ssh from $me only (private network traffic is unfiltered)"
  fi
  local role type
  for role in lg app; do
    type=$([ "$role" = lg ] && echo "$LG_TYPE" || echo "$APP_TYPE")
    hcloud server describe "$NAME-$role" >/dev/null 2>&1 && continue
    log "creating $NAME-$role ($type, $LOCATION)"
    hcloud server create --name "$NAME-$role" --type "$type" --image "$IMAGE" --location "$LOCATION" --ssh-key "$NAME" \
      --network "$NAME" --firewall "$NAME" --user-data-from-file "$HERE/cloud-init.yaml" --label "$LABEL" >/dev/null
  done
  for role in lg app; do
    log "waiting for cloud-init on $role"
    until on "$role" test -f /root/cloud-init-done 2>/dev/null; do sleep 5; done
  done
  echo "lg=$(private_ip lg) app=$(private_ip app)" > "$STATE/addresses"
  log "up: $(cat "$STATE/addresses")"
}

provision() {
  local app_ip lg_ip; app_ip=$(private_ip app); lg_ip=$(private_ip lg)
  log "building the JVM distribution and staging the seed"
  (cd "$ROOT" && JAVA_HOME=$(mise where java) ./gradlew -q :campfire:installDist)
  rm -rf "$STATE/stage" && mkdir -p "$STATE/stage"
  sqlite3 "$ROOT/tmp/seed.sqlite3" ".backup '$STATE/stage/production.sqlite3'"
  cp "$ROOT/tmp/cookie" "$RUST/parity/.env.reference" "$HERE/remote-bench.sh" "$HERE/native-image.sh" "$HERE/rust-backlog.patch" "$STATE/stage/"
  if [ -n "${PGO_PROFILE:-}" ]; then cp "$PGO_PROFILE" "$STATE/stage/room.iprof"; else log "recording the PGO profile on the load generator"; fi

  log "uploading sources to the load generator"
  on lg mkdir -p /root/src/rust /root/src/verification /root/src/sqlite /root/kotlin /root/seed /root/.ssh
  DEST=/root/src/rust/ sync_to lg --delete --exclude target --exclude 'crates/*/tests/golden' --exclude 'reference/.git' \
    "$RUST/Cargo.toml" "$RUST/Cargo.lock" "$RUST/Dockerfile" "$RUST/.dockerignore" "$RUST/crates" "$RUST/reference"
  DEST=/root/src/verification/ sync_to lg --delete --exclude target "$VERIFICATION/loadgen"
  DEST=/root/src/sqlite/ sync_to lg "$SQLITE_SRC/sqlite3.c" "$ROOT/native/sqlite-flags.txt"
  DEST=/root/kotlin/ sync_to lg --delete --exclude native "$ROOT/campfire/build/install/campfire/"
  DEST=/root/seed/ sync_to lg "$STATE/stage/"
  scp "${SSH_OPTS[@]}" -q "$KEY" "root@$(public_ip lg):/root/.ssh/id_ed25519"
  on lg "printf 'app=%s\nlg=%s\n' $app_ip $lg_ip > /root/seed/addresses"

  log "building on the load generator (Rust image, SQLite, native image, loadgen, oha)"
  on lg env GRAAL_IMAGE="$GRAAL_IMAGE" bash -s <<'REMOTE'
set -euo pipefail
cd /root/src/rust
DOCKER_BUILDKIT=1 docker buildx build --load -q -t campfire-rust:app --build-arg APP_VERSION=bench --build-arg GIT_REVISION=bench . > /root/build-rust.log 2>&1 || { tail -40 /root/build-rust.log; exit 1; }
rm -rf /root/src/rust-backlog && cp -a /root/src/rust /root/src/rust-backlog && cd /root/src/rust-backlog && patch -s -p1 < /root/seed/rust-backlog.patch
DOCKER_BUILDKIT=1 docker buildx build --load -q -t campfire-rust:backlog --build-arg APP_VERSION=bench --build-arg GIT_REVISION=bench . > /root/build-rust-backlog.log 2>&1 || { tail -40 /root/build-rust-backlog.log; exit 1; }
cd /root
mkdir -p /root/kotlin/native/linux-x86_64 /root/static /root/bin
docker run --rm -v /root/src/sqlite:/s:ro -v /root/kotlin/native/linux-x86_64:/out -v /root/static:/static rust:1.98.1-trixie bash -c \
  'cc $(cat /s/sqlite-flags.txt) /s/sqlite3.c -o /out/libcampfire_sqlite3.so -lpthread -ldl -lm &&
   cc $(sed "s/-shared//" /s/sqlite-flags.txt) -c /s/sqlite3.c -o /tmp/sqlite3.o && ar rcs /static/libcampfire_sqlite3.a /tmp/sqlite3.o' 2>/root/build-sqlite.log
docker run --rm -v /root/src/verification:/v -v /root/bin:/out rust:1.98.1-trixie bash -c \
  'cd /v/loadgen && cargo build -q --release --locked && cp target/release/loadgen /out/loadgen'
curl -fsSL -o /root/bin/oha https://github.com/hatoo/oha/releases/download/v1.16.0/oha-linux-amd64 && chmod +x /root/bin/oha
docker pull -q eclipse-temurin:25-jre >/dev/null
docker pull -q eclipse-temurin:27_35-jre >/dev/null
docker pull -q container-registry.oracle.com/graalvm/jdk:25i4 >/dev/null
docker pull -q debian:trixie-slim >/dev/null

source /root/seed/native-image.sh

if [ ! -f /root/seed/room.iprof ]; then
  native_image "$GRAAL_IMAGE" /root/native-instr /root/build-native-instr.log --pgo-instrument --install-exit-handlers
  COOKIE=$(< /root/seed/cookie) URL=http://127.0.0.1:4390/rooms/486777696
  rm -rf /root/pgo-run && mkdir -p /root/pgo-run/db /root/pgo-run/files && cp /root/seed/production.sqlite3 /root/pgo-run/db/
  docker rm -f pgo >/dev/null 2>&1 || true
  docker run -d --name pgo --network host -w /data --env-file /root/seed/.env.reference -e RAILS_LOG_LEVEL=warn -e LOG_REQUESTS=false \
    -e HTTP_PORT=4390 -e TARGET_PORT=4391 -e CAMPFIRE_STORAGE_PATH=/data -e CAMPFIRE_DATABASE_PATH=/data/db/production.sqlite3 \
    -e CAMPFIRE_FILES_PATH=/data/files -v /root/pgo-run:/data -v /root/native-instr:/app:ro debian:trixie-slim /app/campfire >/dev/null
  until curl -fs -o /dev/null http://127.0.0.1:4390/up; do sleep 0.1; done
  for conc in 1 16 64 256 1024; do /root/bin/loadgen http --base http://127.0.0.1:4390 --cookie "$COOKIE" --path /rooms/486777696 --conc $conc --duration 15 > /dev/null; done
  /root/bin/oha --no-tui --disable-compression -z 20s -c 256 -H "Cookie: $COOKIE" -H "Accept-Encoding: gzip" -H "User-Agent: curl/8.7.1" "$URL" > /dev/null
  docker stop -t 120 pgo >/dev/null && docker logs pgo > /root/pgo-run.log 2>&1 && docker rm pgo >/dev/null
  test -s /root/pgo-run/default.iprof || { echo "no profile written"; tail -20 /root/pgo-run.log; exit 1; }
  cp /root/pgo-run/default.iprof /root/seed/room.iprof
  ls -la /root/seed/room.iprof
fi
native_image "$GRAAL_IMAGE" /root/native /root/build-native.log -O3 --pgo=/pgo/room.iprof
ls -la /root/bin /root/native /root/kotlin/native/linux-x86_64
REMOTE

  log "installing the apps and seed on the app server"
  on lg bash -s <<REMOTE
set -euo pipefail
SSH="ssh -n -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR root@$app_ip"
RSYNC="rsync -az --delete -e 'ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR'"
docker save campfire-rust:app campfire-rust:backlog eclipse-temurin:25-jre eclipse-temurin:27_35-jre container-registry.oracle.com/graalvm/jdk:25i4 debian:trixie-slim | \${SSH/ssh -n/ssh} docker load -q
\$SSH mkdir -p /root/kotlin /root/native /root/seed
eval \$RSYNC /root/kotlin/ root@$app_ip:/root/kotlin/
eval \$RSYNC /root/native/ root@$app_ip:/root/native/
eval \$RSYNC /root/seed/ root@$app_ip:/root/seed/
iperf3 -s -D -1 >/dev/null; sleep 1
\$SSH "iperf3 -c $lg_ip -R -t 5 -P 4 -J" | python3 -c 'import json,sys; r=json.load(sys.stdin); print("private network, app -> lg: %.2f Gbit/s" % (r["end"]["sum_received"]["bits_per_second"] / 1e9))'
REMOTE
  scp "${SSH_OPTS[@]}" -q "root@$(public_ip lg):/root/seed/room.iprof" "root@$(public_ip lg):/root/build-native.log" "$STATE/"
  natives
  log "provisioned"
}

natives() {
  local app_ip; app_ip=$(private_ip app)
  scp "${SSH_OPTS[@]}" -q "$HERE/native-image.sh" "$HERE/remote-bench.sh" "root@$(public_ip lg):/root/seed/"
  log "building native-g1 and native-ce on the load generator"
  on lg env GRAAL_IMAGE="$GRAAL_IMAGE" CE_IMAGE="$CE_IMAGE" APP_IP="$app_ip" bash -s <<'REMOTE'
set -euo pipefail
source /root/seed/native-image.sh
docker pull -q "$CE_IMAGE" >/dev/null
native_image "$GRAAL_IMAGE" /root/native-g1 /root/build-native-g1.log -O3 --pgo=/pgo/room.iprof --gc=G1
native_image "$CE_IMAGE" /root/native-ce /root/build-native-ce.log -O2
O="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"
for d in native-g1 native-ce seed; do rsync -az --delete -e "ssh $O" /root/$d/ root@$APP_IP:/root/$d/; done
REMOTE
  scp "${SSH_OPTS[@]}" -q "root@$(public_ip lg):/root/build-native-g1.log" "root@$(public_ip lg):/root/build-native-ce.log" "$STATE/"
}

run() {
  on lg "nohup env $(printf '%q ' "${ENV_PASS[@]}") bash /root/seed/remote-bench.sh $* < /dev/null > /root/bench.log 2>&1 &"
  log "running; follow with: $0 logs"
}

fetch() {
  mkdir -p "$STATE/results"
  rsync -az -e "ssh ${SSH_OPTS[*]}" "root@$(public_ip lg):/root/results/" "$STATE/results/"
  scp "${SSH_OPTS[@]}" -q "root@$(public_ip lg):/root/bench.log" "$STATE/results/bench.log"
  log "results in ${STATE#"$ROOT"/}/results"
}

down() {
  local role
  for role in lg app; do hcloud server describe "$NAME-$role" >/dev/null 2>&1 && hcloud server delete "$NAME-$role" >/dev/null && log "deleted $NAME-$role"; done
  hcloud firewall describe "$NAME" >/dev/null 2>&1 && { until hcloud firewall delete "$NAME" >/dev/null 2>&1; do sleep 3; done; log "deleted firewall"; }
  hcloud network describe "$NAME" >/dev/null 2>&1 && { until hcloud network delete "$NAME" >/dev/null 2>&1; do sleep 3; done; log "deleted network"; }
  hcloud ssh-key describe "$NAME" >/dev/null 2>&1 && hcloud ssh-key delete "$NAME" >/dev/null && log "deleted ssh key"
  status
}

status() {
  hcloud server list -l "$LABEL"; hcloud network list -l "$LABEL"; hcloud firewall list -l "$LABEL"; hcloud ssh-key list -l "$LABEL"
}

ENV_PASS=(PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin)
for var in APPS REPS SECS CONCS SOAK_MINUTES OPEN_CONNS; do if [ -n "${!var:-}" ]; then ENV_PASS+=("$var=${!var}"); fi; done

case "${1:-}" in
  up) up ;;
  provision) provision ;;
  natives) natives ;;
  run) shift; run "$@" ;;
  logs) on lg tail -n 200 -f /root/bench.log ;;
  fetch) fetch ;;
  ssh) shift; on "$@" ;;
  status) status ;;
  down) down ;;
  *) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 1 ;;
esac
