// Xuất Excel đơn J&T theo ngày bằng Edge rồi nhập vào adh.
// Chế độ --doi-soat: lấy các file "Lịch sử đối soát" (Dòng tiền) về và nhập vào adh (xem README).
//
// Nguyên tắc an toàn:
//  - Tool KHÔNG đăng nhập J&T hay adh thay bạn, không nhập mật khẩu/OTP, không đọc hay lưu cookie.
//    Bạn tự đăng nhập trong cửa sổ Edge riêng của tool; phiên nằm trong hồ sơ Edge đó trên máy bạn.
//  - Hết phiên, captcha hay OTP: tool dừng và chờ bạn xử lý trong cửa sổ Edge.
//  - Chỉ đọc danh sách đơn và bấm "Xuất" trên J&T; không sửa, hủy hay in đơn.
//  - Dữ liệu chỉ đi từ J&T xuống máy bạn rồi lên adh của bạn. File tải về bị xóa sau khi nhập xong.
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import {
  ensureAdhLoggedIn, readJtAdvice, uploadAndPreview, commitImport, parseVnDate, fmtVnDate,
  SETTLEMENT_FILE, readSettlementAdvice, uploadSettlementAndPreview, commitSettlement,
} from './adh.mjs';

const args = new Map();
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a.startsWith('--')) {
    const next = process.argv[i + 1];
    if (next && !next.startsWith('--')) { args.set(a.slice(2), next); i++; } else args.set(a.slice(2), true);
  }
}

const ADH_URL = (args.get('adh') || process.env.ADH_URL || 'https://adh.43-134-105-89.sslip.io').replace(/\/$/, '');
const JT_URL = 'https://khachhang.jtexpress.vn/orderTable';
const ROOT = path.join(process.env.LOCALAPPDATA || os.homedir(), 'adh-jt-sync');
const PROFILE = path.join(ROOT, 'edge-profile');
const DOWNLOADS = path.join(ROOT, 'downloads');
const LOGIN_WAIT_MS = 10 * 60 * 1000;
const JT_MAX_ROWS = 500;

const log = (m) => console.log(`[${new Date().toLocaleTimeString('vi-VN')}] ${m}`);

async function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = await rl.question(question);
  rl.close();
  return answer.trim().toLowerCase();
}

/** Mở trang đơn hàng J&T; nếu bị đưa về trang đăng nhập thì chờ bạn đăng nhập. */
async function openJt(page) {
  await page.goto(JT_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  if (page.url().includes('/login')) {
    log('Cần đăng nhập J&T: hãy đăng nhập (và nhập OTP) trong cửa sổ Edge vừa mở. Tool đang chờ.');
    await page.bringToFront();
    await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: LOGIN_WAIT_MS });
    await page.goto(JT_URL, { waitUntil: 'domcontentloaded' });
  }
  await page.locator('input.ccmvn-range-input').first().waitFor({ timeout: 30000 });
  const field = await page.locator('input[readonly]').nth(1).inputValue().catch(() => '');
  if (field && !/Thời gian tạo/.test(field)) {
    throw new Error(`Bộ lọc ngày của J&T đang là "${field}", không phải "Thời gian tạo". Hãy đổi lại rồi chạy lại.`);
  }
}

async function setDate(page, index, text) {
  const input = page.locator('input.ccmvn-range-input').nth(index);
  await input.click({ clickCount: 3 });
  await page.keyboard.type(text);
  await page.keyboard.press('Enter');
}

/** Lọc theo khoảng ngày tạo đơn; trả về số đơn J&T báo. */
async function searchRange(page, from, to) {
  await setDate(page, 0, fmtVnDate(from));
  await setDate(page, 1, fmtVnDate(to));
  await page.getByRole('button', { name: 'Tìm kiếm' }).click();
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(1500);
  const shown = (await page.locator('body').innerText()).match(/Hiển thị:\s*([\d.,]+)\s*đơn hàng/);
  if (!shown) throw new Error('Không đọc được số đơn "Hiển thị: N đơn hàng" trên J&T (giao diện có thể đã đổi).');
  const [d0, d1] = await page.locator('input.ccmvn-range-input').evaluateAll((els) => els.map((e) => e.value));
  if (d0 !== fmtVnDate(from) || d1 !== fmtVnDate(to)) {
    throw new Error(`Ô ngày trên J&T đang là ${d0} đến ${d1}, không khớp ${fmtVnDate(from)} đến ${fmtVnDate(to)}.`);
  }
  return Number(shown[1].replace(/[.,]/g, ''));
}

