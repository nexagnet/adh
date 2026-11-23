# Làm lại giao diện quản trị (23/11/2026): đã làm, quyết định, việc còn dở

Người dùng (chủ shop) chê giao diện "quá rác" và dặn bỏ các màn đã lỗi thời so với nghiệp vụ (D18: hệ thống tự gửi Tin 1 theo chiến dịch, tự thêm khách đồng ý kết bạn vào nhóm, nhóm tự thêm và nhóm của link là **một** nhóm). Mẹ dùng **điện thoại**, nên bản điện thoại là ưu tiên.

## 1. Đã làm (đã gộp vào `main`)

| PR | Nội dung |
|---|---|
| #45 | Điện thoại: thanh bên thành thanh trên cùng, nút "Menu" (checkbox CSS, không JS); đầu trang xếp dọc; thẻ số 2 cột; biểu mẫu một cột. `static/responsive-tables.js`: bảng từ 4 cột trở lên thành thẻ có tên cột (bỏ qua `table.grid` và bảng dưới 4 cột). Sửa lỗi CSS `.notice strong:first-child`. `.formrow` thành lưới. Chạy thử trên Zalo: ô nhập số gọn (3 số đầu, còn lại trong "Thêm số khác"), hướng dẫn gập. Chiến dịch: hướng dẫn và hai khung soạn tin gập. |
| #46 | Gộp "Khách mua 1 lần" + "Khách mua từ 2 lần" thành mục **Khách hàng**; thêm nút lọc loại khách ở đầu trang danh sách. |

## 2. Danh sách màn: giữ, gộp, bỏ

