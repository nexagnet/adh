# zalo-bridge: cầu nối Zalo cho Adh (D16)

Dịch vụ Node nhỏ nằm sau giao diện `ZaloGateway` của Adh (`docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`, hợp đồng HTTP ở `docs/ZALO-BRIDGE-API.md`). **Mặc định không khởi động** (profile Docker `zalo-auto`).

**Cảnh báo:** dùng thư viện không chính thức `zca-js` (giả lập Zalo Web) là **trái điều khoản Zalo** và **tài khoản phụ có thể bị khóa**. Chủ shop đã chấp nhận rủi ro cho tài khoản phụ (22/11/2026). Không bao giờ dùng tài khoản của bố mẹ.

## Chỉ có ba việc
1. `GET /status`: trạng thái phiên.
2. `GET /events?after=`: các lần "thành bạn" (sự kiện `ADD` của người khác); chỉ hỏi số điện thoại của đúng người đó.
3. `POST /groups/{id}/members`: thêm MỘT người vào MỘT nhóm.

Cộng với quản lý phiên cho trang quản trị (quét QR, đăng xuất, dừng, nghe lại). **Không có** gửi tin, gửi lời mời kết bạn, tìm người theo số, liệt kê bạn bè hay lệnh tùy ý. Ba bài test khóa điều này: `test/no-send.test.mjs`, `test/server.test.mjs`, và `ZaloGatewayShapeTest` bên Java.

## Ranh giới (ngoài phạm vi)
Không có và sẽ không thêm: giả gõ phím, độ trễ ngẫu nhiên cho giống người, đổi `imei`/`userAgent`, proxy xoay, nhiều tài khoản luân phiên, gửi tin hàng loạt. Cầu nối chỉ có giới hạn khối lượng (trần mỗi ngày, khung giờ 07:00–22:00 giờ Việt Nam), không phải màn che.

## Cầu nối tự bảo vệ
- Phiên (`imei`, `cookie`, `userAgent`) mã hóa **AES-256-GCM** trước khi ghi (`/data/session.enc`, quyền 600); khóa `SESSION_KEY` ở biến môi trường, tách khỏi tệp và sao lưu. Thiếu khóa thì không khởi động.
- Chỉ tài khoản có mã `ZALO_ACCOUNT_ID` (tài khoản phụ) được giữ phiên; quét nhầm tài khoản khác thì bị từ chối và đăng xuất.
- Cầu dao riêng: mã đóng `3000` (cùng tài khoản mở nơi khác) hay `3003`, mất phiên, Zalo cảnh báo hoặc lỗi lạ khi thêm người, hết giờ chờ, vượt trần cứng (gấp đôi `APP_ZALO_AUTO_MAX_DAILY`), ngoài khung giờ: dừng, đóng kết nối nghe, **ghi vào đĩa** (khởi động lại không xóa cầu dao). Chỉ `POST /session/resume` (người bấm "Mở lại") thoát.
- Đứt kết nối bất thường (đo thật: mã 1006 sau khoảng 40 phút) thì nối lại bằng phiên đã lưu với backoff 5s → 15s → 45s → 2 phút → 5 phút, tối đa 8 lần rồi dừng.
- Nhật ký chỉ có thông điệp ngắn, **không** uid, số điện thoại hay tên. Sự kiện chỉ nằm trong bộ nhớ.
- Lỗi theo từng người chỉ được coi là lỗi riêng người khi Zalo trả `errorMembers` cho đúng người đó, hoặc mã lỗi nằm trong `BRIDGE_PERSON_ERROR_CODES` (mặc định rỗng). Lỗi chưa biết thì dừng.

## Chạy thử không cần Zalo (chế độ giả)
```bash
cd zalo-bridge
npm ci --ignore-scripts
BRIDGE_MODE=fake BRIDGE_KEY=khoa-chung-thu-nghiem-1 SESSION_KEY=$(openssl rand -hex 32) \
  BRIDGE_DATA_DIR=/tmp/bridge BRIDGE_HOST=127.0.0.1 node src/main.mjs
```
Ở chế độ này không nối Zalo; quét QR "tự xong" sau vài giây, và có thêm `POST /fake/friend {"uid":"123","phone":"84912345678"}` để bơm một sự kiện kết bạn xem Adh xử lý thế nào. Đường này **không tồn tại** ở chế độ thật.

## Biến môi trường
| Biến | Ý nghĩa |
|---|---|
| `BRIDGE_KEY` | Khóa chung với Adh, ≥ 16 ký tự (bắt buộc) |
| `SESSION_KEY` | 64 ký tự hex, khóa AES-256 mã hóa phiên (bắt buộc) |
| `ZALO_ACCOUNT_ID` | Mã Zalo (chữ số) của tài khoản phụ (bắt buộc ở chế độ thật) |
| `BRIDGE_MODE` | `zca` (mặc định) hoặc `fake` |
| `BRIDGE_DATA_DIR` | Thư mục dữ liệu (mặc định `/data`) |
| `APP_ZALO_AUTO_MAX_DAILY` | Trần cứng của Adh (mặc định 100); cầu nối dùng gấp đôi |
| `BRIDGE_PERSON_ERROR_CODES` | Mã lỗi Zalo đã biết là lỗi riêng người, cách nhau dấu phẩy |
| `BRIDGE_PORT`, `BRIDGE_HOST` | Mặc định 3100, 0.0.0.0 (trong mạng Docker, không công bố cổng) |

## Kiểm thử
`npm test` (Node ≥ 20). Không cần Zalo; dùng bộ điều hợp giả (`src/fake-adapter.mjs`). Bộ điều hợp thật (`src/zca-adapter.mjs`) **chưa được chạy với tài khoản Zalo thật** trong quá trình code; xem `docs/HUONG-DAN-THU-ZALO-TU-DONG.md` để chạy thử thật.

## Nâng zca-js
Phiên bản ghim đúng `2.3.0` trong `package.json` và `package-lock.json`. Chỉ nâng bằng PR có người xem; không dùng bản fork hay bản đăng lại. Mỗi lần nâng phải chạy lại hướng dẫn thử thật, vì Zalo hay đổi giao thức.
