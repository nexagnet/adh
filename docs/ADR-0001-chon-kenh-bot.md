# ADR-0001: Chọn kênh cho bot trong nhóm Zalo

**Trạng thái:** **Kênh đã chọn: Zalo (chủ shop, 22/11/2026, D17). Phương án kỹ thuật cụ thể (P1, P2, P3 bên dưới) VẪN CHƯA CHỌN.** Chưa chạy spike nào, chưa kết luận nào được đánh dấu "đạt". Chọn "Zalo" loại bỏ các kênh ngoài Zalo (Messenger, Telegram, SMS) nhưng chưa nói dùng OA, Bot Platform hay tài khoản cá nhân riêng.
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

## Báo việc cho mẹ qua Zalo (thiết kế, chưa code; chủ shop chọn Zalo ngày 22/11/2026)

Chủ shop chọn Zalo cho cả hai việc: kênh của trợ lý trong nhóm và cách báo việc cho mẹ. Hiện việc ("Việc cần mẹ xử lý", `HandoffService`) chỉ hiện trên web, mẹ không mở thì bỏ sót.

**Đề xuất (chưa được chủ shop duyệt, vì khác với một đường gửi riêng):** trợ lý nhắc mẹ **ngay trong chính nhóm Zalo** nơi khách hỏi (ví dụ "Mẹ ơi, khách vừa hỏi giá, xin mẹ xem"). Lý do:
- Mẹ vốn là trưởng nhóm nên Zalo tự báo cho mẹ; **không cần thêm đường gửi tin riêng cho mẹ**.
- Trợ lý đằng nào cũng phải đăng tin vào nhóm để trả lời (mốc 1E, `BotChannel.send`); nhắc mẹ dùng đúng đường đó, **không thêm khả năng mới nào**. Không gửi hàng loạt, không gửi cho người ngoài nhóm.
- Bộ nối D16 **không có hàm gửi tin** và `ZaloGatewayShapeTest` khóa điều đó; một đường "gửi tin riêng cho mẹ" sẽ phải đổi ranh giới của ADR-0002 mục 4, nên cần quyết định riêng của chủ shop.

**Điểm cắm có sẵn:** `HandoffService.open(...)` trả `true` đúng lúc tạo việc mới (mỗi nhóm một việc đang mở, nên **không thể nhắc dồn dập**: chỉ nhắc khi mở việc mới, không nhắc lại). Khi có kênh trợ lý thật, chỗ gọi sau `open` là nơi gửi một câu nhắc qua `BotChannel`. Hiện **chưa viết giao diện hay lớp nào cho việc này** vì chưa có kênh gửi để cắm vào; viết trước chỉ thêm mã chết.

**Giới hạn đề xuất khi code:** nhắc tối đa một lần cho mỗi việc, chỉ trong nhóm của việc đó, chỉ khi `bot.enabled` bật và nhóm đó đã bật trợ lý, tôn trọng công tắc tắt khẩn cấp; web vẫn là nơi ghi nhận (mẹ bấm *Mẹ đã xem*). Nếu mẹ không ở trong nhóm (hiếm), không có đường dự phòng, vẫn là trang web.

**Chưa biết:** kênh nào trong P1–P3 cho phép trợ lý gắn thẻ (@) hay nhắn tên mẹ trong nhóm; phải thử ở spike P1-1, P3-1 hoặc P2-1 trước.
