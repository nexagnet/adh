# Hợp đồng HTTP giữa Adh và dịch vụ cầu nối Zalo (`zalo-bridge`)

Áp dụng cho D16 / `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`. Phía Adh: `vn.shop.crm.zalo.ZcaBridgeGateway`. Phía Node: thư mục `zalo-bridge/` (xem `zalo-bridge/README.md`).

- Chỉ gọi trong **mạng nội bộ Docker**; dịch vụ không công bố cổng ra ngoài.
- Mọi yêu cầu có tiêu đề `X-Bridge-Key: <khóa chung>`; sai khóa trả `401`.
- Khi CRM đã xác nhận cầu nối, các yêu cầu nghiệp vụ kèm `X-Bridge-Account-Tag` bằng dấu từ `/status`. Cầu nối trả `409` với `account_mismatch` nếu dấu không khớp cấu hình, trước khi xử lý yêu cầu.
- Mọi trả lời là JSON, mã `200` cho mọi kết quả đã xử lý (kể cả lỗi riêng một người); mã khác `200` là lỗi hạ tầng và Adh coi là "lạ".
- **D18 (23/11/2026):** thêm đường `POST /messages` (gửi MỘT tin văn bản cho MỘT khách, có hạn mức và khoảng nghỉ cố định). **24/11/2026:** thêm `POST /friend-requests` (body `{uid, text}`, text tối đa 150 ký tự): gửi MỘT lời mời kết bạn cho MỘT người đã nhận Tin 1 từ chính bộ nối (không thì `PERSON_ERROR NO_INVITE`), mỗi người một lần (`DUPLICATE`), trần mỗi ngày `BRIDGE_FRIEND_DAILY` (mặc định 50, lỗi `FRIEND_LIMIT`), khoảng nghỉ chung với tin. **24/11/2026:** `POST /groups` nay cho mẹ (phó nhóm) vào nhóm NGAY LÚC TẠO như một thành viên (Zalo chỉ cho đặt phó nhóm với người đang trong nhóm), kết quả thêm `deputyCode` (mã lỗi Zalo thật dạng `Z118`) khi đặt phó nhóm lỗi; thêm `POST /groups/{id}/deputy` (`{uid}`): thêm mẹ vào nhóm nếu chưa có rồi đặt phó nhóm, trần 10 lần mỗi ngày. **25/11/2026:** `POST /friend-requests` gặp mã Zalo 223 (`BRIDGE_FRIEND_LIST_FULL_CODES`, mặc định 223; "Danh sách yêu cầu của bạn đã đầy") trả `{outcome:"PERSON_ERROR",code:"FRIEND_LIST_FULL"}` và KHÔNG dừng cầu dao; lời mời chưa đi nên không đánh dấu "đã mời", Adh tạm dừng riêng phần lời mời kết bạn. Thêm loại tin `ASK_FRIEND` cho `POST /messages` (chỉ `uid`, chỉ người đã nhận Tin 1 từ chính bộ nối, mỗi người một lần, không gửi cho người đã được gửi lời mời kết bạn `FRIEND_REQUESTED` hay đang nhắn tới): MỘT tin nhờ khách kết bạn lại. **25/11/2026:** thêm `POST /friend-requests/undo` (`{uid}`): thu hồi MỘT lời mời kết bạn mà chính bộ nối đã gửi (zca-js `undoFriendRequest`, đúng một chỗ gọi trong `zca-adapter.mjs`); không thì `PERSON_ERROR NOT_REQUESTED`, mỗi người một lần (`DUPLICATE`), trần `BRIDGE_UNDO_DAILY` (30/ngày) và nghỉ `BRIDGE_UNDO_GAP_SEC` (60 giây) → `RATE_LIMITED`; mã lỗi Zalo thành `PERSON_ERROR Z<mã>` không dừng cầu dao. **25/11/2026:** thêm `GET /friend-requests/incoming` (mã Zalo của khách đã nhận Tin 1 vừa tự gửi lời mời kết bạn tới, chỉ đọc từ bộ nhớ) và `POST /friend-requests/accept` (`{uid}`, zca-js `acceptFriendRequest`, một chỗ gọi): chỉ người trong danh sách đó (`PERSON_ERROR NO_REQUEST` nếu không), mỗi người một lần (`DUPLICATE`), trần `BRIDGE_ACCEPT_DAILY` (50/ngày) và nghỉ cố định `BRIDGE_ACCEPT_GAP_SEC` (30 giây) → `RATE_LIMITED`; mã lỗi Zalo thành `PERSON_ERROR Z<mã>` không dừng cầu dao. `ASK_FRIEND` nay cũng nhận cho người đã bị thu hồi lời mời. **Vẫn không có** nhận lời mời của người ngoài hệ thống, liệt kê bạn bè hay lệnh tùy ý. Chỉ có thêm đường đọc `GET /groups` (nhóm của chính tài khoản phụ). Test `ZaloGatewayShapeTest` (Java), `zalo-bridge/test/server.test.mjs` và `no-send.test.mjs` (Node) khóa việc này, kể cả số lần xuất hiện của `findUser`/`sendMessage` (đúng một lần mỗi hàm, chỉ trong `zca-adapter.mjs`).

