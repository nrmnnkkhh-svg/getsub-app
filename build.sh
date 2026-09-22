#!/bin/bash
# Build the GetSub share-handler APK entirely from Termux
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
# Env overrides let CI (GitHub Actions) reuse this exact script:
#   GETSUB_ANDROID_JAR     path to android.jar (default: Termux setup location)
#   GETSUB_KEYSTORE        signing keystore    (default: ~/.debug.keystore)
#   GETSUB_KEYSTORE_ALIAS  key alias           (default: debug)
#   GETSUB_KEYSTORE_PASS   store+key password  (default: android)
# The d8 fallback in Step 4 keeps non-Termux toolchains working; on Termux
# (where dx exists) behavior is exactly what it always was.
ANDROID_JAR="${GETSUB_ANDROID_JAR:-$HOME/android-sdk/android.jar}"

BUILD="$PROJECT/build"
GEN="$BUILD/gen"
OBJ="$BUILD/obj"
APK_DIR="$BUILD/apk"
COMPILED_RES="$BUILD/compiled_res"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "ERROR: android.jar not found at $ANDROID_JAR"
  echo "Run the one-time setup step first."
  exit 1
fi

echo "=== Cleaning build artifacts ==="
rm -rf "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES" "$BUILD/classes.dex"
mkdir -p "$GEN" "$OBJ" "$APK_DIR" "$COMPILED_RES"

echo "=== Step 1: Compile resources ==="
aapt2 compile --dir "$PROJECT/res" -o "$COMPILED_RES/"

echo "=== Step 2: Link resources + generate R.java ==="
# Optional version stamping (CI sets these; Termux builds leave them empty):
VNAME="${GETSUB_VERSION_NAME:-}"
VCODE="${GETSUB_VERSION_CODE:-}"
VFLAGS=()
[ -n "$VNAME" ] && VFLAGS+=(--version-name "$VNAME")
[ -n "$VCODE" ] && VFLAGS+=(--version-code "$VCODE")
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT/AndroidManifest.xml" \
  --java "$GEN" \
  ${VFLAGS[@]+"${VFLAGS[@]}"} \
  -o "$APK_DIR/app-unaligned.apk" \
  "$COMPILED_RES"/*.flat

echo "=== Step 3: Compile Java ==="
javac \
  -source 1.8 -target 1.8 \
  -classpath "$ANDROID_JAR" \
  -d "$OBJ" \
  "$GEN/com/getsub/share/R.java" \
  "$PROJECT"/src/com/getsub/share/*.java

echo "=== Step 4: Convert to DEX ==="
if command -v dx >/dev/null 2>&1; then
  dx --dex --output="$BUILD/classes.dex" "$OBJ"
else
  # d8 fallback (CI/desktop toolchains): dx is a Termux package, d8 ships
  # with the official Android build-tools. Same DEX output, different
  # launcher. On Termux the dx branch above always wins (dx is installed),
  # so the known d8+OpenJDK21 Termux snag stays avoided.
  ( cd "$OBJ" && jar cf "$BUILD/classes.jar" . )
  d8 --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD" "$BUILD/classes.jar"
fi

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/app-unaligned.apk" "$APK_DIR/app.apk"
cd "$BUILD" && zip -j "$APK_DIR/app.apk" classes.dex
cd "$PROJECT"

echo "=== Step 6: Sign ==="
KS="${GETSUB_KEYSTORE:-$HOME/.debug.keystore}"
KS_ALIAS="${GETSUB_KEYSTORE_ALIAS:-debug}"
KS_PASS="${GETSUB_KEYSTORE_PASS:-android}"
if [ ! -f "$KS" ]; then
  echo "ERROR: keystore not found at $KS"
  echo "Run the one-time setup step first (or set GETSUB_KEYSTORE)."
  exit 1
fi

apksigner sign \
  --ks "$KS" \
  --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  "$APK_DIR/app.apk"

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/app.apk"
echo ""
echo "Install with:"
echo "  cp $APK_DIR/app.apk ~/storage/downloads/getsub-app.apk && termux-open ~/storage/downloads/getsub-app.apk"
