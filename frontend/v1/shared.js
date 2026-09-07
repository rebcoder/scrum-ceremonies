/**
 * Copyright (c) 2026 rebcoder. MIT licensed — see LICENSE.
 *
 * Shared utilities for v1 tools (Poker, Retro, Mood)
 * Dark mode, copy-to-clipboard, host name polling, share modal binding
 */

// ─── Dark Mode Toggle ──────────────────────────────────────────
(function initDarkMode() {
  const toggle = document.getElementById('theme-toggle');
  if (!toggle) return;
  // Show the icon for the action the button performs: moon = "switch to dark",
  // sun = "switch to light". Rendered via the shared icon set (Lucide parity).
  function paint(name) {
    toggle.innerHTML = '<span class="ic" data-ic="' + name + '" aria-hidden="true"></span>';
    hydrateIcons(toggle); // no-op if ICONS not yet assigned; DOMContentLoaded pass finishes it
  }
  const theme = localStorage.getItem('ceremonies_theme') || 'light';
  if (theme === 'dark') {
    document.documentElement.setAttribute('data-theme', 'dark');
    paint('sun');
    toggle.setAttribute('aria-pressed', 'true');
  } else {
    paint('moon');
    toggle.setAttribute('aria-pressed', 'false');
  }
  toggle.addEventListener('click', function () {
    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    if (isDark) {
      document.documentElement.removeAttribute('data-theme');
      localStorage.setItem('ceremonies_theme', 'light');
      paint('moon');
      toggle.setAttribute('aria-pressed', 'false');
    } else {
      document.documentElement.setAttribute('data-theme', 'dark');
      localStorage.setItem('ceremonies_theme', 'dark');
      paint('sun');
      toggle.setAttribute('aria-pressed', 'true');
    }
  });
})();

// ─── Copy to Clipboard ─────────────────────────────────────────
function showCopySuccess(button) {
  var originalIcon = button.querySelector('.copy-icon');
  if (originalIcon) {
    setIcon(originalIcon, 'check');
    button.style.background = 'var(--color-success-muted)';
    button.style.borderColor = 'var(--color-success)';
    button.style.color = 'var(--color-success)';
    setTimeout(function () {
      setIcon(originalIcon, 'clipboard-list');
      button.style.background = '';
      button.style.borderColor = '';
      button.style.color = '';
    }, 2000);
  }
  if (typeof window.showToast === 'function') {
    window.showToast('Room code copied!', 'success');
  }
}

function fallbackCopy(text, button) {
  var textArea = document.createElement('textarea');
  textArea.value = text;
  textArea.style.position = 'fixed';
  textArea.style.opacity = '0';
  document.body.appendChild(textArea);
  textArea.select();
  try {
    document.execCommand('copy');
    showCopySuccess(button);
  } catch (err) {
    console.error('Fallback copy failed:', err);
    if (typeof window.showToast === 'function') {
      window.showToast('Failed to copy. Please copy manually.', 'error');
    }
  }
  document.body.removeChild(textArea);
}

// ─── Copy Room Code Button Binding ──────────────────────────────
document.addEventListener('DOMContentLoaded', function () {
  var copyBtn = document.getElementById('copy-room-code-btn') || document.getElementById('copy-retro-room-code-btn');
  var roomCodeEl = document.getElementById('room-code') || document.getElementById('retro-room-code');

  if (copyBtn && roomCodeEl) {
    copyBtn.addEventListener('click', function () {
      var roomCode = roomCodeEl.textContent.trim();
      if (!roomCode) return;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(roomCode).then(function () {
          showCopySuccess(copyBtn);
        }).catch(function () {
          fallbackCopy(roomCode, copyBtn);
        });
      } else {
        fallbackCopy(roomCode, copyBtn);
      }
    });
  }
});

// ─── Share Modal Binding ────────────────────────────────────────
document.addEventListener('DOMContentLoaded', function () {
  var shareBtn = document.getElementById('share-btn') || document.getElementById('retro-share-btn');
  if (shareBtn) {
    shareBtn.addEventListener('click', function () {
      if (typeof window.openShareModal === 'function') {
        window.openShareModal();
      }
    });
  }
});

// ─── Host Name Polling ──────────────────────────────────────────
/**
 * Sets up host name display for a v1 room.
 * @param {Object} opts
 * @param {string} opts.roomCodeId      - DOM id of the room code element
 * @param {string} opts.hostInfoId      - DOM id of the host info container
 * @param {string} opts.hostNameId      - DOM id of the host name span
 * @param {string} opts.apiEndpoint     - API path e.g. '/api/room-state' or '/api/retro/state'
 * @param {string} opts.globalFnName    - Name to expose the update function (e.g. 'updateHostNameFromState')
 */