## Việc của Zalo (ba việc gốc, cộng `POST /messages` từ D18, `POST /groups` và `POST /people/lookup` từ D19)

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

### `GET /inbox?after=<seq>` (D19, nhận diện từ chối; TẮT mặc định)
Tin nhắn RIÊNG (1-1, dạng văn bản, không phải của chính tài khoản) mà người **đã nhận Tin 1 hoặc Tin 2 từ chính cầu nối** gửi lại, có `seq` > `after`, tăng dần:
```json
{ "enabled": true, "messages": [ { "seq": 1763780000002, "uid": "123456789", "text": "Thôi đừng nhắn nữa nhé", "at": "2026-11-22T03:05:00Z" } ] }
```
Chỉ có khi đặt `BRIDGE_INBOX_ENABLED=true` ở cầu nối; tắt thì cầu nối **không đăng ký nghe tin nhắn** và trả `{ "enabled": false, "messages": [] }`. Cầu nối lọc bằng sổ gửi tin của chính nó (băm HMAC): tin của người chưa từng nhận tin của hệ thống bị bỏ ngay, không lưu, không ghi log. Không đọc tin nhóm, tệp, ảnh hay lịch sử cũ. Tin giữ **trong bộ nhớ** tối đa 6 giờ và 300 tin, cắt còn 500 ký tự, bỏ khi Adh xác nhận (`seq` ≤ `after`); nội dung, mã Zalo không bao giờ vào log. Adh che dữ liệu (`PiiMasker`) rồi mới nhờ AI gắn nhãn.

### `GET /friend-requests/status?uid=123456789`

Đọc trạng thái lời mời của một UID đã có trong sổ Tin 1 của cầu nối. Trả `{"ok":true,"state":"FRIENDS"}` hoặc một trong các trạng thái `OUTGOING`, `INCOMING`, `NONE`. Lỗi đọc trả `{"ok":false,"code":"..."}`. Dữ liệu trống hoặc trái hợp đồng được coi là lỗi đọc. `NONE` là quan sát không thấy quan hệ hay lời mời, chưa xác định được ai đã thu hồi hoặc từ chối.

ADH có thể quan sát một việc mỗi tài khoản trong từng nhịp với `APP_ZALO_RECONCILE_ENABLED=true`. Mặc định chạy cách nhau 30 phút để phù hợp với ngân sách đọc mặc định của cầu nối. Ảnh cầu nối cũ chưa có endpoint này sẽ chỉ cho kết quả không đọc được. Kiểm chứng chiều lời mời bằng tài khoản thử trước khi dùng làm điều kiện phục hồi.

`APP_ZALO_RECONCILE_TEST_ONLY=true` là mặc định. Cờ này giới hạn lịch đối chiếu ở danh sách số thử, kể cả khi cả hai tài khoản vẫn gửi thật theo các chiến dịch đang chạy. Tắt giới hạn này là một bước vận hành riêng sau nghiệm thu. Chế độ gửi thử của từng tài khoản vẫn áp dụng cho cả việc đọc.

