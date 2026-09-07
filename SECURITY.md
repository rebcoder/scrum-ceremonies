# Security Policy

## Reporting a vulnerability

Please report security issues **privately** through GitHub's
[Security Advisories](https://docs.github.com/en/code-security/security-advisories/guiding-contributors-through-security-vulnerabilities/privately-reporting-a-security-vulnerability)
("Report a vulnerability" on the repository's Security tab) rather than opening a
public issue.

This is a small project maintained in spare time. Expect a first response in days,
not hours, and please don't treat that as an invitation to disclose early — say so
if you have a deadline in mind and we'll work to it.

## What is in scope

There is **no hosted service**. Every instance is run by whoever deployed it, so
reports should be about the software, not about someone's deployment:

- Room isolation — one room's state reaching another
- The 10-user room cap or the room-creation rate limits being bypassed
- Anything that makes room content persist past its 8-hour TTL
- XSS through display names, retro cards, or mood responses
- WebSocket authorisation — sending to a room you haven't joined

**Out of scope:** the configuration of a third party's instance, missing security
headers on a deployment you don't control, and reports that a self-hoster has
exposed `/actuator` or Redis to the internet. Those are deployment mistakes, not
software defects — though if you think the defaults invite the mistake, that *is*
worth reporting.

## Things you should know before reporting

Some behaviour looks like a vulnerability but is a documented decision:

- **Rooms are anonymous and unauthenticated by design.** Anyone holding an 8-char
  room code can join. There are no accounts, so there is nothing to authenticate
  against. Room codes are the only secret; treat them as such.
- **Room join is not rate-limited.** Only room creation is (5/min, 20/hr, 100/day
  per IP). `RoomController.joinRoom` says so in-line. If you can show this is
  exploitable in practice, that is a genuine report — the current position is that
  it isn't worth the friction.
- **Rate limiting fails open.** If Redis is unreachable the limiter returns `true`
  rather than blocking legitimate users. Room *creation* still fails with 503,
  because the write behind it fails.
- **Everything expires in 8 hours.** Ephemerality is a feature, not a data-loss bug.

[`CONTRIBUTING.md`](CONTRIBUTING.md) lists the frozen surfaces in full.

## Deploying this safely

If you run an instance, the defaults assume local development:

- Set `APP_CORS_ALLOWED_ORIGINS` to your origin. The default is localhost only.
- Set `METRICS_SCRAPE_TOKEN`, or `/actuator/prometheus` serves nothing — the
  filter treats an empty token as deny, deliberately, so an unconfigured
  deployment does not publish metrics to everyone.
- Keep Redis off the public internet. It holds every room's contents.
- Terminate TLS in front of the app and set `APP_HTTPS_ENABLED=true` so session
  cookies are marked secure.
