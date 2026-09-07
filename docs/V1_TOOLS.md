# V1 Free Scrum Tools — Complete Reference

> **Status:** stable. The v1 REST/WS **API contract is frozen** (see
> [`CONTRIBUTING.md`](../CONTRIBUTING.md)); everything else is open to contribution.
> **E2E tests:** the specs live in `e2e/`; `npx playwright test --list` is the authoritative
> inventory.

---

## 1. Overview

V1 tools are **anonymous, real-time collaboration tools** for Scrum teams. No login required — anyone with a room code can join instantly. Data is ephemeral, stored in Redis with an 8-hour TTL.

> **This app makes no outbound calls at all**: no ads, no analytics, no telemetry.

| Tool | Purpose | URL | Users |
|------|---------|-----|-------|
| **Planning Poker** | Estimate backlog items together | `/poker.html` | 1-10 |
| **Sprint Retrospective** | Three-column retro board with voting | `/retro.html` | 1-10 |
| **Team Mood Check** | Anonymous mood pulse survey | `/mood.html` | 1-10 |

**Key principles:**
- Zero friction — no signup, no login, share a link and start
- Real-time — STOMP over SockJS WebSocket, instant sync across all participants
- Anonymous — display names only, no accounts, no persistence beyond 8 hours
- Self-contained — each tool works independently

---

## 2. Architecture

### System Diagram

```
Browser (<tools domain TBD> / localhost:3000)
┌──────────────────────────────────────────────────────────────┐
│  shared.js     — dark mode, clipboard, host polling,        │
│                  team names, focus traps                     │
│  {tool}.js     — room logic, WebSocket, PDF export          │
│  style.css     — design tokens, responsive, dark mode       │
└─────────┬──────────────────────────┬─────────────────────────┘
          │ REST API                 │ WebSocket STOMP
          │ /api/*                   │ /ws → /topic/{tool}.{roomId}
          ▼                          ▼
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot Backend (localhost:8080)                        │
│                                                              │
│  Controllers (REST)          Controllers (WebSocket/STOMP)   │
│  ├─ RoomController           ├─ VoteController               │
│  ├─ RetrospectiveController  ├─ RetrospectiveWsController    │
│  └─ MoodController           └─ MoodWsController             │
│                                                              │
│  Services                    Security                        │
│  ├─ RoomService              ├─ SecurityConfig (CSRF, CORS)  │
│  ├─ RetrospectiveService     ├─ RateLimitService (Lua)       │
│  ├─ MoodService              ├─ RoomLimitService (3/IP)      │
│  └─ RedisRoomPresenceService └─ ClientIpResolver             │
│                                                              │
│  Scheduled Tasks                                             │
│  ├─ RoomCleanupTask (empty rooms, inactive users)            │
│  └─ cleanupInactivePresenceUsers (presence hash: 45s)        │
└─────────┬────────────────────────────────────────────────────┘
          │
          ▼
┌──────────────────────────────────────────────────────────────┐
│  Redis                                                       │
│  room:{id}              — Room object (JSON, 8h TTL)         │
│  retro:{id}             — RetrospectiveBoard (JSON, 8h TTL)  │
│  mood:{id}              — MoodRoom (JSON, 8h TTL)            │
│  room:{id}:presence     — Hash {userId: lastSeenEpochMs}     │
│  rooms:session:{ip}     — Set of room codes per IP           │
│  rate:ip:{ip}:{window}  — Rate limit counters                │
└──────────────────────────────────────────────────────────────┘
```

### Frontend File Structure

```
frontend/v1/                   ← SINGLE SOURCE OF TRUTH
├── shared.js                  — Shared utilities (dark mode, clipboard, host polling, focus traps)
├── script.js                  — Planning Poker logic + WebSocket (~750 lines)
├── retro.js                   — Retrospective logic + WebSocket + PDF export (~1530 lines)
├── mood.js                    — Mood Check logic + WebSocket + PDF export (~1830 lines)
├── share-modal.js             — Share room link modal (~290 lines)
├── cache-bust-utils.js        — Cache busting for deployments (~200 lines)
├── config.js                  — API_BASE_URL + WS_BASE_URL (production)
├── config.local.js            — Localhost overrides (gitignored)
├── style.css                  — Full design system (~2960 lines)
├── index.html                 — Landing page (3 tool cards)
├── poker.html                 — Planning Poker
├── retro.html                 — Sprint Retrospective
├── mood.html                  — Team Mood Check
├── join.html                  — Join room page
├── demo.html / demo.js        — Interactive demo walkthrough
└── about.html, privacy.html, terms.html — Static pages
```

