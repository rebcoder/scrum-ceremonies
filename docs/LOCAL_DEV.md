# Local development runbook

> Companion to [`V1_TOOLS.md`](V1_TOOLS.md) (the deep reference) and
> [`../CONTRIBUTING.md`](../CONTRIBUTING.md) (the rules). This file is for *getting it
> running* and *what to do when it will not*.

The stack is three processes: **Redis** → **Spring Boot backend** → **static frontend**.
The backend never serves the frontend (`spring.web.resources.add-mappings=false`), so
the pages must be served separately.

---

## 1. The one-command path

```bash
./scripts/dev-up.sh
# tools → http://localhost:3000
# api   → http://localhost:8080   (health: /actuator/health, docs: /swagger-ui)
```

It starts Redis via `docker compose` (skipped if something already answers on
:6379), then the backend on :8080, then `python3 -m http.server 3000` over
`frontend/v1`, and writes `frontend/v1/config.local.js` if it is missing.
Ctrl+C stops the app processes; the Redis container is left running for fast
re-runs (`docker compose down` stops it).

> ⚠️ **`dev-up.sh` kills whatever is already holding :8080 and :3000.** That is fine
> when this repo is the only thing running, and destructive when it is not. If you
> have another service on :8080, use the manual path below instead.

---

## 2. The manual path (and the alternate-port recipe)

```bash
# 1 — Redis on :6379.  Either is fine:
docker compose up -d redis        # redis:7-alpine, 256mb, allkeys-lru
brew services start redis         # or a host Redis you already run

# 2 — backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev                       # :8080
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
    -Dspring-boot.run.arguments=--server.port=8081                       # :8081

# 3 — static frontend
npx http-server frontend/v1 -p 3000 -c-1 --silent
# or:  cd frontend/v1 && python3 -m http.server 3000
```

Then point the pages at whichever port the backend took —
`frontend/v1/config.local.js` is gitignored and exists for exactly this:

```js
window.APP_CONFIG = {
  API_BASE_URL: 'http://localhost:8081',
  WS_BASE_URL: 'http://localhost:8081/ws'
};
```

**What a non-8080 port does and does not break:**

| | on a custom port |
|---|---|
| The three tools in a browser | ✅ fine — `config.local.js` drives them |
| CORS | ✅ fine — the allow-list is about the *page* origin (`:3000`), not the API port |
| `scripts/api-smoke-test.sh` | ✅ takes the base URL as `$1` |
| `e2e/*.spec.ts` | ❌ `http://localhost:8080` is hardcoded in the specs |

So: any port is fine for running and driving the app by hand; **Playwright needs the
backend on :8080.**

The `dev` profile only relaxes limits for local work — room creation goes to
50/min · 200/hr · 1000/day and the active-room cap per session to 20. Everything
else matches the default profile.

---

## 3. Health checks, in increasing order of thoroughness

```bash
curl -s http://localhost:8080/actuator/health          # {"status":"UP","groups":[...]}
API_BASE_URL=http://localhost:8080 ./scripts/redis-health-check.sh
bash scripts/api-smoke-test.sh http://localhost:8080   # 16 checks across all three tools
```

A green `api-smoke-test.sh` is `16/16 passed, 0 failed`. It covers create/join/state
for Poker, Retro and Mood, the public-room API, and a 404 on a nonexistent room.

The WebSocket scripts default to a placeholder origin, so they must be pointed at
localhost explicitly — without `API_BASE_URL` they fail with a
connect timeout that looks like a broken backend:

```bash
API_BASE_URL=http://localhost:8080 node scripts/ws-smoke-test.js          # STOMP CONNECT
API_BASE_URL=http://localhost:8080 node scripts/ws-reconnection-test.js   # 7 checks
API_BASE_URL=http://localhost:8080 node scripts/ws-stress-test.js         # 50 concurrent
```

`redis-health-check.sh` reads the `redis` indicator out of `/actuator/health`, and that
indicator is only rendered when health details are shown. The default is
`show-details=when-authorized`, so against a plain local backend the script reports that
details are hidden — a configuration answer, not a Redis answer. To exercise it for real:

```bash
HEALTH_SHOW_DETAILS=always mvn spring-boot:run -Dspring-boot.run.profiles=dev
API_BASE_URL=http://localhost:8080 ./scripts/redis-health-check.sh   # → Redis Health: PASS
```

---

## 4. Driving the three tools by hand

The flows are not symmetrical — knowing this saves a confused debugging session.

**Planning Poker** — `poker.html`: fill `#poker-name-input` → `#create-room-btn`
→ redirects to `?room=CODE`, `#voting-section` appears.

