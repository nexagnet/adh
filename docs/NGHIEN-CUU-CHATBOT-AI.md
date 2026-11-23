# Nghiên cứu repo chatbot AI để tích hợp vào Adh (23/11/2026)

Mục đích: xem có repo mã nguồn mở nào đủ tốt để **dùng lại** cho phần trợ lý AI trả lời khách của Adh (khách nhắn riêng, sau này cả trong nhóm Zalo, và giai đoạn 2 có đội bán hàng xử lý ngoại lệ), hay nên tự viết. Đây là tài liệu nghiên cứu, **không phải quyết định**: chưa có mã nào đổi theo nó. Quy tắc nền vẫn là `CLAUDE.md` mục 3 và 3a (D18, D19), thiết kế AI ở `docs/THIET-KE-AI-DOC-TIN-DEN.md`.

## 1. Kết luận

- **Giai đoạn 1: tự viết trong Adh.** Không repo nào đủ cả bốn điều Adh cần: trả lời chỉ dựa trên bảng thông tin cửa hàng, kiểm đầu ra bằng quy tắc cứng, tiếng Việt, và đi qua các chốt chặn D18 (hạn mức, khung giờ, nghỉ cố định, cầu dao) cùng `AiGate`, `PiiMasker` đã có.
- **Giai đoạn 2 (đội bán hàng):** mở rộng `/handoffs` trong Adh trước (người phụ trách, trạng thái, ghi chú nội bộ). Nếu đội đông, thử **Chatwoot + `zca-bridge`** như một dự án riêng (mục 5).
- **Khi cần RAG trên danh mục lớn hoặc cơ chế thử lại có kiểm soát:** cân nhắc **LangChain4j** như một thư viện (mục 3). Chưa cần lúc này vì bảng thông tin cửa hàng nhỏ có thể nằm thẳng trong lời nhắc.
- **Học ý, không lấy mã:** từ `zaloclaw`, `2anh-zalo-bot`, `zalo-flow`, `zca-bridge`, Chatwoot (mục 6).

## 2. Máy chủ và điều kiện

Máy hiện 2 GB RAM, rất chật (`CLAUDE.md` mục 6). Có thể nâng lên 4 GB nên ngưỡng nặng được nới, nhưng vẫn ưu tiên gọn, giấy phép cho dùng thương mại và miễn phí ở giai đoạn 1.

## 3. Thư viện Java dùng ngay trong Adh (không cần dịch vụ riêng)

