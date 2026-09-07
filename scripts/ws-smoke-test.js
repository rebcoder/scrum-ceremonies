#!/usr/bin/env node
/**
 * WebSocket handshake smoke test: connect to API_BASE_URL/ws, STOMP CONNECT, expect CONNECTED.
 * Exit 0 on success, 1 on failure. No UI.
 * Defaults to the local backend; override for another host:
 *   API_BASE_URL=https://api.example.com node scripts/ws-smoke-test.js
 */
const WebSocket = require('ws');
const { Client } = require('@stomp/stompjs');

const base = process.env.API_BASE_URL || 'http://localhost:8080';
// A single regex with a callback picks ws:// vs wss:// based on whether the input was
// http:// or https://; the two branches can't overlap by construction, unlike a chain of
// sequential .replace() calls where an earlier pattern (e.g. one matching `https?`) can
// already consume the plain http:// case and leave a later, more specific replacement
// unreachable — silently forcing every URL onto the TLS scheme even for a plaintext host.
//
// The path must be `/ws/websocket`, not `/ws`. WebSocketConfig registers the endpoint
// `.withSockJS()`, so `/ws` is the SockJS base path, not a raw WebSocket endpoint — a raw
// WebSocket handshake against it is refused with HTTP 400 and no CONNECTED frame ever
// arrives:
//
//     ws://localhost:8080/ws            -> ERROR Unexpected server response: 400
//     ws://localhost:8080/ws/websocket  -> OPEN
const wsUrl =
  base.replace(/^http(s)?:\/\//, (_m, secure) => (secure ? 'wss://' : 'ws://')).replace(/\/$/, '') +
  '/ws/websocket';

function main() {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => {
      client.deactivate().catch(() => {});
      console.error('FAIL: Timeout waiting for CONNECTED');
      resolve(1);
    }, 15000);

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
      client.deactivate().catch(() => {});
      console.log('OK: STOMP CONNECTED');
      console.log('WebSocket: PASS');
      resolve(0);
    };
    client.onStompError = (frame) => {
      clearTimeout(timeout);
      console.error('FAIL: STOMP error', frame.headers?.message || frame);
      resolve(1);
    };
    client.onWebSocketError = (err) => {
      clearTimeout(timeout);
      console.error('FAIL: WebSocket error', err.message);
      resolve(1);
    };
    client.onWebSocketClose = () => {
      if (!client.connected) {
        clearTimeout(timeout);
        console.error('FAIL: WebSocket closed before CONNECTED');
        resolve(1);
      }
    };

    client.activate();
  });
}

main().then((code) => process.exit(code));
