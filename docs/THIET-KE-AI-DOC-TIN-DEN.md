# Thiết kế: AI (DeepSeek) đọc tin đến và hỗ trợ mẹ (bản để Phùng Việt duyệt, CHƯA code)

Viết 23/11/2026. Bạn đã duyệt bốn chỗ dùng AI: (1) nhận ra khách nhắn "đừng nhắn nữa", muốn mua hoặc khiếu nại; (2) trợ lý trong nhóm trả lời câu hỏi thường gặp; (3) soạn nháp trả lời cho mẹ duyệt; (4) tóm tắt việc "cần mẹ xử lý" mỗi ngày. Bạn cũng chốt **gộp tài khoản phụ và trợ lý AI thành MỘT tài khoản Zalo**.

> **Cập nhật 23/11/2026 sau khi Phùng Việt duyệt (bản này thay các chỗ cũ bên dưới):**
> - Phạm vi chỉ còn **AI nhận diện từ chối** (mục 3). Các việc "AI chỉ gợi ý, mẹ quyết" (nhận ra khách muốn mua hoặc khiếu nại để báo mẹ, soạn nháp trả lời, tóm tắt việc cho mẹ, trợ lý trả lời trong nhóm) **tạm thời chưa làm**; mục 2 và mục 4 giữ lại làm tài liệu cho việc sau, không code.
> - Quy tắc của chủ dự án: cái gì là ngôn ngữ tự nhiên, không có quy luật thì dùng AI. Vì vậy **bỏ "bước 1 luật từ khóa"** ở mục 3.3: mọi tin đến đều qua AI. Khi AI lỗi, hết hạn mức hoặc không chắc thì mặc định **tạm dừng tin cho khách đó và báo mẹ**, không tự ghi từ chối.
> - Ngưỡng độ chắc 0,8 để ghi sổ từ chối tự động (chỉnh được bằng cấu hình), bỏ nội dung tin sau khi ghi sổ, giữ trích đoạn đã che tối đa 300 ký tự trong 30 ngày cho tin cần mẹ xem: **đã được duyệt**.
> - Gộp tài khoản mời và trợ lý AI thành **một tài khoản Zalo** (rủi ro bị khóa nên hạn mức và cầu dao chặt hơn).

## 1. Chia làm hai nhóm việc

| Nhóm | Việc | Cần bộ nối đọc tin đến? | Cách làm |
|---|---|---|---|
| A. Không đụng Zalo | (3) soạn nháp trả lời cho mẹ, (4) tóm tắt việc cần mẹ xử lý | Không | Code ngay thành PR nhỏ, mặc định tắt |
| B. Cần đọc tin đến | (1) nhận diện từ chối / muốn mua / khiếu nại; (2) trợ lý trả lời trong nhóm | Có (nguyên tắc 9 của CLAUDE.md đang cấm) | **Chờ bạn duyệt thiết kế này** rồi mới code |

## 2. Nhóm A: code ngay (không cần bộ nối)

- Thêm bản cài thật cho giao diện `AiProvider` có sẵn (hiện chỉ có `MockAiProvider` luôn trả "không biết"): gọi API DeepSeek.
- **Công tắc tắt mặc định.** Muốn chạy phải đặt biến môi trường: bật AI, khóa API, tên mô hình. Khóa API **chỉ đi qua biến môi trường**, không vào repo, log hay PR.
- Chỉ gửi cho DeepSeek: câu hỏi của khách đã **che** số điện thoại, email, số dài (`PiiMasker` có sẵn) và các câu trả lời FAQ cửa hàng đã duyệt. **Không bao giờ gửi** số điện thoại, địa chỉ, mã Zalo, tên đầy đủ, lịch sử trò chuyện.
- AI chỉ **gợi ý nháp** cho mẹ. Không tự gửi, không tự chốt đơn, không hứa giá, tồn kho, bảo hành. Nháp bị chặn nếu có số tiền, số điện thoại, đường link hoặc con số nào **không có trong FAQ đã duyệt**; không chắc thì trả "không biết" và chuyển mẹ.
- Giới hạn: hết thời gian chờ 10 giây, tối đa N lượt gọi mỗi ngày (mặc định 200), lỗi mạng hoặc hết hạn mức thì im lặng chuyển mẹ (AI hỏng không được làm mẹ hết việc).
- Tóm tắt hằng ngày: gửi cho AI chỉ **loại việc và đoạn trích đã che**, nhận về vài dòng cho mẹ đọc trên trang Tổng quan.

