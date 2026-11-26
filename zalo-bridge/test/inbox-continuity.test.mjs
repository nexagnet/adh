import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { InboxBuffer } from '../src/events.mjs';

function fixture(t, overrides = {}) {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'adh-inbox-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const clock = { time: 1000 };
  const config = { now: () => clock.time, file: path.join(dir, 'inbox.enc'), key: Buffer.alloc(32, 7), ...overrides };
  return { clock, config, inbox: new InboxBuffer(config) };
}

test('inbox records an unprocessed gap when its size limit discards older messages', (t) => {
  const { inbox, clock } = fixture(t);
  for (let i = 0; i < 301; i++) inbox.push({ uid: '1', text: 'test' });
  assert.equal(inbox.after(0).length, 300);
  assert.ok(inbox.lossAt >= clock.time);
  assert.equal(inbox.caughtUp(), false);
  inbox.after(inbox.last);
  assert.equal(inbox.caughtUp(), true);
});

test('acknowledged messages survive an app cursor rollback as a recorded gap', (t) => {
  const { inbox, config, clock } = fixture(t);
  const seq = inbox.push({ uid: '1', text: 'test' });
  inbox.after(seq);
  const restored = new InboxBuffer(config);
  clock.time += 100;
  assert.deepEqual(restored.after(0), []);
  assert.equal(restored.lossAt, clock.time);
});

test('invalid inbox disk file starts a new coverage window, and failed persistence blocks readiness', (t) => {
  const { config, clock } = fixture(t);
  writeFileSync(config.file, 'invalid ciphertext');
  const reopened = new InboxBuffer(config);
  assert.equal(reopened.lossAt, clock.time);
  assert.deepEqual(reopened.after(0), []);
  assert.equal(reopened.caughtUp(), true);

  const badPath = new InboxBuffer({ ...config, file: path.join(config.file, 'inbox.enc') });
  badPath.push({ uid: '1', text: 'test' });
  badPath.after(badPath.last);
  assert.equal(badPath.caughtUp(), false);
});
