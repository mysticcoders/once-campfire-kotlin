#!/usr/bin/env bash
# Runs on the load generator (sourced): builds this app as a native image from the JVM
# distribution's jars in /root/kotlin, linking SQLite statically from /root/static.
#
#   native_image IMAGE OUT LOG [native-image options...]
#
# IMAGE is the GraalVM native-image container; OUT gets the binary beside a copy of native/;
# /root/seed is mounted at /pgo for a --pgo profile.

# Builds one native image and prints the builder's version, optimization level, GC and PGO lines.
native_image() {
  local image=$1 out=$2 log=$3; shift 3
  rm -rf "$out" && mkdir -p "$out" && cp -R /root/kotlin/native "$out/native"
  docker run --rm -v /root/kotlin/lib:/lib-in:ro -v "$out":/out -v /root/seed:/pgo:ro -v /root/static:/static:ro \
    --entrypoint native-image "$image" \
    -cp '/lib-in/*' -o /out/campfire --no-fallback \
    -H:+UnlockExperimentalVMOptions -H:+ForeignAPISupport \
    --enable-native-access=ALL-UNNAMED -H:+ReportExceptionStackTraces \
    -H:NativeLinkerOption=-lm -H:CLibraryPath=/static \
    "$@" -march=x86-64-v3 campfire.app.MainKt > "$log" 2>&1 || { tail -40 "$log"; return 1; }
  grep -E 'Java version|optimization level|Garbage collector|Finished generating' "$log" || true
}
