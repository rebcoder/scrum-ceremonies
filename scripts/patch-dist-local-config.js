#!/usr/bin/env node
/**
 * Patches frontend/dist/index.html to load config.local.js on localhost.
 * Uses single-quoted document.write so the inline script does not break on inner ".
 * Usage: node patch-dist-local-config.js <path-to-dist>
 */
const fs = require('fs');
const path = require('path');
const distDir = process.argv[2];
if (!distDir) {
  console.error('Usage: node patch-dist-local-config.js <path-to-dist>');
  process.exit(1);
}
const distIndex = path.join(distDir, 'index.html');
if (!fs.existsSync(distIndex)) {
  console.error('Not found:', distIndex);
  process.exit(1);
}
let html = fs.readFileSync(distIndex, 'utf8');
// Remove any existing (broken or empty) localhost config loader blocks so we don't run bad code
html = html.replace(/<script>\s*if \(window\.location\.hostname === ['"]localhost['"] \|\| window\.location\.hostname === ['"]127\.0\.0\.1['"]\) \{\s*\}\s*<\/script>\s*/g, '');
// Broken: document.write("<script src="config.local.js"> - double-quoted string breaks parsing
const brokenLoader = new RegExp(
  '<script>\\s*if\\s*\\(window\\.location\\.hostname[\\s\\S]*?document\\.write\\("<script src="config\\.local\\.js"><\\\\/script>"\\)\\s*;\\s*\\}\\s*<\\/script>\\s*',
  'g'
);
html = html.replace(brokenLoader, '');
// Remove correct loader blocks too so we end up with exactly one (no duplicates)
// Match both </script> and <\/script> in the written string
const correctLoaderBlock = new RegExp(
  '<script>\\s*if\\s*\\(window\\.location\\.hostname\\s*===\\s*["\']localhost["\']\\s*\\|\\|\\s*window\\.location\\.hostname\\s*===\\s*["\']127\\.0\\.0\\.1["\']\\)\\s*\\{\\s*document\\.write\\(\'<script src="config\\.local\\.js"><\\\\?/script>\'\\)\\s*;\\s*\\}\\s*<\\/script>\\s*',
  'g'
);
html = html.replace(correctLoaderBlock, '');
const configScriptTag = '<script src="config.js"></script>';
if (!html.includes(configScriptTag)) {
  console.error('index.html does not contain expected config.js script tag');
  process.exit(1);
}
const q = String.fromCharCode(39);
// Use <\\/script> so HTML contains <\/script> and the parser does not close the script tag early
const inner = '<script src="config.local.js"><\\/script>';
const loader = '<script>\n  if (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1") {\n    document.write(' + q + inner + q + ');\n  }\n  </script>\n  ';
html = html.replace(configScriptTag, loader + configScriptTag);
fs.writeFileSync(distIndex, html);
console.log('Patched', distIndex);
