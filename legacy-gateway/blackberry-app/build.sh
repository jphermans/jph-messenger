#!/bin/bash
# =============================================================================
# JPH Messenger - BlackBerry Build Script (LINUX - no Windows needed!)
# =============================================================================
# VERIFIED working build chain on this server:
#   javac (JDK 8, -source 1.3) -> ProGuard -microedition (preverify) -> RAPC -> .cod
#
# Requirements:
#   - JDK 8            (e.g. /tmp/jdk8u504-b01, Temurin 8)
#   - ProGuard         (apt-get install proguard -> /usr/share/java/proguard.jar)
#   - BlackBerry JDE 7.1 extracted at BB_JDE (rapc.jar + net_rim_api.jar)
#     (download: https://archive.org/download/java-for-blackberryos/BlackBerry%20JDE/BlackBerry_JDE_7.1.0.exe
#      then: 7z x exe -> locate MSCF cab -> cabextract -> files land in BB_JDE)
#
# Usage: ./build.sh
# Output: dist/JPHMessenger.cod + dist/JPHMessenger.jad (OTA-ready)
# =============================================================================
set -e

JAVA8=${JAVA8:-/tmp/jdk8u504-b01}
BB_JDE=${BB_JDE:-/tmp/bbjde}
PROGUARD=${PROGUARD:-/usr/share/java/proguard.jar}

# --- validate ---
[ -x "$JAVA8/bin/javac" ] || { echo "ERROR: JDK8 not found at $JAVA8"; exit 1; }
[ -f "$BB_JDE/rapc.jar" ] || { echo "ERROR: rapc.jar not found at $BB_JDE"; exit 1; }
[ -f "$BB_JDE/net_rim_api.jar" ] || { echo "ERROR: net_rim_api.jar not found at $BB_JDE"; exit 1; }
[ -f "$PROGUARD" ] || { echo "ERROR: proguard.jar not found at $PROGUARD"; exit 1; }

export PATH="$JAVA8/bin:$PATH"

rm -rf build dist preverify_out
mkdir -p build dist preverify_out

# --- Step 1: compile (Java 1.3 bytecode against BB API) ---
echo "=== [1/4] javac ==="
javac -source 1.3 -target 1.3 \
    -bootclasspath "$BB_JDE/net_rim_api.jar" \
    -classpath "$BB_JDE/net_rim_api.jar" \
    -d build \
    src/com/jph/*.java

# --- Step 2: jar ---
echo "=== [2/4] jar ==="
jar cf build/JPHMessenger.jar -C build com

# --- Step 3: ProGuard preverify (adds CLDC stack maps; replaces preverify.exe) ---
echo "=== [3/4] ProGuard preverify ==="
java -jar "$PROGUARD" \
    -injars build/JPHMessenger.jar \
    -outjars preverify_out/JPHMessenger.jar \
    -libraryjars "$BB_JDE/net_rim_api.jar" \
    -microedition \
    -dontshrink -dontoptimize -dontobfuscate

# --- Step 4: RAPC -> COD ---
echo "=== [4/4] RAPC ==="
# NOTE: no -midlet flag - this is a native UiApplication (main() entry),
# NOT a MIDlet. Packaging a UiApplication as MIDlet causes
# "Uncaught exception: exception thrown in a middle constructor" on device.
java -classpath "$BB_JDE/rapc.jar" net.rim.tools.compiler.Compiler \
    import="$BB_JDE/net_rim_api.jar" \
    -codename=JPHMessenger \
    preverify_out/JPHMessenger.jar

# --- Build OTA JAD (RIM-COD-URL style, no Jar-Size needed for COD install) ---
CODSIZE=$(stat -c%s JPHMessenger.cod)
cp JPHMessenger.cod dist/
printf 'MIDlet-Name: JPH Messenger\nMIDlet-Version: 0.1.0\nMIDlet-Vendor: JPH\nMIDlet-1: JPH Messenger,,com.jph.JPHMessenger\nMIDlet-Jar-URL: JPHMessenger.jar\nMIDlet-Jar-Size: 0\nRIM-COD-URL: JPHMessenger.cod\nRIM-COD-Size: %s\nMicroEdition-Profile: MIDP-2.0\nMicroEdition-Configuration: CLDC-1.1\n' "$CODSIZE" > dist/JPHMessenger.jad

echo
echo "=== BUILD COMPLETE ==="
ls -la dist/
echo
echo "OTA install: serve dist/ over HTTP, open JPHMessenger.jad in BlackBerry Browser"
echo "USB install:  JavaLoader.exe load -u dist/JPHMessenger.cod"
