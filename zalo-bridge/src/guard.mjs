// Chốt chặn cứng của cầu nối: kiểm mã, khung giờ, trần mỗi ngày. Tách riêng để test.

import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';
import { createHmac } from 'node:crypto';

export const ZALO_ID = /^\d{1,32}$/;
export const VN_OFFSET_MS = 7 * 3600 * 1000; // Việt Nam là UTC+7, không có giờ mùa hè

export function vnMinutesOfDay(ms) {
  const d = new Date(ms + VN_OFFSET_MS);
  return d.getUTCHours() * 60 + d.getUTCMinutes();
}

export function vnDayStart(ms) {
  return Math.floor((ms + VN_OFFSET_MS) / 86400000) * 86400000 - VN_OFFSET_MS;
}

export function inWindow(ms, startMin, endMin) {
  const m = vnMinutesOfDay(ms);
  return m >= startMin && m < endMin;
}

/**
 * Sổ các lần thêm người (chỉ thời điểm, không có mã người hay nhóm), lưu ra đĩa để khởi động lại không xóa số đếm.
 * Tính cả lần lỗi. Giữ 48 giờ gần nhất.
 */
export class AddLedger {
  constructor(file) {
    this.file = file;
    this.times = [];
    if (file && existsSync(file)) {
      try {
        const parsed = JSON.parse(readFileSync(file, 'utf8'));
        if (Array.isArray(parsed)) this.times = parsed.filter((t) => Number.isFinite(t));
      } catch {
        // tệp hỏng: không biết đã dùng bao nhiêu; cầu nối sẽ dừng (thất bại kín), xem Bridge.start
        this.corrupt = true;
      }
    }
  }

  countSince(sinceMs) {
    return this.times.filter((t) => t >= sinceMs).length;
  }

  record(ms) {
    this.times.push(ms);
    this.times = this.times.filter((t) => t >= ms - 48 * 3600 * 1000);
    if (this.file) {
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, JSON.stringify(this.times), { mode: 0o600 });
      renameSync(tmp, this.file);
    }
  }
}

/**
 * Sổ tin nhắn gửi khách (D18), lưu ra đĩa: các thời điểm gửi (để đếm trần ngày và đo khoảng nghỉ cố định) và dấu vết "đã gửi loại
 * tin này cho người này" để không bao giờ gửi một loại tin hai lần cho cùng một người. Dấu vết là HMAC bằng khóa riêng, không lưu
 * mã Zalo hay số điện thoại dạng đọc được. Tính cả lần lỗi. Tệp hỏng thì cầu nối dừng (thất bại kín).
 */
export class MessageLedger {
  constructor(file, secret) {
    this.file = file;
    this.secret = secret;
    this.times = [];
    this.sent = new Set();
    this.inviteTimes = new Map();
    this.lastInviteTrim = 0;
    this.corrupt = false;
    if (file && existsSync(file)) {
      try {
        const parsed = JSON.parse(readFileSync(file, 'utf8'));
        if (!parsed || !Array.isArray(parsed.times) || !Array.isArray(parsed.sent)) throw new Error('sai dạng');
        this.times = parsed.times.filter((t) => Number.isFinite(t));
        this.sent = new Set(parsed.sent.filter((h) => typeof h === 'string'));
        if (Array.isArray(parsed.inviteTimes)) {
          this.inviteTimes = new Map(parsed.inviteTimes.filter((e) => Array.isArray(e) && this.sent.has(e[0]) && Number.isSafeInteger(e[1])));
        }
      } catch {
        this.corrupt = true;
      }
    }
  }

  mark(kind, uid) {
    return createHmac('sha256', this.secret).update(`${kind}:${uid}`).digest('hex').slice(0, 32);
  }

  has(kind, uid) {
    return this.sent.has(this.mark(kind, uid));
  }

  inviteCovered(uid, since) {
    return (this.inviteTimes.get(this.mark('INVITE', uid)) ?? -1) >= since;
  }

  countSince(sinceMs) {
    return this.times.filter((t) => t >= sinceMs).length;
  }

  last() {
    return this.times.length ? Math.max(...this.times) : null;
  }

  /** Ghi một lần gửi (trước khi gọi Zalo). */
  record(ms) {
    this.times.push(ms);
    this.times = this.times.filter((t) => t >= ms - 48 * 3600 * 1000);
    this.save();
  }

  /** Ghi đã gửi xong loại tin này cho người này. */
  markSent(kind, uid, at = null) {
    const mark = this.mark(kind, uid);
    this.sent.add(mark);
    if (kind === 'INVITE' && Number.isSafeInteger(at)) {
      const dayMs = 86400 * 1000;
      if (at - this.lastInviteTrim >= dayMs) {
        for (const [key, time] of this.inviteTimes) {
          if (time < at - 90 * dayMs) this.inviteTimes.delete(key);
        }
        this.lastInviteTrim = at;
      }
      this.inviteTimes.set(mark, at);
    }
    this.save();
  }

  save() {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify({ times: this.times, sent: [...this.sent], inviteTimes: [...this.inviteTimes] }), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}
