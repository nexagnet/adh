// Chốt chặn cứng của cầu nối: kiểm mã, khung giờ, trần mỗi ngày. Tách riêng để test.

import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs';

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
