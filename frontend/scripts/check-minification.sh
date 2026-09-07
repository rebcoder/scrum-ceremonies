#!/usr/bin/env bash
# Manual minification verification. Do NOT run in CI.
# Usage: bash frontend/scripts/check-minification.sh
# Run from repo root or from frontend/.

set -e
FRONTEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$FRONTEND_DIR"

echo "=== Manual minification check (do not use in CI) ==="
echo "Frontend dir: $FRONTEND_DIR"

# 1. Run build (generates dist from src; does not modify src)
npm run build

DIST="$FRONTEND_DIR/dist"
SRC="$FRONTEND_DIR/v1"
FAIL=0

# 2. Dist files smaller than source (for JS/CSS that are minified)
for name in script.js retro.js mood.js style.css demo.css; do
  if [ -f "$SRC/$name" ] && [ -f "$DIST/$name" ]; then
    SZ_SRC=$(wc -c < "$SRC/$name")
    SZ_DIST=$(wc -c < "$DIST/$name")
    if [ "$SZ_DIST" -gt "$SZ_SRC" ]; then
      echo "FAIL: $name dist ($SZ_DIST) larger than source ($SZ_SRC)"
      FAIL=1
    else
      echo "OK: $name source=$SZ_SRC dist=$SZ_DIST"
    fi
  fi
done

# 3. No ${app.version} placeholders in dist
if grep -r '\${app\.version}' "$DIST" 2>/dev/null; then
  echo "FAIL: Found \${app.version} placeholder in dist"
  FAIL=1
else
  echo "OK: No \${app.version} placeholders in dist"
fi

# 4. config.local.js NOT in dist
if [ -f "$DIST/config.local.js" ]; then
  echo "FAIL: config.local.js must not be in dist"
  FAIL=1
else
  echo "OK: config.local.js not in dist"
fi

# 5. No hardcoded localhost URLs in dist (runtime hostname checks like window.location.hostname === 'localhost' are OK)
# Bad: http://localhost:8080, ws://localhost/ws. Good: hostname === 'localhost' (runtime check).
#
# This predicate is the SAME one production-smoke-test.sh uses; the vendored
# sockjs-client bundle embeds "http://localhost/" literals in its own URL-parsing
# code, so it needs its own exclusion or every clean build reports FAIL.
# Keep the two predicates identical: if one changes, change both.
if grep -rE 'https?://localhost[^0-9]|wss?://localhost|https?://127\.0\.0\.1|wss?://127\.0\.0\.1' "$DIST" \
     --include='*.js' --include='*.html' \
     --exclude-dir='vendor' \
     --exclude='sockjs*.js' 2>/dev/null; then
  echo "FAIL: Found hardcoded localhost URL in dist (use config or runtime hostname check instead)"
  FAIL=1
else
  echo "OK: No hardcoded localhost URLs in dist"
fi

if [ $FAIL -eq 1 ]; then
  echo "=== Minification check FAILED ==="
  exit 1
fi
echo "=== Minification check passed ==="
