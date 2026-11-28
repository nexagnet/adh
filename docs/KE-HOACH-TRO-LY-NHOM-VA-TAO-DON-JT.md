# Kế hoạch: trợ lý AI trong nhóm Zalo (tài khoản thứ hai) và tự tạo đơn J&T

Viết ngày 25/11/2026 theo yêu cầu của chủ dự án (Phùng Việt): "chốt làm thêm tài khoản cá nhân thứ hai vào nhóm; tìm cách tạo đơn tự động trên J&T; viết kế hoạch chi tiết có đầy đủ ngữ cảnh vào repo". Dành cho AI hoặc người vào làm tiếp mà **không đọc được lịch sử trò chuyện**. **Chưa có dòng mã nào được viết cho kế hoạch này.** Mỗi PR dưới đây là một việc nhỏ; làm theo thứ tự, và hỏi chủ dự án ở các chỗ ghi "CẦN DUYỆT".

**Thứ tự đọc trước khi làm:** `CLAUDE.md` (quy tắc, D18 đến D21), `docs/BAN-GIAO-CHIEN-DICH-THU.md`, file này, rồi các file được trỏ tới ở từng mục. Khi file này mâu thuẫn với `CLAUDE.md` thì `CLAUDE.md` thắng, **trừ đúng hai điểm đã được chủ dự án chốt riêng ở mục 1** (cho phép một tài khoản thứ hai; muốn tạo đơn J&T tự động).

Trả lời chủ dự án bằng tiếng Việt, ngắn, dễ hiểu; giao diện 100% tiếng Việt ("trợ lý AI", không "bot").

Nghiên cứu nền (đọc để biết nguồn và chỗ nào chưa kiểm chứng): `NGHIEN-CUU-AI-CHOT-DON-TRONG-NHOM.md` trong thư mục project files của dự án (`/mnt/project-files/nghien-cuu/`), kèm `docs/NGHIEN-CUU-CHATBOT-AI.md`, `docs/BAO-CAO-JT-TICH-HOP.md`, `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`, `docs/THIET-KE-AI-TRA-LOI.md`, `docs/ZALO-BRIDGE-API.md`.

---

## 1. Quyết định của chủ dự án (25/11/2026) và điều chưa được duyệt

| Việc | Trạng thái |
|---|---|
| Thêm **một tài khoản cá nhân thứ hai** vào nhóm bằng link nhóm để làm trợ lý AI tư vấn và chốt đơn trong nhóm | **Chủ dự án đã chốt "làm"** (tin nhắn 25/11/2026). Đảo một phần quy tắc "MỘT tài khoản Zalo" của `CLAUDE.md` mục 3a (D19). Phần còn lại của mục 3 (cấm né phát hiện) **vẫn nguyên** |
| Tìm cách **tạo đơn tự động trên J&T** | Chủ dự án yêu cầu tìm cách. **Chưa chọn đường nào.** Quyết định cũ "không tự đăng nhập J&T, không làm tích hợp chính thức" (`CLAUDE.md` mục 4) vẫn là nền; dùng **khóa API do J&T cấp** là việc khác nhưng cần chủ dự án nói rõ bằng lời trước khi code (CẦN DUYỆT, mục 7) |
| Số điện thoại, thiết bị, tên và ảnh của tài khoản thứ hai | **Chưa có.** Việc của chủ dự án, không bằng mã |
| Cho trợ lý **tự động** nói trong nhóm (không qua người duyệt) | **Chưa duyệt.** Bắt đầu bằng "chỉ soạn nháp", mở tự động chỉ khi chủ dự án nói bằng lời sau khi đã xem nháp |

**Chủ dự án nhận trách nhiệm** về rủi ro tài khoản bị Zalo khóa (đã nói ở D16, 22/11). Điều đó **không** mở rộng sang né phát hiện: các điều cấm ở mục 2 vẫn tuyệt đối.

## 2. Những điều KHÔNG được làm (lặp lại vì quan trọng nhất)

