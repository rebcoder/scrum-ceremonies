#!/usr/bin/env bash
# Run Scrum Ceremonies locally to mirror production:
# - Static server serves the built frontend from frontend/dist
# - Backend and Redis run in Docker
# No application logic changes; build, containers, and workflow only.
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

echo "=== Step 1: Build frontend ==="
cd "$ROOT/frontend"
npm ci --no-audit --no-fund
node scripts/build.js
echo "Frontend build done → frontend/dist/"

echo "=== Step 2: Patch dist for local API (config.local.js) ==="
DIST="$ROOT/frontend/dist"
SRC="$ROOT/frontend/v1"
if [ -f "$SRC/config.local.js" ]; then
  cp "$SRC/config.local.js" "$DIST/config.local.js"
  node "$ROOT/scripts/patch-dist-local-config.js" "$DIST"
  echo "Patched dist to load config.local.js on localhost."
else
  echo "Creating config.local.js for local API (http://localhost:8080)..."
  printf '%s\n' \
    'window.APP_CONFIG = window.APP_CONFIG || {};' \
    'window.APP_CONFIG.API_BASE_URL = "http://localhost:8080";' \
    'window.APP_CONFIG.WS_BASE_URL = "http://localhost:8080/ws";' \
    > "$DIST/config.local.js"
  cp "$DIST/config.local.js" "$SRC/config.local.js"
  node "$ROOT/scripts/patch-dist-local-config.js" "$DIST"
  echo "Patched dist to load config.local.js on localhost."
fi

echo "=== Step 3: Start Docker (Redis, Backend) ==="
cd "$ROOT"
docker compose up -d redis app
echo "Waiting for backend to be ready..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health | grep -qE '^200|^503'; then
    echo "Backend responding."
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "Backend did not become ready in time. Check: docker compose logs app"
    exit 1
  fi
  sleep 2
done

echo "=== Step 4: Serve frontend (static server) ==="
echo "Frontend will be at http://localhost:3000"
echo "Backend API at http://localhost:8080"
echo "Press Ctrl+C to stop the static server (Docker keeps running)."
cd "$ROOT"
npx serve frontend/dist -l 3000
