# Đối chiếu giao diện Penpot với nghiệp vụ (giai đoạn 1)

Ngày: 21/11/2026. Chỉ đọc, không sửa file Penpot, không sửa code.

## Phần nào đọc trực tiếp, phần nào suy luận

- **Đọc trực tiếp:** toàn bộ chữ trên 16 board của trang "Page 1" (qua Penpot API), ảnh xuất của 6 board (1, 4, 7, 11, 12, 13), cỡ chữ nhỏ nhất của từng board; file 01 (BR-01..16, mục 5–12), file 03 (D1–D8, mục 3.2, 3.3, 4B, mốc 1A–1F, 2F); PR nháp #3 gồm các mẫu giao diện `fragments.html` (thanh điều hướng), `landing.html`, `campaigns.html`, `campaign-detail.html`, `sending.html`, danh sách file của PR và `docs/ADR-0001`.
- **Chưa xem ảnh, chỉ đọc chữ:** board 2, 3, 5, 6, 8, 9, 10, 14, 15, 16. Màu sắc, độ tương phản và bố cục của các board này chưa được kiểm tra.
- **Suy luận:** mỗi mục dưới đây ghi rõ "Đọc trực tiếp" hay "Suy luận".
- **Không kiểm tra được:** các liên kết/luồng bấm (prototype) trong Penpot, vì chỉ đọc cấu trúc và chữ.

## Kết luận ngắn

Thiết kế bám nghiệp vụ khá chặt. Các nguyên tắc 4B đều có mặt: mẹ không kết nối vào hệ thống, danh sách 2+ lần không có nút mời, chữ "lượt bấm" không nói "đã vào nhóm", số khách nhóm không tính mẹ và bot, gửi thật mặc định tắt kèm nút tạm dừng, tự dừng khi từ chối/lỗi/hết ngân sách, nút tắt bot.

Chỗ cần sửa chủ yếu là: số liệu mâu thuẫn giữa các màn, trang khách thiếu nút "ngừng nhận tin" và các trạng thái lỗi, màn chiến dịch thiếu đường xuất danh sách cho mẹ gửi tay, thanh điều hướng thiếu 5 mục, và vài chỗ lệch với file 03.

## Ưu tiên CAO (sửa trước khi chốt thiết kế)

### 1. Số liệu "đủ điều kiện mời" mâu thuẫn giữa màn 1, 4 và 12
- **Đọc trực tiếp.** Màn 4 ghi đủ điều kiện mời = 0, chờ duyệt = 126, và vẫn liệt kê "Danh sách (3 khách)" bên dưới. Màn 12 ghi "Có 126 khách đủ điều kiện mời". Màn 1 ghi "Đủ điều kiện mời (chưa duyệt) = 0".
- **Vì sao lệch:** file 01 tiêu chí 6 và file 03 mốc 1B: chỉ khách đã duyệt cơ sở liên hệ mới được vào chiến dịch. Code (`campaigns.html`) cũng lấy "Đã duyệt, đủ điều kiện mời". 126 khách chờ duyệt chưa được mời.
- **Sửa:** màn 12 đổi thành số đã duyệt (hiện là 0 hoặc 3), kèm liên kết "Duyệt danh sách mời" khi bằng 0. Màn 1 bỏ chữ lẫn "(chưa duyệt)" trong thẻ "Đủ điều kiện mời". Màn 4 cho số ở thẻ đầu khớp với số dòng trong bảng.

### 2. Trang khách bấm link (màn 13) thiếu nút "ngừng nhận tin" và thiếu các trạng thái lỗi
- **Đọc trực tiếp.** Code `landing.html` có nút "Tôi không muốn nhận tin từ cửa hàng nữa" và có thêm hai trang `landing-stopped.html`, `landing-unavailable.html`. Màn 13 chỉ có trạng thái bình thường: một nút "Vào nhóm Zalo", một nút gọi cửa hàng, và không có đường từ chối.
- **Vì sao lệch:** file 01 mục 6.8 và 10 (khách từ chối, yêu cầu ngừng liên hệ, link hết hạn, nhóm tạm đầy); file 03 mục 3.2 (sổ từ chối kiểm tra trước mọi lần gửi). Khách lớn tuổi cần một đường từ chối rất dễ thấy.
- **Sửa:** thêm nút chữ nhỏ nhưng rõ "Tôi không muốn nhận tin từ cửa hàng nữa". Vẽ thêm ba trạng thái: "đã ngừng nhận tin", "link hết hạn hoặc không còn hiệu lực" (kèm số gọi cửa hàng), "chưa có nhóm nào còn chỗ" (đề nghị gọi cửa hàng).

