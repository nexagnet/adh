# Thiết kế Penpot mới: danh sách thay đổi cho luồng code

Trang Penpot: **"Bản chỉnh sửa (đã rà soát)"** (20 board) và **"Giấy in giới thiệu"** (1 tờ A5 và 1 ghi chú). Trang gốc "Page 1" giữ nguyên.

## Nguyên tắc thiết kế mới

- **Thanh bên trái chia nhóm** thay thanh trên cùng (đủ 12 mục, có đếm việc cần làm): Khách hàng (Tổng quan, Nhập đơn, Khách mua 1 lần, Khách mua từ 2 lần, Trạng thái đơn); Mời vào nhóm (Danh sách mời, Chiến dịch mời, Nhóm Zalo, Cài đặt gửi tin); Trợ lý AI (Việc cần người thật xử lý, Tài khoản trợ lý AI); Khác (Yêu cầu của khách). Tab đang mở được tô sáng đúng màn.
- **Mỗi màn một việc chính** (một nút nổi bật), việc phụ là nút viền, việc nguy hiểm (xóa, tạm dừng) màu đỏ và tách riêng.
- Chữ nội dung ≥ 17px, nhãn nhỏ ≥ 16px. Màu chữ tương phản cao. Trạng thái luôn có chữ, không chỉ dựa vào màu.
- 100% tiếng Việt: bỏ mã nội bộ (BASIS_APPROVED, EXPORTED, VIETTEL_POST:..., admin, BR-16, DRY_RUN, kill switch, "bot"). "Bot" đổi thành "trợ lý AI"; "CSV" đổi thành "Excel". Còn giữ tên riêng (Viettel Post, J&T Express, Zalo, SMS, QR).

## Thay đổi theo màn

| Màn | Thay đổi chính |
|---|---|
| 1 Tổng quan | Khối "Việc cần làm hôm nay" ở đầu (121 chờ duyệt, yêu cầu quá hạn, việc trợ lý chuyển cho mẹ, trạng thái chưa rõ nghĩa), mỗi việc có nút đi tới. Số liệu nhóm lại; thêm "Chỗ trống trong các nhóm". Sửa mâu thuẫn số liệu với màn 4 và 12. |
| 2 Khách mua 1 lần | Bộ lọc có đếm; cột "Duyệt mời" và cột mới "Lời mời" (chưa mời, đã gửi, đã bấm); phân trang. Dữ liệu mẫu không còn trùng với danh sách mua từ 2 lần. |
| 3 Chi tiết khách | Hai cột: trái là mua hàng, ghi chú, nhật ký; phải là cơ sở liên hệ, lời mời, quyền dữ liệu của khách, xóa dữ liệu (khung đỏ riêng). Nhật ký ghi "Bố/Mẹ" thay admin. |
| 4 Danh sách mời | Thẻ đếm thành bộ lọc; khung "Duyệt 121 khách" tách riêng, rõ "duyệt chưa gửi tin cho ai"; bảng đã duyệt có bằng chứng bằng chữ tiếng Việt. Nút chính "Tạo chiến dịch mời". |
| 5 Yêu cầu của khách | Hạn theo loại yêu cầu (ngừng liên hệ và xuất dữ liệu 2 ngày làm việc; xóa 20 ngày; tạm đặt, cần xác nhận pháp lý); nhãn "Quá hạn"; nút "Ghi nhận yêu cầu mới". |
| 6 và 9 Nhập file | Thêm thanh bốn bước. Màn 6: cột nhập dạng danh sách, cảnh báo khi thiếu mã đơn hàng của shop, ô "nhớ cách chọn cột". Màn 9: thêm "Đơn đổi trạng thái" và "Khách đổi nhóm". |
| 10 Trạng thái đơn | "Cần chọn nghĩa" lên trước, bảng "Đã biết nghĩa" sau. |
| 11 Nhóm Zalo | Sửa số đếm cho khớp bảng; thêm "Chỗ trống"; form thêm nhóm gọn trong một khung. |
| 12 Chiến dịch mời | Ba bước rõ ràng. Thêm nút **"Xuất danh sách để mẹ gửi tay"** làm việc chính; "Gửi tự động" bị khóa; hiển thị đúng hiện trạng (chưa có nhà cung cấp SMS); số khách khớp màn 4 (3 đã duyệt). Nội dung tin bỏ chữ "bảo hành". |
| 7 Đăng nhập | Chọn Bố hoặc Mẹ, mật khẩu riêng. Thêm trạng thái **7b: nhập sai nhiều lần, đợi 15 phút** (khớp code). |
| 8 Tài khoản trợ lý AI | Công tắc an toàn lên đầu; "Cách kết nối" là ba lựa chọn chưa chọn (Zalo Bot chính thức, Zalo OA, tài khoản cá nhân riêng có cảnh báo khóa); nút Kết nối bị khóa tới khi chọn. |
| 13 Trang khách (điện thoại) | Chữ to hơn; nói rõ trong nhóm có chủ cửa hàng và trợ lý AI; thêm nút **"Tôi không muốn nhận tin từ cửa hàng nữa"**. Thêm ba trạng thái: **13b đã ngừng nhận tin, 13c link hết hạn, 13d các nhóm đã đủ người** (khớp `landing-stopped` và `landing-unavailable`). |
| 14 Việc cần người thật xử lý | Dạng thẻ việc, mỗi thẻ một lý do, tóm tắt, nhóm, giờ và nút xử lý; thêm ghi chú "Chưa chọn cách báo cho mẹ". |
| 15 Cài đặt gửi tin | Thêm bảng **Nhật ký gửi gần đây**; nhà cung cấp SMS ghi "Chưa chọn"; nút tạm dừng tất cả. |
| 16 Khách mua từ 2 lần | Ghi chú "không có nút mời", bảng gọn, dữ liệu mẫu riêng. |