1. **Không né phát hiện của Zalo**: không giả vân tay/`imei`/`userAgent`, không ngẫu nhiên hóa thời gian cho giống người, không proxy xoay, không xoay vòng nhiều tài khoản (tài khoản thứ hai có **một** vai cố định, không luân phiên với …994), không giả thao tác. Có test khóa cứng ở `ZaloGatewayShapeTest` (cấm `Random` ở đường gửi) và `zalo-bridge/test/no-send.test.mjs` (đếm chỗ gọi hàm gửi). Mọi đường gửi mới phải được thêm vào các test khóa, không nới test.
2. **Không bao giờ tự xóa số điện thoại khách** (`CLAUDE.md` mục 5a).
3. **Không ghi vào repo, log, PR**: mật khẩu, khóa, token J&T, mã tài khoản Zalo (cả hai tài khoản), mã nhóm Zalo thật, phiên đăng nhập, số điện thoại hay dữ liệu khách thật. Dữ liệu thử chỉ dùng `fixtures/` (giả).
4. **Không thêm đường gọi Zalo mới mà không đi qua lớp bao an toàn** (`SafeZaloLink`) và chế độ thử (`TestMode`). Không thêm nút "chạy thật" lên web (`APP_ZALO_LIVE` chỉ ở `.env`).
5. **Không lộ dữ liệu khách này cho khách khác**: trong nhóm, lời nhắc gửi AI chỉ gồm "bảng thông tin cửa hàng" (công khai) và chính câu hỏi vừa rồi; không đưa đơn, tên, địa chỉ, số điện thoại, lịch sử của bất kỳ ai.
6. **Không sửa mục đích của PR đã gộp** mà chưa hỏi chủ dự án (quy tắc ngày 25/11).
7. **Máy chủ chỉ 2 GB RAM, còn khoảng 300 MB trống**: không thêm dịch vụ nặng (không Redis, Chatwoot, LangChain4j). Cầu nối thứ hai là một container Node nhỏ giống cái hiện có (giới hạn bộ nhớ nhỏ). Kiểm RAM trước khi thêm; `deploy.sh` từ chối khi RAM trống dưới 250 MB. **Nếu RAM không đủ cho container thứ hai thì dừng và báo chủ dự án**, đừng ép.
8. **Giai đoạn 1 phải miễn phí.** Không dịch vụ trả phí trong kế hoạch này mà chưa hỏi.

## 3. Bối cảnh cần biết (tóm tắt, kiểm lại bằng `git log` và mã)

- Adh: Java 25 + Spring Boot + PostgreSQL (`app/`, gói `vn.shop.crm`), cầu nối Node `zalo-bridge/` (thư viện `zca-js`, không chính thức), máy chủ Tencent, deploy bằng cron kéo ảnh Docker mỗi 10 phút khi CI xanh (cron **không** kéo `infra/` compose hay `.env`, nên đổi compose phải làm tay).
- Một tài khoản phụ (mã đuôi …994) hiện làm hết: gửi Tin 1, gửi lời mời kết bạn, thêm khách vào nhóm, tạo nhóm, đọc tin riêng, trả lời tin riêng bằng AI. Mẹ dùng Zalo thật riêng, là **phó nhóm**, chốt đơn trong nhóm. Nhóm chia theo khu vực, mục tiêu khoảng 5 khách mỗi nhóm.
- Cầu nối hiện **không đọc tin nhóm** (`GET /inbox` chỉ đọc tin riêng của người đã nhận tin hệ thống; ghi rõ "Không đọc tin nhóm" ở `docs/ZALO-BRIDGE-API.md`). Mọi việc đọc/gửi mới đều là **việc mới của cầu nối** và phải được khóa bằng test.
- Mọi lượt gọi AI đi qua `AiGate` (trần ngày, cầu dao); chỉ gửi văn bản đã che (`PiiMasker`); đầu ra qua `ReplyValidator` (chặn số lạ, link, số điện thoại, hứa hẹn, đổi trả, nền tảng khác, xin thông tin cá nhân, giả làm người). AI xưng "em" gọi "bác", một câu ngắn, **không chữ ký**, không bao giờ nhận là người; hỏi thẳng thì nói thật là trợ lý AI. Ưu đãi: giảm 10% so với TikTok, miễn ship; không nói về đổi trả của TikTok, không chê nền tảng.
- **Chế độ thử** mặc định bật, tắt khi `APP_ZALO_LIVE=true` ở `.env` máy chủ. Theo ghi nhớ gần nhất, hệ thống đang **chạy thật** (từ 24/11): đừng giả định đang ở chế độ thử, xem dải đầu trang `/zalo`.
- Hiện có `/handoffs` (hàng đợi "người thật", cột `assigned_to` để dành cho đội bán hàng).
- J&T: nhập đơn bằng file Excel xuất tay ("Đơn hàng", 14 cột) và file đối soát ("Lịch sử đối soát", 16 cột, tiêu đề ở dòng 3), tool Edge nhận file chứ không lưu mật khẩu. **Cửa hàng tạo đơn trên web/app J&T**; các API chính thức của J&T (xem mục 6) thiết kế cho việc tạo đơn **từ hệ thống của shop**. `--doi-soat` với J&T thật chưa từng chạy.
- **Số migration:** `CLAUDE.md` ghi tới V48 nhưng ngày 25/11 repo đã có **V52** (V51 thời tiết nhiều ngày, V52 Tin 1 bị chặn người lạ). Số mới là **`V53`** (kiểm lại bằng `ls app/src/main/resources/db/migration | sort -V | tail`), gộp `main` vào nhánh trước khi mở PR có migration.

## 4. Phần A: trợ lý AI trong nhóm bằng tài khoản Zalo thứ hai

### 4.1 Thiết kế đã chốt hướng, chờ duyệt chi tiết

