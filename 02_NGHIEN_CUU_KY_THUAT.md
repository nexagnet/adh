# NGHIÊN CỨU KỸ THUẬT & KẾ HOẠCH THỰC THI — FAMILY ZALO CRM

**Phiên bản:** 1.0 — tra cứu ngày 21/11/2026  
**Đối tượng:** Claude Code / lập trình viên.  
**Tài liệu nguồn nghiệp vụ:** `01_DAC_TA_NGHIEP_VU.md` — tài liệu này **không được tự ý thay thế** quyết định nghiệp vụ trong file đó.

> **Bản chất tài liệu:** Đánh giá khả thi và phương án triển khai. Các repo bên dưới được tham khảo từ trang dự án/tài liệu công khai; **chưa clone, chưa chạy, chưa kết nối với tài khoản thật của gia đình**. Tính năng chưa được kiểm thử phải coi là giả thuyết và xác minh bằng POC.

## 1. Kết luận kỹ thuật ngắn gọn

- **Có cần viết phần mềm riêng không? CÓ, nhưng chỉ một ứng dụng quản trị gọn** để gộp dữ liệu 2 hãng, phân loại khách, lập danh sách mời, phân phối nhóm và xem tình trạng. Repo Zalo không thay thế được nghiệp vụ riêng này.
- **Không cần tạo CRM hoàn chỉnh, n8n hay Chatwoot ngay lập tức.** Tập trung vào bài toán 500 khách đầu tiên và quyền thao tác của bố mẹ.
- Giai đoạn đầu **lấy Excel/CSV** từ Viettel Post và J&T Express; tích hợp API chính thức vào giai đoạn tiếp theo nếu tài khoản của cửa hàng được cấp quyền.
- Zalo khách: dùng **link tham gia nhóm** (tính năng chính thức của Zalo). Để thử tự động đọc/gửi tin bằng **tài khoản cá nhân của bot**, dùng `zca-js` (không chính thức) trong một dịch vụ biệt lập.
- Tách tài khoản **mẹ** và **bot**. Tránh đăng nhập tài khoản mẹ vào trình lắng nghe tự động; mẹ vẫn vận hành Zalo điện thoại/PC như bình thường.
- Lời mời SMS không tự động được coi là tin CSKH chỉ vì không có quảng cáo giá. Cần xác định cơ sở liên hệ hợp lệ, phân loại mẫu tin với nhà cung cấp và ghi nhận quyền từ chối trước khi gửi hàng loạt.
- Dùng **một VPS, một cơ sở dữ liệu và các container nhỏ**, có sao lưu, đăng nhập admin và chế độ thử nghiệm không gửi tin thật.

## 2. Khảo sát nguồn giao vận

### 2.1 Viettel Post

**Nguồn chính thức:**
- Trang OpenAPI: https://partner2.viettelpost.vn/
- Hướng dẫn tích hợp: https://partner2.viettelpost.vn/document/detail-instructions
- Môi trường/địa chỉ kết nối: https://partner2.viettelpost.vn/document/environment-parameter

**Đánh giá:** Có hệ thống API đối tác và cơ chế xác thực, nhưng **không mặc định tài khoản bán hàng của gia đình đã được cấp tất cả quyền API**. Theo hướng dẫn tích hợp, đối tác cần làm việc với đơn vị Viettel Post khi xin quyền. Chưa xác minh được endpoint đầy đủ để xuất tất cả đơn đúng tài khoản của gia đình. Do đó:

1. Yêu cầu chủ cửa hàng xuất tệp mẫu Excel/CSV từ giao diện mình đang dùng, **ẩn dữ liệu cá nhân trước khi chia sẻ để phát triển**.
2. Kiểm tra cột, phân biệt mã vận đơn với mã đơn bán hàng và ý nghĩa trạng thái.
3. Viết **importer cấu hình ánh xạ cột**, không hardcode dữ liệu demo là chuẩn cố định.
4. Nếu sau này có API được cấp, làm adapter thay thế cho importer, không sửa logic phân loại.

### 2.2 J&T Express Việt Nam

**Nguồn chính thức:**
- Cổng J&T Express: https://jtexpress.vn/vi
- Hướng dẫn cửa hàng dùng app/website và `vip.jtexpress.vn`: https://jtexpress.vn/vi/news-detail/cau-hoi-thuong-gap-khi-chu-shop-len-don-qua-app-va-website
- Hướng dẫn liên quan đến tạo đơn và trạng thái: https://jntexpress.vn/bai-viet/huong-dan-len-don-app-va-website-cho-khach-moi

**Đánh giá:** J&T có giao diện quản lý đơn; thông tin/API đối tác phụ thuộc dịch vụ và tài khoản. **Chưa có xác nhận chính thức về quyền API lịch sử đơn của chính shop** và không nên giả định có endpoint công khai cho việc này. Nên dùng file xuất trước, nhờ J&T hỗ trợ nếu giao diện không có quyền xuất danh sách.

