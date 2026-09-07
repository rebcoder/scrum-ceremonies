#!/usr/bin/env bash
#
# dev-up.sh — bring the Scrum Ceremonies stack up locally with ONE command.
#
#   Redis (docker)  →  backend :8080  →  static frontend :3000
#
# Then open http://localhost:3000 . The backend never serves the frontend
# (spring.web.resources.add-mappings=false), so this script serves frontend/v1
# on :3000 itself. Create frontend/v1/config.local.js pointing API/WS at
# http://localhost:8080 (see the README).
#
# Ctrl+C tears the app processes down. The Redis container is LEFT running for
# fast re-runs (stop it with: docker compose down).
#
set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"
LOG_DIR="$PROJECT_ROOT/.dev-logs"
mkdir -p "$LOG_DIR"

PIDS=()
port_open() { nc -z localhost "$1" >/dev/null 2>&1; }

cleanup() {
  echo; echo "▼ Stopping the dev stack (backend + frontend)…"
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  for p in 8080 3000; do lsof -ti:"$p" 2>/dev/null | xargs kill 2>/dev/null || true; done
  echo "  done. Docker (redis) left running — 'docker compose down' to stop it."
}
trap cleanup INT TERM EXIT

echo "═══ scrum-ceremonies dev-up ═══  (root: $PROJECT_ROOT)"

# ── 0. free our dev ports if a stale run is holding them ─────────────────────
for p in 8080 3000; do
  if port_open "$p"; then
    echo "  ! :$p already in use — killing the stale holder"
    lsof -ti:"$p" 2>/dev/null | xargs kill 2>/dev/null || true
    sleep 1
  fi
done

# ── 1. redis (docker) — only start it if it isn't already reachable ──────────
echo "▶ Redis…"
if port_open 6379; then
  echo "  ✓ redis already answering on :6379"
else
  if ! command -v docker >/dev/null 2>&1; then
    echo "  ✗ docker not found and nothing on :6379. Install Docker Desktop or start Redis, then re-run." >&2
    exit 1
  fi
  docker compose up -d redis
  for _ in $(seq 1 30); do port_open 6379 && break; sleep 1; done
  if ! port_open 6379; then
    echo "  ✗ redis did not come up on :6379 — check: docker compose logs redis" >&2
    exit 1
  fi
  echo "  ✓ redis up"
fi

# ── 2. backend :8080 ─────────────────────────────────────────────────────────
echo "▶ Backend :8080 (logs: $LOG_DIR/backend.log)…"
( mvn -q spring-boot:run -Dspring-boot.run.profiles=dev ) > "$LOG_DIR/backend.log" 2>&1 &
PIDS+=($!)
for _ in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then break; fi
  sleep 2
done
if ! curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "  ✗ backend never answered /actuator/health. Last log lines:" >&2
  tail -20 "$LOG_DIR/backend.log" >&2
  exit 1
fi
echo "  ✓ backend healthy"

# ── 3. static frontend :3000 ─────────────────────────────────────────────────
echo "▶ Frontend :3000 (serving frontend/v1)…"
if [ ! -f frontend/v1/config.local.js ]; then
  echo "  ! frontend/v1/config.local.js missing — creating a localhost one"
  cat > frontend/v1/config.local.js <<'CFG'
window.APP_CONFIG = {
  API_BASE_URL: 'http://localhost:8080',
  WS_BASE_URL: 'http://localhost:8080/ws'
};
CFG
fi
( cd frontend/v1 && python3 -m http.server 3000 ) > "$LOG_DIR/frontend.log" 2>&1 &
PIDS+=($!)
for _ in $(seq 1 15); do port_open 3000 && break; sleep 1; done
port_open 3000 && echo "  ✓ frontend up" || { echo "  ✗ frontend never bound :3000" >&2; exit 1; }

echo
echo "═══ Ready ═══"
echo "  tools     → http://localhost:3000"
echo "  api       → http://localhost:8080  (health: /actuator/health)"
echo "  api docs  → http://localhost:8080/swagger-ui"
echo
echo "Ctrl+C to stop."
wait