**Vai trò.** Tài khoản thứ hai ("trợ lý nhóm") **chỉ ở trong các nhóm khách**, tên và ảnh nói rõ là trợ lý AI của cửa hàng, mặc định im lặng. Tài khoản …994 giữ nguyên việc mời, kết bạn, thêm nhóm, tạo nhóm, tin riêng. Lợi ích: lỗi hoặc khóa ở bên này không kéo sập bên kia.

**Cách vào nhóm: do con người làm, không do mã.** Chủ dự án (hoặc mẹ) mở link nhóm trên điện thoại của tài khoản thứ hai và vào, hoặc …994/mẹ thêm tài khoản này vào nhóm như thêm một thành viên. **Không viết mã cho tài khoản thứ hai tự bấm link vào hàng loạt nhóm** (tăng rủi ro, không cần thiết với vài chục nhóm). Adh chỉ **ghi nhận** nhóm nào có trợ lý (bảng `group_assist_membership`, người ghi, giờ) và **chỉ đọc/nói trong nhóm đã ghi nhận**. Tin chào đầu nhóm nên có một câu nói rõ trong nhóm có trợ lý AI của cửa hàng (nội dung chủ dự án duyệt; tin chào đầu nhóm hiện đã có, thêm câu này là việc nhỏ).

**Cầu nối thứ hai.** Một container riêng của cùng ảnh `zalo-bridge`, cấu hình `BRIDGE_ROLE=GROUP`:
- Có **phiên đăng nhập, thư mục lưu phiên, cầu dao, sổ hạn mức, cổng, khóa chung riêng** (không dùng chung gì với …994 để tách rủi ro). Biến môi trường riêng (ví dụ `ZALO_GROUP_ACCOUNT_ID`, `BRIDGE_GROUP_KEY`); tên chính xác chốt khi code, không ghi giá trị vào repo.
- **Vai `GROUP` chỉ mở đúng các đường**: `GET /status`, `GET /group-inbox`, `POST /group-messages` (và `POST /group-reactions` nếu sau này duyệt). **Không** có `/friend-requests`, `/messages` riêng tư, `/groups` tạo nhóm, `/people/lookup`, thêm thành viên. Ngược lại vai hiện có (`PRIMARY`) **không** có các đường nhóm này. Test khóa cả hai chiều.
- `GET /group-inbox`: chỉ trả tin **văn bản** trong nhóm mà Adh đã đưa vào danh sách cho phép (đẩy qua `PUT /allowed-groups`), tin của chính tài khoản, của nhóm lạ, tệp/ảnh, lịch sử cũ đều bị bỏ ngay, không lưu, không log. Giữ **trong bộ nhớ** tối đa vài giờ, cắt độ dài. Nội dung, mã người gửi, mã nhóm không vào log.
- `POST /group-messages`: gửi MỘT tin văn bản vào MỘT nhóm trong danh sách cho phép, có **khoảng nghỉ cố định**, hạn mức ngày và giờ cho từng nhóm và toàn cục, khung giờ, phanh khẩn cấp, cầu dao (lỗi lạ hoặc Zalo cảnh báo thì tự dừng, không tự mở lại, không tự gửi lại khi không rõ kết quả: ghi `UNKNOWN`). **Không có số ngẫu nhiên.**
- Con số hạn mức ban đầu (đề xuất, chủ dự án duyệt): tối đa 1 tin mỗi nhóm mỗi 5 phút, 10 tin mỗi nhóm mỗi ngày, 100 tin cả ngày, nghỉ cố định 30 giây giữa hai tin, 08:00 đến 21:00. **Đây là mức tự đặt, không phải mức Zalo công bố (Zalo không công bố).**

**Phía Adh** (gói mới `vn.shop.crm.groupai`, **không** nhét vào `ZaloAutoService`):
- Giao diện `GroupLink` riêng (không dùng chung `ZaloLink` để không lẫn đường), có bản bọc an toàn tương tự `SafeZaloLink`: ở chế độ thử chỉ nhóm thử mới được đọc/nói; nhóm thật bị chặn.
- Công tắc riêng (tắt mặc định, `APP_GROUPAI_ENABLED`) cộng ba nấc **Tắt / Chỉ soạn nháp / Tự động** (Tự động chỉ mở sau khi đã chạy nháp và tick xác nhận, y như `/zalo/reply`). Theo "Dừng ngay" và "Tạm dừng tất cả" hiện có.
- Cầu dao riêng cho cầu nối nhóm; cảnh báo sức khỏe (`ZaloHealthService`) thêm tài khoản thứ hai.

