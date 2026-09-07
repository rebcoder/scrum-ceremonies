# Contributing to Scrum Ceremonies

Thanks for taking a look. This is a small project with a deliberately small
surface: three anonymous Scrum ceremonies, no accounts, no database, nothing
persisted beyond eight hours.

## Getting it running

```bash
./scripts/dev-up.sh        # redis → backend :8080 → static frontend :3000
```

You need **JDK 17** (`export JAVA_HOME=$(/usr/libexec/java_home -v 17)` on
macOS), **Node 20+**, and either Docker or a local Redis on :6379.
[`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) is the runbook, including what to do
when a port is taken or Redis is in the wrong place.
[`docs/V1_TOOLS.md`](docs/V1_TOOLS.md) is the deep reference: data models, every
endpoint, every STOMP destination.

There is no hosted instance and no domain. Everything runs on your machine.

## Ground rules

### Frozen surfaces

These are load-bearing for every client that has ever bookmarked a link or
shared a room code. Open an issue before touching any of them.

- **REST paths** (`/api/create-room`, `/api/join-room`, …). Verb-based and legacy
  on purpose. Never rename, never "RESTify".
- **WebSocket topics**: `/topic/room.{roomId}` and siblings.
- **Max 10 users per room**, enforced in two places that must agree: the Redis Lua
  presence script in `RedisRoomPresenceService` (the atomic gate) and
  `Room.canAddUser()` (defence in depth). The UI renders `${userCount}/10` and
  warns at 8 but never refuses. `RoomCapacityConsistencyTest` pins all three.
- **8-hour Redis TTL** for room state.
- **Room-creation rate limits**: 5/min, 20/hr, 100/day per IP, Lua-atomic. They
  fail *open* when the client IP cannot be resolved or Redis is unreachable; the
  503 you see on `/api/create-room` without Redis comes from the room write that
  follows, not from the limiter. Room *join* is not rate-limited: `canJoinRoom`
  exists but has no production caller, and `RoomController.joinRoom` says so.
- **Max 3 active rooms per session** (IP-based). Room IDs match
  `^[a-zA-Z0-9]{8}$`; names are capped at 20 chars, control characters stripped,
  HTML-escaped on render.

### Scope

**No database, no auth, no mail, no AI, no outbound calls.** Redis is the only
dependency. If your change wants one of those, it does not belong here. Adding a
third-party call is a change of character, not a feature; raise it first.

### Conventions

- Any check-then-write against Redis goes through a Lua script. Never split one
  into `GET` + `SET`; see `RateLimitService` and `RedisRoomPresenceService`.
- Keep the `spring.redis.*` property names. `RedisConfig` reads them through
  explicit `@Value` and builds its own connection factory; renaming them to
  Boot's `spring.data.redis.*` silently falls back to `localhost:6379`.
- Constructor injection only. No `@Data` on the mutable state of Redis-serialized
  models. Errors are never swallowed. No `console.log` in shipped JS.
- `frontend/v1/` is the source of truth. Never hand-edit `frontend/dist/`; it is
  regenerated and untracked. `config.js` supplies same-origin defaults and
  `config.local.js` (gitignored) overrides them for localhost.
- Setting `element.style.display` inline overrides the stylesheet. If a rule
  declares `display: flex`, the JS that reveals it must set `'flex'`, not `'block'`.
- **New behaviour ships with tests. A bug fix ships with a regression test** that
  fails before your fix and passes after. Show that it failed.
- No `@Disabled` without a reason in the message. JaCoCo floor: 60% line coverage
  per package, enforced by `mvn verify`.

## Verifying

```bash
mvn verify                              # backend + the 60%-line-per-package gate
SKIP_CACHE_TESTS=false mvn verify       # + Testcontainers cache suites (needs Docker)
cd frontend && node scripts/build.js    # if you touched anything in frontend/v1/
npx playwright test                     # needs the backend already running on :8080
bash scripts/api-smoke-test.sh          # 16 endpoint checks
```

Two habits this project cares about, both learned the hard way:

- **Don't pipe a verification command.** A pipeline exits with the *last*
  command's status, so `mvn test | tail` reports `tail`'s success. Redirect to a
  file and read the file.
- **Claims about what ran come from the XML**, not the console. `tests=`
  undercounts `@Nested`:

  ```bash
  grep -ho '<testcase ' target/surefire-reports/*.xml | wc -l
  grep -ho '<failure'   target/surefire-reports/*.xml | wc -l
  ```

A check that passes without exercising the thing it names is worse than no
check. If you add a gate, break it once and watch it go red before you ship it.

## Gotchas

- **Port 8080 may be taken.** Run the backend elsewhere with
  `-Dspring-boot.run.arguments=--server.port=8081` and point `config.local.js` at
  it. The e2e specs hardcode `:8080`; `api-smoke-test.sh` takes a `BASE_URL`.
- **`scripts/dev-up.sh` kills whatever holds :8080 and :3000.** Check first.
- **Mood Check inserts a mode-selection step.** Fill name → *Create* shows the
  mode cards and creates no room yet; picking Quick Pulse or Scrum Pulse is what
  calls the API.
- **Playwright starts the static frontend for you; it does not start the
  backend.** Bring the backend up first or the API-touching specs fail loudly, by
  design.

## Pull requests

Small and focused beats large and sweeping. Describe what you changed, what you
measured, and anything you deliberately left alone. If a test is skipped, say
why.

Be decent to each other in issues and reviews. That's the whole code of conduct.
