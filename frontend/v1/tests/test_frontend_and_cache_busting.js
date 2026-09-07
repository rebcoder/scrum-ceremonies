#!/usr/bin/env node
/**
 * Frontend functional and cache-busting tests.
 * Run from repo root: node frontend/src/tests/test_frontend_and_cache_busting.js
 * Or from frontend/src: node tests/test_frontend_and_cache_busting.js
 *
 * Tests:
 * 1. Required files exist under frontend/src/
 * 2. config.js defines APP_CONFIG with API_BASE_URL and WS_BASE_URL
 * 3. cache-bust-utils.js contains required functions and exports window.cacheBustUtils
 * 4. Main HTML files reference config and key assets
 * 5. Optional: live URL check if FRONTEND_URL is set
 */

const fs = require('fs');
const path = require('path');

const FRONTEND_DIR = path.resolve(__dirname, '..');
const PROJECT_ROOT = path.resolve(FRONTEND_DIR, '..');
let passed = 0;
let failed = 0;

function ok(name, message) {
  console.log('✓', name, message ? `- ${message}` : '');
  passed++;
}

function fail(name, message) {
  console.error('✗', name, message ? `- ${message}` : '');
  failed++;
}

function fileExists(rel) {
  const p = path.join(FRONTEND_DIR, rel);
  return fs.existsSync(p) && fs.statSync(p).isFile();
}

function readFile(rel) {
  return fs.readFileSync(path.join(FRONTEND_DIR, rel), 'utf8');
}

// ---- 1. Required files ----
const REQUIRED_FILES = [
  'index.html',
  'config.js',
  'cache-bust-utils.js',
  'script.js',
  'style.css',
  'demo.js',
  'demo.css',
  'poker.html',
  'retro.html',
  'mood.html',
  'mood.js',
  'retro.js',
  'share-modal.js',
  'demo.html',
  'join.html',
  'about.html',
  'privacy.html',
  'terms.html',
  'contact.html',
  'robots.txt',
  'staticwebapp.config.json',
  'favicon.ico',
];

console.log('\n=== 1. Required files exist ===\n');
for (const f of REQUIRED_FILES) {
  if (fileExists(f)) ok('File exists', f);
  else fail('File missing', f);
}

// ---- 2. config.js ----
console.log('\n=== 2. config.js structure ===\n');
if (fileExists('config.js')) {
  const configContent = readFile('config.js');
  if (configContent.includes('APP_CONFIG')) ok('config.js', 'defines APP_CONFIG');
  else fail('config.js', 'missing APP_CONFIG');
  if (configContent.includes('API_BASE_URL')) ok('config.js', 'defines API_BASE_URL');
  else fail('config.js', 'missing API_BASE_URL');
  if (configContent.includes('WS_BASE_URL')) ok('config.js', 'defines WS_BASE_URL');
  else fail('config.js', 'missing WS_BASE_URL');
} else {
  fail('config.js', 'file not found');
}

// ---- 3. cache-bust-utils.js ----
console.log('\n=== 3. Cache-busting utilities ===\n');
if (fileExists('cache-bust-utils.js')) {
  const cbContent = readFile('cache-bust-utils.js');
  const requiredFns = ['isIncompatibleError', 'forceReload', 'handleWebSocketError', 'handleServerResponse', 'monitorWebSocketConnection'];
  for (const fn of requiredFns) {
    if (cbContent.includes(fn)) ok('cache-bust-utils.js', `contains ${fn}`);
    else fail('cache-bust-utils.js', `missing ${fn}`);
  }
  if (cbContent.includes('window.cacheBustUtils')) ok('cache-bust-utils.js', 'exports window.cacheBustUtils');
  else fail('cache-bust-utils.js', 'missing window.cacheBustUtils export');
  if (cbContent.includes('sessionStorage.clear')) ok('cache-bust-utils.js', 'forceReload clears sessionStorage');
  else fail('cache-bust-utils.js', 'forceReload should clear sessionStorage');
  const incompatiblePatterns = ['version mismatch', 'incompatible', 'protocol error'];
  if (incompatiblePatterns.every(p => cbContent.includes(p))) ok('cache-bust-utils.js', 'incompatible error patterns present');
  else fail('cache-bust-utils.js', 'missing some incompatible error patterns');
} else {
  fail('cache-bust-utils.js', 'file not found');
}