function setupHostNamePolling(opts) {
  function updateHostName(roomState) {
    if (roomState && roomState.hasHost && roomState.hostName) {
      var hostInfo = document.getElementById(opts.hostInfoId);
      var hostName = document.getElementById(opts.hostNameId);
      if (hostInfo && hostName) {
        hostName.textContent = roomState.hostName;
        hostInfo.style.display = 'block';
      }
    } else {
      var hostInfo = document.getElementById(opts.hostInfoId);
      if (hostInfo) {
        hostInfo.style.display = 'none';
      }
    }
  }

  function fetchHostName() {
    var roomCodeEl = document.getElementById(opts.roomCodeId);
    if (roomCodeEl && roomCodeEl.textContent && roomCodeEl.textContent.trim().length === 8) {
      var roomId = roomCodeEl.textContent.trim();
      fetch(APP_CONFIG.API_BASE_URL + opts.apiEndpoint + '?roomId=' + roomId)
        .then(function (r) { return r.json(); })
        .then(function (data) { updateHostName(data); })
        .catch(function (err) { console.log('Could not fetch host name:', err); });
    }
  }

  // Poll until room code appears
  var pollCount = 0;
  var maxPolls = 20;
  var pollInterval = setInterval(function () {
    pollCount++;
    var roomCodeEl = document.getElementById(opts.roomCodeId);
    if (roomCodeEl && roomCodeEl.textContent && roomCodeEl.textContent.trim().length === 8) {
      fetchHostName();
      clearInterval(pollInterval);
    } else if (pollCount >= maxPolls) {
      clearInterval(pollInterval);
    }
  }, 500);

  // Intercept fetch calls to update host name from room-state responses
  var originalFetch = window.fetch;
  window.fetch = function () {
    var args = arguments;
    var url = args[0];
    if (typeof url === 'string' && url.includes(APP_CONFIG.API_BASE_URL + opts.apiEndpoint)) {
      return originalFetch.apply(this, args).then(function (response) {
        if (response.ok) {
          response.clone().json().then(function (data) {
            updateHostName(data);
          }).catch(function () {});
        }
        return response;
      });
    }
    return originalFetch.apply(this, args);
  };

  // Expose globally
  if (opts.globalFnName) {
    window[opts.globalFnName] = updateHostName;
  }
}

// ─── Modal Focus Trap ───────────────────────────────────────────
/**
 * Traps keyboard focus within a modal element.
 * Returns a cleanup function to remove the trap.
 * @param {HTMLElement} modalEl - The modal container element
 * @returns {Function} cleanup - Call to remove the focus trap
 */