**Lưu ý:** Hai hãng có mã trạng thái khác nhau; cần giữ `raw_status` và bảng chuyển đổi độc lập. Không dựa vào việc nhìn thấy chữ “giao” trong một cột để kết luận đã giao thành công.

### 2.3 Phương án đối với hai nhà vận chuyển

| Phương án | Chi phí/độ phức tạp | Chọn khi nào |
|---|---|---|
| Import Excel/CSV có màn hình xem trước | Rất thấp | **V1 mặc định** |
| Upload định kỳ và hợp nhất lại | Thấp | Giai đoạn có thêm đơn mới |
| API Viettel Post/J&T | Trung bình, phụ thuộc quyền đối tác | Sau khi có thông tin đăng nhập, API được cấp và xác thực thành công |
| Scraping tài khoản giao vận | Mong manh, dễ hỏng, xử lý dữ liệu nhạy cảm | **Không ưu tiên**, chỉ cân nhắc nếu có quyền và không có cách được hỗ trợ |

## 3. Khảo sát Zalo cá nhân và dự án GitHub

### 3.1 zca-js — thư viện chọn để làm POC

- **Repo:** https://github.com/RFS-ADRENO/zca-js
- **Loại:** Thư viện JavaScript/TypeScript dùng **API Zalo cá nhân không chính thức**, hoạt động dựa trên mô phỏng tương tác Zalo Web.
- **License công bố:** MIT tại repo.
- **Repo công bố ví dụ:** `loginQR`, `api.listener.on('message', ...)`, phân biệt `ThreadType.Group`, `api.sendMessage(...)`; có phần thao tác nhóm và thông tin nhóm trong mã/tài liệu API.
- **Giới hạn quan trọng từ README:** **mỗi tài khoản chỉ một web listener hoạt động tại một thời điểm**; mở Zalo trên trình duyệt khác khi listener đang chạy có thể khiến listener ngừng. Thư viện cũng cảnh báo nguy cơ tài khoản bị hạn chế và có issue về API đổi/404.
- **Đề xuất:** chạy zca-js **chỉ cho tài khoản bot** trên server. Mẹ tiếp tục dùng Zalo cá nhân của mẹ bình thường. Không dùng browser automation song song cho cùng tài khoản bot.
- **Chưa được coi là chắc chắn:** tự tạo nhóm hàng loạt, lấy link nhóm, đếm đầy đủ thành viên, nhận sự kiện join/leave, phục hồi phiên sau restart, tự gửi tin ổn định. **Từng mục cần POC với phiên bản khóa chặt** trước khi kích hoạt tính năng liên quan.

**POC bắt buộc:**

1. Đăng nhập bot bằng QR từ thiết bị hợp lệ, giữ phiên và restart dịch vụ.
2. Cho bot tham gia **1 nhóm test** cùng mẹ và vài tài khoản test; kiểm tra bot nhận tin nhóm và gửi trả lời, **không phản hồi tin do chính bot gửi**.
3. Thử `getGroupInfo` / danh sách thành viên / sự kiện join-leave (nếu phiên bản thật hỗ trợ), đối chiếu với Zalo thật.
4. Thử gửi tin khi mẹ đang dùng Zalo điện thoại/PC của mẹ; xác nhận không bị ảnh hưởng.
5. Giả lập mất kết nối, bot không biết đáp án, tắt bot; bảo đảm mẹ vẫn dùng Zalo được.
6. Thử link nhóm bị vô hiệu, bị đầy, đã hết quyền và nhóm bị xóa.

**Nguyên tắc:** Không kết luận một API hoạt động chỉ vì tên hàm tồn tại trong README hoặc bài blog.

### 3.2 zca-zalo-service — CHỈ THAM KHẢO KIẾN TRÚC

- **Repo:** https://github.com/leanhluong/zca-zalo-service
- Có mã ví dụ cho mô hình `Zalo ⇄ service ⇄ hệ thống chính`, REST API, webhook, đăng nhập QR và Docker.
- **Điểm quyết định:** README ghi mục **Giấy phép: “Private.”**, không thấy giấy phép mở cho phép tái sử dụng. Repo còn ít lịch sử hoạt động. **Không copy/đóng gói/chạy sản phẩm dựa vào mã repo này khi chưa có quyền tác giả cho phép**. Có thể học ý tưởng ranh giới API/service ở mức khái niệm.

### 3.3 n8n / Chatwoot — CHƯA CẦN Ở V1

| Repo | Công dụng | Quyết định |
|---|---|---|
| https://github.com/n8n-io/n8n | Kết nối quy trình, lịch chạy, webhook, AI workflow; self-host, giấy phép **fair-code** | Hữu ích khi có nhiều workflow; **chưa cần** cho 500 khách và một bot. |
| https://github.com/chatwoot/chatwoot | Hộp thư chăm sóc khách tập trung, phân công nhân viên, quản trị hội thoại | Cân nhắc **V2**; không có sẵn kết nối Zalo cá nhân chính thức trong bài toán này. |
| https://github.com/RFS-ADRENO/zca-js | Zalo bot cá nhân, sự kiện nhóm, gửi/nhận tin | **POC V1**. |

