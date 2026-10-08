# Campfire in Kotlin

A Kotlin implementation of [ONCE Campfire](https://github.com/basecamp/once-campfire), ported from
[the Rust port](https://github.com/basecamp/once-campfire-rust), with the same SQLite database,
storage layout and signed/encrypted cookies. It runs on the JVM or as a GraalVM native image.

**Status:** early. The framework layers the Rust app runs on every request are ported. Only the room
page (`GET /rooms/:id`) is served, byte-identical to the Rust port's (headers included), except
for messages with attachments or sounds.

## Building

```sh
native/build.sh && native/build.sh linux && native/build.sh static
./gradlew :campfire:installDist
SECRET_KEY_BASE=... CAMPFIRE_DATABASE_PATH=storage/db/production.sqlite3 HTTP_PORT=3000 DISABLE_SSL=1 \
  JAVA_OPTS=-XX:+UseParallelGC campfire/build/install/campfire/bin/campfire
```

Native image (Oracle GraalVM, with a PGO profile from a load run):

```sh
graal/build.sh oracle-pgo container-registry.oracle.com/graalvm/native-image:25 -O3 --pgo=/pgo/room.iprof
```

## Performance

These are measurements of the room page (`GET /rooms/:id`, the busy room, 40 messages), with Kotlin
running the same request path as the Rust port:

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

**Setup:** Hetzner Cloud in Hillsboro. The app server is a ccx13 (2 dedicated vCPUs, which are one
AMD EPYC Milan core with its two SMT threads, at 2.0 GHz). The load generator is a ccx23 on the same
private network. The Rust port runs as its production image (built from `once-campfire-rust`'s
`Dockerfile`), Kotlin on `eclipse-temurin:25-jre` with `-XX:+UseParallelGC`, and the native image
is Oracle GraalVM 25 with `-O3`, the PGO profile above and `-march=x86-64-v3`. Each app ran alone,
in Docker with host networking, on a fresh copy of the seed. Each run had a 20 s warmup, then 15 s
per step and 3 alternating reps; the tables show medians. All 108 closed-loop runs were checked
byte for byte against the Rust page, and none were invalid. Bodies, ETags and headers matched Rust
for both user-agent branches and both encodings (`bench/cloud/`; `cloud.sh up`, `provision`,
`run`, `fetch`, `down`).

An earlier run in Ashburn, before the pipelining and SQLite fixes, agreed with these figures to
within a few percent. A rerun there the next afternoon was about 45% slower for every app, on two
fresh app servers in turn, so it was abandoned for Hillsboro. Check one quick step against these
figures before trusting a new run.

**Request logging off**, req/s (share of Rust; CPU per request):

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 2,443 (273 µs) | 3,044 (125%; 192 µs) | 2,760 (113%; 193 µs) |
| 16 | 7,787 (250 µs) | 8,696 (112%; 219 µs) | 7,543 (97%; 249 µs) |
| 64 | 8,243 (236 µs) | 8,965 (109%; 211 µs) | 7,510 (91%; 249 µs) |
| 256 | 8,515 (230 µs) | 8,847 (104%; 212 µs) | 7,038 (83%; 260 µs) |
| 1,024 | 6,885 (284 µs) | 8,391 (122%; 228 µs) | 6,807 (99%; 270 µs) |
| 4,096 | 5,784 (336 µs) | 7,030 (122%; 276 µs) | 5,580 (96%; 324 µs) |

**Latency and memory, logging off:**

| | p99 at 64 / 1,024 / 4,096 connections | Anonymous memory under load |
|---|---|---:|
| Rust | 12.2 / 284 / 1,786 ms | 14–225 MB |
| Kotlin JVM | 9.2 / 145 / 660 ms | 279–506 MB |
| Kotlin native | 11.6 / 169 / 768 ms | 26–45 MB |

**As shipped** (request log on), req/s (share of Rust):

| Connections | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 16 | 5,412 | 5,887 (109%) | 5,099 (94%) |
| 64 | 5,920 | 6,050 (102%) | 5,129 (87%) |
| 1,024 | 4,856 | 5,709 (118%) | 4,538 (93%) |

Logging costs all three about 30% here, against 5–10% in the Docker VM: the request log goes
through Docker's log driver, whose CPU is spent outside the app's container. With logging on, the
apps' containers used 1.4–1.6 cores while the server was 1.8–2.0 cores busy.

**Rust's listen backlog at 4,096 connections.** 4 of Rust's 6 runs at 4,096 connections stalled
(all 3 as shipped, 1 of 3 with logging off): about one core busy, 2,100–3,100 req/s, and a 27.5 s
worst case. The Rust port's listener is Tokio's `TcpListener::bind`, which takes its backlog from
mio: 128, the standard library's value (`mio-1.2.3/src/net/tcp/listener.rs`). During a stalled run
the kernel counted 13,465 listen-queue overflows and sent 1,776 SYN cookies. The dropped SYNs are
retried on Linux's 1, 3, 7, 15 s schedule, and the server idles while it waits. Thruster, which
the Rust front server replaces, is Go, and Go listens with the kernel's `somaxconn` (65,535 on
these servers). This app's Netty listener asks for 1,024. It overflowed too (2,856 drops at 4,096
connections), but its retried SYNs got in on the first retry, so its worst case was 1.4 s. The
4,096-connection medians above are of the 3 runs each, so Rust's as-shipped median (2,317 req/s,
p99 4.1 s) is a stalled run and is left out of the as-shipped table.

**Open loop** (oha at a fixed rate over 2,048 connections, logging off, median of 2): Rust served
every request up to 6,000/s (p99 31 ms) and saturated at about 6,100/s. The native image served
every request at 4,000/s (p99 9 ms) and saturated at about 5,900/s. The JVM served every request
up to 7,000/s (p99 122 ms) and saturated at about 7,300/s. Its p99 at 4,000/s was 315–389 ms in
both reps, with a p50 of 1.0 ms, and the earlier Ashburn run showed the same thing. Rust and the
native image don't. That points to the JIT. oha sends a different request variant from the
warmup's (gzip, a curl user agent), so the first second may still be compiling that path. This
hasn't been confirmed.

**What the results show:**

- **In the Docker VM the JVM build is about level with Rust.** It is 5–8% behind at 1–64
  connections, level at 256 and ahead at 1,024, with a much tighter tail there. It uses 4–18×
  Rust's memory, depending on load.
- **On Hetzner's x86 the JVM build is ahead of Rust:** 104–125% with logging off. That box is one
  physical core, so these are single-core results.
- **The native image** is within 75–87% of Rust's throughput in the Docker VM and 83–113% on
  Hetzner. It uses less memory than Rust under heavy load on Hetzner, and has no JIT warmup.
- **Caveats:**
  - It's one cached page. Writes, WebSockets and cache misses (rich text, attachments) aren't
    measured.
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
- **Gzip bytes** (`kit`): responses are compressed with the JDK's zlib at the same level as the
  Rust port, which uses zlib-rs. On x86, where zlib-rs takes AVX2 paths, the compressed bytes
  differ (same length, same decoded body). The parity scripts compare decoded bodies.
- **Not yet verified against Rails:** `reference-tools/rails_compat_verify_rust.rb` checks the Rust
  crate's output against Rails, and hasn't been run against `rails_compat_kotlin_output.json`.
