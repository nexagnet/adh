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

test('mã lỗi đã khai báo là lỗi CẢ NHÓM (ném lỗi hoặc errorMembers) thì báo GROUP_ERROR và không dừng', async () => {
  const c = setup({ env: { BRIDGE_GROUP_ERROR_CODES: '178, 270' } });
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.nextAdd = Object.assign(new Error('nhóm không còn'), { code: 178 });
  assert.deepEqual(await c.bridge.addMember('2002', '777'), { outcome: 'GROUP_ERROR', code: 'Z178' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.adapter.nextAdd = { errorMembers: ['888'], error_data: { 270: ['888'] } };
  assert.deepEqual(await c.bridge.addMember('2002', '888'), { outcome: 'GROUP_ERROR', code: 'Z270' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  // mã khác vẫn là lỗi riêng người, lỗi lạ vẫn dừng
  c.adapter.nextAdd = { errorMembers: ['999'], error_data: { 240: ['999'] } };
  assert.deepEqual(await c.bridge.addMember('2002', '999'), { outcome: 'PERSON_ERROR', code: 'Z240' });
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

// ---------- D18: gửi MỘT tin cho MỘT khách ----------

const GAP = 90 * 1000;

async function ready(env = {}) {
  const c = setup({ env });
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.uids = new Map([['84900000001', '501'], ['84900000002', '502'], ['84900000003', '503']]);
  return c;
}

test('gửi tin mời theo số: tìm đúng một số, gửi đúng một tin văn bản cho đúng một người, trả uid', async () => {
  const c = await ready();
  const r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Chào anh' });
  assert.deepEqual(r, { outcome: 'OK', uid: '501' });
  assert.deepEqual(c.adapter.sent, [{ uid: '501', text: 'Chào anh' }]);
  c.cleanup();
});

test('Tin 1 nhiều dòng: gửi thành vài tin ngắn liền nhau cho CÙNG khách, phần đầu là lời chào; vẫn là MỘT lần Tin 1 (một dòng sổ, một lần tính khoảng nghỉ giữa khách)', async () => {
  const c = await ready({ BRIDGE_PART_GAP_SEC: '0' });
  const text = 'Dạ em chào bác Lan ạ.\nBác từng mua áo.\n\nBác bấm Chấp nhận nhé.';
  const r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text });
  assert.deepEqual(r, { outcome: 'OK', uid: '501' });
  assert.deepEqual(c.adapter.sent.map((x) => x.text), ['Dạ em chào bác Lan ạ.', 'Bác từng mua áo.', 'Bác bấm Chấp nhận nhé.']);
  assert.ok(c.adapter.sent.every((x) => x.uid === '501'));
  // khách thứ hai vẫn phải chờ khoảng nghỉ giữa khách, và khách này không nhận Tin 1 lần nữa
  const again = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000002', text: 'x' });
  assert.equal(again.outcome, 'RATE_LIMITED');
  c.clock.t += 200000;
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '501', text: 'y' })).code, 'DUPLICATE');
  c.cleanup();
});

test('Tin 1 nhiều dòng: quá 4 dòng thì gộp phần dư vào tin cuối; phần sau lỗi thì dừng cầu dao nhưng vẫn báo đã gửi (không gửi lại phần đầu)', async () => {
  const c = await ready({ BRIDGE_PART_GAP_SEC: '0' });
  const r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: '1\n2\n3\n4\n5' });
  assert.equal(r.outcome, 'OK');
  assert.deepEqual(c.adapter.sent.map((x) => x.text), ['1', '2', '3', '4\n5']);
  c.cleanup();
  const d = await ready({ BRIDGE_PART_GAP_SEC: '0' });
  let n = 0;
  const orig = d.adapter.sendText.bind(d.adapter);
  d.adapter.sendText = async (uid, t) => (++n === 2 ? ((d.adapter.sendError = null), { message: null, attachment: [] }) : orig(uid, t));
  const r2 = await d.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a\nb' });
  assert.deepEqual(r2, { outcome: 'OK', code: 'PARTIAL', uid: '501' });
  assert.equal(d.bridge.status().state, 'TRIPPED');
  d.cleanup();
});

test('gửi tin theo mã Zalo (tin gửi link): không tìm số, trả uid', async () => {
  const c = await ready();
  const r = await c.bridge.sendMessage({ kind: 'LINK', uid: '777', text: 'Link nhóm' });
  assert.deepEqual(r, { outcome: 'OK', uid: '777' });
  assert.deepEqual(c.adapter.sent, [{ uid: '777', text: 'Link nhóm' }]);
  c.cleanup();
});

test('đầu vào sai bị từ chối trước khi chạm Zalo: loại tin, văn bản trống hoặc quá dài, thiếu hay thừa người nhận, số/mã sai', async () => {
  const c = await ready();
  const bad = [
    { kind: 'X', phone: '84900000001', text: 'a' },
    { kind: 'INVITE', phone: '84900000001', text: '   ' },
    { kind: 'INVITE', phone: '84900000001', text: 'a'.repeat(1001) },
    { kind: 'INVITE', text: 'a' },
    { kind: 'INVITE', phone: '84900000001', uid: '1', text: 'a' },
    { kind: 'INVITE', phone: '12', text: 'a' },
    { kind: 'LINK', uid: '1; rm', text: 'a' },
    { kind: 'LINK', uid: ['1', '2'], text: 'a' },
  ];
  for (const m of bad) assert.equal((await c.bridge.sendMessage(m)).outcome, 'PERSON_ERROR', JSON.stringify(m));
  assert.equal(c.adapter.sent?.length ?? 0, 0);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('khoảng nghỉ cố định giữa hai tin: gửi sớm hơn thì bị từ chối RATE_LIMITED (không dừng, không gọi Zalo), đủ khoảng nghỉ thì gửi', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  c.clock.t += 30 * 1000;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000002', text: 'a' }), { outcome: 'RATE_LIMITED', retryAfterSec: 60 });
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.equal(c.adapter.sent.length, 1);
  c.clock.t += 60 * 1000;
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000002', text: 'a' })).outcome, 'OK');
  assert.equal(c.adapter.sent.length, 2);
  c.cleanup();
});

test('khoảng nghỉ còn nguyên sau khi khởi động lại (đọc từ sổ trên đĩa)', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  const b2 = c.rebuild();
  await b2.start();
  await flush();
  assert.equal(b2.status().state, 'LISTENING');
  assert.equal((await b2.sendMessage({ kind: 'INVITE', phone: '84900000002', text: 'a' })).outcome, 'RATE_LIMITED');
  c.cleanup();
});

test('trần tin mỗi ngày: chạm trần thì dừng cầu cao (cả thêm nhóm), tính cả lần không tìm thấy, và nhớ sau khi khởi động lại', async () => {
  const c = await ready({ BRIDGE_MSG_DAILY: '2' });
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  c.clock.t += GAP;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84911111111', text: 'a' }), { outcome: 'PERSON_ERROR', code: 'NOT_FOUND' });
  c.clock.t += GAP;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000002', text: 'a' }), { outcome: 'RESTRICTED', code: 'MSG_LIMIT' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.equal(c.adapter.sent.length, 1);
  assert.equal((await c.bridge.addMember('2', '3')).outcome, 'RESTRICTED');
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().tripReason, 'MSG_LIMIT');
  c.cleanup();
});