Nếu phát triển V2 theo hướng tự xây giao diện nhân viên đơn giản thì không bắt buộc tích hợp Chatwoot. Chọn 1 cách, tránh hệ thống chồng chéo.

### 3.4 Link nhóm: thành phần chính thức của Zalo

- **Hướng dẫn chính thức:** https://help.zalo.me/huong-dan/chuyen-muc/tro-chuyen-nhom/moi-ban-tham-gia-nhom-chat/
- Trưởng nhóm có thể **sao chép link hoặc mã QR** để mời người chưa kết bạn; khách quyết định tham gia. Có thể hủy link.
- Dữ liệu `click URL`, `Zalo join`, `Zalo user ID`, `SMS phone` là **4 nguồn sự kiện khác nhau**. Không tự đồng nhất chúng.
- Nếu chưa xác minh lấy được link và số lượng thành viên tự động từ zca-js: **mẹ/admin copy link nhóm vào dashboard thủ công**, nhập số lượng khách ước lượng. Sau đó nâng cấp API khi POC thành công.

## 4. SMS, dịch vụ gửi và nghĩa vụ dữ liệu

### 4.1 Dịch vụ SMS tham khảo

- **eSMS developer:** https://developers.esms.vn/esms-api/ham-gui-tin/tin-nhan-sms-otp-cskh
- **API tài liệu chi tiết và trạng thái gửi:** https://esms.vn/SMSApi/ApiDetail
- Có khái niệm Brandname, template, API key, `SMSID` và tra trạng thái; **giá, điều kiện đăng ký và loại nội dung được chấp thuận cần kiểm tra theo tài khoản thực tế**.
- Cần hỏi rõ eSMS/nhà cung cấp: mẫu **mời vào nhóm Zalo của cửa hàng** được phân loại là tin nào, có cho phép liên kết URL không, cần Brandname/đăng ký mẫu không, tần suất hạn chế ra sao.
- Thiết kế interface `SmsProvider` có các hàm `sendInvitation`, `getDeliveryStatus`; hỗ trợ `MockSmsProvider` để demo không tốn tiền.

### 4.2 Điều kiện sử dụng số điện thoại khách cũ

- Luật Bảo vệ dữ liệu cá nhân **91/2025/QH15**, hiệu lực 01/01/2026: https://congbao.chinhphu.vn/van-ban/luat-so-91-2025-qh15-45578.htm
- Nghị định **356/2025/NĐ-CP**, hiệu lực 01/01/2026: https://congbao.chinhphu.vn/van-ban/nghi-dinh-so-356-2025-nd-cp-468371.htm
- Nghị định **91/2020/NĐ-CP** về chống SMS/cuộc gọi rác (xem văn bản và thay đổi liên quan): https://cspl.mic.gov.vn/Pages/TinTuc/tinchitiet.aspx?tintucid=138202

**Thiết kế tối thiểu:**

- Không lấy việc khách cung cấp số để giao hàng làm bằng chứng mặc định họ đồng ý nhận tin mời tham gia nhóm. **SMS mời tham gia cộng đồng cửa hàng có thể bị phân loại là thông điệp tiếp thị**, dù không nêu giá sản phẩm.
- Trước khi bật gửi thật: người dùng xác nhận căn cứ pháp lý/quyền liên hệ; `consent_status = UNKNOWN | OPTED_IN | OPTED_OUT`, nguồn và thời điểm xác nhận. Mặc định `UNKNOWN`, **không gửi tự động cho UNKNOWN**.
- Ghi nhận yêu cầu từ chối và chặn gửi tiếp; không gửi lại nhiều lần để ép khách tham gia.
- Chỉ thu thập trường cần dùng; phân quyền; sao lưu mã hóa; giới hạn retention; xuất/xóa dữ liệu theo yêu cầu hợp lệ. Địa chỉ và SĐT không được phơi ra trong log AI, URL hoặc chat nhóm.
- Trước rollout thực tế, kiểm tra văn bản pháp luật và hướng dẫn của nhà cung cấp SMS, vì đây không phải tư vấn pháp lý cuối cùng.

## 5. Kiến trúc phù hợp với một VPS và thế mạnh Java Backend

**Đề xuất mặc định: Modular Monolith**, không microservices cho 500 khách.

```text
                         ADMIN WEB (bạn; giai đoạn sau nhân viên)
                                      │ HTTPS
                               [Reverse proxy]
                                      │
                              [Spring Boot App]
                         ┌────────────┼──────────────────┐
                         │            │                  │
                  [PostgreSQL]  [SMS provider]      [AI provider API]
                         │                               ▲
                         └── Customer/Order/Group         │
                                         │                │
                                         ▼                │
                                  [Zalo bot bridge] ──────┘
                                  Node.js + zca-js
                                         │
                                  TÀI KHOẢN BOT
                                         │
                                    Các nhóm Zalo
                                         │
                          TÀI KHOẢN MẸ dùng Zalo thật
```

