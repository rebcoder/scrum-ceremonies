#!/bin/bash
#
# API Smoke Test — verifies all critical endpoints after deployment.
# Usage: ./scripts/api-smoke-test.sh [BASE_URL]
# Default: http://localhost:8080
#
# Covers the anonymous tool endpoints: create/join/state for all three tools.
#

BASE_URL="${1:-http://localhost:8080}"
PASSED=0
FAILED=0
echo "============================================"
echo "API Smoke Test — $(date)"
echo "Target: $BASE_URL"
echo "============================================"
echo ""

check() {
  local name="$1"
  local method="$2"
  local url="$3"
  local expected="$4"
  local data="$5"
  local headers="$6"

  if [ "$method" = "GET" ]; then
    status=$(curl -s -o /dev/null -w "%{http_code}" -H "Origin: $BASE_URL" $headers "$url" 2>/dev/null)
  elif [ "$method" = "POST" ]; then
    if [ -n "$data" ]; then
      status=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -H "Origin: $BASE_URL" $headers -d "$data" "$url" 2>/dev/null)
    else
      status=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Origin: $BASE_URL" $headers "$url" 2>/dev/null)
    fi
  fi

  if [ "$status" = "$expected" ]; then
    echo "  ✓ $name (HTTP $status)"
    ((PASSED++))
  else
    echo "  ✗ $name (expected $expected, got $status)"
    ((FAILED++))
  fi
}

check_body() {
  local name="$1"
  local url="$2"
  local needle="$3"

  body=$(curl -s -H "Origin: $BASE_URL" "$url" 2>/dev/null)
  if echo "$body" | grep -q "$needle"; then
    echo "  ✓ $name (contains '$needle')"
    ((PASSED++))
  else
    echo "  ✗ $name (missing '$needle')"
    ((FAILED++))
  fi
}

# ── Infrastructure ──
echo "Infrastructure"
echo "──────────────"
check "Health endpoint" GET "$BASE_URL/actuator/health" 200
check "Info endpoint" GET "$BASE_URL/actuator/info" 200
check_body "Health status UP" "$BASE_URL/actuator/health" '"status":"UP"'
echo ""

# ── v1 Planning Poker ──
echo "v1 Planning Poker"
echo "─────────────────"
IP="10.$(( RANDOM % 255 )).$(( RANDOM % 255 )).$(( RANDOM % 255 ))"
ROOM_RESPONSE=$(curl -s -X POST -H "X-Forwarded-For: $IP" "$BASE_URL/api/create-room" 2>/dev/null)
ROOM_ID=$(echo "$ROOM_RESPONSE" | tr -d '"' | tr -d ' ')

if [ ${#ROOM_ID} -eq 8 ]; then
  echo "  ✓ Create poker room ($ROOM_ID)"
  ((PASSED++))
  check "Join poker room" POST "$BASE_URL/api/join-room?roomId=$ROOM_ID&userId=smoke-user&name=SmokeTest" 200
  check "Get room state" GET "$BASE_URL/api/room-state?roomId=$ROOM_ID" 200
  check "Get votes" GET "$BASE_URL/api/votes?roomId=$ROOM_ID" 200
  check "Public room API" GET "$BASE_URL/api/rooms/poker/$ROOM_ID/public" 200
else
  echo "  ✗ Create poker room (response: $ROOM_RESPONSE)"
  ((FAILED++))
fi
check "Join nonexistent room" POST "$BASE_URL/api/join-room?roomId=ZZZZZZZZ&userId=u1&name=Test" 404
echo ""

# ── v1 Retrospective ──
echo "v1 Retrospective"
echo "────────────────"
IP="10.$(( RANDOM % 255 )).$(( RANDOM % 255 )).$(( RANDOM % 255 ))"
RETRO_ID=$(curl -s -X POST -H "X-Forwarded-For: $IP" "$BASE_URL/api/retro/create" 2>/dev/null | tr -d '"' | tr -d ' ')

if [ ${#RETRO_ID} -eq 8 ]; then
  echo "  ✓ Create retro board ($RETRO_ID)"
  ((PASSED++))
  check "Join retro board" POST "$BASE_URL/api/retro/join?roomId=$RETRO_ID" 200
  check_body "Retro state has columns" "$BASE_URL/api/retro/state?roomId=$RETRO_ID" '"wentWell"'
else
  echo "  ✗ Create retro board (response: $RETRO_ID)"
  ((FAILED++))
fi
echo ""

# ── v1 Mood Check ──
echo "v1 Mood Check"
echo "─────────────"
IP="10.$(( RANDOM % 255 )).$(( RANDOM % 255 )).$(( RANDOM % 255 ))"
MOOD_ID=$(curl -s -X POST -H "Content-Type: application/json" -H "X-Forwarded-For: $IP" -d '{"mode":"QUICK_PULSE"}' "$BASE_URL/api/mood/create" 2>/dev/null | tr -d '"' | tr -d ' ')

if [ ${#MOOD_ID} -eq 8 ]; then
  echo "  ✓ Create mood room ($MOOD_ID)"
  ((PASSED++))
  check "Join mood room" POST "$BASE_URL/api/mood/join?roomId=$MOOD_ID" 200
  check "Mood room state" GET "$BASE_URL/api/mood/state?roomId=$MOOD_ID" 200
else
  echo "  ✗ Create mood room (response: $MOOD_ID)"
  ((FAILED++))
fi
echo ""

# ── v1 Status ──
echo "v1 Status"
echo "─────────"
check "API status" GET "$BASE_URL/api/status" 200
echo ""

# ── Summary ──
echo "============================================"
TOTAL=$((PASSED + FAILED))
echo "Results: $PASSED/$TOTAL passed, $FAILED failed"
echo "============================================"

if [ "$FAILED" -gt 0 ]; then
  exit 1
fi
