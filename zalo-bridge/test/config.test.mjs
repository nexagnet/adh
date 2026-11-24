import test from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig, ConfigError } from '../src/config.mjs';
import { makeEnv } from './helpers.mjs';

test('thiếu khóa chung hoặc khóa phiên thì không khởi động', () => {
  assert.throws(() => loadConfig({ ...makeEnv('/tmp/x'), BRIDGE_KEY: 'ngan' }), ConfigError);
  assert.throws(() => loadConfig({ ...makeEnv('/tmp/x'), SESSION_KEY: '' }), /SESSION_KEY/);
  assert.throws(() => loadConfig({ ...makeEnv('/tmp/x'), SESSION_KEY: 'zz'.repeat(32) }), ConfigError);
});

test('chế độ thật phải khai báo mã tài khoản phụ', () => {
  assert.throws(() => loadConfig({ ...makeEnv('/tmp/x'), ZALO_ACCOUNT_ID: '' }), /ZALO_ACCOUNT_ID/);
  assert.throws(() => loadConfig({ ...makeEnv('/tmp/x'), ZALO_ACCOUNT_ID: 'abc' }), ConfigError);
  assert.doesNotThrow(() => loadConfig({ ...makeEnv('/tmp/x'), BRIDGE_MODE: 'fake', ZALO_ACCOUNT_ID: '' }));
});

test('trần cứng của cầu nối gấp đôi trần của Adh; khung giờ 07:00-22:00', () => {
  const c = loadConfig(makeEnv('/tmp/x'));
  assert.equal(c.hardDaily, 600);
  assert.equal(c.windowStartMin, 7 * 60);
  assert.equal(c.windowEndMin, 22 * 60);
  assert.equal(loadConfig({ ...makeEnv('/tmp/x'), APP_ZALO_AUTO_MAX_DAILY: '30' }).hardDaily, 60);
});

test('mặc định không coi mã lỗi nào là lỗi riêng người', () => {
  assert.equal(loadConfig(makeEnv('/tmp/x')).personErrorCodes.size, 0);
  assert.ok(loadConfig({ ...makeEnv('/tmp/x'), BRIDGE_PERSON_ERROR_CODES: '240, 241' }).personErrorCodes.has('241'));
  assert.equal(loadConfig(makeEnv('/tmp/x')).groupErrorCodes.size, 0);
  assert.ok(loadConfig({ ...makeEnv('/tmp/x'), BRIDGE_GROUP_ERROR_CODES: '178, 270' }).groupErrorCodes.has('270'));
});
