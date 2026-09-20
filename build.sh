#!/bin/bash
# Build the GetSub share-handler APK entirely from Termux
set -e

PROJECT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="$HOME/android-sdk/android.jar"

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
aapt2 link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT/AndroidManifest.xml" \
  --java "$GEN" \
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
dx --dex --output="$BUILD/classes.dex" "$OBJ"

echo "=== Step 5: Package APK ==="
cp "$APK_DIR/app-unaligned.apk" "$APK_DIR/app.apk"
cd "$BUILD" && zip -j "$APK_DIR/app.apk" classes.dex
cd "$PROJECT"

echo "=== Step 6: Sign ==="
if [ ! -f "$HOME/.debug.keystore" ]; then
  echo "ERROR: debug keystore not found at $HOME/.debug.keystore"
  echo "Run the one-time setup step first."
  exit 1
fi

apksigner sign \
  --ks "$HOME/.debug.keystore" \
  --ks-key-alias debug \
  --ks-pass pass:android \
  --key-pass pass:android \
  "$APK_DIR/app.apk"

echo ""
echo "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR/app.apk"
echo ""
echo "Install with:"
echo "  cp $APK_DIR/app.apk ~/storage/downloads/getsub-app.apk && termux-open ~/storage/downloads/getsub-app.apk"
