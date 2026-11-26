import test from 'node:test';
import assert from 'node:assert/strict';
import { setup, loginAndListen, flush } from './helpers.mjs';

const UID = '501';
const OUTGOING = { is_friend: 0, is_requested: 0, is_requesting: 1 };
const EXPECTED = { ok: true, state: 'OUTGOING' };
const CLOSED = { ok: false, code: 'NOT_ACTIVE' };
const DISALLOWED = { outcome: 'RESTRICTED', code: 'NOT_ACTIVE' };
const ENABLED = { BRIDGE_FRIEND_RECONCILE_ENABLED: 'true' };
const INVITE = { uid: UID, text: 'Lời mời thử' };

async function account(t, env = ENABLED) {
  const c = setup({ env });
  t.after(() => c.cleanup());
  await c.bridge.start();
  await loginAndListen(c);
  c.bridge.msgLedger.markSent('INVITE', UID);
  c.adapter.friendRequestStates = new Map([[UID, OUTGOING]]);
  return c;
}

test('đối chiếu lời mời sau lỗi gửi vẫn đọc được khi inbox tắt, mọi lần gửi bị chặn', async (t) => {
  const c = await account(t);
  let calls = 0;
  c.adapter.sendFriendRequest = async () => {
    calls += 1;
    throw Object.assign(new Error('hết giờ chờ'), { name: 'TimeoutError' });
  };

  assert.deepEqual(await c.bridge.sendFriendRequest(INVITE), { outcome: 'SYSTEM_ERROR', code: 'TIMEOUT' });
  assert.equal(c.bridge.status().state, 'TRIPPED');
  assert.equal(c.bridge.status().friendReconcileAvailable, true);
  assert.deepEqual(await c.bridge.readFriendRequestStatus(UID), EXPECTED);
  assert.deepEqual(c.bridge.inboxMessages(0), []);
  assert.deepEqual(await c.bridge.sendFriendRequest(INVITE), DISALLOWED);
  assert.equal(calls, 1);
});

test('khởi động lại sau lỗi mềm đọc được khi đã đúng tài khoản và kết nối, không mở gửi', async (t) => {
  const c = await account(t);
  c.bridge.trip('SYSTEM_ERROR:TIMEOUT');
  c.adapter.stopListening();
  const restarted = c.rebuild();
  const listen = c.adapter.listen.bind(c.adapter);
  let confirmConnection;
  c.adapter.listen = (handlers) => listen({
    ...handlers,
    onConnected: () => { confirmConnection = handlers.onConnected; },
  });
  await restarted.start();
  await flush();

  assert.equal(restarted.status().state, 'TRIPPED');
  assert.deepEqual(await restarted.readFriendRequestStatus(UID), CLOSED);
  confirmConnection();
  assert.equal(restarted.status().accountOk, true);
  assert.equal(restarted.status().friendReconcileAvailable, true);
  assert.deepEqual(await restarted.readFriendRequestStatus(UID), EXPECTED);
  assert.deepEqual(await restarted.sendFriendRequest(INVITE), DISALLOWED);
  assert.equal(c.adapter.friendRequests, undefined);
});

test('khi tắt cờ đối chiếu, lỗi mềm không giữ phiên nếu inbox cũng tắt', async (t) => {
  const c = await account(t, {});
  c.bridge.trip('SYSTEM_ERROR:NO_ACK');
  assert.equal(c.bridge.status().friendReconcileAvailable, false);
  assert.deepEqual(await c.bridge.readFriendRequestStatus(UID), CLOSED);
  assert.equal(c.adapter.listening, false);
});

test('sai tài khoản khi khôi phục, dừng tay, hạn chế và mất phiên đều chặn đọc', async (t) => {
  const c = await account(t);
  c.bridge.trip('SYSTEM_ERROR:TIMEOUT');
  c.adapter.stopListening();
  c.adapter.ownId = '9999';
  const restarted = c.rebuild();
  await restarted.start();
  await flush();
  assert.equal(restarted.status().friendReconcileAvailable, false);
  assert.deepEqual(await restarted.readFriendRequestStatus(UID), CLOSED);
  assert.equal(c.adapter.listening, false);

  for (const reason of ['STOP', 'RESTRICTED:Z1', 'SESSION_LOST:SESSION', 'SYSTEM_ERROR:Z429']) {
    await t.test(reason, async (subtest) => {
      const active = await account(subtest);
      active.bridge.trip(reason);
      assert.equal(active.bridge.status().friendReconcileAvailable, false);
      assert.deepEqual(await active.bridge.readFriendRequestStatus(UID), CLOSED);
      assert.equal(active.adapter.listening, false);
    });
  }
});

test('phiên đọc lại nhận mã đăng nhập trùng thì dừng hẳn, không tự nối lại', async (t) => {
  const c = await account(t);
  c.bridge.trip('SYSTEM_ERROR:TIMEOUT');
  const restores = c.adapter.restores;
  c.adapter.handlers.onDisconnected(3000);
  await flush();
  assert.equal(c.bridge.status().tripReason, 'DUPLICATE_CONNECTION');
  assert.equal(c.adapter.restores, restores);
  assert.deepEqual(await c.bridge.readFriendRequestStatus(UID), CLOSED);
});

test('Zalo báo hạn chế trong lúc chỉ đọc thì đóng phiên và không gọi đọc lần nữa', async (t) => {
  const c = await account(t);
  c.bridge.trip('SYSTEM_ERROR:TIMEOUT');
  let calls = 0;
  c.adapter.friendRequestStatus = async () => {
    calls += 1;
    throw new Error('Zalo yêu cầu xác minh');
  };
  assert.deepEqual(await c.bridge.readFriendRequestStatus(UID), { ok: false, code: 'RESTRICTED' });
  assert.equal(c.bridge.status().friendReconcileAvailable, false);
  assert.deepEqual(await c.bridge.readFriendRequestStatus(UID), CLOSED);
  assert.equal(calls, 1);
});