### 3. Màn 12 (Chiến dịch mời) thiếu đường "xuất danh sách để mẹ gửi tay" và kênh hiển thị không đúng hiện trạng
- **Đọc trực tiếp.** Màn 12 chỉ có hai nút: "Chạy thử" và "Gửi thật (đang khóa)". Dòng kênh ghi "SMS brandname (dự phòng: Zalo tài khoản riêng)". Code `campaign-detail.html` có thêm "Xuất danh sách để mẹ gửi tay" và "Hủy các lời mời chưa gửi", đồng thời ghi rõ chưa có kênh gửi tự động nào. File 03 mục 2.1 dòng B gọi gửi tay là "fallback bắt buộc phải có". PR #3 ghi nhà cung cấp SMS/ZBS chưa chọn.
- **Vì sao lệch:** thiết kế mô tả như thể SMS brandname đã sẵn sàng. Thực tế 1D hiện chỉ có chạy thử và xuất CSV cho mẹ gửi tay.
- **Sửa:** thêm nút "Xuất danh sách để mẹ gửi tay" làm hành động chính khi gửi thật còn khóa. Dòng kênh đổi thành "Chưa có kênh gửi tự động: chạy thử hoặc mẹ gửi tay" cho đến khi chọn nhà cung cấp. Thêm ô tên chiến dịch, hạn hiệu lực của link (mặc định 14 ngày trong code), chỗ sửa nội dung tin với chỗ giữ `{link}`, và trạng thái "đang tạm dừng" kèm nút "Bỏ tạm dừng" (code có, thiết kế chỉ có nút tạm dừng).
- **Cần chủ shop quyết (xung đột trong tài liệu, không phải lỗi thiết kế):** file 03 mục 1 ghi SMS quảng cáo cần khách đăng ký trước, khách cũ chưa đăng ký thì SMS gần như bị chặn, còn mốc 1D nói SMS chỉ cho khách `OPT_IN`. Nhưng quyết định D4 chọn SMS làm kênh chính. Màn 12 theo D4. Nếu giữ SMS thì màn 3 và 12 cần cho thấy khách nào có đồng ý (OPT_IN) thật, vì hiện chỉ có "duyệt cơ sở sau bán hàng".

### 4. Thanh điều hướng thiếu 5 mục và tab sáng sai
- **Đọc trực tiếp.** Thanh điều hướng trên mọi board có 8 mục: Tổng quan, Nhập đơn, Danh sách mời, Khách mua 1 lần, Khách mua từ 2 lần, Trạng thái đơn, Yêu cầu của khách, Đăng xuất. Code `fragments.html` có thêm Chiến dịch mời, Nhóm Zalo, Gửi tin, Bot, Việc cần mẹ xử lý (tổng 12 mục + Đăng xuất). Ở màn 11 và 12, tab sáng là "Danh sách mời".
- **Sửa:** thêm 5 mục vào thanh điều hướng của mọi board và làm tab sáng đúng màn. Với 13 mục, cân nhắc gộp nhóm: "Dữ liệu" (nhập đơn, hai danh sách, trạng thái đơn), "Mời khách" (danh sách mời, chiến dịch, nhóm, gửi tin), "Trợ lý" (bot, việc cần mẹ), "Yêu cầu của khách".

### 5. Màn 14 chưa nói mẹ nhận báo việc bằng đường nào
- **Đọc trực tiếp.** Màn 14 là một trang web "chỉ nhắc việc". File 01 mục 9: mẹ không cần đăng nhập web để trả lời khách. Mục 7: bot "thông báo cho bố mẹ qua kênh vận hành đã thống nhất". File 03 mốc 1E ghi "kênh thông báo cần chốt".
- **Suy luận.** Nếu mẹ không mở web thì việc khách muốn mua hoặc khiếu nại sẽ bị bỏ sót. Đây không phải lỗi hình vẽ mà là một quyết định còn thiếu, nhưng màn 14 nên nói rõ đang thiếu.
- **Sửa:** thêm dòng "Cách báo cho mẹ: chưa chọn" kèm các lựa chọn (tin Zalo riêng từ bot tới mẹ, hoặc bố nhắc). Thêm số việc "Mới" lên thanh điều hướng.

## Ưu tiên TRUNG BÌNH

