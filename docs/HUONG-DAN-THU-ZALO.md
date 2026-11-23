# Hướng dẫn thử trên tài khoản Zalo thật (chưa chạy chiến dịch thật)

Dành cho chủ cửa hàng. Mục đích: thử cả quy trình **bằng số điện thoại của bạn hoặc người quen đã đồng ý**, trước khi nhắn khách thật.

> **Cập nhật 23/11/2026 (D18):** chủ shop đã bỏ nguyên tắc "không tự gửi tin"; hệ thống sẽ tự gửi tin như người thật, gửi từ từ. Mới ghi tài liệu, **chưa có mã**; hướng dẫn dưới đây mô tả bản hiện tại (nhắn tay) và sẽ được sửa khi mã tự gửi gộp. Xem `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md` mục 1I.11.

**Nguyên tắc của bản hiện tại (trước D18):** hệ thống **không tự gửi tin** và **không đăng nhập Zalo**. Bạn tự nhắn bằng tài khoản Zalo phụ (cá nhân) trên điện thoại hoặc máy tính, rồi bấm nút ghi lại trên trang quản trị.

## Chuẩn bị (một lần)

1. Có tài khoản Zalo phụ để nhắn, và 1 đến 3 điện thoại khác có Zalo (của bạn hoặc người quen **đã đồng ý nhận tin thử**).
2. Vào **Nhóm Zalo**, tạo ít nhất hai nhóm thử đang **Đang nhận khách**, mỗi nhóm một miền (ví dụ nhóm Miền Bắc và nhóm Miền Nam), dán link nhóm thật. Chọn "Khu vực của nhóm" cho đúng.
3. Vào **Chiến dịch mời**, ở khung **Nội dung tin mặc định** soạn tin tư vấn, **có câu cho khách biết cách xin ngừng nhận tin** (ví dụ: "Nếu không muốn nhận tin, bạn nhắn \"ngừng\" mình sẽ không nhắn nữa"). Bấm lưu.

## Thử từng bước

Vào menu **Mời vào nhóm → Chạy thử trên Zalo**.

1. **Tạo đợt thử.** Đặt tên, kiểm tra nội dung tin, nhập số điện thoại thử (tối đa 10) và chọn **tỉnh/thành** cho từng số. Chọn tỉnh ở các miền khác nhau (ví dụ một số ở Hà Nội, một số ở Cần Thơ) để thử gợi ý nhóm theo khu vực. Bấm **Tạo đợt chạy thử**.
2. **Đọc tin.** Mở đợt thử: mỗi số có sẵn nội dung tin đã điền tên. (Link riêng chỉ có ở chiến dịch thật, trong đợt thử chỉ hiện chữ giữ chỗ.)
3. **Nhắn tay.** Dùng tài khoản Zalo phụ, tìm số đó trong Zalo, gửi kết bạn kèm tin hoặc nhắn tin. Xong bấm **Đã nhắn** ở đúng dòng.
4. **Kết bạn.** Trên điện thoại bên kia, chấp nhận kết bạn. Bấm **Đã kết bạn**.
5. **Kiểm tra nhóm theo khu vực.** Cột "Nhóm Zalo nên thêm vào" phải là nhóm **cùng khu vực** với số đó (số ở Hà Nội thì Nhóm Miền Bắc, số ở Cần Thơ thì Nhóm Miền Nam). Thêm số đó vào nhóm bằng tay trên Zalo, rồi bấm **Đã vào nhóm**. Nếu chưa có nhóm còn chỗ cho khu vực đó, hệ thống báo và không ghi gì.
6. **Thử từ chối.** Với một số, bắt đầu lại, nhắn "ngừng" từ điện thoại bên kia rồi bấm **Khách từ chối**. Trong đợt thử, việc này **không** vào danh sách từ chối thật.
7. **Dọn dẹp.** Bấm **Thử lại từ đầu** để làm lại, hoặc **Xóa đợt thử và các số đã nhập** khi xong (số điện thoại thử bị xóa hẳn).

### Đợt thử không đụng tới gì của khách thật

Đợt thử nằm ở bảng riêng. Chạy hết quy trình mà các thứ sau **không đổi**: trạng thái "đã nhắn" của khách thật, nhật ký gửi, danh sách từ chối, số khách trong nhóm Zalo. (Có test tự động kiểm điều này.)

## Sau khi thử xong, chạy chiến dịch thật (chỉ khi bạn quyết định)

1. **Chiến dịch mời**: bước 1 chọn **khu vực** (ví dụ trời rét thì chọn miền cần nhắn), đặt số khách tối đa, tạo chiến dịch. Hệ thống cho biết còn bao nhiêu khách đã duyệt ở từng miền.
2. Mỗi ngày mở **Lô nhắn hôm nay**: hệ thống chỉ gợi ý vài người đủ hạn mức còn lại của ngày và của giờ, trong giờ nhắn. Nhắn tay từng người, xong bấm **Đã nhắn**. Có gợi ý nhịp nghỉ giữa hai tin (mặc định 3 phút, chỉ để tham khảo).
3. Khách đồng ý kết bạn: mở chiến dịch bấm **Đã kết bạn**, thêm vào nhóm gợi ý rồi bấm **Đã vào nhóm**. Khách từ chối: **Khách từ chối** (không bao giờ nhắn lại).

## Hạn mức (đổi trong cấu hình máy chủ, không đổi được từ trình duyệt)

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `APP_SENDING_DAILY_LIMIT` | Tối đa số tin mỗi ngày | 20 |
| `APP_SENDING_HOURLY_LIMIT` | Tối đa số tin trong 60 phút gần nhất (0 = không giới hạn theo giờ) | 6 |
| `app.sending.window-start/end` | Giờ nhắn | 07:00 đến 22:00 |
| `app.sending.suggested-gap-minutes` | Nhịp gợi ý giữa hai tin | 3 phút |
| `app.invitations.max-campaign` | Số khách tối đa trong một chiến dịch | 300 |

## Cần biết trước khi nhắn khách thật

- Hạn mức và nhịp chậm chỉ để giữ việc nhắn **nhỏ, thong thả, lịch sự**. Đây **không phải** cam kết Zalo sẽ không hạn chế tài khoản phụ; tin tư vấn cho khách chưa đồng ý nhận tin vẫn có thể bị khách báo cáo.
- Chỉ nhắn **một lần duy nhất** cho mỗi khách và tôn trọng ngay mọi lời xin ngừng nhận tin (đã chốt ở D13, chủ cửa hàng đã nhận rủi ro ở D3).
- Bản hiện tại **không** có chế độ tự gửi tin lên Zalo (D18 sẽ thêm, chưa có mã). Công cụ giả thao tác, đổi dấu vân tay hay xoay vòng tài khoản **không** nằm trong D18 và vẫn sẽ không làm.
