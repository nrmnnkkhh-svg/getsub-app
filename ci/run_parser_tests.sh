#!/bin/bash
# Parser verification suite (runs in CI and in dev sandboxes alike):
# compiles the REAL SubtitleFetcher + tests/TestParsers.java, executes the
# 40-check battery, then golden-diffs formatParagraphs against the original
# getsub CLI Python formatter.
#
# Env:
#   GETSUB_ANDROID_JAR  android.jar used for compilation (default: Termux path)
#   GETSUB_JSON_JAR     real org.json jar for RUNTIME (android.jar has stubs);
#                       default: auto-downloaded from Maven Central
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${GETSUB_ANDROID_JAR:-$HOME/android-sdk/android.jar}"
OUT="$ROOT/build/ci-tests"

rm -rf "$OUT" && mkdir -p "$OUT"

echo "=== compile SubtitleFetcher + TestParsers ==="
javac -nowarn -classpath "$JAR" -d "$OUT" \
  "$ROOT/src/com/getsub/share/SubtitleFetcher.java" \
  "$ROOT/tests/TestParsers.java"

echo "=== generate synthetic caption lines ==="
python3 - "$OUT/lines.txt" <<'PYEOF'
import random, sys
random.seed(42)
words = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa",
         "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon"]
lines = []
for i in range(80):
    n = random.randint(2, 16)
    s = " ".join(random.choice(words) for _ in range(n))
    if i % 9 == 4:
        s = ">> " + s
    if i % 3 == 0:
        s += "."
    elif i % 5 == 1:
        s += "?"
    elif i % 11 == 2:
        s += "!"
    lines.append(s)
with open(sys.argv[1], "w") as f:
    f.write("\n".join(lines) + "\n")
print("wrote", len(lines), "synthetic lines")
PYEOF

JSON_JAR="${GETSUB_JSON_JAR:-}"
if [ -z "$JSON_JAR" ]; then
  JSON_JAR="$OUT/json.jar"
  JSON_URL="https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$JSON_JAR" "$JSON_URL"
  else
    python3 -c "import urllib.request; urllib.request.urlretrieve('$JSON_URL', '$JSON_JAR')"
  fi
fi

echo "=== run check battery ==="
java -cp "$JSON_JAR:$OUT" com.getsub.share.TestParsers "$OUT/lines.txt" "$OUT/java_out.txt"

echo "=== golden: original Python formatter (getsub CLI) ==="
python3 - "$OUT/lines.txt" "$OUT/py_out.txt" <<'PYEOF'
import sys
lines = [l.rstrip("\n") for l in open(sys.argv[1]) if l.strip() != ""]
paragraphs = []
current = []
for line in lines:
    if line.startswith('>>') and current:
        paragraphs.append(' '.join(current))
        current = []
    current.append(line)
    joined = ' '.join(current)
    if len(joined) > 250 and line[-1:] in '.?!' and not line.startswith('>>'):
        paragraphs.append(joined)
        current = []
if current:
    paragraphs.append(' '.join(current))
with open(sys.argv[2], 'w') as f:
    f.write('\n'.join(paragraphs) + '\n')
print("python formatter:", len(paragraphs), "paragraphs")
PYEOF

echo "=== diff Java vs Python ==="
if diff -q "$OUT/java_out.txt" "$OUT/py_out.txt" >/dev/null; then
  echo "GOLDEN DIFF: IDENTICAL ($(wc -l < "$OUT/java_out.txt") paragraphs)"
else
  echo "GOLDEN DIFF: MISMATCH"
  diff "$OUT/java_out.txt" "$OUT/py_out.txt" | head -n 20
  exit 1
fi
echo "=== PARSER SUITE COMPLETE ==="
