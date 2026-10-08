#!/usr/bin/env bash
# Builds the app as a GraalVM native image for Linux (Docker's architecture), from the jars
# `./gradlew :campfire:installDist` produced. The binary lands in build/native/<name>/ beside native/<platform>/
# with the SQLite library, where the app looks for it.
#   graal/build.sh NAME IMAGE [native-image options...]
#   graal/build.sh ce     ghcr.io/graalvm/native-image-community:25
#   graal/build.sh oracle container-registry.oracle.com/graalvm/native-image:25 -O3
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
NAME=$1 IMAGE=$2; shift 2
OUT=$HERE/build/native/$NAME
rm -rf "$OUT"; mkdir -p "$OUT"
cp -R "$HERE/campfire/build/install/campfire/native" "$OUT/native"
docker run --rm -v "$HERE/campfire/build/install/campfire/lib":/lib-in:ro -v "$OUT":/out -v "$HERE/build/pgo":/pgo -v "$HERE/native/static":/static:ro \
  --entrypoint native-image "$IMAGE" \
  -cp '/lib-in/*' -o /out/campfire --no-fallback \
  -H:+UnlockExperimentalVMOptions -H:+ForeignAPISupport \
  --enable-native-access=ALL-UNNAMED -H:+ReportExceptionStackTraces \
  -H:NativeLinkerOption=-lm -H:CLibraryPath=/static/linux-$(docker run --rm rust:1.98.1-trixie uname -m) \
  "$@" campfire.app.MainKt 2>&1 | tee "$OUT/build.log" | tail -25
ls -la "$OUT"
