# Hợp đồng HTTP giữa Adh và dịch vụ cầu nối Zalo (`zalo-bridge`)

Áp dụng cho D16 / `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`. Phía Adh: `vn.shop.crm.zalo.ZcaBridgeGateway`. Phía Node: thư mục `zalo-bridge/` (xem `zalo-bridge/README.md`).

- Chỉ gọi trong **mạng nội bộ Docker**; dịch vụ không công bố cổng ra ngoài.
- Mọi yêu cầu có tiêu đề `X-Bridge-Key: <khóa chung>`; sai khóa trả `401`.
- Mọi trả lời là JSON, mã `200` cho mọi kết quả đã xử lý (kể cả lỗi riêng một người); mã khác `200` là lỗi hạ tầng và Adh coi là "lạ".
- **D18 (23/11/2026):** thêm đường `POST /messages` (gửi MỘT tin văn bản cho MỘT khách, có hạn mức và khoảng nghỉ cố định). **Vẫn không có** đường gửi lời mời kết bạn, nhận lời mời, liệt kê bạn bè hay lệnh tùy ý. Chỉ có thêm đường đọc `GET /groups` (nhóm của chính tài khoản phụ). Test `ZaloGatewayShapeTest` (Java), `zalo-bridge/test/server.test.mjs` và `no-send.test.mjs` (Node) khóa việc này, kể cả số lần xuất hiện của `findUser`/`sendMessage` (đúng một lần mỗi hàm, chỉ trong `zca-adapter.mjs`).

## Việc của Zalo (ba việc gốc, cộng `POST /messages` từ D18)

### `GET /status`
```json
{ "state": "LISTENING", "tripReason": null, "accountOk": true,
  "lastEventAt": "2026-11-22T03:00:00Z", "sessionSince": "2026-11-22T01:00:00Z", "detail": null }
```
`state`: `LOGIN_REQUIRED` (chưa có phiên hoặc hết hạn), `CONNECTING`, `LISTENING`, `TRIPPED` (cầu nối tự dừng, `tripReason` nói vì sao: `DUPLICATE_CONNECTION`, `KICKED`, `RESTRICTED`, `SESSION_LOST`, `SYSTEM_ERROR`, `LIMIT`, `WINDOW`, `RECONNECT_FAILED`, `LEDGER_CORRUPT`, `INTERRUPTED` (sập đúng lúc đang thêm người, không biết kết quả), `CRASH_LOOP` (khởi động lại quá 5 lần trong 10 phút); có thể kèm `:mã` sau), `STOPPED` (được yêu cầu dừng, giữ phiên). `accountOk` = tài khoản đang đăng nhập đúng là tài khoản phụ đã khai báo.

### `GET /events?after=<seq>`
Các lần "thành bạn" (loại `ADD` của zca-js, `isSelf=false`) có `seq` > `after`, tăng dần:
```json
{ "events": [ { "seq": 1763780000001, "uid": "123456789", "phone": "84912345678", "at": "2026-11-22T03:00:00Z" } ] }
```
`phone` là `null` khi người đó không cho thấy số (đa số). Cầu nối chỉ hỏi số của **đúng uid trong sự kiện**, không dò ai khác. Sự kiện `REQUEST` (người khác tự gửi lời mời) **không** được trả về. Cầu nối giữ sự kiện trong bộ nhớ, bỏ những sự kiện có `seq` ≤ `after` của lần hỏi sau; `seq` tăng đơn điệu kể cả sau khi khởi động lại (dựa trên thời gian).

### `POST /groups/{zaloGroupId}/members`  body `{"uid": "123456789"}`
Cả hai mã chỉ gồm chữ số (≤ 32). Trả:
```json
{ "outcome": "OK" }
{ "outcome": "PERSON_ERROR", "code": "ALREADY_MEMBER" }
```
`outcome`: `OK`; `PERSON_ERROR` (lỗi riêng người này: đã trong nhóm, bị chặn, nhóm đầy, không phải bạn…; không làm cầu dao nhảy); `SESSION_LOST`; `RESTRICTED` (Zalo yêu cầu xác minh/captcha/hạn chế, hoặc chạm trần của chính cầu nối); `SYSTEM_ERROR` (mọi lỗi lạ). Ba loại sau làm Adh **dừng ngay**; cầu nối cũng tự dừng. `code` chỉ gồm chữ, số, gạch, không chứa dữ liệu khách.

Cầu nối có **trần cứng riêng** (gấp đôi `APP_ZALO_AUTO_MAX_DAILY` mỗi ngày, trong khung giờ 07:00–22:00 giờ Việt Nam): vượt thì trả `RESTRICTED` mã `LIMIT` và tự dừng.