**Quy tắc trả lời trong nhóm** (kế thừa D20, thêm các luật của nhóm):
1. Mặc định **im lặng**. Chỉ xét tin khi (a) có nhắc tên trợ lý, hoặc (b) AI phân loại là câu hỏi rõ ràng thuộc phạm vi (nghi ngờ thì không nói).
2. **Im lặng khi người thật đã nói**: mẹ hoặc thành viên quản trị đã nhắn trong nhóm trong N phút gần nhất (đề xuất 10) hoặc đã trả lời đúng câu hỏi đó. Theo **từng nhóm**.
3. Gắn câu hỏi với mã Zalo người hỏi để nhắc đúng người; **không nói gì về người khác** trong nhóm.
4. Phạm vi: mời vào nhóm, ưu đãi, thông tin trong bảng cửa hàng, và **chốt đơn** (mục 4.3). Giá, còn hàng, bảo hành, đổi trả, khiếu nại, ảnh, tin mơ hồ: **chuyển người thật** qua `/handoffs` và hoặc mời nhắn riêng.
5. Nội dung câu qua `ReplyValidator` (có thể thêm luật nhóm: không nhắc tới người cụ thể ngoài người hỏi, không đưa link ngoài danh sách cho phép).
6. Khách đã từ chối, đã chặn, hoặc đã xóa khỏi hệ thống: **không bao giờ** trợ lý nói với họ trong nhóm (trường hợp họ vẫn ở trong nhóm: chỉ im lặng, chuyển người thật nếu họ hỏi).
7. Mỗi tin đến xử lý một lần (dedupe theo mã tin), có khóa theo cuộc trò chuyện.
8. Tin nhóm **không** lưu nội dung lâu: chỉ giữ nhãn, giờ, mã tin; trích đoạn đã che tối đa 300 ký tự giữ 30 ngày cho việc xem nháp, như phần nhận diện từ chối.

### 4.2 Danh sách PR cho phần A (theo thứ tự; mỗi cái một nhánh `claude/project-thread-*`)

| PR | Việc | Chạm vào | Nghiệm thu |
|---|---|---|---|
| A0 | **Tài liệu và quy tắc**: sửa `CLAUDE.md` mục 3a và mục 3 (từ "MỘT tài khoản" thành "một tài khoản cho mời và tin riêng, cộng một tài khoản trợ lý nhóm có vai cố định"), thêm ADR-0003 "Tài khoản thứ hai: trợ lý nhóm" ghi rủi ro và điều kiện ở 4.1; cập nhật `docs/ZALO-BRIDGE-API.md` phần mở đầu. Chỉ tài liệu | Chủ dự án đọc và nói đồng ý (CẦN DUYỆT); CI xanh |
| A1 | **Cầu nối vai `GROUP`**: `config.mjs` đọc `BRIDGE_ROLE`; `server.mjs` chỉ đăng ký các đường của vai; thêm `GET /group-inbox`, `PUT /allowed-groups`, `POST /group-messages` ở `zca-adapter.mjs` (đúng MỘT chỗ gọi hàm gửi nhóm và đúng MỘT chỗ đăng ký nghe tin nhóm); bộ đếm và phanh; cầu dao; `fake-adapter.mjs` bản giả. Khóa bằng `no-send.test.mjs` (đếm số chỗ gọi, kiểm vai `PRIMARY` không có đường nhóm và ngược lại), test nhóm không nằm trong danh sách bị bỏ, test không log nội dung | `npm test` xanh; không đường nào ngoài danh sách |
| A2 | **Phía Adh nền tảng** (V53): bảng `group_assist_membership`, `group_assist_settings` (công tắc, nấc, hạn mức), `group_assist_message` (nhật ký nhãn/giờ/mã tin, không nội dung dài); gói `groupai` với `GroupLink`, bản bọc an toàn có chế độ thử, `GroupAssistGateway` gọi cầu nối; hiện trạng ở `/zalo` (tab mới hoặc trong tab hiện có theo `docs/KE-HOACH-DON-GIAN-HOA-GIAO-DIEN-CAU-HINH.md`, tiếng Việt); thêm bảng vào TRUNCATE của `AbstractPostgresTest`; sức khỏe tài khoản thứ hai ở `ZaloHealthService`; compose: thêm dịch vụ `zalo-bridge-group` vào `infra/docker-compose.prod.yml` và `.env.example` (không giá trị thật) | `./mvnw verify` xanh; test chế độ thử: nhóm thật bị chặn |
| A3 | **Ghi nhận nhóm có trợ lý**: màn nhập "nhóm này đã có trợ lý" (chọn từ danh sách nhóm đã gán mã), đẩy danh sách cho phép xuống cầu nối nhóm; tin chào đầu nhóm thêm câu "có trợ lý AI" (nội dung chủ dự án duyệt) | Test: nhóm chưa ghi nhận không được đọc/nói |
| A4 | **Đọc và soạn nháp**: `GroupInboxService` (kéo `GET /group-inbox`), bộ phân loại (nhắc tên / câu hỏi / ngoài phạm vi, nghi ngờ thì im), bộ soạn câu từ bảng thông tin cửa hàng, `ReplyValidator` thêm luật nhóm, im lặng khi người thật đã nói (theo nhóm), dedupe, hàng đợi nháp hiện ở `/zalo/reply` hoặc tab "Trợ lý nhóm" để mẹ **bấm Gửi** hoặc Bỏ; việc ngoài phạm vi vào `/handoffs`. **Chưa tự gửi** (nấc Tự động chưa mở) | Có test: không lộ dữ liệu khách khác (kiểm lời nhắc gửi AI chỉ có bảng cửa hàng và câu hỏi); test câu thử tiếng Việt thật theo thứ tự ở `CLAUDE.md` mục 7 |
| A5 | **Gửi nháp đã duyệt** qua `POST /group-messages` (nút Gửi trong giao diện), chỉ nhóm đã ghi nhận, khoảng nghỉ cố định, cầu dao; kết quả không rõ thì `UNKNOWN`, không gửi lại | Test khóa đường gửi duy nhất; không có `Random` |
| A6 | **Thử Zalo thật** (việc của chủ dự án, theo hướng dẫn viết trong PR này): một nhóm thử có số của chủ dự án và người quen đồng ý; chạy nấc "Chỉ soạn nháp" vài ngày; đọc nhật ký cầu nối, điền mã lỗi thật, ghi các chỗ "chưa kiểm chứng" | Chủ dự án báo kết quả |
| A7 | **Nấc Tự động** (chỉ sau A6 và khi chủ dự án nói bằng lời): hạn mức, tick xác nhận đã thử, khung giờ, nút Dừng ngay | CẦN DUYỆT |

