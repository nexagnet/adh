# Cập nhật đơn J&T vào adh bằng Edge

Việc cần làm lặp lại: vào trang Đơn hàng của J&T, lọc theo **Thời gian tạo** đúng khoảng ngày adh gợi ý, bấm **Xuất**, rồi nhập file Excel vào adh (Nhập đơn, hãng J&T Express). Cả hai cách dưới đây đều **không đăng nhập J&T hay adh thay bạn**, không lưu mật khẩu hay cookie.

## Cách 1 (đã chạy thật ngày 22/11/2026): nhờ Claude làm trong Edge chính

Dùng phiên Edge bạn đã đăng nhập sẵn (J&T và adh) qua extension **Claude in Edge**. Nói với Claude: "cập nhật đơn J&T vào adh". Claude sẽ:

1. Mở `https://adh.43-134-105-89.sslip.io/imports/new`, đọc mục "Cần xuất Excel từ ngày nào?" của J&T Express (ví dụ 06/11/2026 đến 22/11/2026). Nếu adh chia nhiều khoảng (J&T chỉ cho lọc tối đa 31 ngày và xuất tối đa 500 đơn mỗi file) thì làm từng khoảng.
2. Mở `https://khachhang.jtexpress.vn/orderTable`. Ô ngày nằm sau nhãn "Thời gian tạo" (hai ô `input.ccmvn-range-input`, gõ `dd/MM/yyyy` rồi Enter), bấm **Tìm kiếm**, kiểm tra "Hiển thị: N đơn hàng" khớp nút **Xuất (N)**, rồi bấm Xuất. File `Data dd-MM-yyyy-HH-mm-ss <mã khách> ĐƠN HÀNG.xlsx` rơi vào thư mục Tải xuống.
3. Ở adh: chọn hãng **J&T Express**, tải file lên, kiểm tra cột adh đã đoán sẵn (3 cột bắt buộc: Mã vận đơn, SĐT người nhận, Trạng thái vận đơn), **Xem trước**, đọc số liệu, rồi **Xác nhận nhập**.
4. Báo bạn số liệu: tổng dòng, đơn mới, đơn đã có, đơn đổi trạng thái, dòng lỗi.

Claude dừng lại hỏi bạn nếu: bị đưa về trang đăng nhập, gặp OTP/captcha, số đơn không khớp, có dòng lỗi, hoặc adh báo trạng thái chưa rõ nghĩa.

Sau khi nhập, nên **xóa file Excel trong Tải xuống** (chứa tên, SĐT, địa chỉ thật của khách).

## Cách 2 (chưa chạy thử đủ): tool Node + Playwright, bấm đúp là chạy

Chạy trong một cửa sổ Edge **hồ sơ riêng** của tool (`%LOCALAPPDATA%\adh-jt-sync\edge-profile`). Edge không cho tool bên ngoài điều khiển hồ sơ chính của bạn, nên lần đầu bạn phải tự đăng nhập adh và J&T một lần trong cửa sổ đó; các lần sau phiên còn nguyên cho tới khi J&T hết hạn (ước chừng 1 ngày).

Cần: Node.js 20 trở lên, Microsoft Edge.

```powershell
cd tools\jt-edge-sync
npm install
node sync.mjs --dry      # chỉ tải file Excel từ J&T, chưa nhập vào adh
node sync.mjs            # tải, tải lên adh, xem trước, hỏi xác nhận rồi nhập
```

Hoặc bấm đúp `chay-dong-bo.bat`. Tùy chọn:

| Tùy chọn | Ý nghĩa |
|---|---|
| `--from dd/MM/yyyy --to dd/MM/yyyy` | Tự chọn khoảng ngày thay vì theo gợi ý của adh |
| `--dry` | Chỉ tải file về `%LOCALAPPDATA%\adh-jt-sync\downloads`, không nhập |
| `--yes` | Không hỏi xác nhận trước khi nhập |
| `--keep` | Không xóa file Excel tạm sau khi chạy |
| `--adh <địa chỉ>` | Địa chỉ adh khác (hoặc đặt biến môi trường `ADH_URL`) |

Tool dừng và chờ bạn nếu bị đưa về trang đăng nhập (adh hoặc J&T), và dừng hẳn với thông báo lỗi nếu số đơn trên J&T không khớp nút Xuất, vượt 500 đơn, hoặc adh chưa đoán được cột bắt buộc. File Excel tải về bị xóa sau khi chạy (trừ `--dry`, `--keep`).

**Trạng thái kiểm chứng:** phần lọc ngày và bấm Xuất trên J&T, cùng luồng tải lên, xem trước, xác nhận trên adh đã được làm đúng bằng Cách 1 (các bộ chọn như trên). Phần mã Playwright của Cách 2 mới được viết, chưa chạy trọn một lần vì bạn chưa đăng nhập được J&T trong hồ sơ riêng.

## Giới hạn và lưu ý

- J&T hết phiên khoảng 1 ngày, có OTP và dấu hiệu captcha lúc đăng nhập, nên đăng nhập luôn là việc của người. Không dùng trình duyệt "ẩn danh" để né captcha.
- Giao diện J&T hoặc adh đổi thì bộ chọn có thể hỏng; tool sẽ dừng và báo lỗi thay vì nhập thiếu.
- Đơn kẹt "Đã lấy hàng" từ tháng 6 và 7 không được J&T cập nhật nữa; cần chủ shop chọn nghĩa ở mục Trạng thái đơn của adh.