### `POST /messages` (D18)  body `{"kind":"INVITE"|"LINK", "phone":"84912345678" | "uid":"123456789", "text":"..."}`
Đúng một trong `phone` hoặc `uid`; `text` không rỗng, tối đa 1000 ký tự. Cầu nối tra mã Zalo theo số (`findUser`) rồi gửi MỘT tin (`sendMessage`). Trả:
```json
{ "outcome": "OK", "uid": "123456789" }
{ "outcome": "RATE_LIMITED", "retryAfterSec": 45 }
{ "outcome": "PERSON_ERROR", "code": "NOT_FOUND" }
```
- `OK`: đã gửi (Zalo có xác nhận). `uid` kèm theo khi gửi theo số.
- `RATE_LIMITED`: chưa đủ khoảng nghỉ cố định giữa hai tin (`BRIDGE_MSG_GAP_SEC`, mặc định 90 giây). **Chắc chắn chưa gửi**; Adh chờ `retryAfterSec`.
- `PERSON_ERROR`: lỗi riêng người này, không làm cầu dao nhảy: `NOT_FOUND` (số không có Zalo hoặc không tìm thấy), `DUPLICATE` (đã gửi tin loại này cho người này rồi, sổ nhớ bằng mã băm HMAC, không bao giờ gửi lần hai), `BAD_INPUT`, `BAD_PHONE`, `BAD_ID`.
- `RESTRICTED` (`WINDOW` ngoài 07:00–22:00; `MSG_LIMIT` quá `BRIDGE_MSG_DAILY` mỗi ngày, mặc định 60; `NOT_ACTIVE`), `SESSION_LOST` (`LOGIN_REQUIRED`), `SYSTEM_ERROR` (`BUSY`, `NOT_CONNECTED`, và `NO_ACK` khi Zalo không xác nhận: **không biết đã gửi hay chưa**, cầu nối tự dừng). Các mã `BUSY`, `NOT_CONNECTED`, `NOT_ACTIVE`, `WINDOW`, `MSG_LIMIT`, `LOGIN_REQUIRED`, `BAD_*` là bị từ chối TRƯỚC khi chạm Zalo (Adh coi là "chưa gửi"); mọi kết quả lạ khác Adh coi là "không biết", đánh dấu thất bại và dừng, không tự gửi lại.
- Không có độ trễ ngẫu nhiên, không giả người: khoảng nghỉ là **cố định**, trần và khung giờ là cầu dao thật. Nhật ký bộ nối không chứa số, mã, tên hay nội dung tin.

## Đọc danh sách nhóm (chỉ đọc, chỉ trang quản trị gọi)
### `GET /groups`
Nhóm mà **tài khoản phụ đang tham gia**, để chọn "mã nhóm Zalo" cho nhóm của Adh (không phải liệt kê bạn bè):
```json
{ "ok": true, "groups": [ { "id": "987654321", "name": "Khách hàng Miền Nam", "members": 12, "adminOnlyAdd": false, "isAdmin": true } ] }
{ "ok": false, "code": "LOGIN_REQUIRED" }
```
`code` khi không đọc được: `LOGIN_REQUIRED`, `NOT_ACTIVE` (đang dừng/chưa nghe), `BUSY`, `LIST_FAILED`. Chỉ đọc nên lỗi **không** làm cầu dao nhảy. Chỉ hỏi khi đang nghe bình thường; kết quả được giữ 30 giây để không hỏi dồn Zalo. Tên nhóm không vào log. `adminOnlyAdd` = nhóm bật "chỉ quản trị viên được thêm thành viên"; `isAdmin` = tài khoản phụ có là chủ/quản trị viên nhóm không.

## Quản lý phiên (chỉ trang quản trị gọi)
- `POST /session/login` bắt đầu quét QR → `{ "state": "WAITING_SCAN", "qr": "<PNG base64>" }`; `GET /session/login` hỏi tiến độ. `state`: `WAITING_SCAN`, `SCANNED`, `DONE`, `EXPIRED`, `FAILED`, `ACCOUNT_MISMATCH` (tài khoản quét không phải tài khoản phụ đã khai báo: cầu nối từ chối và đăng xuất, không lưu gì, không đụng phiên phụ đang chạy). Khi và chỉ khi `ACCOUNT_MISMATCH`, trả thêm `scannedId` (mã Zalo chữ số của tài khoản vừa quét) để người quản trị điền đúng `ZALO_ACCOUNT_ID`; mã này chỉ ở bộ nhớ, không log, không lưu đĩa, Adh chỉ hiện trên trang quét QR của admin. Ảnh QR chỉ nằm trong bộ nhớ.
- `POST /session/logout` đăng xuất và xóa tệp phiên. `POST /session/stop` dừng nghe, giữ phiên. `POST /session/resume` nghe lại bằng phiên đã lưu (chỉ khi người bấm "Mở lại"). Cả ba trả `{ "ok": true|false }`.
- Mật khẩu và OTP Zalo không bao giờ đi qua hợp đồng này.

## Cách Adh xử lý lỗi
| Tình huống | Adh làm |
|---|---|
| Không nối được cầu nối (từ chối kết nối) | `UNREACHABLE`: chưa chạm Zalo, không tính hạn mức, không dừng, thử lại nhịp sau |
| Hết giờ chờ lúc thêm người (kết quả không biết) | `SYSTEM_ERROR` mã `TIMEOUT`: dừng ngay, khách để người kiểm tra tay |
| Trả lời lạ, mã HTTP khác 200 lúc thêm người | `SYSTEM_ERROR`: dừng ngay |
| `PERSON_ERROR` | Ghi `FAILED` cho khách đó, không tự thử lại, hiện cho người xử lý |
