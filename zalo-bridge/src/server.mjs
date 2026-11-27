// Máy chủ HTTP nội bộ (node:http, không thêm thư viện). Hợp đồng: docs/ZALO-BRIDGE-API.md.
// Có đúng các đường: /healthz, /status, /events, GET /inbox (D19: tin riêng của người đã nhận tin của hệ thống; tắt mặc định), GET /groups (chỉ đọc), POST /groups (D19: tạo MỘT nhóm), POST /groups/{id}/members, POST /messages (D18: một tin cho một người), POST /friend-requests (một lời mời kết bạn cho một người đã nhận Tin 1), POST /people/lookup (D19: tra tên và ảnh của MỘT số để xác nhận mẹ),
// /session/*. KHÔNG có đường gửi hàng loạt, gửi lời mời kết bạn, tìm người theo số rời, hay liệt kê bạn bè. server.test.mjs khóa danh sách này.

import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { ZALO_ID } from './guard.mjs';

const MAX_BODY = 4096;

function sameKey(a, b) {
  const x = Buffer.from(a ?? '');
  const y = Buffer.from(b ?? '');
  return x.length === y.length && timingSafeEqual(x, y);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('quá lớn'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(Object.assign(new Error('JSON sai'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

/** Danh sách đường được phép (để test và tài liệu). */
export const ROUTES = [
  'GET /healthz',
  'GET /status',
  'GET /events',
  'GET /inbox',
  'POST /reactions',
  'GET /groups',
  'GET /groups/{id}/inspect',
  'POST /groups/{id}/join',
  'POST /groups',
  'POST /groups/{id}/members',
  'POST /groups/{id}/deputy',
  'POST /messages',
  'POST /friend-requests',
  'POST /friend-requests/undo',
  'GET /friend-requests/incoming',
  'GET /friend-requests/status',
  'POST /friend-requests/accept',
  'POST /people/lookup',
  'POST /session/login',
  'GET /session/login',
  'POST /session/logout',
  'POST /session/stop',
  'POST /session/resume',
];

export function createServer({ bridge, config, fake = null }) {
  return http.createServer(async (req, res) => {
    const send = (status, body) => {
      const text = JSON.stringify(body);
      res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Content-Length': Buffer.byteLength(text) });
      res.end(text);
    };
    try {
      const url = new URL(req.url, 'http://bridge');
      const route = `${req.method} ${url.pathname}`;
      if (route === 'GET /healthz') return send(200, { ok: true });
      if (!sameKey(req.headers['x-bridge-key'], config.key)) return send(401, { error: 'unauthorized' });
      const accountTag = req.headers['x-bridge-account-tag'];
      if (accountTag !== undefined && accountTag !== bridge.status().accountTag) {
        return send(409, { error: 'account_mismatch' });
      }

      if (route === 'GET /status') return send(200, bridge.status());
      if (route === 'GET /events') {
        const after = Number(url.searchParams.get('after') ?? '0');
        return send(200, { events: bridge.friendEvents(after) });
      }
      if (route === 'GET /inbox') {
        const after = Number(url.searchParams.get('after') ?? '0');
        return send(200, { enabled: config.inboxEnabled, messages: bridge.inboxMessages(after) });
      }
      if (route === 'POST /friends/check') {
        const body = await readJson(req);
        return send(200, await bridge.checkFriends(body.uids));
      }
      if (route === 'GET /groups') return send(200, await bridge.listGroups());
      const inspect = /^GET \/groups\/(\d{1,32})\/inspect$/.exec(route);
      if (inspect) {
        if (typeof accountTag !== 'string' || !/^[0-9a-f]{12}$/.test(accountTag)) {
          return send(409, { error: 'account_unverified' });
        }
        return send(200, await bridge.inspectGroup(inspect[1]));
      }
      const join = /^POST \/groups\/(\d{1,32})\/join$/.exec(route);
      if (join) {
        if (typeof accountTag !== 'string' || !/^[0-9a-f]{12}$/.test(accountTag)) {
          return send(409, { error: 'account_unverified' });
        }
        const body = await readJson(req);
        return send(200, await bridge.joinGroupByLink(join[1], body.inviteLink, body.ownerVerified === true));
      }
      if (route === 'POST /groups') {
        const body = await readJson(req);
        return send(200, await bridge.createGroup({ name: body.name, members: body.members, deputy: body.deputy, welcome: body.welcome }));
      }
      const add = /^POST \/groups\/([^/]+)\/members$/.exec(route);
      if (add) {
        const groupId = decodeURIComponent(add[1]);
        const body = await readJson(req);
        const uid = String(body.uid ?? '');
        if (!ZALO_ID.test(groupId) || !ZALO_ID.test(uid)) return send(200, { outcome: 'PERSON_ERROR', code: 'BAD_ID' });
        return send(200, await bridge.addMember(groupId, uid));
      }
      const dep = /^POST \/groups\/([^/]+)\/deputy$/.exec(route);
      if (dep) {
        const body = await readJson(req);
        return send(200, await bridge.setDeputy({ groupId: decodeURIComponent(dep[1]), uid: body.uid }));
      }
      if (route === 'POST /messages') {
        const body = await readJson(req);
        return send(200, await bridge.sendMessage({ kind: body.kind, phone: body.phone, uid: body.uid, text: body.text }));
      }
      if (route === 'POST /reactions') {
        const body = await readJson(req);
        return send(200, await bridge.react({ seq: body.seq }));
      }
      if (route === 'POST /friend-requests') {
        const body = await readJson(req);
        return send(200, await bridge.sendFriendRequest({ uid: body.uid, text: body.text }));
      }
      if (route === 'GET /friend-requests/incoming') return send(200, { uids: bridge.incomingRequests() });
      if (route === 'GET /friend-requests/status') {
        const uids = url.searchParams.getAll('uid');
        const verify = url.searchParams.getAll('verifyDirection');
        if (verify.length > 1 || (verify.length === 1 && verify[0] !== 'true')) {
          return send(200, { ok: false, code: 'BAD_INPUT' });
        }
        return send(200, await bridge.readFriendRequestStatus(uids.length === 1 ? uids[0] : null, verify.length === 1));
      }
      if (route === 'POST /friend-requests/accept') {
        const body = await readJson(req);
        return send(200, await bridge.acceptFriendRequest({ uid: body.uid }));
      }
      if (route === 'POST /friend-requests/undo') {
        const body = await readJson(req);
        return send(200, await bridge.undoFriendRequest({ uid: body.uid }));
      }
      if (route === 'POST /people/lookup') {
        const body = await readJson(req);
        return send(200, await bridge.lookupPerson({ phone: body.phone }));
      }
      if (route === 'POST /session/login') {
        await readJson(req);
        const l = bridge.startLogin();
        return send(200, { state: l.state, ...(l.qr ? { qr: l.qr } : {}), ...(l.scannedId ? { scannedId: l.scannedId } : {}) });
      }
      if (route === 'GET /session/login') {
        const l = bridge.loginStatus();
        return send(200, { state: l.state, ...(l.qr ? { qr: l.qr } : {}), ...(l.scannedId ? { scannedId: l.scannedId } : {}) });
      }
      if (route === 'POST /session/logout') return send(200, { ok: await bridge.logout() });
      if (route === 'POST /session/stop') return send(200, { ok: bridge.stop() });
      if (route === 'POST /session/resume') return send(200, { ok: await bridge.resume() });

      // Chỉ ở chế độ giả (chạy thử ở máy): bơm một sự kiện kết bạn để xem cả hệ thống chạy thế nào.
      if (fake && route === 'POST /fake/friend') {
        const body = await readJson(req);
        fake.emitFriendAdd(String(body.uid ?? ''), body.phone ?? null);
        return send(200, { ok: true });
      }
      if (fake && route === 'POST /fake/message') {
        const body = await readJson(req);
        fake.emitMessage(String(body.uid ?? ''), String(body.text ?? ''));
        return send(200, { ok: true });
      }
      return send(404, { error: 'not_found' });
    } catch (e) {
      return send(e.status ?? 500, { error: e.status ? e.message : 'internal' });
    }
  });
}
