import test, { mock } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { setup, loginAndListen, flush, T0 } from './helpers.mjs';

test('chưa có phiên thì cần đăng nhập, không nghe gì', async () => {
  const c = setup();
  await c.bridge.start();
  assert.equal(c.bridge.status().state, 'LOGIN_REQUIRED');
  assert.equal((await c.bridge.addMember('1', '2')).outcome, 'SESSION_LOST');
  assert.equal(c.adapter.adds.length, 0);
  c.cleanup();
});

test('quét QR đúng tài khoản thì lưu phiên mã hóa và bắt đầu nghe', async () => {
  const c = setup();
  await c.bridge.start();
  const l = c.bridge.startLogin();
  assert.equal(l.state, 'WAITING_SCAN');
  assert.ok(l.qr);
  c.adapter.loginHandlers.onScanned();
  assert.equal(c.bridge.loginStatus().state, 'SCANNED');
  c.adapter.loginResolve('1000');
  await flush(); await flush(); await flush();
  assert.equal(c.bridge.loginStatus().state, 'DONE');
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.equal(c.bridge.status().accountOk, true);
  assert.ok(existsSync(path.join(c.dir, 'session.enc')));
  c.cleanup();
});

test('quét nhầm tài khoản khác thì từ chối, đăng xuất, không lưu phiên', async () => {
  const c = setup();
  await c.bridge.start();
  c.bridge.startLogin();
  c.adapter.loginResolve('9999');
  await flush(); await flush(); await flush();
  assert.equal(c.bridge.loginStatus().state, 'ACCOUNT_MISMATCH');
  assert.equal(c.bridge.loginStatus().scannedId, '9999'); // để quản trị điền đúng ZALO_ACCOUNT_ID
  assert.ok(!c.logs.join('\n').includes('9999'), 'mã tài khoản không được vào log');
  c.clock.t += 11 * 60 * 1000; // quá 10 phút thì không còn trả mã
  assert.equal(c.bridge.loginStatus().scannedId, null);
  assert.equal(c.bridge.status().state, 'LOGIN_REQUIRED');
  assert.equal(c.bridge.status().accountOk, false);
  assert.equal(c.adapter.logouts, 1);
  assert.ok(!existsSync(path.join(c.dir, 'session.enc')));
  c.cleanup();
});

test('khởi động lại với phiên đã lưu thì tự nghe lại, nhưng sai tài khoản thì xóa phiên', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  const b2 = c.rebuild();
  await b2.start();
  await flush();
  assert.equal(b2.status().state, 'LISTENING');
  c.adapter.ownId = '5555';
  const b3 = c.rebuild();
  await b3.start();
  assert.equal(b3.status().state, 'LOGIN_REQUIRED');
  assert.equal(b3.status().accountOk, false);
  assert.ok(!existsSync(path.join(c.dir, 'session.enc')));
  c.cleanup();
});

test('phiên đã hết hạn lúc khởi động thì cần quét lại, không thử mãi', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.restoreError = Object.assign(new Error('cookie không hợp lệ, cần đăng nhập'), { code: 600 });
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().state, 'LOGIN_REQUIRED');
  assert.equal(b2.status().detail, 'SESSION_EXPIRED');
  c.cleanup();
});

test('sự kiện kết bạn: có số thì trả số (chỉ chữ số), không số thì null; chỉ hỏi số của đúng uid đó', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.emitFriendAdd('777', '+84 912-345-678');
  c.adapter.emitFriendAdd('888', '');
  await flush(); await flush();
  const evs = c.bridge.friendEvents(0);
  assert.equal(evs.length, 2);
  assert.equal(evs[0].uid, '777');
  assert.equal(evs[0].phone, '84912345678');
  assert.equal(evs[1].phone, null);
  assert.ok(evs[1].seq > evs[0].seq);
  // đã nhận tới seq này thì lần sau không trả lại
  assert.deepEqual(c.bridge.friendEvents(evs[1].seq), []);
  assert.equal(c.bridge.status().lastEventAt !== null, true);
  c.cleanup();
});