### 4.3 Chốt đơn trong nhóm (đi cùng A4 trở đi, dùng chung với phần B)

Trong nhóm **không** thu thập tên, số điện thoại, địa chỉ của khách (lộ trước các khách khác). Luồng: khách bày tỏ muốn mua → trợ lý nói một câu mời "bác nhắn riêng em để chốt đơn" → ở tin riêng (đã có AI trả lời D20, mở rộng phạm vi chốt đơn) hỏi sản phẩm, số lượng, địa chỉ nhận → tạo **đơn nháp** (mục 6, `order_draft`) → người duyệt. Khách đã là khách trong hệ thống (đã có số, địa chỉ từ đơn cũ) thì đơn nháp điền sẵn để người xác nhận lại. Việc mở rộng phạm vi tin riêng sang "chốt đơn" **đổi hành vi đã gộp (D20: phạm vi hẹp)** nên **CẦN DUYỆT** trước khi code, không tự ý.

### 4.4 Rủi ro của phần A (nói thẳng với chủ dự án)

- Vẫn là thư viện không chính thức, trái điều khoản Zalo; tài khoản thứ hai có thể bị khóa; hai tài khoản của cùng một cửa hàng cùng ở một nhóm có thể bị Zalo liên hệ nếu có báo cáo. Ngưỡng của Zalo không công bố nên mọi hạn mức là suy đoán an toàn, phải chạy thật nhỏ và quan sát.
- Nhận tin nhóm là **việc đọc mới**: phải chắc chắn không đọc và không lưu nhóm lạ (test khóa ở A1), không lưu nội dung lâu.
- Một nhóm có người thật nhắn qua lại: trợ lý chen vào sai lúc sẽ gây khó chịu → chọn mặc định im, chỉ nháp.
- RAM máy chủ: container thứ hai tốn thêm bộ nhớ (xem mục 2.7).

## 5. Việc của chủ dự án (không làm bằng mã)

1. Chuẩn bị số điện thoại và thiết bị cho tài khoản thứ hai (của chính chủ dự án, một tài khoản duy nhất); đặt tên và ảnh nói rõ là trợ lý AI của cửa hàng; đăng nhập quét QR giống cách đã làm với …994.
2. Cho một nhóm thử vào (số của chủ dự án và người quen đồng ý) trước khi vào nhóm khách thật.
3. Duyệt A0, duyệt nội dung câu chào có trợ lý, duyệt hạn mức ở 4.1.
4. Với J&T: xem mục 6.4.

## 6. Phần B: tự tạo đơn trên J&T

### 6.1 Điều đã biết và chưa biết (đọc kỹ)

- **Đã đọc trực tiếp:** `docs/BAO-CAO-JT-TICH-HOP.md` (21/11/2026): các API J&T được liệt kê là Tạo đơn, Hủy đơn, Tra hành trình, Tra giá tiền, Mã hóa API (ký bằng `data_digest`, MD5 rồi Base64, cần `customerid` và `eccompanyid` do J&T cấp); phải **ký hợp đồng** với J&T rồi mới có mã khách hàng và khóa; webhook trạng thái đăng ký qua nhân viên sale hoặc bưu cục; không thấy API liệt kê đơn theo thời gian; các đối tác (Pancake, Sapo, MISA eShop, Fchat, Goship, TrustSales) tạo đơn từ nền tảng của họ.
- **Phiên 25/11 không đọc được nội dung** các trang `api-docs.jtexpress.vn` (công cụ báo `ROBOTS_UNREACHABLE`); báo cáo 21/11 ghi tên miền khi đó không phân giải. Vì vậy **mọi tên trường, cách ký, môi trường thử, giới hạn tần suất, phí đều chưa được kiểm chứng** trong repo này. **Đừng viết mã gọi J&T dựa vào trí nhớ**: cần tài liệu thật do chủ dự án dán vào hoặc do J&T gửi.
- Goship (trung gian) có tích hợp J&T, có webhook trạng thái, có tài liệu riêng ở `https://doc.goship.io` (**chưa đọc**); phí theo biểu phí Goship (chưa đọc).
- Điều khoản cổng khách hàng J&T không thấy cấm rõ robot, nhưng cổng có OTP email và captcha; tài khoản gắn CCCD, ngân hàng, COD, nên **không** tự đăng nhập hay giữ phiên (quyết định cũ vẫn đúng).

