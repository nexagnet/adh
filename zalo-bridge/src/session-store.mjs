// Lưu phiên Zalo (imei, cookie, userAgent) ĐÃ MÃ HÓA bằng AES-256-GCM. Ai có phiên là chiếm được tài khoản phụ nên
// không bao giờ ghi dạng rõ, kể cả log. Khóa nằm ở biến môi trường/bí mật Docker, tách khỏi tệp và khỏi sao lưu.

import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { existsSync, readFileSync, renameSync, writeFileSync, rmSync } from 'node:fs';

const VERSION = 1;

export function encryptJson(key, value) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  const data = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return JSON.stringify({ v: VERSION, iv: iv.toString('base64'), tag: tag.toString('base64'), data: data.toString('base64') });
}

export function decryptJson(key, text) {
  const o = JSON.parse(text);
  if (o.v !== VERSION) throw new Error('phiên bản tệp phiên lạ');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(o.iv, 'base64'));
  decipher.setAuthTag(Buffer.from(o.tag, 'base64'));
  const plain = Buffer.concat([decipher.update(Buffer.from(o.data, 'base64')), decipher.final()]);
  return JSON.parse(plain.toString('utf8'));
}

export class SessionStore {
  constructor(file, key) {
    this.file = file;
    this.key = key;
  }

  /** Trả {session} nếu đọc được; {session:null} nếu chưa có; {session:null, corrupt:true} nếu hỏng/bị sửa/sai khóa. */
  load() {
    if (!existsSync(this.file)) return { session: null };
    try {
      return { session: decryptJson(this.key, readFileSync(this.file, 'utf8')) };
    } catch {
      return { session: null, corrupt: true };
    }
  }

  save(session) {
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, encryptJson(this.key, session), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  clear() {
    rmSync(this.file, { force: true });
  }
}