Ở môi trường thử, đặt thêm `BRIDGE_FRIEND_RECONCILE_ENABLED=true` trên từng cầu nối cần giữ đối chiếu khi gặp `SYSTEM_ERROR:TIMEOUT` hoặc `SYSTEM_ERROR:NO_ACK`. Tệp Compose hai cầu nối dùng `BRIDGE_FRIEND_RECONCILE_ENABLED_2` cho cầu nối 2. Tất cả mặc định tắt. Cầu nối chỉ giữ hoặc khôi phục phiên đọc nếu đúng tài khoản, đã kết nối và không bị dừng cứng. ADH vẫn giữ cầu dao gửi chung, cần người xem trước khi mở lại. `/status` báo `friendReconcileAvailable`; có thể là false nếu cầu nối cũ, lệch tài khoản, mất phiên hay đang kết nối.

### `POST /groups/{zaloGroupId}/members`  body `{"uid": "123456789"}`
Kết quả có thể là `GROUP_ERROR` (24/11/2026): Zalo báo lỗi của CẢ NHÓM (nhóm không còn, không đủ quyền...). Chỉ khi mã lỗi nằm trong `BRIDGE_GROUP_ERROR_CODES` (mặc định rỗng, điền sau khi chạy thật); không làm cầu nối dừng, Adh tạm dừng đúng nhóm đó.
Cả hai mã chỉ gồm chữ số (≤ 32). Trả:
```json
{ "outcome": "OK" }
{ "outcome": "PERSON_ERROR", "code": "ALREADY_MEMBER" }
```
`outcome`: `OK`; `PERSON_ERROR` (lỗi riêng người này: đã trong nhóm, bị chặn, nhóm đầy, không phải bạn…; không làm cầu dao nhảy); `SESSION_LOST`; `RESTRICTED` (Zalo yêu cầu xác minh/captcha/hạn chế, hoặc chạm trần của chính cầu nối); `SYSTEM_ERROR` (mọi lỗi lạ). Ba loại sau làm Adh **dừng ngay**; cầu nối cũng tự dừng. `code` chỉ gồm chữ, số, gạch, không chứa dữ liệu khách.

Cầu nối có **trần cứng riêng** (gấp đôi `APP_ZALO_AUTO_MAX_DAILY` mỗi ngày, trong khung giờ 07:00–22:00 giờ Việt Nam): vượt thì trả `RESTRICTED` mã `LIMIT` và tự dừng.

