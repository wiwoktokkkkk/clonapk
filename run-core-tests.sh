#!/usr/bin/env bash
# Uji mesin clone (pure Java) tanpa perlu perangkat Android atau Flutter SDK.
#
# Jalankan:  ./run-core-tests.sh
# Butuh:     JDK 11+ dan koneksi internet (sekali, untuk unduh BouncyCastle)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
LIB="$ROOT/tools/lib"
BCVER="1.81"
APKSIG="apksig-8.11.1"

# Folder jar wajib ada dulu: pada clone baru ia kosong (isinya di-gitignore).
mkdir -p "$LIB"

if [ ! -f "$LIB/$APKSIG.jar" ]; then
  echo "Mengunduh $APKSIG.jar ..."
  curl -fsSL -o "$LIB/$APKSIG.jar" \
    "https://dl.google.com/android/maven2/com/android/tools/build/apksig/8.11.1/$APKSIG.jar"
fi
CP="$LIB/bcprov-jdk18on-$BCVER.jar:$LIB/bcpkix-jdk18on-$BCVER.jar:$LIB/bcutil-jdk18on-$BCVER.jar:$LIB/$APKSIG.jar"

for a in bcprov bcpkix bcutil; do
  jar="$LIB/$a-jdk18on-$BCVER.jar"
  if [ ! -f "$jar" ]; then
    echo "Mengunduh $a-jdk18on-$BCVER.jar ..."
    curl -fsSL -o "$jar" \
      "https://repo1.maven.org/maven2/org/bouncycastle/$a-jdk18on/$BCVER/$a-jdk18on-$BCVER.jar"
  fi
done

OUT="$ROOT/build/core-classes"
rm -rf "$OUT" && mkdir -p "$OUT"

echo "Mengompilasi mesin clone ..."
javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" \
  $(find "$ROOT/android/app/src/main/java/com/clonapk/core" "$ROOT/tools/test" -name '*.java')

echo "Menjalankan uji ..."
java -cp "$OUT:$CP" com.clonapk.core.CloneTest "$ROOT/build/clone-test"
