/**
 * Frontend runtime config and API connectivity check.
 * Run in browser (after config.js) or in Node for CI:
 *   node frontend/src/tests/runtime-check.js
 *   API_BASE_URL=http://localhost:8080 node frontend/v1/tests/runtime-check.js
 *
 * Backend health endpoint: /actuator/health (not /api/health).
 */
(function runRuntimeCheck() {
  var isNode = typeof process !== 'undefined' && process.versions && process.versions.node;
  var API_BASE_URL;
  var WS_BASE_URL;

  if (isNode) {
    API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
    WS_BASE_URL = process.env.WS_BASE_URL || 'http://localhost:8080/ws';
  } else {
    if (typeof window === 'undefined' || !window.APP_CONFIG) {
      console.error('[runtime-check] FAIL: window.APP_CONFIG is not defined. Load config.js first.');
      if (typeof process !== 'undefined') process.exit(1);
      return;
    }
    API_BASE_URL = window.APP_CONFIG.API_BASE_URL;
    WS_BASE_URL = window.APP_CONFIG.WS_BASE_URL;
  }

  var missing = [];
  if (!API_BASE_URL) missing.push('APP_CONFIG.API_BASE_URL');
  if (!WS_BASE_URL) missing.push('APP_CONFIG.WS_BASE_URL');
  if (missing.length) {
    console.error('[runtime-check] FAIL: Missing config: ' + missing.join(', '));
    if (isNode) process.exit(1);
    return;
  }
  console.log('[runtime-check] API_BASE_URL:', API_BASE_URL);
  console.log('[runtime-check] WS_BASE_URL:', WS_BASE_URL);

  var healthUrl = API_BASE_URL.replace(/\/$/, '') + '/actuator/health';

  function onResult(ok, message) {
    if (ok) {
      console.log('[runtime-check] SUCCESS: ' + message);
      if (isNode) process.exit(0);
    } else {
      console.error('[runtime-check] FAIL: ' + message);
      if (isNode) process.exit(1);
    }
  }

  if (isNode) {
    var https = require('https');
    var parsed = new URL(healthUrl);
    var opts = { hostname: parsed.hostname, port: parsed.port || 443, path: parsed.pathname || '/actuator/health', method: 'GET' };
    var req = https.request(opts, function (res) {
      var statusOk = res.statusCode === 200 || res.statusCode === 503;
      onResult(statusOk, 'GET ' + healthUrl + ' -> ' + res.statusCode);
    });
    req.on('error', function (err) {
      onResult(false, 'GET ' + healthUrl + ' error: ' + err.message);
    });
    req.setTimeout(10000, function () {
      req.destroy();
      onResult(false, 'GET ' + healthUrl + ' timeout');
    });
    req.end();
  } else {
    fetch(healthUrl, { method: 'GET', mode: 'cors' })
      .then(function (res) {
        var statusOk = res.status === 200 || res.status === 503;
        onResult(statusOk, 'GET ' + healthUrl + ' -> ' + res.status);
      })
      .catch(function (err) {
        onResult(false, 'GET ' + healthUrl + ' error: ' + (err.message || String(err)));
      });
  }
})();
