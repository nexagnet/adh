// Bộ đệm sự kiện kết bạn trong BỘ NHỚ (không ghi đĩa: có thể chứa số điện thoại). Mất khi khởi động lại; Adh rơi về
// hàng đợi tay, không mất khách.

export class EventBuffer {
  constructor({ max = 1000, ttlMs = 48 * 3600 * 1000, now = () => Date.now() } = {}) {
    this.items = [];
    this.last = 0;
    this.max = max;
    this.ttlMs = ttlMs;
    this.now = now;
  }

  /** seq tăng đơn điệu và không quay lại sau khi khởi động lại (dựa trên thời gian). */
  push({ uid, phone }) {
    const t = this.now();
    this.last = Math.max(this.last + 1, t);
    this.items.push({ seq: this.last, uid, phone: phone || null, at: new Date(t).toISOString() });
    if (this.items.length > this.max) this.items.splice(0, this.items.length - this.max);
    return this.last;
  }

  /** Các sự kiện có seq > after. Những sự kiện đã được xác nhận (seq <= after) và quá hạn bị bỏ. */
  after(after) {
    const cutoff = this.now() - this.ttlMs;
    this.items = this.items.filter((e) => e.seq > after && Date.parse(e.at) >= cutoff);
    return this.items.slice();
  }
}

// Bộ đệm tin nhắn riêng đến (D19, nhận diện từ chối). Có nội dung nên: giữ ngắn hạn, cắt 500 ký tự, và nếu có tệp thì chỉ ghi ĐÃ MÃ HÓA
// (AES-256-GCM, cùng khóa với phiên) để khởi động lại hay cầu dao nhảy không làm mất tin chưa được Adh lấy đi. Tin đã được xác nhận thì bị xóa khỏi tệp.
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { encryptJson, decryptJson } from './session-store.mjs';

export const INBOX_TEXT_MAX = 500;

export class InboxBuffer {
  constructor({ max = 300, ttlMs = 24 * 3600 * 1000, now = () => Date.now(), file = null, key = null } = {}) {
    this.items = [];
    this.last = 0;
    this.max = max;
    this.ttlMs = ttlMs;
    this.now = now;
    this.file = file && key ? file : null;
    this.key = key;
    this.durable = false;
    this.lossAt = 0;
    this.coverageFloor = 0;
    this.acknowledged = 0;
    if (this.file && existsSync(this.file)) {
      try {
        const o = decryptJson(this.key, readFileSync(this.file, 'utf8'));
        if (!Array.isArray(o?.items)) throw new Error('INVALID_INBOX');
        this.items = o.items.filter((e) => Number.isSafeInteger(e?.seq) && typeof e.uid === 'string' && typeof e.text === 'string' && Number.isFinite(Date.parse(e.at)));
        if (this.items.length !== o.items.length) this.lossAt = this.now();
        this.last = Math.max(0, ...this.items.map((e) => e.seq), Number.isFinite(o?.last) ? o.last : 0);
        this.lossAt = Math.max(this.lossAt, Number.isSafeInteger(o.lossAt) ? o.lossAt : 0);
        this.acknowledged = Number.isSafeInteger(o.acknowledged) ? o.acknowledged : 0;
      } catch {
        this.items = [];
        this.lossAt = this.now();
      }
    }
  }

  save() {
    if (!this.file) return;
    try {
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, encryptJson(this.key, {
        last: this.last, items: this.items, lossAt: this.lossAt, acknowledged: this.acknowledged,
      }), { mode: 0o600 });
      renameSync(tmp, this.file);
      this.durable = true;
    } catch {
      this.markLoss();
      this.durable = false;
    }
  }

  push({ uid, text }) {
    const t = this.now();
    this.last = Math.max(this.last + 1, t);
    this.items.push({ seq: this.last, uid, text: String(text).slice(0, INBOX_TEXT_MAX), at: new Date(t).toISOString() });
    if (this.items.length > this.max) {
      this.items.splice(0, this.items.length - this.max);
      this.markLoss();
    }
    this.save();
    return this.last;
  }

  /** Tin có seq > after. Tin đã được xác nhận (seq <= after) và quá hạn bị bỏ ngay. */
  after(after) {
    const cutoff = this.now() - this.ttlMs;
    const priorAck = this.acknowledged;
    if (after < priorAck) {
      this.markLoss(); // DB Adh đã lùi về con trỏ trước những tin bridge đã xóa.
      this.acknowledged = after;
    }
    for (const item of this.items) {
      if (item.seq <= after) this.acknowledged = Math.max(this.acknowledged, item.seq);
    }
    const pending = this.items.filter((e) => e.seq > after);
    const kept = pending.filter((e) => Date.parse(e.at) >= cutoff);
    if (kept.length !== pending.length) this.markLoss();
    if (kept.length !== this.items.length || this.acknowledged !== priorAck || !this.durable) {
      this.items = kept;
      this.save();
    }
    return this.items.slice();
  }

  caughtUp() {
    return this.durable && this.items.length === 0;
  }

  markLoss() {
    this.lossAt = Math.max(this.lossAt + 1, this.now(), this.coverageFloor + 1);
  }
}
