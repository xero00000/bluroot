#!/bin/bash
# Pack the root proxy into one classes.dex. app_process loads only the first dex in a jar.
set -eu
CLASSROOT="$1"
DEXDIR="$2"
OUTDIR="$3"
ANDROID_JAR="$4"
D8="$5"
HAL_JAR="$6"
JARBIN="$7"

rm -rf "$DEXDIR"
mkdir -p "$DEXDIR" "$OUTDIR"
mapfile -d '' files < <(find "$CLASSROOT/com/bluroot/share/proxy" "$CLASSROOT/android/hardware/bluetooth" -name '*.class' -print0)
if [ "${#files[@]}" -eq 0 ]; then
  echo "no proxy classes under $CLASSROOT" >&2
  exit 1
fi
"$D8" --min-api 29 --lib "$ANDROID_JAR" --lib "$HAL_JAR" --output "$DEXDIR" "${files[@]}"
if [ ! -f "$DEXDIR/classes.dex" ]; then
  echo "d8 did not write classes.dex" >&2
  exit 1
fi
extra=$(find "$DEXDIR" -name 'classes[0-9]*.dex' | wc -l)
if [ "$extra" -ne 0 ]; then
  echo "proxy classes do not fit in one dex" >&2
  exit 1
fi
rm -f "$OUTDIR/bluroot-proxy.jar"
"$JARBIN" cf "$OUTDIR/bluroot-proxy.jar" -C "$DEXDIR" classes.dex
