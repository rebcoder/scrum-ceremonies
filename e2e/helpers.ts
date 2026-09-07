/** Shared helpers for the e2e specs. */

export function uid(): string {
  return Math.random().toString(36).substring(2, 8);
}

/**
 * Flush the per-IP rate-limit and active-room-cap keys between specs.
 *
 * rooms:session:* is the per-IP active-room cap registry (max 3 in 30min,
 * sessionId == IP for anonymous v1) — every e2e run shares ONE IP, so the
 * v1 journeys are timing-dependent on the cleanup task without this flush
 * (measured: 3-4 of a day's runs flaked exactly here; 1-2 passed on cleanup
 * timing luck).
 */
export async function flushRateLimits(): Promise<void> {
  try {
    const cp = await import('node:child_process');
    // Use EVAL to atomically find and delete all rate limit keys.
    // Use redis-cli from HOST — docker exec connects to a different Redis view.
    cp.execSync(
      `redis-cli -h localhost -p 6379 EVAL "local keys = redis.call('KEYS','rate:*') for _,k in ipairs(keys) do redis.call('DEL',k) end local rk = redis.call('KEYS','rooms:session:*') for _,k in ipairs(rk) do redis.call('DEL',k) end return #keys + #rk" 0`,
      { timeout: 5000 },
    );
  } catch {
    // Ignore — tests can still work with retry logic
  }
}