### `POST /messages` (D18)  body `{"kind":"INVITE"|"LINK"|"WELCOME"|"REPLY", "phone":"84912345678" | "uid":"123456789", "text":"..."}`
Đúng một trong `phone` hoặc `uid`; `text` không rỗng, tối đa 1000 ký tự. Cầu nối tra mã Zalo theo số (`findUser`) rồi gửi MỘT tin (`sendMessage`). Trả:
```json
{ "outcome": "OK", "uid": "123456789" }
{ "outcome": "RATE_LIMITED", "retryAfterSec": 45 }
{ "outcome": "PERSON_ERROR", "code": "NOT_FOUND" }
```
- `OK`: đã gửi (Zalo có xác nhận). `uid` kèm theo khi gửi theo số.
- `RATE_LIMITED`: chưa đủ khoảng nghỉ cố định giữa hai tin (`BRIDGE_MSG_GAP_SEC`, mặc định 90 giây; riêng tin trả lời `WELCOME`/`REPLY` là `BRIDGE_REPLY_GAP_SEC`, mặc định 30 giây, tính từ tin gần nhất bất kể loại). **Chắc chắn chưa gửi**; Adh chờ `retryAfterSec`.
- `PERSON_ERROR`: lỗi riêng người này, không làm cầu dao nhảy: `NOT_FOUND` (số không có Zalo hoặc không tìm thấy), `DUPLICATE` (đã gửi tin loại này cho người này rồi, sổ nhớ bằng mã băm HMAC, không bao giờ gửi lần hai), `BAD_INPUT`, `BAD_PHONE`, `BAD_ID`.
- `RESTRICTED` (`WINDOW` ngoài 07:00–22:00; `MSG_LIMIT` quá `BRIDGE_MSG_DAILY` mỗi ngày (phanh khẩn cấp đếm MỌI tin, mặc định 150; Adh chỉ tính hạn mức ngày theo Tin 1); `NOT_ACTIVE`), `SESSION_LOST` (`LOGIN_REQUIRED`), `SYSTEM_ERROR` (`BUSY`, `NOT_CONNECTED`, và `NO_ACK` khi Zalo không xác nhận: **không biết đã gửi hay chưa**, cầu nối tự dừng). Các mã `BUSY`, `NOT_CONNECTED`, `NOT_ACTIVE`, `WINDOW`, `MSG_LIMIT`, `LOGIN_REQUIRED`, `BAD_*` là bị từ chối TRƯỚC khi chạm Zalo (Adh coi là "chưa gửi"); mọi kết quả lạ khác Adh coi là "không biết", đánh dấu thất bại và dừng, không tự gửi lại.
- **`WELCOME` (D19, khách từng từ chối tự nhắn lại muốn mua):** CHỈ theo `uid` (không có `phone`), cần bật `BRIDGE_INBOX_ENABLED`, và chỉ cho người **vừa nhắn tới trong 24 giờ**: cầu nối giữ trong bộ nhớ "mã Zalo -> giờ tin riêng gần nhất" (chỉ người đã nhận Tin 1/Tin 2, như bộ đệm tin đến) và cho trả lời đúng MỘT lần cho mỗi tin đến. Không có đường nào nhắn người chưa nhắn trước: không khớp thì trả `PERSON_ERROR` mã `NOT_REPLYABLE` (từ chối trước khi chạm Zalo, Adh coi là "chưa gửi" và không tính cầu dao). Vẫn chịu khoảng nghỉ cố định, khung giờ, trần ngày và cầu dao chung; không ghi vào sổ `DUPLICATE` (quy tắc "tối đa một lần tự gỡ trong 30 ngày" do Adh giữ).
- **`REPLY` (D20, câu trả lời do trợ lý AI soạn):** cùng chốt chặn như `WELCOME`: chỉ theo `uid`, cần `BRIDGE_INBOX_ENABLED`, chỉ cho người vừa nhắn tới trong 24 giờ, đúng MỘT lần cho mỗi tin đến (`WELCOME` và `REPLY` dùng chung một lượt trả lời), không ghi sổ `DUPLICATE`. Bộ nối KHÔNG kiểm nội dung câu: Adh đã kiểm cứng ({@code ReplyValidator}) trước khi gửi.
- Không có độ trễ ngẫu nhiên, không giả người: khoảng nghỉ là **cố định**, trần và khung giờ là cầu dao thật. Nhật ký bộ nối không chứa số, mã, tên hay nội dung tin.

### `POST /groups` (D19)  body `{"name":"Áo điều hòa ADH - Miền Nam - 01", "members":["111","222"], "deputy":"333"}`
Tạo đúng MỘT nhóm Zalo, bật link mời và (nếu có `deputy`) thêm người đó làm phó nhóm. `name` 1–100 ký tự, không có ký tự điều khiển; `members` 1–5 mã Zalo chữ số (khách đã đồng ý vào nhóm); `deputy` tùy chọn (mã của mẹ, Adh đọc từ biến môi trường, không ghi vào repo). Trả:
```json
{ "outcome": "OK", "groupId": "9876", "link": "https://zalo.me/g/xxxx", "added": ["111"], "failed": ["222"], "deputy": "OK" }
{ "outcome": "RATE_LIMITED", "retryAfterSec": 120 }
{ "outcome": "PERSON_ERROR", "code": "BAD_NAME" }
```
- `OK`: nhóm đã tạo. `added` là người Zalo xác nhận đã vào, `failed` là người chưa vào (Adh dùng link mời làm dự phòng). `link` là `null` khi bật link lỗi; `deputy` là `OK`, `FAILED` hoặc `NONE`. Bật link hoặc thêm phó nhóm lỗi **không** làm cầu dao nhảy (nhóm đã có; Adh ghi lại và nhắc mẹ làm tay), trừ khi Zalo báo hạn chế/mất phiên.
- `PERSON_ERROR`: `BAD_NAME`, `BAD_MEMBERS`, `BAD_DEPUTY` (đầu vào sai, chưa chạm Zalo).
- `RATE_LIMITED`: chưa đủ khoảng nghỉ cố định giữa hai lần tạo (`BRIDGE_GROUP_GAP_SEC`, mặc định và tối thiểu 300 giây). Chắc chắn chưa tạo.
- `RESTRICTED` (`WINDOW` ngoài 07:00–22:00; (từ 24/11/2026 cầu nối KHÔNG còn trần số nhóm tạo mỗi ngày, mã `GROUP_LIMIT` và biến `BRIDGE_GROUP_DAILY` đã bỏ; chỉ còn khoảng nghỉ tối thiểu `BRIDGE_GROUP_GAP_SEC`) `NOT_ACTIVE`), `SESSION_LOST` (`LOGIN_REQUIRED`), `SYSTEM_ERROR` (`BUSY`, `NOT_CONNECTED`, `NO_ACK` khi Zalo không trả mã nhóm: **không biết đã tạo hay chưa**, cầu nối tự dừng, không tự thử lại).
- Sổ đếm lần tạo (`group-creates.json`) ghi TRƯỚC khi gọi Zalo; sổ hỏng thì dừng (`LEDGER_CORRUPT`). Không có độ trễ ngẫu nhiên; tên nhóm, mã nhóm, mã người không vào log. Bộ nối **không** đổi chủ nhóm, đổi tên, xóa nhóm, đuổi người hay chặn ai.