test('ngoài khung giờ thì từ chối và dừng, không gọi Zalo', async () => {
  const c = await ready();
  c.clock.t = Date.parse('2026-11-22T16:00:00Z'); // 23:00 giờ Việt Nam
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' }), { outcome: 'RESTRICTED', code: 'WINDOW' });
  assert.equal(c.bridge.status().tripReason, 'WINDOW');
  assert.equal(c.adapter.sent?.length ?? 0, 0);
  c.cleanup();
});

test('mỗi loại tin chỉ một lần cho một người: lần hai bị từ chối DUPLICATE (theo số lẫn theo mã), loại tin khác vẫn gửi được', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  c.clock.t += GAP;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE', uid: '501' });
  c.clock.t += GAP;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', uid: '501', text: 'a' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE' });
  c.clock.t += GAP;
  assert.equal((await c.bridge.sendMessage({ kind: 'LINK', uid: '501', text: 'link' })).outcome, 'OK');
  assert.equal(c.adapter.sent.length, 2);
  // còn nhớ sau khi khởi động lại; tệp trên đĩa không chứa uid hay số điện thoại đọc được
  const { readFileSync } = await import('node:fs');
  const raw = readFileSync(path.join(c.dir, 'messages.json'), 'utf8');
  assert.ok(!raw.includes('501') && !raw.includes('84900000001'));
  const b2 = c.rebuild();
  await b2.start();
  await flush();
  c.clock.t += GAP;
  assert.equal((await b2.sendMessage({ kind: 'LINK', uid: '501', text: 'link' })).code, 'DUPLICATE');
  c.cleanup();
});

test('không tìm thấy người trên Zalo là lỗi riêng người: không dừng, không gửi gì', async () => {
  const c = await ready();
  const r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84955555555', text: 'a' });
  assert.deepEqual(r, { outcome: 'PERSON_ERROR', code: 'NOT_FOUND' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.equal(c.adapter.sent?.length ?? 0, 0);
  c.cleanup();
});

test('lỗi lạ khi tìm hay gửi thì dừng cầu dao; mã đã khai báo là lỗi riêng người thì không dừng', async () => {
  let c = await ready();
  c.adapter.sendError = Object.assign(new Error('lỗi lạ'), { code: 999 });
  let r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' });
  assert.deepEqual(r, { outcome: 'SYSTEM_ERROR', code: 'Z999', uid: '501' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();

  c = await ready();
  c.adapter.findError = Object.assign(new Error('chưa đăng ký'), { code: 216 });
  r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' });
  assert.equal(r.outcome, 'SYSTEM_ERROR');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();

  c = await ready({ BRIDGE_PERSON_ERROR_CODES: '216' });
  c.adapter.findError = Object.assign(new Error('chưa đăng ký'), { code: 216 });
  r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' });
  assert.deepEqual(r, { outcome: 'PERSON_ERROR', code: 'Z216' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('Tin 1 bị Zalo từ chối vì người nhận không nhận tin người lạ (Z122): lỗi riêng người đó, không dừng, vẫn được kết bạn rồi gửi lại Tin 1', async () => {
  const c = await ready({ BRIDGE_PERSON_ERROR_CODES: '225' }); // 122 nằm ở danh sách riêng, không cần thêm vào mã lỗi người
  c.adapter.sendError = Object.assign(new Error('Không thể nhận tin nhắn từ bạn.'), { code: 122 });
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Chào anh' }), { outcome: 'PERSON_ERROR', code: 'STRANGER_BLOCKED', uid: '501' });
  assert.equal(c.bridge.status().state, 'LISTENING'); // không ngắt cầu dao
  // chưa từng nhận Tin 1 nhưng được kết bạn vì Tin 1 bị chặn; vẫn mỗi người một lời mời
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '501', text: 'Em là shop' }), { outcome: 'OK' });
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '501', text: 'Em là shop' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE' });
  // người khác chưa từng bị chặn thì vẫn không được kết bạn
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '777', text: 'Em là shop' }), { outcome: 'PERSON_ERROR', code: 'NO_INVITE' });
  // gửi lại Tin 1 được (chưa từng ghi "đã gửi Tin 1")
  c.adapter.sendError = null;
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', uid: '501', text: 'Chào anh' }), { outcome: 'OK', uid: '501' });
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', uid: '501', text: 'Chào anh' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE' });
  c.cleanup();
});

test('Z212 (không tìm thấy số điện thoại) khi gửi Tin 1 là lỗi riêng số đó (NOT_FOUND), không dừng cầu dao', async () => {
  const c = await ready({ BRIDGE_PERSON_ERROR_CODES: '' });
  c.adapter.sendError = Object.assign(new Error('Không tìm thấy'), { code: 212 });
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Chào anh' }), { outcome: 'PERSON_ERROR', code: 'NOT_FOUND', uid: '501' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('mã lạ khác Z122 khi gửi Tin 1 vẫn là lỗi lạ và dừng cầu dao', async () => {
  const c = await ready({ BRIDGE_PERSON_ERROR_CODES: '225' });
  c.adapter.sendError = Object.assign(new Error('lỗi lạ'), { code: 999 });
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Chào anh' })).outcome, 'SYSTEM_ERROR');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
});

test('Zalo cảnh báo (RESTRICTED), mất phiên (SESSION_LOST) hay hết giờ chờ khi gửi thì dừng', async () => {
  for (const [err, outcome] of [
    [Object.assign(new Error('Too many requests'), { code: 114 }), 'RESTRICTED'],
    [Object.assign(new Error('cần xác minh captcha'), { code: 5 }), 'RESTRICTED'],
    [Object.assign(new Error('zpw_sek hết hạn'), { code: 600 }), 'SESSION_LOST'],
  ]) {
    const c = await ready();
    c.adapter.sendError = err;
    const r = await c.bridge.sendMessage({ kind: 'LINK', uid: '9', text: 'a' });
    assert.equal(r.outcome, outcome);
    assert.equal(c.bridge.status().state, 'TRIPPED');
    c.cleanup();
  }
});

test('Zalo không xác nhận tin (không có message) thì coi là không biết kết quả và dừng, không ghi là đã gửi', async () => {
  const c = await ready();
  c.adapter.sendText = async () => ({ message: null, attachment: [] });
  const r = await c.bridge.sendMessage({ kind: 'LINK', uid: '9', text: 'a' });
  assert.deepEqual(r, { outcome: 'SYSTEM_ERROR', code: 'NO_ACK', uid: '9' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
});

test('không gửi khi chưa đăng nhập, đang dừng, hay đang bận; sập giữa lúc gửi thì khởi động lại dừng', async () => {
  const c = setup();
  await c.bridge.start();
  assert.equal((await c.bridge.sendMessage({ kind: 'LINK', uid: '9', text: 'a' })).outcome, 'SESSION_LOST');
  await loginAndListen(c);
  c.bridge.stop();
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'LINK', uid: '9', text: 'a' }), { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' });
  await c.bridge.resume();
  await flush();
  c.bridge.busy = true;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'LINK', uid: '9', text: 'a' }), { outcome: 'SYSTEM_ERROR', code: 'BUSY' });
  c.bridge.busy = false;
  const { writeFileSync } = await import('node:fs');
  writeFileSync(path.join(c.dir, 'bridge-state.json'), JSON.stringify({ inflight: true }));
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().tripReason, 'INTERRUPTED');
  assert.equal(c.adapter.sent?.length ?? 0, 0);
  c.cleanup();
});

