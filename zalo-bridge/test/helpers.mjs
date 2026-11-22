import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Bridge } from '../src/bridge.mjs';
import { EventBuffer } from '../src/events.mjs';
import { AddLedger } from '../src/guard.mjs';
import { SessionStore } from '../src/session-store.mjs';
import { FakeAdapter } from '../src/fake-adapter.mjs';
import { loadConfig } from '../src/config.mjs';

export const KEY = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

// 2026-11-22 10:00 giờ Việt Nam (03:00 UTC): trong khung giờ.
export const T0 = Date.parse('2026-11-22T03:00:00Z');

export function makeEnv(dir, extra = {}) {
  return {
    BRIDGE_KEY: 'khoa-chung-cho-test-1234',
    SESSION_KEY: KEY,
    ZALO_ACCOUNT_ID: '1000',
    BRIDGE_DATA_DIR: dir,
    BRIDGE_ADD_TIMEOUT_MS: '1000',
    ...extra,
  };
}

export function setup({ env = {}, adapterOpts = {}, start = true } = {}) {
  const dir = mkdtempSync(path.join(tmpdir(), 'bridge-'));
  const config = loadConfig(makeEnv(dir, env));
  const adapter = new FakeAdapter({ ownId: '1000', ...adapterOpts });
  const clock = { t: T0 };
  const logs = [];
  const build = () =>
    new Bridge({
      config,
      adapter,
      store: new SessionStore(path.join(dir, 'session.enc'), config.sessionKey),
      ledger: new AddLedger(path.join(dir, 'adds.json')),
      events: new EventBuffer({ now: () => clock.t }),
      now: () => clock.t,
      log: (m) => logs.push(m),
    });
  const bridge = build();
  return { dir, config, adapter, clock, logs, bridge, rebuild: build, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

export const flush = () => new Promise((r) => setImmediate(r));

/** Đăng nhập QR giả cho tới khi nghe. */
export async function loginAndListen(ctx) {
  ctx.bridge.startLogin();
  ctx.adapter.loginResolve();
  await flush();
  await flush();
  await flush();
}