// ---- 4. HTML references ----
console.log('\n=== 4. HTML structure and references ===\n');
const htmlFilesRequireConfig = ['index.html', 'poker.html', 'retro.html', 'mood.html', 'join.html'];
const htmlFilesOptionalConfig = ['demo.html'];
for (const h of htmlFilesRequireConfig) {
  if (!fileExists(h)) {
    fail(h, 'file missing');
    continue;
  }
  const content = readFile(h);
  if (content.includes('config.js')) ok(h, 'references config.js');
  else fail(h, 'should reference config.js');
  if (h === 'index.html') {
    if (content.includes('style.css')) ok(h, 'references style.css');
    else fail(h, 'should reference style.css');
  }
}
for (const h of htmlFilesOptionalConfig) {
  if (!fileExists(h)) {
    fail(h, 'file missing');
    continue;
  }
  const content = readFile(h);
  if (content.includes('config.js')) ok(h, 'references config.js');
  else ok(h, 'no config.js (demo-only page)');
}
if (fileExists('index.html')) {
  const idx = readFile('index.html');
  if (idx.includes('cache-bust-utils.js') || idx.includes('cacheBustUtils')) ok('index.html', 'uses cache-bust utils or global');
  else ok('index.html', 'cache-bust-utils may be loaded by app pages only');
}
if (fileExists('poker.html') && readFile('poker.html').includes('cache-bust-utils')) ok('poker.html', 'references cache-bust-utils.js');

// ---- 5. Versioned asset pattern (build output) ----
// If BUILD_STATIC_DIR is set (e.g. target/classes/static), check versioned assets exist
const buildDir = process.env.BUILD_STATIC_DIR;
if (buildDir) {
  console.log('\n=== 5. Build output versioned assets ===\n');
  const buildPath = path.isAbsolute(buildDir) ? buildDir : path.join(PROJECT_ROOT, buildDir);
  if (fs.existsSync(buildPath)) {
    const files = fs.readdirSync(buildPath);
    const versionedJs = files.filter(f => /\.v[\d_]+\\.js$/.test(f));
    const versionedCss = files.filter(f => /\.v[\d_]+\\.css$/.test(f));
    if (versionedJs.length >= 1) ok('Build', `versioned JS: ${versionedJs.join(', ')}`);
    else fail('Build', 'no versioned .v*.js files found');
    if (versionedCss.length >= 1) ok('Build', `versioned CSS: ${versionedCss.join(', ')}`);
    else fail('Build', 'no versioned .v*.css files found');
  } else {
    fail('Build', `BUILD_STATIC_DIR not found: ${buildPath}`);
  }
} else {
  console.log('\n=== 5. Build output (skip) ===\n');
  console.log('Set BUILD_STATIC_DIR to check versioned assets (e.g. target/classes/static)\n');
}

// ---- 6. Optional live URL ----
const frontendUrl = process.env.FRONTEND_URL;
if (frontendUrl) {
  console.log('\n=== 6. Live frontend URL ===\n');
  const https = require(frontendUrl.startsWith('https') ? 'https' : 'http');
  const urls = [
    { path: '/', name: 'Home' },
    { path: '/poker.html', name: 'Poker' },
    { path: '/retro.html', name: 'Retro' },
    { path: '/mood.html', name: 'Mood' },
    { path: '/config.js', name: 'config.js' },
  ];
  let liveDone = 0;
  function maybeDone() {
    liveDone++;
    if (liveDone === urls.length) printSummary();
  }
  urls.forEach(({ path: p, name }) => {
    const url = frontendUrl.replace(/\/$/, '') + p;
    const parsed = new URL(url);
    const opts = { hostname: parsed.hostname, port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80), path: parsed.pathname || '/', method: 'GET' };
    const req = https.request(opts, (res) => {
      if (res.statusCode === 200) ok(`GET ${p}`, `200 ${name}`);
      else fail(`GET ${p}`, `${res.statusCode} ${name}`);
      maybeDone();
    });
    req.on('error', (err) => {
      fail(`GET ${p}`, err.message);
      maybeDone();
    });
    req.setTimeout(10000, () => {
      req.destroy();
      fail(`GET ${p}`, 'timeout');
      maybeDone();
    });
    req.end();
  });
} else {
  printSummary();
}

function printSummary() {
  console.log('\n========================================');
  console.log('Frontend & cache-bust tests summary');
  console.log('========================================');
  console.log('Passed:', passed);
  console.log('Failed:', failed);
  console.log('Total:', passed + failed);
  if (failed > 0) process.exit(1);
  process.exit(0);
}

// If not using async live checks, summary is printed after sync tests
if (!frontendUrl) {
  printSummary();
}
