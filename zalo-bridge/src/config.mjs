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

  const maxDaily = int(env, 'APP_ZALO_AUTO_MAX_DAILY', 300, 1, 10000);
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
    msgDaily: int(env, 'BRIDGE_MSG_DAILY', 150, 1, 1500),
    msgGapMs: int(env, 'BRIDGE_MSG_GAP_SEC', 90, 30, 3600) * 1000,
    // Tin 1 chia nhiều tin ngắn gửi liền cho CÙNG một khách: khoảng cách CỐ ĐỊNH giữa các phần (không ngẫu nhiên). Chỉ áp dụng giữa các phần của một Tin 1;
    // khoảng nghỉ giữa hai KHÁCH vẫn là msgGapMs. Đặt 0 chỉ để chạy thử.
    partGapMs: int(env, 'BRIDGE_PART_GAP_SEC', 3, 0, 10) * 1000,
    // Khoảng nghỉ cố định riêng cho tin TRẢ LỜI người vừa nhắn tới (WELCOME, REPLY), ngắn hơn; tính từ tin gần nhất bất kể loại. Không ngẫu nhiên.
    replyGapMs: int(env, 'BRIDGE_REPLY_GAP_SEC', 30, 15, 3600) * 1000,
    // Lời mời kết bạn (23/11/2026, chủ shop chốt "tài khoản phụ chủ động kết bạn"): chỉ gửi cho người ĐÃ nhận Tin 1 từ chính cầu nối này, mỗi người
    // một lời mời. Trần cứng mỗi ngày của CHÍNH cầu nối (Adh đặt thấp hơn, mặc định 10); dùng chung khoảng nghỉ cố định, khung giờ, phanh
    // khẩn cấp đếm mọi tin và cầu dao với phần gửi tin. Không ngẫu nhiên hóa.
    // Khoảng nghỉ CỐ ĐỊNH giữa hai việc liên tiếp cho CÙNG MỘT người (Tin 1 rồi kết bạn, kết bạn rồi gửi lại Tin 1; 25/11/2026); Tin 1 bị chặn người lạ thì lời mời đi ngay. Giữa các KHÁCH vẫn là msgGapMs.
    samePersonGapMs: int(env, 'BRIDGE_SAME_PERSON_GAP_SEC', 7, 0, 3600) * 1000,
    friendDaily: int(env, 'BRIDGE_FRIEND_DAILY', 50, 1, 1000),
    // Mã lỗi Zalo của việc "người nhận không nhận tin nhắn từ người lạ" (mặc định 122, đo thật 24/11/2026): với Tin 1 thì đây là lỗi RIÊNG người đó
    // (không dừng cầu dao), cầu nối ghi nhớ "Tin 1 bị chặn" để vẫn được gửi lời mời kết bạn cho người này; Tin 1 sẽ gửi lại sau khi họ thành bạn.
    // Mã lỗi Zalo của việc không tìm thấy người theo số điện thoại (mặc định 212, ADR-0002): lỗi RIÊNG số đó, không dừng cầu dao; trả về như NOT_FOUND.
    notFoundCodes: new Set(String(env.BRIDGE_NOT_FOUND_CODES ?? '212').split(',').map((x) => x.trim()).filter((x) => /^\d{1,4}$/.test(x))),
    strangerBlockedCodes: new Set(String(env.BRIDGE_STRANGER_BLOCKED_CODES ?? '122').split(',').map((x) => x.trim()).filter((x) => /^\d{1,4}$/.test(x))),
    // Tạo nhóm Zalo (D19): trần cứng của CHÍNH cầu nối là 3 nhóm mỗi ngày (Adh mặc định 1), khoảng nghỉ CỐ ĐỊNH tối thiểu giữa hai lần tạo.
    // Vượt trần ngày thì cầu nối tự dừng; tạo sớm hơn khoảng nghỉ thì chỉ bị từ chối (RATE_LIMITED). Không ngẫu nhiên hóa.
    groupGapMs: int(env, 'BRIDGE_GROUP_GAP_SEC', 300, 300, 86400) * 1000,
    // Tra tên và ảnh của MỘT số điện thoại để xác nhận mẹ làm phó nhóm: tối đa mỗi giờ (công khai, cố định); quá thì bị từ chối, không dừng.
    profileLookupsPerHour: int(env, 'BRIDGE_PROFILE_LOOKUPS_PER_HOUR', 5, 0, 20),
    // Đọc tin nhắn RIÊNG gửi tới tài khoản (chỉ từ người đã nhận Tin 1 hoặc Tin 2 của hệ thống) để Adh nhận ra khách từ chối. TẮT mặc định:
    // tắt thì bộ nối không đăng ký nghe tin nhắn. Tin giữ trong bộ nhớ, không ghi đĩa, không ghi log.
    inboxEnabled: ['1', 'true'].includes(String(env.BRIDGE_INBOX_ENABLED ?? '').toLowerCase()),
    // Thả tim tin khách (24/11/2026, chủ shop duyệt): trần mỗi ngày và khoảng nghỉ CỐ ĐỊNH giữa hai lần thả tim; chỉ có tác dụng khi đã bật đọc tin riêng.
    reactDaily: int(env, 'BRIDGE_REACT_DAILY', 300, 1, 2000),
    reactGapMs: int(env, 'BRIDGE_REACT_GAP_SEC', 3, 2, 3600) * 1000,
    // Đối chiếu danh sách bạn (24/11/2026, chủ shop duyệt, CHỈ ĐỌC): giữa hai lần đọc Zalo tối thiểu ngần này giây (trong khoảng đó trả từ bản đã đọc),
    // tối đa mỗi ngày ngần này lần đọc. Chỉ trả lại những mã Adh hỏi và là bạn; không bao giờ trả cả danh sách.
    friendSyncMinMs: int(env, 'BRIDGE_FRIEND_SYNC_MIN_SEC', 600, 300, 86400) * 1000,
    friendSyncDaily: int(env, 'BRIDGE_FRIEND_SYNC_DAILY', 48, 1, 100),
    // Nối lại khi kết nối nghe đứt bất thường (đo thật: mã 1006 sau khoảng 40 phút).
    reconnectDelaysMs: [5000, 15000, 45000, 120000, 300000],
    reconnectMaxAttempts: int(env, 'BRIDGE_RECONNECT_MAX', 8, 1, 100),
    // Mã lỗi Zalo (do ZaloApiError.code) đã thấy khi chạy thật và biết chắc là lỗi riêng một người. Mặc định rỗng: lỗi lạ thì dừng.
    // Tương tự, nhưng là mã báo lỗi của CẢ NHÓM khi thêm người (nhóm không còn, mình không đủ quyền...). Mặc định rỗng: lỗi lạ thì dừng.
    groupErrorCodes: new Set((env.BRIDGE_GROUP_ERROR_CODES ?? '').split(',').map((s) => s.trim()).filter(Boolean)),
    personErrorCodes: new Set((env.BRIDGE_PERSON_ERROR_CODES ?? '').split(',').map((s) => s.trim()).filter(Boolean)),
  };
}
