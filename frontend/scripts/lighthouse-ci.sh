#!/usr/bin/env bash
# Lighthouse CI: serve frontend/dist, audit the shipped pages, fail if scores below threshold.
# Run from repo root. Requires: Node, npx serve, npx lighthouse, Chrome/Chromium.
# Override: LIGHTHOUSE_PERF_MIN=80 LIGHTHOUSE_A11Y_MIN=85 LIGHTHOUSE_BP_MIN=90
#
# ─────────────────────────────────────────────────────────────────────────────
# This repo ships ONE surface — the static pages in frontend/dist. Two lessons are
# deliberately KEPT:
#   - no `serve -s`: SPA mode rewrites unmatched paths to the root index.html
#     with a 200, so a misrouted audit scores the wrong page and reports it as
#     coverage (a hollow gate).
#   - stale-report handling: each audit deletes its report first and fails if it
#     was not regenerated, so a crashed run cannot present the previous result.
# ─────────────────────────────────────────────────────────────────────────────
set -e
FRONTEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$FRONTEND_DIR/dist"
PORT="${LIGHTHOUSE_PORT:-9999}"
PERF_MIN="${LIGHTHOUSE_PERF_MIN:-80}"
A11Y_MIN="${LIGHTHOUSE_A11Y_MIN:-85}"
BP_MIN="${LIGHTHOUSE_BP_MIN:-90}"
REPORT_DIR="${LIGHTHOUSE_REPORT_DIR:-$FRONTEND_DIR}"

if [ ! -d "$DIST" ] || [ ! -f "$DIST/index.html" ]; then
  echo "FAIL: frontend/dist not found or empty. Run: cd frontend && npm run build"
  exit 1
fi

echo "=== Lighthouse CI (Performance >= $PERF_MIN, Accessibility >= $A11Y_MIN, Best Practices >= $BP_MIN) ==="

# The port must be OURS. A previous run can leave an orphaned `serve` listening (the
# trap kills the subshell, not the node process under it) — and then this script's own
# server fails to bind, the wait loop gets a 200 from the STRANGER, and the audit scores
# whatever that process serves while reporting it as this bundle. An orphaned server from
# an earlier run can silently make a broken setup look like it passed, so refuse to guess.
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "FAIL: port $PORT is already in use, so this script cannot know whose server it is auditing."
  echo "      Free it (lsof -nP -iTCP:$PORT -sTCP:LISTEN) or set LIGHTHOUSE_PORT."
  exit 1
fi

# NOT `serve -s` — see the SPA-mode note in the header. This is load-bearing.
cd "$DIST"
npx -y serve -l "$PORT" >/dev/null 2>&1 &
SERVER_PID=$!
# Kill the whole process group: npx spawns node, and killing only $SERVER_PID is what
# orphaned the listener in the first place.
trap 'pkill -P $SERVER_PID 2>/dev/null; kill $SERVER_PID 2>/dev/null; true' EXIT

# npx may need to fetch `serve` on a cold cache, so allow 60s rather than 10.
code="000"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/" 2>/dev/null) || code="000"
  [ "$code" = "200" ] && break
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "FAIL: the static server exited before it began answering. It did not start;"
    echo "      it is not merely slow, and waiting longer will not help."
    exit 1
  fi
  if [ "$i" -eq 60 ]; then
    echo "FAIL: Server did not start on port $PORT (last code: $code)"
    exit 1
  fi
  sleep 1
done

FAIL=0

audit() {
  label="$1"
  path="$2"
  report="$REPORT_DIR/lighthouse-report-$label.json"

  # A previous run's report must not be able to stand in for this one.
  rm -f "$report"

  npx -y lighthouse "http://127.0.0.1:$PORT$path" \
    --output=json --output-path="$report" \
    --chrome-flags="--headless --no-sandbox --disable-gpu --disable-dev-shm-usage" \
    --only-categories=performance,accessibility,best-practices \
    --quiet 2>/dev/null || true

  if [ ! -f "$report" ]; then
    echo "FAIL: [$label] Lighthouse produced no report for $path — the audit DID NOT RUN."
    echo "      Deliberately fatal: treating a missing report as a pass is how a gate"
    echo "      reports coverage it does not have."
    FAIL=1
    return
  fi

  perf=$(node -e "const r=require(process.argv[1]); console.log(Math.round((r.categories?.performance?.score ?? 0)*100));" "$report")
  a11y=$(node -e "const r=require(process.argv[1]); console.log(Math.round((r.categories?.accessibility?.score ?? 0)*100));" "$report")
  bp=$(node -e "const r=require(process.argv[1]); console.log(Math.round((r.categories?.['best-practices']?.score ?? 0)*100));" "$report")

  audited=$(node -e "const r=require(process.argv[1]); console.log(r.finalDisplayedUrl ?? r.finalUrl ?? '');" "$report")
  case "$audited" in
    *"$path"*) ;;
    *) echo "WARN: [$label] report says it audited '$audited', expected a URL containing '$path'" ;;
  esac

  [ "$perf" -lt "$PERF_MIN" ] && { echo "FAIL: [$label] Performance $perf < $PERF_MIN"; FAIL=1; }
  [ "$a11y" -lt "$A11Y_MIN" ] && { echo "FAIL: [$label] Accessibility $a11y < $A11Y_MIN"; FAIL=1; }
  [ "$bp" -lt "$BP_MIN" ] && { echo "FAIL: [$label] Best Practices $bp < $BP_MIN"; FAIL=1; }

  echo "  [$label] $path -> Performance=$perf Accessibility=$a11y Best Practices=$bp"
}

audit "v1-home" "/"
audit "v1-poker" "/poker.html"

if [ "$FAIL" -eq 0 ]; then
  echo "Lighthouse: PASS"
  exit 0
fi
echo "Lighthouse: FAIL"
exit 1
