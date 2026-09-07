import { test, expect, Page } from './fixtures';
import { flushRateLimits, uid } from './helpers';

/**
 * v1 — Join an existing room BY CODE via the tool's own join form.
 *
 * Coverage gap this fills: the existing specs join only via the ?room= URL /
 * sessionStorage (08.4, 09.x), and invalid-join is only tested at the API level
 * (14.6/11/17). The actual user journey — a teammate lands on the tool page,
 * types the shared code + their name, clicks Join, and enters the room — was
 * untested at the UI level, as was the invalid-code error surface.
 *
 * v1 tools on :3000 (static), API on :8080.
 */
const V1_URL = 'http://localhost:3000';

async function createPokerRoom(page: Page, name: string): Promise<string> {
  await page.goto(`${V1_URL}/poker.html`);
  await page.waitForLoadState('networkidle');
  await page.locator('#poker-name-input').fill(name);
  await page.locator('#create-room-btn').click();
  await page.waitForURL(/room=/, { timeout: 15_000 });
  await expect(page.locator('#room-info')).toBeVisible({ timeout: 10_000 });
  return (await page.locator('#room-code').textContent())!.trim();
}

test.describe('v1 — join an existing room by code (UI form)', () => {
  test.beforeEach(async () => {
    await flushRateLimits();
  });

  test('poker — teammate joins by typing the shared code', async ({ browser }) => {
    const host = await browser.newPage();
    const code = await createPokerRoom(host, `Host-${uid()}`);

    const guest = await browser.newPage();
    const guestName = `Guest-${uid()}`;
    await guest.goto(`${V1_URL}/poker.html`);
    await guest.waitForLoadState('networkidle');
    await guest.locator('#poker-name-input').fill(guestName);
    await guest.locator('#room-code-input').fill(code);
    await guest.locator('#join-room-btn').click();

    // Lands in the SAME room via the join form (redirect to /poker?room=CODE)
    await guest.waitForURL(new RegExp(`room=${code}`), { timeout: 15_000 });
    await expect(guest.locator('#voting-section')).toBeVisible({ timeout: 10_000 });
    await expect(guest.locator('#room-code')).toHaveText(code);
    await expect(guest.locator('#user-name')).toHaveText(guestName);

    await host.close();
    await guest.close();
  });

  test('poker — invalid code shows "Room not found" and stays on the form', async ({ page }) => {
    await page.goto(`${V1_URL}/poker.html`);
    await page.waitForLoadState('networkidle');
    await page.locator('#poker-name-input').fill(`NoRoom-${uid()}`);
    await page.locator('#room-code-input').fill('ZZZZZZZZ'); // valid format, nonexistent
    await page.locator('#join-room-btn').click();

    const toast = page.locator('#toast-container .toast, #toast-container [class*="toast"]').first();
    await expect(toast).toBeVisible({ timeout: 5_000 });
    await expect(toast).toContainText(/room not found/i);
    // did NOT navigate / still on the create+join form
    await expect(page.locator('#room-section')).toBeVisible();
    await expect(page).toHaveURL(/poker\.html$/);
  });

  test('poker — join validation: empty code, then empty name', async ({ page }) => {
    await page.goto(`${V1_URL}/poker.html`);
    await page.waitForLoadState('networkidle');

    // empty code → "enter a valid room code"
    await page.locator('#poker-name-input').fill(`Val-${uid()}`);
    await page.locator('#join-room-btn').click();
    let toast = page.locator('#toast-container .toast, #toast-container [class*="toast"]').first();
    await expect(toast).toContainText(/enter a valid room code/i, { timeout: 5_000 });

    // code present but name empty → "enter your name"
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.locator('#room-code-input').fill('ABCD1234');
    await page.locator('#join-room-btn').click();
    toast = page.locator('#toast-container .toast, #toast-container [class*="toast"]').first();
    await expect(toast).toContainText(/enter your name/i, { timeout: 5_000 });
  });

  test('retro — teammate joins a board by code', async ({ browser }) => {
    const host = await browser.newPage();
    await host.goto(`${V1_URL}/retro.html`);
    await host.waitForLoadState('networkidle');
    await host.locator('#retro-name-input').fill(`RHost-${uid()}`);
    await host.locator('#retro-create-room-btn').click();
    await host.waitForURL(/room=/, { timeout: 15_000 });
    await expect(host.locator('#retro-board')).toBeVisible({ timeout: 10_000 });
    const code = (await host.locator('#retro-room-code').textContent())!.trim();

    const guest = await browser.newPage();
    await guest.goto(`${V1_URL}/retro.html`);
    await guest.waitForLoadState('networkidle');
    await guest.locator('#retro-name-input').fill(`RGuest-${uid()}`);
    await guest.locator('#retro-room-code-input').fill(code);
    await guest.locator('#retro-join-room-btn').click();

    await guest.waitForURL(new RegExp(`room=${code}`), { timeout: 15_000 });
    await expect(guest.locator('#retro-board')).toBeVisible({ timeout: 10_000 });
    await expect(guest.locator('#retro-room-code')).toHaveText(code);

    await host.close();
    await guest.close();
  });

  test('retro — invalid board code shows the inline "Board not found" error', async ({ page }) => {
    await page.goto(`${V1_URL}/retro.html`);
    await page.waitForLoadState('networkidle');
    await page.locator('#retro-name-input').fill(`RNoBoard-${uid()}`);
    await page.locator('#retro-room-code-input').fill('ZZZZZZZZ');
    await page.locator('#retro-join-room-btn').click();

    await expect(page.locator('#retro-join-error')).toBeVisible({ timeout: 5_000 });
    await expect(page.locator('#retro-join-error')).toContainText(/not found/i);
    await expect(page.locator('#retro-room-section')).toBeVisible();
  });

  test('mood — teammate joins a room by code', async ({ browser }) => {
    const host = await browser.newPage();
    await host.goto(`${V1_URL}/mood.html`);
    await host.waitForLoadState('networkidle');
    await host.locator('#mood-name-input').fill(`MHost-${uid()}`);
    await host.locator('#create-room-btn').click();
    await expect(host.locator('#mode-selection-section')).toBeVisible({ timeout: 10_000 });
    await host.locator('.mode-card[data-mode="quick"]').click();
    await host.waitForURL(/room=/, { timeout: 15_000 });
    await expect(host.locator('#room-info')).toBeVisible({ timeout: 10_000 });
    const code = (await host.locator('#room-code').textContent())!.trim();

    const guest = await browser.newPage();
    await guest.goto(`${V1_URL}/mood.html`);
    await guest.waitForLoadState('networkidle');
    await guest.locator('#mood-name-input').fill(`MGuest-${uid()}`);
    await guest.locator('#room-code-input').fill(code);
    await guest.locator('#join-room-btn').click();

    await guest.waitForURL(new RegExp(`room=${code}`), { timeout: 15_000 });
    await expect(guest.locator('#room-info')).toBeVisible({ timeout: 10_000 });
    await expect(guest.locator('#room-code')).toHaveText(code);

    await host.close();
    await guest.close();
  });
});
