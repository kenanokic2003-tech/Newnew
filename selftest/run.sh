#!/usr/bin/env bash
# Runs the KENO vision + physics self-test on a desktop JVM (needs JDK 17+ and kotlinc 2.0.x on PATH).
# usage: ./selftest/run.sh   (from the project root)
set -e
OUT=build_selftest; rm -rf "$OUT"; mkdir -p "$OUT"
kotlinc -jvm-target 17 -nowarn -d "$OUT" app/src/main/java/com/keno/billiards/core/*.kt selftest/*.kt
KLIB="$(dirname "$(dirname "$(which kotlinc)")")/lib/kotlin-stdlib.jar"
java -Dselftest.out=selftest_out -cp "$OUT:$KLIB" selftest.SelfTestKt
