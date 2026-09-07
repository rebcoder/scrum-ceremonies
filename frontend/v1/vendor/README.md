# Vendored third-party assets (v1 tools)

These files are served from our own origin. **Do not replace them with a CDN tag.**

## fonts/ — Inter

`inter-v20-latin.woff2` + `inter-v20-latin-ext.woff2`, the variable face at weights
400–800, 134KB together. Declared by `@font-face` at the top of `style.css`.

They are here for the same reason the JS below is: loading Inter from
`fonts.googleapis.com` sends each visitor's IP to Google on every page view and makes
the tools unusable offline or air-gapped. That sits badly with a product whose promise
is that nothing leaves the room.

Scripts outside latin/latin-ext (Cyrillic, Greek, Vietnamese) fall back to the system
stack in `--font-sans`. They render; they just are not Inter. Adding a subset means
another woff2 here plus its `unicode-range` block — not a CDN link.

Inter is SIL OFL 1.1; `fonts/OFL.txt` is the licence, which must ship with the files.

## fonts/ — Instrument Serif

`instrument-serif-v5-{latin,latin-ext}.woff2` and the italic pair, about 47KB together.
The landing page's display face (headings and the italic accent word); the tool pages
stay on Inter. Declared by `@font-face` in `index.html` only, so the tool pages never
download it. SIL OFL 1.1; `fonts/OFL-InstrumentSerif.txt` is the licence.

SHA-256, as downloaded from the Google Fonts static host:

| File | SHA-256 |
|---|---|
| `instrument-serif-v5-latin.woff2` | `60c06664b5a95c7de6cc3e00d1f9034d78bd1e40b564016b241674449a067d4d` |
| `instrument-serif-v5-latin-ext.woff2` | `a8c4bd7cd7073180e740d2d83a616b5cb0845579b73207eeafeae8532e70c901` |
| `instrument-serif-v5-italic-latin.woff2` | `6ee678c33f388dd7ba59700ebea635deb98821baafd817b09891f7927177f702` |
| `instrument-serif-v5-italic-latin-ext.woff2` | `a04fc7ed18a8037149ce0bfda58076709d8e0840e136ed00abbdc196b7992443` |

## JavaScript

### Why they are here

These used to load from `cdn.jsdelivr.net`, and two of them (`sockjs-client`,
`stompjs`) had **no version pin** — meaning
every visitor executed whatever the registry served at that moment, with no
`integrity=` attribute and no CSP on the document (the CSP in `SecurityConfig`
attaches to API responses only; a static host serves these pages).

Arbitrary third-party JavaScript running on these pages can read and send anything
the page can: room codes, display names, every card and vote in the session, and the
JS-readable CSRF cookie. A registry compromise would have reached every visitor at
once.

The canonical failure of this exact pattern is the **polyfill.io** supply-chain
takeover (June 2024): a widely-trusted CDN script host changed hands and began
serving malicious payloads to 490,000+ sites, none of which had to be
individually compromised. Pinning plus SRI would have blunted that; self-hosting
removes the trust relationship entirely, which is why GitHub serves all of its
own JavaScript and does not allow third-party origins in `script-src` at all.

### Provenance

Downloaded once from the jsDelivr registry mirror at the exact versions below. SHA-256 of each file is recorded in the commit that added it, so
any later modification is detectable by re-hashing.

| File | Package | Version | Notes |
|---|---|---|---|
| `sockjs-1.6.1.min.js` | `sockjs-client` | 1.6.1 | Matches the root `package.json` devDependency used by `scripts/ws-*-test.js`; previously **unpinned** on the CDN. |
| `stomp-2.3.3.min.js` | `stompjs` | 2.3.3 | The **legacy** package providing the `Stomp.over(socket)` API. **Not** `@stomp/stompjs` v7, which has an incompatible `new Client({...})` API. Previously **unpinned**. |
| `gsap-3.12.2.min.js` | `gsap` | 3.12.2 | Version unchanged from the CDN tag. |
| `jspdf-2.5.1.umd.min.js` | `jspdf` | 2.5.1 | Version unchanged. Also referenced by the runtime fallback in `retro.js`. |
| `html2canvas-1.4.1.min.js` | `html2canvas` | 1.4.1 | Version unchanged. |

### Upgrading

1. Download the new version, keep the version in the filename.
2. Re-hash and record the SHA-256 in the commit message.
3. Update the `<script src>` tags in `poker.html` / `retro.html` / `mood.html`
   **and** the dynamic-load fallback in `retro.js`.
4. Verify the v1 pages still work with `cdn.jsdelivr.net` blocked at the network
   level — that is the standing gate, and it is what proves the page is genuinely
   self-hosted rather than silently falling back.