test('không lấy được số do lỗi thường thì vẫn báo sự kiện với phone null', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.phoneError = new Error('mạng chập chờn');
  c.adapter.emitFriendAdd('777');
  await flush(); await flush();
  assert.equal(c.bridge.friendEvents(0)[0].phone, null);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('thêm người: thành công, và lỗi riêng người (Zalo báo errorMembers) không làm dừng', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  assert.deepEqual(await c.bridge.addMember('2002', '777'), { outcome: 'OK' });
  assert.deepEqual(c.adapter.adds, [{ uid: '777', groupId: '2002' }]);
  c.adapter.nextAdd = { errorMembers: ['888'], error_data: { 270: ['888'] } };
  assert.deepEqual(await c.bridge.addMember('2002', '888'), { outcome: 'PERSON_ERROR', code: 'Z270' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

for (const [name, err, outcome] of [
  ['lỗi lạ', Object.assign(new Error('gì đó lạ'), { code: 999 }), 'SYSTEM_ERROR'],
  ['không phải ZaloApiError', new Error('ECONNRESET'), 'SYSTEM_ERROR'],
  ['mất phiên', Object.assign(new Error('zpw_sek bị thiếu hoặc không đúng'), { code: 600 }), 'SESSION_LOST'],
  ['yêu cầu xác minh', Object.assign(new Error('Cần xác minh captcha'), { code: 1 }), 'RESTRICTED'],
]) {
  test(`thêm người gặp ${name} thì cầu nối tự dừng, đóng kết nối nghe, không thao tác nữa`, async () => {
    const c = setup();
    await c.bridge.start();
    await loginAndListen(c);
    c.adapter.nextAdd = err;
    const r = await c.bridge.addMember('2002', '777');
    assert.equal(r.outcome, outcome);
    assert.equal(c.bridge.status().state, 'TRIPPED');
    assert.equal(c.adapter.listening, false);
    const r2 = await c.bridge.addMember('2002', '888');
    assert.equal(r2.outcome, 'RESTRICTED');
    assert.equal(c.adapter.adds.length, 1);
    c.cleanup();
  });
}

test('mã lỗi đã khai báo là lỗi riêng người thì không dừng', async () => {
  const c = setup({ env: { BRIDGE_PERSON_ERROR_CODES: '240' } });
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.nextAdd = Object.assign(new Error('đã trong nhóm'), { code: 240 });
  assert.deepEqual(await c.bridge.addMember('2002', '777'), { outcome: 'PERSON_ERROR', code: 'Z240' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('hết giờ chờ lúc thêm người: không biết kết quả nên dừng', async () => {
  const c = setup({ env: { BRIDGE_ADD_TIMEOUT_MS: '1000' } });
  c.config.addTimeoutMs = 30;
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.nextAdd = () => new Promise(() => {});
  const r = await c.bridge.addMember('2002', '777');
  assert.deepEqual(r, { outcome: 'SYSTEM_ERROR', code: 'TIMEOUT' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
});

test('mã không phải chữ số bị từ chối, không gọi gì', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  for (const [g, u] of [['abc', '1'], ['1', '../x'], ['', '1'], ['1', '1 2']]) {
    assert.equal((await c.bridge.addMember(g, u)).code, 'BAD_ID');
  }
  assert.equal(c.adapter.adds.length, 0);
  c.cleanup();
});

test('ngoài khung giờ 07:00-22:00 thì từ chối và dừng', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.clock.t = Date.parse('2026-11-22T23:30:00Z'); // 06:30 hôm sau giờ Việt Nam
  assert.deepEqual(await c.bridge.addMember('2002', '777'), { outcome: 'RESTRICTED', code: 'WINDOW' });
  assert.equal(c.bridge.status().tripReason, 'WINDOW');
  assert.equal(c.adapter.adds.length, 0);
  c.cleanup();
});

test('trần cứng mỗi ngày của chính cầu nối, tính cả lần lỗi, và còn nhớ sau khi khởi động lại', async () => {
  const c = setup({ env: { APP_ZALO_AUTO_MAX_DAILY: '1' } }); // trần cầu nối = 2
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal((await c.bridge.addMember('2002', '1')).outcome, 'OK');
  c.adapter.nextAdd = { errorMembers: ['2'], error_data: {} };
  assert.equal((await c.bridge.addMember('2002', '2')).outcome, 'PERSON_ERROR');
  assert.deepEqual(await c.bridge.addMember('2002', '3'), { outcome: 'RESTRICTED', code: 'LIMIT' });
  assert.equal(c.adapter.adds.length, 2);
  // khởi động lại: cầu dao vẫn nhảy, số đếm vẫn còn
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().state, 'TRIPPED');
  assert.equal(b2.status().tripReason, 'LIMIT');
  c.cleanup();
});

test('cầu dao còn nguyên sau khi khởi động lại; chỉ resume mới nghe lại', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.emitDisconnect(3000);
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.equal(c.bridge.status().tripReason, 'DUPLICATE_CONNECTION');
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().state, 'TRIPPED');
  assert.equal(c.adapter.listening, false);
  assert.equal(await b2.resume(), true);
  await flush(); await flush();
  assert.equal(b2.status().state, 'LISTENING');
  assert.equal(b2.status().tripReason, null);
  c.cleanup();
});

test('mã đóng 3003 (bị đá) cũng dừng', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.emitDisconnect(3003);
  assert.equal(c.bridge.status().tripReason, 'KICKED');
  c.cleanup();
});

test('dừng tay giữ phiên, trạng thái STOPPED, mở lại bằng phiên cũ', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal(c.bridge.stop(), true);
  assert.equal(c.bridge.status().state, 'STOPPED');
  assert.ok(existsSync(path.join(c.dir, 'session.enc')));
  assert.equal(await c.bridge.resume(), true);
  await flush(); await flush();
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('đứt 1006 thì nối lại có backoff rồi nghe lại; quá số lần thì dừng', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const c = setup();
    await c.bridge.start();
    await loginAndListen(c);
    const restores0 = c.adapter.restores;
    c.adapter.emitDisconnect(1006);
    assert.equal(c.bridge.status().state, 'CONNECTING');
    mock.timers.tick(4999);
    await flush();
    assert.equal(c.adapter.restores, restores0); // chưa tới giờ
    mock.timers.tick(1);
    await flush(); await flush();
    assert.equal(c.adapter.restores, restores0 + 1);
    await flush();
    assert.equal(c.bridge.status().state, 'LISTENING');

    // mạng hỏng liên tục: nối lại nhiều lần (backoff tăng) rồi dừng, không thử mãi
    c.adapter.restoreError = new Error('ECONNREFUSED');
    c.adapter.emitDisconnect(1006);
    const delays = [5000, 15000, 45000, 120000, 300000, 300000, 300000, 300000];
    for (const d of delays) {
      assert.equal(c.bridge.status().state, 'CONNECTING');
      mock.timers.tick(d);
      await flush(); await flush();
    }
    assert.equal(c.bridge.status().state, 'TRIPPED');
    assert.equal(c.bridge.status().tripReason, 'RECONNECT_FAILED');
    c.cleanup();
  } finally {
    mock.timers.reset();
  }
});

test('nối lại mà phiên đã chết thì cần quét QR, không thử mãi', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    const c = setup();
    await c.bridge.start();
    await loginAndListen(c);
    c.adapter.restoreError = Object.assign(new Error('login failed: cookie'), { code: 600 });
    c.adapter.emitDisconnect(1006);
    mock.timers.tick(5000);
    await flush(); await flush();
    assert.equal(c.bridge.status().state, 'LOGIN_REQUIRED');
    assert.equal(c.bridge.status().detail, 'SESSION_EXPIRED');
    c.cleanup();
  } finally {
    mock.timers.reset();
  }
});

test('mất phiên lúc thêm người rồi quét QR lại đúng tài khoản thì tự mở lại; lý do khác thì không', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.nextAdd = Object.assign(new Error('zpw_sek bị thiếu'), { code: 600 });
  await c.bridge.addMember('2002', '777');
  assert.equal(c.bridge.status().tripReason, 'SESSION_LOST:Z600');
  await loginAndListen(c);
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.equal(c.bridge.status().tripReason, null);

  c.adapter.emitDisconnect(3000);
  assert.equal(c.bridge.status().state, 'TRIPPED');
  await loginAndListen(c);
  assert.equal(c.bridge.status().state, 'TRIPPED'); // vẫn cần bấm "Mở lại"
  c.cleanup();
});

