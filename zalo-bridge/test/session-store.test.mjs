import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { SessionStore, encryptJson, decryptJson } from '../src/session-store.mjs';

const key = Buffer.alloc(32, 7);
const session = { imei: 'IMEI-12345-SECRET', cookie: [{ name: 'zpw_sek', value: 'COOKIE-VALUE-SECRET' }], userAgent: 'UA-SECRET' };

test('mã hóa rồi giải mã lại đúng', () => {
  assert.deepEqual(decryptJson(key, encryptJson(key, session)), session);
});

test('tệp phiên không chứa chuỗi dạng rõ nào của phiên, quyền 600', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'sess-'));
  const file = path.join(dir, 'session.enc');
  new SessionStore(file, key).save(session);
  const text = readFileSync(file, 'utf8');
  for (const secret of ['IMEI-12345-SECRET', 'COOKIE-VALUE-SECRET', 'UA-SECRET', 'zpw_sek', 'cookie', 'imei']) {
    assert.ok(!text.includes(secret), `lộ ${secret}`);
  }
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.deepEqual(new SessionStore(file, key).load().session, session);
});

test('sai khóa, bị sửa, hoặc hỏng thì coi là hỏng, không trả phiên', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'sess-'));
  const file = path.join(dir, 'session.enc');
  new SessionStore(file, key).save(session);
  assert.equal(new SessionStore(file, Buffer.alloc(32, 8)).load().corrupt, true);
  const o = JSON.parse(readFileSync(file, 'utf8'));
  o.data = Buffer.from('x'.repeat(10)).toString('base64');
  writeFileSync(file, JSON.stringify(o));
  assert.equal(new SessionStore(file, key).load().corrupt, true);
  writeFileSync(file, 'không phải json');
  assert.equal(new SessionStore(file, key).load().corrupt, true);
});

test('chưa có tệp thì không có phiên và không hỏng', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'sess-'));
  assert.deepEqual(new SessionStore(path.join(dir, 'none.enc'), key).load(), { session: null });
});

test('mỗi lần mã hóa dùng iv khác nhau', () => {
  assert.notEqual(encryptJson(key, session), encryptJson(key, session));
});