### Design System (style.css)
- **Tokens:** CSS custom properties at `:root` + `[data-theme="dark"]`; page-bg `#EEF3F5`,
  surface `#FFFFFF`, text `#10202B`. `ContrastTokenSweepTest` measures every text/ground pair.
- **Colors:** Primary #0F7C90 (dark theme #1EA5BB), Success #10B981, Warning #F59E0B,
  Danger #EF4444, Info #8b5cf6
- **Display face:** the landing page pairs Instrument Serif (headings) with Inter; the tool
  pages use Inter only. Both are self-hosted under `vendor/fonts/`.
- **Typography:** Inter, 8px spacing system, 12-32px type scale
- **Shadows:** 4 elevation levels (card, card-hover, modal, btn-primary)
- **Breakpoints:** 640px (mobile), 900px (tablet), mobile-first. Interactions are **tap/input**
  (no drag, no sliders) and verified working at 375/390/768 with zero horizontal overflow.
- **Dark mode:** Complete token inversion, persisted in `localStorage('ceremonies_theme')`
- **Animations:** `prefers-reduced-motion` respected
- **Known token debt:** tertiary text `--color-text-tertiary #9B9FB8` ≈ 2.6:1 on white
  (below WCAG AA). 5 gradient end-stops + 3 always-white CTA values intentionally remain
  raw hex (no clean token equivalent).

### Shared Utilities (shared.js)
| Utility | Purpose |
|---|---|
| `initDarkMode()` | Theme toggle with `aria-pressed`, localStorage persistence |
| `showCopySuccess()` / `fallbackCopy()` | Clipboard API with graceful fallback |
| `setupHostNamePolling()` | Polls for host name display, intercepts fetch responses |
| `trapFocus()` | Modal focus trap (Tab/Shift+Tab cycling) |
| `TEAM_NAME_OPTIONS` + helpers | Random team name assignment, localStorage persisted |

### Accessibility
- `aria-live="polite"` on all toast containers
- `aria-pressed` on dark mode toggle and sort button
- `aria-label` on vote/delete buttons; mood survey faces are labeled per option
  (`MOOD_ARIA_LABELS`, verified complete — every face has a name, not just the emoji)
- **Decorative emoji are `aria-hidden`:** emoji used as label/heading/button
  prefixes ("👤 Your Name", "🏠 Room Code") and icon-container glyphs are excluded from the
  accessible name — screen readers read "Your Name", not "house Room Code", while the emoji
  still render for sighted users. Guarded by `e2e/v1-a11y-guard.spec.ts`.
- Focus traps on modals and confirm dialogs
- `role="alert"` on error messages
- `prefers-reduced-motion` disables all animations
- **Deferred (logged):** native `alert()`/`confirm()` are still used instead of styled
  dialogs — functional and accessible, an aesthetic-consistency gap only. Retro empty-state
  icons (JS-rendered 🎉/💡/🚀) are decorative and can be `aria-hidden` when convenient.

---

## 3. Planning Poker

### What It Does
Teams estimate user stories by simultaneously revealing hidden votes. Prevents anchoring bias.

### User Flow
1. Host enters name (max 20 chars) and clicks "Create New Room"
2. Room created via `POST /api/create-room` — returns 8-char alphanumeric code
3. Host shares room code or invite link with team
4. Teammates enter code, click "Join" (`POST /api/join-room`), enter their name
5. All see voting panel with Fibonacci cards: **1, 2, 3, 5, 8, 13, 20, coffee-break**
6. Each person clicks a card to vote (hidden as "?" until reveal)
7. Host clicks "Reveal Votes" — all votes flip simultaneously (animation)
8. Vote summary shown: average, lowest (amber), highest (green), consensus indicator
9. Host clicks "Clear Votes" for next round — all votes reset

### Data Model (Redis)
```
Room {
  roomId: "a1b2c3d4"           // 8-char alphanumeric
  userNames: { "uid1": "Alice", "uid2": "Bob" }
  userVotes: { "uid1": Vote { voteValue: "5", timestamp: ... } }
  revealed: false               // Toggle on reveal
  lastActivityTime: 1710...     // Epoch ms
  hostName: "Alice"             // First user to join
}
```