| Màn | Quyết định | Trạng thái |
|---|---|---|
| Tổng quan, Nhập đơn (+ chi tiết, chọn cột, xem trước), Trạng thái đơn, Danh sách mời, Chiến dịch mời, Yêu cầu của khách, In giấy giới thiệu, Bản đồ khách, Việc cần mẹ xử lý, Tài khoản trợ lý AI, trang khách (`/moi`, `/i/{token}`) | Giữ | không đổi |
| Khách mua 1 lần / từ 2 lần | Gộp thành **Khách hàng** | Xong (#46) |
| **Chạy thử trên Zalo** (`/test-runs`) | **Giữ** tới khi chủ shop cấu hình xong chiến dịch thử và chạy thật, **sau đó xóa**. Chủ shop đã chốt: chiến dịch thử phải có trước khi chạy chiến dịch thật. Đừng xóa khi dọn màn lỗi thời. | Đã làm đẹp (#45); xóa sau |
| Zalo tài khoản phụ (`/zalo`) | Giữ, **luôn hiện trên menu** (nay là trung tâm điều khiển tự gửi, không còn là tùy chọn) | Xong |
| Cài đặt gửi tin (`/sending`) | **Gộp** vào Zalo tài khoản phụ (hạn mức, giờ nhắn, tạm dừng) | Xong: mục "Tạm dừng, giới hạn gửi tay và nhật ký chiến dịch" cuối `/zalo`; `/sending` chuyển hướng sang đó |
| Nhóm Zalo (`/groups`) + gán mã nhóm (`/zalo/groups`) | **Gộp một trang** (nhóm tự thêm và nhóm link phải là một) | Chưa làm |
| Lô nhắn hôm nay (`/today`) | **Bỏ hẳn khi tự gửi chạy** (chủ shop chọn: bỏ hẳn, không giữ dự phòng) | Chờ phần tự gửi ở backend |
| Chờ vào nhóm (`/friends`) | Bỏ cùng đợt, chỉ giữ danh sách **ngoại lệ** (kẹt, chưa có nhóm hợp khu vực) trong chi tiết chiến dịch | Chờ phần tự gửi |
| Nút nhắn tay (Đã nhắn, Đã kết bạn, Đã vào nhóm, Đã gửi link nhóm, Khách đã rời nhóm) và "Xuất danh sách để mẹ gửi tay" | Bỏ hẳn cùng đợt (chủ shop chọn "Bỏ hẳn khi tự gửi chạy") | Chờ phần tự gửi |

**Vì sao chưa xóa các màn nhắn tay:** đó là đường làm việc duy nhất cho tới khi phần tự gửi của luồng Zalo gộp; xóa trước thì không ai ghi nhận được "đã nhắn". Làm đợt xóa **sau** khi backend tự gửi gộp, và nhớ gỡ luôn test, controller (`TodayController`, `FriendsController`, các `POST /campaigns/{id}/invitations/{id}/...`) và mục menu.

## 3. Việc còn dở (theo thứ tự nên làm)

1. Sửa chữ lỗi thời theo D18 ("Hệ thống không tự gửi tin", "không đăng nhập Zalo", "mẹ gửi tay", "nhắn tay") ở: `campaigns.html`, `campaign-detail.html`, `today.html`, `sending.html`, `test-runs.html`, `test-run-detail.html`. Chỉ sửa sau khi chữ nghiệp vụ tự gửi được chốt (con số giãn cách, lô, hạn mức; Tin 2 có tự gửi không: xem file 03 mục 1I.11). Nhiều test kiểm chữ tiếng Việt cụ thể, sửa test theo.
2. (Xong) `NavMenu`: mục Zalo tài khoản phụ luôn hiện; đã gỡ "Cài đặt gửi tin".
3. (Cài đặt gửi tin đã gộp vào `zalo.html`.) Còn: gộp Nhóm Zalo + gán mã nhóm vào một trang (`groups.html`, `zalo-groups.html`). Hai việc này đụng file Zalo mà luồng backend đang sửa nên làm sau khi họ gộp, hoặc làm cùng họ.
4. Đợt xóa màn nhắn tay (mục 2). Đợt xóa `/test-runs` sau khi chủ shop chạy chiến dịch thật.
5. Chưa rà bản điện thoại kỹ cho các trang: Nhập đơn (chọn cột, xem trước), Trạng thái đơn, Danh sách mời, Việc cần mẹ xử lý, Tài khoản trợ lý AI, Yêu cầu của khách, In giấy giới thiệu, Zalo tài khoản phụ (`/zalo`). Chạy app cục bộ, chụp 390px, tìm chỗ tràn khung. Bảng nhiều cột đã tự thành thẻ; cần xem bảng nào nên giữ dạng bảng.
6. Trang Tổng quan: khối "Việc cần làm hôm nay" còn mục nhắn tay (khách đã kết bạn chờ thêm vào nhóm); sửa khi bỏ màn nhắn tay.

## 4. Mẹo cho phiên sau

- Chạy cục bộ: cần JDK 25 và Maven. Bản `mvnw` có thể bị chặn mạng nên dùng `mvn` hệ thống. PostgreSQL 16 cài sẵn (`pg_ctlcluster 16 main start`; tạo user `crm`, DB `crm`), chạy jar với `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_ADMIN_PASSWORD`. Để sửa CSS/mẫu không phải dựng lại jar: đặt `SPRING_THYMELEAF_PREFIX=file:.../templates/`, `SPRING_THYMELEAF_CACHE=false`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:.../static/`.
- Test tích hợp dùng PostgreSQL nhúng, **không chạy được bằng root**: chạy bằng người dùng thường trên bản sao repo.
- Dữ liệu mẫu để xem giao diện: nhập `fixtures/viettelpost_sample.csv` rồi `fixtures/jt_sample.csv`, thêm vài nhóm Zalo (`zalo_group`), duyệt khách ở Danh sách mời, tạo chiến dịch.
- `CustomerListFilterTest` **khóa thứ tự** khung "Lọc theo khu vực và công cụ khác" nằm trên các nút lọc và trên bảng. Đừng dời khung này mà không sửa test.
- CSP cấm script và style nội tuyến; `responsive-tables.js` là file tĩnh cùng nguồn nên hợp lệ.
- Bản đồ và `/print` có CSS riêng; `@media screen and (max-width: 700px)` (không phải `@media (max-width...)`) để giấy in A5 không bị ảnh hưởng.