**Sprint Retrospective** — `retro.html`: fill `#retro-name-input` →
`#retro-create-room-btn` → redirects, `#retro-board` appears. Cards go in via
`#wentWell-input` + `.went-well-btn` (and the `to-improve` / `action-items` pairs).

**Team Mood Check** — `mood.html` **has an extra step**: fill `#mood-name-input` →
`#create-room-btn` reveals `#mode-selection-section` and creates **no room yet**.
Clicking `.mode-card[data-mode="quick"]` (or `"scrum"`) is what calls the API and
redirects. A test that waits for a URL change straight after *Create* will time out.

**Joining an existing room** is a form, not a URL. Visiting `?room=CODE` directly
assumes a display name is already in `sessionStorage`. The real teammate flow:
open the tool page, fill the name field *and* `#room-code-input`, click join.

To watch real-time sync, drive two independent browser contexts (not two tabs —
they would share `sessionStorage`) and check that the participant counter reaches
`2/10` on both.

---

## 5. Tests

```bash
mvn test                          # needs Redis on :6379
SKIP_CACHE_TESTS=false mvn test   # + the Testcontainers cache suites (needs Docker)
mvn verify                        # adds the JaCoCo 60%-line-per-package gate
npx playwright test               # needs the backend on :8080 already running
```

About 54 tests skip on a normal local run, and every skip carries a reason in the
XML. The large block (33) is the Testcontainers cache suites, gated on
`SKIP_CACHE_TESTS=false` — unset is the local default and also skips. CI sets it.

Read results from the XML, never from a piped console tail:

```bash
grep -ho '<testcase ' target/surefire-reports/*.xml | wc -l   # what actually ran
grep -ho '<failure'   target/surefire-reports/*.xml | wc -l
grep -ho '<skipped'   target/surefire-reports/*.xml | wc -l
```

---

## 6. Frontend build

```bash
cd frontend && npm ci && node scripts/build.js   # v1/ → dist/ (Terser + CleanCSS)
bash scripts/bundle-size-check.sh
```

`frontend/v1/` is the source of truth. `frontend/dist/` is generated and untracked —
never hand-edit it, and rebuild after touching any `v1/` asset.

---

## 7. Troubleshooting

**`:8080 already in use`, or the app answers but is not this app.**
Find the holder before killing anything:

```bash
lsof -ti:8080 | xargs ps -o pid=,command= -p
```

If it belongs to another project, take the alternate-port recipe in §2 rather than
killing it. `dev-up.sh` and `scripts/reset-local-env.sh` both kill :8080 unconditionally.

**Backend starts, then every write fails with 503.**
The rate limiter itself fails **open** when Redis is unreachable; the 503 comes from
the room write that follows it (see `RateLimitService`). Check Redis first: `redis-cli -h localhost -p 6379 ping` → `PONG`. Note that a Redis
in Docker and a Redis on the host are *different keyspaces*; if you switch between
them, rooms appear to vanish.

**"Room not found" for a room you just made, or 429 on creation.**
Room state carries an 8-hour TTL, and rate-limit plus active-room counters are keyed
by IP — so every local run and every e2e run shares one bucket. Flush and retry:

```bash
redis-cli -h localhost -p 6379 --scan --pattern 'rate:*'          | xargs -r redis-cli DEL
redis-cli -h localhost -p 6379 --scan --pattern 'rooms:session:*' | xargs -r redis-cli DEL
```

`e2e/helpers.ts` exports `flushRateLimits()`, which does exactly this between specs.

**Playwright's API specs fail but the app works in my browser.**
The specs hardcode `http://localhost:8080`; the static server they auto-start is
:3000. Playwright does **not** start the backend — that is intentional, so a missing
backend fails loudly instead of silently skipping.

**`net::ERR_ABORTED` on `POST /api/join-room` in the console.**
Benign. `response.ok` resolves on headers and the handler navigates immediately, so
the body read is cancelled. The join succeeded — check the participant count.

**Wrong JDK, or Docker missing.**
`HarnessEnvironmentTest` fails with an explanatory message; read it.
On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

**A CSS change has no visible effect.**
Check whether JS sets `style.display` inline on that element — an inline style beats
the stylesheet. This is what broke the shared `.team-members-list` widget: the rule
declared `display: flex`, the reveal code set `'block'`, and the flex `gap` silently
did nothing.

**Full reset.** `./scripts/reset-local-env.sh` stops the backend, restarts Redis,
flushes all data, and brings the backend back on the dev profile. It assumes :8080.