### `POST /people/lookup` (D19)  body `{"phone":"84912345678"}`
Tra tên và ảnh đại diện của **đúng một** số điện thoại (số của mẹ, chủ shop nhập tay ở trang Zalo) để chủ shop bấm xác nhận đúng người trước khi làm phó nhóm. Chỉ đọc: không nhắn, không kết bạn. Chỉ tra được khi người đó đã bật cho phép tìm bằng số điện thoại trong Zalo. Trả:
```json
{ "outcome": "OK", "uid": "123456789", "name": "Tên hiển thị", "avatar": "data:image/jpeg;base64,..." }
{ "outcome": "PERSON_ERROR", "code": "NOT_FOUND" }
{ "outcome": "RATE_LIMITED", "retryAfterSec": 1800 }
```
- `avatar` là ảnh nhỏ (≤ 100 KB) đã tải về thành data URI (chỉ https tới máy chủ ảnh của Zalo, không theo chuyển hướng); `null` khi tải ảnh lỗi (chỉ mất ảnh, việc tra vẫn `OK`).
- `PERSON_ERROR`: `BAD_PHONE`, `NOT_FOUND` (không có Zalo hoặc không cho tìm bằng số). Không làm cầu dao nhảy.
- `RATE_LIMITED`: quá `BRIDGE_PROFILE_LOOKUPS_PER_HOUR` lần mỗi giờ (mặc định 5, tối đa 20; tính cả lần lỗi). `RESTRICTED` mã `WINDOW` ngoài 07:00–22:00 (không dừng), `NOT_ACTIVE`; `SESSION_LOST`; `SYSTEM_ERROR` (`BUSY`, `NOT_CONNECTED`, lỗi lạ: cầu nối tự dừng).
- Số điện thoại, mã Zalo và tên không vào log. Gọi `findUser` của thư viện vẫn chỉ ở một chỗ (`zca-adapter.mjs`).

### `POST /friends/check` (24/11/2026, CHỈ ĐỌC)  body `{"uids":["123","456"]}` (tối đa 500 mã số)
Trong số mã Adh hỏi, mã nào đang là bạn của tài khoản phụ. Trả `{"ok":true,"friends":["123"]}` hoặc `{"ok":false,"code":"WINDOW|BUSY|LIMIT|NOT_ACTIVE|LOGIN_REQUIRED|LIST_FAILED|BAD_INPUT"}`. Bộ nối đọc danh sách bạn bằng zca-js `getAllFriends` (chỉ lấy mã) nhưng KHÔNG BAO GIỜ trả mã ngoài danh sách hỏi, không ghi đĩa, không ghi log. Chốt chặn cố định: trong khung giờ; giữa hai lần đọc Zalo tối thiểu `BRIDGE_FRIEND_SYNC_MIN_SEC` (mặc định 600; trong khoảng đó trả từ bản đã đọc); tối đa `BRIDGE_FRIEND_SYNC_DAILY` (48) lần đọc mỗi ngày. Lỗi không làm cầu nối dừng.

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

