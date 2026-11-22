# ADR-0001: Chọn kênh cho bot trong nhóm Zalo

**Trạng thái:** CHƯA QUYẾT. Chưa chạy spike nào, chưa kết luận nào được đánh dấu "đạt".
**Liên quan:** file 03 mục 2.2 (các phương án P1–P4) và mốc 1E; file 01 BR-11/BR-12, tiêu chí 9–10.

## Đã có trong code (không phụ thuộc kênh)

Lõi bot nằm ở `app/src/main/java/vn/shop/crm/bot`:

- `BotEngine.handle(IncomingMessage)`: nhận tin nhóm do **adapter kênh** chuyển tới, quyết định trả lời FAQ, chuyển người thật,
  hoặc im lặng. Mỗi tin xử lý đúng một lần (khóa duy nhất theo nhóm + mã tin), bỏ qua tin của chính bot, im lặng khi mẹ vừa trả
  lời, có công tắc tắt toàn hệ thống và theo nhóm, mặc định **chạy thử** (chỉ ghi "sẽ trả lời gì").
- `BotChannel`: giao diện gửi tin vào nhóm. Bản duy nhất hiện có là `NoopBotChannel` (không gửi gì).
- `AiProvider` + `MockAiProvider`: AI tùy chọn, chỉ nhận văn bản đã che SĐT/email/số dài; mặc định không đưa ra câu trả lời.
- "Việc cần mẹ xử lý" (`HandoffService`): OPEN → ACKNOWLEDGED → RESOLVED, hiện trên web và thẻ ở màn Tổng quan.

Adapter kênh (Zalo Bot, GMF hay `zca-js`) chỉ cần: (1) gọi `BotEngine.handle` cho mỗi tin nhóm với `SenderKind` đúng
(`CUSTOMER`, `OWNER` là mẹ, `BOT` là chính bot), (2) cài `BotChannel.send`.

## Spike cần làm (chủ cửa hàng chọn phương án, không dùng tài khoản của mẹ)

| # | Câu hỏi | Cách thử | Kết quả |
|---|---|---|---|
| P1-1 | OA tạo được GMF-10 test qua API và lấy `group_link`? | OA test + gói tối thiểu | CHƯA CHẠY |
| P1-2 | Mẹ làm phó nhóm được không? Có API duyệt thành viên? | Nhóm test | CHƯA CHẠY |
| P1-3 | Webhook `user_send_group_text` nhận đủ tin nhóm? | Nhóm test | CHƯA CHẠY |
| P1-4 | Giá tin OA → nhóm sau 31/12/2026 | Hỏi Zalo/xem bảng giá | CHƯA RÕ |
| P3-1 | Zalo Bot Platform thêm được vào nhóm cá nhân? Đọc tin nhóm không cần @mention? | Bot test | CHƯA CHẠY |
| P2-1 | `zca-js` (chỉ khi chủ cửa hàng chấp nhận rủi ro khóa tài khoản bằng văn bản) | Tài khoản phụ | CHƯA CHẠY |

## Quy tắc khi chọn

- Giữ đúng nghiệp vụ đã chốt: mẹ là trưởng nhóm cá nhân (D2). Nếu P1 (GMF) làm lệch BR-08/BR-11 (OA sở hữu nhóm, phê duyệt từng
  người) thì **chủ cửa hàng quyết**, không tự đổi nghiệp vụ.
- Không giả lập thao tác người dùng, không đổi dấu vân tay, không xoay vòng tài khoản để né phát hiện (D5).
- Khóa phiên bản thư viện không chính thức nếu dùng, tài khoản bot tách riêng, có công tắc tắt (đã có) và cách đăng nhập lại.
- Ghi kết quả từng dòng của bảng trên vào ADR này trước khi chọn.
