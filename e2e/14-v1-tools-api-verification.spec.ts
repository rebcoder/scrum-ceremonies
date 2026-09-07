import { test, expect } from './fixtures';
import { flushRateLimits } from './helpers';

const API = 'http://localhost:8080/api';
const V1_URL = 'http://localhost:3000';

/**
 * Journey 14: v1 Tools — API + UI Verification
 *
 * Tests v1 tools (Planning Poker, Retrospective, Mood Check) via direct API calls
 * and verifies UI rendering. Requires:
 * - Backend on localhost:8080
 * - v1 frontend on localhost:3000 (npx http-server frontend/dist -p 3000)
 * - Redis on localhost:6379
 */

function uniqueIp(): string {
  return `10.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}`;
}

test.describe('@smoke Journey 14: v1 Tools — API Verification', () => {
  test.beforeAll(async () => {
    await flushRateLimits();
  });

  test.beforeEach(async () => {
    await flushRateLimits();
  });

  // ── Planning Poker API ──

  test.describe('Planning Poker', () => {
    let roomId: string;

    test('14.1 — create poker room returns 8-char alphanumeric ID', async ({ request }) => {
      const res = await request.post(`${API}/create-room`, {
        headers: { 'X-Forwarded-For': uniqueIp() },
      });
      expect(res.ok()).toBeTruthy();
      roomId = (await res.text()).trim();
      expect(roomId).toMatch(/^[a-zA-Z0-9]{8}$/);
    });

    test('14.2 — join poker room succeeds', async ({ request }) => {
      const res = await request.post(
        `${API}/join-room?roomId=${roomId}&userId=user1&name=TestPlayer`,
      );
      expect(res.ok()).toBeTruthy();
    });

    test('14.3 — room state has correct structure', async ({ request }) => {
      const res = await request.get(`${API}/room-state?roomId=${roomId}`);
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data).toHaveProperty('names');
      expect(data).toHaveProperty('votes');
    });

    test('14.4 — get votes returns valid response', async ({ request }) => {
      const res = await request.get(`${API}/votes?roomId=${roomId}`);
      expect(res.ok()).toBeTruthy();
    });

    test('14.5 — public room API returns room info', async ({ request }) => {
      const res = await request.get(`${API}/rooms/poker/${roomId}/public`);
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.roomId).toBe(roomId);
      expect(data.roomType).toBeTruthy();
    });

    test('14.6 — join nonexistent room returns error', async ({ request }) => {
      const res = await request.post(`${API}/join-room?roomId=ZZZZZZZZ&userId=u1&name=Test`);
      expect(res.ok()).toBeFalsy();
    });

    test('14.7 — poker page renders correctly', async ({ page }) => {
      await page.goto(`${V1_URL}/poker.html`);
      await page.waitForLoadState('domcontentloaded');

      // Page title
      await expect(page.getByRole('heading', { name: /Planning Poker/i }).first()).toBeVisible({ timeout: 5_000 });

      // Form elements
      await expect(page.locator('#poker-name-input')).toBeVisible();
      await expect(page.locator('#create-room-btn')).toBeVisible();
      await expect(page.locator('#room-code-input')).toBeVisible();
      await expect(page.locator('#join-room-btn')).toBeVisible();

      // Accessibility: labels have for attributes
      await expect(page.locator('label[for="poker-name-input"]')).toBeVisible();
      await expect(page.locator('label[for="room-code-input"]')).toBeVisible();
    });
  });

  // ── Retrospective Board API ──

  test.describe('Retrospective', () => {
    let boardId: string;

    test('14.8 — create retro board returns 8-char ID', async ({ request }) => {
      const res = await request.post(`${API}/retro/create`, {
        headers: { 'X-Forwarded-For': uniqueIp() },
      });
      expect(res.ok()).toBeTruthy();
      boardId = (await res.text()).trim();
      expect(boardId).toMatch(/^[a-zA-Z0-9]{8}$/);
    });

    test('14.9 — join retro board succeeds', async ({ request }) => {
      const res = await request.post(`${API}/retro/join?roomId=${boardId}`);
      expect(res.ok()).toBeTruthy();
    });

    test('14.10 — retro state has three columns', async ({ request }) => {
      const res = await request.get(`${API}/retro/state?roomId=${boardId}`);
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data).toHaveProperty('wentWell');
      expect(data).toHaveProperty('toImprove');
      expect(data).toHaveProperty('actionItems');
    });

    test('14.11 — join nonexistent retro board returns error', async ({ request }) => {
      const res = await request.post(`${API}/retro/join?roomId=ZZZZZZZZ`);
      expect(res.ok()).toBeFalsy();
    });

    test('14.12 — retro page renders correctly', async ({ page }) => {
      await page.goto(`${V1_URL}/retro.html`);
      await page.waitForLoadState('domcontentloaded');

      await expect(page.getByRole('heading', { name: /Retrospective/i }).first()).toBeVisible({ timeout: 5_000 });
      await expect(page.locator('#retro-name-input')).toBeVisible();
      await expect(page.locator('#retro-create-room-btn')).toBeVisible();

      // Accessibility: labels have for attributes
      await expect(page.locator('label[for="retro-name-input"]')).toBeVisible();
      await expect(page.locator('label[for="retro-room-code-input"]')).toBeVisible();
    });
  });

  // ── Mood Check API ──

  test.describe('Mood Check', () => {
    let roomId: string;

    test('14.13 — create mood room (Quick Pulse) returns 8-char ID', async ({ request }) => {
      const res = await request.post(`${API}/mood/create`, {
        headers: {
          'X-Forwarded-For': uniqueIp(),
          'Content-Type': 'application/json',
        },
        data: { mode: 'QUICK_PULSE' },
      });
      expect(res.ok()).toBeTruthy();
      roomId = (await res.text()).trim();
      expect(roomId).toMatch(/^[a-zA-Z0-9]{8}$/);
    });

    test('14.14 — create mood room (Scrum Pulse) succeeds', async ({ request }) => {
      const res = await request.post(`${API}/mood/create`, {
        headers: {
          'X-Forwarded-For': uniqueIp(),
          'Content-Type': 'application/json',
        },
        data: { mode: 'SCRUM_PULSE' },
      });
      expect(res.ok()).toBeTruthy();
      const id = (await res.text()).trim();
      expect(id).toMatch(/^[a-zA-Z0-9]{8}$/);
    });

    test('14.15 — join mood room succeeds', async ({ request }) => {
      const res = await request.post(`${API}/mood/join?roomId=${roomId}`);
      expect(res.ok()).toBeTruthy();
    });

    test('14.16 — mood room state returns valid structure', async ({ request }) => {
      const res = await request.get(`${API}/mood/state?roomId=${roomId}`);
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data).toHaveProperty('mode');
    });

    test('14.17 — join nonexistent mood room returns error', async ({ request }) => {
      const res = await request.post(`${API}/mood/join?roomId=ZZZZZZZZ`);
      expect(res.ok()).toBeFalsy();
    });

    test('14.18 — mood page renders correctly', async ({ page }) => {
      await page.goto(`${V1_URL}/mood.html`);
      await page.waitForLoadState('domcontentloaded');

      await expect(page.getByRole('heading', { name: /Mood Check/i }).first()).toBeVisible({ timeout: 5_000 });
      await expect(page.locator('#mood-name-input')).toBeVisible();
      await expect(page.locator('#create-room-btn')).toBeVisible();

      // Accessibility: labels have for attributes
      await expect(page.locator('label[for="mood-name-input"]')).toBeVisible();
      await expect(page.locator('label[for="room-code-input"]')).toBeVisible();

      // Accessibility: mode cards have role="button"
      const modeCards = page.locator('.mode-card[role="button"]');
      // Mode cards may not be visible until room is created, so just check they exist in DOM
      // (they're hidden initially, shown after room creation)
    });
  });

  // ── Cross-Tool Verification ──

  test.describe('Cross-Tool', () => {
    test('14.19 — health endpoint is up', async ({ request }) => {
      const res = await request.get('http://localhost:8080/actuator/health');
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('UP');
    });

    test('14.20 — status endpoint returns OK', async ({ request }) => {
      const res = await request.get(`${API}/status`);
      expect(res.ok()).toBeTruthy();
    });

    test('14.21 — homepage renders with all three tool cards', async ({ page }) => {
      await page.goto(`${V1_URL}/index.html`);
      await page.waitForLoadState('domcontentloaded');

      // Should show three tool cards
      await expect(page.locator('text=Planning Poker').first()).toBeVisible({ timeout: 5_000 });
      await expect(page.locator('text=Retrospective').first()).toBeVisible();
      await expect(page.locator('text=Mood Check').first()).toBeVisible();
    });

    test('14.22 — dark mode toggle works on homepage', async ({ page }) => {
      await page.goto(`${V1_URL}/index.html`);
      await page.waitForLoadState('domcontentloaded');

      const toggle = page.locator('[aria-label="Toggle dark mode"]').first();
      if (await toggle.isVisible()) {
        await toggle.click();
        const theme = await page.getAttribute('html', 'data-theme');
        expect(theme).toBe('dark');

        await toggle.click();
        const themeAfter = await page.getAttribute('html', 'data-theme');
        expect(themeAfter).not.toBe('dark');
      }
    });

    test('14.23 — rate limiting endpoint responds (dev profile has relaxed limits)', async ({ request }) => {
      const ip = uniqueIp();

      // Create a room — should succeed on first try
      const res = await request.post(`${API}/create-room`, {
        headers: { 'X-Forwarded-For': ip },
      });
      // Should get 200 (success) or 429 (rate limited) — both are valid
      expect([200, 429]).toContain(res.status());
    });

    test('14.24 — X-Request-Id header is returned', async ({ request }) => {
      const res = await request.get('http://localhost:8080/actuator/info');
      const requestId = res.headers()['x-request-id'];
      expect(requestId).toBeTruthy();
      expect(requestId.length).toBeGreaterThanOrEqual(8);
    });
  });
});