| Thư viện | Giấy phép (đã đọc LICENSE) | Phổ biến | Có gì | Chưa kiểm chứng |
|---|---|---|---|---|
| [LangChain4j](https://github.com/langchain4j/langchain4j) | Apache-2.0 | khoảng 13 nghìn sao; JDK 17 trở lên | Rào chắn đầu vào và đầu ra (`InputGuardrail`, `OutputGuardrail`) tự viết được, có thử lại và nhắc lại khi đầu ra sai; bộ lọc lệnh chèn bằng regex; kiểm JSON đúng khuôn; RAG hỗ trợ pgvector (chạy trên Postgres sẵn có) | Rào chắn ghi là **thử nghiệm**; chưa xác nhận gọi DeepSeek được; chưa xác nhận hợp phiên bản Spring Boot đang dùng; chưa đọc phần trí nhớ theo từng người |
| [Spring AI](https://github.com/spring-projects/spring-ai) | Apache-2.0 | khoảng 9,4 nghìn sao; 2.x cho Spring Boot 4, 1.1.x cho Spring Boot 3.5 | Nhà cung cấp DeepSeek, RAG với pgvector, trí nhớ hội thoại theo mã cuộc trò chuyện, đầu ra thành đối tượng Java | **Không có rào chắn đầu ra** dạng có tên; chưa kiểm cách cấu hình DeepSeek |

Nhận định (suy đoán có cơ sở): bộ kiểm đầu ra của Adh chủ yếu là quy tắc cứng (chặn giá, số tiền, link, số điện thoại, cam kết không có trong bảng, câu quá dài). Viết vài chục dòng Java thì gọn và dễ kiểm thử hơn kéo cả khung. LangChain4j đáng dùng **khi** danh mục sản phẩm lớn cần tìm kiếm ngữ nghĩa, hoặc khi muốn chuỗi "kiểm đầu vào, kiểm đầu ra, thử lại một lần, rồi chuyển người thật".

## 4. Các repo đã xét

| Repo | Giấy phép | Phổ biến | Nặng | Kết luận |
|---|---|---|---|---|
| [zalo-flow](https://github.com/aizaloapp/zalo-flow) (bot Zalo cá nhân tiếng Việt, dùng zca-js) | **MIT kèm Commons Clause, README ghi phi thương mại** (chưa đọc được file LICENSE) | 13 sao, mới | Node 22.5 trở lên; tự khoe dưới 100 MB RAM | **Không dùng**: shop là kinh doanh, trùng việc với `zalo-bridge`. Học: gom tin ngắn liên tiếp, khóa theo cuộc trò chuyện, bỏ qua tin do chính mình vừa gửi, im khi người thật vừa nhắn |
| [zaloclaw](https://github.com/monas-team/zaloclaw) | MIT (đã đọc LICENSE) | 24 sao | Cần nền tảng OpenClaw; có chức năng tự cập nhật mã từ GitHub (rủi ro an toàn) | **Không dùng**. Học: cấu hình theo từng nhóm (`groupPolicy`, `requireMention`) |
| [2anh-zalo-bot](https://github.com/luonghaianh1208/2anh-zalo-bot) | MIT (đã đọc LICENSE) | 15 sao | Cần Hermes Agent, khuyên khoảng 10 GB RAM | **Không dùng**. Mặc định chỉ trả lời khi được nhắc tên; tự ghi nhận **cả nhóm dùng chung một phiên** (nguy cơ lẫn ngữ cảnh giữa người trong nhóm): ví dụ phản diện cho điều Adh phải tránh |
| [openzca](https://github.com/darkamenosa/openzca) (CLI trên zca-js) | MIT theo README (chưa mở LICENSE) | 104 sao | Node 22.13 hoặc 18 trở lên (README và trang web ghi khác nhau) | **Không cần**: `zalo-bridge` đã làm các việc này |
| [GwenDev_ZaloChat](https://github.com/ntkiendz/GwenDev_ZaloChat) | README ghi MIT (chưa đọc LICENSE) | 2 sao | nhỏ | **Không**: không có logic trả lời nhóm |
| zalo-agent (vuhai2002) | ghi MIT theo kết quả tìm kiếm; **không mở được trang repo** | thấp | | **Không**: hỗ trợ nhiều tài khoản, trái D19 |
| [Chatwoot](https://github.com/chatwoot/chatwoot) | MIT (thư mục `enterprise/` chưa đọc giấy phép riêng) | khoảng 40 nghìn sao | Rails, Postgres, Redis, Sidekiq | **Chưa**, xem mục 5 |
| [diendh/zca-bridge](https://github.com/diendh/zca-bridge) (nối Zalo vào Chatwoot) | README ghi Apache-2.0; **chưa đọc được file LICENSE** | 121 sao, Node 24 trở lên, Fastify, Postgres riêng | | **Chưa**, xem mục 5 |
| [NeMo Guardrails](https://github.com/NVIDIA-NeMo/Guardrails) | Apache-2.0 | khoảng 7 nghìn sao | Python, thêm một dịch vụ | **Không đáng** cho vài quy tắc; chưa xác nhận gọi DeepSeek |
| [Guardrails AI](https://github.com/guardrails-ai/guardrails) | Apache-2.0 | khoảng 7,6 nghìn sao | Python | **Không đáng**; README báo phần kiểm "hosted" bị ngừng từ 25/08/2026 (chưa kiểm đã ngừng thật chưa) |
| [Dify](https://github.com/langgenius/dify) | **Apache-2.0 sửa đổi**: cấm dùng mã để chạy môi trường nhiều workspace nếu chưa xin phép, cấm gỡ logo ở giao diện | khoảng 160 nghìn sao | tối thiểu 4 GB RAM, nhiều dịch vụ | **Không** |
| Botpress, Typebot, Rasa | Botpress Apache-2.0 nhưng chuyển người thật là tính năng trả phí; Typebot nguồn tin mâu thuẫn (MIT hay AGPL); Rasa chưa rõ | | 2 đến 4 GB | **Không**: thiên về kịch bản cố định, không có Zalo |
| [Shopify shop-chat-agent](https://github.com/Shopify/shop-chat-agent) | **Chỉ cho dùng để tích hợp Shopify**; repo đã lưu trữ 16/10/2026 | 142 sao | | **Không dùng được** |

Không repo Zalo nào ở trên giải quyết đủ: đọc đúng người hỏi trong nhóm, im khi mẹ đã trả lời, hạn mức theo nhóm, không lộ dữ liệu giữa khách. Chúng chỉ làm tới mức "chỉ trả lời khi được nhắc tên" và "cấu hình theo từng nhóm".

Chưa tìm được lời nhắc (prompt) công khai nào đã dùng thật cho kiểu "chỉ trả lời dựa trên bảng thông tin cửa hàng, tiếng Việt, kèm chuyển người thật" có giấy phép rõ. Phần này **tự soạn**, kèm bộ vài chục câu hỏi thử tiếng Việt giả trước khi bật (như đã ghi ở `docs/THIET-KE-AI-DOC-TIN-DEN.md`).

## 5. Phương án đội bán hàng giai đoạn 2: Chatwoot + zca-bridge

Chatwoot cho sẵn hộp thư nhiều người, phân công, trạng thái, ghi chú nội bộ. `zca-bridge` đưa tin Zalo vào Chatwoot và gửi ngược câu trả lời của nhân viên sang Zalo (hàng đợi trong Postgres, chống vòng lặp tin của chính mình, lỗi gửi vĩnh viễn ghi thành ghi chú nội bộ). **Chưa dùng ngay** vì:

1. **Tranh tài khoản.** `zca-bridge` tự giữ phiên đăng nhập zca-js, còn Adh đã có `zalo-bridge` giữ phiên MỘT tài khoản phụ (D19). Hai bên cùng nghe một tài khoản có thể xung đột (suy đoán, chưa thử). Dùng thì hoặc để `zca-bridge` thay `zalo-bridge` (mất các chốt chặn D18 đã viết), hoặc Adh tự viết lớp đẩy tin sang Chatwoot qua API.
2. **Có tùy chọn trái luật dự án.** `zca-bridge` có proxy riêng cho từng tài khoản để "cô lập IP". `CLAUDE.md` mục 3 cấm proxy xoay và mọi cách né phát hiện. Nếu thử thì **không bật** tùy chọn này.
3. **Nặng thêm:** Rails, Postgres, Redis, Sidekiq và một Postgres riêng cho cầu nối. Máy 4 GB có thể gánh nhưng sát (suy đoán, chưa đo).

Đề xuất: mở rộng `/handoffs` trước. Chỉ khi đội đông và cần nhiều kênh mới thử Chatwoot như dự án riêng, bắt đầu bằng một nhánh nhỏ với tài khoản thử, không đụng tài khoản thật.

## 6. Học gì, tự viết gì

**Học (lấy ý, không lấy mã):**
- Cấu hình theo từng nhóm và mặc định chỉ trả lời khi được nhắc tên (zaloclaw, 2anh-zalo-bot).
- Gom tin ngắn liên tiếp thành một lượt hỏi bằng thời gian chờ **cố định**; khóa theo cuộc trò chuyện; bỏ qua tin do chính mình vừa gửi; ghi lỗi gửi thành ghi chú (zalo-flow, zca-bridge).
- Hộp thư cho đội: phân công, trạng thái, ghi chú nội bộ (Chatwoot).
- Chuỗi "kiểm đầu vào, kiểm đầu ra, thử lại một lần, rồi chuyển người thật" (LangChain4j).

**Tự viết trong Adh:**
1. Bộ kiểm đầu ra bằng quy tắc cứng và phân loại phạm vi bằng AI (dựa trên `AiGate`, `PiiMasker`).
2. Lời nhắc và bảng thông tin cửa hàng ("sản phẩm", "áo điều hòa và phụ kiện"), bộ câu hỏi thử tiếng Việt giả.
3. **Chính sách trả lời trong nhóm** (khi có, chưa làm): chỉ trả lời khi được hỏi hoặc câu hỏi rõ ràng thuộc hai chủ đề; gắn câu hỏi với mã Zalo người gửi; im khi mẹ hoặc người thật đã nhắn trong nhóm trong N phút hoặc đã trả lời đúng câu đó; trần số câu trả lời mỗi nhóm mỗi giờ và mỗi ngày (cộng hạn mức chung và cầu dao đã có); **câu trả lời trong nhóm chỉ dựng từ bảng thông tin cửa hàng công khai và chính câu hỏi vừa rồi, không đưa đơn hàng, tên, địa chỉ, lịch sử của khách nào vào lời nhắc gửi AI**, để khách này không lộ dữ liệu cho khách khác. Việc cần dữ liệu riêng (đơn, bảo hành cụ thể) thì chuyển người thật hoặc mời nhắn riêng.
4. Hàng đợi ngoại lệ cho đội bán hàng: mở rộng `/handoffs` (người phụ trách, trạng thái mới, đang xử lý, đã xong, ghi chú nội bộ, nhật ký).

## 7. Cảnh báo giấy phép

- **zalo-flow:** Commons Clause cấm bán hoặc dùng thương mại, không phải giấy phép mã nguồn mở theo định nghĩa OSI. Không đưa mã của nó vào Adh.
- **Shopify shop-chat-agent:** giấy phép chỉ cho tích hợp Shopify.
- **Dify:** giấy phép sửa đổi, có điều kiện về nhiều workspace và logo.
- **Typebot, Botpress, Rasa:** nguồn so sánh mâu thuẫn hoặc có tính năng trả phí; cần đọc LICENSE gốc nếu định dùng.
- Mọi repo mang chữ MIT/Apache theo README nhưng chưa mở LICENSE được (zca-bridge, openzca, GwenDev_ZaloChat, zalo-agent) phải **đọc file LICENSE** trước khi lấy bất cứ thứ gì.
- Thư mục `enterprise/` của Chatwoot có thể mang giấy phép khác MIT; chưa đọc.

## 8. Cảnh báo chống né phát hiện

Nhiều repo Zalo ở trên quảng cáo "Anti-Ban" (zalo-flow), proxy theo từng tài khoản (zca-bridge), hoặc "built to avoid Zalo account bans" (2anh-zalo-bot). Quy tắc của Adh (`CLAUDE.md` mục 3, ADR-0002 mục 12) vẫn nguyên:

- Phần **giới hạn tốc độ cố định, hạn mức, cầu dao, im khi người thật đang trả lời** thì Adh đã có hoặc nên có: đây là minh bạch và giới hạn, không phải né.
- **Không** bê: ngẫu nhiên hóa thời gian cho giống người, giả vân tay thiết bị, proxy xoay hoặc cô lập IP theo tài khoản, xoay nhiều tài khoản, giả thao tác. Có test khóa (`ZaloGatewayShapeTest`, `zalo-bridge/test/no-send.test.mjs`).
- Gom tin ngắn liên tiếp chỉ dùng thời gian chờ **cố định** để hiểu đúng câu hỏi; không dùng để "trông giống người".
- Mọi thư viện zca-js (kể cả các repo ở trên) đều tự ghi là không chính thức, có thể vi phạm điều khoản Zalo và khiến tài khoản bị khóa. Người dùng đã chấp nhận rủi ro đó cho tài khoản phụ; điều đó không mở rộng sang việc né phát hiện.

## 9. Chỗ chưa chắc hoặc chưa đọc được

- Không gọi được API GitHub trong phiên nghiên cứu, nên số sao và ngày cập nhật lấy từ trang repo và bài viết (chụp ở thời điểm khác nhau, có chỗ lệch: Chatwoot 36,7 đến 40 nghìn sao; NeMo 7,1 đến 7,4 nghìn).
- **Ngày commit cuối và bản phát hành của hầu hết repo không đọc được** (trang lịch sử bị chặn), nên chưa biết repo nào còn được chăm.
- Chưa đọc file LICENSE của: zalo-flow, zca-bridge, openzca, GwenDev_ZaloChat, zalo-agent, thư mục `enterprise/` của Chatwoot. Giấy phép các repo đó dựa vào README.
- Chưa xác nhận LangChain4j, NeMo Guardrails, Guardrails AI gọi được DeepSeek; chưa kiểm cách cấu hình DeepSeek của Spring AI.
- RAM thực tế của zalo-flow và Chatwoot chưa đo; chuyện hai bộ nối cùng một tài khoản Zalo là suy đoán.
- Chưa đánh giá mẫu workflow n8n cho Zalo (giấy phép n8n có điều kiện) và Zalo OA/ZNS chính thức (vẫn là hướng hợp lệ nếu sau này muốn đi chính thức, xem `docs/ADR-0001-chon-kenh-bot.md`).
- Bài giới thiệu zalo-flow mang tính quảng bá của chính nhóm tác giả; các nhận xét về zalo-flow chưa được kiểm độc lập.

## 10. Nguồn

- Repo: các liên kết trong bảng mục 3 và 4.
- Tài liệu LangChain4j: https://docs.langchain4j.dev/tutorials/guardrails
- Bài mô tả kiến trúc zalo-flow (Viblo, 21/10/2026).
- So sánh nền tảng chatbot mã nguồn mở: featurebase.app, ossalt.com (blog của bên bán sản phẩm hoặc có liên kết giới thiệu, chỉ để tham khảo).
