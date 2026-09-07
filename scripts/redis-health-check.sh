#!/usr/bin/env bash
# Redis health smoke check: curl actuator/health and ensure redis.status is UP.
# Usage: ./scripts/redis-health-check.sh            (defaults to the local backend)
#        API_BASE_URL=https://api.example.com ./scripts/redis-health-check.sh

set -e
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
HEALTH_URL="${API_BASE_URL%/}/actuator/health"

echo "=== Redis health check ($HEALTH_URL) ==="
resp=$(curl -sS -w "\n%{http_code}" "$HEALTH_URL" 2>/dev/null) || { echo "FAIL: Could not reach $HEALTH_URL"; exit 1; }
# `head -n -1` ("all but the last line") is a GNU extension; BSD/macOS head rejects a negative
# count outright with "illegal line count". That would make this script work in CI (GNU
# coreutils) but break on every macOS developer machine — the wrong way round for a script
# whose whole purpose is to be run by a human checking on something. `sed '$d'` is POSIX and
# does the same job on both.
body=$(echo "$resp" | sed '$d')
code=$(echo "$resp" | tail -n 1)

if [ "$code" != "200" ]; then
  echo "FAIL: Health endpoint returned HTTP $code"
  echo "Redis Health: FAIL"
  exit 1
fi

# Require "redis": { "status": "UP" }
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
printf '%s' "$body" > "$tmp"
# Actuator nests indicators under `components`, never at the top level — reading
# `j.redis` directly is always undefined. For example, against a healthy local
# backend with HEALTH_SHOW_DETAILS=always:
#
#     j.redis            -> undefined
#     j.components.redis -> { status: 'UP', details: { version: '8.2.1' } }
#
# A hidden-details response is also distinguished from a sick Redis: with the default
# `show-details=when-authorized` the body carries no `components` at all, which is a
# configuration answer, not a health answer. Reporting that as "Redis down" would send
# whoever runs this after a deploy hunting the wrong problem.
status=$(node -e "
const d=require('fs').readFileSync('$tmp','utf8');
let j; try { j=JSON.parse(d); } catch(e) { process.exit(2); }
if (!j.components) { console.log('NO_DETAILS'); process.exit(0); }
const r=j.components.redis; if(!r) { console.log('MISSING'); process.exit(0); }
console.log(r.status || 'MISSING');
" 2>/dev/null) || status="PARSE_ERROR"

if [ "$status" = "NO_DETAILS" ]; then
  echo "FAIL: health details are hidden, so the redis indicator is not visible."
  echo "      This is a CONFIG result, not a Redis result — Redis may well be fine."
  echo "      Set HEALTH_SHOW_DETAILS=always on the target, or query it as an authorized principal."
  echo "Redis Health: FAIL"
  exit 1
fi
if [ "$status" = "PARSE_ERROR" ] || [ "$status" = "MISSING" ]; then
  echo "FAIL: no redis indicator under .components (or invalid JSON)"
  echo "Redis Health: FAIL"
  exit 1
fi
if [ "$status" != "UP" ]; then
  echo "FAIL: Redis status is '$status' (expected UP)"
  echo "Redis Health: FAIL"
  exit 1
fi

echo "OK: Redis status is UP"
echo "Redis Health: PASS"
exit 0
