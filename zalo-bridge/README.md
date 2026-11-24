# zalo-bridge: cầu nối Zalo cho Adh (D16)

Dịch vụ Node nhỏ nằm sau giao diện `ZaloGateway` của Adh (`docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`, hợp đồng HTTP ở `docs/ZALO-BRIDGE-API.md`). **Mặc định không khởi động** (profile Docker `zalo-auto`).

**Cảnh báo:** dùng thư viện không chính thức `zca-js` (giả lập Zalo Web) là **trái điều khoản Zalo** và **tài khoản phụ có thể bị khóa**. Chủ shop đã chấp nhận rủi ro cho tài khoản phụ (22/11/2026). Không bao giờ dùng tài khoản của bố mẹ.

## Chỉ có bốn việc (ba việc gốc + gửi tin từ D18)
1. `GET /status`: trạng thái phiên.
2. `GET /events?after=`: các lần "thành bạn" (sự kiện `ADD` của người khác); chỉ hỏi số điện thoại của đúng người đó.
3. `POST /groups/{id}/members`: thêm MỘT người vào MỘT nhóm.
4. `POST /messages` (D18): gửi MỘT tin (Tin 1 mời hoặc Tin 2 link nhóm) cho MỘT khách; có trần ngày, khoảng nghỉ CỐ ĐỊNH, mỗi khách một tin mỗi loại.

> **D18 (23/11/2026):** chủ shop đã bỏ nguyên tắc "không tự gửi tin"; việc gửi nay có mã (`POST /messages`, hợp đồng ở `docs/ZALO-BRIDGE-API.md`) nhưng vẫn giới hạn chặt: công tắc tắt mặc định, giờ gửi, trần ngày, khoảng nghỉ cố định, mỗi khách một lần, lỗi lạ thì dừng. Xem ADR-0002 mục 12.

Cộng với quản lý phiên cho trang quản trị (quét QR, đăng xuất, dừng, nghe lại). Có đúng MỘT đường gửi lời mời kết bạn (`POST /friend-requests`, từ 24/11/2026: cho một người đã nhận Tin 1 từ chính bộ nối, mỗi người một lời mời, có trần mỗi ngày `BRIDGE_FRIEND_DAILY`, mặc định 50). **Không có** nhận lời mời, liệt kê bạn bè, tìm người hàng loạt hay lệnh tùy ý (chỉ `findUser` cho đúng số của khách đang được nhắn, và `sendMessage` đúng một tin). Các bài test khóa điều này, kể cả số lần xuất hiện của `findUser`/`sendMessage` trong mã. Ba bài test khóa điều này: `test/no-send.test.mjs`, `test/server.test.mjs`, và `ZaloGatewayShapeTest` bên Java.

Cộng thêm một đường **chỉ đọc** `GET /groups`: nhóm Zalo mà tài khoản phụ đang tham gia (mã, tên, số thành viên), để trang quản trị chọn "mã nhóm Zalo". Không phải liệt kê bạn bè.

## Ranh giới (ngoài phạm vi)
Không có và sẽ không thêm: giả gõ phím, độ trễ ngẫu nhiên cho giống người, đổi `imei`/`userAgent`, proxy xoay, nhiều tài khoản luân phiên, gửi tin hàng loạt. *(D18, 23/11/2026: "gửi tin hàng loạt" không còn bị cấm; chủ shop cho phép gửi từ từ, giãn cách, lô nhỏ. Các thứ còn lại trong danh sách D18 không nhắc tới nên giữ nguyên; xem ADR-0002 mục 12.)* Cầu nối chỉ có giới hạn khối lượng (trần mỗi ngày, khung giờ 07:00–22:00 giờ Việt Nam), không phải màn che.

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
| `ZALO_ACCOUNT_ID` | Mã Zalo (chữ số) của tài khoản phụ (bắt buộc ở chế độ thật). Chưa biết thì đặt tạm `0`, quét QR bằng tài khoản phụ: trang quản trị hiện mã khi báo sai tài khoản |
| `BRIDGE_MODE` | `zca` (mặc định) hoặc `fake` |
| `BRIDGE_DATA_DIR` | Thư mục dữ liệu (mặc định `/data`) |
| `APP_ZALO_AUTO_MAX_DAILY` | Trần cứng của Adh (mặc định 100); cầu nối dùng gấp đôi |
| `BRIDGE_MSG_DAILY` | Trần cứng số tin mỗi ngày (mặc định 60; Adh có trần riêng thấp hơn, mặc định 25) |
| `BRIDGE_MSG_GAP_SEC` | Khoảng nghỉ tối thiểu giữa hai tin, **cố định** (mặc định 90, tối thiểu 30; Adh buộc ≥ 100) |
| `BRIDGE_PERSON_ERROR_CODES` | Mã lỗi Zalo đã biết là lỗi riêng người, cách nhau dấu phẩy |
| `BRIDGE_PORT`, `BRIDGE_HOST` | Mặc định 3100, 0.0.0.0 (trong mạng Docker, không công bố cổng) |

## Kiểm thử
`npm test` (Node ≥ 20). Không cần Zalo; dùng bộ điều hợp giả (`src/fake-adapter.mjs`). Bộ điều hợp thật (`src/zca-adapter.mjs`) **chưa được chạy với tài khoản Zalo thật** trong quá trình code; xem `docs/HUONG-DAN-THU-ZALO-TU-DONG.md` để chạy thử thật.

## Nâng zca-js
Phiên bản ghim đúng `2.3.0` trong `package.json` và `package-lock.json`. Chỉ nâng bằng PR có người xem; không dùng bản fork hay bản đăng lại. Mỗi lần nâng phải chạy lại hướng dẫn thử thật, vì Zalo hay đổi giao thức.
