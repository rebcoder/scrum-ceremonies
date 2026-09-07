/**
 * Regenerates the product screenshots from the running local stack:
 *   docs/images/home*.png and frontend/v1/assets/demo/{poker,retro,mood}/*-step-N.png
 *
 * Prerequisites: ./scripts/dev-up.sh (backend :8080, static frontend :3000, Redis).
 * Usage:         node scripts/capture-screenshots.js            # everything
 *                ONLY=poker,mood node scripts/capture-screenshots.js
 *
 * Uses three browser contexts (Alice, Bob, Chloe) so the shots show a real
 * multi-user session; every room is throwaway and expires with the 8-hour TTL.
 */
const { chromium } = require('@playwright/test');
const fs = require('fs');
const BASE = 'http://localhost:3000';
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const ONLY = (process.env.ONLY || 'home,poker,retro,mood').split(',');
const DEMO = `${ROOT}/frontend/v1/assets/demo`;
const DOCS = `${ROOT}/docs/images`;
const VIEW = { width: 1280, height: 800 };

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function shot(page, file, full = false, clip) {
  await sleep(600);
  await page.screenshot(clip ? { path: file, clip } : { path: file, fullPage: full });
  console.log('saved', file.replace(ROOT + '/', ''), fs.statSync(file).size, 'bytes');
}
async function newPage(browser, dark = false) {
  const ctx = await browser.newContext({ viewport: VIEW, deviceScaleFactor: 1, colorScheme: dark ? 'dark' : 'light' });
  return ctx.newPage();
}
async function withName(page, url, key, name) {
  await page.goto(url);
  await page.evaluate(([k, n]) => sessionStorage.setItem(k, n), [key, name]);
}