### API Endpoints
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/api/create-room` | Create poker room, returns 8-char code |
| POST | `/api/join-room?roomId={code}` | Join existing room |
| GET | `/api/room-state?roomId={code}` | Get current room state |
| GET | `/api/votes?roomId={code}` | Get the votes cast so far |
| POST | `/api/leave-room?roomId={code}&userId={id}` | Leave the room (also sent as a `sendBeacon` on unload) |

### WebSocket Protocol
```
Subscribe: /topic/room.{roomId}
Send:      /app/join.{roomId}     { userId, userName }
           /app/vote.{roomId}     { userId, voteValue }
           /app/room.{roomId}.reveal   {}
           /app/room.{roomId}.clear    {}
           /app/room.{roomId}.leave    { userId }
           /app/reconnect.{roomId}     { userId, userName }

Also published: /topic/room.{roomId}.votes  — the vote payload, on its own topic
```

---

## 4. Sprint Retrospective

### What It Does
Three-column retro board (Went Well / To Improve / Action Items) with card voting, search, sort, discussion timer, and PDF export.

### User Flow
1. Host creates board via `POST /api/retro/create` — 8-char code returned
2. Teammates join via code — name input modal appears (backdrop blur, focus trap)
3. Three columns visible: Went Well (green), To Improve (amber), Action Items (blue)
4. Anyone can add cards to any column (type + Enter or click "Add")
5. Anyone can upvote cards (max 5 votes per user across all columns)
6. Host can start a 10-minute discussion timer (synced to all clients)
7. Search filters cards across columns (debounced 300ms, client-side)
8. Sort by votes toggles highest-voted first
9. Export PDF generates a 4-page document (cover + one page per column)

### Card Lifecycle
- **Add:** Type text (max 180 chars, auto-growing textarea) and submit
- **Vote:** Click thumbs-up button; remaining votes badge updates (amber at 1, red at 0)
- **Delete:** Only card author or board host can delete; styled confirm dialog (not `confirm()`)
- **Search/Sort:** Client-side filtering, no API calls

### Data Model (Redis)
```
RetrospectiveBoard {
  roomId: "x1y2z3w4"
  userNames: { "uid1": "Alice", "uid2": "Bob" }
  columns: {
    "wentWell":    [RetroCard, ...],
    "toImprove":   [RetroCard, ...],
    "actionItems": [RetroCard, ...]
  }
  userVoteCounts: { "uid1": 3, "uid2": 5 }  // Max 5 per user
  timerStartedAt: 1710849600000              // 0 = not started
  timerDurationMs: 600000                    // 10 minutes
  hostName: "Alice"
  lastActivityTime: 1710...
}

RetroCard {
  id: "a1b2c3d4"         // 8-char UUID prefix
  text: "..."             // Max 180 chars
  authorId: "uid1"
  authorName: "Alice"
  votes: 3
  createdAt: 1710...
}
```

### API Endpoints
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/api/retro/create` | Create retro board, returns 8-char code |
| POST | `/api/retro/join?roomId={code}` | Join existing board |
| GET | `/api/retro/state?roomId={code}` | Get board state |

### WebSocket Protocol
```
Subscribe: /topic/retro.{roomId}
Send:      /app/retro.join.{roomId}           { userId, userName }
           /app/retro.card.add.{roomId}       { column, text, userId, userName }
           /app/retro.card.upvote.{roomId}    { column, cardId, userId }
           /app/retro.card.delete.{roomId}    { column, cardId, userId }
           /app/retro.timer.start.{roomId}    { userId }
           /app/retro.leave.{roomId}          { userId }
```

### Discussion Timer
- Host-only control; backend validates sender is host
- 10-minute countdown synced across all clients
- Timer state included in ALL WebSocket responses (late joiners see active timer)
- Visual: pulses red under 1 minute, shows "Time's up!" when done

---

## 5. Team Mood Check

### What It Does
Anonymous pulse survey for team sentiment. Two modes: Quick Pulse (single emoji, ~10 seconds) or Scrum Pulse (5-question survey, ~45-60 seconds).

