/**
 * Frontend build: read from /frontend/v1, minify JS/CSS, copy HTML/assets, output to /frontend/dist.
 * Does NOT modify source files. Run only during deployment (CI). Stable filenames, no hashing.
 * Excludes: *.local.js (not for production), tests/
 */
const fs = require('fs');
const path = require('path');
const { minify: minifyJs } = require('terser');
const CleanCSS = require('clean-css');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(__dirname, '..', 'v1');
const DIST = path.join(ROOT, 'dist');

const JS_FILES = [
  'shared.js',
  'script.js',
  'retro.js',
  'mood.js',
  'cache-bust-utils.js',
  'share-modal.js',
  'demo.js',
  'config.js',
];

const CSS_FILES = ['style.css', 'demo.css'];

// Exclude *.local.js from production (do not copy)
const LOCAL_JS_PATTERN = /\.local\.js$/;

async function minifyJavaScript(srcPath, outPath) {
  const code = fs.readFileSync(srcPath, 'utf8');
  const result = await minifyJs(code, {
    compress: { drop_console: true, drop_debugger: true },
    mangle: false,
    format: { comments: /^!|Copyright/i },
    ecma: 2020
  });
  if (result.error) throw result.error;
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, result.code);
}

function minifyCss(srcPath, outPath) {
  const code = fs.readFileSync(srcPath, 'utf8');
  const result = new CleanCSS({ level: 1 }).minify(code);
  if (result.errors.length) throw new Error(result.errors.join('; '));
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, result.styles);
}

function copyFile(src, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(src, dest);
}

function copyRecursive(srcDir, destDir, filter = () => true) {
  if (!fs.existsSync(srcDir)) return;
  const entries = fs.readdirSync(srcDir, { withFileTypes: true });
  for (const e of entries) {
    const s = path.join(srcDir, e.name);
    const d = path.join(destDir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'tests') continue; // do not deploy tests
      copyRecursive(s, d, filter);
    } else if (e.isFile() && filter(s, e.name)) {
      fs.mkdirSync(path.dirname(d), { recursive: true });
      fs.copyFileSync(s, d);
    }
  }
}


async function build() {
  if (fs.existsSync(DIST)) fs.rmSync(DIST, { recursive: true });
  fs.mkdirSync(DIST, { recursive: true });

  // Minify JS (exclude *.local.js — they are not in JS_FILES; skip if missing)
  for (const name of JS_FILES) {
    if (LOCAL_JS_PATTERN.test(name)) continue;
    const srcPath = path.join(SRC, name);
    if (!fs.existsSync(srcPath)) {
      console.warn('Skip (not found):', name);
      continue;
    }
    const outPath = path.join(DIST, name);
    await minifyJavaScript(srcPath, outPath);
    console.log('Minified JS:', name);
  }

  // Minify CSS
  for (const name of CSS_FILES) {
    const srcPath = path.join(SRC, name);
    if (!fs.existsSync(srcPath)) {
      console.warn('Skip (not found):', name);
      continue;
    }
    const outPath = path.join(DIST, name);
    minifyCss(srcPath, outPath);
    console.log('Minified CSS:', name);
  }

  // Copy HTML (preserve filenames); strip config.local.js line for production (dev-only script)
  const htmlFiles = fs.readdirSync(SRC).filter((f) => f.endsWith('.html'));
  for (const name of htmlFiles) {
    const srcPath = path.join(SRC, name);
    const destPath = path.join(DIST, name);
    let content = fs.readFileSync(srcPath, 'utf8');
    if (content.includes('config.local.js')) {
      content = content.replace(/\s*document\.write\s*\(\s*['\"].*config\.local\.js.*['\"]\s*\)\s*;?\s*/g, '');
    }
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.writeFileSync(destPath, content);
  }
  console.log('Copied HTML:', htmlFiles.length, 'files');

  // Copy static assets (no *.local.js)
  copyRecursive(path.join(SRC, 'assets'), path.join(DIST, 'assets'), (_, name) => !LOCAL_JS_PATTERN.test(name));
  // Vendored third-party JS (self-hosted; see frontend/v1/vendor/README.md).
  // Copied verbatim — these are already minified upstream and must ship
  // byte-identical to the reviewed, hashed files.
  copyRecursive(path.join(SRC, 'vendor'), path.join(DIST, 'vendor'), () => true);

  // Copy root-level static files (exclude *.local.js)
  const staticRoot = ['favicon.ico', 'favicon.svg', 'robots.txt', 'sitemap.xml'];
  for (const name of staticRoot) {
    const src = path.join(SRC, name);
    if (fs.existsSync(src)) copyFile(src, path.join(DIST, name));
  }

  // Write staticwebapp.config.json with security headers, cache headers, and the
  // join route.
  //
  // globalHeaders is the ONLY place a CSP reaches these documents. The policy in
  // SecurityConfig.buildCspPolicy() attaches to API responses, and the backend is
  // API-only in production (spring.web.resources.add-mappings=false) — so it never
  // sees the HTML that loads scripts. Keep the two in sync by hand.
  //
  // script-src has NO third-party origin: v1's JS is self-hosted from vendor/,
  // never pulled from a CDN. 'unsafe-inline' is required because the v1 pages
  // carry several inline <script> blocks each; omitting it breaks them with CSP
  // violations. This does not weaken the policy in practice: the risk a strict
  // script-src guards against is a third-party origin serving script, and none
  // is permitted here.
  //
  // Google Fonts is a genuine dependency (style + font only, never script).
  const CSP = [
    "default-src 'self'",
    "script-src 'self' 'unsafe-inline'",
    "style-src 'self' 'unsafe-inline'",
    "font-src 'self' data:",
    "img-src 'self' data:",
    // Same-origin by default: config.js resolves the API to window.location.origin,
    // so a same-host deploy needs no extra source here. Add your API origin if you
    // serve it from a different host.
    "connect-src 'self'",
    "frame-src 'self'",
    "frame-ancestors 'self'",
    "base-uri 'self'",
    "form-action 'self'"
  ].join('; ');

  const swaConfig = {
    globalHeaders: {
      'Content-Security-Policy': CSP,
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Permissions-Policy': 'camera=(), microphone=(), geolocation=()'
    },
    navigationFallback: {
      rewrite: '/index.html'
    },
    routes: [
      { route: '/index.html', headers: { 'Cache-Control': 'no-cache' } },
      { route: '/join', serve: '/join.html' },
      { route: '/*.js', headers: { 'Cache-Control': 'public, max-age=31536000, immutable' } },
      { route: '/*.css', headers: { 'Cache-Control': 'public, max-age=31536000, immutable' } }
    ]
  };
  fs.writeFileSync(path.join(DIST, 'staticwebapp.config.json'), JSON.stringify(swaConfig, null, 2));

  console.log('Build complete →', DIST);
}

build().catch((err) => {
  console.error(err);
  process.exit(1);
});
