import { test, expect } from './fixtures';

/**
 * v1 a11y regression guard.
 *
 * The v1 tools use emoji as decorative prefixes in labels ("👤 Your Name",
 * "🏠 Room Code"). They are aria-hidden so the accessible NAME excludes the
 * emoji. This guard fails if a future label edit re-introduces emoji into an
 * accessible name — a regression guard like this one is what keeps a fix
 * from silently regressing.
 *
 * Static-only (loads the create form, no room creation) — fast and stable.
 */
const V1_URL = 'http://localhost:3000';

test.describe('v1 a11y guard — accessible names exclude decorative emoji', () => {
  test('poker — name + room-code inputs named without emoji', async ({ page }) => {
    await page.goto(`${V1_URL}/poker.html`);
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('textbox', { name: 'Your Name' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Room Code' })).toBeVisible();
    // The name label uses an aria-hidden Lucide icon rather than an emoji prefix,
    // so the icon is hidden from the a11y tree and the accessible name stays a
    // clean "Your Name".
    await expect(page.locator('label[for="poker-name-input"]')).not.toContainText('👤');
    await expect(page.locator('label[for="poker-name-input"] .ic[data-ic="user"]')).toHaveAttribute(
      'aria-hidden',
      'true',
    );
  });

  test('mood — name + room-code inputs named without emoji', async ({ page }) => {
    await page.goto(`${V1_URL}/mood.html`);
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('textbox', { name: 'Your Name' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Room Code' })).toBeVisible();
  });

  test('retro — name + board-code inputs named without emoji', async ({ page }) => {
    await page.goto(`${V1_URL}/retro.html`);
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('textbox', { name: 'Your Name' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Board Code' })).toBeVisible();
  });
});