### User Flow
1. Host creates room via `POST /api/mood/create` — chooses mode
2. Teammates join and see survey questions
3. Each person submits responses (one submission per user enforced)
4. Host sees submission count ("3/5 submitted")
5. Host clicks "Reveal Results" — aggregated results shown to all
6. Export PDF saves results as formatted document

### Quick Pulse Mode
Single question: "How do you feel?" with 5 emoji options (sad through excited).

### Scrum Pulse Mode (5 Questions)
1. **Mood:** How are you feeling? (5 emoji options)
2. **Confidence:** How confident about this sprint? (1-10 scale)
3. **Workload:** Current workload? (Under / Just Right / Over)
4. **Blockers:** Currently blocked? (Yes / No)
5. **Comments:** Additional comments (optional free text)

### Privacy Rules
- Comments shown only if 3 or more participants (prevents identification)
- **Confidence average is shown from the FIRST response.** The `>= 3` privacy floor in `MoodService` guards **comments only**; `averageConfidence` is published unconditionally, so in a room with one submitter it is that person's exact answer.
- Responses are not linked to names in the results view

### Data Model (Redis)
```
MoodRoom {
  roomId: "m1n2o3p4"
  mode: "QUICK_PULSE" | "SCRUM_PULSE"
  userNames: { "uid1": "Alice", "uid2": "Bob" }
  responses: {
    "uid1": MoodResponse { mood: 4, confidence: 8, workload: "JUST_RIGHT",
                           blocked: false, comment: "Great sprint!" }
  }
  submittedUsers: { "uid1": true, "uid2": true }
  revealed: false
  sessionEnded: false
  hostName: "Alice"
  lastActivityTime: 1710...
}
```

### API Endpoints
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/api/mood/create` | Create mood room (body: `{mode}`), returns 8-char code |
| POST | `/api/mood/join?roomId={code}` | Join room |
| GET | `/api/mood/state?roomId={code}` | Get room state |

### WebSocket Protocol
```
Subscribe: /topic/mood.{roomId}
Send:      /app/mood.join.{roomId}            { userId, userName }
           /app/mood.response.{roomId}          { userId, ...responses }
           /app/mood.setmode.{roomId}         { userId, mode }
           /app/mood.reveal.{roomId}          { userId }
           /app/mood.endSession.{roomId}      { userId }
           /app/mood.leave.{roomId}           { userId }
```

---

## 6. Security Model

### Rate Limiting (Redis Lua Scripts — Atomic)
```
Per IP address:
├─ Room creation:  5/min,  20/hour,  100/day   (prod)
│                  50/min, 200/hour, 1000/day   (dev profile)
├─ Room join:      NOT ENFORCED  (see note)
└─ Fail-open: If Redis unavailable, the limiter returns true
```

> **Room join is not rate-limited.** `RateLimitService.canJoinRoom`
> exists and reads `app.rate-limit.room-join.per-minute` (default 20), but **nothing in
> `src/main/java` calls it**; the only references are in `RateLimitServiceCacheTest`.
> `RoomController.joinRoom` states the intent in-line: *"Join operations are NOT
> rate-limited per requirements. Only room creation is rate-limited to prevent abuse."*
> **On fail-open:** the limiter fails open — `catch (Exception) { return true; }`.
> `POST /api/create-room` nonetheless returns **503** when Redis is down, because the room
> write that follows throws `RedisConnectionFailureException` and `RoomController` maps it
> to SERVICE_UNAVAILABLE. The 503 is a property of the endpoint, not of the limiter.

Lua script: `INCR key` then `if count==1 then EXPIRE key TTL` then `return count <= limit`.

### Room Capacity — 3-Layer Enforcement
| Layer | Where | How |
|-------|-------|-----|
| 1 | Redis Lua Script (`RedisRoomPresenceService`) | Atomic `HLEN` check before adding user to presence hash |
| 2 | WebSocket Controller | After Lua succeeds, counts Redis users AND room model users; rejects if either >= 10 |
| 3 | REST Controller | Pre-check capacity before REST join completes |

### Session Room Limits
- Max 3 active rooms simultaneously per IP (20 in dev profile)
- Room considered active if `lastActivity` < 30 minutes
- Tracked in Redis set: `rooms:session:{ip}`

### Input Validation
| Field | Rule |
|-------|------|
| Room ID | `^[a-zA-Z0-9]{8}$` (strict regex, all controllers) |
| User name | Max 20 chars, control chars stripped, HTML escaped (`< > " '`) |
| User ID | Max 1000 chars |
| Card text | Max 180 chars (frontend `maxlength` + controller check + model truncation) |