async function exportRange(page, from, to) {
  const count = await searchRange(page, from, to);
  log(`J&T: ${fmtVnDate(from)} đến ${fmtVnDate(to)} có ${count} đơn.`);
  if (count === 0) return null;
  if (count > JT_MAX_ROWS) {
    throw new Error(`Khoảng này có ${count} đơn, vượt ${JT_MAX_ROWS} đơn mỗi lần xuất của J&T. Hãy chạy với khoảng ngắn hơn (--from, --to).`);
  }
  const exportBtn = page.locator('button.ccmvn-button--green', { hasText: 'Xuất' }).first();
  const label = (await exportBtn.innerText()).trim();
  if (!label.includes(`(${count})`)) throw new Error(`Nút "${label}" không khớp ${count} đơn; dừng để tránh xuất thiếu.`);
  const [download] = await Promise.all([page.waitForEvent('download', { timeout: 120000 }), exportBtn.click()]);
  fs.mkdirSync(DOWNLOADS, { recursive: true });
  const file = path.join(DOWNLOADS, `jt_${fmtVnDate(from).replaceAll('/', '-')}_${fmtVnDate(to).replaceAll('/', '-')}.xlsx`);
  await download.saveAs(file);
  log(`Đã tải ${path.basename(file)} (${fs.statSync(file).size} byte).`);
  return file;
}

/** Mở J&T; nếu bị đưa về trang đăng nhập thì chờ bạn đăng nhập. Không bấm gì khác trên J&T. */
async function openJtHome(page) {
  await page.goto(JT_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  if (page.url().includes('/login')) {
    log('Cần đăng nhập J&T: hãy đăng nhập (và nhập OTP) trong cửa sổ Edge vừa mở. Tool đang chờ.');
    await page.bringToFront();
    await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: LOGIN_WAIT_MS });
  }
}

/**
 * Chế độ --doi-soat: nhập file "Lịch sử đối soát" của J&T vào adh.
 *  - Mặc định: mở J&T trong Edge riêng, bạn tự vào Dòng tiền > Lịch sử đối soát và bấm "Tải về" cho các kỳ cần nhập.
 *    Tool KHÔNG bấm gì trên J&T, chỉ nhận các file đối soát (tên dạng <mã khách>-<ngày>-<ngày>.xlsx) mà Edge tải về.
 *  - --folder <thư mục>: không mở J&T, nhập các file đối soát bạn đã tải sẵn trong thư mục đó.
 */
async function mainSettlement() {
  fs.mkdirSync(PROFILE, { recursive: true });
  const folder = typeof args.get('folder') === 'string' ? args.get('folder') : null;
  log('Mở Edge (hồ sơ riêng của tool)...');
  const ctx = await chromium.launchPersistentContext(PROFILE, { channel: 'msedge', headless: false, viewport: null, acceptDownloads: true });
  const files = [];
  const downloaded = []; // file do tool tải về (xóa sau khi chạy); file trong --folder là của bạn, không xóa tự động
  try {
    const adhPage = ctx.pages()[0] ?? (await ctx.newPage());
    await ensureAdhLoggedIn(adhPage, ADH_URL, log);
    const advice = await readSettlementAdvice(adhPage, ADH_URL);
    log(advice.latestEnd
      ? `adh đã có đối soát tới hết ngày ${advice.latestEnd}; hãy lấy các kỳ sau ngày đó.`
      : 'adh chưa có kỳ đối soát nào; hãy lấy các kỳ từ ngày cửa hàng bắt đầu gửi hàng.');

    if (folder) {
      for (const name of fs.readdirSync(folder).filter((n) => SETTLEMENT_FILE.test(n)).sort()) files.push(path.join(folder, name));
      if (!files.length) throw new Error(`Không thấy file đối soát nào (tên dạng <mã khách>-yyyyMMdd-MMdd.xlsx) trong ${folder}.`);
      log(`Tìm thấy ${files.length} file đối soát trong ${folder}.`);
    } else {
      fs.mkdirSync(DOWNLOADS, { recursive: true });
      const onDownload = async (download) => {
        const name = download.suggestedFilename();
        if (!SETTLEMENT_FILE.test(name)) { log(`Bỏ qua file tải về "${name}" (không phải file đối soát).`); return; }
        const file = path.join(DOWNLOADS, name);
        await download.saveAs(file);
        if (!files.includes(file)) { files.push(file); downloaded.push(file); }
        log(`Đã nhận ${name} (${fs.statSync(file).size} byte).`);
      };
      const attach = (p) => p.on('download', onDownload);
      ctx.pages().forEach(attach);
      ctx.on('page', attach);
      const jtPage = await ctx.newPage();
      await openJtHome(jtPage);
      log('Trong cửa sổ J&T: vào Dòng tiền > Lịch sử đối soát, bấm "Tải về" ở từng kỳ cần nhập. Tool chỉ nhận file tải về, không bấm thay bạn.');
      await ask('Tải xong các kỳ cần nhập thì gõ c rồi Enter để tiếp tục: ');
      await new Promise((r) => setTimeout(r, 1500)); // chờ file cuối ghi xong
      if (!files.length) throw new Error('Chưa nhận được file đối soát nào.');
    }
    if (args.get('dry')) {
      log(`Chế độ --dry: có ${files.length} file, KHÔNG tải lên adh. File nằm ở ${folder ?? DOWNLOADS}.`);
      args.set('keep', true);
      return;
    }

    log('Tải file lên adh (chưa lưu gì, chỉ xem trước)...');
    const preview = await uploadSettlementAndPreview(adhPage, ADH_URL, files);
    for (const c of preview.cards) console.log(`   ${c.label}: ${c.value}`);
    for (const n of preview.notices) console.log(`   Lưu ý: ${n.replace(/\s+/g, ' ')}`);
    const valid = preview.cards.find((c) => /hợp lệ/.test(c.label))?.value ?? 0;
    if (valid === 0) throw new Error('Không có dòng hợp lệ nào; không nhập.');
    if (!args.get('yes')) {
      const ok = await ask('Xác nhận nhập vào adh? (gõ c rồi Enter để nhập, phím khác để hủy): ');
      if (ok !== 'c') { log('Đã hủy, chưa lưu gì vào adh.'); return; }
    }
    log(`Đã nhập xong. ${await commitSettlement(adhPage)}`);
    if (folder && args.get('delete')) {
      for (const f of files) fs.rmSync(f, { force: true });
      log('Đã xóa các file đối soát trong thư mục (chứa tên, SĐT, địa chỉ khách).');
    } else if (folder) {
      log('Nhớ xóa các file đối soát trong thư mục sau khi nhập (chứa tên, SĐT, địa chỉ khách), hoặc chạy lại với --delete.');
    }
  } finally {
    await ctx.close().catch(() => {});
    if (!args.get('keep')) {
      for (const f of downloaded) fs.rmSync(f, { force: true });
      if (downloaded.length) log('Đã xóa file Excel tạm (chứa dữ liệu khách).');
    }
  }
}