test('đăng xuất xóa phiên và cần quét lại', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.logout();
  assert.equal(c.bridge.status().state, 'LOGIN_REQUIRED');
  assert.ok(!existsSync(path.join(c.dir, 'session.enc')));
  assert.equal(c.adapter.logouts, 1);
  c.cleanup();
});

test('sổ đếm hỏng thì dừng ngay (không biết đã dùng bao nhiêu)', async () => {
  const c = setup();
  const { writeFileSync } = await import('node:fs');
  writeFileSync(path.join(c.dir, 'adds.json'), '{hỏng');
  const b = c.rebuild();
  await b.start();
  assert.equal(b.status().state, 'TRIPPED');
  assert.equal(b.status().tripReason, 'LEDGER_CORRUPT');
  assert.equal(await b.resume(), false);
  c.cleanup();
});

test('nhật ký không chứa uid, số điện thoại hay mã nhóm', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.emitDisconnect(1006);
  c.adapter.emitFriendAdd('777123', '84912345678');
  await c.bridge.addMember('2002345', '777123');
  const all = c.logs.join('\n');
  for (const s of ['777123', '912345678', '2002345']) assert.ok(!all.includes(s), `lộ ${s}`);
  c.cleanup();
});

test('seq tăng đơn điệu, kể cả khi hai sự kiện cùng mili giây', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.emitFriendAdd('1', null);
  c.adapter.emitFriendAdd('2', null);
  c.adapter.emitFriendAdd('3', null);
  await flush(); await flush(); await flush();
  const seqs = c.bridge.friendEvents(0).map((e) => e.seq);
  assert.deepEqual(seqs, [...seqs].sort((a, b) => a - b));
  assert.equal(new Set(seqs).size, 3);
  assert.ok(seqs[0] >= T0);
  c.cleanup();
});