test('tệp sổ tin hỏng thì dừng ngay; nhật ký không chứa nội dung tin, số điện thoại hay mã Zalo', async () => {
  const c = await ready();
  await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'NOIDUNGBIMAT' });
  assert.ok(!c.logs.join('\n').match(/NOIDUNGBIMAT|84900000001|501/));
  const { writeFileSync } = await import('node:fs');
  writeFileSync(path.join(c.dir, 'messages.json'), '{hỏng');
  const b2 = c.rebuild();
  await b2.start();
  assert.equal(b2.status().state, 'TRIPPED');
  assert.equal(b2.status().tripReason, 'LEDGER_CORRUPT');
  c.cleanup();
});

// ---------- D19: tạo MỘT nhóm theo khu vực ----------

const GROUP_GAP = 300 * 1000;
const NEW_GROUP = { name: 'Áo điều hòa ADH - Miền Nam - 01', members: ['501', '502', '503'], deputy: '900' };

test('tạo nhóm: tạo một nhóm với đúng các thành viên, bật link, thêm phó nhóm, trả mã nhóm và link', async () => {
  const c = await ready();
  const r = await c.bridge.createGroup(NEW_GROUP);
  assert.deepEqual(r, { outcome: 'OK', groupId: '9001', link: 'https://zalo.me/g/fake9001', added: ['501', '502', '503'], failed: [], deputy: 'OK' });
  // mẹ (phó nhóm) vào nhóm ngay lúc tạo, vì Zalo chỉ cho đặt phó nhóm với người đang trong nhóm
  assert.deepEqual(c.adapter.created, [{ name: 'Áo điều hòa ADH - Miền Nam - 01', members: ['501', '502', '503', '900'] }]);
  assert.deepEqual(c.adapter.adds, []);
  assert.deepEqual(c.adapter.deputies, [{ uid: '900', groupId: '9001' }]);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('tạo nhóm: tin chào đầu nhóm gửi đúng một lần vào nhóm vừa tạo, sau khi bật link và đặt phó nhóm, không bị chặn bởi khoảng nghỉ tin riêng', async () => {
  const c = await ready();
  await c.bridge.sendMessage({ kind: 'INVITE', phone: '849000000001', text: 'Tin 1' }); // vừa gửi tin riêng: khoảng nghỉ riêng đang chạy
  const r = await c.bridge.createGroup({ ...NEW_GROUP, welcome: 'Dạ em chào cả nhóm ạ' });
  assert.equal(r.outcome, 'OK');
  assert.equal(r.welcome, 'OK');
  assert.deepEqual(c.adapter.groupSent, [{ groupId: '9001', text: 'Dạ em chào cả nhóm ạ' }]);
  c.cleanup();
});

test('tạo nhóm: tin chào lỗi thì nhóm vẫn tạo xong, báo FAILED kèm mã, không gửi lại', async () => {
  const c = await ready();
  c.adapter.groupSendError = Object.assign(new Error('Không gửi được'), { code: 221 });
  const r = await c.bridge.createGroup({ ...NEW_GROUP, welcome: 'Chào cả nhóm' });
  assert.equal(r.outcome, 'OK');
  assert.equal(r.welcome, 'FAILED');
  assert.equal(c.adapter.groupSent, undefined);
  c.cleanup();
});

test('tạo nhóm: không có tin chào thì không gửi gì vào nhóm; tin chào sai khuôn bị từ chối trước khi chạm Zalo', async () => {
  const c = await ready();
  assert.equal((await c.bridge.createGroup(NEW_GROUP)).welcome, undefined);
  assert.equal(c.adapter.groupSent, undefined);
  for (const bad of ['', '   ', 'Chào {ten}', 'x'.repeat(1501), 5]) {
    const r = await c.bridge.createGroup({ name: 'N', members: ['601'], welcome: bad });
    assert.deepEqual(r, { outcome: 'PERSON_ERROR', code: 'BAD_WELCOME' });
  }
  assert.equal(c.adapter.created.length, 1);
  c.cleanup();
});

test('tạo nhóm: mẹ chưa vào nhóm lúc tạo thì thêm vào trước rồi mới đặt phó nhóm', async () => {
  const c = await ready();
  c.adapter.createFailMembers = ['900'];
  const r = await c.bridge.createGroup(NEW_GROUP);
  assert.equal(r.deputy, 'OK');
  assert.deepEqual(r.added, ['501', '502', '503']); // mẹ không bị tính là khách
  assert.deepEqual(c.adapter.adds, [{ uid: '900', groupId: '9001' }]);
  assert.deepEqual(c.adapter.deputies, [{ uid: '900', groupId: '9001' }]);
  c.cleanup();
});

test('tạo nhóm: đặt phó nhóm lỗi thì báo kèm mã lỗi Zalo thật (không mã người) và ghi vào log', async () => {
  const c = await ready();
  c.adapter.deputyError = Object.assign(new Error('Không đặt được'), { code: 118 });
  const r = await c.bridge.createGroup(NEW_GROUP);
  assert.equal(r.outcome, 'OK');
  assert.equal(r.deputy, 'FAILED');
  assert.equal(r.deputyCode, 'Z118');
  assert.ok(c.logs.join('\n').includes('mã Z118'));
  assert.ok(!c.logs.join('\n').match(/900|9001/));
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('đặt lại phó nhóm: thêm mẹ vào nhóm rồi đặt phó nhóm; sai mã bị từ chối; lỗi trả mã; có trần mỗi ngày', async () => {
  const c = await ready();
  assert.deepEqual(await c.bridge.setDeputy({ groupId: 'abc', uid: '900' }), { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.deepEqual(await c.bridge.setDeputy({ groupId: '9001', uid: '9; rm' }), { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
  assert.deepEqual(c.adapter.deputies ?? [], []);
  assert.deepEqual(await c.bridge.setDeputy({ groupId: '9001', uid: '900' }), { outcome: 'OK', deputy: 'OK' });
  assert.deepEqual(c.adapter.adds, [{ uid: '900', groupId: '9001' }]);
  assert.deepEqual(c.adapter.deputies, [{ uid: '900', groupId: '9001' }]);
  c.adapter.deputyError = Object.assign(new Error('x'), { code: 118 });
  assert.deepEqual(await c.bridge.setDeputy({ groupId: '9001', uid: '900' }), { outcome: 'OK', deputy: 'FAILED', code: 'Z118' });
  for (let i = 0; i < 8; i++) await c.bridge.setDeputy({ groupId: '9001', uid: '900' });
  assert.equal((await c.bridge.setDeputy({ groupId: '9001', uid: '900' })).outcome, 'RATE_LIMITED');
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('tạo nhóm: người không vào được báo ở failed, không dừng', async () => {
  const c = await ready();
  c.adapter.createFailMembers = ['502'];
  const r = await c.bridge.createGroup({ name: 'Nhóm', members: ['501', '502', '503'] });
  assert.equal(r.outcome, 'OK');
  assert.deepEqual(r.added, ['501', '503']);
  assert.deepEqual(r.failed, ['502']);
  assert.equal(r.deputy, 'NONE');
  c.cleanup();
});

test('tạo nhóm: đầu vào sai bị từ chối trước khi chạm Zalo (tên, thành viên, phó nhóm)', async () => {
  const c = await ready();
  const bad = [
    { name: '', members: ['1'] },
    { name: 'a'.repeat(101), members: ['1'] },
    { name: 'a\nb', members: ['1'] },
    { name: 'N', members: [] },
    { name: 'N', members: ['1', '2', '3', '4', '5', '6'] },
    { name: 'N', members: ['1', '1'] },
    { name: 'N', members: ['1; rm'] },
    { name: 'N', members: ['1'], deputy: 'x' },
    { name: 'N', members: ['1'], deputy: '1' },
    { name: 'N', members: '123' },
  ];
  for (const m of bad) assert.equal((await c.bridge.createGroup(m)).outcome, 'PERSON_ERROR', JSON.stringify(m));
  assert.equal(c.adapter.created?.length ?? 0, 0);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('tạo nhóm: khoảng nghỉ cố định, tạo sớm hơn thì RATE_LIMITED (không dừng, không gọi Zalo)', async () => {
  const c = await ready();
  assert.equal((await c.bridge.createGroup(NEW_GROUP)).outcome, 'OK');
  c.clock.t += 60 * 1000;
  assert.deepEqual(await c.bridge.createGroup({ name: 'Nhóm 2', members: ['601'] }), { outcome: 'RATE_LIMITED', retryAfterSec: 240 });
  assert.equal(c.adapter.created.length, 1);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.clock.t += GROUP_GAP;
  assert.equal((await c.bridge.createGroup({ name: 'Nhóm 2', members: ['601'] })).outcome, 'OK');
  c.cleanup();
});

test('tạo nhóm: không có trần số nhóm mỗi ngày, chỉ có khoảng nghỉ cố định (chủ dự án chốt 24/11/2026)', async () => {
  const c = await ready();
  for (const n of ['A', 'B', 'C', 'D', 'E']) {
    assert.equal((await c.bridge.createGroup({ name: n, members: ['601'] })).outcome, 'OK');
    c.clock.t += GROUP_GAP;
  }
  assert.equal(c.adapter.created.length, 5);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('tạo nhóm: cầu nối không còn biến BRIDGE_GROUP_DAILY; nghỉ dưới 300 giây vẫn bị từ chối', () => {
  assert.doesNotThrow(() => setup({ env: { BRIDGE_GROUP_DAILY: '4' } }));
  assert.throws(() => setup({ env: { BRIDGE_GROUP_GAP_SEC: '60' } }), /BRIDGE_GROUP_GAP_SEC/);
});

test('tạo nhóm: ngoài khung giờ thì từ chối và dừng; chưa đăng nhập hoặc đã dừng thì không gọi Zalo', async () => {
  const c = await ready();
  c.clock.t = Date.parse('2026-11-22T17:00:00Z'); // 00:00 giờ Việt Nam
  assert.deepEqual(await c.bridge.createGroup(NEW_GROUP), { outcome: 'RESTRICTED', code: 'WINDOW' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.equal((await c.bridge.createGroup(NEW_GROUP)).outcome, 'RESTRICTED');
  assert.equal(c.adapter.created?.length ?? 0, 0);
  c.cleanup();
  const d = setup();
  await d.bridge.start();
  assert.equal((await d.bridge.createGroup(NEW_GROUP)).outcome, 'SESSION_LOST');
  d.cleanup();
});

test('tạo nhóm: Zalo không trả mã nhóm (không biết đã tạo chưa) thì dừng, không tự thử lại', async () => {
  const c = await ready();
  c.adapter.createNoAck = true;
  assert.deepEqual(await c.bridge.createGroup(NEW_GROUP), { outcome: 'SYSTEM_ERROR', code: 'NO_ACK' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
});

test('tạo nhóm: lỗi lạ khi tạo thì dừng; bật link hoặc thêm phó nhóm lỗi thì KHÔNG dừng, nhóm vẫn báo đã tạo', async () => {
  const c = await ready();
  c.adapter.createError = new Error('lạ');
  assert.equal((await c.bridge.createGroup(NEW_GROUP)).outcome, 'SYSTEM_ERROR');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
  const d = await ready();
  d.adapter.linkError = new Error('link lỗi');
  d.adapter.deputyError = new Error('phó lỗi');
  const r = await d.bridge.createGroup(NEW_GROUP);
  assert.equal(r.outcome, 'OK');
  assert.equal(r.link, null);
  assert.equal(r.deputy, 'FAILED');
  assert.equal(d.bridge.status().state, 'LISTENING');
  d.cleanup();
});

test('tạo nhóm: sổ tạo nhóm lưu ra đĩa nên khởi động lại không xóa số đếm; tệp hỏng thì dừng; log không chứa tên nhóm hay mã Zalo', async () => {
  const c = await ready();
  await c.bridge.createGroup({ name: 'TENBIMAT', members: ['501'], deputy: '900' });
  assert.ok(!c.logs.join('\n').match(/TENBIMAT|501|900|9001/));
  const b2 = c.rebuild();
  assert.equal(b2.groupLedger.times.length, 1);
  const { writeFileSync } = await import('node:fs');
  writeFileSync(path.join(c.dir, 'group-creates.json'), '[hỏng');
  const b3 = c.rebuild();
  await b3.start();
  assert.equal(b3.status().tripReason, 'LEDGER_CORRUPT');
  c.cleanup();
});

// ---------- D19: tra tên và ảnh của MỘT số để xác nhận mẹ ----------

async function readyLookup(env = {}) {
  const c = await ready(env);
  c.adapter.profiles = new Map([['84900000009', { uid: '900', name: 'Mẹ của shop', avatarUrl: 'https://s120.zadn.vn/a.jpg' }]]);
  return c;
}

test('tra người: đúng một số, trả mã, tên và ảnh (data URI); không nhắn, không kết bạn; log không có số, mã hay tên', async () => {
  const c = await readyLookup();
  const r = await c.bridge.lookupPerson({ phone: '84900000009' });
  assert.equal(r.outcome, 'OK');
  assert.equal(r.uid, '900');
  assert.equal(r.name, 'Mẹ của shop');
  assert.match(r.avatar, /^data:image\/png;base64,/);
  assert.deepEqual(c.adapter.profileLookups, ['84900000009']);
  assert.equal(c.adapter.sent, undefined);
  assert.equal(c.adapter.adds.length, 0);
  assert.ok(!c.logs.join('\n').match(/84900000009|900|Mẹ/));
  c.cleanup();
});

test('tra người: số sai bị từ chối trước khi chạm Zalo; không thấy thì NOT_FOUND (không dừng); ảnh lỗi thì chỉ mất ảnh', async () => {
  const c = await readyLookup();
  for (const phone of [undefined, null, '', '12', '1'.repeat(16), 'abc']) {
    assert.deepEqual(await c.bridge.lookupPerson({ phone }), { outcome: 'PERSON_ERROR', code: 'BAD_PHONE' }, String(phone));
  }
  assert.equal(c.adapter.profileLookups, undefined);
  assert.deepEqual(await c.bridge.lookupPerson({ phone: '84900000010' }), { outcome: 'PERSON_ERROR', code: 'NOT_FOUND' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.adapter.avatarError = new Error('ảnh hỏng');
  const r = await c.bridge.lookupPerson({ phone: '84900000009' });
  assert.equal(r.outcome, 'OK');
  assert.equal(r.avatar, null);
  c.cleanup();
});

test('tra người: trần mỗi giờ công khai và cố định (mặc định 5), quá thì RATE_LIMITED, không dừng, hết giờ thì được lại', async () => {
  const c = await readyLookup();
  for (let i = 0; i < 5; i += 1) assert.equal((await c.bridge.lookupPerson({ phone: '84900000009' })).outcome, 'OK');
  const r = await c.bridge.lookupPerson({ phone: '84900000009' });
  assert.equal(r.outcome, 'RATE_LIMITED');
  assert.ok(r.retryAfterSec > 0);
  assert.equal(c.adapter.profileLookups.length, 5);
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.clock.t += 61 * 60 * 1000;
  assert.equal((await c.bridge.lookupPerson({ phone: '84900000009' })).outcome, 'OK');
  c.cleanup();
});

test('tra người: lỗi lạ thì dừng cầu dao; chưa đăng nhập, đã dừng hay ngoài khung giờ thì không gọi Zalo', async () => {
  const c = await readyLookup();
  c.adapter.profileError = new Error('lạ');
  assert.equal((await c.bridge.lookupPerson({ phone: '84900000009' })).outcome, 'SYSTEM_ERROR');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.deepEqual(await c.bridge.lookupPerson({ phone: '84900000009' }), { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' });
  c.cleanup();
  const d = await readyLookup();
  d.clock.t += 14 * 3600 * 1000; // 00:00 giờ Việt Nam hôm sau
  assert.deepEqual(await d.bridge.lookupPerson({ phone: '84900000009' }), { outcome: 'RESTRICTED', code: 'WINDOW' });
  assert.equal(d.adapter.profileLookups, undefined);
  assert.equal(d.bridge.status().state, 'LISTENING');
  d.cleanup();
  const e = setup();
  await e.bridge.start();
  assert.equal((await e.bridge.lookupPerson({ phone: '84900000009' })).outcome, 'SESSION_LOST');
  e.cleanup();
});

// ---------- đọc tin riêng để nhận diện từ chối (D19) ----------

test('tin riêng: tắt mặc định thì bộ nối không đăng ký nghe tin và /inbox rỗng', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal(c.adapter.handlers.onMessage, undefined);
  c.adapter.emitMessage('777', 'đừng nhắn nữa');
  assert.deepEqual(c.bridge.inboxMessages(0), []);
  c.cleanup();
});

test('tin riêng: bật thì chỉ giữ tin của người đã nhận Tin 1 hoặc Tin 2 từ chính bộ nối; người lạ bị bỏ, không vào log', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal(typeof c.adapter.handlers.onMessage, 'function');
  c.adapter.emitMessage('555', 'tôi là người lạ');
  assert.deepEqual(c.bridge.inboxMessages(0), []);
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào anh' })).outcome, 'OK');
  c.adapter.emitMessage('777', '  Đừng nhắn nữa nhé  ');
  c.adapter.emitMessage('777', '   ');
  c.adapter.emitMessage('abc', 'sai mã');
  const got = c.bridge.inboxMessages(0);
  assert.equal(got.length, 1);
  assert.equal(got[0].uid, '777');
  assert.equal(got[0].text, 'Đừng nhắn nữa nhé');
  assert.ok(Number.isInteger(got[0].seq));
  assert.ok(!c.logs.join('\n').includes('777') && !c.logs.join('\n').includes('Đừng'));
  c.cleanup();
});

test('tin riêng: cắt 500 ký tự, xác nhận (after) thì bỏ tin cũ, quá hạn thì tự bỏ', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: '1' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  c.adapter.emitMessage('777', 'a'.repeat(900));
  const first = c.bridge.inboxMessages(0);
  assert.equal(first[0].text.length, 500);
  c.clock.t += 1000;
  c.adapter.emitMessage('777', 'tin hai');
  const after = c.bridge.inboxMessages(first[0].seq);
  assert.deepEqual(after.map((m) => m.text), ['tin hai']);
  assert.deepEqual(c.bridge.inboxMessages(0).map((m) => m.text), ['tin hai']);
  c.clock.t += 25 * 3600 * 1000;
  assert.deepEqual(c.bridge.inboxMessages(0), []);
  c.cleanup();
});

// ---------- thả tim tin khách (24/11/2026) ----------

test('thả tim: chỉ thả vào tin riêng đã nhận, một lần mỗi tin, khoảng nghỉ cố định, tin lạ bị từ chối', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  c.adapter.emitMessage('777', 'Cảm ơn');
  c.adapter.emitMessage('777', 'tin hai');
  const [a, b] = c.bridge.inboxMessages(0);
  assert.deepEqual(await c.bridge.react({ seq: 999999 }), { outcome: 'PERSON_ERROR', code: 'NOT_REACTABLE' });
  assert.deepEqual(await c.bridge.react({ seq: a.seq }), { outcome: 'OK' });
  assert.equal(c.adapter.hearts.length, 1);
  assert.equal(c.adapter.hearts[0].uid, '777');
  assert.deepEqual(await c.bridge.react({ seq: a.seq }), { outcome: 'PERSON_ERROR', code: 'NOT_REACTABLE' });
  assert.equal((await c.bridge.react({ seq: b.seq })).outcome, 'RATE_LIMITED');
  c.clock.t += 4000;
  assert.equal((await c.bridge.react({ seq: b.seq })).outcome, 'OK');
  assert.ok(!c.logs.join('\n').includes('777'));
  c.cleanup();
});

test('thả tim: tắt đọc tin riêng thì từ chối; quá 24 giờ thì từ chối; lỗi lạ thì dừng cầu dao', async () => {
  const off = setup();
  await off.bridge.start();
  await loginAndListen(off);
  assert.deepEqual(await off.bridge.react({ seq: 1 }), { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' });
  off.cleanup();
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  c.adapter.emitMessage('777', 'ok');
  const [m] = c.bridge.inboxMessages(0);
  c.clock.t += 25 * 3600 * 1000;
  assert.equal((await c.bridge.react({ seq: m.seq })).code, 'NOT_REACTABLE');
  c.adapter.emitMessage('777', 'ok 2');
  const m2 = c.bridge.inboxMessages(0).at(-1);
  c.adapter.heartError = new Error('lạ');
  assert.equal((await c.bridge.react({ seq: m2.seq })).outcome, 'SYSTEM_ERROR');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
});

// ---------- tin mẫu trả lời người đã nhắn trước (WELCOME, D19) ----------

test('WELCOME: chỉ theo mã Zalo, cần bật đọc tin riêng và người đó phải vừa nhắn tới; phone hoặc người chưa nhắn đều bị từ chối trước khi chạm Zalo', async () => {
  const off = setup();
  await off.bridge.start();
  await loginAndListen(off);
  assert.equal((await off.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Chào' })).code, 'BAD_INPUT'); // chưa bật cờ
  off.cleanup();

  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' });
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', phone: '84900000001', text: 'Chào' })).code, 'BAD_INPUT');
  const sentBefore = c.adapter.sent.length;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Chào' })).code, 'NOT_REPLYABLE'); // chưa nhắn tới
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '888', text: 'Chào' })).code, 'NOT_REPLYABLE'); // người lạ
  assert.equal(c.adapter.sent.length, sentBefore);
  assert.equal(c.bridge.status().state, 'LISTENING'); // từ chối kiểu này không làm cầu dao nhảy
  c.cleanup();
});

test('tin trả lời (WELCOME, REPLY) có khoảng nghỉ cố định riêng 30 giây; tin chủ động vẫn theo khoảng nghỉ 90 giây', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' })).outcome, 'OK');
  c.adapter.emitMessage('777', 'Ưu đãi như nào');
  c.clock.t += 10000;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' }), { outcome: 'RATE_LIMITED', retryAfterSec: 20 });
  c.clock.t += 21000; // 31 giây sau tin trước
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' })).outcome, 'OK');
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '778', text: 'Mời' })).outcome, 'RATE_LIMITED'); // tin chủ động chưa đủ 90 giây
  c.adapter.emitMessage('777', 'Cho hỏi thêm');
  c.clock.t += 31000;
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '778', text: 'Mời' })).outcome, 'RATE_LIMITED'); // mới 31 giây
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' })).outcome, 'OK');
  c.cleanup();
});

test('WELCOME: mỗi tin đến chỉ được trả lời một lần, quá 24 giờ thì thôi, tin mới thì được lại; vẫn chịu khoảng nghỉ và trần ngày chung', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' });
  c.adapter.emitMessage('777', 'Em muốn mua thêm');
  const early = await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Chào' });
  assert.equal(early.outcome, 'RATE_LIMITED'); // vừa gửi Tin 1 xong, chưa đủ khoảng nghỉ
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Dạ em chào' })).outcome, 'OK');
  assert.deepEqual(c.adapter.sent.at(-1), { uid: '777', text: 'Dạ em chào' });
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Dạ em chào' })).code, 'NOT_REPLYABLE'); // đã trả lời tin đó
  c.adapter.emitMessage('777', 'Cho em hỏi nữa');
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Dạ em chào' })).outcome, 'OK'); // tin mới, được lại
  c.adapter.emitMessage('777', 'Alo');
  c.clock.t += 25 * 3600 * 1000;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Dạ em chào' })).code, 'NOT_REPLYABLE'); // quá 24 giờ
  c.cleanup();
});

test('WELCOME không đánh dấu sổ đã gửi nên không chặn nhầm Tin 1 hay Tin 2; lỗi gửi vẫn tiêu tin đến đó (không tự thử lại)', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' });
  c.adapter.emitMessage('777', 'ok');
  c.clock.t += 120000;
  await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Chào' });
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'LINK', uid: '777', text: 'Link' })).outcome, 'OK');
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' })).code, 'DUPLICATE'); // Tin 1 vẫn chỉ một lần
  c.cleanup();
});

// ---------- câu trả lời do trợ lý AI soạn (REPLY, D20): cùng chốt chặn như WELCOME ----------

test('REPLY: chỉ theo mã Zalo, cần cờ đọc tin riêng, chỉ trả lời người vừa nhắn tới, mỗi tin đến một lần, quá 24 giờ thì thôi', async () => {
  const off = setup();
  await off.bridge.start();
  await loginAndListen(off);
  assert.equal((await off.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' })).code, 'BAD_INPUT'); // chưa bật cờ
  off.cleanup();

  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' });
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', phone: '84900000001', text: 'Dạ' })).code, 'BAD_INPUT'); // không theo số điện thoại
  const before = c.adapter.sent.length;
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' })).code, 'NOT_REPLYABLE'); // chưa nhắn tới
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '888', text: 'Dạ' })).code, 'NOT_REPLYABLE'); // người lạ
  assert.equal(c.adapter.sent.length, before);
  c.adapter.emitMessage('777', 'Vào nhóm có mất phí không');
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ không ạ' })).outcome, 'OK');
  assert.deepEqual(c.adapter.sent.at(-1), { uid: '777', text: 'Dạ không ạ' });
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ không ạ' })).code, 'NOT_REPLYABLE'); // đã trả lời tin đó
  c.adapter.emitMessage('777', 'Alo');
  c.clock.t += 25 * 3600 * 1000;
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' })).code, 'NOT_REPLYABLE'); // quá 24 giờ
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('REPLY không đánh dấu sổ đã gửi nên không chặn nhầm Tin 1 hay Tin 2; WELCOME và REPLY dùng chung một lượt trả lời cho mỗi tin đến', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' });
  c.adapter.emitMessage('777', 'ok');
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'Dạ' })).outcome, 'OK');
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'WELCOME', uid: '777', text: 'Chào' })).code, 'NOT_REPLYABLE'); // tin đó đã được trả lời
  assert.equal((await c.bridge.sendMessage({ kind: 'LINK', uid: '777', text: 'Link' })).outcome, 'OK');
  c.clock.t += 120000;
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' })).code, 'DUPLICATE');
  c.cleanup();
});

