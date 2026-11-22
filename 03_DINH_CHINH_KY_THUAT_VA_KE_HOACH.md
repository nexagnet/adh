# ĐÍNH CHÍNH KỸ THUẬT & KẾ HOẠCH THỰC HIỆN CODE

**Ngày tra cứu:** 21/11/2026
**Nguồn sự thật nghiệp vụ:** `01_DAC_TA_NGHIEP_VU.md` (không thay đổi, mọi mâu thuẫn phát hiện được đánh dấu **[XUNG ĐỘT]** để chủ cửa hàng quyết).
**Quan hệ với `02_NGHIEN_CUU_KY_THUAT.md`:** file này **thay thế các điểm sai/thiếu** của file 02; phần file 02 không bị phản bác vẫn giữ nguyên (module, mô hình dữ liệu gợi ý, thuật toán chia nhóm, an toàn server).

> Mức tin cậy: **[ĐÃ ĐỌC]** = đọc trực tiếp tài liệu chính thức/nguồn gốc ngày 21/11/2026; **[NGUỒN PHỤ]** = bài tổng hợp bên thứ ba; **[CHƯA KIỂM CHỨNG]** = phải POC. Chưa chạy thử bất kỳ API nào, chưa dùng tài khoản thật.

---

## 0. QUYẾT ĐỊNH ĐÃ CHỐT VỚI CHỦ CỬA HÀNG (21/11/2026)

Mục này **ghi đè** mọi chỗ khác trong file khi có mâu thuẫn (ví dụ đề xuất VPS Việt Nam ở mục 1 và 3.1, ZBS là kênh mời ưu tiên ở mục 2.1).

| # | Quyết định | Hệ quả cho kế hoạch |
|---|---|---|
| D1 | Cửa hàng là **kinh doanh cá nhân, chưa có hộ kinh doanh**; **sẽ đăng ký hộ kinh doanh** | Đăng ký là việc đầu tiên, mở khóa SMS brandname, Zalo OA xác thực, ZBS. Thủ tục và phí chưa tra, hỏi kế toán/UBND địa phương |
| D2 | Nhóm Zalo: **giữ nhóm cá nhân, mẹ làm trưởng nhóm** (đúng BR-07/08/11) | Bỏ GMF khỏi lựa chọn mặc định. Mốc 1E chỉ còn: không bot, hoặc `zca-js` bằng tài khoản riêng |
| D3 | Chủ cửa hàng **tự chấp nhận rủi ro pháp lý** khi mời khách cũ | Vẫn ghi cơ sở liên hệ = "chủ cửa hàng chịu trách nhiệm" cho từng khách, vẫn có sổ từ chối và nhật ký gửi. Chưa có xác nhận pháp lý ≠ được bỏ qua cổng chặn gửi |
| D4 | Kênh mời chính: **SMS brandname qua nhà cung cấp**; dự phòng: **Zalo cá nhân bằng tài khoản riêng** (không dùng tài khoản của mẹ) | Mốc 1D làm bộ gửi SMS trước. Zalo cá nhân là bộ gửi thứ hai, tắt mặc định, chủ cửa hàng tự chịu rủi ro khóa tài khoản |
| D5 | Gửi **chậm, theo lô nhỏ**, tự dừng khi có từ chối/lỗi cao/hết ngân sách | Hàng đợi có hạn mức ngày, giờ gửi 07:00–22:00, khoảng nghỉ ngẫu nhiên, mỗi khách một lần. **Không** làm thủ thuật né phát hiện (giả lập thao tác, đổi dấu vân tay, xoay vòng tài khoản) |
| D6 | Ngân sách SMS **500 nghìn – 1,5 triệu/tháng**; VPS và tên miền đã có | Đặt trần chi tiêu SMS trong hệ thống |
| D7 | VPS **ở Singapore, 2 CPU, 2 GB RAM, 40 GB đĩa** | Dữ liệu khách nằm ngoài Việt Nam: ghi nhận rủi ro chuyển dữ liệu xuyên biên giới, mã hóa đĩa/sao lưu, không ghi SĐT đầy đủ vào log, hỏi luật sư. Cấu hình 2 GB: giới hạn bộ nhớ Java (~512 MB), Postgres nhỏ, bật swap, sao lưu ra ngoài VPS, không cài dịch vụ nặng |
| D8 | Chưa có file mẫu từ hai hãng; chủ cửa hàng **có quyền truy cập tài khoản của ba mẹ và tự xuất** khi cần | Dev bằng dữ liệu giả; chủ cửa hàng tự xuất, che SĐT trước khi gửi file mẫu |
| D9 | **21/11/2026: tạm chưa dùng SMS, làm qua Zalo trước** | Đường mời hiện dùng: xuất danh sách, **mẹ gửi tay** bằng Zalo của mình, lô nhỏ. Không viết bộ gửi SMS/ZBS lúc này, giữ chúng ở trạng thái tắt và ghi "chưa dùng". Mục D4 (SMS là kênh chính) tạm không áp dụng cho tới khi chủ cửa hàng quyết lại |
| D10 | **21/11/2026: giấy in bỏ vào MỌI gói hàng, kể cả khách mua từ 2 lần**, dùng một mã QR tĩnh chung | Ngoại lệ so với BR-06: khách tự quét, hệ thống không chủ động mời; ai quét cũng vào được nhóm còn chỗ. Vẫn chặn quét lặp (cookie, giới hạn theo địa chỉ mạng) và có cách khách tự xin ngừng nhận tin. Mẹ đếm lại thành viên nhóm mỗi tuần (giả định) |
| D11 | **21/11/2026: phân vùng khách theo miền/tỉnh và xếp nhóm Zalo theo khu vực** (mục 1G) | Lý do kinh doanh: mùa lạnh chủ yếu khách miền Nam mua, và nhóm gồm người ở gần nhau thì nói chuyện cùng hoàn cảnh. Tỉnh/thành chỉ được suy ra từ địa chỉ vận đơn đã có (không gọi dịch vụ ngoài, không lưu thêm gì về khách); không chắc thì "chưa rõ". Nhóm có khu vực: cùng tỉnh > cùng vùng > cùng miền > nhóm chung; khác miền bị loại. Mã QR chung hỏi khách chọn tỉnh (có thể bỏ qua); khách đã chọn tỉnh mà chưa có nhóm hợp được ghi là "đang chờ nhóm" (chỉ mã tỉnh) để mẹ biết cần mở nhóm ở đâu |
| D12 | **21/11/2026: bản đồ phân bố khách theo tỉnh và nhắc việc thời tiết** | Bản đồ là sơ đồ minh họa tự có trong phần mềm (không dùng dịch vụ bản đồ ngoài), chỉ hiện SỐ ĐẾM theo tỉnh, không địa chỉ/số điện thoại. Thời tiết lấy từ MET Norway (miễn phí, không cần khóa, CC BY 4.0; Open-Meteo bản miễn phí chỉ cho phi thương mại nên không dùng): chỉ hỏi tỉnh đang có khách, chỉ gửi tọa độ thành phố tỉnh lỵ, tôn trọng Expires. MẶC ĐỊNH TẮT (`APP_WEATHER_ENABLED`). Nhắc việc chỉ hiện trên web cho mẹ khi tỉnh có từ 10 khách mà dự báo 48 giờ tới lạnh (thấp nhất ≤13°C Bắc, ≤16°C Trung, ≤22°C Nam) hoặc nóng (≥37°C); KHÔNG tự nhắn khách. Ngưỡng là giả định, chỉnh được |
| D13 | **21/11/2026: nhắn bằng tài khoản Zalo cá nhân phụ, thêm vào nhóm theo khu vực** | Chủ shop chốt: nhắn tin tư vấn sản phẩm cho khách mua 1 lần bằng tài khoản phụ; ai đồng ý kết bạn thì thêm vào nhóm cùng khu vực; mỗi người chỉ một tin; nội dung do chủ shop soạn ở trang quản trị. Hệ thống không tự gửi tin, chỉ chuẩn bị danh sách, nội dung, gợi ý nhóm và ghi các bước do người gửi đánh dấu (đã nhắn, đã kết bạn, đã vào nhóm, từ chối). Rủi ro đã nêu: tin tư vấn là quảng cáo (khác BR-16), nhắn người chưa đồng ý nhận tin (D3), Zalo có thể hạn chế tài khoản phụ. Chi tiết ở `docs/BAN-GIAO.md` |
| D14 | **22/11/2026: bản đồ khách dùng bản đồ thật, thêm lớp thời tiết chạy theo giờ** | Thay sơ đồ tự vẽ của D12 bằng Leaflet (đóng gói sẵn, không CDN) + nền OpenStreetMap + ranh giới 63 tỉnh Natural Earth (miền công cộng). Ô nền tải từ `tile.openstreetmap.org` ngay trên trình duyệt: chỉ lượng dùng nhỏ, có ghi nguồn, giới hạn zoom 12; máy chủ cửa hàng không gọi OSM và không có dữ liệu khách nào đi ra. Lớp thời tiết chạy 48 giờ (nhiệt độ/mưa/mây) dùng lại MET Norway của D12, hỏi cả 63 tỉnh (chỉ tọa độ tỉnh lỵ). Không dùng RainViewer (chỉ miễn phí cho cá nhân/học tập, chỉ 2 giờ đã qua, phóng to tối đa mức 7, giới hạn 100 lượt/phút/địa chỉ mạng) và Open-Meteo (bản miễn phí chỉ phi thương mại). Vẫn MẶC ĐỊNH TẮT thời tiết |
| D15 | **22/11/2026: khách đồng ý kết bạn từ tin nhắn của tài khoản phụ thì thêm vào nhóm phù hợp ngay** (mục 1I) | Chủ shop chốt: bước "kết bạn" và bước "vào nhóm" của D13 gộp thành một luồng, không để khách đã kết bạn nằm chờ. Vẫn **bán tự động**: người thật (mẹ) thêm khách vào nhóm bằng Zalo; hệ thống gợi ý nhóm đúng khu vực (D11), giữ chỗ, nhắc việc và ghi lại. **Chưa code.** Hệ thống không tự gửi tin, không tự kết bạn, không tự thêm vào nhóm, không đăng nhập Zalo; nếu sau này có kênh chính thức thì thay các bước tay, giữ nguyên quy tắc. Còn câu hỏi mở cần chủ shop trả lời (mục 1I.6) |