function trapFocus(modalEl) {
  var focusableSelectors = 'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

  function handleKeydown(e) {
    if (e.key !== 'Tab') return;
    var focusable = modalEl.querySelectorAll(focusableSelectors);
    if (focusable.length === 0) return;
    var first = focusable[0];
    var last = focusable[focusable.length - 1];
    if (e.shiftKey) {
      if (document.activeElement === first) {
        e.preventDefault();
        last.focus();
      }
    } else {
      if (document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  }

  modalEl.addEventListener('keydown', handleKeydown);

  // Focus first focusable element
  var firstFocusable = modalEl.querySelector(focusableSelectors);
  if (firstFocusable) firstFocusable.focus();

  return function cleanup() {
    modalEl.removeEventListener('keydown', handleKeydown);
  };
}

// ─── Team Name Utilities (shared across all v1 tools) ───────────
var TEAM_NAME_STORAGE_KEY = 'ceremonies.teamName';
var TEAM_NAME_OPTIONS = [
  'Team Nova', 'Team Orbit', 'Team Pulse', 'Team Horizon',
  'Team Avengers', 'Team Rangers', 'Team Catalyst', 'Team Blue',
  'Team Phoenix', 'Team Eclipse', 'Team Summit', 'Team Velocity',
  'Team Aurora', 'Team Zenith', 'Team Voyager', 'Team Vertex',
  'Team Ember', 'Team Atlas'
];

function isLocalStorageAvailable() {
  try {
    var k = '__ceremonies_storage_test__';
    localStorage.setItem(k, '1');
    localStorage.removeItem(k);
    return true;
  } catch (e) { return false; }
}

function getStoredTeamName() {
  if (!isLocalStorageAvailable()) return null;
  var stored = (localStorage.getItem(TEAM_NAME_STORAGE_KEY) || '').trim();
  return stored || null;
}

function ensureTeamNameAssigned() {
  var existing = getStoredTeamName();
  if (existing) return existing;
  if (!isLocalStorageAvailable()) return 'Team';
  var randomName = TEAM_NAME_OPTIONS[Math.floor(Math.random() * TEAM_NAME_OPTIONS.length)];
  localStorage.setItem(TEAM_NAME_STORAGE_KEY, randomName);
  return randomName;
}

/* ------------------------------------------------------------------ *
 * Icon system — inline SVG, Lucide-style.
 * Decorative chrome only; functional glyphs (mood scale, Planning Poker
 * card faces incl. the coffee-break vote) stay as real content.
 * Usage: <span class="ic" data-ic="home" aria-hidden="true"></span>
 * hydrateIcons() runs on load AND is exposed for JS-rendered DOM to
 * re-hydrate after it injects markup (guarded by data-ic-done).
 * ------------------------------------------------------------------ */
var ICONS = {
  'home': '<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"/><path d="M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  'target': '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>',
  'users': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><path d="M16 3.128a4 4 0 0 1 0 7.744"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><circle cx="9" cy="7" r="4"/>',
  'clipboard-list': '<rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/>',
  'user': '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  'arrow-left': '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
  'bar-chart': '<path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
  'layers': '<path d="M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z"/><path d="M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12"/><path d="M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17"/>',
  'search': '<path d="m21 21-4.34-4.34"/><circle cx="11" cy="11" r="8"/>',
  'x': '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  'brain': '<path d="M12 18V5"/><path d="M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4"/><path d="M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5"/><path d="M17.997 5.125a4 4 0 0 1 2.526 5.77"/><path d="M18 18a4 4 0 0 0 2-7.464"/><path d="M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517"/><path d="M6 18a4 4 0 0 1-2-7.464"/><path d="M6.003 5.125a4 4 0 0 0-2.526 5.77"/>',
  'link': '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
  'share': '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" x2="15.42" y1="13.51" y2="17.49"/><line x1="15.41" x2="8.59" y1="6.51" y2="10.49"/>',
  'crown': '<path d="M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.664a1 1 0 0 0 1.516-.294z"/><path d="M5 21h14"/>',
  'moon': '<path d="M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"/>',
  'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
  'rocket': '<path d="M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5"/><path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09"/><path d="M9 12a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.4 22.4 0 0 1-4 2z"/><path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 .05 5 .05"/>',
  'lightbulb': '<path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"/><path d="M9 18h6"/><path d="M10 22h4"/>',
  'star': '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"/>',
  'alert-triangle': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
  'trash': '<path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  'check': '<path d="M20 6 9 17l-5-5"/>',
  'check-circle': '<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>',
  'zap': '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
  'log-out': '<path d="m16 17 5-5-5-5"/><path d="M21 12H9"/><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>',
  'frown': '<circle cx="12" cy="12" r="10"/><path d="M16 16s-1.5-2-4-2-4 2-4 2"/><line x1="9" x2="9.01" y1="9" y2="9"/><line x1="15" x2="15.01" y1="9" y2="9"/>',
  'refresh': '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
  'file-text': '<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
  'thumbs-up': '<path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"/><path d="M7 10v12"/>',
  'pencil': '<path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/>',
  'hand': '<path d="M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2"/><path d="M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2"/><path d="M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/>',
  'sparkle': '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"/><path d="M20 2v4"/><path d="M22 4h-4"/><circle cx="4" cy="20" r="2"/>',
  'smartphone': '<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><path d="M12 18h.01"/>',
  'message-circle': '<path d="M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719"/>',
  'mail': '<path d="m22 7-8.991 5.727a2 2 0 0 1-2.009 0L2 7"/><rect x="2" y="4" width="20" height="16" rx="2"/>',
  'camera': '<path d="M13.997 4a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 1.759-1.048l.489-.904A2 2 0 0 1 10.004 4z"/><circle cx="12" cy="13" r="3"/>',
  'circle-x': '<circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/>',
  'info': '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>'
};

function iconSvg(name) {
  var inner = ICONS && ICONS[name];
  return inner ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
    'aria-hidden="true" focusable="false">' + inner + '</svg>' : '';
}

// Render (or swap) the icon on a single element. Use for JS-driven state
// changes on an already-hydrated placeholder (e.g. copy → check → copy).
function setIcon(el, name) {
  if (!el) return;
  var svg = iconSvg(name);
  if (!svg) return; // unknown name, or ICONS not yet assigned
  el.innerHTML = svg;
  el.setAttribute('data-ic', name);
  el.setAttribute('data-ic-done', '1');
}

function hydrateIcons(root) {
  if (!ICONS) return; // hoisted var not yet assigned (early IIFE call); DOMContentLoaded pass covers it
  var scope = root || document;
  var nodes = scope.querySelectorAll('[data-ic]');
  for (var i = 0; i < nodes.length; i++) {
    var el = nodes[i];
    if (el.getAttribute('data-ic-done') === '1') continue;
    setIcon(el, el.getAttribute('data-ic'));
  }
}

window.hydrateIcons = hydrateIcons;
window.setIcon = setIcon;
document.addEventListener('DOMContentLoaded', function () { hydrateIcons(document); });
