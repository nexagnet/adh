import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, ROUTES } from '../src/server.mjs';
import { setup, loginAndListen, flush } from './helpers.mjs';

async function boot(opts) {
  const c = setup(opts);
  await c.bridge.start();
  await loginAndListen(c);
  const server = createServer({ bridge: c.bridge, config: c.config });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, path, { body, key = c.config.key, accountTag } = {}) => {
    const r = await fetch(base + path, {
      method,
      headers: {
        ...(key ? { 'X-Bridge-Key': key } : {}),
        ...(accountTag !== undefined ? { 'X-Bridge-Account-Tag': accountTag } : {}),
        'Content-Type': 'application/json',
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: r.status, json: await r.json() };
  };
  return { c, server, call, close: () => new Promise((r) => server.close(r)) };
}

test('/healthz không cần khóa và không lộ thông tin; mọi đường khác cần khóa', async () => {
  const s = await boot();
  assert.deepEqual((await s.call('GET', '/healthz', { key: '' })).json, { ok: true });
  for (const [m, p] of [['GET', '/status'], ['GET', '/events'], ['POST', '/groups/1/members'], ['POST', '/session/stop']]) {
    assert.equal((await s.call(m, p, { key: '' })).status, 401);
    assert.equal((await s.call(m, p, { key: 'sai-khoa-sai-khoa' })).status, 401);
  }
  await s.close();
});

test('dấu tài khoản khác bị từ chối trước khi đổi phiên', async () => {
  const s = await boot();
  try {
    const tag = (await s.call('GET', '/status')).json.accountTag;
    assert.match(tag, /^[0-9a-f]{12}$/);
    const wrong = await s.call('POST', '/session/stop', { accountTag: '000000000000' });
    assert.deepEqual(wrong, { status: 409, json: { error: 'account_mismatch' } });
    assert.equal((await s.call('GET', '/status')).json.state, 'LISTENING');
    assert.equal((await s.call('POST', '/session/stop', { accountTag: tag })).json.ok, true);
  } finally {
    await s.close();
  }
});

test('trạng thái, sự kiện và thêm người theo đúng hợp đồng', async () => {
  const s = await boot();
  const st = await s.call('GET', '/status');
  assert.equal(st.json.state, 'LISTENING');
  assert.equal(st.json.accountOk, true);
  s.c.adapter.emitFriendAdd('555', '84900000001');
  await flush(); await flush();
  const ev = await s.call('GET', '/events?after=0');
  assert.equal(ev.json.events.length, 1);
  assert.deepEqual(Object.keys(ev.json.events[0]).sort(), ['at', 'phone', 'seq', 'uid']);
  const add = await s.call('POST', '/groups/2002/members', { body: { uid: '555' } });
  assert.deepEqual(add.json, { outcome: 'OK' });
  assert.deepEqual(s.c.adapter.adds, [{ uid: '555', groupId: '2002' }]);
  await s.close();
});

test('đọc trạng thái lời mời chỉ cho mã đã nhận Tin 1 của đúng tài khoản', async () => {
  const s = await boot();
  try {
    const tag = (await s.call('GET', '/status')).json.accountTag;
    const p = '/friend-requests/status?uid=555';
    assert.equal((await s.call('GET', p, { key: '' })).status, 401);
    assert.equal((await s.call('GET', p, { accountTag: '000000000000' })).status, 409);
    assert.deepEqual((await s.call('GET', p, { accountTag: tag })).json, { ok: false, code: 'NO_INVITE' });
    s.c.bridge.msgLedger.markSent('INVITE', '555');
    s.c.adapter.friendRequestStates = new Map([['555', { is_friend: 0, is_requested: 0, is_requesting: 1 }]]);
    let listReads = 0;
    s.c.adapter.hasSentFriendRequest = async (uid) => { listReads += 1; return uid === '555'; };
    assert.deepEqual((await s.call('GET', p + '&uid=123', { accountTag: tag })).json, { ok: false, code: 'BAD_INPUT' });
    assert.deepEqual((await s.call('GET', p, { accountTag: tag })).json, { ok: true, state: 'OUTGOING' });
    assert.deepEqual(s.c.adapter.friendRequestReads, ['555']);
    assert.equal(listReads, 0);
    const verify = p + '&verifyDirection=true';
    s.c.clock.t += 3100;
    assert.deepEqual((await s.call('GET', verify + '&verifyDirection=false', { accountTag: tag })).json, { ok: false, code: 'BAD_INPUT' });
    assert.equal((await s.call('GET', verify, { accountTag: '000000000000' })).status, 409);
    assert.equal(listReads, 0);
    assert.deepEqual((await s.call('GET', verify, { accountTag: tag })).json, { ok: true, state: 'OUTGOING_VERIFIED' });
    assert.equal(listReads, 1);
    assert.equal(s.c.adapter.friendRequests, undefined);
  } finally {
    await s.close();
    s.c.cleanup();
  }
});

test('mã sai hoặc body lạ không gọi được Zalo', async () => {
  const s = await boot();
  assert.deepEqual((await s.call('POST', '/groups/abc/members', { body: { uid: '1' } })).json, { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.deepEqual((await s.call('POST', '/groups/1/members', { body: { uid: '1; rm' } })).json, { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.deepEqual((await s.call('POST', '/groups/1/members', { body: {} })).json, { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.equal(s.c.adapter.adds.length, 0);
  await s.close();
});

test('phiên: đăng nhập QR, dừng, mở lại, đăng xuất', async () => {
  const s = await boot();
  assert.equal((await s.call('POST', '/session/stop', { body: {} })).json.ok, true);
  assert.equal((await s.call('GET', '/status')).json.state, 'STOPPED');
  assert.equal((await s.call('POST', '/session/resume', { body: {} })).json.ok, true);
  await flush(); await flush();
  assert.equal((await s.call('GET', '/status')).json.state, 'LISTENING');
  const l = await s.call('POST', '/session/login', { body: {} });
  assert.equal(l.json.state, 'WAITING_SCAN');
  assert.ok(l.json.qr);
  assert.equal((await s.call('POST', '/session/logout', { body: {} })).json.ok, true);
  assert.equal((await s.call('GET', '/status')).json.state, 'LOGIN_REQUIRED');
  await s.close();
});

test('KHÔNG có đường gửi hàng loạt, tìm người rời, liệt kê bạn bè (chỉ POST /messages một tin một người và POST /friend-requests một lời mời cho một người đã nhận Tin 1)', async () => {
  const s = await boot();
  const forbidden = [
    ['POST', '/message'], ['POST', '/messages/1'], ['GET', '/messages'], ['POST', '/send'], ['POST', '/sendMessage'], ['POST', '/friends/request'], ['POST', '/friend-requests/1'], ['GET', '/friend-requests'],
    ['POST', '/friends'], ['GET', '/friends'], ['GET', '/users'], ['POST', '/users/find'], ['GET', '/users/84912345678'],
    ['POST', '/broadcast'], ['PUT', '/groups'], ['POST', '/groups/create'], ['POST', '/groups/1'],  // GET /groups và POST /groups (D19, tạo một nhóm) là đường được phép riêng
    ['GET', '/groups/1/members'], ['DELETE', '/groups/1/members'],
    ['POST', '/fake/friend'],
  ];
  for (const [m, p] of forbidden) {
    const r = await s.call(m, p, { body: m === 'GET' ? undefined : { uid: '1', text: 'xin chào' } });
    assert.equal(r.status, 404, `${m} ${p} phải là 404`);
  }
  assert.equal(s.c.adapter.adds.length, 0);
  await s.close();
});

test('POST /people/lookup (D19) cần khóa, tra đúng một số, số sai không gọi Zalo, không có đường tra khác', async () => {
  const s = await boot();
  assert.equal((await s.call('POST', '/people/lookup', { key: '', body: {} })).status, 401);
  s.c.adapter.profiles = new Map([['84900000009', { uid: '900', name: 'Mẹ', avatarUrl: 'https://s120.zadn.vn/a.jpg' }]]);
  assert.deepEqual((await s.call('POST', '/people/lookup', { body: { phone: ['1', '2'] } })).json, { outcome: 'PERSON_ERROR', code: 'BAD_PHONE' });
  const ok = await s.call('POST', '/people/lookup', { body: { phone: '84900000009' } });
  assert.equal(ok.json.outcome, 'OK');
  assert.equal(ok.json.uid, '900');
  assert.equal(s.c.adapter.profileLookups.length, 1);
  for (const [m, p] of [['GET', '/people/lookup'], ['POST', '/people'], ['POST', '/people/lookup/1'], ['GET', '/people']]) {
    assert.equal((await s.call(m, p, { body: m === 'GET' ? undefined : { phone: '84900000009' } })).status, 404, `${m} ${p}`);
  }
  assert.equal(s.c.adapter.profileLookups.length, 1);
  await s.close();
});

test('danh sách đường được phép là cố định', () => {
  assert.deepEqual(ROUTES, [
    'GET /healthz', 'GET /status', 'GET /events', 'GET /inbox', 'POST /reactions', 'GET /groups', 'GET /groups/{id}/inspect', 'GET /groups/join-target', 'POST /groups/{id}/join', 'POST /groups', 'POST /groups/{id}/members', 'POST /groups/{id}/deputy', 'POST /messages', 'POST /friend-requests', 'POST /friend-requests/undo', 'GET /friend-requests/incoming', 'GET /friend-requests/status', 'POST /friend-requests/accept', 'POST /people/lookup', 'POST /session/login', 'GET /session/login',
    'POST /session/logout', 'POST /session/stop', 'POST /session/resume',
  ]);
});

test('scannedId chỉ xuất hiện khi sai tài khoản; các trạng thái khác và /status không lộ mã', async () => {
  const s = await boot();
  const start = await s.call('POST', '/session/login', { body: {} });
  assert.equal(start.json.state, 'WAITING_SCAN');
  assert.ok(!('scannedId' in start.json));
  s.c.adapter.loginResolve('7777');
  await flush(); await flush(); await flush();
  const mismatch = await s.call('GET', '/session/login');
  assert.equal(mismatch.json.state, 'ACCOUNT_MISMATCH');
  assert.equal(mismatch.json.scannedId, '7777');
  assert.ok(!JSON.stringify((await s.call('GET', '/status')).json).includes('7777'));
  // bắt đầu quét lại thì mã cũ biến mất
  const again = await s.call('POST', '/session/login', { body: {} });
  assert.equal(again.json.state, 'WAITING_SCAN');
  assert.ok(!('scannedId' in again.json));
  await s.close();
});

test('GET /groups cần khóa và trả nhóm của tài khoản phụ; chỉ có GET', async () => {
  const s = await boot();
  assert.equal((await s.call('GET', '/groups', { key: '' })).status, 401);
  s.c.adapter.groups = [{ id: '222', name: 'Nhóm thử', members: 3, adminOnlyAdd: true, isAdmin: false }];
  const r = await s.call('GET', '/groups');
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.groups[0].id, '222');
  assert.equal((await s.call('PUT', '/groups', { body: {} })).status, 404);
  assert.equal(s.c.adapter.adds.length, 0);
  await s.close();
});

test('tham gia bằng link chỉ cho đúng phiên và đúng nhóm đã xác minh', async () => {
  const s = await boot();
  try {
    const accountTag = (await s.call('GET', '/status')).json.accountTag;
    const inviteLink = 'https://zalo.me/g/fixture';
    const p = '/groups/222/join';
    let calls = 0;
    let member = false;
    s.c.adapter.resolveGroupInviteLink = async () => '333';
    const resolve = '/groups/join-target?inviteLink=' + encodeURIComponent(inviteLink);
    assert.equal((await s.call('GET', resolve)).status, 409);
    assert.deepEqual((await s.call('GET', resolve, { accountTag })).json, { ok: true, groupId: '333' });
    s.c.adapter.hasGroupMembership = async () => member;
    s.c.adapter.joinGroupLink = async (link) => {
      assert.equal(link, inviteLink);
      calls++;
      member = true;
    };
    assert.equal((await s.call('POST', p, { key: '', accountTag, body: { inviteLink } })).status, 401);
    assert.equal((await s.call('POST', p, { body: { inviteLink } })).status, 409);
    assert.equal((await s.call('POST', p, { accountTag: '000000000000', body: { inviteLink } })).status, 409);
    assert.deepEqual((await s.call('POST', p, { accountTag, body: { inviteLink: 'https://example.test/g/fixture' } })).json,
      { outcome: 'NOT_DISPATCHED', code: 'BAD_INPUT' });
    assert.deepEqual((await s.call('POST', p, { accountTag, body: { inviteLink } })).json,
      { outcome: 'NOT_DISPATCHED', code: 'LINK_GROUP_MISMATCH' });
    assert.equal(calls, 0);
    s.c.adapter.resolveGroupInviteLink = async () => '222';
    assert.deepEqual((await s.call('POST', p, { accountTag, body: { inviteLink } })).json,
      { outcome: 'JOINED', groupId: '222' });
    assert.deepEqual((await s.call('POST', p, { accountTag, body: { inviteLink } })).json,
      { outcome: 'JOINED', code: 'ALREADY_MEMBER', groupId: '222' });
    assert.equal(calls, 1);
  } finally {
    await s.close();
  }
});

test('tham gia nhóm phân biệt đang chờ duyệt và kết quả chưa rõ', async () => {
  const s = await boot();
  try {
    const accountTag = (await s.call('GET', '/status')).json.accountTag;
    const inviteLink = 'https://zalo.me/g/fixture';
    const p = '/groups/222/join';
    const options = { accountTag, body: { inviteLink } };
    let calls = 0;
    s.c.adapter.resolveGroupInviteLink = async () => '222';
    s.c.adapter.hasGroupMembership = async () => false;
    s.c.adapter.joinGroupLink = async () => {
      calls++;
      throw Object.assign(new Error('provider details'), {code: 240});
    };
    assert.deepEqual((await s.call('POST', p, options)).json, { outcome: 'PENDING', code: 'Z240' });
    assert.equal(calls, 1);
    s.c.adapter.joinGroupLink = async () => { calls++; };
    assert.deepEqual((await s.call('POST', p, options)).json, { outcome: 'UNKNOWN', code: 'MEMBERSHIP_UNCONFIRMED' });
    assert.equal(calls, 2);
    s.c.adapter.resolveGroupInviteLink = async () => { throw new Error('provider details'); };
    assert.deepEqual((await s.call('POST', p, options)).json, { outcome: 'NOT_DISPATCHED', code: 'LINK_CHECK_FAILED' });
    assert.equal(calls, 2);
    assert.deepEqual((await s.call('POST', p, { accountTag, body: { inviteLink, ownerVerified: true } })).json,
      { outcome: 'UNKNOWN', code: 'MEMBERSHIP_UNCONFIRMED' });
    assert.equal(calls, 3);
  } finally {
    await s.close();
  }
});

test('POST /groups (D19) cần khóa, tạo đúng một nhóm, body sai không gọi Zalo', async () => {
  const s = await boot();
  assert.equal((await s.call('POST', '/groups', { key: '', body: {} })).status, 401);
  const bad = await s.call('POST', '/groups', { body: {} });
  assert.equal(bad.json.outcome, 'PERSON_ERROR');
  assert.equal((s.c.adapter.created ?? []).length, 0);
  const ok = await s.call('POST', '/groups', { body: { name: 'Áo điều hòa ADH - Miền Nam - 01', members: ['111', '222'], deputy: '333' } });
  assert.equal(ok.json.outcome, 'OK');
  assert.equal(s.c.adapter.created.length, 1);
  await s.close();
});

test('POST /groups/{id}/deputy (đặt lại phó nhóm) cần khóa, sai mã bị từ chối, đúng thì đặt một phó nhóm; không có đường đổi/xóa phó nhóm', async () => {
  const s = await boot();
  assert.equal((await s.call('POST', '/groups/9001/deputy', { key: '', body: { uid: '900' } })).status, 401);
  assert.deepEqual((await s.call('POST', '/groups/abc/deputy', { body: { uid: '900' } })).json, { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.deepEqual((await s.call('POST', '/groups/9001/deputy', { body: {} })).json, { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.deepEqual((await s.call('POST', '/groups/9001/deputy', { body: { uid: '900' } })).json, { outcome: 'OK', deputy: 'OK' });
  assert.deepEqual(s.c.adapter.deputies, [{ uid: '900', groupId: '9001' }]);
  for (const [m, p] of [['GET', '/groups/9001/deputy'], ['DELETE', '/groups/9001/deputy'], ['PUT', '/groups/9001/deputy']]) {
    assert.equal((await s.call(m, p, { body: m === 'GET' ? undefined : { uid: '900' } })).status, 404, `${m} ${p}`);
  }
  await s.close();
});

test('POST /messages cần khóa, gửi đúng một tin cho một người, không nhận danh sách người nhận', async () => {
  const s = await boot();
  assert.equal((await s.call('POST', '/messages', { key: '', body: {} })).status, 401);
  s.c.adapter.uids = new Map([['84912345678', '777']]);
  const r = await s.call('POST', '/messages', { body: { kind: 'INVITE', phone: '0912345678'.replace(/^0/, '84'), text: 'Xin chào' } });
  assert.deepEqual(r.json, { outcome: 'OK', uid: '777' });
  assert.deepEqual(s.c.adapter.sent, [{ uid: '777', text: 'Xin chào' }]);
  // danh sách người nhận hay cả hai trường cùng lúc: từ chối, không gửi
  s.c.clock.t += 10 * 60 * 1000;
  const many = await s.call('POST', '/messages', { body: { kind: 'INVITE', phone: ['84900000001', '84900000002'], text: 'x' } });
  assert.deepEqual(many.json, { outcome: 'PERSON_ERROR', code: 'BAD_PHONE' });
  const both = await s.call('POST', '/messages', { body: { kind: 'INVITE', phone: '84900000001', uid: '1', text: 'x' } });
  assert.deepEqual(both.json, { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' });
  assert.equal(s.c.adapter.sent.length, 1);
  await s.close();
});

test('GET /inbox: cần khóa; tắt mặc định thì báo enabled=false và rỗng; bật thì trả tin của người đã nhận tin hệ thống', async () => {
  const off = await boot();
  assert.equal((await off.call('GET', '/inbox', { key: '' })).status, 401);
  const r = await off.call('GET', '/inbox');
  assert.equal(r.json.enabled, false);
  assert.deepEqual(r.json.messages, []);
  await off.close();
  const on = await boot({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await on.c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  on.c.adapter.emitMessage('777', 'không cần đâu');
  const r2 = await on.call('GET', '/inbox?after=0');
  assert.equal(r2.json.enabled, true);
  assert.equal(r2.json.messages.length, 1);
  assert.equal((await on.call('GET', '/inbox?after=' + r2.json.messages[0].seq)).json.messages.length, 0);
  assert.equal((await on.call('POST', '/inbox', { body: {} })).status, 404);
  await on.close();
});

test('kiểm tra đúng một nhóm bằng phiên đã ghim, không đổi dữ liệu', async () => {
  const s = await boot();
  try {
    const tag = (await s.call('GET', '/status')).json.accountTag;
    const p = '/groups/222/inspect';
    s.c.adapter.groups = [{ id: '222', name: 'Nhóm thử', adminOnlyAdd: true, isAdmin: false,
      inviteLink: 'https://zalo.me/g/fake222' }];
    assert.equal((await s.call('GET', p)).status, 409);
    assert.equal((await s.call('GET', p, { accountTag: '000000000000' })).status, 409);
    assert.deepEqual((await s.call('GET', p, { accountTag: tag })).json, {
      ok: true, present: true, adminOnlyAdd: true, admin: false, inviteLink: 'https://zalo.me/g/fake222', accountTag: tag,
    });
    s.c.adapter.groups[0].isAdmin = true;
    assert.equal((await s.call('GET', p, { accountTag: tag })).json.admin, true);
    assert.deepEqual((await s.call('GET', '/groups/333/inspect', { accountTag: tag })).json,
      { ok: true, present: false, accountTag: tag });
    assert.equal(s.c.adapter.adds.length, 0);
  } finally {
    await s.close();
  }
});

test('đối chiếu phó nhóm yêu cầu dấu tài khoản, kiểm lại chủ nhóm và không thêm người đã có mặt', async () => {
  const s = await boot();
  try {
    const tag = (await s.call('GET', '/status')).json.accountTag;
    const base = '/groups/222';
    const group = { id: '222', isAdmin: true, adminOnlyAdd: true, owner: true, deputyRoles: { '333': 'MEMBER' } };
    s.c.adapter.groups = [group];
    const inspect = base + '/inspect?deputyUid=333';
    assert.equal((await s.call('GET', inspect)).status, 409);
    assert.deepEqual((await s.call('GET', inspect, { accountTag: tag })).json, {
      ok: true, present: true, adminOnlyAdd: true, admin: true, owner: true, groupId: '222',
      deputyRole: 'MEMBER', inviteLink: null, accountTag: tag,
    });
    assert.deepEqual((await s.call('GET', inspect + '&deputyUid=444', { accountTag: tag })).json, { ok: false, code: 'BAD_INPUT' });
    assert.deepEqual((await s.call('GET', base + '/inspect?deputyUid=', { accountTag: tag })).json, { ok: false, code: 'BAD_INPUT' });
    assert.equal(s.c.adapter.adds.length, 0);
    assert.equal(s.c.adapter.deputies?.length ?? 0, 0);

    const body = { uid: '333', alreadyMember: true };
    const write = base + '/deputy';
    assert.equal((await s.call('POST', write, { body })).status, 409);
    assert.equal((await s.call('POST', write, { body, accountTag: '000000000000' })).status, 409);
    const invalid = await s.call('POST', write, { body: { ...body, alreadyMember: 'true' }, accountTag: tag });
    assert.deepEqual(invalid.json, { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' });
    group.owner = false;
    assert.deepEqual((await s.call('POST', write, { body, accountTag: tag })).json, { outcome: 'PERSON_ERROR', code: 'NO_OWNER' });
    group.owner = true;
    group.deputyRoles['333'] = 'UNKNOWN';
    assert.deepEqual((await s.call('POST', write, { body, accountTag: tag })).json, { outcome: 'PERSON_ERROR', code: 'ROLE_UNVERIFIED' });
    group.deputyRoles['333'] = 'ABSENT';
    assert.deepEqual((await s.call('POST', write, { body, accountTag: tag })).json, { outcome: 'PERSON_ERROR', code: 'MEMBERSHIP_CHANGED' });
    assert.equal(s.c.adapter.adds.length, 0);
    assert.equal(s.c.adapter.deputies?.length ?? 0, 0);
    group.deputyRoles['333'] = 'MEMBER';
    assert.deepEqual((await s.call('POST', write, { body, accountTag: tag })).json, { outcome: 'OK', deputy: 'OK' });
    assert.deepEqual(s.c.adapter.deputies, [{ uid: '333', groupId: '222' }]);
    assert.equal(s.c.adapter.adds.length, 0);

    group.deputyRoles['333'] = 'ADMIN';
    assert.deepEqual((await s.call('POST', write, { body, accountTag: tag })).json, { outcome: 'OK', deputy: 'OK' });
    assert.equal(s.c.adapter.deputies.length, 1);
    group.deputyRoles['333'] = 'ABSENT';
    const absent = { ...body, alreadyMember: false };
    assert.deepEqual((await s.call('POST', write, { body: absent, accountTag: tag })).json, { outcome: 'OK', deputy: 'OK' });
    assert.deepEqual(s.c.adapter.adds, [{ uid: '333', groupId: '222' }]);
    assert.equal(s.c.adapter.deputies.length, 2);

    s.c.adapter.nextAdd = { errorMembers: ['333'], error_data: { '114': ['333'] } };
    assert.deepEqual((await s.call('POST', write, { body: absent, accountTag: tag })).json,
      { outcome: 'OK', deputy: 'FAILED', code: 'ADD-Z114' });
    assert.equal(s.c.adapter.deputies.length, 2, 'không bổ nhiệm tiếp khi Zalo từ chối thêm');

    s.c.adapter.nextAdd = async () => {
      s.c.bridge.stop();
      return { errorMembers: [] };
    };
    assert.deepEqual((await s.call('POST', write, { body: absent, accountTag: tag })).json,
      { outcome: 'OK', deputy: 'FAILED', code: 'SESSION_CHANGED' });
    assert.equal(s.c.adapter.deputies.length, 2, 'không bổ nhiệm khi phiên đổi giữa hai bước');
    assert.ok(!s.c.logs.join('\n').includes('333'));
  } finally {
    await s.close();
    s.c.cleanup();
  }
});

test('đặt phó nhóm theo đối chiếu báo lỗi không rõ kết quả để ADH dừng chung', async () => {
  const s = await boot();
  try {
    const tag = (await s.call('GET', '/status')).json.accountTag;
    s.c.adapter.groups = [{ id: '222', isAdmin: true, owner: true, deputyRoles: { '333': 'MEMBER' } }];
    s.c.adapter.deputyError = Object.assign(new Error('quá thời gian'), { name: 'TimeoutError' });
    const r = await s.call('POST', '/groups/222/deputy', { body: { uid: '333', alreadyMember: true }, accountTag: tag });
    assert.deepEqual(r.json, { outcome: 'SYSTEM_ERROR', code: 'TIMEOUT' });
    assert.equal((await s.call('GET', '/status')).json.state, 'TRIPPED');
    assert.equal(s.c.adapter.adds.length, 0);
  } finally {
    await s.close();
    s.c.cleanup();
  }
});