// ---------- tin nhắc của chiến dịch nhiều lần gửi (FOLLOWUP2, FOLLOWUP3; D21) ----------

test('FOLLOWUP: cần bật đọc tin riêng; không bật thì từ chối trước khi chạm Zalo', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Mời' })).outcome, 'OK');
  c.clock.t += GAP;
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '501', text: 'Nhắc' })).code, 'BAD_INPUT');
  assert.equal(c.adapter.sent.length, 1);
  c.cleanup();
});

test('FOLLOWUP: chỉ theo mã Zalo, chỉ cho người đã nhận Tin 1 qua cầu nối này; người lạ hay người chỉ nhận Tin 2 bị từ chối trước khi chạm Zalo', async () => {
  const c = await ready({ BRIDGE_INBOX_ENABLED: 'true' });
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', phone: '84900000001', text: 'Nhắc' })).code, 'BAD_INPUT'); // không theo số điện thoại
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP4', uid: '501', text: 'Nhắc' })).code, 'BAD_INPUT'); // không có lần thứ tư
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '888', text: 'Nhắc' })).code, 'NO_INVITE'); // người lạ
  assert.equal((await c.bridge.sendMessage({ kind: 'LINK', uid: '501', text: 'Link' })).outcome, 'OK');
  c.clock.t += GAP;
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '501', text: 'Nhắc' })).code, 'NO_INVITE'); // chỉ có Tin 2, chưa có Tin 1
  assert.equal(c.adapter.sent.length, 1);
  assert.equal(c.bridge.status().state, 'LISTENING'); // từ chối kiểu này không làm cầu dao nhảy
  c.cleanup();
});