### 6.2 Các đường, từ rẻ đến tốn công

| Mức | Đường | Tiền | Rủi ro | Cần gì |
|---|---|---|---|---|
| B1 | **Đơn nháp trong Adh + mẹ tạo đơn trên cổng J&T** (sao chép nội dung; có nút "Sao chép"). Sau đó nhập file như hiện nay | 0 | Thấp | Chỉ mã Adh |
| B2 | **Xuất file nhập hàng loạt đúng mẫu J&T** (nếu cổng có chức năng này: **chưa xác nhận**) | 0 | Thấp | File mẫu thật từ cổng (không kèm dữ liệu khách) |
| B3 | **API chính thức J&T** tạo đơn/hủy đơn/tra hành trình | Có thể 0 phí API, cước như thường (suy đoán) | Vừa: đơn sai, trùng; khóa API là bí mật | Hợp đồng với J&T, `customerid`, `eccompanyid`, khóa, tài liệu thật, môi trường thử |
| B4 | **Qua Goship** (một API nhiều hãng, có webhook) | Theo biểu phí Goship | Thấp đến vừa, phụ thuộc bên thứ ba | Tài khoản Goship, đọc `doc.goship.io` |
| B5 | Nền tảng đối tác tạo đơn (Pancake, Sapo, Fchat...) | Phí nền tảng | Đổi cách làm việc của bố mẹ | Quyết định lớn, không trong kế hoạch này |
| Loại | Tự đăng nhập cổng J&T, giữ phiên, bot OTP/captcha | | Cao | **Không làm** |

**Khuyến nghị:** làm B1 ngay (không cần ai cấp gì), **song song** chủ dự án xin tài khoản API (B3) hoặc hỏi Goship (B4). B3/B4 chỉ code khi đã có tài liệu thật.

### 6.3 Danh sách PR cho phần B

| PR | Việc | Ghi chú |
|---|---|---|
| B0 | **Tài liệu**: dán tài liệu API J&T (hoặc Goship) mà chủ dự án cung cấp vào `docs/` (đã che khóa, không có khóa thật), cập nhật `docs/BAO-CAO-JT-TICH-HOP.md` mục 2 "Tích hợp chính thức" bằng sự thật đã đọc | Chặn B3/B4 cho tới khi có |
| B1 | **Đơn nháp** (V53 hoặc V54): bảng `order_draft` (khách, sản phẩm và số lượng, địa chỉ, COD, trạng thái `NHAP / CHO_DUYET / DA_DUYET / DA_TAO / BO`, người duyệt, nguồn `TIN_RIENG / NHOM / TAY`, mã đơn hãng khi có); trang "Đơn nháp" (có thể là tab trong trang hiện có) cho người duyệt xem, sửa, sao chép, đánh dấu đã tạo và dán mã vận đơn; **số điện thoại và địa chỉ chỉ hiện cho admin**, không vào nhật ký, không gửi AI | Không gọi dịch vụ ngoài; test: AI không bao giờ nhận số/địa chỉ |
| B2 | **Giao diện cổng vận chuyển** `ShippingOrderGateway` (tạo, hủy, tra) với bản giả `FakeShippingGateway` cho test và bản `Disabled` mặc định; **chưa có bản gọi J&T thật**. Lớp bao an toàn: chỉ đơn `DA_DUYET` mới được tạo; **khóa chống tạo hai lần** (mã đơn nội bộ cố định, kiểm trước khi gọi); lỗi không rõ kết quả thì `UNKNOWN`, không tự gọi lại, báo người; công tắc `APP_SHIPPING_API_ENABLED` tắt mặc định; chế độ thử: chỉ khách thử | Khóa bằng test; thêm vào danh sách TRUNCATE |
| B3 | **Bản J&T thật** (hoặc Goship): ký theo tài liệu thật, khóa API chỉ qua biến môi trường, không log khóa hay thân yêu cầu có số điện thoại; thử trên môi trường thử trước (nếu có), rồi MỘT đơn thật do chủ dự án duyệt | Chỉ làm sau B0; CẦN DUYỆT trước khi tạo đơn thật đầu tiên (tạo đơn thật tốn cước, khó hoàn tác) |
| B4 | **Theo dõi trạng thái**: webhook (nếu J&T/Goship cấp) hoặc gọi tra hành trình theo lịch thưa; cập nhật trạng thái đơn; vẫn giữ nhập file và đối soát như hiện nay làm nguồn đối chiếu | Phụ thuộc điều J&T trả lời về webhook |
| B5 | **Đối soát và tiền**: nối mã vận đơn tạo bằng API với file "Lịch sử đối soát" (đã có), kiểm `/performance` (tiền tố `NHOM-` ở `CLAUDE.md` mục 8, chờ chủ dự án chốt) | Chạy `--doi-soat` với J&T thật trước |

