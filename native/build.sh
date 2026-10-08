#!/usr/bin/env bash
# Builds SQLite as a shared library from the amalgamation the Rust port bundles (libsqlite3-sys
# 0.38.2, SQLite 3.53.2), with the flags its build.rs passes for the `bundled` feature, at -O3 as
# cargo's release profile does (flags in sqlite-flags.txt). Writes native/<os>-<arch>/libcampfire_sqlite3.{dylib,so}.
#   native/build.sh            # this machine (macOS, or Linux with cc)
#   native/build.sh linux      # Linux for Docker's architecture, in the Rust build image
#   native/build.sh static     # the same as a static library, for the native image
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${SQLITE_SRC:-$HOME/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/libsqlite3-sys-0.38.2/sqlite3}
read -r -a FLAGS < "$HERE/sqlite-flags.txt"
if [ "${1:-}" = linux ]; then
  arch=$(docker run --rm rust:1.98.1-trixie uname -m)
  mkdir -p "$HERE/linux-$arch"
  docker run --rm -v "$SRC":/sqlite:ro -v "$HERE/linux-$arch":/out rust:1.98.1-trixie \
    cc "${FLAGS[@]}" /sqlite/sqlite3.c -o /out/libcampfire_sqlite3.so -lpthread -ldl -lm
elif [ "${1:-}" = static ]; then
  arch=$(docker run --rm rust:1.98.1-trixie uname -m)
  mkdir -p "$HERE/static/linux-$arch"
  docker run --rm -v "$SRC":/sqlite:ro -v "$HERE/static/linux-$arch":/out -v "$HERE/sqlite-flags.txt":/flags:ro rust:1.98.1-trixie \
    sh -c 'cc $(sed "s/-shared//" /flags) -c /sqlite/sqlite3.c -o /tmp/sqlite3.o && ar rcs /out/libcampfire_sqlite3.a /tmp/sqlite3.o'
elif [ "$(uname)" = Linux ]; then
  mkdir -p "$HERE/linux-$(uname -m)"
  cc "${FLAGS[@]}" "$SRC/sqlite3.c" -o "$HERE/linux-$(uname -m)/libcampfire_sqlite3.so" -lpthread -ldl -lm
else
  mkdir -p "$HERE/macos-aarch64"
  cc "${FLAGS[@]}" "$SRC/sqlite3.c" -o "$HERE/macos-aarch64/libcampfire_sqlite3.dylib"
fi