test('FOLLOWUP: mỗi lần nhắc một lần cho một người, lần ba cần lần hai đã gửi, vẫn chịu khoảng nghỉ chung, và không chặn nhầm Tin 1 hay Tin 2', async () => {
  const c = await ready({ BRIDGE_INBOX_ENABLED: 'true' });
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Mời' })).outcome, 'OK');
  c.clock.t += GAP;
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP3', uid: '501', text: 'Nhắc 3' })).code, 'NO_PREVIOUS'); // chưa có lần hai
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '501', text: 'Nhắc 2' })).outcome, 'OK');
  assert.deepEqual(c.adapter.sent.at(-1), { uid: '501', text: 'Nhắc 2' });
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'FOLLOWUP3', uid: '501', text: 'Nhắc 3' }), { outcome: 'RATE_LIMITED', retryAfterSec: 90 }); // chưa đủ nghỉ
  c.clock.t += GAP;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '501', text: 'Nhắc 2' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE' });
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP3', uid: '501', text: 'Nhắc 3' })).outcome, 'OK');
  c.clock.t += GAP;
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'FOLLOWUP3', uid: '501', text: 'Nhắc 3' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE' });
  assert.equal((await c.bridge.sendMessage({ kind: 'LINK', uid: '501', text: 'Link' })).outcome, 'OK'); // Tin 2 vẫn gửi được
  assert.equal(c.adapter.sent.length, 4);
  c.cleanup();
});