**Stack đề xuất:**

| Thành phần | Công nghệ | Vì sao |
|---|---|---|
| Backend nghiệp vụ | **Java 21 + Spring Boot** (bản ổn định tương thích tại lúc tạo repo) | Phù hợp năng lực Java BE, dữ liệu và logic có quy tắc |
| UI quản trị V1 | **Thymeleaf + HTMX** hoặc web UI nhẹ tương đương | Ít mã frontend, nhanh làm các bảng upload/duyệt |
| Database | **PostgreSQL** | Ràng buộc khóa, giao dịch và báo cáo đơn |
| Bridge Zalo | **Node.js TypeScript + zca-js** | Thư viện Zalo ở hệ JS; cô lập lỗi và phiên login |
| Gửi SMS | Adapter đến eSMS hoặc dịch vụ hợp lệ có API | Thay được nhà cung cấp |
| AI bot | LLM API tùy chọn + rules; có `MockAiProvider` | Thử nghiệm chi phí thấp, giảm trả lời bịa |
| Triển khai | Docker Compose + HTTPS reverse proxy + backup | Một VPS, đơn giản và đủ cho hiện tại |

**Nếu dùng toàn Node.js/TypeScript** để giảm số ngôn ngữ thì cũng khả thi; tuy nhiên vì người viết hệ thống quen Java Backend, phương án Spring Boot + sidecar Node giới hạn phạm vi Node chỉ còn Zalo là hợp lý.

**Không đề xuất trong V1:** Kafka, Kubernetes, Redis bắt buộc, event bus phức tạp, nhiều database, tự xây omnichannel agent, giao diện React/Next.js lớn nếu chưa cần.

### 5.1 Ranh giới các module

1. `imports`: nhận file → xem trước → mapping cột → validate → lưu raw + kết quả.
2. `shipping`: quản lý 2 nguồn, vận đơn, trạng thái, mã đơn gốc, hợp nhất lần mua.
3. `customers`: số điện thoại chuẩn hóa, tên, địa chỉ gần nhất, phân loại.
4. `consents`: quyền liên hệ, chống gửi trùng, yêu cầu từ chối.
5. `invitations`: chiến dịch mời, link riêng/token, trạng thái SMS và click.
6. `zalo_groups`: danh sách nhóm, link Zalo, số khách dự kiến/thực tế, trạng thái mở/đóng.
7. `bot`: FAQ, nhóm được bật bot, sự kiện tin đến, chuyển người thật; có nút pause.
8. `reports`: số khách phân loại, tình hình lời mời/nhóm, lỗi đồng bộ.

**Quan trọng:** bridge Zalo chỉ là adapter và không giữ logic phân loại khách; không viết logic cốt lõi rải rác trong workflow n8n hoặc event handlers.

## 6. Mô hình dữ liệu gợi ý (không phải DB schema bắt buộc)

| Bảng | Trường chính | Ghi chú |
|---|---|---|
| `import_batches` | `id`, `source`, `filename`, `sha256`, `imported_at`, `status`, `error_count` | Idempotent; lưu dấu vết lần nhập. |
| `raw_shipments` | `id`, `source`, `tracking_no`, `raw_status`, `raw_payload`, `batch_id` | Lưu có kiểm soát, bảo vệ dữ liệu gốc. |
| `customers` | `id`, `phone_normalized`, `name`, `address`, `last_order_at` | `UNIQUE(phone_normalized)` nếu số hợp lệ. |
| `orders` | `id`, `source`, `external_order_id`, `purchase_key`, `customer_id`, `status`, `delivered_at`, `returned_at` | Đặt ràng buộc chống trùng. |
| `shipments` | `id`, `order_id`, `carrier`, `tracking_no`, `raw_status`, `canonical_status` | Cho phép một đơn có nhiều kiện. |
| `customer_segments` | `customer_id`, `successful_purchase_count`, `segment`, `calculated_at` | Cache/derived; truy được nguồn tính. |
| `contact_permissions` | `customer_id`, `purpose`, `status`, `source`, `recorded_at` | Có trạng thái chưa rõ/đồng ý/từ chối. |
| `zalo_groups` | `id`, `zalo_group_id`, `invite_link`, `target_customer_count`, `actual_customer_count`, `status` | Mục tiêu ~5 khách; không tính mẹ, bot. |
| `invitations` | `id`, `customer_id`, `token_hash`, `group_id_nullable`, `status`, `sms_provider_id`, `clicked_at` | Mời riêng nhưng không kết luận Zalo ID từ click. |
| `bot_events` | `id`, `group_id`, `message_id`, `event_type`, `confidence`, `human_handoff_status` | Chặn xử lý trùng sự kiện. |
| `zalo_contact_links` | `customer_id`, `zalo_user_id`, `verified_by_order_at` | **Tùy chọn**, chỉ điền sau khi mua lại/có căn cứ. |
| `audit_logs` | `actor`, `action`, `entity_type`, `entity_id`, `timestamp` | Tránh ghi thẳng PII và nội dung tin nhắn vào log. |