### 6. Hạn phản hồi yêu cầu dữ liệu (màn 5) lệch file 03
- **Đọc trực tiếp.** Màn 5 đặt 15 ngày cho mọi yêu cầu và ghi "mức tạm đặt". File 03 mục 1 điểm 4 nêu hộ kinh doanh vẫn phải phản hồi trong 2 ngày làm việc và xóa trong 20 ngày. Nguồn đó tự ghi là nguồn phụ, cần đối chiếu Công báo.
- **Sửa:** hạn tính theo loại yêu cầu (xóa, xuất, ngừng liên hệ), không dùng một hạn chung. Hiển thị hạn như số có thể cấu hình, chưa phải kết luận pháp lý.
- **Liên quan:** màn 1 ghi "Yêu cầu của khách chưa xử lý = 0" trong khi màn 5 có 2 yêu cầu chưa xong, một cái "Quá hạn". Thẻ này nên đếm đúng và nổi màu cảnh báo khi có yêu cầu quá hạn.

### 7. Trạng thái lời mời của từng khách chưa hiện ở màn 2 và 3
- **Đọc trực tiếp.** File 01 mục 5: trạng thái lời mời tách khỏi phân loại (chưa được phép gửi, đã nhận, đã bấm...). Màn 2 chỉ có cột "Duyệt mời". Màn 3 không có mục "lời mời đã tạo và lượt bấm".
- **Sửa:** thêm cột "Lời mời" (Chưa mời / Đã gửi / Đã bấm / Từ chối) ở màn 2 và một khối nhỏ ở màn 3. Mỗi dòng ghi "Đã bấm" chứ không ghi "đã vào nhóm".

### 8. Màn 11 số đếm sai và màn 12 không cảnh báo hết chỗ
- **Đọc trực tiếp.** Màn 11: thẻ "Nhóm đang nhận khách = 4" nhưng bảng chỉ có 2 nhóm "Đang nhận"; thẻ "Nhóm đã đầy / tạm đóng = 1" nhưng bảng có 2 (Đã đầy, Tạm đóng).
- **Suy luận.** Màn 12 cho lô 20 khách, trong khi các nhóm đang nhận chỉ còn 5 chỗ (Nhóm 1: 1, Nhóm 3: 4). File 01 mục 6.5 và file 03 mốc 1C nói khi chưa có nhóm thì phải báo.
- **Sửa:** sửa hai thẻ cho khớp bảng. Màn 12 thêm dòng "Chỗ trống trong các nhóm: N" và cảnh báo khi lô lớn hơn chỗ trống.

### 9. Màn 15 thiếu nhật ký gửi
- **Đọc trực tiếp.** Code `sending.html` có bảng "Nhật ký gửi gần đây" (3 số cuối, kênh, kết quả, chi phí). File 03 mục 3.2 yêu cầu `send_log` giữ ít nhất 1 năm. Màn 15 chỉ có cấu hình và ngưỡng tự dừng.
- **Sửa:** thêm bảng nhật ký gửi và trạng thái "đang tạm dừng" kèm lý do vào màn 15.

### 10. Báo cáo nhập file (màn 9) không nói đơn đổi trạng thái
- **Đọc trực tiếp.** Màn 9 chỉ có "Vận đơn mới / Đã có từ trước / Dòng lỗi". File 03 mốc 1A cần xử lý ca "đơn giao thành công rồi chuyển hoàn" và cập nhật phân loại khi nhập đợt mới (cũng là rủi ro cao ở mục 5).
- **Sửa:** thêm số "Đơn đổi trạng thái so với lần nhập trước" và "Khách đổi nhóm (mua 1 lần ↔ 2+ lần)".

### 11. Màn 8 (bot) giả định đã chọn `zca-js`
- **Đọc trực tiếp.** Màn 8 có nút "Kết nối: quét mã QR" và cảnh báo "tài khoản Zalo cá nhân không chính thức". `ADR-0001` ghi trạng thái CHƯA QUYẾT, chưa chạy spike nào; file 03 mục 2.2 nói đây là phương án cuối cùng, mặc định là không bot hoặc P1/P3.
- **Sửa:** để màn 8 ở trạng thái "Chưa chọn phương án kết nối" và liệt kê các lựa chọn. Chỉ hiện nút QR khi chủ shop chấp nhận bằng văn bản rủi ro khóa tài khoản.

## Ưu tiên THẤP (dọn trong lúc sửa)