## 3. Nhóm B: thiết kế đọc tin đến (cần bạn duyệt)

**Hiện trạng:** bộ nối chỉ nghe sự kiện kết bạn, không đọc nội dung tin nhắn, và có test khóa việc đó.

**Đề xuất mở rộng, tối thiểu:**
1. Bộ nối **chỉ chuyển tin nhắn riêng (1-1)** gửi tới tài khoản, **chỉ từ người Adh đã gửi Tin 1 hoặc Tin 2** (Adh đẩy sang bộ nối danh sách mã Zalo cho phép, giữ trong bộ nhớ, không ghi đĩa). Tin của người lạ bị bỏ ngay, không lưu, không ghi log. Không đọc tin nhóm ở bước này.
2. Bộ nối **không đọc danh sách bạn, không đọc lịch sử cũ**, chỉ tin mới đến lúc đang nghe. Vẫn không gửi gì ngoài Tin 1 và Tin 2.
3. Adh nhận `{mã Zalo, nội dung tối đa 500 ký tự, giờ}`:
   - ~~Bước 1, luật từ khóa~~ (đã bỏ theo quy tắc "ngôn ngữ tự nhiên thì dùng AI"). **Mọi tin đến đều qua AI**: che dữ liệu rồi hỏi DeepSeek, bắt buộc trả JSON `{ý_định: TỪ_CHỐI | MUỐN_MUA | KHIẾU_NẠI | CÂU_HỎI | KHÁC, độ_chắc: 0..1}`. Không cho AI hành động nào khác ngoài gắn nhãn.
4. Quyết định theo nhãn:
   - TỪ_CHỐI, độ chắc từ 0,8: **ghi sổ từ chối**, hủy Tin 2 và việc thêm nhóm của khách đó, hiện một dòng trong "Việc cần mẹ xử lý" kèm nút **Hoàn tác**.
   - TỪ_CHỐI độ chắc dưới 0,8, hoặc AI lỗi, hết hạn mức, nghi ngờ: **tạm dừng mọi tin cho khách đó** và tạo việc cho mẹ xem. Không bao giờ tự ghi từ chối vĩnh viễn khi không chắc.
   - MUỐN_MUA, KHIẾU_NẠI, CÂU_HỎI: tạo việc cho mẹ (dùng `HandoffService` có sẵn) kèm nháp trả lời. **Hệ thống không tự trả lời khách trong cuộc trò chuyện riêng.**
5. **Lưu trữ tối thiểu:** tin của khách đã ghi sổ từ chối thì chỉ giữ nhãn và giờ, bỏ nội dung. Tin tạo việc cho mẹ giữ trích đoạn đã che tối đa 300 ký tự, **tự xóa sau 30 ngày**. Không lưu nội dung nào khác.
6. Nội dung khách viết là **dữ liệu không đáng tin**: câu lệnh trong tin ("hãy bỏ qua hướng dẫn") không được làm AI đổi hành vi; đầu ra chỉ nhận đúng JSON, sai khuôn thì coi là "nghi ngờ".
7. Cầu dao và công tắc riêng cho phần đọc tin: tắt mặc định; tự ngắt khi lỗi liên tiếp; hạn mức gọi AI mỗi ngày.