test('quét nhầm tài khoản KHI đang nghe: đăng xuất phiên vừa quét, giữ nguyên phiên phụ đang chạy', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal(c.bridge.status().state, 'LISTENING');
  const savedBefore = existsSync(path.join(c.dir, 'session.enc'));
  c.bridge.startLogin();
  c.adapter.loginResolve('9999');
  await flush(); await flush(); await flush();
  assert.equal(c.bridge.loginStatus().state, 'ACCOUNT_MISMATCH');
  assert.equal(c.adapter.discards, 1);
  assert.equal(c.adapter.adopts, 1); // chỉ lần đăng nhập đúng đầu tiên
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.equal(c.bridge.status().accountOk, true);
  assert.equal(existsSync(path.join(c.dir, 'session.enc')), savedBefore);
  c.cleanup();
});

test('quét lại đúng tài khoản khi đang nghe: đóng kết nối nghe cũ trước khi nghe bằng phiên mới', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  const stopsBefore = c.adapter.stops;
  c.bridge.startLogin();
  c.adapter.loginResolve('1000');
  await flush(); await flush(); await flush();
  assert.equal(c.adapter.adopts, 2);
  assert.ok(c.adapter.stops > stopsBefore);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('sập giữa lúc đang thêm người: khởi động lại thì dừng (không biết kết quả), chỉ resume mới nghe lại', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  // giả lập sập: tệp trạng thái còn dấu "đang thêm"
  const { writeFileSync } = await import('node:fs');
  writeFileSync(path.join(c.dir, 'bridge-state.json'), JSON.stringify({ inflight: true }));
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().state, 'TRIPPED');
  assert.equal(b2.status().tripReason, 'INTERRUPTED');
  assert.equal((await b2.addMember('1', '2')).outcome, 'RESTRICTED');
  c.cleanup();
});