**Lưu ý dữ liệu:** Không gộp `shipping tracking_no` với `purchase_key`. Nếu thiếu `external_order_id`, đánh dấu xác minh, không tự đoán rằng hai kiện cùng ngày chắc là một lần mua.

### 6.1 Trạng thái gợi ý

- `canonical_status`: `CREATED`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, `RETURNING`, `RETURNED`, `UNKNOWN`.
- `segment`: `NO_SUCCESSFUL_PURCHASE`, `ONE_TIME`, `REPEAT`.
- `invitation.status`: `DRAFT`, `PENDING_PERMISSION`, `READY`, `SENDING`, `SENT`, `FAILED`, `CLICKED`, `EXPIRED`, `CANCELLED`, `OPTED_OUT`.
- `zalo_group.status`: `PREPARING`, `OPEN`, `NEAR_TARGET`, `PAUSED`, `CLOSED`.
- SMS `SENT`/`DELIVERED`/`CLICKED`/**`JOINED`** là các khái niệm khác nhau; chỉ có `JOINED` nếu bot hoặc vận hành cung cấp dữ liệu thực tế.

## 7. Thuật toán chia nhóm khoảng năm khách, không cần map Zalo

**Mục tiêu:** Ưu tiên nhóm ít khách; không ép đúng 5; tránh gửi quá nhiều lời mời vào cùng một nhóm trước khi thấy kết quả.

### 7.1 Thuật toán V1 đề xuất

1. Mỗi nhóm có `target_customers = 5` và `actual_customers` (nếu biết được). Không tính 2 tài khoản cửa hàng.
2. Khởi tạo **một số nhóm nhỏ** (ví dụ 2–3 nhóm test), không tạo trước 100 nhóm.
3. Với người đủ điều kiện mời, sinh **token URL riêng** `https://shop-domain.vn/i/<token>`; bản thân token không lộ SĐT.
4. Khi người nhận mở URL, hệ thống chọn **nhóm đang mở có số khách và số lời mời đang chờ thấp**, cấp đường dẫn đến Zalo. Nếu chưa có nhóm thì thông báo “liên hệ cửa hàng”, không redirect sai.
5. Duy trì `pending_invites` trong thời gian quan sát; xếp hạng nhóm theo `actual_customers + pending_invites * estimated_accept_rate`. Khi thiếu dữ liệu, dùng tỷ lệ giả định để điều phối nhưng **không coi kết quả là số người thật**.
6. Sau mỗi đợt, lấy số khách thực tế bằng bot (nếu POC đạt) hoặc mẹ/admin cập nhật thủ công; điều chỉnh số lượt mời đợt sau.
7. Khi nhóm gần đích, có thể tạm dừng cấp link mới; link đã cấp có thể vẫn được chia sẻ, dẫn đến lệch một ít và điều này **được nghiệp vụ chấp nhận**.
8. Với lượng khách ít, **ưu tiên kiểm soát thủ công** hơn tối ưu thuật toán quá mức.

**Lưu ý kỹ thuật quan trọng:** Redirect động chỉ quyết định *link Zalo nào người dùng thấy tại thời điểm bấm*, không thể kiểm soát tuyệt đối ai tham gia sau đó. Tính năng khóa/hủy link phải có thao tác của trưởng nhóm hoặc API đã POC đạt. Không được tuyên bố “nhóm chỉ đúng 5 người” hay “click = tham gia”.

### 7.2 Nếu không dùng landing page

Có thể gửi **trực tiếp link nhóm Zalo** theo những lô mời nhỏ, nhập số khách mỗi nhóm thủ công. Cách này ít thành phần hơn nhưng **không có theo dõi click theo từng số** và khó điều phối lại link đã phát. Là phương án dự phòng khi cần ra mắt thật nhanh.

## 8. Thiết kế bot AI và chuyển người thật

### 8.1 Quy trình tối thiểu

```text
Zalo group message ─> bridge ZCA ─> kiểm tra trùng/self-message
                                   │
                                   ├─> FAQ rõ ràng, dữ liệu chắc -> bot trả lời
                                   │
                                   ├─> muốn đặt hàng -> tạo thông báo cho mẹ
                                   │
                                   ├─> khiếu nại/bảo hành -> thông báo mẹ
                                   │
                                   └─> không chắc/không hiểu -> chuyển người thật
```

- **Ưu tiên rules + knowledge base nhỏ** trước khi gọi LLM cho mọi tin nhắn (giảm chi phí và nguy cơ bịa).
- Chỉ trả lời khi có tín hiệu là câu hỏi hướng tới cửa hàng; không chen ngang mọi cuộc trao đổi của khách trong nhóm.
- Chặn vòng lặp phản hồi bằng `isSelf`, `sender_id` và idempotency `message_id`.
- Khi cần người thật: báo nhóm, trích phần nội dung cần xử lý, trạng thái `OPEN → ACKNOWLEDGED → RESOLVED`. Ở V1 mẹ nhận báo và vào Zalo **thật**.
- Có `bot_enabled` theo nhóm và toàn hệ thống; hỗ trợ `DRY_RUN` để bot chỉ ghi “nếu chạy sẽ trả lời thế này” mà không gửi thật.
- Không tự nhận địa chỉ, SĐT hoặc xác nhận số lượng/giá chốt trong nhóm công khai; khuyến khích chuyển sang trao đổi riêng nếu phải xử lý thông tin cá nhân.

### 8.2 Đầu vào cho AI

- Bảng sản phẩm áo điều hòa/pin/quạt được kiểm chứng và giá được người bán duyệt (nếu muốn AI báo giá).
- Chính sách bảo hành, cách dùng, số hotline thật của cửa hàng.
- Các câu hỏi thường gặp và câu trả lời mẫu.
- Thông tin **không có** trong kho dữ liệu phải được xử lý thành “để cửa hàng kiểm tra”, không đoán.

### 8.3 LLM/model

- Claude Code là công cụ **viết phần mềm**, không phải backend AI tự chạy vĩnh viễn trên server. Muốn bot thông minh chạy 24/7 phải có **LLM API** được cấu hình hoặc mô hình chạy riêng; phí/giới hạn phải tính riêng.
- Ban đầu dùng `MockAiProvider`, rồi mới thử provider do chủ cửa hàng chọn, có giới hạn chi tiêu, timeout và fallback.

## 9. Thiết kế an toàn tối thiểu cho server

- Docker Compose: `app`, `postgres`, `zalo-bridge` (khi POC đạt), `reverse-proxy`; chưa cần n8n/Chatwoot/Redis.
- HTTPS cho admin và landing page; bridge chỉ giao tiếp nội bộ, **không mở API zca ra Internet**.
- Thông tin tài khoản, session/cookie Zalo, SMS API key, AI key để trong secret/environment ngoài git; dùng volume hạn chế quyền.
- Admin có đăng nhập và quyền tối thiểu; **không public danh sách SĐT, địa chỉ, lịch sử đơn**.
- Chống lỗi import: giới hạn file, validate đầu vào, reject file độc, preview cột, báo lỗi dòng.
- Sao lưu DB tự động, thử khôi phục, theo dõi dung lượng; không đưa bản sao dữ liệu thật vào repo công khai.
- Có hàng đợi gửi SMS có hạn mức, idempotency, nút dừng chiến dịch, lịch sử gửi. **Mặc định tắt gửi thật** khi khởi tạo dự án.
- Theo dõi bridge health, mất phiên, bot không nhận tin; có cơ chế đăng nhập lại bằng QR của tài khoản bot và thông báo cho chủ cửa hàng.
- Chú trọng rủi ro **không chỉ bị khóa tài khoản**: cập nhật Zalo có thể làm API thay đổi, mất kết nối hoặc đọc thiếu tin; thiết kế fallback thủ công.

## 10. Trình tự thực thi cho Claude Code — có cổng nghiệm thu

### Mốc 0 — Thu thập mẫu và xác minh đầu vào

**Cần chủ cửa hàng cung cấp:** mỗi hãng 1 tệp Excel/CSV **đã ẩn thông tin cá nhân**, ảnh màn hình mô tả các trạng thái nếu cần, quyền truy cập server, quyết định cách liên hệ hợp lệ. Claude không được đòi mật khẩu/OTP trong chat và không lấy dữ liệu tài khoản ngoài phạm vi đã cho phép.

**Hoàn thành khi:** đọc được danh sách cột, hiểu ý nghĩa trạng thái, nhận diện thiếu mã đơn bán hàng và báo tác động của trường thiếu.

### Mốc 1 — Import và phân loại (PHẢI LÀM ĐẦU TIÊN)

- Dựng Spring Boot app + Postgres + web admin tối thiểu.
- Upload file Viettel Post/J&T; chọn mapping cột; preview và lỗi từng dòng; import idempotent.
- Chuẩn hóa SĐT, gộp 2 nguồn, phân loại `ONE_TIME`/`REPEAT`; xem nguồn tạo ra mỗi lần mua.
- Xuất CSV hai danh sách; tài khoản admin đăng nhập được.

**Kiểm thử:** 1 lần Viettel + 1 lần J&T cùng SĐT = repeat; nhập lại file không tăng; đơn hủy/hoàn không tính; một đơn tách kiện = một lần nếu có ID đơn bán hàng; dữ liệu thiếu không tự phỏng đoán.

### Mốc 2 — Quản lý nhóm và lời mời KHÔNG GỬI THẬT

- CRUD nhóm, link Zalo, mục tiêu ~5 khách, số khách hiện có.
- Chọn khách đủ điều kiện liên hệ; tạo chiến dịch ở `DRY_RUN`, theo dõi lời mời và click token; thử chia nhóm.
- Tạo 2–3 nhóm test qua Zalo thật nếu chủ cửa hàng tự nguyện và rõ người tham gia test; ưu tiên **nhập link thủ công** ở mốc này.

**Kiểm thử:** Không cần ghép Zalo ID ↔ phone; không nhầm click thành join; không SMS cho `UNKNOWN`/`OPTED_OUT`; không vượt sang tập `REPEAT`.

### Mốc 3 — SMS thật (CHỈ SAU KHI ĐỦ ĐIỀU KIỆN)

- Cấu hình SMS provider, mẫu được nhà cung cấp xác nhận loại tin, quyền liên hệ, số tiền dự kiến.
- Gửi thử một nhóm nhỏ **khách đã đồng ý**, xác minh delivery, click, opt-out và báo cáo chi phí.
- Không phát tán hàng loạt 500 số trong một lệnh mặc định.

### Mốc 4 — Bot Zalo cá nhân (POC TRƯỚC, SAU ĐÓ TÍCH HỢP)

- Trong `zalo-bridge` Node.js, thử `zca-js` phiên bản cố định.
- Đăng nhập **tài khoản bot** (không đăng nhập tài khoản mẹ); nhận/gửi nhóm test; kiểm tra member info và sự kiện join/leave nếu có.
- Gắn FAQ/AI router, chỉ bật với nhóm test, sau đó mới bật từng nhóm thật.
- Thông báo mẹ khi nhận diện có nhu cầu mua hoặc yêu cầu hỗ trợ.

**Kiểm thử:** Bot không tự trả lời chính mình, không tự chốt đơn, ngừng được ngay và không ảnh hưởng mẹ dùng Zalo thật.

### Mốc 5 — Hoàn thiện V1 và chuyển giao

- Docker Compose deploy một VPS, HTTPS, backup/restore, logs đã che PII.
- README chạy một lệnh, `.env.example` không có secrets, tài liệu nhập file theo 2 hãng, hướng dẫn bố mẹ quản lý nhóm/link và cách tắt bot.
- Dashboard đủ dùng: tổng quan hai tập khách, nhóm Zalo, lời mời và thông báo AI.
- Chỉ sau khi V1 ổn định mới đề xuất V2 nhân viên.

### Mốc V2 — Chỉ khi tuyển nhân viên đầu tiên

- Website inbox theo **nhóm**, nhân viên được gán nhóm, AI chuyển tiếp nhu cầu có người xử lý.
- Tài khoản nhân viên; phân quyền; audit log; ngăn nhiều người xử lý một nhiệm vụ.
- Làm POC riêng chuyện nhân viên gửi tin qua **tài khoản nào**; chưa chốt mặt kinh doanh nên không tự chọn thay người dùng.
- Có thể đánh giá Chatwoot lúc này, không bắt buộc tích hợp.

## 11. Bảng tiêu chí kiểm chứng công nghệ (không được đánh dấu đạt khi chưa chạy)

| Mã | Câu hỏi POC | Kết quả hiện tại | Lối thoát nếu không đạt |
|---|---|---|---|
| POC-01 | Viettel Post/J&T xuất được file lịch sử có SĐT, trạng thái? | **Chưa kiểm chứng bằng tài khoản shop** | Xin export từ bộ phận hỗ trợ; import thủ công. |
| POC-02 | Có thể phân biệt 1 đơn bán hàng ↔ nhiều vận đơn? | **Phụ thuộc cột nguồn** | Cờ cần đối chiếu, không tự gộp. |
| POC-03 | Tài khoản bot QR login zca-js trên server? | Repo có hướng dẫn, **chưa chạy** | Tạm tắt bot, mẹ trả lời tay. |
| POC-04 | Bot đọc/gửi đúng nhóm, không xung đột với mẹ? | Repo có ví dụ, **chưa chạy thực tế** | Bot chạy nhóm test; ngừng nếu lỗi. |
| POC-05 | Lấy thành viên/link/nhận join-leave ổn định? | **Chưa chứng minh** | Nhập link & số khách thủ công. |
| POC-06 | SMS provider cho phép nội dung mời Zalo, thống kê delivery? | Có API tham khảo, **chưa có tài khoản/mẫu duyệt** | Chuẩn bị danh sách & mời thủ công cho khách đã đồng ý. |
| POC-07 | Redirect `/i/<token>` mở Zalo trên máy khách lớn tuổi? | **Chưa kiểm chứng trên điện thoại thực** | Gửi trực tiếp link Zalo, hướng dẫn bằng lời. |
| POC-08 | AI nhận diện nhu cầu mua mà không báo trùng/bịa thông tin? | **Chưa kiểm chứng** | Rules/FAQ + thông báo người thật, dry-run. |

## 12. Các rủi ro và nguyên tắc ra quyết định

| Rủi ro | Mức quan trọng | Cách xử lý tối thiểu |
|---|---|---|
| Gửi tin sai phạm vi đồng ý/không hợp lệ | Cao | Mặc định không gửi, xác minh quyền và loại tin. |
| Import sai → phân loại sai | Cao | Giữ bản gốc, preview, trạng thái rõ, test bằng dữ liệu giả. |
| Bị lộ SĐT/địa chỉ | Cao | Mã hóa đường truyền, phân quyền, ẩn log, kiểm soát export. |
| API Zalo cá nhân đổi/mất phiên | Cao | Bridge tách biệt, nút pause, mẹ tự dùng Zalo. |
| AI trả lời sai hoặc chốt nhầm | Cao | FAQ xác thực, handoff, không cho tự chốt. |
| Không biết chính xác ai vào nhóm | **Được chấp nhận bởi nghiệp vụ** | Không map khi mới vào; đo số nhóm qua cập nhật tổng hợp. |
| Nhóm vượt hoặc thiếu 5 khách | **Được chấp nhận** | Thuật toán theo đợt, cập nhật số thực tế, dừng mời khi gần mục tiêu. |
| Chi phí quá cao so với số khách ít | Trung bình | Một server, không CRM/n8n lớn, đo chi phí SMS + LLM. |

## 13. Nguyên tắc triển khai dự án và bàn giao

- Khởi tạo mono-repo gọn: `app/` Spring Boot; `zalo-bridge/` Node.js; `infra/` Compose; `docs/` có hai tài liệu nguồn; test dùng fixture không chứa PII.
- Mỗi mốc có test tự động, lệnh khởi động, cách nhập file mẫu và màn hình demo.
- Không tự ý gọi API thật, đăng nhập Zalo thật hoặc gửi SMS thật khi chạy setup/CI; môi trường `DEV` luôn `DRY_RUN`.
- Khi repo upstream đổi hoặc license không rõ, ghi lại phiên bản, kiểm tra license và security trước khi nâng cấp.
- Tự động hóa phải giảm thao tác cho bố mẹ; nếu làm UI khó dùng hơn, ưu tiên một thao tác thủ công trước khi đầu tư phức tạp.
- Không thêm năng lực 1688, sourcing, kho lớn, tích hợp TikTok hay tổng đài nếu chủ cửa hàng không yêu cầu lại.

## 14. Tài liệu kiểm chứng / nguồn tham khảo

**Nguồn chính thức / repo gốc** (kiểm tra 21/11/2026; có thể thay đổi):

1. Zalo Help — mời bằng QR/link, hủy link: https://help.zalo.me/huong-dan/chuyen-muc/tro-chuyen-nhom/moi-ban-tham-gia-nhom-chat/
2. `zca-js` — README, license MIT, giới hạn listener, rủi ro API không chính thức: https://github.com/RFS-ADRENO/zca-js
3. `zca-js` — issues có trường hợp API lịch sử nhóm lỗi 404: https://github.com/RFS-ADRENO/zca-js/issues/367
4. `zca-zalo-service` — kiến trúc REST/webhook và ghi “License: Private”: https://github.com/leanhluong/zca-zalo-service
5. Viettel Post OpenAPI & hướng dẫn đối tác: https://partner2.viettelpost.vn/document/detail-instructions
6. J&T Express — hệ thống quản lý tài khoản: https://jtexpress.vn/vi/news-detail/cau-hoi-thuong-gap-khi-chu-shop-len-don-qua-app-va-website
7. eSMS API: https://esms.vn/SMSApi/ApiDetail
8. n8n (fair-code, tùy chọn): https://github.com/n8n-io/n8n
9. Chatwoot (tùy chọn cho V2): https://github.com/chatwoot/chatwoot
10. Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15: https://congbao.chinhphu.vn/van-ban/luat-so-91-2025-qh15-45578.htm
11. Nghị định 356/2025/NĐ-CP: https://congbao.chinhphu.vn/van-ban/nghi-dinh-so-356-2025-nd-cp-468371.htm
12. Nghị định 91/2020/NĐ-CP về chống tin nhắn rác: https://cspl.mic.gov.vn/Pages/TinTuc/tinchitiet.aspx?tintucid=138202

## 15. Lời nhắc dành cho Claude Code (đọc khi bắt đầu)

> Hãy đọc `01_DAC_TA_NGHIEP_VU.md` trước. Đây là nguồn sự thật về phạm vi sản phẩm. Đừng xây toàn hệ thống một lần. Trước tiên đề xuất cấu trúc repo gọn, danh sách thông tin/tệp còn thiếu và kế hoạch mốc 0–1. Kiểm thử bằng dữ liệu giả. Bước đầu tiên là **import hai nguồn Viettel Post + J&T và phân loại khách chính xác**; không tự đăng nhập hoặc gửi tin nhắn qua bất kỳ tài khoản nào. Về sau triển khai link nhóm, SMS và bot theo cổng nghiệm thu. Nếu API/repo thực tế khác tài liệu, báo rõ bằng chứng rồi xin quyết định trước khi thay đổi nghiệp vụ.

---

**Kết luận:** Tái sử dụng `zca-js` như một adapter thử nghiệm cho tài khoản bot, không thay thế phần mềm nghiệp vụ. Giá trị quan trọng nhất trong V1 là **dữ liệu khách sạch, phân loại đúng, mời đơn giản, mẹ vẫn dùng Zalo thật**; tất cả chức năng nhân viên chỉ xuất hiện ở V2.
