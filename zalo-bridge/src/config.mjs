// Cấu hình từ biến môi trường. Thiếu thứ bắt buộc thì KHÔNG khởi động (thất bại kín).

const HEX64 = /^[0-9a-fA-F]{64}$/;

export class ConfigError extends Error {}

function int(env, name, def, min, max) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  const n = Number.parseInt(raw, 10);
  if (!Number.isInteger(n) || n < min || n > max) throw new ConfigError(`${name} phải là số nguyên từ ${min} đến ${max}`);
  return n;
}

function hhmm(env, name, def) {
  const v = env[name] ?? def;
  const m = /^(\d{1,2}):(\d{2})$/.exec(v);
  if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) throw new ConfigError(`${name} phải có dạng HH:MM`);
  return Number(m[1]) * 60 + Number(m[2]);
}

/** Đọc cấu hình. {@code env} mặc định là process.env; test truyền vào một đối tượng riêng. */
export function loadConfig(env = process.env) {
  const mode = env.BRIDGE_MODE ?? 'zca';
  if (mode !== 'zca' && mode !== 'fake') throw new ConfigError('BRIDGE_MODE phải là zca hoặc fake');

  const key = env.BRIDGE_KEY ?? '';
  if (key.length < 16) throw new ConfigError('BRIDGE_KEY (khóa chung với Adh) phải có ít nhất 16 ký tự');

  const sessionKeyHex = env.SESSION_KEY ?? '';
  if (!HEX64.test(sessionKeyHex)) {
    throw new ConfigError('SESSION_KEY phải là 64 ký tự hex (khóa AES-256 mã hóa phiên, tách khỏi tệp phiên và khỏi sao lưu)');
  }

  const accountId = env.ZALO_ACCOUNT_ID ?? '';
  if (mode === 'zca' && !/^\d{1,32}$/.test(accountId)) {
    throw new ConfigError('ZALO_ACCOUNT_ID phải là mã Zalo (chỉ chữ số) của TÀI KHOẢN PHỤ đã khai báo');
  }

  const maxDaily = int(env, 'APP_ZALO_AUTO_MAX_DAILY', 100, 1, 10000);
  return {
    mode,
    host: env.BRIDGE_HOST ?? '0.0.0.0',
    port: int(env, 'BRIDGE_PORT', 3100, 1, 65535),
    key,
    sessionKey: Buffer.from(sessionKeyHex, 'hex'),
    accountId,
    dataDir: env.BRIDGE_DATA_DIR ?? '/data',
    // Trần cứng của CHÍNH cầu nối: gấp đôi trần cứng của Adh, để lỗi của Adh không vượt trần (ADR-0002 mục 3.4).
    hardDaily: maxDaily * 2,
    windowStartMin: hhmm(env, 'BRIDGE_WINDOW_START', '07:00'),
    windowEndMin: hhmm(env, 'BRIDGE_WINDOW_END', '22:00'),
    // Chờ tối đa bao lâu cho một lần thêm người; quá hạn là "không biết kết quả" (Adh sẽ dừng).
    addTimeoutMs: int(env, 'BRIDGE_ADD_TIMEOUT_MS', 30000, 1000, 120000),
    // Số lần hỏi thông tin (để lấy số điện thoại) tối đa mỗi giờ; quá thì sự kiện vẫn nhận nhưng không hỏi, rơi về hàng đợi tay.
    maxLookupsPerHour: int(env, 'BRIDGE_MAX_LOOKUPS_PER_HOUR', 60, 0, 1000),
    // Tin nhắn gửi cho khách (D18): trần cứng mỗi ngày và khoảng nghỉ CỐ ĐỊNH tối thiểu giữa hai tin. Công khai, không ngẫu nhiên hóa.
    // Vượt trần ngày thì cầu nối tự dừng; gửi quá sớm thì chỉ bị từ chối (RATE_LIMITED), Adh thử lại sau.
    msgDaily: int(env, 'BRIDGE_MSG_DAILY', 60, 1, 400),
    msgGapMs: int(env, 'BRIDGE_MSG_GAP_SEC', 90, 30, 3600) * 1000,
    // Nối lại khi kết nối nghe đứt bất thường (đo thật: mã 1006 sau khoảng 40 phút).
    reconnectDelaysMs: [5000, 15000, 45000, 120000, 300000],
    reconnectMaxAttempts: int(env, 'BRIDGE_RECONNECT_MAX', 8, 1, 100),
    // Mã lỗi Zalo (do ZaloApiError.code) đã thấy khi chạy thật và biết chắc là lỗi riêng một người. Mặc định rỗng: lỗi lạ thì dừng.
    personErrorCodes: new Set((env.BRIDGE_PERSON_ERROR_CODES ?? '').split(',').map((s) => s.trim()).filter(Boolean)),
  };
}
