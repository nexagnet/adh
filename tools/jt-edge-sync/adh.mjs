// Phần làm việc với adh: đọc khoảng ngày cần xuất, tải file lên, xem trước, xác nhận.
// Chạy trong chính cửa sổ Edge của tool, nên dùng phiên đăng nhập adh do người dùng tự đăng nhập.

const VN_DATE = /(\d{2})\/(\d{2})\/(\d{4})/g;

/** "dd/MM/yyyy" -> Date (giữa trưa, tránh lệch múi giờ) */
export function parseVnDate(s) {
  const [d, m, y] = s.split('/').map(Number);
  return new Date(y, m - 1, d, 12);
}

export function fmtVnDate(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** Chờ người dùng đăng nhập adh nếu bị đưa về trang /login. */
export async function ensureAdhLoggedIn(page, adhUrl, log, timeoutMs = 10 * 60 * 1000) {
  await page.goto(`${adhUrl}/imports/new`, { waitUntil: 'domcontentloaded' });
  if (!page.url().includes('/login')) return;
  log('Cần đăng nhập adh: hãy đăng nhập trong cửa sổ Edge vừa mở (tool không nhập mật khẩu giúp bạn).');
  await page.bringToFront();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: timeoutMs });
  await page.goto(`${adhUrl}/imports/new`, { waitUntil: 'domcontentloaded' });
}

/**
 * Đọc mục "Cần xuất Excel từ ngày nào?" của hãng J&T trên trang Nhập file.
 * Trả về { hasData, ranges: [{from: Date, to: Date}] } (ranges rỗng nếu adh chưa có đơn J&T nào).
 */
export async function readJtAdvice(page) {
  const panel = page.locator('section .panel', { has: page.locator('h3', { hasText: 'J&T' }) }).first();
  const text = await panel.innerText();
  if (/Chưa nhập đơn nào/.test(text)) return { hasData: false, ranges: [] };
  const ranges = [];
  const items = await panel.locator('ol li').allInnerTexts();
  const source = items.length ? items : [text.match(/Xuất từ ngày[\s\S]*?\(hôm nay\)/)?.[0] ?? ''];
  for (const t of source) {
    const dates = [...t.matchAll(VN_DATE)].map((m) => parseVnDate(m[0]));
    if (dates.length >= 2) ranges.push({ from: dates[0], to: dates[1] });
  }
  if (!ranges.length) throw new Error('Không đọc được khoảng ngày từ trang Nhập file của adh.');
  return { hasData: true, ranges };
}

/** Tải các file J&T lên adh; dừng ở trang xem trước và trả về số liệu. */
export async function uploadAndPreview(page, adhUrl, files) {
  await page.goto(`${adhUrl}/imports/new`, { waitUntil: 'domcontentloaded' });
  await page.selectOption('#carrier', 'JT_EXPRESS');
  await page.setInputFiles('#file', files);
  await Promise.all([page.waitForURL(/\/imports\/\d+\/mapping/), page.click('form[action*="/imports"] button[type=submit]')]);
  const batchId = Number(page.url().match(/\/imports\/(\d+)\/mapping/)[1]);
  // adh đã đoán sẵn cột; chỉ bấm "Xem trước" nếu cột bắt buộc đã được chọn.
  const required = await page.locator('.maprow:has(.badge) select').evaluateAll((els) => els.map((e) => e.value));
  if (required.some((v) => !v)) {
    throw new Error(`adh chưa đoán được hết cột bắt buộc ở lô ${batchId}; hãy mở ${page.url()} để chọn cột bằng tay.`);
  }
  await Promise.all([page.waitForURL(/\/imports\/\d+\/preview/), page.click('#mapform button[type=submit]')]);
  const cards = await page.locator('.cards.c5 .card').evaluateAll((els) =>
    els.map((e) => ({ label: e.querySelector('.label').innerText.trim(), value: Number(e.querySelector('.big').innerText) })));
  const notices = await page.locator('.notice').allInnerTexts();
  return { batchId, cards, notices };
}

export async function commitImport(page) {
  await Promise.all([page.waitForURL(/\/imports\/\d+$/), page.click('form[action*="/commit"] button[type=submit]')]);
  return page.url();
}

// ---------- File "Lịch sử đối soát" (Dòng tiền của J&T) ----------

/** Tên file đối soát J&T tải về: "<mã khách>-<yyyyMMdd>-<MMdd>.xlsx". */
export const SETTLEMENT_FILE = /^[A-Za-z0-9]+-\d{8}-\d{4}\.xlsx$/;

/** Đọc "Kỳ mới nhất đã nhập kết thúc ngày dd/MM/yyyy" trên trang nhập đối soát; null nếu chưa nhập kỳ nào. */
export async function readSettlementAdvice(page, adhUrl) {
  await page.goto(`${adhUrl}/imports/settlement`, { waitUntil: 'domcontentloaded' });
  const text = await page.locator('main').innerText();
  const m = text.match(/Kỳ mới nhất đã nhập kết thúc ngày\s+(\d{2}\/\d{2}\/\d{4})/);
  return m ? { latestEnd: m[1] } : { latestEnd: null };
}

/** Tải các file đối soát lên adh; dừng ở trang xem trước và trả về số liệu (chưa lưu gì). */
export async function uploadSettlementAndPreview(page, adhUrl, files) {
  await page.goto(`${adhUrl}/imports/settlement`, { waitUntil: 'domcontentloaded' });
  await page.setInputFiles('#file', files);
  await Promise.all([
    page.waitForURL(/\/imports\/settlement\/\d+$/),
    page.click('form[enctype="multipart/form-data"] button[type=submit]'),
  ]);
  const cards = await page.locator('.cards.c5 .card').evaluateAll((els) =>
    els.map((e) => ({ label: e.querySelector('.label').innerText.trim(), value: Number(e.querySelector('.big').innerText) })));
  const notices = await page.locator('.notice').allInnerTexts();
  return { cards, notices };
}

/** Bấm "Xác nhận nhập" ở trang xem trước đối soát; trả về dòng thông báo kết quả của adh. */
export async function commitSettlement(page) {
  await Promise.all([page.waitForURL(/\/imports\/settlement$/), page.click('form[action*="/commit"] button[type=submit]')]);
  return (await page.locator('.notice.ok').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
}

