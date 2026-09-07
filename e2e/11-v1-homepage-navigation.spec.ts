import { test, expect } from './fixtures';
import { flushRateLimits, uid } from './helpers';

/**
 * Journey 11: v1 Homepage & Cross-Tool Navigation
 *
 * Tests the landing page, tool card links, join page, static pages,
 * API health check, room creation API, and dark mode persistence.
 *
 * v1 tools run on localhost:3000 (static server)
 * API calls go to localhost:8080
 */

const V1_URL = 'http://localhost:3000';
const API_URL = 'http://localhost:8080';

test.describe('Journey 11: v1 Homepage & Navigation', () => {
  test.beforeAll(async () => {
    await flushRateLimits();
  });

  test.beforeEach(async () => {
    await flushRateLimits();
  });

  test('11.1 — Homepage loads with all three tool cards', async ({ page }) => {
    await page.goto(`${V1_URL}/`);
    // `networkidle` is unreliable here since some pages keep a live WebSocket
    // or poll connection open. `load` is sufficient — the tool cards render
    // synchronously from the HTML, no JS required.
    await page.waitForLoadState('load');

    // Title should contain the product name
    await expect(page).toHaveTitle(/Scrum Ceremonies/i);

    // Should show h3 headings for all 3 tools inside the tool cards
    await expect(
      page.locator('h3.ld-tool-name:has-text("Planning Poker")'),
    ).toBeVisible({ timeout: 5_000 });
    await expect(
      page.locator('h3.ld-tool-name:has-text("Sprint Retrospective")'),
    ).toBeVisible();
    await expect(
      page.locator('h3.ld-tool-name:has-text("Team Mood Check")'),
    ).toBeVisible();
  });

  test('11.2 — Poker link navigates to poker page', async ({ page }) => {
    await page.goto(`${V1_URL}/`);
    await page.waitForLoadState('load');

    // Click the poker tool card link (a.ld-tool-card[href="/poker.html"])
    await page.locator('a.ld-tool-card[href="/poker.html"]').click();
    await page.waitForLoadState('load');

    // URL should contain "poker"
    await expect(page).toHaveURL(/poker/);

    // Poker page heading should be visible
    await expect(
      page.getByRole('heading', { name: /Scrum Planning Poker/i }).first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('11.3 — Retro link navigates to retro page', async ({ page }) => {
    await page.goto(`${V1_URL}/`);
    await page.waitForLoadState('load');

    // Click the retro tool card link
    await page.locator('a.ld-tool-card[href="/retro.html"]').click();
    await page.waitForLoadState('load');

    await expect(page).toHaveURL(/retro/);

    await expect(
      page.getByRole('heading', { name: /Sprint Retrospective/i }).first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('11.4 — Mood link navigates to mood page', async ({ page }) => {
    await page.goto(`${V1_URL}/`);
    await page.waitForLoadState('load');

    // Click the mood tool card link
    await page.locator('a.ld-tool-card[href="/mood.html"]').click();
    await page.waitForLoadState('load');

    await expect(page).toHaveURL(/mood/);

    await expect(
      page.getByRole('heading', { name: /Team Mood Check/i }).first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('11.5 — Join page loads with modal backdrop', async ({ page }) => {
    // join.html requires type and room query params; without them it shows an error modal
    const response = await page.goto(`${V1_URL}/join.html`);
    await page.waitForLoadState('load');

    // Page should have loaded (200 status)
    expect(response).not.toBeNull();
    expect(response!.status()).toBe(200);

    // The join-modal-backdrop should be visible (it's the full-page wrapper)
    await expect(page.locator('#join-modal-backdrop')).toBeVisible({ timeout: 5_000 });

    // Since no valid room params, the error message should be shown
    await expect(page.locator('#error-message')).toBeVisible();
    await expect(page.locator('#error-text')).toContainText(/Invalid room URL|doesn't exist|not found/i);
  });

  test('11.6 — About page loads with heading', async ({ page }) => {
    await page.goto(`${V1_URL}/about.html`);
    await page.waitForLoadState('load');

    await expect(
      page.getByRole('heading', { name: /About Scrum Ceremonies/i }).first(),
    ).toBeVisible({ timeout: 5_000 });

    // Should contain descriptive content about Scrum
    await expect(
      page.getByRole('heading', { name: /What is Scrum Ceremonies/i }).first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('11.7 — Privacy page loads with heading', async ({ page }) => {
    await page.goto(`${V1_URL}/privacy.html`);
    await page.waitForLoadState('load');

    await expect(
      page.getByRole('heading', { name: /Privacy Policy/i }).first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('11.8 — Terms page loads with heading', async ({ page }) => {
    await page.goto(`${V1_URL}/terms.html`);
    await page.waitForLoadState('load');

    await expect(
      page.getByRole('heading', { name: /Terms and Conditions/i }).first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('11.9 — API health check returns UP', async ({ page }) => {
    const response = await page.request.get(`${API_URL}/actuator/health`);
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(body.status).toBe('UP');
  });

  test('11.10 — Room creation API returns 8-char code', async ({ page }) => {
    // POST /api/create-room creates a new poker room
    const response = await page.request.post(`${API_URL}/api/create-room`);

    // 200 = room created, 429 = rate limited, 400 = session room limit
    expect([200, 400, 429]).toContain(response.status());

    if (response.status() === 200) {
      const body = await response.text();
      // Room ID should be 8 alphanumeric characters
      expect(body).toMatch(/^[a-zA-Z0-9]{8}$/);
    }
  });

  test('11.11 — Dark mode persists across pages', async ({ page }) => {
    // Start on the homepage
    await page.goto(`${V1_URL}/`);
    await page.waitForLoadState('load');

    const html = page.locator('html');

    // Ensure we start in light mode
    await page.evaluate(() => {
      localStorage.removeItem('ceremonies_theme');
      document.documentElement.removeAttribute('data-theme');
    });

    // Toggle to dark mode on homepage
    await page.locator('#theme-toggle').click();
    await expect(html).toHaveAttribute('data-theme', 'dark');

    // Verify localStorage was set
    const storedTheme = await page.evaluate(() => localStorage.getItem('ceremonies_theme'));
    expect(storedTheme).toBe('dark');

    // Navigate to poker page
    await page.goto(`${V1_URL}/poker.html`);
    await page.waitForLoadState('load');

    // Dark mode should persist (read from localStorage on page load)
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    // Navigate to retro page
    await page.goto(`${V1_URL}/retro.html`);
    await page.waitForLoadState('load');

    // Dark mode should still persist
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    // Navigate to mood page
    await page.goto(`${V1_URL}/mood.html`);
    await page.waitForLoadState('load');

    // Dark mode should still persist
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

    // Clean up: reset to light mode
    await page.evaluate(() => {
      localStorage.setItem('ceremonies_theme', 'light');
    });
  });
});
