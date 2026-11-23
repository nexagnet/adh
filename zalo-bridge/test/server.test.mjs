import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, ROUTES } from '../src/server.mjs';
import { setup, loginAndListen, flush } from './helpers.mjs';

async function boot() {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  const server = createServer({ bridge: c.bridge, config: c.config });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, path, { body, key = c.config.key } = {}) => {
    const r = await fetch(base + path, {
      method,
      headers: { ...(key ? { 'X-Bridge-Key': key } : {}), 'Content-Type': 'application/json' },
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

test('KHÔNG có đường gửi hàng loạt, gửi lời mời kết bạn, tìm người rời, liệt kê bạn bè (chỉ POST /messages một tin một người)', async () => {
  const s = await boot();
  const forbidden = [
    ['POST', '/message'], ['POST', '/messages/1'], ['GET', '/messages'], ['POST', '/send'], ['POST', '/sendMessage'], ['POST', '/friends/request'],
    ['POST', '/friends'], ['GET', '/friends'], ['GET', '/users'], ['POST', '/users/find'], ['GET', '/users/84912345678'],
    ['POST', '/broadcast'], ['POST', '/groups'], ['PUT', '/groups'],  // GET /groups (đọc nhóm của chính tài khoản phụ) là đường được phép riêng
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

test('danh sách đường được phép là cố định', () => {
  assert.deepEqual(ROUTES, [
    'GET /healthz', 'GET /status', 'GET /events', 'GET /groups', 'POST /groups/{id}/members', 'POST /messages', 'POST /session/login', 'GET /session/login',
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
  assert.equal((await s.call('POST', '/groups', { body: {} })).status, 404);
  assert.equal(s.c.adapter.adds.length, 0);
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