**Việc chủ cửa hàng đang giữ:** (1) đăng ký hộ kinh doanh; (2) hỏi eSMS và SpeedSMS: mẫu "mời vào nhóm Zalo" thuộc loại tin nào, có gắn link được không, giá mỗi tin, phí brandname, và **cá nhân chưa đăng ký hộ kinh doanh có đăng ký brandname được không**; (3) xuất file mẫu Viettel Post và J&T. Nhà cung cấp SMS chưa được chọn.

---

## 1. Những điểm file 02 sai hoặc thiếu

| # | File 02 nói / bỏ sót | Kết quả tra cứu lại | Hệ quả |
|---|---|---|---|
| 1 | Zalo chỉ có 2 đường: link nhóm thủ công + `zca-js` không chính thức | **Thiếu hẳn các kênh chính thức:** (a) **GMF** — nhóm chat do Zalo OA sở hữu, có API tạo nhóm, trả `group_link`, webhook tin nhắn nhóm `user_send_group_text` **[ĐÃ ĐỌC]**; (b) **ZBS Template Message** (hợp nhất UID giao dịch + UID truyền thông + ZNS từ 01/01/2026), gửi theo **SĐT** **[ĐÃ ĐỌC]**; (c) **Zalo Bot Platform** chính thức (`bot-api.zaloplatforms.com`, token qua "Zalo Bot Creator") **[ĐÃ ĐỌC]** | Có thể làm toàn bộ V1 **không cần** thư viện không chính thức. Đây là thay đổi lớn nhất. |
| 2 | SMS (eSMS) là kênh mời chính | SMS quảng cáo theo NĐ 91/2020 cần **khách đăng ký trước**, nhãn `[QC]`/`[AD]`, tên định danh, ≤3 tin/24h, 07:00–22:00, lưu nhật ký ≥1 năm **[NGUỒN PHỤ]**. Khách cũ chưa từng đăng ký ⇒ SMS mời gần như bị chặn. Chính sách ZBS Template (cập nhật 09/07/2026) lại **cho phép nhắn người đã mua hàng**, nêu rõ "thông báo hậu mãi cho khách hàng" là nội dung hợp lệ, chứng từ chấp nhận gồm "biên bản giao nhận/phiếu bảo hành" **[ĐÃ ĐỌC]** | Kênh mời ưu tiên đổi thành **ZBS Template (Hậu mãi)**; SMS hạ xuống dự phòng. Dữ liệu vận đơn chính là chứng từ. |
| 3 | `consent_status = UNKNOWN/OPTED_IN/OPTED_OUT` | Quá thô. Luật 91/2025 + NĐ 356/2025 (hiệu lực 01/01/2026): đồng ý **theo từng mục đích**, im lặng ≠ đồng ý, phải **lưu bằng chứng** (ai, lúc nào, kênh nào, nội dung đã hiển thị). NĐ 330/2026 (19/08/2026): xử lý không có đồng ý bị phạt 30–50 triệu **[NGUỒN PHỤ — cần đối chiếu Công báo]** | Thay bằng **sổ đồng ý + sổ từ chối** có bằng chứng, xem mục 3.2. |
| 4 | Không nhắc nghĩa vụ liên quan dữ liệu ra nước ngoài, chatbot AI | Lưu trên VPS/đám mây nước ngoài và gọi LLM ở nước ngoài có thể là "chuyển dữ liệu xuyên biên giới" (hồ sơ riêng 60 ngày). Hộ kinh doanh **được miễn** hồ sơ đánh giá tác động và chỉ định nhân sự bảo vệ dữ liệu, nhưng **vẫn phải** xử lý yêu cầu quyền của khách đúng hạn (phản hồi 2 ngày làm việc; xóa 20 ngày…). NĐ 356 Đ.10 về xử lý tự động có thể chạm tới chatbot **[NGUỒN PHỤ, nguồn tự nêu chưa chắc]** | Chọn **VPS đặt tại Việt Nam**; che PII trước khi gọi LLM; có chức năng xuất/xóa dữ liệu một khách; hỏi luật sư trước khi chạy thật. |
| 5 | "Java 21 + Spring Boot bản ổn định" | Spring Boot **4.0** ra 20/11/2025, **4.1** ra 10/06/2026; hỗ trợ OSS của 4.0 hết 31/12/2026; **Java 25 LTS** hỗ trợ hạng nhất **[ĐÃ ĐỌC / NGUỒN PHỤ]** | Dùng **Java 25 + Spring Boot 4.2.x** (khi dựng mốc 1A, Spring Initializr liệt kê **4.2.0** là bản ổn định mới nhất, mới hơn 4.1). Không dùng nhánh 3.x. |
| 6 | Viettel Post: "chưa xác minh được xuất file" | Hướng dẫn chính thức xác nhận cổng quản lý vận đơn có **tìm theo SĐT người nhận, lọc theo ngày tạo, "Xuất excel"** với cột: mã vận đơn, mã đơn hàng, ngày tạo, thông tin người gửi/nhận, hàng hóa, cước, **trạng thái** **[ĐÃ ĐỌC]**. 12 trạng thái: Mới tạo, Chờ lấy, Đã lấy, Đang vận chuyển, Đang giao, **Giao thành công**, Chờ xử lý, **Đã duyệt hoàn**, **Đang chuyển hoàn**, Phát tiếp, **Đã trả**, **Đã hủy** | POC-01 cho Viettel Post cơ bản đạt trên giấy; vẫn cần file mẫu thật để chốt tên cột. **Mã đơn hàng** riêng với mã vận đơn ⇒ khả thi cho BR-03 (gộp kiện). |
| 7 | J&T: export | **Không tìm thấy** tài liệu chính thức về xuất danh sách (chỉ có "Tải lên hàng loạt" để tạo đơn) **[CHƯA KIỂM CHỨNG]** | Vẫn là rủi ro lớn nhất của mốc import; xin file mẫu thật hoặc nhờ hotline J&T. |
| 8 | zca-js "rủi ro bị hạn chế" | Tài liệu của chính thư viện nói rõ dùng nó **trái chính sách Zalo** và có thể bị vô hiệu hóa tài khoản **[ĐÃ ĐỌC]**; tháng 4/2026 Zalo đổi cơ chế E2EE làm lỗi gửi tin (issue #328, mã 123) **[ĐÃ ĐỌC]**. Mặt tích cực: v2.3.0 mới phát hành 7 ngày trước, 107 dependents, MIT, có sẵn `joinGroupLink`, `getGroupLinkInfo`, `getGroupInfo` (có `totalMember`, `pendingApprove`), `createGroup`, `addGroupDeputy`, `changeGroupOwner` **[ĐÃ ĐỌC tên hàm; hành vi CHƯA kiểm chứng]** | Là phương án **cuối cùng**, không phải mặc định. Nếu dùng: bot có thể bị khóa bất kỳ lúc nào, mất toàn bộ vai trò bot trong các nhóm. |
| 9 | Kiến trúc có sidecar Node.js bắt buộc | Nếu chọn đường chính thức (GMF/ZBS/Bot API) thì Java gọi thẳng REST + nhận webhook, **không cần Node** | Bỏ `zalo-bridge` khỏi mặc định; chỉ thêm nếu cuối cùng chọn zca-js. |
| 10 | Không ước chi phí | Xem mục 2.3 | Chi phí GMF có thể là điểm quyết định. |

**Phần file 02 vẫn đúng:** `zca-zalo-service` license "Private" (không dùng); n8n/Chatwoot chưa cần ở V1; monolith + Postgres + Docker Compose; nguyên tắc DRY_RUN, không gộp click với join; thuật toán chia nhóm; danh sách module.

---

## 2. Các kênh Zalo: so sánh sau tra cứu lại

### 2.1 Kênh MỜI khách (từ danh sách SĐT)

| Phương án | Điều kiện | Chi phí | Hợp luật/ToS | Ghi chú |
|---|---|---|---|---|
| **A. ZBS Template "Hậu mãi" theo SĐT (ưu tiên)** | OA doanh nghiệp/hộ kinh doanh **đã xác thực** (cần Giấy ĐKKD hoặc công văn xác thực), tài khoản ZBS, gói OA (gửi qua API cần gói Tăng trưởng 2,5 triệu/năm; gói Tiêu chuẩn 1 triệu/năm chỉ gửi qua "công cụ chiến dịch", có hạn chế), mẫu tin **được Zalo duyệt 2–3 ngày** | ~200đ/tin thành công + nút bấm đầu tiên 0đ (chưa gồm VAT) | Có căn cứ chính sách **[ĐÃ ĐỌC]**. Mẫu phải gắn giao dịch đã phát sinh và có tham số (tên khách, mã đơn…), không được là quảng cáo trơn. Zalo có thể tiền kiểm/hậu kiểm chứng từ (≤1% mẫu trong 7 ngày); tạm ngừng nếu khiếu nại >5% hoặc >50/24h | Khách không dùng Zalo/SĐT không gắn Zalo ⇒ không gửi được (không bị tính phí). Phù hợp BR-16 nếu nội dung là "hỗ trợ sau mua". **[CHƯA KIỂM CHỨNG]** Zalo có duyệt mẫu "mời vào nhóm hỗ trợ" không. |
| B. Mẹ gửi thủ công link nhóm/nhắn Zalo cá nhân | Không | 0 | Không tự động nên ít rủi ro nền tảng; vẫn phải theo luật dữ liệu | Fallback bắt buộc phải có; hệ thống chỉ xuất danh sách + link riêng. |
| C. SMS | Brandname/tên định danh, `[QC]`, khách đã đăng ký trước | Theo nhà cung cấp **[CHƯA KIỂM CHỨNG]** | Khó với khách cũ chưa đồng ý | Chỉ dùng cho khách đã **opted-in có bằng chứng**. |

### 2.2 Kênh NHÓM + BOT

| Phương án | Cách hoạt động | Ưu | Nhược / XUNG ĐỘT với nghiệp vụ |
|---|---|---|---|
| **P1. GMF chính thức (OA làm bot)** | OA tạo nhóm qua API (`creategroupwithoa`), nhận `group_link`; OA trả lời qua API; webhook nhận tin nhóm. OA tên "Trợ lý cửa hàng…" = đúng BR-11 (bot công khai). Gói OA có tối đa 5–15 tài khoản nhân viên trên OA Manager ⇒ **cho V2 gần như có sẵn** | Chính thức, không bị khóa vì ToS, có SLA, có webhook | **[XUNG ĐỘT BR-11]** nhóm do **OA sở hữu**, "trưởng nhóm" không phải mẹ; mẹ chỉ là thành viên (có thể làm phó nhóm — **CHƯA KIỂM CHỨNG**). **[XUNG ĐỘT BR-08/tiêu chí 7]** vào bằng link thì **cần trưởng/phó nhóm phê duyệt** từng người; chỉ khi OA mời trực tiếp mới vào thẳng và chỉ mời được người đã quan tâm OA. Phí: GMF-10 (tối đa 10 thành viên kể cả OA — đủ cho 5 khách + mẹ + OA) **25.000đ/nhóm/tháng**; gói Tăng trưởng/Toàn diện kèm 1/3 nhóm GMF-100 miễn phí (cần xác nhận); tin OA→nhóm miễn phí đến 31/12/2026, sau đó **chưa rõ giá**. Nhóm hết hạn không gia hạn bị giải tán sau 45 ngày; **OA không thể rời nhóm** |
| P2. Nhóm Zalo cá nhân (mẹ là trưởng nhóm) + bot cá nhân bằng `zca-js` | Đúng mô tả BR-07/08/09/11 | Miễn phí, khớp nghiệp vụ nhất | **Trái chính sách Zalo**, bot có thể bị khóa, API đổi bất ngờ (đã xảy ra 04/2026). Nhóm còn nguyên khi bot bị khóa (mẹ vẫn là trưởng nhóm) |
| P3. Zalo Bot Platform chính thức | Bot (tên bắt đầu bằng "Bot") qua token, `getUpdates`/webhook, `sendMessage` | Chính thức, miễn phí đăng ký | Tài liệu chính thức tôi đọc chỉ liệt kê tin nhắn 1–1 (getMe, sendMessage, getUpdates, setWebhook…). Plugin bên thứ ba **nói** hỗ trợ nhóm (cần @mention) ⇒ **[CHƯA KIỂM CHỨNG]** bot có thêm vào nhóm cá nhân được không, không @mention có đọc được không |
| P4. V1 chỉ nhóm cá nhân, **không bot** | Mẹ tự làm hết; hệ thống chỉ quản lý danh sách/link/token | Đơn giản nhất, đúng "mẹ dùng Zalo thật" | Chưa có AI |

**Khuyến nghị:** Không cần quyết ngay. Mốc 1A–1D (import, phân loại, nhóm, mời) **độc lập** với việc chọn P1/P2/P3. Quyết định ở mốc 1E sau một spike POC (mục 4, mốc 1E). Mặc định tạm: **P4 → nâng cấp P1 hoặc P3** nếu POC đạt; **P2 chỉ khi chủ cửa hàng chấp nhận rủi ro khóa tài khoản bằng văn bản**.

### 2.3 Ước tính chi phí thô (giả định, cần xác nhận)

- OA Tăng trưởng: 2.500.000đ/năm (~208k/tháng) nếu cần API.
- ZBS Template: 400 khách × ~200đ ≈ **80.000đ + VAT** cho một đợt (chỉ tin thành công).
- GMF-10: nếu 20–40% khách ONE_TIME vào nhóm ⇒ ~16–32 nhóm × 25.000đ ≈ **0,4–0,8 triệu/tháng** (trừ phần nhóm miễn phí trong gói). Đây là khoản lặp lại duy nhất đáng kể.
- VPS Việt Nam: ước chừng vài trăm nghìn/tháng. LLM: tùy mức dùng, đặt hạn mức chi tiêu.

---

## 3. Điều chỉnh thiết kế

### 3.1 Stack (giữ file 02, sửa phiên bản)
Java 25 LTS + Spring Boot 4.2.x, Spring Security, JDBC (`JdbcClient`, SQL thuần) + Flyway, PostgreSQL, Thymeleaf (chưa cần HTMX), Postgres nhúng (Zonky) cho test, Docker Compose, reverse proxy HTTPS, VPS tại Việt Nam. Chuẩn hóa SĐT bằng thư viện libphonenumber (không tự viết regex). Đọc Excel/CSV bằng Apache POI/FastExcel + commons-csv. Node.js `zalo-bridge`: **chỉ nếu** chọn P2.

### 3.2 Mô hình đồng ý (thay `consent_status`)
Bảng `contact_basis`: `customer_id`, `purpose` (`GROUP_INVITE_POSTSALE` | `MARKETING` | `CARE_CALL`…), `basis` (`TRANSACTION_POSTSALE` | `OPT_IN` | `NONE`), `status` (`ELIGIBLE` | `BLOCKED` | `OPTED_OUT`), `evidence_ref` (ví dụ id vận đơn giao thành công hoặc bản ghi đăng ký), `channel`, `text_version`, `recorded_at`, `recorded_by`. Bảng `suppression` (từ chối/yêu cầu ngừng) kiểm tra **trước mọi lần gửi**. Mặc định không có dòng = **không gửi**. Mọi lần gửi ghi `send_log` (giữ ≥1 năm) kèm mẫu tin và kết quả.

### 3.3 Cổng chặn gửi thật
Cấu hình `SENDING_ENABLED=false` mặc định; chỉ bật qua biến môi trường bởi chủ cửa hàng; `DRY_RUN` luôn bật ở DEV/CI; hạn mức theo lô (ví dụ ≤20/lô ở pilot); nút "tạm dừng tất cả" ghi audit.

---

## 4. KẾ HOẠCH THỰC HIỆN CODE

Quy ước độ lớn: **S** (nhỏ), **M** (vừa), **L** (lớn). Không ghi giờ vì phụ thuộc vào file mẫu và duyệt mẫu Zalo. Chưa có repo nên chưa có mẫu code hiện hữu để bám theo; các quy ước (đặt tên, test, lỗi) sẽ do mốc 1A thiết lập rồi các mốc sau bám theo.

### Cấu trúc repo đề xuất
```
/app            Spring Boot (modules: imports, shipping, customers, consents, invitations, groups, bot, reports, security)
/infra          docker-compose.yml, reverse-proxy, backup scripts, .env.example
/docs           01, 02, 03 + runbook cho bố mẹ
/fixtures       dữ liệu giả (KHÔNG PII thật)
/zalo-bridge    (chỉ tạo nếu chọn P2)
```

---

# GIAI ĐOẠN 1 — CHỈ CÓ BỐ MẸ (không nhân viên)

Mục tiêu: dữ liệu khách sạch → mời đúng người, đúng luật → nhóm Zalo nhỏ → (tùy chọn) bot hỗ trợ. **Mẹ luôn dùng Zalo thật; hệ thống hỏng không được làm mẹ không làm việc được (tiêu chí 9).**

## 1A. Nền tảng + Nhập & phân loại đơn (L) — LÀM ĐẦU TIÊN
**Đầu vào cần có:** 1 file mẫu mỗi hãng đã ẩn PII (Mốc 0 bên dưới).
- Khởi tạo repo, CI, Flyway, Testcontainers, đăng nhập admin, cấu hình `DRY_RUN`.
- Nhập Excel/CSV: upload → **cấu hình ánh xạ cột theo từng hãng (lưu được)** → xem trước → lỗi theo dòng → lưu bản gốc (`raw_shipments`) → nhập idempotent theo (nguồn + mã vận đơn) và `sha256` file.
- Bảng ánh xạ trạng thái → `canonical_status` riêng cho Viettel Post (12 trạng thái đã biết) và J&T (sau khi có file mẫu). Trạng thái lạ ⇒ `UNKNOWN`, không tính là thành công.
- Chuẩn hóa SĐT (libphonenumber, giữ giá trị gốc), không tự đoán số thiếu/sai ⇒ vào danh sách lỗi.
- `purchase_key`: ưu tiên mã đơn hàng của hãng; không có ⇒ đưa vào "cần kiểm tra", không gộp theo ngày/giá.
- Đếm lần mua thành công, phân loại `ONE_TIME`/`REPEAT`, xem được **từng đơn đã tạo ra kết quả**; xuất CSV hai danh sách; cập nhật lại khi nhập đợt mới (đơn đã giao rồi chuyển hoàn).
- **Nghiệm thu (tiêu chí 1–5 của file 01):** nhập cùng file 2 lần không tăng; VTP+J&T cùng SĐT ⇒ `REPEAT`; 1 giao + 2 hủy/hoàn ⇒ `ONE_TIME`; trạng thái chưa rõ ⇒ không tính; xem/xuất hai danh sách. Có test tự động cho từng điểm.
- **Phụ thuộc:** file mẫu. Nếu chưa có J&T: làm Viettel Post trước, J&T là adapter thứ hai (interface `ShipmentFileAdapter`).

## 1B. Hồ sơ liên hệ + danh sách chăm sóc (M)
- Sổ cơ sở liên hệ và danh sách từ chối (mục 3.2), màn hình duyệt "đủ điều kiện mời".
- Danh sách `REPEAT` riêng cho bố mẹ (tên, địa chỉ, số đơn, lần mua gần nhất, ghi chú tay đơn giản) — **không có đường nào đưa REPEAT vào chiến dịch mời** (test chặn).
- Dashboard tổng quan: số đơn theo hãng, khách duy nhất, ONE_TIME/REPEAT, lỗi nhập.
- Chức năng quyền dữ liệu của một khách: xem, xuất, xóa an toàn, rút đồng ý (có bộ đếm hạn phản hồi).
- **Nghiệm thu:** tiêu chí 6; ONE_TIME chưa `ELIGIBLE` không xuất hiện trong danh sách gửi.

## 1C. Nhóm Zalo + lời mời ở chế độ DRY_RUN (M)
- CRUD nhóm: tên, link (nhập tay), mục tiêu ~5 khách, số khách thực tế (nhập tay), trạng thái; **không tính tài khoản mẹ và bot** vào số khách (tiêu chí 8).
- Chiến dịch mời: chọn lô, sinh token riêng `/i/<token>` (không lộ SĐT, có hạn), trang đích rất đơn giản cho người lớn tuổi (chữ to, 1 nút), chuyển hướng tới nhóm theo thuật toán mục 7 file 02; nếu chưa có nhóm ⇒ thông báo liên hệ cửa hàng.
- Ghi nhận **lượt bấm** (không phải "đã vào nhóm"), nút tạm dừng tất cả, giới hạn lô.
- **Nghiệm thu:** không cần ghép Zalo ID ↔ SĐT; click ≠ join; không đổ hết vào một nhóm; link hết hạn xử lý đúng.

## 1D. Gửi thật theo lô nhỏ — pilot (M) — CỔNG PHÁP LÝ
Điều kiện bắt đầu (chủ cửa hàng làm): xác thực OA, nạp ZBS, duyệt mẫu ZBS Template, xác nhận pháp lý.
- Interface `InvitationSender` với 4 bản cài: `Mock`, `ManualExport` (xuất danh sách + link cho mẹ), `ZbsTemplate`, `Sms` (dự phòng, chỉ cho `OPT_IN`).
- Pilot 10–20 khách ONE_TIME đủ điều kiện, theo dõi: gửi thành công, lỗi (không có Zalo), phản hồi, khiếu nại, chi phí thực.
- Chỉ mở rộng lô khi khiếu nại thấp; ngưỡng dừng tự động (gần ngưỡng 5% của Zalo).
- **Nghiệm thu:** không gửi cho `BLOCKED/OPTED_OUT`; gửi lại cùng người không trùng; có `send_log`.

## 1E. Bot AI (L) — spike POC trước, quyết định sau
**Spike (S–M), có giới hạn thời gian, KHÔNG chạy trên tài khoản mẹ:**
1. P1: tạo OA test, thử tạo GMF-10 test, lấy link, thử mời/duyệt vào nhóm, nhận webhook `user_send_group_text`, thử đặt mẹ làm phó nhóm, thử API duyệt thành viên.
2. P3: tạo Bot qua Zalo Bot Creator, thử thêm vào nhóm test, thử đọc tin nhóm có/không @mention.
3. P2 (chỉ nếu chủ cửa hàng đồng ý rủi ro): `zca-js` khóa phiên bản, tài khoản phụ dùng riêng.
→ Ghi **ADR** (quyết định kiến trúc) với bảng kết quả; chủ cửa hàng chọn.
**Sau khi chọn:**
- Cổng `BotChannel` (adapter theo phương án chọn); lõi (`bot` module) độc lập kênh.
- Rules + FAQ + chính sách do cửa hàng duyệt; LLM tùy chọn qua `AiProvider` (có `Mock`), **che SĐT/địa chỉ trước khi gọi**, timeout, hạn mức chi.
- Nhận diện "muốn mua / khiếu nại / cần người thật" ⇒ tạo `handoff` (OPEN→ACK→RESOLVED) và **thông báo mẹ** (kênh thông báo cần chốt: ví dụ tin Zalo riêng từ bot/OA tới mẹ). Bot không tự chốt đơn, không hỏi/ghi địa chỉ, SĐT, thẻ trong nhóm công khai.
- Chặn tự trả lời chính mình, trùng `message_id`; bot ngừng khi mẹ đã trả lời; `bot_enabled` theo nhóm + toàn hệ thống; `DRY_RUN` ghi "sẽ trả lời gì".
- Lộ trình bật: DRY_RUN → nhóm test → từng nhóm thật.
- **Nghiệm thu:** tiêu chí 9–10; kill switch tức thì; bot hỏng không ảnh hưởng mẹ.

## 1F. Đóng gói V1 + bàn giao (M)
- Docker Compose một VPS tại Việt Nam, HTTPS, sao lưu tự động + **thử khôi phục**, log che PII, secrets ngoài git, `.env.example`.
- Runbook tiếng Việt cho bố mẹ: nhập file, xem danh sách, thêm link nhóm, cách tắt bot, cách xử lý khi khách yêu cầu ngừng/xóa.
- Rà soát bảo mật (không public SĐT/địa chỉ, giới hạn file upload, đăng nhập, rate limit).
- **Cổng kết thúc GĐ1:** ổn định liên tục một thời gian do chủ cửa hàng quyết; chỉ khi đó mới bắt đầu GĐ2.

## 1I. Kết bạn → vào nhóm ngay (D15) (S–M) — CHỈ GHI PHẠM VI, CHƯA CODE
**Nghiệp vụ (chủ shop chốt 22/11/2026):** khi khách đồng ý kết bạn với **tài khoản Zalo phụ** (sau tin tư vấn của D13), khách được **thêm vào nhóm Zalo phù hợp luôn**, theo phân vùng đã có (D11). Mốc này là phần mở rộng của D13, nằm trong phạm vi giai đoạn 1, và **dùng đúng cách bán tự động của D13**: người thật thao tác trên Zalo, hệ thống chỉ gợi ý, giữ chỗ, nhắc việc và ghi lại. Không xây tự gửi tin, tự kết bạn hay tự thêm vào nhóm; không đăng nhập Zalo; không làm thủ thuật né phát hiện (D5).

**1I.1 Hiện đã có (D13, đã code) và phần còn thiếu**
- Đã có: trạng thái lời mời `READY → SENT → FRIENDED → JOINED` (V12), nút *Đã kết bạn*, *Đã vào nhóm* (gợi ý nhóm cùng tỉnh > vùng > miền > chung, tăng số khách thật của nhóm), *Khách từ chối* (vào danh sách từ chối vĩnh viễn), lô nhắn hôm nay, đợt chạy thử `/test-runs`.
- Còn thiếu so với nghiệp vụ mới (đây là phạm vi của mốc này): (a) hai bước kết bạn và vào nhóm đang rời nhau, chưa có thao tác gộp và chưa có gợi ý nhóm **ngay lúc** đánh dấu kết bạn; (b) nhóm chưa được **giữ chỗ** khi đánh dấu kết bạn, nên hai khách kết bạn liền nhau có thể cùng bị gợi ý vào một nhóm gần đầy; (c) chưa có việc nhắc "đã kết bạn mà chưa thêm vào nhóm"; (d) khách kết bạn khi chưa có nhóm hợp mới chỉ bị báo lỗi, chưa ghi vào "đang chờ nhóm" như đường QR (D11); (e) khách rời nhóm chưa làm giảm số khách của nhóm.

**1I.2 Quy tắc nghiệp vụ**
| # | Quy tắc |
|---|---|
| R1 | **Điểm kích hoạt:** khách bấm đồng ý kết bạn với tài khoản phụ. Chỉ người cầm tài khoản phụ thấy được điều này trên Zalo, nên người đó bấm *Đã kết bạn* trên trang quản trị. Hệ thống không tự biết khách đã kết bạn. |
| R2 | **Chỉ thêm khách đã kết bạn, đã được nhắn và chưa từ chối.** Khách chỉ có số điện thoại, chưa kết bạn, hoặc nằm trong danh sách từ chối thì không bao giờ được thêm vào nhóm. Khách `REPEAT` không có lời mời nên không vào luồng này (BR-06). |
| R3 | **Gợi ý nhóm ngay khi bấm *Đã kết bạn*:** nhóm đang mở, có link, còn chỗ, ưu tiên cùng tỉnh > cùng vùng > cùng miền > nhóm chung; nhóm miền khác bị loại (D11, `app.grouping.allow-other-region` mặc định tắt). Màn hình hiện tên nhóm, khu vực, số khách hiện có và link nhóm. |
| R4 | **Giữ chỗ:** nhóm gợi ý được giữ một chỗ cho khách đó đến khi bấm *Đã vào nhóm* hoặc hết hạn giữ chỗ, để nhiều khách kết bạn cùng ngày không bị dồn vào một nhóm. Hết hạn giữ chỗ thì gợi ý lại từ đầu. Thời hạn mặc định **24 giờ (giả định)**, chỉnh được. |
| R5 | **Thao tác một lần:** có nút gộp *Đã kết bạn và đã thêm vào nhóm* cho trường hợp người gửi thêm khách vào nhóm ngay trong lúc đang làm. Hai nút tách vẫn giữ cho trường hợp thêm muộn. |
| R6 | **Mục tiêu thời gian:** thêm khách vào nhóm **trong cùng ngày kết bạn** (giả định, để khách không quên mình đã kết bạn). Khách đã kết bạn quá **2 ngày (giả định)** mà chưa vào nhóm hiện thành việc trên "Việc cần làm hôm nay". Nhắc chỉ trên web, không nhắn khách thêm. |
| R7 | **Chưa có nhóm hợp** (chưa có nhóm khu vực, hoặc đã đầy): không ghi `JOINED`, **không đẩy sang nhóm miền khác**, khách giữ trạng thái đã kết bạn kèm nhãn "chờ nhóm". Hệ thống ghi một dòng "đang chờ nhóm" theo mã tỉnh (như đường QR, không kèm SĐT/tên) để mẹ biết cần mở nhóm ở khu vực nào. Khi mẹ mở nhóm mới đúng khu vực, các khách đang chờ hiện thành việc để thêm vào. |
| R8 | **Khách từ chối ở bất kỳ bước nào** (không kết bạn, nhắn "ngừng", báo muốn ra khỏi nhóm): ghi vào danh sách từ chối vĩnh viễn, không nhắn lại, không thêm vào nhóm nữa. Nếu đã ở trong nhóm, mẹ mời ra tay và số khách thật của nhóm giảm 1. |
| R9 | **Mỗi người chỉ nhận một tin chủ động** (D13). Lời chào trong nhóm và trả lời khi khách nhắn tới là việc của mẹ, không phải tin chiến dịch. Khách nhắn tới thì được trả lời bình thường. |
| R10 | **Khách không phản hồi:** đã nhắn quá **14 ngày (giả định)** mà chưa kết bạn thì chỉ hiện nhãn "không phản hồi" để mẹ biết; **không nhắn lại** (file 01 mục 6: không nhắn thúc ép lặp lại). |
| R11 | **Đếm khách:** khách vào nhóm theo luồng này tính +1 số khách thật của nhóm; tài khoản mẹ, trợ lý AI và tài khoản phụ **không** tính là khách (tiêu chí 8). Bấm hai lần không tăng hai lần. Mẹ vẫn đếm lại thành viên mỗi tuần (giả định đã có). |
| R12 | **Quyền riêng tư trong nhóm:** các khách trong nhóm thấy tên Zalo của nhau. Không đăng SĐT, địa chỉ hay thông tin đơn hàng của ai trong nhóm; hệ thống không hiện tên đầy đủ khách khác ở trang của một khách. Nhóm giữ nhỏ (~5 khách, BR-07). |
| R13 | **Việc cấm:** hệ thống không tự gửi tin hay kết bạn hay thêm người vào nhóm, không đăng nhập tài khoản phụ, không dùng tài khoản của mẹ làm tài khoản gửi, không dùng công cụ tự động trên Zalo, không giả lập thao tác. Nếu sau này có kênh chính thức (xem 1I.5), nó chỉ thay các bước tay mà **không đổi** R1–R12. |

**1I.3 Trạng thái khách trong luồng**
| Trạng thái | Nghĩa | Chuyển khi | Hệ thống ghi |
|---|---|---|---|
| `READY` | Nằm trong chiến dịch, chưa nhắn | Tạo chiến dịch (chỉ khách đủ điều kiện, mỗi người một lần) | — |
| `SENT` | Đã nhắn bằng tài khoản phụ | Người gửi bấm *Đã nhắn* trong giờ/hạn mức | `send_log` kênh `ZALO_PHU` |
| `FRIENDED` | Đã đồng ý kết bạn, **chờ thêm vào nhóm** | Bấm *Đã kết bạn* (R1) | `friended_at`, giữ chỗ nhóm gợi ý (R4), sự kiện `FRIENDED`. Nhãn phụ **"chờ nhóm"** nếu chưa có nhóm hợp (R7), **"quá hạn thêm vào nhóm"** nếu quá 2 ngày (R6), **"không phản hồi"** ở nhánh `SENT` quá 14 ngày (R10); các nhãn này suy ra từ dữ liệu, **không bắt buộc thêm giá trị trạng thái mới** |
| `JOINED` | Đã thêm vào nhóm | Bấm *Đã vào nhóm* hoặc nút gộp (R5) | `group_id`, `joined_at`, +1 số khách của nhóm, sự kiện `JOINED_GROUP` |
| `CANCELLED`, từ chối | Không còn trong luồng | Khách từ chối hoặc bị hủy chiến dịch (R8) | Danh sách từ chối (chỉ mã băm), nhóm giảm 1 nếu đã vào |
Khách vào bằng mã QR chung (D10) có đường riêng (`CLICKED`); hai đường không chồng nhau trên cùng một khách.

**1I.4 Phụ thuộc**
- Code D13 hiện có (`InviteService.markFriended/markJoined`, V12, V14, `SendService.today`, `TestRunService`) và gợi ý nhóm của D11 (`GroupService.pickGroup`, khu vực nhóm).
- **Có nhóm Zalo thật theo từng khu vực**, đang mở, có link: nếu không, khách rơi vào "chờ nhóm" (R7). Mẹ phải mở nhóm trước khi chạy chiến dịch cho khu vực đó.
- **Người thêm khách vào nhóm phải có quyền thêm thành viên** (xem câu hỏi Q1). **[CHƯA KIỂM CHỨNG]** thành viên thường của nhóm Zalo cá nhân có thêm được người khác không, và Zalo có giới hạn số lần kết bạn/thêm người mỗi ngày không. Phải thử bằng đợt chạy thử `/test-runs` với số điện thoại tự nguyện trước khi dùng cho khách thật; không đoán.
- Sổ từ chối, cổng chặn gửi, hạn mức lô và giờ nhắn (D5), chiến dịch theo khu vực (V14). Hai tài khoản web Bố/Mẹ (Đ1) giúp nhật ký ghi đúng người bấm, nhưng không chặn mốc này.

**1I.5 Hướng kênh chính thức (nếu có, không làm bây giờ)**
Nếu sau này chủ shop có OA/ZBS và chọn GMF (mục 2.1, 2.2; ADR-0001 chưa quyết): tin mời đi qua ZBS có duyệt mẫu, khách đồng ý thì OA mời vào nhóm GMF. Khi đó "kết bạn" được thay bằng "khách quan tâm OA" hoặc "khách bấm đồng ý vào nhóm", bằng chứng đồng ý ghi theo `contact_basis`. Nhưng GMF làm lệch BR-08/BR-11 (OA sở hữu nhóm, phê duyệt từng người), nên **chủ shop quyết** trước. Trạng thái và quy tắc R1–R12 giữ nguyên, chỉ đổi người thực hiện từ tay sang kênh chính thức. Hiện **không xây** gì cho hướng này.

**1I.6 Câu hỏi còn mở (cần chủ shop trả lời trước khi code)**
- **Q1. Ai là người thêm khách vào nhóm?** (a) Tài khoản phụ: phải là thành viên nhóm và có quyền thêm; nhóm có thêm một tài khoản cửa hàng (mẹ, trợ lý AI, tài khoản phụ), cần sửa cách đếm "không tính tài khoản cửa hàng". (b) Mẹ: mẹ phải kết bạn được với khách, trong khi D13 chỉ dùng tài khoản phụ và mẹ không kết nối hệ thống. Đề xuất: (a), xác nhận bằng chạy thử.
- **Q2. Khách chỉ đồng ý kết bạn đã đủ để thêm vào nhóm chưa?** Thêm người vào nhóm cùng các khách lạ khác là bước lớn hơn kết bạn. Đề xuất: tin đầu nói rõ "kết bạn xong sẽ mời vào nhóm cùng khu vực (tối đa vài người), muốn ra thì nhắn một câu" (R12, R8) và người gửi không thêm nếu khách có ý ngần ngại. Chủ shop xác nhận cách làm và soạn lại mẫu tin.
- **Q3. Khi chưa có nhóm khu vực:** chờ mẹ mở nhóm (đề xuất, R7), hay cho vào nhóm chung/miền khác (trái D11 mặc định)? Mẹ mở nhóm mới trong khoảng bao lâu thì chấp nhận được?
- **Q4. Các mốc thời gian giả định:** giữ chỗ 24 giờ, nhắc sau 2 ngày, đóng "không phản hồi" sau 14 ngày: có hợp với nhịp của mẹ không?
- **Q5. Ai cầm tài khoản phụ hằng ngày** (bố, mẹ hay cả hai) và có cần ghi theo người không (Đ1)?
- **Q6. Khách rời nhóm hoặc bị mời ra:** báo hệ thống bằng tay (đề xuất, R8) hay chỉ dựa vào đếm lại hằng tuần?
- **Q7. Tin chào trong nhóm:** mẹ tự viết mỗi lần, hay hệ thống đưa sẵn một câu chào mẫu để mẹ dán? (Không tự đăng.)

**1I.7 Nghiệm thu khi code (chưa làm)**
1. Bấm *Đã kết bạn* hiện ngay nhóm gợi ý đúng khu vực, hoặc báo rõ "chưa có nhóm hợp" và ghi "chờ nhóm", không ghi `JOINED`.
2. Có nút gộp một lần; bấm hai lần không tăng số khách thật hai lần.
3. Hai khách cùng khu vực kết bạn liền nhau không bị gợi ý vào một nhóm chỉ còn một chỗ (giữ chỗ).
4. Khách chưa kết bạn, khách từ chối, khách `REPEAT` không bao giờ được gợi ý nhóm.
5. Không có nhóm cùng miền thì không gợi ý nhóm miền khác (trừ khi bật cấu hình).
6. Khách `FRIENDED` quá hạn hiện thành việc trên "hôm nay"; không có đường nào tự nhắn khách.
7. Đợt chạy thử `/test-runs` chạy được luồng mới mà không đổi dữ liệu thật (giữ test so ảnh chụp trước/sau).
8. Không có mã gọi Zalo hay đăng nhập Zalo trong repo.

**1I.8 Không thuộc mốc này:** tự động gửi tin, tự kết bạn, tự thêm người vào nhóm, đăng nhập Zalo, bộ gửi SMS/ZBS (D9), bot trong nhóm (1E), khách `REPEAT`.

## Mốc 0 (song song, không phải code) — việc chủ cửa hàng cần làm
1. Xuất **1 file mẫu mỗi hãng** (Viettel Post: "Xuất excel" ở Quản lý vận đơn; J&T: hỏi hotline/portal), ẩn PII hoặc thay số giả; chụp màn hình các trạng thái.
2. Quyết: tìm hiểu đăng ký/xác thực **OA** bằng Giấy ĐKKD hộ kinh doanh (cần để dùng ZBS/GMF).
3. Hỏi luật sư/kế toán về cơ sở liên hệ cho khách cũ và nghĩa vụ hộ kinh doanh.
4. Chọn nhà cung cấp VPS tại Việt Nam.

---

# GIAI ĐOẠN 2 — CÓ NHÂN VIÊN ĐẦU TIÊN

**Chỉ bắt đầu khi bố mẹ thật sự tuyển người và GĐ1 ổn định.** Không xây trước.

## 2A. Chốt các quyết định còn mở (S, không code)
- Nhân viên trả lời **dưới tài khoản nào** (OA/bot, mẹ, hay tài khoản nhân viên) — file 01 ghi rõ chưa chốt.
- **Tự xây hộp thư hay dùng OA Manager:** nếu GĐ1 chọn P1 (GMF) thì OA Manager đã có chat nhiều nhân viên (gói Tiêu chuẩn tối đa 5, Tăng trưởng 15, có gắn nhãn/ghi chú/chỉ định nhân viên) ⇒ có thể **không cần tự xây inbox**, chỉ xây phần điều phối + ghi đơn. Nếu chọn P2/P3: tự xây inbox đơn giản (Chatwoot chỉ cân nhắc, không bắt buộc).
- Quyền nhìn thấy PII của nhân viên (mặc định che SĐT/địa chỉ, chỉ hiện khi xử lý đơn).

## 2B. Tài khoản nhân viên + phân quyền (M)
Vai trò `OWNER` (bố mẹ) / `STAFF`; quyền theo trường dữ liệu; `audit_logs` cho xem/xuất PII; thu hồi quyền khi nghỉ việc; không cho nhân viên xuất danh sách khách hàng loạt.

## 2C. Nhiệm vụ theo nhóm Zalo (L)
- Bảng phân công **nhóm → nhân viên** (không phân theo từng khách), chuyển nhóm khi nghỉ/quá tải.
- `handoff` từ bot ⇒ tạo nhiệm vụ cho nhân viên phụ trách nhóm; trạng thái OPEN→ACK→RESOLVED; **khóa nhận việc** để hai người không xử lý trùng; bot ngừng khi người đã nhận.
- Nếu tự xây inbox: gửi/nhận qua adapter kênh đã chọn ở GĐ1; AI gợi ý câu trả lời nhưng nhân viên duyệt.
- **Nghiệm thu:** không trùng trả lời/nhiệm vụ; chuyển nhóm không mất lịch sử; nhân viên chỉ thấy nhóm được giao.

## 2D. Ghi đơn thủ công + liên kết khách khi mua lại (M)
- Form ghi **ý định đặt hàng** (không trừ kho, không tạo vận đơn, không thanh toán): SĐT nhận, sản phẩm, số lượng, ghi chú.
- Khi có đơn mới và khách cung cấp SĐT nhận hàng ⇒ liên kết `zalo_user_id` ↔ khách (BR-10), kèm bằng chứng đơn.
- Nhập đơn mới từ file giao vận cập nhật phân loại; khách ONE_TIME mua lại ⇒ REPEAT (không đuổi khỏi nhóm).

## 2E. Báo cáo & củng cố (S–M)
Thông báo nhiệm vụ quá hạn, báo cáo xử lý theo nhóm, backup/audit; đánh giá lại nhu cầu Chatwoot/n8n chỉ khi vận hành thấy cần.

## 2F. Kết nối API hãng vận chuyển: tự nhận đơn mới và khách mới (M, chủ shop quyết 21/11/2026: để GĐ2)
**Bối cảnh:** GĐ1 nhập đơn bằng file Excel xuất tay (đã kiểm chứng với 6 file Viettel Post + 6 file J&T thật). Khi đơn mới liên tục phát sinh, xuất tay hằng tuần vẫn dùng được; mốc này chỉ thay bước xuất tay bằng kết nối tự động.

**Điều kiện cần làm trước (không phải code, chủ shop làm; chưa có kết quả):**
- **Viettel Post:** hệ thống Open API "Partner" (`partner.viettelpost.vn`), có webhook. Liên hệ nhân viên kinh doanh hoặc CSKH, hai bên thống nhất hợp tác thì được cấp tài khoản Partner. Trang giới thiệu không nói rõ có dành cho shop nhỏ không.
- **J&T Express:** chưa thấy tài liệu công khai. Các phần mềm bán hàng kết nối được sau khi shop **ký hợp đồng** với J&T và được cấp `API Key`, `CustomerId`, `EccompanyId`; webhook đăng ký bằng cách gửi URL cho nhân viên kinh doanh hoặc bưu cục đã ký hợp đồng.
- **Phải hỏi hãng (quyết định có làm được hay không):**
  1. Đơn shop tạo trên app/web của hãng có được đẩy về webhook/API không, hay chỉ đơn tạo qua chính API đó?
  2. Dữ liệu trả về có **SĐT người nhận** không? Không có SĐT thì không tạo được khách (khách định danh bằng SĐT).
  3. Có API lấy lại danh sách đơn cũ không (để lấp khoảng trống khi webhook lỗi)?
  4. Điều kiện và chi phí cấp tài khoản API.
- Nếu hãng trả lời "không" ở câu 1 hoặc 2: **không làm mốc này**, tiếp tục nhập file.

**Thiết kế (khi đủ điều kiện):**
- Một cổng nạp chung `ShipmentIngest` cho mọi nguồn (file, webhook, API kéo định kỳ). Cùng khóa (hãng + mã vận đơn), cùng ghi lịch sử trạng thái, cùng phân loại; nhập lặp không đếm trùng. Logic của mốc 1A không đổi.
- Webhook: HTTPS công khai trên VPS (nên làm sau mốc 1F), xác thực bằng chữ ký hoặc khóa bí mật, trả 200 nhanh rồi xử lý bất đồng bộ, chống gửi lặp, ghi `raw_payload`. Nhà cung cấp lỗi hoặc đổi định dạng thì **dừng và báo**, không đoán.
- Có thể kết hợp kéo định kỳ (reconcile) để bù webhook bị rơi.
- Khóa API lưu ngoài git, không ghi log; không ghi SĐT đầy đủ vào log (D7).
- **Khách mới từ API chỉ vào danh sách, không tự động được mời.** Vẫn phải qua cổng cơ sở liên hệ và sổ từ chối (mốc 1B) trước mọi lần gửi.
- Không đăng nhập vào cổng web của hãng bằng mật khẩu của shop để lấy dữ liệu (vi phạm điều khoản, rủi ro khóa tài khoản).
- **Nghiệm thu:** đơn mới từ hãng xuất hiện trong hệ thống trong vài phút; nhận lặp cùng sự kiện không tăng số lần mua; khách mới không có cơ sở liên hệ không vào danh sách gửi; mất kết nối không làm hỏng dữ liệu cũ.

**Không thuộc phạm vi cả hai giai đoạn:** tổng đài, tích hợp TikTok, ERP/kho, 1688, tự chốt đơn, CRM nâng cao, chia ca phức tạp.

---

## 4B. THIẾT KẾ GIAO DIỆN GIAI ĐOẠN 1 (Penpot, file "adh", trang "Page 1") — CHỜ CHỦ SHOP DUYỆT

Thiết kế dựa trên file 01 (BR-01..BR-16, mục 6–9, 11) và quyết định D1–D8. Chữ lớn (≥ 17px), tương phản cao, một việc mỗi màn hình, vì người dùng là bố mẹ lớn tuổi. Màn hình web chạy trên máy tính; riêng trang khách bấm link thiết kế cho điện thoại.

| # | Màn hình (board Penpot) | Mốc | Nguồn nghiệp vụ | Trạng thái code |
|---|---|---|---|---|
| 1 | Tổng quan | 1A→1D | Mục 11 (chỉ số) | Có (1A+1B) |
| 2 | Danh sách khách (mua 1 lần / mua từ 2 lần) | 1A, 1B | BR-04, BR-06, mục 8 | Có |
| 3 | Chi tiết khách (cơ sở liên hệ, ghi chú, quyền dữ liệu, nhật ký, vận đơn) | 1A, 1B | BR-02/03, mục 6.1, 8 | Có |
| 4 | Danh sách mời (duyệt cơ sở liên hệ) | 1B | BR-05, mục 6.1 | Có |
| 5 | Yêu cầu của khách về dữ liệu (hạn phản hồi) | 1B | Mục 10 (từ chối/ngừng liên hệ) | Có |
| 6 | Nhập file: chọn cột | 1A | Mục 4 | Có |
| 7 | Đăng nhập (Bố / Mẹ, mật khẩu riêng) | 1B-bổ sung | Mục 9 V1 | **Chưa**: hiện chỉ có 1 tài khoản OWNER; cần chủ shop xác nhận |
| 8 | Tài khoản Zalo của bot (+ công tắc an toàn) | 1E | BR-11/12, mục 7, "nút tắt bot" | Chưa (chờ chọn phương án bot) |
| 9 | Nhập file: báo cáo, lỗi, trùng | 1A | Mục 4, 9 V1 | Có (cần thêm giao diện đẹp hơn) |
| 10 | Trạng thái đơn (ánh xạ trạng thái hãng) | 1A | Mục 4 | Có |
| 11 | Nhóm Zalo (link, mục tiêu ~5 khách, số khách thật) | 1C | BR-07, mục 6.5–6.7 | Chưa |
| 12 | Chiến dịch mời (lô nhỏ, xem trước tin, tạm dừng tất cả, kết quả) | 1C, 1D | BR-08/16, mục 6, D5, D6 | Chưa |
| 13 | Trang khách bấm link (điện thoại, chữ to, 1 nút) | 1C | Mục 6.2–6.4, tiêu chí 7 | Chưa |
| 14 | Việc cần mẹ xử lý (bot báo người thật) | 1E | BR-12, mục 7 "Ở V1" | Chưa |
| 15 | Cài đặt gửi tin (bật/tắt gửi thật, giờ gửi, hạn mức ngày, trần ngân sách SMS) | 1D | D3–D6, mục 3.3 | Chưa |

**Quy tắc thiết kế rút từ nghiệp vụ (không được vi phạm khi code):**
1. **Mẹ không cần đăng nhập web để trả lời khách** (mục 9 V1). Web chỉ là công cụ dữ liệu: nhập đơn, duyệt danh sách, quản lý nhóm, tắt bot. Mẹ vẫn dùng Zalo thật.
2. **Tài khoản Zalo của mẹ không bao giờ kết nối vào hệ thống** (BR-11, D4). Chỉ có tài khoản Zalo RIÊNG của bot (mốc 1E, nếu chọn `zca-js`), và màn 8 ghi rõ rủi ro bị khóa.
3. Danh sách `REPEAT` **không có nút mời**; danh sách mời chỉ có khách `ONE_TIME` đã duyệt (BR-06, tiêu chí 6).
4. Gửi thật mặc định TẮT, có nút "tạm dừng tất cả" trên mọi màn hình chiến dịch (mục 6.6, mục 3.3).
5. Lượt bấm link hiển thị là "lượt bấm", **không** ghi "đã vào nhóm" (mục 6.3). Số khách nhóm tách khỏi tài khoản mẹ và bot (tiêu chí 8).
6. Không có giao diện hội thoại cho nhân viên (V1 không xây, BR-13). Giao diện nhân viên thuộc GĐ2.

**Câu hỏi chờ chủ shop (ảnh hưởng thiết kế/code):**
- (Đ1) "Đăng nhập tài khoản cá nhân của bố mẹ": bố và mẹ có cần **hai tài khoản web riêng** (để nhật ký ghi đúng người duyệt/xóa) không? Thiết kế màn 7 đang đề xuất có. Hiện code chỉ có 1 tài khoản.
- (Đ2) "Đăng nhập của AI bot": hiểu là **kết nối tài khoản Zalo riêng của bot** (màn 8, mốc 1E) chứ không phải tài khoản đăng nhập web. Đúng không?

## 5. Rủi ro chính và biện pháp

| Rủi ro | Mức | Biện pháp |
|---|---|---|
| Gửi tin cho khách chưa có cơ sở liên hệ | Cao | Cổng 3.3, sổ cơ sở liên hệ, pilot nhỏ, hỏi luật sư trước 1D |
| J&T không xuất được danh sách | Cao | Hỏi hãng sớm; làm Viettel Post trước; adapter tách biệt |
| Zalo không duyệt mẫu "mời vào nhóm" | Trung bình | Chuẩn bị mẫu nội dung hậu mãi; fallback `ManualExport` |
| GMF làm lệch BR-08/BR-11 (phê duyệt, OA là chủ nhóm) | Trung bình–Cao | **Chủ cửa hàng quyết** ở 1E; không tự đổi nghiệp vụ |
| Chi phí GMF theo số nhóm | Trung bình | Chỉ tạo nhóm khi cần; theo dõi chi phí hàng tháng |
| zca-js bị khóa/đổi API | Cao nếu chọn | Chỉ khi chủ cửa hàng chấp nhận; tài khoản phụ; kill switch |
| Dữ liệu ra nước ngoài (VPS/LLM) | Trung bình | VPS Việt Nam, che PII, hỏi luật sư |
| Phân loại sai do trạng thái đổi sau khi giao | Cao | Cập nhật lại khi nhập đợt mới; test cho ca hoàn sau giao |
| Làm quá sớm V2 | Trung bình | Cổng bắt đầu GĐ2 mục "Cổng kết thúc GĐ1" |

## 6. Điểm chưa kiểm chứng cần POC
Zalo duyệt mẫu mời nhóm? · Hộ kinh doanh đăng ký OA/ZBS có bị hạn chế gì? · GMF: mẹ làm phó nhóm, API duyệt thành viên, giá tin OA→nhóm sau 31/12/2026 · Zalo Bot Platform có vào nhóm không · J&T xuất file · NĐ 91/2020 áp dụng thế nào cho tin ZBS · hành vi thật của zca-js (nếu chọn).

## 7. Nguồn đã đọc (21/11/2026)
- ZBS Template Message tổng quan: https://oa.zalo.me/home/documents/vie/guides/zbs-template-message
- Chính sách chung ZBS Template (cập nhật 09/07/2026): https://zalo.solutions/terms
- Bảng giá ZBS: https://zalo.solutions/business-message/pricing
- Bảng giá OA và GMF: https://zalo.solutions/oa/pricing
- Chính sách GMF: https://oa.zalo.me/home/documents/policy/tinh-nang-quan-ly-nhom
- API tạo nhóm GMF: https://developers.zalo.me/docs/official-account/nhom-chat-gmf/quan-ly/create_group
- Webhook tin nhắn nhóm GMF: https://developers.zalo.me/docs/official-account/webhook/nhom-chat-gmf/message_webhook
- Khởi tạo & xác thực OA: https://oa.zalo.me/home/documents/vie/guides/khoi-tao-zalo-official-account_61
- Zalo Bot: https://docs.zaloplatforms.com/docs/BOT
- zca-js (README, npm 2.3.0, tài liệu API, issue #328): https://github.com/RFS-ADRENO/zca-js · https://www.npmjs.com/package/zca-js · https://tdung.gitbook.io/zca-js · https://github.com/RFS-ADRENO/zca-js/issues/328
- Viettel Post quản lý vận đơn/xuất Excel: https://viettelpost.com.vn/guide/quan-ly-van-don/
- Spring Boot 4.0/4.1 và vòng đời: https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/ · https://endoflife.date/spring-boot
- Tổng hợp luật dữ liệu cá nhân 2026 (nguồn phụ, cần đối chiếu Công báo): https://mona.software/nghien-cuu/crm-du-lieu-ca-nhan-theo-luat/