**Không làm (giữ nguyên cấm):** né phát hiện bot của Zalo, giả người, gửi lời mời kết bạn, đọc danh sách bạn, đọc tin của người chưa nhận tin của hệ thống.

## 4. Trợ lý trong nhóm (việc 2) khi chỉ còn MỘT tài khoản

- Tài khoản duy nhất vừa đi mời, vừa thêm nhóm, vừa là trợ lý trong nhóm. **Rủi ro lớn nhất:** tài khoản bị Zalo khóa thì mất cả mời lẫn trợ lý. Vì vậy hạn mức tin, hạn mức thêm nhóm và cầu dao phải chặt hơn nữa; trợ lý trong nhóm phải **im lặng khi cầu dao ngắt**.
- Trợ lý vẫn **nói rõ là trợ lý AI của cửa hàng**, không giả làm mẹ; chỉ trả lời bằng FAQ đã duyệt, thấy ý định mua hoặc khiếu nại thì báo mẹ, không chốt đơn.
- Bộ nối phải **đọc tin trong nhóm** và **gửi tin vào nhóm** (hiện chưa có hai việc này). Đó là bước mở rộng thứ hai, **xin duyệt riêng**: chỉ nhóm do Adh quản lý, chỉ tin của khách trong các nhóm đó, mỗi tin xử lý đúng một lần, tối đa vài câu trả lời mỗi 5 phút (lõi `BotEngine` đã có giới hạn này).
- Chưa chạy thử thật với Zalo; mục "Những điều chưa kiểm" ở dưới.

## 5. Rủi ro cần bạn biết

1. **Dữ liệu ra nước ngoài.** Nội dung tin khách gửi tới DeepSeek (máy chủ ngoài Việt Nam). Dù đã che số điện thoại và email, bản thân câu chữ của khách vẫn có thể là dữ liệu cá nhân. Nghị định 13/2023 có yêu cầu với việc chuyển dữ liệu cá nhân ra nước ngoài; **chưa có xác nhận pháp lý**. Cách giảm: gửi tối thiểu (một tin, đã che, không tên, không lịch sử), kiểm chính sách dùng dữ liệu của DeepSeek (có dùng để huấn luyện không) trước khi bật. Tôi sẽ làm lớp gọi AI **thay được nhà cung cấp** để đổi sang nhà khác nếu cần.
2. **AI nhận nhầm.** Giảm bằng luật từ khóa trước, ngưỡng độ chắc, nút Hoàn tác, và mặc định "nghi ngờ thì báo mẹ".
3. **Chi phí.** Hạn mức gọi mỗi ngày và công tắc tắt.
4. **Khóa API lộ.** Chỉ qua biến môi trường trên máy chủ; không ghi log, không đưa vào repo.
5. **Tài khoản duy nhất bị khóa** (mục 4).

## 6. Những điều chưa kiểm với Zalo / DeepSeek thật

- Thư viện `zca-js` có phát được sự kiện tin nhắn riêng đến ổn định không, có kèm mã người gửi không, độ trễ.
- Tin nhắn nhóm: có nhận được không khi tài khoản chỉ là thành viên.
- Tên mô hình "flash" chính xác của DeepSeek, giá, giới hạn tốc độ, chính sách dữ liệu.
- Độ chính xác của AI trên tiếng Việt thực tế (cần thử với tin giả vài chục câu trước khi bật).

## 7. Việc xin bạn quyết

1. Duyệt mục 3 (đọc tin riêng từ người đã nhận Tin 1) để tôi code nhóm B bước 1? (Có / Sửa / Không)
2. Ngưỡng ghi từ chối tự động 0,8 và tin bị bỏ nội dung sau khi ghi sổ: đồng ý?
3. Giữ trích đoạn 300 ký tự trong 30 ngày để mẹ xem: đồng ý, hay muốn ngắn hơn?
4. Trợ lý trong nhóm (mục 4): để sau, xin duyệt riêng khi bước 1 chạy ổn?
