#!/usr/bin/env bash
# Production smoke test for /frontend/dist. Run after npm run build.
# Fails CI if critical issues found. Lightweight, no browser. Run from repo root or frontend/.
set -e

FRONTEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$FRONTEND_DIR/dist"
SRC="$FRONTEND_DIR/v1"
FAIL=0

echo "=== Production smoke test: $DIST ==="

# --- 1. Verify build exists ---
if [ ! -d "$DIST" ]; then
  echo "FAIL: /frontend/dist does not exist. Run: cd frontend && npm run build"
  exit 1
fi
if [ ! -f "$DIST/index.html" ]; then
  echo "FAIL: /frontend/dist/index.html missing"
  exit 1
fi
echo "OK: Build exists, index.html present"

# --- 2. Scan for critical leaks ---
# Fixed-string checks (use grep exit code only; do not pipe to head)
if grep -rF '${app.version}' "$DIST" --include='*.js' --include='*.html' --include='*.css' 2>/dev/null; then
  echo "FAIL: Critical leak - \${app.version} placeholder"
  FAIL=1
fi
# URL-like localhost/127.0.0.1 only (ignore runtime hostname === 'localhost').
#
# The vendored copy of sockjs-client is excluded because it embeds the literals
# "http://localhost/" and "http://localhost:80" in its own URL-parsing code —
# library internals, not leaked configuration. This check is BLOCKING in CI, and
# without the exclusion it would fail on every build regardless of whether anything
# is actually wrong, which trains people to ignore it (a gate that is permanently
# red is as useless as one that is permanently green). The exclusion is narrow —
# vendored files only — so a real localhost leak in our own HTML or first-party
# JS still fails the check.
if grep -rE 'https?://localhost[^0-9]|wss?://localhost|https?://127\.0\.0\.1|wss?://127\.0\.0\.1' "$DIST" \
     --include='*.js' --include='*.html' \
     --exclude-dir='vendor' \
     --exclude='sockjs*.js' 2>/dev/null; then
  echo "FAIL: Critical leak - localhost/127.0.0.1 URL (use APP_CONFIG)"
  FAIL=1
fi
if grep -rF 'config.local.js' "$DIST" --include='*.js' --include='*.html' 2>/dev/null; then
  echo "FAIL: Critical leak - config.local.js reference"
  FAIL=1
fi
# Any plaintext http:// API origin in a build (localhost excluded above) is a
# downgrade waiting to happen. Host-agnostic since this project ships no domain.
if grep -rEn 'http://api\.[A-Za-z0-9.-]+' "$DIST" --include='*.js' --include='*.html' 2>/dev/null; then
  echo "FAIL: Critical leak - plaintext http:// API origin (must use https)"
  FAIL=1
fi
# Hardcoded relative /api: fetch("/api or fetch('/api (literal quote then /api)
if grep -rE 'fetch\s*\(\s*["\x27]/api|axios\.(get|post)\s*\(\s*["\x27]/api' "$DIST" --include='*.js' 2>/dev/null; then
  echo "FAIL: Critical leak - hardcoded /api call (must use APP_CONFIG.API_BASE_URL)"
  FAIL=1
fi

if [ $FAIL -eq 1 ]; then
  exit 1
fi
echo "OK: No critical leaks (placeholders, localhost, config.local, http api, hardcoded /api)"

# --- 3. Core files exist ---
CORE_FILES="index.html poker.html retro.html mood.html join.html style.css script.js staticwebapp.config.json"
for f in $CORE_FILES; do
  if [ ! -f "$DIST/$f" ]; then
    echo "FAIL: Missing required file: $f"
    exit 1
  fi
done
echo "OK: Core files present"

# --- 4. File size (minification check) ---
MAX_KB=200
for name in script.js retro.js mood.js style.css demo.css; do
  if [ ! -f "$DIST/$name" ]; then continue; fi
  DIST_BYTES=$(wc -c < "$DIST/$name" | tr -d ' ')
  DIST_KB=$((DIST_BYTES / 1024))
  if [ -f "$SRC/$name" ]; then
    SRC_BYTES=$(wc -c < "$SRC/$name" | tr -d ' ')
    if [ "$DIST_BYTES" -gt "$SRC_BYTES" ]; then
      echo "FAIL: $name in dist ($DIST_BYTES) larger than source ($SRC_BYTES) - minification expected"
      FAIL=1
    fi
  fi
  if [ "$DIST_KB" -ge "$MAX_KB" ]; then
    echo "FAIL: $name size $DIST_KB KB exceeds threshold $MAX_KB KB"
    FAIL=1
  fi
