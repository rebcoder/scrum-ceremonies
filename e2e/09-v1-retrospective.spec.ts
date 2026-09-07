import { test, expect, Page } from './fixtures';
import { flushRateLimits, uid } from './helpers';

/**
 * Journey 9: v1 Sprint Retrospective — Full Board Lifecycle
 *
 * Tests the anonymous Retrospective Board tool end-to-end:
 *   Page load → Create board → Add notes (3 columns) → Search → Sort →
 *   Multi-user collaboration → Dark mode
 *
 * v1 tools run on localhost:3000 (static server)
 * API calls go to localhost:8080
 */

const V1_URL = 'http://localhost:3000';

/** Helper: create a retro board and return the room code */
async function createRetroBoard(page: Page, name: string): Promise<string> {
  await page.goto(`${V1_URL}/retro.html`);
  await page.waitForLoadState('networkidle');

  // Fill name and create board
  await page.locator('#retro-name-input').fill(name);
  await page.locator('#retro-create-room-btn').click();

  // The create button triggers an API call then redirects via window.location.href
  // to /retro?room=XXXXXXXX. Wait for the navigation to complete.
  await page.waitForURL(/room=/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  // Wait for the board to become visible (WebSocket connection + DOM render)
  await expect(page.locator('#retro-board')).toBeVisible({ timeout: 10_000 });

  // Wait for room info section (board created)
  await expect(page.locator('#retro-room-info')).toBeVisible({ timeout: 10_000 });

  // Extract the 8-char room code
  const roomCodeText = await page.locator('#retro-room-code').textContent();
  expect(roomCodeText).toBeTruthy();
  const roomCode = roomCodeText!.trim();
  expect(roomCode).toMatch(/^[a-zA-Z0-9]{8}$/);

  return roomCode;
}

test.describe('Journey 9: v1 Sprint Retrospective', () => {
  test.beforeAll(async () => {
    await flushRateLimits();
  });

  test.beforeEach(async () => {
    await flushRateLimits();
  });

  test('9.1 — Page loads with create form visible', async ({ page }) => {
    await page.goto(`${V1_URL}/retro.html`);
    await page.waitForLoadState('networkidle');

    // Room creation section is visible
    await expect(page.locator('#retro-room-section')).toBeVisible({ timeout: 5_000 });

    // Name input is visible
    const nameInput = page.locator('#retro-name-input');
    await expect(nameInput).toBeVisible();
    await expect(nameInput).toHaveAttribute('placeholder', /Enter your name/i);

    // Create button is visible
    const createBtn = page.locator('#retro-create-room-btn');
    await expect(createBtn).toBeVisible();
    await expect(createBtn).toContainText('Create New Board');
  });

  test('9.2 — Create a board and verify room code', async ({ page }) => {
    const roomCode = await createRetroBoard(page, `Retro-${uid()}`);

    // Room code element shows an 8-char alphanumeric code
    await expect(page.locator('#retro-room-code')).toHaveText(/^[a-zA-Z0-9]{8}$/);

    // Room info section is visible
    await expect(page.locator('#retro-room-info')).toBeVisible();

    // Board is visible
    await expect(page.locator('#retro-board')).toBeVisible();

    // Room creation section should be hidden
    await expect(page.locator('#retro-room-section')).toBeHidden();
  });

  test('9.3 — Three columns are visible with correct titles', async ({ page }) => {
    await createRetroBoard(page, `Columns-${uid()}`);

    // Went Well column
    const wentWellCol = page.locator('.went-well-column');
    await expect(wentWellCol).toBeVisible();
    await expect(wentWellCol.locator('.column-title')).toHaveText('Went Well');

    // Improve column (renamed to match Scrum Guide terminology: "To Improve" -> "Improve")
    const toImproveCol = page.locator('.to-improve-column');
    await expect(toImproveCol).toBeVisible();
    await expect(toImproveCol.locator('.column-title')).toHaveText('Improve');

    // Improvement Actions column (was "Action Items")
    const actionItemsCol = page.locator('.action-items-column');
    await expect(actionItemsCol).toBeVisible();
    await expect(actionItemsCol.locator('.column-title')).toHaveText('Improvement Actions');
  });

  test('9.4 — Add note to "Went Well" column', async ({ page }) => {
    await createRetroBoard(page, `WentWell-${uid()}`);

    const noteText = `Great collaboration ${uid()}`;

    // Fill the Went Well input
    await page.locator('#wentWell-input').fill(noteText);

    // Click the Add button in the Went Well column
    await page.locator('.went-well-btn').click();
    await page.waitForTimeout(1_500);

    // Verify the card appears in the wentWell container
    const wentWellContainer = page.locator('#wentWell');
    await expect(wentWellContainer.locator(`text=${noteText}`)).toBeVisible({ timeout: 5_000 });

    // Total cards counter should increment
    await expect(page.locator('#total-cards')).not.toHaveText('0');
  });

  test('9.5 — Add note to "To Improve" column', async ({ page }) => {
    await createRetroBoard(page, `ToImprove-${uid()}`);

    const noteText = `Need better code reviews ${uid()}`;

    // Fill the To Improve input
    await page.locator('#toImprove-input').fill(noteText);

    // Click the Add button in the To Improve column
    await page.locator('.to-improve-btn').click();
    await page.waitForTimeout(1_500);

    // Verify the card appears in the toImprove container
    const toImproveContainer = page.locator('#toImprove');
    await expect(toImproveContainer.locator(`text=${noteText}`)).toBeVisible({ timeout: 5_000 });
  });

  test('9.6 — Add note to "Action Items" column', async ({ page }) => {
    await createRetroBoard(page, `Actions-${uid()}`);

    const noteText = `Set up daily standups ${uid()}`;

    // Fill the Action Items input
    await page.locator('#actionItems-input').fill(noteText);

    // Click the Add button in the Action Items column
    await page.locator('.action-items-btn').click();
    await page.waitForTimeout(1_500);

    // Verify the card appears in the actionItems container
    const actionItemsContainer = page.locator('#actionItems');
    await expect(actionItemsContainer.locator(`text=${noteText}`)).toBeVisible({ timeout: 5_000 });
  });

  test('9.7 — Search filter filters cards', async ({ page }) => {
    await createRetroBoard(page, `Searcher-${uid()}`);

    // Add two notes: one with a unique keyword, one without
    const uniqueKeyword = `unicorn${uid()}`;
    const otherNote = `Regular feedback ${uid()}`;

    // Add first note to Went Well
    await page.locator('#wentWell-input').fill(uniqueKeyword);
    await page.locator('.went-well-btn').click();
    await page.waitForTimeout(1_000);

    // Add second note to To Improve
    await page.locator('#toImprove-input').fill(otherNote);
    await page.locator('.to-improve-btn').click();
    await page.waitForTimeout(2_000);

    // Verify both notes are visible (wait for WebSocket sync)
    await expect(page.locator('#wentWell').getByText(uniqueKeyword)).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#toImprove').getByText(otherNote)).toBeVisible({ timeout: 10_000 });

    // Type the unique keyword in the search field
    await page.locator('#retro-search').fill(uniqueKeyword);
    await page.waitForTimeout(1_000);

    // The matching card should still be visible (scoped to Went Well column)
    await expect(page.locator('#wentWell').getByText(uniqueKeyword)).toBeVisible({ timeout: 5_000 });

    // The non-matching card should be hidden by the search filter.
    // The "To Improve" column should show "No matching notes" instead.
    await expect(page.getByText('No matching notes').first()).toBeVisible({ timeout: 5_000 });

    // Clear search to restore all cards
    await page.locator('#retro-search').fill('');
    await page.waitForTimeout(1_000);
    await expect(page.getByText(otherNote)).toBeVisible({ timeout: 5_000 });
  });

  test('9.8 — Sort by votes toggles sort state', async ({ page }) => {
    await createRetroBoard(page, `Sorter-${uid()}`);

    // Add a couple of notes
    await page.locator('#wentWell-input').fill(`Note A ${uid()}`);
    await page.locator('.went-well-btn').click();
    await page.waitForTimeout(1_000);

    await page.locator('#wentWell-input').fill(`Note B ${uid()}`);
    await page.locator('.went-well-btn').click();
    await page.waitForTimeout(1_000);

    // Click the sort by votes button
    const sortBtn = page.locator('#retro-sort-votes');
    await expect(sortBtn).toBeVisible();

    // Initially aria-pressed should be "false"
    await expect(sortBtn).toHaveAttribute('aria-pressed', 'false');

    // Click to enable sort
    await sortBtn.click();
    await page.waitForTimeout(500);

    // After clicking, aria-pressed should be "true"
    await expect(sortBtn).toHaveAttribute('aria-pressed', 'true');

    // Click again to disable sort
    await sortBtn.click();
    await page.waitForTimeout(500);
    await expect(sortBtn).toHaveAttribute('aria-pressed', 'false');
  });

  test('9.9 — Two users collaborate on retro board', async ({ browser }) => {
    // User 1: create board
    const page1 = await browser.newPage();
    const user1Name = `Alice-${uid()}`;
    const roomCode = await createRetroBoard(page1, user1Name);

    // User 2: join board via URL
    const page2 = await browser.newPage();
    const user2Name = `Bob-${uid()}`;

    // Navigate to the room URL — the name modal will appear
    await page2.goto(`${V1_URL}/retro?room=${roomCode}`);
    await page2.waitForLoadState('networkidle');

    // Fill the name modal if it appears
    const nameModal = page2.locator('#retro-name-modal');
    if (await nameModal.isVisible({ timeout: 5_000 })) {
      await page2.locator('#retro-modal-name-input').fill(user2Name);
      await page2.locator('#retro-modal-continue').click();
    }

    // Wait for the board to appear for User 2
    await expect(page2.locator('#retro-board')).toBeVisible({ timeout: 15_000 });

    // User 1 adds a note to "Went Well"
    const user1Note = `User1 says great work ${uid()}`;
    await page1.locator('#wentWell-input').fill(user1Note);
    await page1.locator('.went-well-btn').click();
    await page1.waitForTimeout(2_000);

    // User 1 should see the note
    await expect(page1.locator('#wentWell').locator(`text=${user1Note}`)).toBeVisible({
      timeout: 5_000,
    });

    // User 2 should also see the note (real-time sync via WebSocket)
    await expect(page2.locator('#wentWell').locator(`text=${user1Note}`)).toBeVisible({
      timeout: 10_000,
    });

    // User 2 adds a note to "To Improve"
    const user2Note = `User2 suggests improvements ${uid()}`;
    await page2.locator('#toImprove-input').fill(user2Note);
    await page2.locator('.to-improve-btn').click();
    await page2.waitForTimeout(2_000);

    // User 2 should see the note
    await expect(page2.locator('#toImprove').locator(`text=${user2Note}`)).toBeVisible({
      timeout: 5_000,
    });

    // User 1 should also see the note via real-time sync
    await expect(page1.locator('#toImprove').locator(`text=${user2Note}`)).toBeVisible({
      timeout: 10_000,
    });

    await page1.close();
    await page2.close();
  });

  test('9.10 — Dark mode toggle sets data-theme', async ({ page }) => {
    await page.goto(`${V1_URL}/retro.html`);
    await page.waitForLoadState('networkidle');

    const html = page.locator('html');

    // Initially not dark
    const initialTheme = await html.getAttribute('data-theme');
    expect(initialTheme).not.toBe('dark');

    // Toggle to dark mode
    await page.locator('#theme-toggle').click();
    await expect(html).toHaveAttribute('data-theme', 'dark');

    // Toggle back to light
    await page.locator('#theme-toggle').click();
    const afterToggle = await html.getAttribute('data-theme');
    expect(afterToggle).not.toBe('dark');
  });
});