test('FOLLOWUP: không nhắc người vừa tự nhắn tới trong 24 giờ, qua 24 giờ thì bộ nối không chặn nữa và phía Adh tự kiểm điều kiện dừng', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Mời' });
  c.adapter.emitMessage('777', 'Cho em hỏi');
  c.clock.t += 120000;
  const before = c.adapter.sent.length;
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '777', text: 'Nhắc' })).code, 'CUSTOMER_REPLIED');
  assert.equal(c.adapter.sent.length, before);
  c.clock.t += 25 * 3600 * 1000;
  assert.equal((await c.bridge.sendMessage({ kind: 'FOLLOWUP2', uid: '777', text: 'Nhắc' })).outcome, 'OK'); // tin của khách đã quá 24 giờ: bộ nối không còn căn cứ chặn (Adh tự kiểm điều kiện dừng)
  c.cleanup();
});

// ---------- lời mời kết bạn (23/11/2026): chỉ cho người đã nhận Tin 1 ----------

test('lời mời kết bạn: chỉ người đã nhận Tin 1, mỗi người một lần, đúng một lời mời cho đúng một người', async () => {
  const c = await ready();
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '501', text: 'Em là shop' }), { outcome: 'PERSON_ERROR', code: 'NO_INVITE' });
  assert.equal(c.adapter.friendRequests, undefined);
  assert.deepEqual(await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'Chào anh' }), { outcome: 'OK', uid: '501' });
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '501', text: 'Em là shop' }), { outcome: 'OK' });
  assert.deepEqual(c.adapter.friendRequests, [{ uid: '501', text: 'Em là shop' }]);
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '501', text: 'Em là shop' }), { outcome: 'PERSON_ERROR', code: 'DUPLICATE' });
  assert.equal(c.adapter.friendRequests.length, 1);
  c.cleanup();
});