### 6.4 Câu hỏi gửi J&T (chủ dự án gọi 1900 1088 hoặc email cskh@jtexpress.vn, hoặc nhân viên sale/bưu cục)

1. Cửa hàng muốn tạo đơn bằng API: điều kiện (sản lượng tối thiểu, hợp đồng), thời gian, phí API, cước có khác đơn tạo trên web không.
2. Có môi trường thử không; giới hạn số yêu cầu mỗi phút.
3. Có API xem danh sách đơn đã tạo trên web (kèm trạng thái) không, hay chỉ tra theo mã vận đơn.
4. Có webhook trạng thái cho đơn tạo bằng API không, đăng ký thế nào.
5. Xử lý COD, hủy đơn, đơn trùng (khóa chống trùng) thế nào.
6. Xin gửi tài liệu API bản đầy đủ (PDF hoặc trang), vì trang `api-docs.jtexpress.vn` công cụ của AI không đọc được.

## 7. Câu hỏi còn mở cho chủ dự án (CẦN DUYỆT)

1. Đồng ý thiết kế tài khoản thứ hai ở 4.1 (vai chỉ-nhóm, cầu nối riêng, vào nhóm bằng tay, nấc nháp trước)? Đồng ý các con số hạn mức đề xuất?
2. Cho phép trợ lý nói "mời nhắn riêng để chốt đơn" ở nhóm và mở rộng phạm vi tin riêng sang chốt đơn (đổi D20)?
3. Với J&T: bắt đầu bằng B1 (đơn nháp), và có đồng ý xin tài khoản API chính thức (khác "tự đăng nhập"), hoặc thử Goship?
4. Máy chủ có đủ RAM cho container cầu nối thứ hai không (cần SSH xem `free -m`)? Nếu không, có chịu nâng máy hoặc tạm tắt thứ gì?
5. Chốt tiền tố đơn `NHOM-` cho đơn bán trong nhóm (`CLAUDE.md` mục 8)?

## 8. Thứ tự làm đề xuất

1. A0 và B0 (tài liệu, không rủi ro) và xin tài khoản/tài liệu J&T ngay.
2. B1 (đơn nháp) vì không phụ thuộc ai.
3. Chủ dự án chuẩn bị tài khoản thứ hai; A1, A2, A3.
4. A4, A5 (nấc nháp), thử nhóm thử (A6).
5. B2 (khung cổng vận chuyển), rồi B3 khi đã có tài liệu và tài khoản J&T.
6. A7 và B4, B5 sau khi các bước trên chạy ổn trên thật.

Mỗi PR: review kỹ backend và **ghi kết quả review vào mô tả PR** (đúng đắn, an toàn, xử lý lỗi, migration, test); CI xanh mới gộp; không đụng `nexagnet/.github`.

## 9. Việc kiểm chứng còn thiếu của kế hoạch này

| Điều | Cần |
|---|---|
| Nội dung API J&T (trường, ký, thử, giới hạn, phí) | Chủ dự án dán tài liệu hoặc J&T gửi |
| `zca-js` có sự kiện/hàm đọc và gửi tin nhóm đúng như dự kiến và dạng lỗi thật của nhóm (bị cấm chat, nhóm giải tán...) | A1 và thử thật A6; điền mã lỗi thật |
| Zalo có phản ứng thế nào khi hai tài khoản cùng một cửa hàng ở một nhóm | Quan sát khi chạy thử nhỏ; không có tài liệu công khai |
| Ảnh hưởng khi bật tin nhóm tới RAM cầu nối | Đo trên máy chủ |
| Giá và điều kiện OA/GMF/ZBS (hướng chính thức dự phòng) | `docs/` cũ có ghi, chưa đọc lại trang Zalo gốc |

Hướng dự phòng chính thức (Zalo OA + ZBS + GMF, cần hộ kinh doanh) vẫn mở nếu tài khoản thứ hai bị khóa nhiều lần: xem `NGHIEN-CUU-KET-BAN-VAO-NHOM-ZALO.md` trong project files.

## 10. Hướng dẫn xin tài khoản API J&T (chủ dự án tự làm; AI không làm thay được)

**Vì sao AI không tự xin được:** hợp đồng và khóa API gắn với **chủ tài khoản J&T** (có CCCD, ngân hàng, COD), J&T sẽ xác minh người ký, có thể gọi lại số đăng ký hoặc đòi giấy tờ; AI không có điện thoại, không phải chủ tài khoản, và không được thay mặt gửi email ra ngoài khi chủ dự án chưa xem. AI có thể **soạn sẵn email** (chủ dự án đọc rồi tự gửi) và đọc lại tài liệu khi chủ dự án dán vào.