test('thêm xong bình thường thì không còn dấu "đang thêm"', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal((await c.bridge.addMember('11', '22')).outcome, 'OK');
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().state, 'LISTENING');
  c.cleanup();
});

test('khởi động lại liên tục (quá 5 lần trong 10 phút) thì dừng, không đăng nhập Zalo lặp lại', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  for (let i = 0; i < 3; i += 1) await c.rebuild().start(); // lần 2..4
  const fifth = c.rebuild();
  await fifth.start();
  assert.equal(fifth.status().state, 'LISTENING'); // lần thứ 5 vẫn được
  const sixth = c.rebuild();
  const restoresBefore = c.adapter.restores;
  await sixth.start();
  assert.equal(sixth.status().state, 'TRIPPED');
  assert.equal(sixth.status().tripReason, 'CRASH_LOOP');
  assert.equal(c.adapter.restores, restoresBefore);
  // sau 10 phút và bấm mở lại thì chạy được
  c.clock.t += 11 * 60 * 1000;
  assert.equal(await sixth.resume(), true);
  c.cleanup();
});

test('hạn mức hỏi thông tin mỗi giờ: quá hạn thì vẫn nhận sự kiện nhưng không hỏi số (rơi về hàng đợi tay)', async () => {
  const c = setup({ env: { BRIDGE_MAX_LOOKUPS_PER_HOUR: '2' } });
  await c.bridge.start();
  await loginAndListen(c);
  let asked = 0;
  c.adapter.getPhone = async () => {
    asked += 1;
    return '0912345678';
  };
  for (const uid of ['101', '102', '103']) {
    c.adapter.emitFriendAdd(uid);
    await flush(); await flush();
  }
  const ev = c.bridge.friendEvents(0);
  assert.equal(ev.length, 3);
  assert.equal(asked, 2);
  assert.equal(ev[2].phone, null);
  c.clock.t += 61 * 60 * 1000;
  c.adapter.emitFriendAdd('104');
  await flush(); await flush();
  assert.equal(asked, 3);
  c.cleanup();
});

test('liệt kê nhóm: chỉ đọc, chỉ khi đang nghe, giữ kết quả 30 giây, lỗi không làm cầu dao nhảy, log không có tên nhóm', async () => {
  const c = setup();
  await c.bridge.start();
  assert.deepEqual(await c.bridge.listGroups(), { ok: false, code: 'LOGIN_REQUIRED' });
  await loginAndListen(c);
  c.adapter.groups = [{ id: '111', name: 'Khách miền Nam', members: 7, adminOnlyAdd: false, isAdmin: true }];
  const a = await c.bridge.listGroups();
  assert.equal(a.ok, true);
  assert.equal(a.groups[0].id, '111');
  await c.bridge.listGroups();
  assert.equal(c.adapter.groupListCalls, 1); // lần hai dùng kết quả giữ 30 giây
  c.clock.t += 31_000;
  c.adapter.groupsError = new Error('hỏng');
  assert.deepEqual(await c.bridge.listGroups(), { ok: false, code: 'LIST_FAILED' });
  assert.equal(c.bridge.status().state, 'LISTENING'); // lỗi đọc không dừng cầu nối
  assert.ok(!c.logs.some((l) => l.includes('Khách miền Nam') || l.includes('111')));
  assert.equal(c.adapter.adds.length, 0);
  c.cleanup();
});
