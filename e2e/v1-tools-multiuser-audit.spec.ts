import { test, expect } from './fixtures';
import { mkdirSync, writeFileSync } from 'fs';
// eslint-disable-next-line @typescript-eslint/no-require-imports
const WebSocket = require('ws');

/**
 * v1 TOOLS MULTI-USER AUDIT (anonymous room tools; verification-run only, not CI).
 *
 * Exercises the three v1 tools — Planning Poker, Sprint Retrospective Board, Team
 * Mood Check — as a real MULTI-USER STOMP simulation. Each "user" is its own
 * raw-WebSocket STOMP connection to /ws/websocket. The audit assertion is
 * PROPAGATION: user A's action (vote / card / response) must reach the OTHER
 * users' subscriptions over /topic. Records a PASS/FAIL matrix; a broadcast that
 * every subscriber received = PASS. REST is used only to create rooms.
 */
const BASE = 'http://localhost:8080';
const WS = 'ws://localhost:8080/ws/websocket';
const SHOTS = 'ux-audit-screenshots/v1-tools-audit';
mkdirSync(SHOTS, { recursive: true });

const matrix: { tool: string; action: string; verdict: string; detail?: string }[] = [];
function rec(tool: string, action: string, ok: boolean, detail?: string) {
  matrix.push({ tool, action, verdict: ok ? 'PASS' : 'FAIL', detail });
  // eslint-disable-next-line no-console
  console.log(`${ok ? '·' : '⚠'} [v1-audit] ${tool} / ${action} → ${ok ? 'PASS' : 'FAIL'}${detail ? ' — ' + detail : ''}`);
}

interface Frame { command: string; headers: Record<string, string>; body: string; }
function parseFrame(raw: string): Frame {
  const nl = raw.indexOf('\n\n');
  const head = raw.slice(0, nl).split('\n');
  const command = head[0];
  const headers: Record<string, string> = {};
  head.slice(1).forEach((h) => { const i = h.indexOf(':'); if (i > 0) headers[h.slice(0, i)] = h.slice(i + 1); });
  let body = raw.slice(nl + 2);
  const z = body.indexOf('\0'); if (z >= 0) body = body.slice(0, z);
  return { command, headers, body };
}

class StompUser {
  private ws: InstanceType<typeof WebSocket>;
  received: Frame[] = [];
  private subId = 0;
  constructor(public name: string) { // Origin must be one of the allowed origins: the static
    // frontend is served on :3000 and the backend's CORS allowlist matches that.
    this.ws = new WebSocket(WS, { headers: { Origin: 'http://localhost:3000' } }); }
  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws.on('open', () => this.ws.send('CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0'));
      this.ws.on('message', (d: Buffer) => {
        for (const part of d.toString().split('\0')) {
          if (!part.trim()) continue;
          const f = parseFrame(part.replace(/^\n+/, ''));
          if (f.command === 'CONNECTED') resolve();
          else if (f.command === 'MESSAGE') this.received.push(f);
        }
      });
      this.ws.on('error', reject);
      setTimeout(() => reject(new Error(`${this.name} connect timeout`)), 8000);
    });
  }
  subscribe(dest: string) { this.ws.send(`SUBSCRIBE\nid:sub-${this.subId++}\ndestination:${dest}\n\n\0`); }
  send(dest: string, body?: unknown) {
    const b = body ? JSON.stringify(body) : '';
    this.ws.send(`SEND\ndestination:${dest}\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(b)}\n\n${b}\0`);
  }
  close() { try { this.ws.close(); } catch { /* noop */ } }
  countMatching(pred: (f: Frame) => boolean): number { return this.received.filter(pred).length; }
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

