import { test, expect, Page } from './fixtures';
import { flushRateLimits, uid } from './helpers';

/**
 * Journey 8: v1 Planning Poker — Full Room Lifecycle
 *
 * Tests the anonymous Planning Poker tool end-to-end:
 *   Page load → Create room → Vote → Reveal → Clear → Multi-user → Dark mode → Copy code
 *
 * v1 tools run on localhost:3000 (static server)
 * API calls go to localhost:8080
 */

const V1_URL = 'http://localhost:3000';

/** Helper: create a poker room and return the page with the room active */
async function createPokerRoom(page: Page, name: string): Promise<string> {
  await page.goto(`${V1_URL}/poker.html`);
  await page.waitForLoadState('networkidle');

  // Fill name and create room
  await page.locator('#poker-name-input').fill(name);
  await page.locator('#create-room-btn').click();

  // The create button triggers an API call then redirects via window.location.href
  // to /poker?room=XXXXXXXX. Wait for the navigation to complete.
  await page.waitForURL(/room=/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  // Wait for room-info section to become visible (room created successfully)
  await expect(page.locator('#room-info')).toBeVisible({ timeout: 10_000 });

  // Extract the 8-char room code
  const roomCodeText = await page.locator('#room-code').textContent();
  expect(roomCodeText).toBeTruthy();
  const roomCode = roomCodeText!.trim();
  expect(roomCode).toMatch(/^[a-zA-Z0-9]{8}$/);

  return roomCode;
}

test.describe('Journey 8: v1 Planning Poker', () => {
  test.beforeAll(async () => {
    await flushRateLimits();
  });

  test.beforeEach(async () => {
    await flushRateLimits();
  });

  test('8.1 — Page loads with create form visible', async ({ page }) => {
    await page.goto(`${V1_URL}/poker.html`);
    await page.waitForLoadState('networkidle');

    // Room creation section is visible
    await expect(page.locator('#room-section')).toBeVisible({ timeout: 5_000 });

    // Name input is visible and empty
    const nameInput = page.locator('#poker-name-input');
    await expect(nameInput).toBeVisible();
    await expect(nameInput).toHaveAttribute('placeholder', /Enter your name/i);

    // Create button is visible
    const createBtn = page.locator('#create-room-btn');
    await expect(createBtn).toBeVisible();
    await expect(createBtn).toContainText('Create New Room');
  });

  test('8.2 — Create a room and verify room code', async ({ page }) => {
    const roomCode = await createPokerRoom(page, `Poker-${uid()}`);

    // Room code element shows an 8-char alphanumeric code
    await expect(page.locator('#room-code')).toHaveText(/^[a-zA-Z0-9]{8}$/);

    // Room info section is visible with user name and room code
    await expect(page.locator('#room-info')).toBeVisible();
    await expect(page.locator('#user-name')).not.toBeEmpty();

    // Voting section should now be visible
    await expect(page.locator('#voting-section')).toBeVisible();

    // Room creation section should be hidden
    await expect(page.locator('#room-section')).toBeHidden();
  });

  test('8.3 — Vote on a card and verify selection', async ({ page }) => {
    await createPokerRoom(page, `Voter-${uid()}`);

    // Voting section should be visible with vote buttons
    await expect(page.locator('#voting-section')).toBeVisible();
    const voteButtons = page.locator('.vote-btn');
    const count = await voteButtons.count();
    expect(count).toBe(8); // 1, 2, 3, 5, 8, 13, 20, coffee

    // Click the "5" vote button
    const fiveBtn = page.locator('.vote-btn[data-value="5"]');
    await fiveBtn.click();
    await page.waitForTimeout(500);

    // Verify the button gets the "selected" class
    await expect(fiveBtn).toHaveClass(/selected/);
  });

  test('8.4 — Two users join the same room', async ({ browser }) => {
    // User 1: create room
    const page1 = await browser.newPage();
    const userName1 = `Alice-${uid()}`;
    const roomCode = await createPokerRoom(page1, userName1);

    // Verify User 1 sees the voting section
    await expect(page1.locator('#voting-section')).toBeVisible();

    // User 2: join room via URL with ?room= param
    const page2 = await browser.newPage();
    const userName2 = `Bob-${uid()}`;

    // Set the session storage username before navigating to the room URL
    // so poker.js picks it up instead of showing prompt()
    await page2.goto(`${V1_URL}/poker.html`);
    await page2.evaluate((name) => {
      sessionStorage.setItem('scrumPokerUsername', name);
    }, userName2);

    // Navigate to the room — use extensionless URL (same format the app redirects to)
    await page2.goto(`${V1_URL}/poker?room=${roomCode}`);
    await page2.waitForLoadState('networkidle');

    // Wait for User 2 to see the voting section
    await expect(page2.locator('#voting-section')).toBeVisible({ timeout: 10_000 });

    // User 2 should see the room code
    await expect(page2.locator('#room-code')).toHaveText(roomCode);

    // User 2 should see their name
    await expect(page2.locator('#user-name')).toHaveText(userName2);

    // Both users should see vote buttons
    const user1Cards = page1.locator('.vote-btn');
    const user2Cards = page2.locator('.vote-btn');
    expect(await user1Cards.count()).toBe(8);
    expect(await user2Cards.count()).toBe(8);

    // User 1 votes "3"
    await page1.locator('.vote-btn[data-value="3"]').click();
    await page1.waitForTimeout(500);
    await expect(page1.locator('.vote-btn[data-value="3"]')).toHaveClass(/selected/);

    // User 2 votes "8"
    await page2.locator('.vote-btn[data-value="8"]').click();
    await page2.waitForTimeout(500);
    await expect(page2.locator('.vote-btn[data-value="8"]')).toHaveClass(/selected/);

    // Wait for WebSocket to propagate participant updates — use longer timeout
    // Participant count updates asynchronously via WebSocket messages
    await expect(page1.locator('#user-count')).not.toHaveText('0', { timeout: 10_000 });

    await page1.close();
    await page2.close();
  });

  test('8.5 — Reveal votes makes votes visible', async ({ page }) => {
    await createPokerRoom(page, `Revealer-${uid()}`);

    // Vote first
    await page.locator('.vote-btn[data-value="5"]').click();
    await page.waitForTimeout(500);

    // Click reveal votes
    await page.locator('#reveal-votes-btn').click();
    await page.waitForTimeout(1_000);

    // Votes section should become visible
    await expect(page.locator('#votes-section')).toBeVisible({ timeout: 5_000 });

    // The votes list should have at least one item
    const votesList = page.locator('#votes-list');
    await expect(votesList).toBeVisible();
  });

  test('8.6 — Clear votes resets the board', async ({ page }) => {
    await createPokerRoom(page, `Clearer-${uid()}`);

    // Vote
    await page.locator('.vote-btn[data-value="13"]').click();
    await page.waitForTimeout(500);
    await expect(page.locator('.vote-btn[data-value="13"]')).toHaveClass(/selected/);

    // Reveal first so votes are visible
    await page.locator('#reveal-votes-btn').click();
    await page.waitForTimeout(1_000);

    // Clear votes — sends WebSocket message, server broadcasts reset
    await page.locator('#clear-votes-btn').click();
    await page.waitForTimeout(2_000);

    // After clear, the votes-revealed class should be removed (revealed=false in state)
    // and the votes list should be empty
    await expect(page.locator('#votes-section.votes-revealed')).toHaveCount(0, { timeout: 5_000 });
  });

  test('8.7 — Dark mode toggle sets data-theme', async ({ page }) => {
    await page.goto(`${V1_URL}/poker.html`);
    await page.waitForLoadState('networkidle');

    // Initially should be light mode (no data-theme attribute)
    const html = page.locator('html');
    const initialTheme = await html.getAttribute('data-theme');
    // Could be null or "light" initially
    expect(initialTheme).not.toBe('dark');

    // Click the theme toggle
    await page.locator('#theme-toggle').click();

    // Should now have data-theme="dark"
    await expect(html).toHaveAttribute('data-theme', 'dark');

    // Click again to toggle back
    await page.locator('#theme-toggle').click();

    // Should no longer be dark
    const afterToggle = await html.getAttribute('data-theme');
    expect(afterToggle).not.toBe('dark');
  });

  test('8.8 — Copy room code button shows success feedback', async ({ page }) => {
    await createPokerRoom(page, `Copier-${uid()}`);

    // Grant clipboard permissions for the test browser context
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);

    // Click the copy room code button
    await page.locator('#copy-room-code-btn').click();

    // The copy icon should change to a checkmark. Post-reskin (H9) the success
    // feedback is a Lucide 'check' SVG icon (data-ic="check"), not the text "✓".
    const copyIcon = page.locator('#copy-room-code-btn .copy-icon');
    await expect(copyIcon).toHaveAttribute('data-ic', 'check', { timeout: 3_000 });

    // A toast should appear with "copied" text
    const toast = page.locator('#toast-container').locator('.toast, [class*="toast"]').first();
    await expect(toast).toBeVisible({ timeout: 3_000 });
    const toastText = await toast.textContent();
    expect(toastText?.toLowerCase()).toContain('copied');
  });
});