test('lời mời kết bạn: đầu vào sai bị từ chối trước khi chạm Zalo', async () => {
  const c = await ready();
  for (const m of [{}, { uid: '501' }, { uid: '501', text: '   ' }, { uid: '501', text: 'x'.repeat(151) }, { uid: 'abc', text: 'a' }, { text: 'a' }]) {
    assert.deepEqual(await c.bridge.sendFriendRequest(m), { outcome: 'PERSON_ERROR', code: 'BAD_INPUT' }, JSON.stringify(m));
  }
  assert.equal(c.adapter.friendRequests, undefined);
  c.cleanup();
});

test('lời mời kết bạn: dùng chung khoảng nghỉ cố định với tin, gửi sớm thì RATE_LIMITED và không gọi Zalo', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  c.clock.t += 3 * 1000;
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '501', text: 'a' }), { outcome: 'RATE_LIMITED', retryAfterSec: 4 });
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.equal(c.adapter.friendRequests, undefined);
  c.cleanup();
});

test('lời mời kết bạn: sau Tin 1 của CHÍNH người đó chỉ chờ 7 giây cố định; người khác vẫn chờ khoảng nghỉ chung', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK'); // uid 501
  c.clock.t += 8 * 1000;
  assert.equal((await c.bridge.sendFriendRequest({ uid: '501', text: 'a' })).outcome, 'OK');
  // tin gần nhất là lời mời kết bạn, không phải Tin 1: người kế tiếp vẫn phải đủ khoảng nghỉ chung
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000002', text: 'a' })).outcome, 'RATE_LIMITED');
  c.cleanup();
});

test('lời mời kết bạn: Tin 1 bị chặn người lạ thì lời mời của chính người đó đi ngay, không chờ', async () => {
  const c = await ready();
  c.adapter.sendError = Object.assign(new Error('x'), { code: 122 });
  const r = await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' });
  assert.equal(r.code, 'STRANGER_BLOCKED');
  assert.equal((await c.bridge.sendFriendRequest({ uid: r.uid, text: 'a' })).outcome, 'OK');
  c.cleanup();
});

test('lời mời kết bạn: chạm trần lời mời mỗi ngày thì dừng cầu dao; ngoài khung giờ cũng dừng', async () => {
  const c = await ready({ BRIDGE_FRIEND_DAILY: '1' });
  for (const [p, u] of [['84900000001', '501'], ['84900000002', '502']]) {
    assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: p, text: 'a' })).outcome, 'OK');
    c.clock.t += GAP + 1000;
  }
  assert.equal((await c.bridge.sendFriendRequest({ uid: '501', text: 'a' })).outcome, 'OK');
  c.clock.t += GAP + 1000;
  assert.deepEqual(await c.bridge.sendFriendRequest({ uid: '502', text: 'a' }), { outcome: 'RESTRICTED', code: 'FRIEND_LIMIT' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.equal(c.adapter.friendRequests.length, 1);
  c.cleanup();
});

test('lời mời kết bạn: lỗi lạ của Zalo thì dừng cầu dao, không tự gửi lại', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  c.clock.t += GAP + 1000;
  const e = new Error('x');
  e.code = 999999;
  c.adapter.friendError = e;
  const r = await c.bridge.sendFriendRequest({ uid: '501', text: 'a' });
  assert.notEqual(r.outcome, 'OK');
  assert.notEqual(r.outcome, 'PERSON_ERROR');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  c.cleanup();
});

test('lời mời kết bạn: không ghi uid, số điện thoại hay nội dung vào log', async () => {
  const c = await ready();
  assert.equal((await c.bridge.sendMessage({ kind: 'INVITE', phone: '84900000001', text: 'a' })).outcome, 'OK');
  c.clock.t += GAP + 1000;
  await c.bridge.sendFriendRequest({ uid: '501', text: 'Câu bí mật' });
  assert.ok(!c.logs.some((l) => l.includes('501') || l.includes('Câu bí mật') || l.includes('84900000001')));
  c.cleanup();
});

// ---------- 24/11/2026: đối chiếu danh sách bạn, chỉ đọc ----------

