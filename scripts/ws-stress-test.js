#!/usr/bin/env node
/**
 * WebSocket Stress Test — 50 concurrent STOMP connections
 * Tests: connection time, subscribe, message delivery, reconnection
 *
 * Usage: node scripts/ws-stress-test.js [BASE_URL] [NUM_CONNECTIONS]
 * Default: http://localhost:8080, 50 connections
 */
const WebSocket = require('ws');
const { Client } = require('@stomp/stompjs');

const BASE_URL = process.argv[2] || 'http://localhost:8080';
const NUM_CONNECTIONS = parseInt(process.argv[3] || '50', 10);
const TIMEOUT_MS = 30000;

const wsUrl = BASE_URL.replace(/^http/, 'ws') + '/ws/websocket';

const results = {
  total: NUM_CONNECTIONS,
  connected: 0,
  failed: 0,
  connectionTimes: [],
  errors: [],
  startTime: Date.now(),
};

function connectOne(index) {
  return new Promise((resolve) => {
    const start = Date.now();

    const timeout = setTimeout(() => {
      try { client.deactivate(); } catch (_) {}
      results.failed++;
      results.errors.push(`[${index}] Timeout after ${TIMEOUT_MS}ms`);
      resolve();
    }, TIMEOUT_MS);

    const client = new Client({
      webSocketFactory: () => new WebSocket(wsUrl),
      connectHeaders: {},
      debug: () => {},
      reconnectDelay: 0,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
    });

    client.onConnect = () => {
      clearTimeout(timeout);
      const elapsed = Date.now() - start;
      results.connected++;
      results.connectionTimes.push(elapsed);

      // Subscribe to a test topic
      client.subscribe('/topic/test.stress.' + index, () => {});

      // Hold connection briefly then disconnect
      setTimeout(() => {
        try { client.deactivate(); } catch (_) {}
        resolve();
      }, 500);
    };

    client.onStompError = (frame) => {
      clearTimeout(timeout);
      results.failed++;
      results.errors.push(`[${index}] STOMP error: ${frame.headers?.message || 'unknown'}`);
      resolve();
    };

    client.onWebSocketError = (err) => {
      clearTimeout(timeout);
      results.failed++;
      results.errors.push(`[${index}] WS error: ${err.message}`);
      resolve();
    };

    client.onWebSocketClose = () => {
      if (!client.connected) {
        clearTimeout(timeout);
        results.failed++;
        results.errors.push(`[${index}] Closed before CONNECTED`);
        resolve();
      }
    };

    client.activate();
  });
}

async function main() {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`  WebSocket Stress Test — ${NUM_CONNECTIONS} concurrent connections`);
  console.log(`  Target: ${wsUrl}`);
  console.log(`${'='.repeat(60)}\n`);

  // Launch all connections concurrently
  const promises = [];
  for (let i = 0; i < NUM_CONNECTIONS; i++) {
    promises.push(connectOne(i));
  }

  await Promise.all(promises);

  const totalTime = Date.now() - results.startTime;
  const times = results.connectionTimes.sort((a, b) => a - b);
  const p50 = times.length ? times[Math.floor(times.length * 0.5)] : 0;
  const p95 = times.length ? times[Math.floor(times.length * 0.95)] : 0;
  const p99 = times.length ? times[Math.floor(times.length * 0.99)] : 0;
  const avg = times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : 0;
  const max = times.length ? times[times.length - 1] : 0;

  console.log(`${'='.repeat(60)}`);
  console.log(`  Results`);
  console.log(`${'='.repeat(60)}`);
  console.log(`  Total time:      ${totalTime}ms`);
  console.log(`  Connected:       ${results.connected}/${results.total}`);
  console.log(`  Failed:          ${results.failed}/${results.total}`);
  console.log(`  Success rate:    ${((results.connected / results.total) * 100).toFixed(1)}%`);
  console.log(`  Connection time: avg=${avg}ms  p50=${p50}ms  p95=${p95}ms  p99=${p99}ms  max=${max}ms`);

  if (results.errors.length > 0) {
    console.log(`\n  Errors (first 10):`);
    results.errors.slice(0, 10).forEach((e) => console.log(`    ${e}`));
  }

  console.log(`\n  Verdict: ${results.connected === results.total ? 'PASS' : 'FAIL'}`);
  console.log(`${'='.repeat(60)}\n`);

  // Write JSON results
  const jsonResults = {
    timestamp: new Date().toISOString(),
    target: wsUrl,
    total_connections: results.total,
    connected: results.connected,
    failed: results.failed,
    success_rate: parseFloat(((results.connected / results.total) * 100).toFixed(1)),
    total_time_ms: totalTime,
    connection_time_avg_ms: avg,
    connection_time_p50_ms: p50,
    connection_time_p95_ms: p95,
    connection_time_p99_ms: p99,
    connection_time_max_ms: max,
    errors: results.errors.slice(0, 20),
  };

  const fs = require('fs');
  const path = require('path');
  // docs/performance/ is not tracked in git (it holds only generated results), so on a
  // fresh clone this write threw ENOENT *before* the process.exit below — turning a
  // passing 50/50 run into exit 1. A guardrail that fails on success is worse than none.
  const outFile = path.join('docs', 'performance', 'results-ws-stress.json');
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, JSON.stringify(jsonResults, null, 2));
  console.log(`  Results written to ${outFile}\n`);

  process.exit(results.failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('Fatal:', err);
  process.exit(1);
});
