// Phân loại kết quả/lỗi khi thêm người vào nhóm thành ĐÚNG các loại mà Adh hiểu (docs/ZALO-BRIDGE-API.md):
//   OK, PERSON_ERROR, GROUP_ERROR (chỉ khi thêm vào nhóm), SESSION_LOST, RESTRICTED, SYSTEM_ERROR.
// Nguyên tắc thất bại kín: cái gì không nhận ra chắc chắn thì là SYSTEM_ERROR (Adh sẽ dừng), không đoán là lỗi riêng người.

const RESTRICTED_RE = /captcha|x[aá]c minh|verify|verification|t[aạ]m kh[oó]a|b[iị] kh[oó]a|h[aạ]n ch[eế]|too many|rate.?limit|spam/i;
const SESSION_RE = /zpw_sek|session|cookie|đăng nhập|dang nhap|login|unauthor/i;

/** Chỉ giữ chữ, số, gạch: mã trả về Adh không bao giờ chứa tên hay số điện thoại. */
export function safeCode(s) {
  const c = String(s ?? '').replace(/[^A-Za-z0-9_-]/g, '');
  return c ? c.slice(0, 40) : undefined;
}

/**
 * @param {object} result  kết quả {errorMembers, error_data} của addUserToGroup khi KHÔNG ném lỗi
 */
export function classifyResult(result, uid, groupCodes = new Set()) {
  const failed = Array.isArray(result?.errorMembers) ? result.errorMembers.map(String) : [];
  if (!failed.includes(String(uid))) return { outcome: 'OK' };
  // Zalo tự báo lỗi riêng cho người này: có cấu trúc, đó chính là lỗi theo người. Mã lấy từ khóa của error_data.
  const codes = Object.entries(result?.error_data ?? {}).filter(([, uids]) => Array.isArray(uids) && uids.map(String).includes(String(uid))).map(([c]) => c);
  // Mã báo lỗi của CẢ NHÓM (nhóm đã giải tán, mình không còn là quản trị...) không phải lỗi của người này: tách riêng để Adh tạm dừng đúng nhóm đó.
  if (codes.some((c) => groupCodes.has(String(c)))) return { outcome: 'GROUP_ERROR', code: safeCode(`Z${codes[0]}`) };
  return { outcome: 'PERSON_ERROR', code: safeCode(codes.length ? `Z${codes[0]}` : 'MEMBER_ERROR') };
}

/**
 * @param {Error & {code?: number|null}} err  lỗi ném ra khi gọi Zalo
 * @param {Set<string>} personCodes  các mã đã biết chắc là lỗi riêng người (cấu hình sau khi chạy thật)
 */
export function classifyError(err, personCodes = new Set(), groupCodes = new Set()) {
  if (err?.name === 'TimeoutError' || err?.code === 'ETIMEDOUT') return { outcome: 'SYSTEM_ERROR', code: 'TIMEOUT' };
  const code = err?.code;
  const hasZaloCode = Number.isFinite(code);
  const message = String(err?.message ?? '');
  if (hasZaloCode && groupCodes.has(String(code))) return { outcome: 'GROUP_ERROR', code: safeCode(`Z${code}`) };
  if (hasZaloCode && personCodes.has(String(code))) return { outcome: 'PERSON_ERROR', code: safeCode(`Z${code}`) };
  if (RESTRICTED_RE.test(message)) return { outcome: 'RESTRICTED', code: safeCode(hasZaloCode ? `Z${code}` : 'RESTRICTED') };
  if (SESSION_RE.test(message) || code === 600) return { outcome: 'SESSION_LOST', code: safeCode(hasZaloCode ? `Z${code}` : 'SESSION') };
  return { outcome: 'SYSTEM_ERROR', code: safeCode(hasZaloCode ? `Z${code}` : 'UNKNOWN') };
}

/** Lỗi khi nối/đăng nhập lại bằng phiên đã lưu: phiên chết hay lỗi mạng tạm. */
export function isSessionDead(err) {
  const m = String(err?.message ?? '');
  return SESSION_RE.test(m) || err?.code === 600 || err?.code === 'LOGIN_FAILED';
}