## Giấy in A5 (trang "Giấy in giới thiệu")

- A5 dọc, 148 x 210 mm; in cho **mọi gói hàng**, kể cả khách mua từ 2 lần (chủ shop đã chọn).
- Gồm: tên cửa hàng, tiêu đề cảm ơn, lời giới thiệu nhóm nhỏ khoảng 5 khách (có chủ cửa hàng và trợ lý AI), mã QR tĩnh và địa chỉ ngắn, hai bước quét mã, dòng "vào nhóm là tự nguyện", số điện thoại cửa hàng, dòng xin ngừng nhận tin.
- Không hứa thời gian trả lời, giá, khuyến mãi hay bảo hành.
- Mười dòng chữ sửa được trên web: tên cửa hàng, tiêu đề, lời giới thiệu, bước 1, bước 2, dòng không cần mã, địa chỉ ngắn, dòng tự nguyện, số điện thoại, dòng ngừng nhận tin.
- **Việc code:** trang cài đặt chữ giấy in; trang in khổ A5 (CSS `@page`); mã QR sinh trên máy chủ từ địa chỉ ngắn (không gọi dịch vụ ngoài); đường chung `/n` dùng thuật toán chia nhóm hiện có, đếm lượt bấm gộp, chỉ POST mới tính lượt bấm.

## Còn cần chủ shop quyết (không đổi được bằng thiết kế)

1. SMS làm kênh chính hay mẹ gửi tay trước (D4 so với file 03 mục 1).
2. Bố và mẹ có hai tài khoản web riêng không (Đ1)? **Đã trả lời 22/11/2026: không** (D17); thiết kế vẽ hai tài khoản nhưng code giữ một.
3. Mẹ nhận báo việc từ trợ lý bằng đường nào (màn 14 đang ghi "chưa chọn").
4. Địa chỉ ngắn và tên miền cho mã QR (cần HTTPS, mốc 1F).
