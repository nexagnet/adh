import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const src = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src');
const files = readdirSync(src).filter((f) => f.endsWith('.mjs'));

/** Bỏ chú thích để chỉ kiểm mã thật. */
function code(file) {
  return readFileSync(path.join(src, file), 'utf8')
    .split('\n')
    .map((l) => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

test('không đoạn mã nào gọi hàm gửi tin, gửi lời mời, tìm người hay liệt kê bạn bè của zca-js', () => {
  const banned = /(sendMessage|sendFriendRequest|sendVoice|sendLink|sendSticker|sendCard|sendVideo|sendImage|sendReport|findUser|findUserByUsername|getAllFriends|getFriendRequest|sendTypingEvent|forwardMessage|acceptFriendRequest|getFriendRecommendations)\b/;
  for (const f of files) {
    assert.ok(!banned.test(code(f)), `${f} có hàm bị cấm`);
  }
});

test('bộ điều hợp zca-js chỉ dùng đúng năm thành phần của api', () => {
  const used = new Set([...code('zca-adapter.mjs').matchAll(/this\.api\??\.([A-Za-z]+)/g)].map((m) => m[1]));
  assert.deepEqual([...used].sort(), ['addUserToGroup', 'getOwnId', 'getUserInfo', 'listener', 'logout'].sort());
});

test('không đặt userAgent, không proxy, không đổi dấu vân tay (không che việc dùng thư viện)', () => {
  const adapter = code('zca-adapter.mjs');
  assert.ok(!/userAgent\s*:\s*['"`]/.test(adapter));
  assert.ok(!/\bagent\b|proxy|imei\s*:\s*['"`]/i.test(adapter.replace(/creds\.imei|ev\.data\.imei/g, '')));
  assert.ok(!/Math\.random|randomInt|jitter/.test(files.map(code).join('\n').replace(/randomBytes/g, '')));
});

test('log chỉ nhận thông điệp ngắn, không truyền uid/phone/name', () => {
  for (const f of files) {
    const c = code(f);
    for (const m of c.matchAll(/this\.log\(([^)]*)\)/g)) {
      assert.ok(!/uid|phone|name|groupId/i.test(m[1]), `${f}: log(${m[1]})`);
    }
  }
});