(async () => {
  const browser = await chromium.launch();
  try {
    // ---------- Homepage ----------
    if (ONLY.includes('home')) {
      const p = await newPage(browser);
      await p.goto(`${BASE}/index.html`); await p.waitForLoadState('networkidle');
      await shot(p, `${DOCS}/home.png`, false, { x: 0, y: 0, width: 1280, height: 720 });
      await p.locator('#theme-toggle').click(); await sleep(400);
      await shot(p, `${DOCS}/home-dark.png`, false, { x: 0, y: 0, width: 1280, height: 720 });
      await p.context().close();
    }
    // ---------- Planning Poker ----------
    if (ONLY.includes('poker')) {
      const a = await newPage(browser);
      await a.goto(`${BASE}/poker.html`); await a.waitForSelector('#room-section');
      await a.locator('#poker-name-input').fill('Alice');
      await shot(a, `${DEMO}/poker/poker-step-1.png`);
      await a.locator('#create-room-btn').click();
      await a.waitForURL(/room=/, { timeout: 15000 }); await a.waitForSelector('#voting-section', { state: 'visible', timeout: 15000 });
      const code = (await a.locator('#room-code').textContent()).trim();
      console.log('poker room', code);
      const others = [];
      for (const name of ['Bob', 'Chloe']) {
        const p = await newPage(browser);
        await withName(p, `${BASE}/poker.html`, 'scrumPokerUsername', name);
        await p.goto(`${BASE}/poker?room=${code}`); await p.waitForLoadState('networkidle');
        await p.waitForSelector('#voting-section', { state: 'visible', timeout: 15000 });
        others.push(p);
      }
      await a.waitForFunction(() => document.querySelector('#user-count')?.textContent?.trim().startsWith('3'), null, { timeout: 15000 }).catch(() => console.log('user-count did not reach 3 in time'));
      await sleep(800);
      await shot(a, `${DEMO}/poker/poker-step-2.png`);
      await a.locator('.vote-btn[data-value="5"]').click();
      await others[0].locator('.vote-btn[data-value="8"]').click();
      await others[1].locator('.vote-btn[data-value="5"]').click();
      await sleep(2500);
      await shot(a, `${DEMO}/poker/poker-step-3.png`);
      await a.locator('#reveal-votes-btn').click();
      await a.waitForSelector('#votes-section', { state: 'visible', timeout: 10000 });
      await a.locator('#votes-section').scrollIntoViewIfNeeded(); await sleep(500);
      await a.evaluate(() => window.scrollTo(0, 0));
      await shot(a, `${DEMO}/poker/poker-step-4.png`, true);
      for (const p of [a, ...others]) await p.context().close();
    }
    // ---------- Sprint Retrospective ----------
    if (ONLY.includes('retro')) {
      const a = await newPage(browser);
      await a.goto(`${BASE}/retro.html`); await a.waitForSelector('#retro-room-section');
      await a.locator('#retro-name-input').fill('Alice');
      await shot(a, `${DEMO}/retro/retro-step-1.png`);
      await a.locator('#retro-create-room-btn').click();
      await a.waitForURL(/room=/, { timeout: 15000 }); await a.waitForSelector('#retro-board', { state: 'visible', timeout: 15000 });
      const code = (await a.locator('#retro-room-code').textContent()).trim();
      console.log('retro room', code);
      const b = await newPage(browser);
      await withName(b, `${BASE}/retro.html`, 'retroUserName', 'Bob');
      await b.goto(`${BASE}/retro?room=${code}`); await b.waitForLoadState('networkidle');
      const modal = b.locator('#retro-name-modal');
      if (await modal.isVisible({ timeout: 3000 }).catch(() => false)) {
        await b.locator('#retro-modal-name-input').fill('Bob'); await b.locator('#retro-modal-continue').click();
      }
      await b.waitForSelector('#retro-board', { state: 'visible', timeout: 15000 });
      const add = async (p, col, btn, text) => { await p.locator(`#${col}-input`).fill(text); await p.locator(`.${btn}`).click(); await sleep(700); };
      await add(a, 'wentWell', 'went-well-btn', 'Pairing on the payment bug got it fixed in a day');
      await add(b, 'wentWell', 'went-well-btn', 'Demo went smoothly and stakeholders were happy');
      await add(a, 'toImprove', 'to-improve-btn', 'Standups ran long, keep them to 15 minutes');
      await add(b, 'toImprove', 'to-improve-btn', 'Too many context switches mid-sprint');
      await add(a, 'actionItems', 'action-items-btn', 'Timebox standup to 15 min (owner: Bob)');
      await sleep(1000);
      await shot(a, `${DEMO}/retro/retro-step-2.png`);
      const upvote = async (p, col, idx) => { const btn = p.locator(`#${col} .retro-upvote`).nth(idx); if (await btn.count()) { await btn.click(); await sleep(500); } };
      await upvote(b, 'wentWell', 0); await upvote(b, 'toImprove', 0); await upvote(a, 'wentWell', 1); await upvote(a, 'toImprove', 0);
      await sleep(1200);
      await shot(a, `${DEMO}/retro/retro-step-3.png`);
      await shot(a, `${DEMO}/retro/retro-step-4.png`, true);
      await a.context().close(); await b.context().close();
    }
    // ---------- Team Mood Check ----------
    if (ONLY.includes('mood')) {
      const a = await newPage(browser);
      await a.goto(`${BASE}/mood.html`); await a.waitForSelector('#room-section');
      await a.locator('#mood-name-input').fill('Alice');
      await shot(a, `${DEMO}/mood/mood-step-1.png`);
      await a.locator('#create-room-btn').click();
      await a.waitForSelector('#mode-selection-section', { state: 'visible', timeout: 10000 });
      await a.locator('.mode-card[data-mode="quick"]').click();
      await a.waitForURL(/room=/, { timeout: 15000 }); await a.waitForSelector('#survey-section', { state: 'visible', timeout: 15000 });
      const code = (await a.locator('#room-code').textContent()).trim();
      console.log('mood room', code);
      const others = [];
      for (const name of ['Bob', 'Chloe']) {
        const p = await newPage(browser);
        await withName(p, `${BASE}/mood.html`, 'moodUserName', name);
        await p.goto(`${BASE}/mood?room=${code}`); await p.waitForLoadState('networkidle');
        await p.waitForSelector('#survey-section', { state: 'visible', timeout: 15000 });
        others.push(p);
      }
      await sleep(1000);
      await shot(others[0], `${DEMO}/mood/mood-step-2.png`);
      await others[0].locator('.mood-option').nth(3).click();
      await others[1].locator('.mood-option').nth(2).click();
      await sleep(2500);
      await a.locator('.mood-option').nth(3).hover();
      await shot(a, `${DEMO}/mood/mood-step-3.png`);
      await a.locator('.mood-option').nth(3).click();
      await sleep(2500);
      await a.waitForSelector('#reveal-results-btn', { state: 'visible', timeout: 15000 });
      await a.locator('#reveal-results-btn').click();
      await a.waitForSelector('#results-section', { state: 'visible', timeout: 15000 });
      await sleep(4000);
      await a.evaluate(() => window.scrollTo(0, 0));
      await shot(a, `${DEMO}/mood/mood-step-4.png`, true);
      for (const p of [a, ...others]) await p.context().close();
    }
  } finally { await browser.close(); }
})().catch(e => { console.error('CAPTURE FAILED:', e.message); process.exit(1); });
