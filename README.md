# Campfire in Kotlin

A Kotlin implementation of [ONCE Campfire](https://github.com/basecamp/once-campfire), ported from
[the Rust port](https://github.com/basecamp/once-campfire-rust), with the same SQLite database,
storage layout and signed/encrypted cookies. It runs on the JVM or as a GraalVM native image.

**Status:** early. The framework layers the Rust app runs on every request are ported. Only the room
page (`GET /rooms/:id`) is served, byte-identical to the Rust port's (headers included), except
for messages with attachments or sounds. It matches the Rust port at
[`ac6d655`](https://github.com/basecamp/once-campfire-rust/commit/ac6d655), including its response
cache: a signed-in room page is kept whole (identity or gzip, up to 1 MB, for 15 s) and served
again after authentication and the room's access check have run, until any commit to the database
moves its generation on (`PRAGMA data_version` on a connection of its own), with fragment caching
isolated by generation and origin in the same way. `CAMPFIRE_RESPONSE_CACHE_MB` bounds it (64 by
default; 0 turns it off). See `campfire/src/main/kotlin/campfire/app/ResponseCache.kt`.

## Building

```sh
native/build.sh && native/build.sh linux && native/build.sh static
./gradlew :campfire:installDist
SECRET_KEY_BASE=... CAMPFIRE_DATABASE_PATH=storage/db/production.sqlite3 HTTP_PORT=3000 DISABLE_SSL=1 \
  JAVA_OPTS=-XX:+UseParallelGC campfire/build/install/campfire/bin/campfire
```

Native image (Oracle GraalVM, with a PGO profile from a load run), with the default Serial GC or
with G1 (faster on two or more cores, at several times the memory; see round three):

```sh
graal/build.sh oracle-pgo container-registry.oracle.com/graalvm/native-image:25 -O3 --pgo=/pgo/room.iprof
graal/build.sh oracle-pgo-g1 container-registry.oracle.com/graalvm/native-image:25 -O3 --pgo=/pgo/room.iprof --gc=G1
```

## Performance

These are measurements of the room page (`GET /rooms/:id`, the busy room, 40 messages), with Kotlin
running the same request path as the Rust port, in two rounds: without the response cache against
the Rust port at `5eb6d2d` (round one), and with it against upstream `ac6d655` (round two). The
Hetzner runs are the reference figures; the Docker VM run below them came first, before the review
fixes and the cache. The request path:

- the front server (Thruster's job) and Rack::Deflater;
- the Rails middleware, `public/` files and the full route table;
- the request context with its cookie jar, session and flash;
- the controller's before-actions, including the hourly session refresh;
- the bounded fragment cache with Rails keys;
- the layout.

Both apps returned byte-identical bodies and ETags, and the same headers in the same order
(`bench/parity.sh`, `bench/parity-requests.sh`).

**Setup:** Docker's Linux VM on an M4 Max, the app pinned to 2 CPUs and the loadgen to 2 others,
one app at a time on a fresh copy of the seed (`bench/compare-docker.sh`). Each run had a 20 s
warmup, then 10 s per step and 3 alternating reps; the tables show medians. Every response was
validated byte for byte against the Rust page: 135 runs, none invalid. The JVM ran with
`-XX:+UseParallelGC`. The native image is Oracle GraalVM 25 with `-O3`, PGO and `-march=native`.

**Request logging off** (`LOG_REQUESTS=false`):

| Connections | Rust req/s (CPU per request) | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 6,851 (133 µs) | 6,340 (114 µs) | 5,888 (122 µs) |
| 16 | 23,214 (83 µs) | 21,588 (87 µs) | 18,389 (96 µs) |
| 64 | 23,493 (82 µs) | 22,223 (83 µs) | 19,214 (93 µs) |
| 256 | 22,805 (84 µs) | 22,330 (83 µs) | 17,224 (98 µs) |
| 1,024 | 20,136 (95 µs) | 21,154 (88 µs) | 16,712 (100 µs) |

**Latency and memory, logging off:**

| | p99 at 64 / 1,024 connections | Memory under load |
|---|---|---:|
| Rust | 4.3 / 142 ms | 20–80 MB |
| Kotlin JVM | 4.6 / 53 ms | about 350 MB |
| Kotlin native | 6.7 / 76 ms | 67–98 MB |

**As shipped** (request log on), req/s:

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 16 | 21,038 | 19,917 | 17,637 |
| 64 | 21,187 | 20,369 | 18,278 |
| 1,024 | 17,966 | 19,076 | 15,067 |

The Rust and JVM figures come from one run; the native figures (and the Rust reference in the
native run, within 4% of these) from a second run after a rebuild.

### On Hetzner (x86)

**Setup:**

- **Servers:** Hetzner Cloud in Hillsboro, AMD EPYC Milan at 2.0 GHz. The app server is a ccx13
  (2 dedicated vCPUs, which are one physical core with its two SMT threads) or a ccx23 (two physical
  cores). The load generator is a separate ccx23 on the same private network, measured at 12–17
  Gbit/s.
- **Apps:** each ran alone, in Docker with host networking, on a fresh copy of the seed.
  - The Rust port runs as its production image, built from its `Dockerfile`.
  - Kotlin runs on `eclipse-temurin:25-jre` with `-XX:+UseParallelGC`.
  - The native image is Oracle GraalVM 25 with `-O3`, a PGO profile recorded on the same code, and
    `-march=x86-64-v3`.
- **Runs:** a 20 s warmup, then 15 s per step and 3 alternating reps; the tables show medians. CPU
  per request is each container's cgroup usage. Request logging is off (`LOG_REQUESTS=false`).
- **Verification:** every response was checked byte for byte against the Rust page: 306
  closed-loop runs, none invalid. Bodies, ETags and headers matched Rust for both user-agent
  branches and both encodings before each run.
- **Rust's listen backlog:** the tables' Rust is the production image with `rust-backlog.patch`,
  which listens with the kernel's `somaxconn` instead of mio's 128 (see below). Below 4,096
  connections it changes throughput by 2% or less; it only removes stalls.

Everything is in `bench/cloud/` (`cloud.sh up`, `provision`, `natives`, `run`, `fetch`, `down`).
The `APPS` variable picks the apps, and the phases are `parity closed open startup jit`. A full
run takes about an hour. Time both servers before trusting a run, for example with
`python3 -c 'for i in range(30_000_000): pass'` under `time` (about 1.5 s on a healthy EPYC Milan
vCPU). Hetzner hands out slow ones: in round three, three ccx23s in a row in Hillsboro ran it in
9.6–10.6 s on every vCPU, idle and with no steal time, and an earlier afternoon run in Ashburn was
45% slower for every app.

**Round one: no response cache, against the Rust port at `5eb6d2d`.** req/s (share of Rust; CPU
per request), one core:

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 2,379 (268 µs) | 2,810 (118%; 183 µs) | 2,716 (114%; 189 µs) |
| 16 | 7,920 (245 µs) | 8,850 (112%; 217 µs) | 7,647 (97%; 245 µs) |
| 64 | 8,530 (228 µs) | 8,961 (105%; 212 µs) | 7,747 (91%; 242 µs) |
| 256 | 8,788 (222 µs) | 8,945 (102%; 213 µs) | 7,408 (84%; 249 µs) |
| 1,024 | 7,196 (271 µs) | 8,536 (119%; 225 µs) | 6,887 (96%; 265 µs) |
| 4,096 | 5,906 (329 µs) | 7,193 (122%; 270 µs) | 6,002 (102%; 307 µs) |

Two cores:

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 2,484 (244 µs) | 3,081 (124%; 164 µs) | 2,844 (115%; 177 µs) |
| 16 | 14,464 (261 µs) | 18,340 (127%; 194 µs) | 16,095 (111%; 216 µs) |
| 64 | 15,122 (250 µs) | 20,664 (137%; 184 µs) | 17,562 (116%; 207 µs) |
| 256 | 16,407 (232 µs) | 20,564 (125%; 187 µs) | 17,703 (108%; 205 µs) |
| 1,024 | 13,448 (283 µs) | 19,613 (146%; 196 µs) | 16,759 (125%; 216 µs) |
| 4,096 | 11,654 (325 µs) | 17,300 (148%; 218 µs) | 12,605 (108%; 246 µs) |

Each app kept the two-core box about 3.8 of its 4 threads busy, while the load generator stayed
under 1.8 of its 4.

**Round two: with the response cache, against upstream `ac6d655`.** One core:

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 3,787 (123 µs) | 4,411 (116%; 86 µs) | 4,264 (113%; 90 µs) |
| 16 | 18,614 (101 µs) | 19,175 (103%; 97 µs) | 16,201 (87%; 115 µs) |
| 64 | 18,634 (102 µs) | 21,643 (116%; 88 µs) | 16,993 (91%; 110 µs) |
| 256 | 19,155 (99 µs) | 24,049 (126%; 81 µs) | 18,431 (96%; 102 µs) |
| 1,024 | 15,686 (122 µs) | 21,377 (136%; 92 µs) | 16,419 (105%; 113 µs) |
| 4,096 | 12,793 (149 µs) | 18,491 (145%; 105 µs) | 12,654 (99%; 139 µs) |

Two cores, up to 256 connections:

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 4,337 (107 µs) | 4,318 (100%; 79 µs) | 3,876 (89%; 86 µs) |
| 16 | 25,505 (116 µs) | 28,701 (113%; 98 µs) | 24,557 (96%; 117 µs) |
| 64 | 30,045 (109 µs) | 38,057 (127%; 77 µs) | 36,106 (120%; 89 µs) |
| 256 | 30,154 (110 µs) | 35,581 (118%; 78 µs) | 34,746 (115%; 90 µs) |

At 1,024 cached connections on two cores, the app server sends 4–5 Gbit/s of gzipped pages and
Hetzner's private network drops packets. In 15 s, Rust retransmitted 252,000 TCP segments and
Kotlin 452,000. Both apps sat half idle through retransmission timeouts: the JVM served about 20k
req/s against Rust's 26k, with a p99 of about 340 ms. Thread dumps, a flight recording, 4 to 16
workers and a second client (oha) all pointed at the network, so that row measures the network
and isn't shown.

**Round three: Java 27, Oracle GraalVM's JIT, and the native image's GC and edition**, with the
response cache, against upstream `ac6d655`, in Ashburn (the Hillsboro servers were slow, see
above). The apps:

- `kotlin`: Temurin 25 (`eclipse-temurin:25-jre`), `-XX:+UseParallelGC`, as before.
- `kotlin27`: Temurin 27 (`eclipse-temurin:27_35-jre`), the same options. The app runs on it
  unchanged and matched Rust in `bench/parity.sh` and `bench/parity-requests.sh`.
- `graaljit`: Oracle GraalVM 25.0.4.1.1's JDK (`container-registry.oracle.com/graalvm/jdk:25i4`)
  as a JVM, with the Graal JIT compiler in place of C2, the same options. Oracle GraalVM has no
  JDK 27 release.
- `native`: Oracle GraalVM 25.0.4.1.1 native image with `-O3`, `-march=x86-64-v3`, PGO and the
  Serial GC. `cloud.sh provision` now records the profile on the load generator from an instrumented
  build of the same jars; round two's profile was recorded on arm64 before the response cache.
- `native-g1`: the same with `--gc=G1`.
- `native-ce`: GraalVM Community 25.0.2 (`ghcr.io/graalvm/native-image-community:25`) with `-O2`
  and Serial. Community has no PGO, `-O3` or G1.

Oracle GraalVM's JDK compresses with a different zlib (24,106 bytes against Rust's 24,229 for the
gzipped page), so `remote-bench.sh`'s parity check now masks a gzipped Content-Length, as
`bench/parity.sh` does. Bodies, ETags and every other header matched Rust for every app; 0 invalid
responses in 342 closed-loop runs.

One core, req/s (share of Rust):

| Connections | Rust | Temurin 25 | Temurin 27 | Oracle GraalVM JIT | Oracle native, PGO |
|---:|---:|---:|---:|---:|---:|
| 1 | 3,592 | 4,021 (112%) | 4,038 (112%) | 3,823 (106%) | 3,789 (105%) |
| 16 | 16,861 | 17,738 (105%) | 18,087 (107%) | 18,975 (113%) | 15,517 (92%) |
| 64 | 17,418 | 21,260 (122%) | 21,523 (124%) | 22,158 (127%) | 16,836 (97%) |
| 256 | 18,140 | 22,724 (125%) | 23,577 (130%) | 24,732 (136%) | 19,217 (106%) |
| 1,024 | 14,129 | 20,544 (145%) | 21,238 (150%) | 21,649 (153%) | 17,072 (121%) |
| 4,096 | 11,577 | 18,040 (156%) | 18,574 (160%) | 19,566 (169%) | 13,576 (117%) |

Two cores, the native builds, req/s (share of Temurin 25; CPU per request). Rust's column is
there for completeness; see below for why it isn't compared:

| Connections | Temurin 25 | Oracle native, Serial | Oracle native, G1 | Community native | Rust |
|---:|---:|---:|---:|---:|---:|
| 1 | 6,862 (86 µs) | 6,380 (93%; 92 µs) | 6,368 (93%; 93 µs) | 4,813 (70%; 148 µs) | 6,346 |
| 16 | 29,483 (108 µs) | 26,680 (90%; 123 µs) | 27,126 (92%; 123 µs) | 17,442 (59%; 198 µs) | 21,639 |
| 64 | 42,771 (84 µs) | 37,816 (88%; 96 µs) | 39,579 (93%; 93 µs) | 21,708 (51%; 163 µs) | 22,294 |
| 256 | 49,294 (76 µs) | 38,581 (78%; 93 µs) | 43,717 (89%; 86 µs) | 21,789 (44%; 159 µs) | 23,500 |
| 1,024 | 47,262 (80 µs) | 33,524 (71%; 100 µs) | 41,917 (89%; 90 µs) | 19,831 (42%; 170 µs) | 20,013 |
| 4,096 | 37,384 (100 µs) | 24,532 (66%; 123 µs) | 34,612 (93%; 108 µs) | 14,749 (39%; 207 µs) | 17,765 |

On two cores, up to 256 connections, Temurin 27 was within 3% of Temurin 25 (one run), and Oracle
GraalVM's JIT 92–103% of it (two runs on two servers). The first two-core server's private network
dropped packets past 256 connections, as in round two, so the table above comes from a second,
fresh server.

Memory under load (`memory.current`, median, two cores; the JVMs are capped at `-Xmx512m`; the
Oracle GraalVM JIT column is from the first two-core server):

| Connections | Rust | Temurin 25 | Oracle GraalVM JIT | Oracle native, Serial | Oracle native, G1 | Community native |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 21 MB | 191 MB | 187 MB | 42 MB | 118 MB | 30 MB |
| 64 | 26 MB | 228 MB | 202 MB | 43 MB | 118 MB | 53 MB |
| 256 | 37 MB | 258 MB | 266 MB | 45 MB | 187 MB | 89 MB |
| 1,024 | 78 MB | 326 MB | 309 MB | 49 MB | 349 MB | 170 MB |
| 4,096 | 231 MB | 339 MB | 331 MB | 64 MB | 522 MB | 47–406 MB |

G1's default maximum heap in a native image is 25% of RAM, and it uses the room; a heap cap would
trade some of its throughput back for memory, which hasn't been measured. Temurin 27 used about
the same memory as Temurin 25.

Startup (median of 5, from `docker run`; first `/up`, first room page, `memory.current` idle and
after the warmup):

| | First `/up` | First room page | Idle / after warmup |
|---|---:|---:|---:|
| Oracle native, Serial | 156 ms | 178 ms | 28 / 43 MB |
| Oracle native, G1 | 162 ms | 187 ms | 47 / 95 MB |
| Community native | 160 ms | 185 ms | 33 / 36 MB |
| Rust | 167 ms | 180 ms | 17 / 22 MB |
| Temurin 25 | 867 ms | 1,123 ms | 160 / 201 MB |

On the one-core server, where the JVMs started more slowly, Temurin 25 reached its first `/up` in
1,275 ms, Temurin 27 in 1,297 ms and Oracle GraalVM's JIT in 1,491 ms. In the `jit` phase (open
loop at 4,000 and 6,000 req/s after an oha warmup, one core), both Temurins had a p99 of 2.5–3.3
ms, Oracle GraalVM's JIT 2.7–13.1 ms, and Rust 1.4–1.9 ms.

**Rust on two cores in round three is unexplained.** It served 21,600–23,500 req/s at 16–256
connections and used 143–160 µs of CPU per request, against round two's 25,505–30,154 req/s and
109–116 µs on the same image and scripts in Hillsboro. On one core it matched round two (17,418
req/s and 109 µs at 64 connections). It reproduced on a second fresh server, the Kotlin builds
matched round two on both, and the reps agree within 2%. At 64 connections its four Tokio workers
were each about 45% busy while the server was 89% busy, so it is waiting on something rather than
short of CPU. The servers ran Ubuntu's 6.8.0-138 kernel; round two's kernel wasn't recorded.

**Latency and memory** (round one, one core):

| | p99 at 64 / 1,024 / 4,096 connections | Anonymous memory under load |
|---|---|---:|
| Rust | 11.7 / 284 / 1,797 ms | 14–226 MB |
| Kotlin JVM | 8.9 / 147 / 639 ms | 277–513 MB |
| Kotlin native | 11.2 / 169 / 728 ms | 27–50 MB |

In round one, the native image's heap grew with the connection count: it used 555 MB at 1,024
connections on two cores, against 27–91 MB at 64 or fewer. With the response cache (round three)
it stays at 42–65 MB at every connection count; with G1 it doesn't (round three). Give it a heap cap
(`-Xmx`) in production either way.

**Startup** (`startup` phase, median of 5 starts, from `docker run`, which itself takes about 270
ms for an empty container):

| | First `/up` | First room page | `memory.current` idle / after warmup |
|---|---:|---:|---:|
| Rust | 165 ms | 178 ms | 15–17 / 17–18 MB |
| Kotlin native | 144 ms | 165 ms | 28–29 / 28–37 MB |
| Kotlin JVM | 1,207 ms | 1,534 ms | 117–133 / 162–288 MB |

**Open loop** (round one, oha at a fixed rate over 2,048 connections, one core, median of 2): the
JVM served every request up to 7,000/s and saturated at about 7,500/s. Rust served every request up
to 6,000/s and saturated at about 6,400/s. The native image saturated at about 6,050/s. The JVM's
p99 at 4,000/s was 229–389 ms after the usual warmup, against 3–5 ms for Rust. It is JIT warmup on
oha's request variant (gzip, a curl user agent). The `jit` phase warms up with oha itself, and
that brought the p99 at 4,000/s down to 31–80 ms, against Rust's 2–3 ms. The rest is most likely
garbage collection, which hasn't been measured.

**Request logging** costs every app about 30% here, against 5–10% in the Docker VM. The request
log goes through Docker's log driver, whose CPU is spent outside the app's container.

**Rust's listen backlog.** The Rust port listens through Tokio's `TcpListener::bind`, which takes
its backlog from mio: 128, the standard library's value (`mio-1.2.3/src/net/tcp/listener.rs`).
Without the patch, 4 of Rust's 6 runs at 4,096 connections stalled: about one core busy,
2,100–3,100 req/s, and a 27.5 s worst case. In one stalled run the kernel counted 13,465
listen-queue overflows and sent 1,776 SYN cookies. The dropped SYNs are retried on Linux's 1, 3,
7, 15 s schedule while the server idles. Thruster, which the Rust front server replaces, is Go,
and Go listens with `somaxconn` (65,535 on these servers). This app's Netty listener asks for
1,024; it overflowed too, but its retried SYNs got in on the first retry, so its worst case was
1.4 s.

**What the results show:**

- **On Hetzner's x86 the JVM build is ahead of Rust:** 102–122% on one core and 124–148% on two
  without the cache; 103–145% and 100–127% with it.
- **The native image** is within 84–125% of Rust without the cache and 87–120% with it. It starts
  faster than Rust and idles at 28–29 MB, against Rust's 15–17 MB and the JVM's 117+ MB.
- **Round three:**
  - Temurin 27 is 0–4% ahead of Temurin 25 on one core and within 3% on two. It starts in the
    same time.
  - Oracle GraalVM's JIT is the fastest JVM on one core from 16 connections up (106–169% of Rust
    overall), 92–103% of Temurin 25 on two, and starts about 200 ms slower. Its memory is a JVM's.
  - The Oracle native image with Serial stays at 42–65 MB under any load, the least of anything
    from 1,024 connections up (Rust uses less below that), and starts fastest. Its throughput is
    66–93% of the JVM's on two cores, falling as connections grow; G1 doesn't fall, which points at
    the Serial collector, which collects on one thread while the app waits.
  - With G1, the Oracle native image holds 89–93% of the JVM's throughput at every load, but uses
    118–522 MB.
  - GraalVM Community's native image runs at 39–70% of the JVM's throughput and about 1.7× the
    Oracle build's CPU per request.
- **In the Docker VM on an M4 Max** (the run above, before the review fixes and the cache), the
  JVM was 92–105% of Rust and the native image 75–87%. The lead on x86 doesn't carry over to every
  CPU.
- **Caveats:**
  - It's one page. Writes, WebSockets, search and attachments aren't measured.
  - It's one and two cores on EPYC Milan; the upstream table uses four cores of a Ryzen AI MAX+ 395,
    so only the ratios compare.
  - The spike that started this repo skipped the framework layers, so its numbers in
    `once-campfire-rust/spikes/kotlin-room/README.md` aren't like for like with these.

## Known differences from the Rust port

Behavior the Kotlin port matches by a different route, or doesn't match yet:

- **Route recognition** (`routes`): routes are indexed by their first path segment and candidates
  matched by hand, instead of a `RegexSet`. The results are the same as a first-match regex scan,
  checked over a generated corpus of paths for every verb. It is about 70 ns per request for the
  room page.
- **WAL checkpoints** (`db`): SQLite's own auto-checkpoint, every 1,000 pages, on the committing
  write as in Rails. The Rust port moves this to a checkpointer thread fed by a WAL hook. The room
  page doesn't write; the message write path will need the Rust arrangement.
- **Fragment template digest** (`views`): a hash of the `views` module's Kotlin sources, generated
  at build time, rather than of the Askama templates. The digest only has to change when the
  templates do.
- **Nested boost fragments** (`views`): a message's boosts render inside its fragment rather than
  as separately cached fragments. The page output is the same, and only cache misses differ.
- **Base64 and JSON parsing** (`rails-compat`): custom decoding, so input Ruby and serde_json
  refuse (missing padding, stray bits, invalid UTF-8) is refused too.
- **Request lifecycle** (`kit`, `Ctx`/`Adapter`/`Router`): actions return a response and stop by
  throwing (`halt`, a `KitError`), where Rust returns a `Result`; any other exception is a 500 as a
  Rust panic is. `send_file` bodies are read into memory when sent rather than streamed, and
  `If-Modified-Since` is parsed as RFC 1123 (Java's parser) rather than jiff's RFC 2822. `Ctx.json`
  takes JSON text the caller encoded, since there is no serde. The flash's ETag part formats
  values as Rust's `Debug` does, except that unassigned and grapheme-extending characters aren't
  escaped. The error pages are the reference's `public/*.html`, copied onto the classpath at build
  time (`ErrorPages.PUBLIC`).
- **Gzip bytes** (`kit`): responses are compressed with the JDK's zlib at the same level (6) as the
  Rust port, which uses zlib-rs. zlib-rs follows zlib-ng, whose level 6 is a different match
  search (`deflate_medium`) from zlib's (`deflate_slow`), so the compressed bytes differ, on
  aarch64 as well as x86, and so can their length by a byte or so; the decoded body is the same.
  Since the response cache, a gzipped room page carries a `Content-Length` (the Rust port's
  `completed` collects the body), so the parity scripts compare decoded bodies and mask a gzipped
  response's `Content-Length`. Matching byte for byte would mean compressing with zlib-rs itself
  (a native library, as SQLite is).
- **Response cache** (`campfire`): Rust runs `begin` and `prepare` in its router for every route;
  here they wrap the routed actions (`routes()`), which is the same for the one endpoint served,
  `rooms#show`. Rust's other cached endpoints (`messages#index`, `users/sidebars#show`,
  `searches#index`) and its detached (broadcast) renderer aren't ported, as nothing here serves
  them. The origin's fragment scope (the SHA-256 of the request's base URL) is remembered for the
  last origin seen rather than hashed on every request.
- **Not yet verified against Rails:** `reference-tools/rails_compat_verify_rust.rb` checks the Rust
  crate's output against Rails, and hasn't been run against `rails_compat_kotlin_output.json`.
