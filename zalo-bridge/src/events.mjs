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
    if (this.file && existsSync(this.file)) {
      try {
        const o = decryptJson(this.key, readFileSync(this.file, 'utf8'));
        if (Array.isArray(o?.items)) this.items = o.items.filter((e) => Number.isFinite(e?.seq) && typeof e.uid === 'string' && typeof e.text === 'string' && typeof e.at === 'string');
        this.last = Math.max(0, ...this.items.map((e) => e.seq), Number.isFinite(o?.last) ? o.last : 0);
      } catch {
        // tệp hỏng hay sai khóa: bắt đầu trống (Adh vẫn còn con trỏ riêng, tin mới vẫn vào)
        this.items = [];
      }
    }
  }

  save() {
    if (!this.file) return;
    try {
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, encryptJson(this.key, { last: this.last, items: this.items }), { mode: 0o600 });
      renameSync(tmp, this.file);
    } catch {
      // không ghi được thì vẫn giữ trong bộ nhớ
    }
  }

  push({ uid, text }) {
    const t = this.now();
    this.last = Math.max(this.last + 1, t);
    this.items.push({ seq: this.last, uid, text: String(text).slice(0, INBOX_TEXT_MAX), at: new Date(t).toISOString() });
    if (this.items.length > this.max) this.items.splice(0, this.items.length - this.max);
    this.save();
    return this.last;
  }

  /** Tin có seq > after. Tin đã được xác nhận (seq <= after) và quá hạn bị bỏ ngay. */
  after(after) {
    const cutoff = this.now() - this.ttlMs;
    const kept = this.items.filter((e) => e.seq > after && Date.parse(e.at) >= cutoff);
    if (kept.length !== this.items.length) {
      this.items = kept;
      this.save();
    }
    return this.items.slice();
  }
}
