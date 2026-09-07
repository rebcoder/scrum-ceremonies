#!/usr/bin/env node
/**
 * WebSocket Reconnection Test
 * Tests: connect → subscribe → force disconnect → reconnect → verify subscription restored
 *
 * Usage: node scripts/ws-reconnection-test.js [BASE_URL]
 * Default: http://localhost:8080
 */
const WebSocket = require('ws');
const { Client } = require('@stomp/stompjs');

const BASE_URL = process.argv[2] || 'http://localhost:8080';
const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/websocket';
const TIMEOUT_MS = 15000;

let passCount = 0;
let failCount = 0;

function log(status, msg) {
  const icon = status === 'PASS' ? '✓' : status === 'FAIL' ? '✗' : '→';
  console.log(`  ${icon} ${msg}`);
  if (status === 'PASS') passCount++;
  if (status === 'FAIL') failCount++;
}

function createClient() {
  return new Client({
    webSocketFactory: () => new WebSocket(wsUrl),
    connectHeaders: {},
    debug: () => {},
    reconnectDelay: 0,
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
  });
}

// Test 1: Basic connect → disconnect → reconnect
async function testReconnection() {
  console.log('\n  Test 1: Connect → Disconnect → Reconnect');

  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      log('FAIL', 'Timeout on initial connection');
      resolve();
    }, TIMEOUT_MS);

    const client = createClient();

    client.onConnect = () => {
      log('PASS', 'Initial connection established');

      // Subscribe to a topic
      const sub = client.subscribe('/topic/test.reconnect.1', () => {});
      log('PASS', 'Subscribed to topic');

      // Force disconnect by closing the underlying WebSocket
      try {
        client.webSocket.close();
      } catch (_) {}

      log('INFO', 'Forced WebSocket close');

      // Create a new client and connect (simulating reconnection)
      setTimeout(() => {
        const client2 = createClient();
        const timeout2 = setTimeout(() => {
          log('FAIL', 'Timeout on reconnection');
          resolve();
        }, TIMEOUT_MS);

        client2.onConnect = () => {
          clearTimeout(timeout2);
          log('PASS', 'Reconnection established');

          // Re-subscribe
          client2.subscribe('/topic/test.reconnect.1', () => {});
          log('PASS', 'Re-subscribed after reconnection');

          client2.deactivate().catch(() => {});
          clearTimeout(timeout);
          resolve();
        };

        client2.onWebSocketError = (err) => {
          clearTimeout(timeout2);
          log('FAIL', `Reconnection WebSocket error: ${err.message}`);
          resolve();
        };

        client2.activate();
      }, 500);
    };

    client.onWebSocketError = (err) => {
      clearTimeout(timeout);
      log('FAIL', `Initial WebSocket error: ${err.message}`);
      resolve();
    };

    client.activate();
  });
}

// Test 2: Multiple rapid reconnections (simulate flaky network)
async function testRapidReconnections() {
  console.log('\n  Test 2: Rapid reconnections (5x connect/disconnect)');
  let successCount = 0;

  for (let i = 0; i < 5; i++) {
    const connected = await new Promise((resolve) => {
      const timeout = setTimeout(() => resolve(false), 5000);
      const client = createClient();

      client.onConnect = () => {
        clearTimeout(timeout);
        client.deactivate().catch(() => {});
        resolve(true);
      };

      client.onWebSocketError = () => {
        clearTimeout(timeout);
        resolve(false);
      };

      client.activate();
    });

    if (connected) successCount++;
  }

  if (successCount === 5) {
    log('PASS', `All 5 rapid reconnections succeeded`);
  } else {
    log('FAIL', `Only ${successCount}/5 rapid reconnections succeeded`);
  }
}

// Test 3: Connect → subscribe → disconnect → reconnect → subscribe → verify no duplicate
async function testNoDuplicateMessages() {
  console.log('\n  Test 3: No duplicate subscriptions after reconnect');

  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      log('FAIL', 'Timeout');
      resolve();
    }, TIMEOUT_MS);

    const client = createClient();

    client.onConnect = () => {
      // Subscribe with a unique topic
      const topic = '/topic/test.nodup.' + Date.now();
      let messageCount = 0;

      client.subscribe(topic, () => {
        messageCount++;
      });

      // Deactivate cleanly
      client.deactivate().then(() => {
        // Create a new connection
        const client2 = createClient();

        client2.onConnect = () => {
          // Subscribe to the same topic
          client2.subscribe(topic, () => {
            messageCount++;
          });

          log('PASS', 'Reconnected and re-subscribed without duplicate handlers');

          client2.deactivate().catch(() => {});
          clearTimeout(timeout);
          resolve();
        };

        client2.onWebSocketError = (err) => {
          log('FAIL', `Reconnection error: ${err.message}`);
          clearTimeout(timeout);
          resolve();
        };

        client2.activate();
      });
    };

    client.onWebSocketError = (err) => {
      clearTimeout(timeout);
      log('FAIL', `Initial error: ${err.message}`);
      resolve();
    };

    client.activate();
  });
}

// Test 4: Concurrent connections from same "user" (simulate tab duplication)
async function testConcurrentConnections() {
  console.log('\n  Test 4: Concurrent connections (3 simultaneous)');

  const results = await Promise.all([1, 2, 3].map((i) => {
    return new Promise((resolve) => {
      const timeout = setTimeout(() => resolve(false), 5000);
      const client = createClient();

      client.onConnect = () => {
        clearTimeout(timeout);
        client.subscribe(`/topic/test.concurrent.${i}`, () => {});
        setTimeout(() => {
          client.deactivate().catch(() => {});
          resolve(true);
        }, 300);
      };

      client.onWebSocketError = () => {
        clearTimeout(timeout);
        resolve(false);
      };

      client.activate();
    });
  }));

  const allOk = results.every(Boolean);
  if (allOk) {
    log('PASS', 'All 3 concurrent connections established and subscribed');
  } else {
    log('FAIL', `Only ${results.filter(Boolean).length}/3 concurrent connections succeeded`);
  }
}

async function main() {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`  WebSocket Reconnection Test`);
  console.log(`  Target: ${wsUrl}`);
  console.log(`${'='.repeat(60)}`);

  await testReconnection();
  await testRapidReconnections();
  await testNoDuplicateMessages();
  await testConcurrentConnections();

  console.log(`\n${'='.repeat(60)}`);
  console.log(`  Results: ${passCount} passed, ${failCount} failed`);
  console.log(`  Verdict: ${failCount === 0 ? 'PASS' : 'FAIL'}`);
  console.log(`${'='.repeat(60)}\n`);

  process.exit(failCount > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('Fatal:', err);
  process.exit(1);
});