test('đối chiếu bạn bè: chỉ trả lại mã Adh hỏi mà đã là bạn, không lộ người ngoài danh sách hỏi', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.friends = new Set(['501', '502', '999']);
  const r = await c.bridge.checkFriends(['501', '503', '999']);
  assert.deepEqual(r, { ok: true, friends: ['501', '999'] });
  assert.ok(!JSON.stringify(r).includes('502'));
  assert.equal(c.bridge.status().state, 'LISTENING');
  assert.deepEqual(c.adapter.adds, []);
  c.cleanup();
});

test('đối chiếu bạn bè: đầu vào sai, chưa nghe, ngoài khung giờ thì không đọc Zalo', async () => {
  const c = setup();
  await c.bridge.start();
  assert.equal((await c.bridge.checkFriends(['501'])).ok, false); // chưa đăng nhập
  await loginAndListen(c);
  for (const bad of [null, [], ['abc'], [501], Array.from({ length: 501 }, (_, i) => String(i + 1))]) {
    assert.deepEqual(await c.bridge.checkFriends(bad), { ok: false, code: 'BAD_INPUT' });
  }
  assert.equal(c.adapter.friendReads ?? 0, 0);
  c.cleanup();
});

test('đối chiếu bạn bè: giữa hai lần đọc Zalo có khoảng nghỉ cố định (trả từ bản đã đọc) và có trần mỗi ngày', async () => {
  const c = setup({ env: { BRIDGE_FRIEND_SYNC_DAILY: '2' } });
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.friends = new Set(['501']);
  await c.bridge.checkFriends(['501']);
  c.adapter.friends = new Set(['501', '502']);
  assert.deepEqual((await c.bridge.checkFriends(['502'])).friends, []); // vẫn trong khoảng nghỉ: bản cũ, không hỏi Zalo
  assert.equal(c.adapter.friendReads, 1);
  c.clock.t += 11 * 60_000; // qua khoảng nghỉ: được đọc lại và thấy người mới
  assert.deepEqual((await c.bridge.checkFriends(['502'])).friends, ['502']);
  assert.equal(c.adapter.friendReads, 2);
  c.clock.t += 11 * 60_000; // trần 2 lần mỗi ngày
  assert.deepEqual(await c.bridge.checkFriends(['502']), { ok: false, code: 'LIMIT' });
  assert.equal(c.adapter.friendReads, 2);
  c.cleanup();
});

test('đối chiếu bạn bè: lỗi đọc thì trả ok=false và KHÔNG làm cầu nối dừng', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.adapter.friendError = new Error('gì đó lạ');
  assert.deepEqual(await c.bridge.checkFriends(['501']), { ok: false, code: 'LIST_FAILED' });
  assert.equal(c.bridge.status().state, 'LISTENING');
  c.cleanup();
});

test('BR-01: mã số thứ tự sự kiện kết bạn không quay lại sau khi bộ nối khởi động lại (Adh không bỏ lỡ sự kiện mới vì con trỏ cũ)', async () => {
  const { EventBuffer } = await import('../src/events.mjs');
  let t = Date.parse('2026-11-22T03:00:00Z');
  const first = new EventBuffer({ now: () => t });
  const a = first.push({ uid: '1', phone: null });
  t += 5 * 3600_000; // bộ nối sập rồi khởi động lại sau năm giờ: bộ đệm mới, trống
  const second = new EventBuffer({ now: () => t });
  const b = second.push({ uid: '2', phone: null });
  assert.ok(b > a, 'seq mới phải lớn hơn seq cũ mà Adh đã nhớ');
  assert.deepEqual(second.after(a).map((e) => e.uid), ['2']);
});

// ---------- không mất tin riêng của khách (cầu dao chỉ chặn GỬI, tin lưu bền) ----------

test('tin riêng: cầu dao nhảy vì lỗi thường thì VẪN nhận tin (chỉ chặn gửi)', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  c.bridge.trip('SYSTEM_ERROR:UNKNOWN');
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.equal(c.adapter.listening, true);
  c.adapter.emitMessage('777', 'Ok');
  assert.deepEqual(c.bridge.inboxMessages(0).map((m) => m.text), ['Ok']);
  assert.equal((await c.bridge.sendMessage({ kind: 'REPLY', uid: '777', text: 'x' })).code, 'NOT_ACTIVE');
  c.cleanup();
});

test('tin riêng: lỗi phiên, Zalo hạn chế hay dừng tay thì đóng hẳn, không nhận', async () => {
  for (const reason of ['SESSION_LOST', 'RESTRICTED:Z1', 'STOP', 'KICKED', 'DUPLICATE_CONNECTION']) {
    const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
    await c.bridge.start();
    await loginAndListen(c);
    await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
    c.bridge.trip(reason);
    assert.equal(c.adapter.listening, false, reason);
    c.adapter.emitMessage('777', 'Ok');
    assert.deepEqual(c.bridge.inboxMessages(0), [], reason);
    c.cleanup();
  }
});

test('tin riêng: tắt cờ nghe tin thì cầu dao đóng hẳn như cũ', async () => {
  const c = setup();
  await c.bridge.start();
  await loginAndListen(c);
  c.bridge.trip('LIMIT');
  assert.equal(c.adapter.listening, false);
  c.cleanup();
});

test('tin riêng: khởi động lại khi đang dừng gửi (lỗi thường) thì nối lại chỉ để nhận, vẫn TRIPPED, tin chưa lấy không mất', async () => {
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  c.adapter.emitMessage('777', 'tin trước khi dừng');
  c.bridge.trip('MSG_LIMIT');
  c.adapter.stopListening();
  const b2 = c.rebuild();
  await b2.start();
  await flush();
  await flush();
  assert.equal(b2.status().state, 'TRIPPED');
  assert.equal(c.adapter.listening, true);
  assert.deepEqual(b2.inboxMessages(0).map((m) => m.text), ['tin trước khi dừng']); // tin lưu bền qua khởi động lại
  c.adapter.emitMessage('777', 'Ok');
  assert.deepEqual(b2.inboxMessages(0).map((m) => m.text), ['tin trước khi dừng', 'Ok']);
  assert.equal((await b2.sendMessage({ kind: 'REPLY', uid: '777', text: 'x' })).code, 'NOT_ACTIVE');
  c.cleanup();
});

test('tin riêng: tệp hộp thư mã hóa, không có chữ rõ, xác nhận thì xóa khỏi tệp', async () => {
  const { readFileSync } = await import('node:fs');
  const c = setup({ env: { BRIDGE_INBOX_ENABLED: 'true' } });
  await c.bridge.start();
  await loginAndListen(c);
  await c.bridge.sendMessage({ kind: 'INVITE', uid: '777', text: 'Chào' });
  c.adapter.emitMessage('777', 'noi dung bi mat');
  const raw = readFileSync(path.join(c.dir, 'inbox.enc'), 'utf8');
  assert.ok(!raw.includes('noi dung bi mat') && !raw.includes('777'));
  const seq = c.bridge.inboxMessages(0)[0].seq;
  c.bridge.inboxMessages(seq);
  const b2 = c.rebuild();
  assert.deepEqual(b2.inboxMessages(0), []);
  c.cleanup();
});
