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

test('không đoạn mã nào gọi hàm gửi đính kèm, tìm theo tên, dò người (getAllFriends có test khóa riêng ở dưới: chỉ một chỗ, chỉ đọc) của zca-js', () => {
  const banned = /(sendVoice|sendLink|sendSticker|sendCard|sendVideo|sendImage|sendReport|findUserByUsername|getFriendRequest|sendTypingEvent|forwardMessage|getFriendRecommendations|sendBankCard|sendToDoc|sendDeliveredEvent|sendSeenEvent)\b/;
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
  assert.match(code('zca-adapter.mjs'), /sendMessage\(\{ msg: text \}, id, group \? ThreadType\.Group : ThreadType\.User\)/);
});

test('bộ điều hợp zca-js chỉ dùng các thành phần đã khai báo', () => {
  const used = new Set([...code('zca-adapter.mjs').matchAll(/this\.api\??\.([A-Za-z]+)/g)].map((m) => m[1]));
  assert.deepEqual([...used].sort(), ['acceptFriendRequest', 'addGroupDeputy', 'addReaction', 'addUserToGroup', 'createGroup', 'enableGroupLink', 'findUser', 'getAllFriends', 'getAllGroups', 'getFriendRequestStatus', 'getGroupInfo', 'getGroupLinkDetail', 'getOwnId', 'getSentFriendRequest', 'getUserInfo', 'listener', 'logout', 'sendFriendRequest', 'sendMessage', 'undoFriendRequest'].sort());
});

test('chỉ đọc link nhóm đang tham gia ở một chỗ, không chuyển danh sách thành viên', () => {
  const adapter = code('zca-adapter.mjs');
  assert.equal((adapter.match(/\bapi\.getGroupLinkDetail\b/g) ?? []).length, 1);
  assert.match(adapter, /if \(!Object\.hasOwn\(membership, groupId\)\) return \{ present: false \}/);
  assert.match(adapter, /return \{ \.\.\.evidence, inviteLink: detail\?\.enabled === 1 \? detail\.link : null \}/);
});

test('sendFriendRequest chỉ có đúng một chỗ gọi (bộ điều hợp thật), chỉ gọi từ Bridge.sendFriendRequest và chỉ khi người đó đã nhận Tin 1', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.sendFriendRequest\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: sendFriendRequest`);
  }
  const b = code('bridge.mjs');
  assert.equal((b.match(/adapter\.sendFriendRequest\(/g) ?? []).length, 1);
  // chỉ người đã nhận Tin 1, hoặc người có Tin 1 bị Zalo chặn vì không nhận tin người lạ (Z122): không có đường nào khác tới lời mời kết bạn
  assert.match(b, /if \(!this\.msgLedger\.has\('INVITE', uid\) && !this\.msgLedger\.has\('INVITE_BLOCKED', uid\)\) return \{ outcome: 'PERSON_ERROR', code: 'NO_INVITE' \}/);
  assert.equal((b.match(/markSent\('INVITE_BLOCKED'/g) ?? []).length, 1);
  assert.match(b, /this\.friendLedger\.has\('FRIEND', uid\)/);
  assert.ok(!/\b(?:getFriendRequest|getReceivedFriendRequests)\b/.test(files.map(code).join('\n')));
});

test('getSentFriendRequest chỉ có một đường đọc, không có endpoint trả danh sách', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.getSentFriendRequest\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: getSentFriendRequest`);
  }
  const bridge = code('bridge.mjs');
  assert.equal((bridge.match(/adapter\.hasSentFriendRequest\(uid\)/g) ?? []).length, 1);
  assert.match(code('zca-adapter.mjs'), /Object\.hasOwn\(requests, uid\) && requests\[uid\]\?\.userId === uid/);
  assert.ok(!code('server.mjs').includes('hasSentFriendRequest'));
});