12. **Dữ liệu mẫu tự mâu thuẫn (đọc trực tiếp).** Ba người xuất hiện ở cả màn 2 (mua 1 lần) và màn 16 (mua từ 2 lần): 0366 111 222 (Le Van C), 0905 000 111 (Pham Thi D), 0372 999 888 (Hoang E). Le Van C "Bị chặn" ở màn 2 nhưng nằm trong "danh sách đủ điều kiện mời" ở màn 4. Hai danh sách 1 lần và 2+ lần không được trùng nhau (BR-04). Nên đổi dữ liệu mẫu để người xem không hiểu nhầm.
13. **Cỡ chữ (đọc trực tiếp).** File 03 mục 4B nói chữ ≥ 17px, nhưng cỡ nhỏ nhất đo được là 15px (màn 2, 3, 5, 8, 10, 11, 12, 14, 15, 16) và 16px ở các màn còn lại. Với bố mẹ lớn tuổi nên nâng chữ bảng và chữ phụ lên ≥ 17px, hoặc sửa lại quy tắc cho khớp.
14. **Tên bot (suy luận).** BR-11 và mục tiêu 5: bot giới thiệu rõ là "trợ lý AI của cửa hàng". Màn 8 đặt tên "Trợ lý cửa hàng" (đúng tên mà chủ shop muốn công khai). Nếu muốn khách hiểu ngay là AI thì đổi thành "Trợ lý AI cửa hàng...". Trang khách (màn 13) cũng chưa cho biết trong nhóm có mẹ và trợ lý AI.
15. **Đăng nhập (màn 7).** Thiết kế có hai tài khoản Bố/Mẹ nhưng file 03 mục 4B (câu hỏi Đ1) chưa được chủ shop xác nhận và code chỉ có một tài khoản. PR #3 mục 1F có khóa đăng nhập sai nhưng màn 7 chưa có trạng thái "đã bị khóa vài phút". Chờ chủ shop trả lời Đ1.

## Những chỗ thiết kế đã đúng (đọc trực tiếp)

- Màn 13 không đòi OTP hay số điện thoại; ghi rõ "không vào nhóm cũng không sao"; nội dung không có giá, không khuyến mãi (BR-16).
- Màn 16 (2+ lần) không có nút mời vào nhóm, có đủ cột file 01 mục 8 (tên, địa chỉ, số đơn thành công, lần mua gần nhất, ghi chú).
- Màn 3 có "từng đơn tạo ra kết quả" (tiêu chí 5), ghi nhật ký, xóa dữ liệu cần gõ XOA, số điện thoại sau xóa chỉ còn dạng băm.
- Màn 10: trạng thái chưa rõ nghĩa không bao giờ tính là mua thành công (tiêu chí 4).
- Màn 11: số khách không tính mẹ và bot (tiêu chí 8); màn 12, 15: tự dừng, hạn mức ngày, giờ 07–22, trần ngân sách khớp D5, D6.
- Màn 14: trạng thái Mới / Mẹ đã xem / Đã xử lý khớp OPEN → ACK → RESOLVED, bot ngừng khi mẹ đã trả lời, có nút tắt bot.
- Màn 5 và 9 che số điện thoại (3 số cuối), hợp với D7 (không ghi SĐT đầy đủ vào log).

## Câu hỏi cho chủ shop

1. Giữ SMS làm kênh chính (D4) hay đổi sang "mẹ gửi tay" cho đến khi có nhà cung cấp và xác nhận pháp lý?
2. Bố và mẹ có cần hai tài khoản web riêng không (câu Đ1)?
3. Khi bot báo "khách muốn mua", mẹ nhận thông báo bằng đường nào?

## Bổ sung: kiểm tra yêu cầu "giao diện 100% tiếng Việt"

Cách làm: quét toàn bộ chữ trên 16 board Penpot, tìm chữ không có dấu tiếng Việt và một danh sách từ khóa tiếng Anh; đọc chữ trong các mẫu giao diện của PR #3 (đã đọc 8 trong 12 file: `bot`, `campaign-detail`, `campaigns`, `fragments`, `handoffs`, `landing`, `login`, `sending`; chưa đọc `dashboard`, `groups`, `landing-stopped`, `landing-unavailable`). Tên hãng (Viettel Post, J&T Express) và tên riêng (Zalo) coi là tên riêng, không tính.

### Trên Penpot: còn chữ tiếng Anh hoặc mã nội bộ ở các màn sau (đọc trực tiếp)

