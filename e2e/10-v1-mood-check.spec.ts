import { test, expect, Page } from './fixtures';
import { flushRateLimits, uid } from './helpers';

/**
 * Journey 10: v1 Team Mood Check — Full Room Lifecycle
 *
 * Tests the anonymous Mood Check tool end-to-end:
 *   Page load → Create room → Mode selection → Quick Pulse survey →
 *   Submit mood → Host reveal → Dark mode
 *
 * v1 tools run on localhost:3000 (static server)
 * API calls go to localhost:8080
 */

const V1_URL = 'http://localhost:3000';

/** Helper: create a mood room and show mode selection.
 *  Note: mood tool shows mode selection AFTER clicking create. The flow is:
 *    1. Fill name → click Create → shows mode selection (no room yet)
 *    2. Click mode card → creates room via API → redirects to ?room=XXX
 *  So after this helper, the page is at the mode-selection stage.
 */
async function createMoodRoom(page: Page, name: string): Promise<void> {
  await page.goto(`${V1_URL}/mood.html`);
  await page.waitForLoadState('networkidle');

  // Fill name
  await page.locator('#mood-name-input').fill(name);

  // Click create room — this hides the form and shows mode selection (no API call yet)
  await page.locator('#create-room-btn').click();

  // Wait for mode selection to appear (shown synchronously by JS, no API call)
  await expect(page.locator('#mode-selection-section')).toBeVisible({ timeout: 10_000 });
}

/**
 * Helper: create mood room and select Quick Pulse mode.
 * After mode selection the page redirects to ?room=XXX and shows the survey.
 */
async function createMoodRoomWithQuickPulse(page: Page, name: string): Promise<void> {
  await createMoodRoom(page, name);

  // Click the Quick Pulse card (data-mode="quick")
  // This triggers a room creation API call, then redirects to ?room=XXX
  await page.locator('.mode-card[data-mode="quick"]').click();

  // Wait for the redirect to complete
  await page.waitForURL(/room=/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  // After redirect, the room info should appear immediately
  await expect(page.locator('#room-info')).toBeVisible({ timeout: 10_000 });

  // Survey section appears after WebSocket connects and room state is received
  await expect(page.locator('#survey-section')).toBeVisible({ timeout: 10_000 });
}

test.describe('Journey 10: v1 Team Mood Check', () => {
  test.beforeAll(async () => {
    await flushRateLimits();
  });

  test.beforeEach(async () => {
    await flushRateLimits();
  });

  test('10.1 — Page loads with create form visible', async ({ page }) => {
    await page.goto(`${V1_URL}/mood.html`);
    await page.waitForLoadState('networkidle');

    // Room creation section is visible
    await expect(page.locator('#room-section')).toBeVisible({ timeout: 5_000 });

    // Name input is visible
    const nameInput = page.locator('#mood-name-input');
    await expect(nameInput).toBeVisible();
    await expect(nameInput).toHaveAttribute('placeholder', /Enter your name/i);

    // Create button is visible
    const createBtn = page.locator('#create-room-btn');
    await expect(createBtn).toBeVisible();
    await expect(createBtn).toContainText('Create New Room');
  });

  test('10.2 — Create room shows room info', async ({ page }) => {
    const name = `Mood-${uid()}`;
    await createMoodRoomWithQuickPulse(page, name);

    // Room info should be visible with a room code
    await expect(page.locator('#room-info')).toBeVisible();
    const roomCodeText = await page.locator('#room-code').textContent();
    expect(roomCodeText).toBeTruthy();
    expect(roomCodeText!.trim()).toMatch(/^[a-zA-Z0-9]{8}$/);

    // User name should be displayed
    await expect(page.locator('#user-name')).toHaveText(name);
  });

  test('10.3 — Mode selection shows Quick Pulse and Scrum Pulse cards', async ({ page }) => {
    await createMoodRoom(page, `Modes-${uid()}`);

    // Mode selection section should be visible
    const modeSection = page.locator('#mode-selection-section');
    await expect(modeSection).toBeVisible({ timeout: 5_000 });

    // Quick Pulse card
    const quickPulseCard = page.locator('.mode-card[data-mode="quick"]');
    await expect(quickPulseCard).toBeVisible();
    await expect(quickPulseCard.locator('.mode-title')).toHaveText('Quick Pulse');

    // Scrum Pulse card
    const scrumPulseCard = page.locator('.mode-card[data-mode="scrum"]');
    await expect(scrumPulseCard).toBeVisible();
    await expect(scrumPulseCard.locator('.mode-title')).toHaveText('Scrum Pulse');
  });

  test('10.4 — Select Quick Pulse and verify survey section appears', async ({ page }) => {
    await createMoodRoomWithQuickPulse(page, `QuickPulse-${uid()}`);

    // Survey section should be visible
    await expect(page.locator('#survey-section')).toBeVisible({ timeout: 10_000 });

    // Survey container should have content (dynamically generated)
    const surveyContainer = page.locator('#survey-container');
    await expect(surveyContainer).not.toBeEmpty();

    // Should see mood emoji options (the Quick Pulse question shows emoji buttons)
    const moodOptions = page.locator('.mood-options, .mood-option, .mood-emoji-btn, [class*="mood"]').first();
    await expect(moodOptions).toBeVisible({ timeout: 5_000 });
  });

  test('10.5 — Submit a mood response', async ({ page }) => {
    await createMoodRoomWithQuickPulse(page, `Submitter-${uid()}`);

    // Wait for survey to be visible
    await expect(page.locator('#survey-section')).toBeVisible({ timeout: 10_000 });

    // Find and click one of the mood emoji buttons (the survey generates clickable options)
    const moodButton = page.locator(
      '.mood-option, .mood-emoji-btn, .emoji-option, [class*="mood-option"], #survey-container button',
    ).first();
    await expect(moodButton).toBeVisible({ timeout: 5_000 });
    await moodButton.click();
    await page.waitForTimeout(2_000);

    // After submitting, the survey section should either hide or show a confirmation
    // The mood tool may show "Waiting for host to reveal" or transition the UI
    // Verify no error occurred
    const errorVisible = await page
      .locator('.error, [role="alert"]:has-text("error")')
      .first()
      .isVisible()
      .catch(() => false);
    expect(errorVisible).toBeFalsy();
  });

  test('10.6 — Host can reveal results', async ({ page }) => {
    await createMoodRoomWithQuickPulse(page, `Host-${uid()}`);

    // Wait for survey
    await expect(page.locator('#survey-section')).toBeVisible({ timeout: 10_000 });

    // Submit a mood
    const moodButton = page.locator(
      '.mood-option, .mood-emoji-btn, .emoji-option, [class*="mood-option"], #survey-container button',
    ).first();
    await expect(moodButton).toBeVisible({ timeout: 5_000 });
    await moodButton.click();
    await page.waitForTimeout(2_000);

    // As host (room creator), the reveal results button should become visible
    const revealBtn = page.locator('#reveal-results-btn');
    // The reveal button may initially be hidden and shown after submission tracking
    // Force-show it if needed by waiting
    await expect(revealBtn).toBeVisible({ timeout: 10_000 });

    // Click reveal results
    await revealBtn.click();
    await page.waitForTimeout(2_000);

    // Results section should appear
    await expect(page.locator('#results-section')).toBeVisible({ timeout: 10_000 });

    // Results container should have content
    const resultsContainer = page.locator('#results-container');
    await expect(resultsContainer).not.toBeEmpty();
  });

  test('10.7 — Dark mode toggle sets data-theme', async ({ page }) => {
    await page.goto(`${V1_URL}/mood.html`);
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
