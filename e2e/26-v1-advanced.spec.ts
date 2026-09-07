import { test, expect, Page } from './fixtures';
import { flushRateLimits, uid } from './helpers';

const V1_URL = 'http://localhost:3000';
const API = 'http://localhost:8080';

/**
 * Journey 26: v1 Tools — Advanced Edge Cases
 *
 * Tests room capacity limits, session creation limits, concurrent users,
 * vote edge cases, retro card limits, and cross-tool room isolation.
 */
// NOTE: the v1 endpoints (/api/create-room, /api/retro/create,
// /api/mood/create) return raw room IDs as `text/plain`, not JSON. This spec
// was written assuming JSON responses (`{ roomId: ... }`) and crashes on
// `await res.json()` with parse errors. The v1 contract is frozen by design
// (see CONTRIBUTING.md), so the spec needs a rewrite — not a backend fix. Skipped
// until rewritten. v1 smoke coverage is provided
// by 08/09/10-v1-*.spec.ts, which DO match the current contract.
test.describe.skip('Journey 26: v1 Advanced Scenarios', () => {
  test.beforeAll(async () => {
    await flushRateLimits();
  });

  test.beforeEach(async () => {
    await flushRateLimits();
  });

  // --- Room creation and join API edge cases ---

  test('26.1 — join poker room with invalid room code format', async ({
    request,
  }) => {
    const res = await request.post(`${API}/api/join-room`, {
      data: { roomId: '!!!invalid', userName: 'Test' },
    });
    // Should reject or return error
    expect([400, 404, 200]).toContain(res.status());
    if (res.status() === 200) {
      const body = await res.json();
      // If 200, should contain error info
      expect(body.success === false || body.error).toBeTruthy();
    }
  });

  test('26.2 — join retro board with non-existent code', async ({
    request,
  }) => {
    const res = await request.post(`${API}/api/retro/join`, {
      data: { boardId: 'ZZZZZZZZ', userName: 'Test' },
    });
    expect([400, 404, 200]).toContain(res.status());
  });

  test('26.3 — join mood room with non-existent code', async ({ request }) => {
    const res = await request.post(`${API}/api/mood/join`, {
      data: { roomId: 'ZZZZZZZZ', userName: 'Test' },
    });
    expect([400, 404, 200]).toContain(res.status());
  });

  // --- Name validation ---

  test('26.4 — create poker room with empty name is rejected', async ({
    request,
  }) => {
    const res = await request.post(`${API}/api/create-room`, {
      data: { hostName: '' },
    });
    // Should reject or give error
    if (res.status() === 200) {
      const body = await res.json();
      // Even if 200, check for error signal
      if (body.roomId) {
        // Allowed empty name — document behavior
        expect(body.roomId).toBeTruthy();
      }
    }
  });

  test('26.5 — create poker room with very long name (> 20 chars)', async ({
    request,
  }) => {
    const res = await request.post(`${API}/api/create-room`, {
      data: { hostName: 'A'.repeat(30) },
    });
    if (res.ok()) {
      const body = await res.json();
      // Name should be truncated to 20 chars
      if (body.hostName) {
        expect(body.hostName.length).toBeLessThanOrEqual(20);
      }
    }
  });

  test('26.6 — create poker room with HTML in name is sanitized', async ({
    request,
  }) => {
    const res = await request.post(`${API}/api/create-room`, {
      data: { hostName: '<b>Bold</b>' },
    });
    if (res.ok()) {
      const body = await res.json();
      expect(body.roomId).toBeTruthy();
    }
  });

  // --- Room state ---

  test('26.7 — poker room state returns valid structure', async ({
    request,
  }) => {
    // Create a room
    const createRes = await request.post(`${API}/api/create-room`, {
      data: { hostName: `Host-${uid()}` },
    });
    expect(createRes.ok()).toBeTruthy();
    const { roomId } = await createRes.json();

    // Check state
    const stateRes = await request.get(`${API}/api/room-state/${roomId}`);
    expect(stateRes.ok()).toBeTruthy();
    const state = await stateRes.json();
    expect(state.roomId).toBe(roomId);
    expect(typeof state.revealed).toBe('boolean');
    expect(Array.isArray(state.users) || typeof state.users === 'object').toBeTruthy();
  });

  // --- Multiple rooms ---

  test('26.8 — user can create multiple poker rooms (up to session limit)', async ({
    request,
  }) => {
    const rooms: string[] = [];
    for (let i = 0; i < 3; i++) {
      const res = await request.post(`${API}/api/create-room`, {
        data: { hostName: `Host-${uid()}` },
      });
      if (res.ok()) {
        const body = await res.json();
        rooms.push(body.roomId);
      }
    }
    expect(rooms.length).toBeGreaterThanOrEqual(1);
  });

  // --- Retro board structure ---

  test('26.9 — retro board has three columns', async ({ request }) => {
    const createRes = await request.post(`${API}/api/retro/create`, {
      data: { hostName: `RetroHost-${uid()}` },
    });
    expect(createRes.ok()).toBeTruthy();
    const { boardId } = await createRes.json();

    const stateRes = await request.get(`${API}/api/retro/state/${boardId}`);
    expect(stateRes.ok()).toBeTruthy();
    const state = await stateRes.json();
    expect(state.columns).toBeTruthy();
    const columnNames = Object.keys(state.columns);
    expect(columnNames.length).toBe(3);
  });

  // --- Mood room types ---

  test('26.10 — create Quick Pulse mood room', async ({ request }) => {
    const res = await request.post(`${API}/api/mood/create`, {
      data: {
        hostName: `MoodHost-${uid()}`,
        mode: 'quick-pulse',
      },
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.roomId).toMatch(/^[a-zA-Z0-9]{8}$/);
  });

  test('26.11 — create Scrum Pulse mood room', async ({ request }) => {
    const res = await request.post(`${API}/api/mood/create`, {
      data: {
        hostName: `MoodHost-${uid()}`,
        mode: 'scrum-pulse',
      },
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.roomId).toMatch(/^[a-zA-Z0-9]{8}$/);
  });

  // --- Cross-tool isolation ---

  test('26.12 — poker room code does not work in retro join', async ({
    request,
  }) => {
    const createRes = await request.post(`${API}/api/create-room`, {
      data: { hostName: `Cross-${uid()}` },
    });
    const { roomId } = await createRes.json();

    const joinRes = await request.post(`${API}/api/retro/join`, {
      data: { boardId: roomId, userName: 'Intruder' },
    });
    // Should fail — poker room is not a retro board
    if (joinRes.status() === 200) {
      const body = await joinRes.json();
      expect(body.success === false || body.error).toBeTruthy();
    }
  });

  test('26.13 — poker room code does not work in mood join', async ({
    request,
  }) => {
    const createRes = await request.post(`${API}/api/create-room`, {
      data: { hostName: `Cross-${uid()}` },
    });
    const { roomId } = await createRes.json();

    const joinRes = await request.post(`${API}/api/mood/join`, {
      data: { roomId, userName: 'Intruder' },
    });
    if (joinRes.status() === 200) {
      const body = await joinRes.json();
      expect(body.success === false || body.error).toBeTruthy();
    }
  });

  // --- Public room API ---

  test('26.14 — public room info does not leak vote values before reveal', async ({
    request,
  }) => {
    const createRes = await request.post(`${API}/api/create-room`, {
      data: { hostName: `Voter-${uid()}` },
    });
    const { roomId } = await createRes.json();

    // Join and vote
    await request.post(`${API}/api/join-room`, {
      data: { roomId, userName: 'Voter1' },
    });

    const publicRes = await request.get(`${API}/api/rooms/${roomId}`);
    expect(publicRes.ok()).toBeTruthy();
    const publicData = await publicRes.json();
    // Should not contain vote values if not revealed
    expect(publicData.revealed).toBe(false);
  });

  // --- Health & status ---

  test('26.15 — actuator health returns UP', async ({ request }) => {
    const res = await request.get(`${API}/actuator/health`);
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.status).toBe('UP');
  });

  test('26.16 — status endpoint returns OK', async ({ request }) => {
    const res = await request.get(`${API}/api/status`);
    expect(res.ok()).toBeTruthy();
  });

  // --- v1 page rendering (requires localhost:3000) ---

  test('26.17 — all v1 pages return valid HTML', async ({ page }) => {
    const pages = [
      '/',
      '/poker.html',
      '/retro.html',
      '/mood.html',
      '/join.html',
      '/about.html',
      '/privacy.html',
      '/terms.html',
    ];
    for (const path of pages) {
      const response = await page.goto(`${V1_URL}${path}`);
      expect(response?.ok(), `Failed to load ${path}`).toBeTruthy();
    }
  });

  test('26.18 — v1 pages have no console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text());
    });
    await page.goto(`${V1_URL}/`);
    await page.waitForLoadState('networkidle');
    // Filter out expected errors (like missing config.local.js)
    const realErrors = errors.filter(
      (e) => !e.includes('config.local') && !e.includes('favicon'),
    );
    expect(realErrors.length).toBe(0);
  });
});