test('getFriendRequestStatus chỉ đọc một mã và chỉ có một đường gọi qua cầu nối', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.getFriendRequestStatus\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: getFriendRequestStatus`);
  }
  assert.equal((code('bridge.mjs').match(/adapter\.friendRequestStatus\(/g) ?? []).length, 1);
  assert.match(code('zca-adapter.mjs'), /async friendRequestStatus\(uid\) \{\s*return this\.api\.getFriendRequestStatus\(uid\);/);
});

test('acceptFriendRequest (nhận lời mời) chỉ có đúng một chỗ gọi (bộ điều hợp thật), chỉ từ Bridge.acceptFriendRequest và chỉ cho người đã nhận Tin 1 vừa tự gửi lời mời tới', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.acceptFriendRequest\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: acceptFriendRequest`);
  }
  const b = code('bridge.mjs');
  assert.equal((b.match(/adapter\.acceptFriendRequest\(/g) ?? []).length, 1);
  assert.match(b, /if \(!this\.incoming\.has\(uid\) \|\| !this\.msgLedger\.has\('INVITE', uid\) && !this\.msgLedger\.has\('INVITE_BLOCKED', uid\)\) return \{ outcome: 'PERSON_ERROR', code: 'NO_REQUEST' \}/);
  assert.match(b, /this\.acceptLedger\.has\('ACCEPT', uid\)/);
  assert.ok(!/Math\.random|crypto\.randomInt/.test(b));
});

test('undoFriendRequest (thu hồi lời mời) chỉ có đúng một chỗ gọi (bộ điều hợp thật), chỉ từ Bridge.undoFriendRequest và chỉ cho lời mời chính cầu nối đã gửi', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.undoFriendRequest\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: undoFriendRequest`);
  }
  const b = code('bridge.mjs');
  assert.equal((b.match(/adapter\.undoFriendRequest\(/g) ?? []).length, 1);
  assert.match(b, /if \(!this\.friendLedger\.has\('FRIEND', uid\)\) return \{ outcome: 'PERSON_ERROR', code: 'NOT_REQUESTED' \}/);
  assert.match(b, /this\.undoLedger\.has\('UNDO', uid\)/);
  assert.ok(!/Math\.random|crypto\.randomInt/.test(b));
});

test('đặt phó nhóm chỉ có một chỗ gọi trong bridge.mjs (#makeDeputy, dùng cho createGroup và setDeputy), không có hàm gỡ phó nhóm', () => {
  const b = code('bridge.mjs');
  assert.equal((b.match(/adapter\.addGroupDeputy\(/g) ?? []).length, 1);
  assert.equal((b.match(/#makeDeputy\(/g) ?? []).length, 3); // một định nghĩa, hai chỗ gọi
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
  const adapter = code('zca-adapter.mjs').split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n');
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

test('D19/D20: tin WELCOME và REPLY chỉ theo mã Zalo, cần cờ đọc tin riêng, chỉ trả lời người vừa nhắn tới và mỗi tin đến chỉ một lần', () => {
  const b = code('bridge.mjs');
  assert.match(b, /const isReply = kind === 'WELCOME' \|\| kind === 'REPLY'/);
  assert.match(b, /isReply && \(uidIn === null \|\| !this\.config\.inboxEnabled\)/);
  assert.match(b, /NOT_REPLYABLE/);
  assert.equal((b.match(/this\.replyable\.delete\(uidIn\)/g) ?? []).length, 1);
  assert.equal((b.match(/this\.replyable\.set\(/g) ?? []).length, 1, 'chỉ tin đến mới mở được đường trả lời');
});

test('getAllFriends (đối chiếu bạn bè, chỉ đọc) có đúng một chỗ gọi ở bộ điều hợp thật và chỉ được dùng từ Bridge.checkFriends, không trả tên hay số', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.getAllFriends\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: getAllFriends`);
  }
  const b = code('bridge.mjs');
  assert.equal((b.match(/adapter\.friendUids\(/g) ?? []).length, 1);
  assert.match(code('zca-adapter.mjs'), /async friendUids\(\) \{[^}]*String\(u\?\.userId/s);
  assert.ok(!/displayName|phoneNumber|avatar/.test(code('zca-adapter.mjs').split('async friendUids()')[1].split('loginQR')[0]));
});

test('addReaction (thả tim) chỉ có một chỗ gọi ở bộ điều hợp thật, chỉ thả Reactions.HEART vào tin riêng, chỉ từ Bridge.react', () => {
  for (const f of files) {
    const n = (code(f).match(/\bapi\??\.addReaction\b/g) ?? []).length;
    assert.equal(n, f === 'zca-adapter.mjs' ? 1 : 0, `${f}: addReaction`);
  }
  assert.match(code('zca-adapter.mjs'), /addReaction\(Reactions\.HEART, .*type: ThreadType\.User/);
  assert.equal((code('bridge.mjs').match(/adapter\.addHeart\(/g) ?? []).length, 1);
});
