# Giấy in giới thiệu nhóm Zalo (bản nháp nội dung, chưa vẽ Penpot)

Khổ A6 dọc (105 x 148 mm), in một mặt, bỏ vào gói hàng. Chữ nội dung ≥ 13pt khi in (khoảng 17px trên màn hình), tiêu đề 22pt trở lên.

## Bố cục (từ trên xuống)

1. **Tên cửa hàng** (dòng nhỏ, căn giữa): "{tên cửa hàng}"
2. **Tiêu đề lớn:** "Cảm ơn anh/chị đã mua hàng!"
3. **Lời giới thiệu (2 câu):** "Cửa hàng có một nhóm Zalo nhỏ, khoảng 5 khách mua hàng, để anh/chị hỏi cách dùng. Trong nhóm có chủ cửa hàng và một trợ lý AI của cửa hàng trả lời các câu hỏi thông thường."
4. **Ba bước:**
   - "1. Mở Zalo, quét mã vuông bên dưới."
   - "2. Bấm nút «Vào nhóm Zalo»."
   - "Không cần nhập mã xác nhận hay số điện thoại."
5. **Mã QR tĩnh** (cạnh ≥ 35 mm, viền trắng quanh mã) và dưới mã là **địa chỉ ngắn dễ gõ**: "{địa chỉ ngắn}", chữ lớn đậm.
6. **Dòng tự nguyện:** "Vào nhóm là tự nguyện. Không vào cũng không sao, anh/chị vẫn gọi điện cho cửa hàng như bình thường."
7. **Số điện thoại cửa hàng** (to, đậm): "{số điện thoại}"
8. **Dòng ngừng nhận tin (chữ nhỏ nhưng ≥ 10pt):** "Không muốn nhận tin từ cửa hàng nữa? Anh/chị gọi hoặc nhắn Zalo vào số trên, cửa hàng sẽ ghi lại và không liên hệ nữa."

## Những điều cố ý KHÔNG viết (vì chưa chắc)

- Không hứa thời gian trả lời, không hứa bảo hành hay giá, không hứa khuyến mãi.
- Không nói "đảm bảo đủ 5 người" (nhóm chỉ khoảng 5 khách, có thể lệch).
- Không viết trợ lý AI "luôn đúng" hay "24/7" (bot có thể tắt hoặc hỏng).

## Chữ sửa được trên web (không cứng trong ảnh)

Các ô văn bản sau sẽ sửa ở một trang cài đặt "Giấy in giới thiệu" trên web; Penpot chỉ vẽ mẫu với chữ mặc định ở trên.

| Ô sửa được | Giá trị mặc định |
|---|---|
| Tên cửa hàng | (cấu hình sẵn) |
| Tiêu đề | Cảm ơn anh/chị đã mua hàng! |
| Lời giới thiệu | như mục 3 |
| Ba bước | như mục 4 |
| Dòng tự nguyện | như mục 6 |
| Số điện thoại | (cấu hình sẵn) |
| Dòng ngừng nhận tin | như mục 8 |
| Địa chỉ ngắn (và mã QR sinh từ đó) | (cấu hình sẵn) |

## Điểm cần quyết hoặc làm khi code (ghi cho luồng code)

1. **Mã QR tĩnh = một link chung cho mọi khách**, không có mã riêng từng khách như link `/i/<token>` đang có. Cần một đường chung (ví dụ `/n`) dùng thuật toán chia nhóm hiện có, đếm lượt bấm gộp (không biết ai bấm), và chỉ nhận POST mới tính lượt bấm như trang khách hiện nay.
2. **Đã quyết (chủ shop, 21/11/2026): giấy bỏ vào MỌI gói hàng, kể cả khách mua từ 2 lần.** Đây là mời thụ động và tự nguyện (BR-08), khác với chiến dịch mời chủ động ở BR-06; danh sách 2+ lần vẫn không có nút mời. Lời trên giấy vì vậy không viết như chỉ dành cho khách mới (không dùng "lần đầu", "khách mới").
3. Trang in dùng CSS khổ A6 để mẹ bấm "In" từ trình duyệt; mã QR sinh ngay trên máy chủ (không gọi dịch vụ bên ngoài).
4. Địa chỉ ngắn cần tên miền ngắn và đã có HTTPS (mốc 1F).