| Màn | Chữ còn sót | Gợi ý |
|---|---|---|
| 3 Chi tiết khách | Mã nhật ký `BASIS_APPROVED`, `EXPORTED` (cột "Việc"); người thực hiện `admin`; mã bằng chứng `VIETTEL_POST:VTP000000001`, `JT000000002` | Nhật ký ghi "Duyệt cơ sở liên hệ", "Xuất dữ liệu khách" (đã có ở cột "Chi tiết" nên bỏ cột mã); người thực hiện ghi "Bố" hoặc "Mẹ"; bằng chứng ghi "Viettel Post, vận đơn VTP000000001" |
| 4 Danh sách mời | Cột "Bằng chứng": `VIETTEL_POST:VTP0001`, `JT_EXPRESS:JT0002`, `VIETTEL_POST:VTP0003` | "Viettel Post · vận đơn VTP0001" |
| 8 Tài khoản Zalo của bot | "(DRY_RUN)", "(kill switch)", "Bot", "quét mã QR" | "Chỉ ghi dự kiến, chưa gửi thật"; "Tạm dừng trợ lý ngay"; dùng "Trợ lý AI" thay "Bot"; "quét mã QR" có thể giữ vì quen thuộc, hoặc "quét mã vuông" |
| 14 Việc cần mẹ xử lý | "bot" (nhiều chỗ) | "trợ lý AI" |
| 1, 11 | "bot", "Việc bot chuyển cho mẹ" | "Việc trợ lý AI chuyển cho mẹ" |
| 12 Chiến dịch mời | "BR-16" (mã tài liệu), "SMS brandname" | Bỏ "(BR-16)"; "tin nhắn SMS có tên cửa hàng" |
| 15 Cài đặt gửi tin | "SMS", "brandname", "[QC]", tên eSMS/SpeedSMS | "SMS" quen với người dùng nên có thể giữ; "brandname" đổi thành "tên cửa hàng hiển thị"; "[QC]" giải thích là "nhãn quảng cáo" |
| 1, 3, 16 | "CSV" (nút tải) | "Tải bảng tính (CSV)" hoặc "Tải về Excel" cho mẹ dễ hiểu |
| 2, 3, 4, 16 | Tên mẫu không dấu (Nguyen Van A...) | Chỉ là dữ liệu mẫu; nếu muốn đúng giao diện mẫu thì dùng tên có dấu (Nguyễn Văn A...) |
| 7, 14 | Chữ cái "B", "M" trong hình tròn | Là chữ cái đầu tên (Bố, Mẹ); không phải tiếng Anh |

Các màn 5, 6, 9, 10, 13, 15 (ngoài các chỗ trên) không phát hiện chữ tiếng Anh. Màn 7 nên chú ý: ngoài hai chữ cái, các chữ khác đều là tiếng Việt.

### Trong mẫu giao diện của PR #3 (đối chiếu để Penpot và code cùng sửa) — đọc trực tiếp

- `bot.html`, bảng "Bot xử lý gần đây": cột "Người nhắn" hiện thẳng mã `CUSTOMER`/`OWNER`/`BOT`, cột "Kết quả" hiện `ANSWERED`/`HANDOFF`, kèm mã ý định. Cần ánh xạ sang "Khách", "Mẹ", "Trợ lý AI", "Đã trả lời", "Đã báo mẹ".
- `bot.html`: tiêu đề "Bot trợ lý trong nhóm" và nhiều chỗ "bot" (nên "Trợ lý AI"); đường dẫn `docs/ADR-0001-chon-kenh-bot.md` hiện trong giao diện (chữ không phải tiếng Việt và mẹ không mở được); "FAQ" trong tiêu đề mục nên đổi "Câu hỏi thường gặp".
- `sending.html`: cột "Kênh" hiện thẳng mã như `MOCK`; cần đổi thành "Chạy thử" / "Mẹ gửi tay" / "SMS".
- `campaign-detail.html`: "tạo bởi admin" hiện tên tài khoản gốc; ghi "Bố"/"Mẹ" khi có hai tài khoản (Đ1).
- `handoffs.html`: "Đã nhận (admin)" tương tự.
- `login.html` đã tiếng Việt hoàn toàn (kể cả báo khóa 15 phút sau nhiều lần sai; màn 7 chưa có trạng thái này).
- `fragments.html`, `landing.html`, `campaigns.html`: không phát hiện chữ tiếng Anh.
- **Chưa kiểm tra:** `dashboard.html`, `groups.html`, `landing-stopped.html`, `landing-unavailable.html`; và các chuỗi do code Java sinh ra (thông báo lỗi, `flash`, nhãn ý định, lý do từ chối) vì chưa đọc mã Java. Nên rà riêng một lượt.