done
if [ $FAIL -eq 0 ]; then
  echo "OK: JS/CSS size and minification check passed"
fi

# --- 5. API base usage ---
if ! grep -rq 'APP_CONFIG\.API_BASE_URL\|API_BASE_URL' "$DIST" --include='*.js' 2>/dev/null; then
  echo "FAIL: No APP_CONFIG.API_BASE_URL (or API_BASE_URL) usage in dist JS"
  FAIL=1
fi
if ! grep -rq 'APP_CONFIG\.WS_BASE_URL\|WS_BASE_URL' "$DIST" --include='*.js' 2>/dev/null; then
  echo "FAIL: No APP_CONFIG.WS_BASE_URL (or WS_BASE_URL) usage in dist JS"
  FAIL=1
fi
# Same third-party exclusion as the check above — sockjs-client embeds
# "http://localhost/" in its own code. See that comment for the reasoning.
if grep -rE 'https?://localhost|wss?://localhost' "$DIST" --include='*.js' \
     --exclude-dir='vendor' --exclude='sockjs*.js' 2>/dev/null; then
  echo "FAIL: Hardcoded localhost URL in dist JS (use APP_CONFIG)"
  FAIL=1
fi
if [ $FAIL -eq 0 ]; then
  echo "OK: API/WS config usage validated, no hardcoded localhost URLs"
fi

# --- 6. No dev artifacts ---
if grep -rE 'console\.log\s*\(' "$DIST" --include='*.js' 2>/dev/null; then
  echo "FAIL: console.log( found in dist (strip for production)"
  FAIL=1
fi
# Markers must carry a colon or paren — i.e. the "TODO(TICKET)" / "TODO: ..."
# convention this project uses. A bare word "TODO" is not our convention, and
# matching it unnarrowed is unfixable-by-construction: a vendored third-party
# library can legitimately contain the literal text "TODO" inside a string or
# comment of its own (for example inside a shader source string bundled with a
# graphics library), which no minifier strips because it isn't code. Matching
# only the project's own convention avoids flagging text we don't control.
#
# The pattern is narrowed, never the path: all of dist/ is still scanned, since
# a check that skips the largest artifact it exists to cover isn't really
# checking that artifact at all.
#
# \bDEBUG\b is retained: underscore is a word character, so a build flag such as
# __X_DEBUG__ does not match it.
if grep -rE '\b(TODO|FIXME|HACK|XXX)\b[[:space:]]*[:(]|\bDEBUG\b' "$DIST" --include='*.js' --include='*.html' 2>/dev/null; then
  echo "FAIL: TODO/FIXME/DEBUG found in dist"
  FAIL=1
fi
if [ $FAIL -eq 0 ]; then
  echo "OK: No dev artifacts (console.log, TODO, FIXME, DEBUG)"
fi

# ── No source maps in the deployed bundle ────────────────────────────────────
#
# This build has no source-map step of its own (Terser/CleanCSS emit none for
# this project), so any *.map file here would only arrive if a vendored library
# shipped one alongside its minified build. Checking the deployed tree rather
# than the build config, because the config being right and the artifact being
# right are different claims — and it is the artifact that gets uploaded.
MAPS=$(find "$DIST" -name '*.map' -type f | wc -l | tr -d ' ')
if [ "$MAPS" -ne 0 ]; then
  echo "FAIL: $MAPS source map(s) in $DIST — should never ship"
  find "$DIST" -name '*.map' -type f | head -5
  echo "      Excluded at copy time in frontend/scripts/build.js. If they are back, that"
  echo "      filter was changed or something else is writing into dist/ after it runs."
  FAIL=1
else
  echo "OK: No source maps in dist ($MAPS found)"
fi

if [ $FAIL -eq 1 ]; then
  echo "=== Production smoke test FAILED ==="
  exit 1
fi
echo "=== Production smoke test PASSED ==="
