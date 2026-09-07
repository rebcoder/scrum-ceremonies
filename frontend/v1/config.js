/**
 * Backend endpoint configuration.
 *
 * Default: SAME ORIGIN as the page. Self-hosting Scrum Ceremonies behind one
 * reverse proxy — static files and the Spring Boot API on the same host — needs
 * no configuration at all, and it keeps `frontend/dist/` free of any hardcoded
 * hostname, so the built assets are not tied to anyone's domain.
 *
 * Override when the API lives elsewhere. Two ways:
 *
 *   1. Local development, where the static server (:3000) and the backend (:8080)
 *      are different origins. Create `config.local.js` (gitignored; the tool pages
 *      load it only on localhost, and `scripts/dev-up.sh` writes it for you):
 *
 *        window.APP_CONFIG = {
 *          API_BASE_URL: 'http://localhost:8080',
 *          WS_BASE_URL:  'http://localhost:8080/ws'
 *        };
 *
 *   2. A deployment with a separate API host — edit the defaults below, or set
 *      window.APP_CONFIG before this script loads.
 *
 * WS_BASE_URL is the SockJS endpoint, so it takes http(s), not ws(s); SockJS
 * negotiates the WebSocket upgrade itself.
 */
(function () {
  var origin = (window.location && window.location.origin) || '';

  window.APP_CONFIG = window.APP_CONFIG || {};
  window.APP_CONFIG.API_BASE_URL = window.APP_CONFIG.API_BASE_URL || origin;
  window.APP_CONFIG.WS_BASE_URL = window.APP_CONFIG.WS_BASE_URL || (origin + '/ws');
})();
