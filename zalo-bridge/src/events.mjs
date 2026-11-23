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

// Bộ đệm tin nhắn riêng đến (D19, nhận diện từ chối) trong BỘ NHỚ: không ghi đĩa, không ghi log. Ngắn hạn hơn bộ đệm kết bạn vì có nội dung.
export const INBOX_TEXT_MAX = 500;

export class InboxBuffer {
  constructor({ max = 300, ttlMs = 6 * 3600 * 1000, now = () => Date.now() } = {}) {
    this.items = [];
    this.last = 0;
    this.max = max;
    this.ttlMs = ttlMs;
    this.now = now;
  }

  push({ uid, text }) {
    const t = this.now();
    this.last = Math.max(this.last + 1, t);
    this.items.push({ seq: this.last, uid, text: String(text).slice(0, INBOX_TEXT_MAX), at: new Date(t).toISOString() });
    if (this.items.length > this.max) this.items.splice(0, this.items.length - this.max);
    return this.last;
  }

  /** Tin có seq > after. Tin đã được xác nhận (seq <= after) và quá hạn bị bỏ ngay. */
  after(after) {
    const cutoff = this.now() - this.ttlMs;
    this.items = this.items.filter((e) => e.seq > after && Date.parse(e.at) >= cutoff);
    return this.items.slice();
  }
}
