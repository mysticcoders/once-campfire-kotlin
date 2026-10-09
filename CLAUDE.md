# once-campfire-kotlin

Campfire in Kotlin: a port of the Rails app in `reference/` (a submodule pinned to the SHA the
Rust port matched, `90b3300`), following the Rust port in `../once-campfire-rust` (matched at its
`ac6d655`), which is the working oracle. It runs on the JVM and as a GraalVM native image.

- The Rails app is the reference for behavior; the Rust port shows how it was matched, and its
  release binary is what parity checks compare against (`bench/parity.sh`).
- Stay compatible with existing installs: the SQLite schema, the storage layout, and the
  signed/encrypted cookies, so people stay signed in when switching implementations.
- Port behavior file by file from the Rust crate that owns it, and cite the Rust file
  (`once-campfire-rust/crates/kit/src/params.rs`) or the Ruby (`reference/app/...`) it matches.
  Note any deliberate divergence in the README.
- Don't edit `reference/`.

## Layout

| Path | Package | Rust crate | What |
|---|---|---|---|
| `ruby-compat` | `campfire.ruby` | `crates/ruby` | Ruby's string and number behavior, checked by `vectors/ruby_core.json` |
| `rails-compat` | `campfire.rails` | `crates/rails_compat` | key generation, message verifier/encryptor, cookie jars, signed ids, global ids, Turbo stream names, checked by `vectors/rails_compat.json` |
| `routes` | `campfire.routes` | `crates/routes` | path helpers and route recognition, checked by `vectors/campfire_routes.json` |
| `db` | `campfire.db` | `crates/db` | SQLite (FFM on the JVM, the C interface in a native image), reader pool, models |
| `kit` | `campfire.kit` | `crates/kit` | HTTP types, Netty server, front server, middleware, request context, gzip splicing |
| `assets` | `campfire.assets` | `crates/assets` | Propshaft digesting, importmap and stylesheet tags, and `public/` serving, generated at build time from reference/ plus `assets/overrides` |
| `richtext` | `campfire.richtext` | `crates/richtext` | the Action Text pipeline: a port of html5ever 0.35 with the Rust port's vendored fixes and Gumbo limits, the sanitizer, attachments, autolinking and plain text, checked by the Rust crate's corpus |
| `views` | `campfire.views` | `crates/views` | templates, layouts, fragment cache |
| `campfire` | `campfire.app` | `crates/campfire` | config, controllers, concerns, the response cache, `main` |
| `native/` | — | — | SQLite built from libsqlite3-sys's amalgamation and flags |
| `graal/` | — | — | native-image build |
| `bench/` | — | — | parity checks against the Rust binary (`parity.sh`, `parity-requests.sh`), the Docker comparison (`compare-docker.sh`), JVM profiling |
| `tools/` | — | — | generators: Askama templates to Kotlin |
| `vectors/` | — | — | golden vectors copied from the Rust repo, produced by the Rails reference |

## Working rules

- Java 25 comes from mise (`mise.toml`); run Gradle as `./gradlew`. `native/build.sh` (macOS),
  `native/build.sh linux` and `native/build.sh static` build SQLite into `native/<platform>/`, which
  the distribution ships beside the jars.
- `./gradlew test` runs everything. Golden-vector tests read `vectors/*.json` (the
  `campfire.vectors` system property points at the directory).
- `bench/parity.sh DB COOKIE_FILE [ROOMS]` and `bench/parity-requests.sh DB COOKIE_FILE bench/requests.txt`
  must stay at 0 different after any change on the request path. They compare bodies, ETags and
  headers in order; the headers that legitimately vary (Date, X-Request-Id, X-Runtime, cookie
  values, Last-Modified, and a gzipped body's Content-Length, see README "Gzip bytes") are masked. `Headers` mirrors `http::HeaderMap`'s ordering (entries in
  first-insertion order, values grouped, swap-remove), which the Rust port's header order follows.
- Comment methods and classes, not individual lines; no single-line comments inside method bodies.
- Write code that reads like the surrounding code: small, clearly named functions. Hot paths avoid
  per-request allocation where it's cheap to (pre-encoded static text, reused buffers), and say so.
- Performance changes need before-and-after measurements; keep raw results in ignored `tmp/`.
