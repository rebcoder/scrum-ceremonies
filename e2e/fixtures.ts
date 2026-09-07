/**
 * The tools API ignores CSRF server-side for /api/** (SecurityConfig), so the plain
 * Playwright test object is the entire fixture. Specs import from here so a fixture
 * can be added later without touching them.
 */
export { test, expect } from '@playwright/test';
export type { Page } from '@playwright/test';