### XSS Prevention
- **Backend:** Model-layer HTML escaping (`< > " '` converted to entities)
- **Frontend:** `textContent` for user data (never `innerHTML` with user input); `retroEscapeHtml()` for template literals

### Data Isolation
- Redis keys scoped per room: `room:{id}`, `retro:{id}`, `mood:{id}`
- WebSocket topics scoped per room: `/topic/room.{id}`, `/topic/retro.{id}`, `/topic/mood.{id}`
- Room ID space: 62^8 = ~218 trillion combinations
- No cross-room data leakage possible

### CSRF
Cookie-based CSRF tokens for browser requests. Disabled for `/ws/**`, `/api/**`, `/actuator/**`.

---

## 7. WebSocket Protocol

### Connection
- **Transport:** STOMP over SockJS
- **Endpoint:** `/ws`
- **Fallback:** XHR streaming, XHR polling (via SockJS)
- **Broker:** SimpleBroker (in-process, single instance only)

### Standard Retro Response Shape
All retro WebSocket responses include:
```json
{
  "state": { "wentWell": [...], "toImprove": [...], "actionItems": [...] },
  "names": { "uid1": "Alice", "uid2": "Bob" },
  "timerStartedAt": 1710849600000,
  "timerDurationMs": 600000,
  "remainingVotes": 3,
  "allowed": true,
  "error": "..."
}
```

Fields like `timerStartedAt`, `remainingVotes`, `allowed`, and `error` are conditionally included depending on the message type.

### Full Topic Reference
See sections 3, 4, and 5 for per-tool subscribe/send topics.

---

## 8. Presence & Cleanup

### Presence Tracking
```
Redis Hash: room:{id}:presence
Key:        userId
Value:      lastSeenEpochMs (updated on activity)
```

Operations (all via `RedisRoomPresenceService`):
- `tryJoinRoom()` — Atomic Lua: check `HLEN < 10`, then `HSET`
- `updateUserActivity()` — `HSET userId` to current time
- `removeUser()` — `HDEL userId`
- `cleanupInactiveUsers()` — Scan hash, remove entries where `now - lastSeen > 60s`
- `getUserCount()` — `HLEN`

### Scheduled Cleanup Tasks (RoomCleanupTask)
| Interval | Task | Threshold |
|----------|------|-----------|
| Every 30s | `cleanupInactiveUsers()` — remove users from room model | `lastActivity > 15 min` |
| Every 45s | `cleanupInactivePresenceUsers()` — remove from presence hash | `lastSeen > 60s` |
| Every 60s | `cleanupEmptyRooms()` / `cleanupEmptyRetroBoards()` — delete empty rooms | `0 users AND lastActivity > 15 min` |

---

## 9. Caching

```
L1 (Caffeine):  Per-instance, 2-min TTL, max 1000 entries per cache
L2 (Redis):     Distributed, 5-min TTL

Cache names: rooms, retroBoards, moodRooms, roomStates

Write operations: @CacheEvict on save/delete
Read operations:  @Cacheable with unless="#result == null"
```

---

## 10. Build & Deployment

### Build Pipeline
```
1. npm run build (frontend/scripts/build.js)
   ├─ Reads source from frontend/v1/
   ├─ Minifies JS via Terser (drops console.log)
   ├─ Minifies CSS via CleanCSS
   ├─ Copies HTML (strips config.local.js line)
   ├─ Writes staticwebapp.config.json (optional; only Azure Static Web Apps reads it)
   └─ Output to frontend/dist/          (build artifact — untracked)

2. Bundle budgets enforced (frontend/scripts/bundle-size-check.sh):
   ├─ Single JS: <= 300KB
   ├─ Total JS:  <= 800KB
   ├─ Single CSS: <= 150KB
   └─ Vendor JS total: <= 900KB
```

### Production Smoke Test
`frontend/scripts/production-smoke-test.sh` verifies:
- No `localhost` URLs in dist files
- No `config.local.js` references
- No unresolved `${app.version}` tokens

