#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# reset-local-env.sh
# Resets the local environment for manual v1 testing.
# Stops backend, restarts Redis, flushes all data, restarts
# backend with dev profile (relaxed rate limits).
# ─────────────────────────────────────────────────────────────
set -e
cd "$(dirname "$0")/.."

echo "=== Local Test Reset ==="

# 1. Stop backend
echo "[1/5] Stopping backend..."
kill $(lsof -ti:8080) 2>/dev/null && sleep 2 || echo "  (not running)"

# 2. Restart Redis fresh
echo "[2/5] Restarting Redis..."
docker compose restart redis >/dev/null 2>&1
sleep 2

# 3. Flush all Redis data
echo "[3/5] Flushing Redis..."
docker compose exec -T redis redis-cli FLUSHALL >/dev/null
echo "  Redis DBSIZE: $(docker compose exec -T redis redis-cli DBSIZE | awk '{print $2}')"

# 4. Start backend with dev profile (relaxed rate limits)
echo "[4/5] Starting backend (dev profile)..."
nohup mvn spring-boot:run -Dspring-boot.run.profiles=dev -q &>/tmp/backend.log &

# 5. Wait for backend to come up
echo "[5/5] Waiting for backend..."
for _ in $(seq 1 30); do
  sleep 2
  if curl -s http://localhost:8080/actuator/health 2>/dev/null | grep -q UP; then
    echo ""
    echo "=== READY ==="
    echo "Backend:  http://localhost:8080 (dev profile, relaxed rate limits)"
    echo ""
    echo "Start v1 static server if not running:"
    echo "  npx http-server frontend/v1 -p 3000 --cors -c-1"
    echo ""
    echo "Then test at:"
    echo "  Home:   http://localhost:3000/index.html"
    echo "  Poker:  http://localhost:3000/poker.html"
    echo "  Retro:  http://localhost:3000/retro.html"
    echo "  Mood:   http://localhost:3000/mood.html"
    echo ""
    echo "Rate limits (dev): 50/min, 200/hour, 1000/day"
    echo "Room limit: 3 active rooms per IP (30 min expiry)"
    exit 0
  fi
done
echo "ERROR: Backend failed to start. Check /tmp/backend.log"
tail -20 /tmp/backend.log
exit 1