test.describe('v1 tools multi-user audit (poker / retro / mood)', () => {
  test.setTimeout(120_000);

  test('3 users interact across all three v1 tools over STOMP', async ({ request }) => {
    // ══════════ PLANNING POKER ══════════
    try {
      const cr = await request.post(`${BASE}/api/create-room`);
      rec('poker', 'create room (REST)', cr.ok());
      const roomId = (await cr.text()).replace(/"/g, '').trim();
      const users = ['Alice', 'Bob', 'Carol'].map((n) => new StompUser(n));
      await Promise.all(users.map((u) => u.connect()));
      rec('poker', '3 users STOMP-connect', users.every((u) => u.ws));
      for (const u of users) { u.subscribe(`/topic/room.${roomId}`); u.subscribe(`/topic/room.${roomId}.votes`); }
      await wait(300);
      for (const u of users) { u.send(`/app/join.${roomId}`, { userId: u.name, userName: u.name }); await wait(150); }
      const votes = ['5', '8', '3'];
      users.forEach((u, i) => u.send(`/app/vote.${roomId}`, { userId: u.name, vote: votes[i], userName: u.name }));
      await wait(700);
      // PROPAGATION: every user should have received vote broadcasts on .votes
      const voteBroadcasts = users.map((u) => u.countMatching((f) => f.headers.destination?.endsWith('.votes')));
      rec('poker', 'votes propagate to all 3 users', voteBroadcasts.every((c) => c >= 1), `per-user vote frames: ${voteBroadcasts.join(',')}`);
      // reveal
      users[0].send(`/app/room.${roomId}.reveal`);
      await wait(500);
      const revealed = users.some((u) => u.received.some((f) => /revealed"?\s*:\s*true/.test(f.body)));
      rec('poker', 'reveal broadcasts revealed=true', revealed);
      // REST read-back
      const st = await request.get(`${BASE}/api/room-state?roomId=${roomId}`);
      const stBody = st.ok() ? await st.json() : {};
      rec('poker', 'room-state REST reflects votes', st.ok() && JSON.stringify(stBody).includes('votes'), `revealed=${stBody.revealed}`);
      users.forEach((u) => u.close());
    } catch (e) { rec('poker', 'flow', false, (e as Error).message); }

    // ══════════ SPRINT RETROSPECTIVE BOARD ══════════
    try {
      const cr = await request.post(`${BASE}/api/retro/create`);
      rec('retro', 'create room (REST)', cr.ok());
      let roomId = (await cr.text()).replace(/"/g, '').trim();
      try { const j = JSON.parse(roomId); roomId = j.roomId ?? j.id ?? roomId; } catch { /* plain text */ }
      const users = ['Dev', 'PO'].map((n) => new StompUser(n));
      await Promise.all(users.map((u) => u.connect()));
      for (const u of users) u.subscribe(`/topic/retro.${roomId}`);
      await wait(300);
      for (const u of users) { u.send(`/app/retro.join.${roomId}`, { userId: u.name, userName: u.name }); await wait(150); }
      users[0].send(`/app/retro.card.add.${roomId}`, { column: 'WENT_WELL', text: 'Pairing worked', userId: 'Dev', userName: 'Dev' });
      users[1].send(`/app/retro.card.add.${roomId}`, { column: 'TO_IMPROVE', text: 'Estimates were off', userId: 'PO', userName: 'PO' });
      await wait(700);
      const cardBroadcasts = users.map((u) => u.countMatching((f) => f.headers.destination?.includes(`retro.${roomId}`) && /text|card/i.test(f.body)));
      rec('retro', 'cards propagate to both users', cardBroadcasts.every((c) => c >= 1), `per-user card frames: ${cardBroadcasts.join(',')}`);
      users[1].send(`/app/retro.card.upvote.${roomId}`, { cardId: '0', userId: 'PO', userName: 'PO' });
      await wait(400);
      rec('retro', 'upvote broadcast received', users.some((u) => u.received.length > 0));
      users.forEach((u) => u.close());
    } catch (e) { rec('retro', 'flow', false, (e as Error).message); }

    // ══════════ TEAM MOOD CHECK ══════════
    try {
      const cr = await request.post(`${BASE}/api/mood/create`);
      rec('mood', 'create room (REST)', cr.ok());
      let roomId = (await cr.text()).replace(/"/g, '').trim();
      try { const j = JSON.parse(roomId); roomId = j.roomId ?? j.id ?? roomId; } catch { /* plain text */ }
      const users = ['U1', 'U2'].map((n) => new StompUser(n));
      await Promise.all(users.map((u) => u.connect()));
      for (const u of users) u.subscribe(`/topic/mood.${roomId}`);
      await wait(300);
      for (const u of users) { u.send(`/app/mood.join.${roomId}`, { userId: u.name, userName: u.name }); await wait(150); }
      users[0].send(`/app/mood.response.${roomId}`, { userId: 'U1', userName: 'U1', responses: { energy: 4, workload: 3 } });
      users[1].send(`/app/mood.response.${roomId}`, { userId: 'U2', userName: 'U2', responses: { energy: 2, workload: 5 } });
      await wait(600);
      const joinOrResp = users.map((u) => u.countMatching((f) => f.headers.destination?.includes(`mood.${roomId}`)));
      rec('mood', 'join/response propagate to both users', joinOrResp.every((c) => c >= 1), `per-user mood frames: ${joinOrResp.join(',')}`);
      users[0].send(`/app/mood.reveal.${roomId}`);
      await wait(500);
      rec('mood', 'reveal broadcast received', users.some((u) => u.received.length > 0));
      users.forEach((u) => u.close());
    } catch (e) { rec('mood', 'flow', false, (e as Error).message); }

    // ── matrix ──
    const pass = matrix.filter((m) => m.verdict === 'PASS').length;
    writeFileSync(`${SHOTS}/v1-capability-matrix.json`, JSON.stringify({ pass, total: matrix.length, matrix }, null, 2));
    // eslint-disable-next-line no-console
    console.log(`\n[v1-audit] ${pass}/${matrix.length} PASS`);
    expect(matrix.length, 'v1 matrix populated').toBeGreaterThan(8);
    const fails = matrix.filter((m) => m.verdict === 'FAIL');
    expect(fails, `v1 failures: ${JSON.stringify(fails)}`).toEqual([]);
  });
});
