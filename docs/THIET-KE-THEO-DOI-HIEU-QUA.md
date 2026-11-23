# Bảng theo dõi hiệu quả chiến dịch (23/11/2026)

Trang `/performance` ("Hiệu quả chiến dịch", nhóm menu "Mời vào nhóm"). Gói `vn.shop.crm.performance`. **Không có migration**, không gửi tin, không gọi mạng, không gọi AI; chỉ đọc dữ liệu đã có và lưu 4 giá trị nhập tay vào `app_setting`.

## 1. Cái gì được đo

Mỗi chiến dịch một dòng, và một dòng tổng:

| Bước | Cách đếm |
|---|---|
| Đã mời | lời mời có `sent_at` (đã nhắn Tin 1) |
| Đã kết bạn | lời mời có `friended_at` |
| Đã vào nhóm | lời mời có `joined_at` |
| Người mua, lần mua | khách đã vào nhóm có lần mua **giao thành công tạo sau lúc vào nhóm** (và trước lúc rời nhóm nếu có) |
| Doanh thu | tổng `cod_collected` (tiền J&T thu hộ) của các lần mua đó, lấy từ file "Lịch sử đối soát" |
| Cước | `freight_fee + cod_fee` của các dòng đối soát đó |

Lưu ý: file Viettel Post chỉ có ngày (không có giờ), nên đơn tạo đúng ngày khách vào nhóm có thể bị xếp là trước khi vào nhóm. File J&T có giờ nên không bị.

Quy tắc "một lần mua" giống `CustomerClassifier`: cùng mã đơn bán hàng là một lần mua, không có mã thì mỗi vận đơn là một lần mua. Lần mua chưa có dòng đối soát vẫn được đếm nhưng chưa có tiền (trang ghi "chưa đối soát").

## 2. So với TikTok: giới hạn của dữ liệu

Đơn TikTok **không có trong hệ thống** (BR-01, đơn TikTok ngoài phạm vi), nên không đo được doanh thu TikTok thật. Có ba thứ thay thế, đều ghi rõ trên trang:

1. **Hòa vốn phí TikTok.** Với đúng các đơn bán qua nhóm: giá TikTok của cùng đơn = doanh thu / (1 - ưu đãi). Chi phí ưu đãi = giá TikTok - doanh thu. Hòa vốn = (ưu đãi + ship miễn) / giá TikTok. Nếu TikTok thu (hoa hồng, phí giao dịch, voucher) nhiều hơn mức này thì bán qua nhóm có lời hơn. Không cần biết số TikTok thật.
2. **Lời lỗ cụ thể**, khi chủ shop nhập "TikTok thu bao nhiêu % giá bán": lời = phí TikTok tránh được - ưu đãi - ship miễn.
3. **So sánh mua lại** (không cần dữ liệu TikTok): trong 30 ngày sau khi nhận Tin 1, bao nhiêu khách vào nhóm có thêm một lần mua, so với khách được mời nhưng không vào nhóm. Chỉ tính người đã nhận Tin 1 từ 30 ngày trở lên. Đây là số tham khảo (người chịu vào nhóm vốn thích shop hơn).

Giá trị chủ shop nhập (lưu ở `app_setting`, khóa `performance.*`): ưu đãi % (mặc định 10, đúng Tin 1), miễn ship (mặc định có), phí TikTok % (mặc định để trống), tiền tố mã đơn của đơn qua nhóm (mặc định để trống).

## 3. Phân biệt đơn qua nhóm với đơn khác: câu hỏi cho chủ dự án

File J&T/Viettel Post không ghi đơn đến từ đâu. Hiện có hai mức:

- **Chưa đặt tiền tố (mặc định):** mọi lần mua giao thành công của khách SAU KHI vào nhóm được coi là đơn qua nhóm. Trang ghi rõ "số mua là ước tính" (có thể gồm đơn khách tự đặt ngoài nhóm).
- **Đã đặt tiền tố** (ví dụ `NHOM`): khi tạo đơn gửi hãng vận chuyển cho đơn bán trong nhóm, ghi mã đơn hàng bắt đầu bằng `NHOM-`. Chỉ những lần mua có mã đơn đó mới được tính. Đây là cách ghi nhận đề xuất vì không cần đổi phần mềm hay hỏi khách.

## 4. Riêng tư

- Trang quản trị chỉ có số tổng hợp theo chiến dịch (không có tên, số điện thoại, mã khách, mã Zalo).
- Báo cáo xuất `/performance.csv` và hàm cho AI `PerformanceService.anonymized()`: chiến dịch có ít hơn 5 người vào nhóm (`MIN_CELL`) bị ẩn số mua, tiền và so sánh; tỉ lệ mua lại của nhóm dưới 5 người bỏ trống. Test khóa việc không có tên, số điện thoại hay mã lời mời lọt vào bản ẩn danh và CSV.
- Không đụng việc xóa số điện thoại khách (CLAUDE.md mục 5a).

## 5. Cho luồng "AI đề xuất chiến dịch" (D21, PR 76)

Dùng `PerformanceService.anonymized()` trả `Overview(campaigns, total, settings)`; mỗi `CampaignPerformance` là bản ghi chỉ có số (và `scopeLabel` do mã đặt, tên chiến dịch do chủ shop gõ nên **không đưa tên cho AI**, dùng mã vùng và số liệu). Phễu theo vùng đã có ở `AdvisorSnapshot`; số liệu theo chiến dịch (mua, doanh thu, hòa vốn, mua lại) là phần bổ sung, chưa đưa vào AI để tránh đụng gói `advisor`.

## 6. Việc chưa làm

- Phân tích theo khu vực hoặc theo giai đoạn (so cùng thời tiết) khi đã có nhiều chiến dịch.
- Đưa số liệu này vào snapshot của AI (PR kế tiếp của luồng D21).