Riêng lời mời kết bạn, Adh ghi dấu trên lời mời và dòng `DANG_GOI` trong cùng một câu lệnh PostgreSQL trước khi gọi cầu nối. Nếu app dừng trước khi câu lệnh commit, lời mời vẫn trong hàng chờ và nhịp sau xét lại các điều kiện gửi. Nếu đã commit mà chưa có kết quả, lời mời vẫn ở `UNKNOWN` để kiểm tra. Khi cầu nối xác nhận gửi thành công hoặc xác nhận chưa gửi, Adh lưu kết quả trên lời mời cùng lần cập nhật nhật ký gửi.

Trạng thái `NONE` chỉ cho biết hiện không có quan hệ bạn bè hoặc lời mời đang chờ. ADH lưu các thay đổi theo tài khoản, lời mời và mốc gửi, gồm mã lỗi đọc đã chuẩn hóa. Ca production ngày 26/11/2026 trả `Z114` cho mã Zalo lưu từ chiến dịch thử cũ. Mã tra lại từ cùng số khác mã cũ. Danh sách bạn bè xác nhận mã hiện tại đã kết bạn, còn endpoint đối chiếu trả `NO_INVITE` vì chưa có Tin 1 cho mã đó. `NONE`, `Z114` và `NO_INVITE` không kích hoạt gửi lại.

Chỉ lỗi kết nối chắc chắn chưa gửi (`BRIDGE_DOWN`, `BUSY`, `NOT_CONNECTED`) mới tiêu ngân sách thử lại lời mời. Sau lần đầu, ADH thử lại tối đa ba lần, cách nhau ít nhất 1, 5 và 15 phút. PostgreSQL lưu lịch và số lần qua restart. Mỗi lượt vẫn qua khung giờ, hạn mức, phê duyệt chiến dịch, chế độ thử và đúng tài khoản. Sau lần thất bại thứ tư, tab **Cần xử lý tay** hiển thị mã kết nối cuối cùng.

Khi còn một lượt gọi lời mời `DANG_GOI`, tab **Cần xử lý tay** chặn gửi lại. Sau ít nhất hai phút, người vận hành có thể kiểm tra Zalo rồi bấm **Đã xử lý**. ADH giữ kết quả `UNKNOWN`, đóng mục nhật ký và ghi ai đã đóng. Cầu dao vẫn cần mở riêng sau khi xem nhật ký.

### Nghiệm thu E2E lời mời kết bạn

1. Dùng mã khách thử đã được ghi sau Tin 1 trên đúng cầu nối. Đọc trạng thái bằng tài khoản gửi khi lời mời còn chờ.
2. Một lượt để bên gửi thu hồi và một lượt riêng để bên nhận từ chối. Ghi trạng thái trước và sau trên tab **Cần xử lý tay**. Nếu hai lượt cùng trả `NONE`, tiếp tục chuyển xử lý tay cho cả hai.
3. Với bridge giả hoặc staging cô lập, dừng app trước khi ghi xong dấu gửi và sau khi cầu nối nhận lệnh. Sau khởi động lại, lượt trước ghi được xét lại hàng chờ, lượt sau giữ `UNKNOWN`. Thử cho cầu nối trả hết giờ chờ, xác minh có thể đọc nhưng không gửi trên cả hai tài khoản. Thử thêm dừng tay và mất phiên để xác minh không giữ đường đọc.

| Tình huống | Adh làm |
|---|---|
| Không nối được cầu nối (từ chối kết nối) | `UNREACHABLE`: chưa chạm Zalo, không tính hạn mức, không dừng, thử lại nhịp sau |
| Hết giờ chờ lúc thêm người (kết quả không biết) | `SYSTEM_ERROR` mã `TIMEOUT`: dừng ngay, khách để người kiểm tra tay |
| Trả lời lạ, mã HTTP khác 200 lúc thêm người | `SYSTEM_ERROR`: dừng ngay |
| `PERSON_ERROR` | Ghi `FAILED` cho khách đó, không tự thử lại, hiện cho người xử lý |
