#!/usr/bin/env bash
# Bundle size budget enforcement for /frontend/dist. Fails CI if limits exceeded.
# Run from repo root: bash frontend/scripts/bundle-size-check.sh

set -e
FRONTEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$FRONTEND_DIR/dist"

MAX_JS_SINGLE_KB=300
MAX_JS_TOTAL_KB=800
MAX_CSS_SINGLE_KB=150
# Ceiling for self-hosted third-party JS. Current usage ~700KB; this catches a
# NEW vendored dependency being added without review, not normal growth.
MAX_VENDOR_JS_KB=900

if [ ! -d "$DIST" ]; then
  echo "FAIL: frontend/dist not found. Run: cd frontend && npm run build"
  exit 1
fi

FAIL=0
echo "=== Bundle size budget check ==="

# Single JS file > 300KB
while IFS= read -r -d '' f; do
  kb=$(( $(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null) / 1024 ))
  if [ "$kb" -gt "$MAX_JS_SINGLE_KB" ]; then
    echo "FAIL: $(basename "$f") ${kb}KB exceeds ${MAX_JS_SINGLE_KB}KB limit"
    FAIL=1
  fi
done < <(find "$DIST" -maxdepth 1 -name "*.js" -print0 2>/dev/null)

# Total JS size > 800KB
total_js=0
for f in "$DIST"/*.js; do
  [ -f "$f" ] || continue
  total_js=$(( total_js + $(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null) ))
done
total_js_kb=$(( total_js / 1024 ))
if [ "$total_js_kb" -gt "$MAX_JS_TOTAL_KB" ]; then
  echo "FAIL: Total JS ${total_js_kb}KB exceeds ${MAX_JS_TOTAL_KB}KB limit"
  FAIL=1
fi

# Any CSS > 150KB
while IFS= read -r -d '' f; do
  kb=$(( $(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null) / 1024 ))
  if [ "$kb" -gt "$MAX_CSS_SINGLE_KB" ]; then
    echo "FAIL: $(basename "$f") ${kb}KB exceeds ${MAX_CSS_SINGLE_KB}KB limit"
    FAIL=1
  fi
done < <(find "$DIST" -maxdepth 1 -name "*.css" -print0 2>/dev/null)

# Vendored third-party JS (frontend/dist/vendor/) is self-hosted rather than
# loaded from a CDN, so it ships as part of this bundle and deserves visibility
# even though it isn't held to the same budget. It is REPORTED here, not
# silently exempt: the limits above use `find -maxdepth 1` and "$DIST"/*.js,
# so vendor/ is invisible to them, and without this block the script would
# print "total JS <= 800KB" while several hundred KB sat unmeasured one
# directory down — a check that doesn't cover what it claims to.
#
# It does not FAIL the build: these bytes were always downloaded by users, just
# from a CDN, and the 300/800KB limits were calibrated for our own code. But the
# number is printed every run so growth is visible rather than discovered later.
vendor_kb=0
if [ -d "$DIST/vendor" ]; then
  while IFS= read -r -d '' f; do
    vendor_kb=$(( vendor_kb + $(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null) / 1024 ))
  done < <(find "$DIST/vendor" -name "*.js" -print0 2>/dev/null)
  vendor_count=$(find "$DIST/vendor" -name "*.js" 2>/dev/null | wc -l | tr -d ' ')
  echo "INFO: vendored third-party JS: ${vendor_kb}KB across ${vendor_count} file(s) (self-hosted, not counted in the limits above)"
  if [ "$vendor_kb" -gt "$MAX_VENDOR_JS_KB" ]; then
    echo "FAIL: vendored JS ${vendor_kb}KB exceeds ${MAX_VENDOR_JS_KB}KB — a new third-party dependency was added; review it."
    FAIL=1
  fi
fi

if [ $FAIL -eq 0 ]; then
  echo "OK: Single JS <= ${MAX_JS_SINGLE_KB}KB, total JS <= ${MAX_JS_TOTAL_KB}KB, single CSS <= ${MAX_CSS_SINGLE_KB}KB"
  echo "Bundle Budget: PASS"
  exit 0
fi
echo "Bundle Budget: FAIL"
exit 1