### Deployment Topology
```
Production (INTENDED — nothing is deployed yet):
  <tools domain TBD>               ← frontend/dist/  (v1 static only)
  <api origin TBD>                 ← Spring Boot (API only, no static files)
  Redis                            ← room state, presence, rate limits

Local Dev:
  localhost:3000 (http-server)     ← v1 static files from frontend/v1/
  localhost:8080 (Spring Boot)     ← API only (spring.web.resources.add-mappings=false)
  localhost:6379 (Redis)
```

The backend never serves static files. `spring.web.resources.add-mappings=false` is intentional and correct.

### File Size Summary
| Tool | JS Size (unminified) | HTML | WebSocket Topics |
|------|---------------------|------|-----------------|
| Planning Poker | script.js (~28K) | poker.html | `/topic/room.{id}` |
| Retrospective | retro.js (~56K) | retro.html | `/topic/retro.{id}` |
| Mood Check | mood.js (~64K) | mood.html | `/topic/mood.{id}` |
| Homepage | config.js (~4K) | index.html | None |
| Shared | shared.js (~20K), style.css (~68K) | — | — |

**Total:** 12 HTML pages, 3 tool JS files (~148K unminified), 1 CSS file (~68K),
**14 REST endpoints**, **18 `@MessageMapping` destinations**, 3 subscribe topics
(plus `/topic/room.{id}.votes`).

---

## 11. E2E Tests

Playwright specs live in `e2e/` at the repo root. `npx playwright test --list` is the
authoritative inventory. Journeys: 08 (Planning Poker), 09 (Retrospective), 10 (Mood Check),
11 (Homepage & navigation), 14 (API verification), 26 (advanced flows), plus
`v1-a11y-guard`, `v1-join-by-code`, and the opt-in `v1-tools-multiuser-audit`
(`INCLUDE_AUDIT=1`).

### Prerequisites
- Backend on `:8080` (dev profile) and Redis on `:6379`. Playwright starts the static
  server on `:3000` itself.
- If rooms from a previous run linger: `redis-cli -h localhost -p 6379 FLUSHALL`

### Running
```bash
npx playwright test                                    # whole suite
npx playwright test e2e/08-v1-planning-poker.spec.ts   # one journey
npx playwright test --headed                           # watch it
```

---

## 12. Known Limitations

| Limitation | Reason | Impact |
|---|---|---|
| No persistence beyond 8 hours | Rooms are ephemeral by design | Teams must export PDF before TTL expires |
| No login or accounts | Frictionless by design; room code is access control | Names are ephemeral per session |
| Max 10 users per room | Hard limit, enforced at 3 levels | Sufficient for typical Scrum teams |
| SimpleBroker only | Single-instance deployment | Must switch to RabbitMQ relay for horizontal scaling |
| No offline support | Requires active WebSocket connection | |
| No vote history in poker | Each round is independent | Team discusses verbally between rounds |
| No anonymous retro cards | Author shown on every card | Some teams may want hidden authorship |
| No card grouping in retro | Cards are independent | Manual discussion handles grouping |
| No mood trend tracking | Rooms are ephemeral | Export PDF for historical tracking |
| Retro card delete/upvote race | No optimistic locking on board | Cosmetic — card may reappear, refresh fixes |
| PDF export freezes UI | Synchronous jsPDF + html2canvas, no progress bar | Known UX gap |
| Clear votes doesn't deselect button | Client-side selection not reset | Known UX gap |
| `alert()` used for some errors | Should be toast notifications | Known UX gap |
| WebSocket disconnect is silent | No reconnection banner | Known UX gap |

---

## 13. Rules

- **The API contract is frozen** — v1 REST paths, STOMP destinations, the 10-user cap, the
  8-hour TTL and the room-creation rate limits (see `CONTRIBUTING.md`). Change them only with explicit
  agreement.
- **Source of truth:** `frontend/v1/`. `frontend/dist/` is generated and untracked.
- **Backend is API-only** — `spring.web.resources.add-mappings=false` is correct; the backend
  never serves HTML.
- Everything else — features, polish, refactors — is open. Start with `CONTRIBUTING.md`.

---

## 14. Known Code Debt

- Team name array duplicated 3x across tool files
- Copy function defined twice (poker.html + share-modal.js)
- Toast auto-remove race condition in retro.js