### Bước 1. Chuẩn bị (5 phút)
- Mã khách hàng J&T của cửa hàng (có trong tên file đối soát dạng `<mã khách>-<ngày>.xlsx`, hoặc trong cổng khách hàng), tên cửa hàng, họ tên người đứng tên tài khoản, số điện thoại và email đăng ký tài khoản J&T.
- Sản lượng đơn gần đây (trong Adh: số đơn J&T mỗi tháng) để J&T biết quy mô.
- **Không gửi qua email**: mật khẩu, mã OTP, ảnh CCCD (chỉ gửi khi J&T yêu cầu qua kênh chính thức của họ).

### Bước 2. Liên hệ (làm một trong ba, nên làm cả hai đầu)
1. **Gọi tổng đài 1900 1088** (số trên trang chính thức của J&T) và nói muốn "tích hợp API tạo đơn cho cửa hàng", xin chuyển cho bộ phận kinh doanh hoặc đối tác tích hợp.
2. **Nhắn nhân viên sale hoặc bưu cục đang lấy hàng** của cửa hàng (người đã ký hợp đồng với shop): theo báo cáo cũ, webhook và tài khoản tích hợp thường đăng ký qua họ.
3. **Email cskh@jtexpress.vn** (địa chỉ ghi ở trang chính thức) bằng mẫu dưới.

### Mẫu email (chủ dự án sửa chỗ trong ngoặc rồi tự gửi)
> Tiêu đề: Đề nghị cấp tài khoản và tài liệu API tạo đơn - mã khách hàng [MÃ KHÁCH HÀNG]
>
> Kính gửi J&T Express Việt Nam,
>
> Cửa hàng [TÊN CỬA HÀNG] (mã khách hàng [MÃ KHÁCH HÀNG], số điện thoại đăng ký [SỐ ĐĂNG KÝ]) đang gửi hàng qua J&T khoảng [SỐ] đơn mỗi tháng, có thu hộ COD. Chúng tôi muốn tạo đơn tự động từ hệ thống quản lý của cửa hàng qua API chính thức. Xin J&T hỗ trợ:
> 1. Điều kiện để được cấp tài khoản API (sản lượng tối thiểu, hợp đồng, thời gian xử lý).
> 2. Cấp thông tin kết nối (mã khách hàng tích hợp, khóa) và tài liệu API đầy đủ bản mới nhất (tạo đơn, hủy đơn, tra hành trình, tra giá, cách ký/mã hóa).
> 3. Có môi trường thử để kiểm thử trước khi tạo đơn thật không, và giới hạn số yêu cầu mỗi phút.
> 4. Có webhook cập nhật trạng thái cho đơn tạo bằng API không, và cách đăng ký.
> 5. Có API xem danh sách đơn đã tạo trên web/app (kèm trạng thái) không.
> 6. Phí API (nếu có) và cước có khác đơn tạo trên web không; cách xử lý COD, hủy đơn và chống tạo trùng đơn.
>
> Người liên hệ: [HỌ TÊN], [SỐ ĐIỆN THOẠI], [EMAIL]. Xin cảm ơn.

### Kịch bản gọi ngắn
"Em gọi từ cửa hàng [tên], mã khách hàng [mã]. Em muốn đăng ký tích hợp API tạo đơn để hệ thống tự tạo đơn J&T. Cho em hỏi điều kiện, ai là người hỗ trợ tích hợp, và xin tài liệu API ạ." Ghi lại **tên người, số, giờ, mã yêu cầu** (nếu có) vào ghi chú riêng (không đưa lên repo).

### Bước 3. Khi J&T trả lời
- Nhận **tài liệu API** (PDF hoặc link): dán nội dung (đã che khóa) cho AI, hoặc để vào thư mục dự án; AI sẽ viết B0 (cập nhật `docs/BAO-CAO-JT-TICH-HOP.md` mục 2).
- Nhận **khóa và mã tích hợp**: **không dán vào chat, repo, log hay PR**. Tự ghi vào `.env` trên máy chủ (biến môi trường, tên chốt khi làm B3) hoặc nói AI cần biến nào, rồi chủ dự án tự điền.
- Nếu J&T từ chối hoặc đòi sản lượng quá cao: dùng **Goship** (xem mục 6.2 B4): đăng ký tại trang Goship, hỏi hỗ trợ tích hợp J&T qua API, đọc `https://doc.goship.io` (đã được liệt kê, AI chưa đọc), và so biểu phí.
- Trong lúc chờ: làm B1 (đơn nháp) để không mất thời gian.

### Bước 4. Việc nhỏ khác nên hỏi cùng lúc
- J&T có gửi email báo cáo đơn hằng ngày không (đã nêu ở `docs/BAO-CAO-JT-TICH-HOP.md` mục 5, đường B).
- Có tài khoản con để cấp riêng cho hệ thống (không dùng tài khoản chính có tiền) không.
