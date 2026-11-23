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

test('không đoạn mã nào gọi hàm gửi lời mời, gửi đính kèm, tìm theo tên, liệt kê bạn bè hay dò người của zca-js', () => {
  const banned = /(sendFriendRequest|sendVoice|sendLink|sendSticker|sendCard|sendVideo|sendImage|sendReport|findUserByUsername|getAllFriends|getFriendRequest|sendTypingEvent|forwardMessage|acceptFriendRequest|getFriendRecommendations|sendBankCard|sendToDoc|sendDeliveredEvent|sendSeenEvent)\b/;
  for (const f of files) {
    assert.ok(!banned.test(code(f)), `${f} có hàm bị cấm`);
  }
});

test('findUser và sendMessage (D18) chỉ nằm trong bộ điều hợp thật, mỗi hàm đúng một chỗ gọi, không ở đâu khác', () => {
  for (const f of files) {
    const c = code(f);
    const find = (c.match(/\bapi\??\.findUser\b/g) ?? []).length;
    const send = (c.match(/\bapi\??\.sendMessage\b/g) ?? []).length;
    if (f === 'zca-adapter.mjs') {
      assert.equal(find, 1, 'findUser phải đúng một lần (findOne dùng chung cho tra số gửi tin và tra mẹ)');
      assert.equal(send, 1, 'sendMessage phải đúng một lần');
    } else {
      assert.equal(find + send, 0, `${f} không được gọi findUser/sendMessage`);
    }
  }
  // chỉ gửi văn bản thuần cho một người, không đính kèm
  assert.match(code('zca-adapter.mjs'), /sendMessage\(\{ msg: text \}, uid, ThreadType\.User\)/);
});

test('bộ điều hợp zca-js chỉ dùng đúng mười hai thành phần của api (thêm hai hàm chỉ đọc nhóm, findUser và sendMessage của D18, ba hàm tạo nhóm của D19)', () => {
  const used = new Set([...code('zca-adapter.mjs').matchAll(/this\.api\??\.([A-Za-z]+)/g)].map((m) => m[1]));
  assert.deepEqual([...used].sort(), ['addGroupDeputy', 'addUserToGroup', 'createGroup', 'enableGroupLink', 'findUser', 'getAllGroups', 'getGroupInfo', 'getOwnId', 'getUserInfo', 'listener', 'logout', 'sendMessage'].sort());
});

test('D19: createGroup, enableGroupLink, addGroupDeputy mỗi hàm đúng một chỗ gọi, chỉ ở bộ điều hợp thật; không đổi chủ nhóm, không giải tán, không đổi tên', () => {
  for (const f of files) {
    const c = code(f);
    for (const fn of ['createGroup', 'enableGroupLink', 'addGroupDeputy']) {
      const n = (c.match(new RegExp(`\\bapi\\??\\.${fn}\\b`, 'g')) ?? []).length;
      assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: ${fn}`);
    }
    assert.ok(!/(changeGroupOwner|disperseGroup|changeGroupName|removeGroupDeputy|removeUserFromGroup|leaveGroup|blockUser|deleteGroup)\b/.test(c), `${f} có hàm nhóm bị cấm`);
  }
});

test('D19: tin nhắn đến chỉ được nghe ở bộ điều hợp thật, chỉ tin riêng văn bản của người khác, và chỉ khi có bộ xử lý (cờ bật)', () => {
  const adapter = code('zca-adapter.mjs');
  assert.equal((adapter.match(/listener\.on\('message'/g) ?? []).length, 1);
  assert.match(adapter, /if \(h\.onMessage\)/);
  assert.match(adapter, /m\.type !== ThreadType\.User \|\| m\.isSelf \|\| typeof m\.data\?\.content !== 'string'/);
  for (const f of files.filter((x) => x !== 'zca-adapter.mjs')) {
    assert.ok(!/listener\.on\('message'/.test(code(f)), `${f} không được nghe tin nhắn`);
  }
  // Bộ nối không có đường nào để trả lời khách ngoài POST /messages (Tin 1 và Tin 2).
  assert.ok(!/getMessageHistory|getArchivedChatList|deleteMessage|undoMessage|sendTypingEvent/.test(files.map(code).join('\n')));
  // nội dung tin không được vào log
  const bridge = code('bridge.mjs');
  const onMessage = bridge.slice(bridge.indexOf('onMessage(gen, uid, text)'), bridge.indexOf('onDisconnected(gen, code)'));
  assert.ok(!/this\.log\(/.test(onMessage));
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