async function main() {
  if (args.get('doi-soat')) return mainSettlement();
  fs.mkdirSync(PROFILE, { recursive: true });
  log('Mở Edge (hồ sơ riêng của tool)...');
  const ctx = await chromium.launchPersistentContext(PROFILE, { channel: 'msedge', headless: false, viewport: null, acceptDownloads: true });
  const files = [];
  try {
    const adhPage = ctx.pages()[0] ?? (await ctx.newPage());
    await ensureAdhLoggedIn(adhPage, ADH_URL, log);

    let ranges;
    if (args.get('from')) {
      ranges = [{ from: parseVnDate(args.get('from')), to: parseVnDate(args.get('to') || fmtVnDate(new Date())) }];
      log(`Dùng khoảng ngày bạn chỉ định: ${fmtVnDate(ranges[0].from)} đến ${fmtVnDate(ranges[0].to)}.`);
    } else {
      const advice = await readJtAdvice(adhPage);
      if (!advice.hasData) {
        throw new Error('adh chưa có đơn J&T nào, nên chưa biết xuất từ ngày nào. Chạy lại với --from dd/MM/yyyy (ví dụ --from 28/06/2026).');
      }
      ranges = advice.ranges;
      log(`adh gợi ý xuất ${ranges.length} khoảng: ${ranges.map((r) => `${fmtVnDate(r.from)}-${fmtVnDate(r.to)}`).join(', ')}.`);
    }

    const jtPage = await ctx.newPage();
    await openJt(jtPage);
    for (const r of ranges) {
      const f = await exportRange(jtPage, r.from, r.to);
      if (f) files.push(f);
    }
    await jtPage.close();

    if (!files.length) {
      log('J&T không có đơn nào trong khoảng này, không cần nhập gì.');
      return;
    }
    if (args.get('dry')) {
      log(`Chế độ --dry: đã tải ${files.length} file, KHÔNG tải lên adh. File nằm ở ${DOWNLOADS}.`);
      args.set('keep', true);
      return;
    }

    log('Tải file lên adh (chưa lưu gì, chỉ xem trước)...');
    const preview = await uploadAndPreview(adhPage, ADH_URL, files);
    for (const c of preview.cards) console.log(`   ${c.label}: ${c.value}`);
    for (const n of preview.notices) console.log(`   Lưu ý: ${n.replace(/\s+/g, ' ')}`);
    const valid = preview.cards.find((c) => /hợp lệ/.test(c.label))?.value ?? 0;
    if (valid === 0) throw new Error('Không có dòng hợp lệ nào; không nhập.');
    if (!args.get('yes')) {
      const ok = await ask('Xác nhận nhập vào adh? (gõ c rồi Enter để nhập, phím khác để hủy): ');
      if (ok !== 'c') { log('Đã hủy, chưa lưu gì vào adh.'); return; }
    }
    const url = await commitImport(adhPage);
    log(`Đã nhập xong. Xem kết quả: ${url}`);
  } finally {
    await ctx.close().catch(() => {});
    if (!args.get('keep')) {
      for (const f of files) fs.rmSync(f, { force: true });
      if (files.length) log('Đã xóa file Excel tạm (chứa dữ liệu khách).');
    }
  }
}

main().catch((e) => { console.error(`LỖI: ${e.message}`); process.exitCode = 1; });
