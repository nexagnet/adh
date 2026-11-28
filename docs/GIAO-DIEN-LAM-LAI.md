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
| Tổng quan, Nhập đơn (+ chi tiết, chọn cột, xem trước), Trạng thái đơn, Danh sách mời, Chiến dịch mời, Yêu cầu của khách, In giấy giới thiệu, Bản đồ khách, Việc cần người thật xử lý, Tài khoản trợ lý AI, trang khách (`/moi`, `/i/{token}`) | Giữ | không đổi |
| Khách mua 1 lần / từ 2 lần | Gộp thành **Khách hàng** | Xong (#46) |
| **Chạy thử trên Zalo** (`/test-runs`) | **ĐÃ XÓA 24/11/2026 (V45)** theo lời chủ shop (thử bằng chiến dịch thật ở chế độ thử). | Đã làm đẹp (#45); xóa sau |
| Zalo tài khoản phụ (`/zalo`) | Giữ, **luôn hiện trên menu** (nay là trung tâm điều khiển tự gửi, không còn là tùy chọn) | Xong |
| Cài đặt gửi tin (`/sending`) | **Gộp** vào Zalo tài khoản phụ (hạn mức, giờ nhắn, tạm dừng) | Xong: mục "Tạm dừng, giới hạn gửi tay và nhật ký chiến dịch" cuối `/zalo`; `/sending` chuyển hướng sang đó |
| Nhóm Zalo (`/groups`) + gán mã nhóm (`/zalo/groups`) | **Gộp một trang** (nhóm tự thêm và nhóm link phải là một) | Một nửa: `/groups` đã hiện trạng thái mã nhóm của từng nhóm và dẫn sang `/zalo/groups` để gán. Chưa nhét danh sách nhóm của tài khoản phụ vào chính `/groups` vì việc đó gọi bộ nối mỗi lần mở trang và `/zalo/**` chỉ dành cho OWNER (cần quyết định riêng) |
| Lô nhắn hôm nay (`/today`) | **Bỏ hẳn khi tự gửi chạy** (chủ shop chọn: bỏ hẳn, không giữ dự phòng) | Chờ phần tự gửi ở backend |
| Chờ vào nhóm (`/friends`) | Bỏ cùng đợt, chỉ giữ danh sách **ngoại lệ** (kẹt, chưa có nhóm hợp khu vực) trong chi tiết chiến dịch | Chờ phần tự gửi |
| Nút nhắn tay (Đã nhắn, Đã kết bạn, Đã vào nhóm, Đã gửi link nhóm, Khách đã rời nhóm) và "Xuất danh sách để mẹ gửi tay" | Bỏ hẳn cùng đợt (chủ shop chọn "Bỏ hẳn khi tự gửi chạy") | Chờ phần tự gửi |

**Vì sao chưa xóa các màn nhắn tay:** đó là đường làm việc duy nhất cho tới khi phần tự gửi của luồng Zalo gộp; xóa trước thì không ai ghi nhận được "đã nhắn". Làm đợt xóa **sau** khi backend tự gửi gộp, và nhớ gỡ luôn test, controller (`TodayController`, `FriendsController`, các `POST /campaigns/{id}/invitations/{id}/...`) và mục menu.

### Rà soát màn thừa lần cuối (23/11/2026, chủ dự án chọn "chỉ dọn phần trùng")

Đã rà từng đường GET của ứng dụng: **không còn màn nào mồ côi** (mọi đường đều có liên kết từ menu hoặc từ trang khác). Không xóa màn nào, lý do:
- `/today`, `/friends`, các nút nhắn tay, "Xuất danh sách để gửi tay": chỉ xóa sau khi chủ dự án báo chạy thử xong (`/test-runs` đã xóa 24/11/2026).
- `/groups` và `/zalo/groups`: hai trang liên kết qua lại, cố ý giữ để khỏi gọi bộ nối mỗi lần mở danh sách nhóm.
- `/sending`: chỉ là đường chuyển hướng sang `/zalo#gui-tay` cho đường dẫn cũ, có test, giữ.
- `/bot`: trang trợ lý trong nhóm (giai đoạn 2), có công tắc riêng, giữ.
Phần "dọn" thực hiện: sửa chú thích lỗi thời của Tin 2 trong `InviteService` (còn ghi "người gửi dán tay, hệ thống không tự gửi"). Phần gỡ cấu hình trùng ở `/zalo`, `/test-runs`, `/today` thuộc PR 8 của D21, làm sau khi chạy thử xong.

## 3. Việc còn dở (theo thứ tự nên làm)

1. Sửa chữ lỗi thời theo D18 ("Hệ thống không tự gửi tin", "không đăng nhập Zalo", "mẹ gửi tay", "nhắn tay") ở: `campaigns.html`, `campaign-detail.html`, `today.html`, `sending.html`, `test-runs.html`, `test-run-detail.html`. Chỉ sửa sau khi chữ nghiệp vụ tự gửi được chốt (con số giãn cách, lô, hạn mức; Tin 2 có tự gửi không: xem file 03 mục 1I.11). Nhiều test kiểm chữ tiếng Việt cụ thể, sửa test theo.
2. (Xong) `NavMenu`: mục Zalo tài khoản phụ luôn hiện; đã gỡ "Cài đặt gửi tin".
3. (Cài đặt gửi tin đã gộp vào `zalo.html`.) Còn: gộp Nhóm Zalo + gán mã nhóm vào một trang (`groups.html`, `zalo-groups.html`). Hai việc này đụng file Zalo mà luồng backend đang sửa nên làm sau khi họ gộp, hoặc làm cùng họ.
4. Đợt xóa màn nhắn tay (mục 2). `/test-runs` đã xóa 24/11/2026.
5. Chưa rà bản điện thoại kỹ cho các trang: Nhập đơn (chọn cột, xem trước), Trạng thái đơn, Danh sách mời, Việc cần người thật xử lý, Tài khoản trợ lý AI, Yêu cầu của khách, In giấy giới thiệu, Zalo tài khoản phụ (`/zalo`). Chạy app cục bộ, chụp 390px, tìm chỗ tràn khung. Bảng nhiều cột đã tự thành thẻ; cần xem bảng nào nên giữ dạng bảng.
6. Trang Tổng quan: khối "Việc cần làm hôm nay" còn mục nhắn tay (khách đã kết bạn chờ thêm vào nhóm); sửa khi bỏ màn nhắn tay.

## 4. Mẹo cho phiên sau

- Chạy cục bộ: cần JDK 25 và Maven. Bản `mvnw` có thể bị chặn mạng nên dùng `mvn` hệ thống. PostgreSQL 16 cài sẵn (`pg_ctlcluster 16 main start`; tạo user `crm`, DB `crm`), chạy jar với `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_ADMIN_PASSWORD`. Để sửa CSS/mẫu không phải dựng lại jar: đặt `SPRING_THYMELEAF_PREFIX=file:.../templates/`, `SPRING_THYMELEAF_CACHE=false`, `SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:.../static/`.
- Test tích hợp dùng PostgreSQL nhúng, **không chạy được bằng root**: chạy bằng người dùng thường trên bản sao repo.
- Dữ liệu mẫu để xem giao diện: nhập `fixtures/viettelpost_sample.csv` rồi `fixtures/jt_sample.csv`, thêm vài nhóm Zalo (`zalo_group`), duyệt khách ở Danh sách mời, tạo chiến dịch.
- `CustomerListFilterTest` **khóa thứ tự** khung "Lọc theo khu vực và công cụ khác" nằm trên các nút lọc và trên bảng. Đừng dời khung này mà không sửa test.
- CSP cấm script và style nội tuyến; `responsive-tables.js` là file tĩnh cùng nguồn nên hợp lệ.
- Bản đồ và `/print` có CSS riêng; `@media screen and (max-width: 700px)` (không phải `@media (max-width...)`) để giấy in A5 không bị ảnh hưởng.

## Bộ lọc cột, ghim cột và co giãn màn hình (23/11/2026)

- **Bảng khách** (`/customers`): bấm biểu tượng phễu ở tên cột thì mở cửa sổ nhỏ (`popover.js`, `table-filter.js`); bấm ra ngoài hoặc Esc thì đóng; điện thoại hiện thành khung trượt từ đáy. Khối lọc làm lại thành các nhóm có nhãn (Loại khách, Tình trạng, Khu vực), thẻ "Đang lọc" có dấu × để bỏ từng điều kiện và nút "Xóa tất cả bộ lọc" (giữ cách sắp xếp).
- **Các danh sách khác**: bảng có `data-filter` được lọc ngay trên máy (`list-filter.js`, cột ít giá trị thì chọn từ danh sách, còn lại gõ chữ không phân biệt dấu); bảng mới muốn có lọc chỉ cần thêm thuộc tính này. Điện thoại có nút "Lọc" mở khung gồm mọi cột. Lọc chỉ áp cho các dòng đang hiển thị trên trang.
- **Ghim cột đầu** (số điện thoại, tên, mã) khi cuộn ngang: bảng khách và các bảng có `data-sticky`; bóng mờ hiện khi đã cuộn. Bảng ghim bỏ `overflow: hidden` của `table` (nếu không thì `position: sticky` không hoạt động), viền và bo góc do khung `.table-wrap` lo. Điện thoại (≤ 700px) bảng thành thẻ nên không ghim.
- **Co giãn**: thanh bên chuyển thành thanh trên cùng từ 1100px (trước là 900px); độ rộng thanh bên và lề trang co theo màn hình; thẻ số liệu tự chia cột theo chỗ trống; bốn thẻ xếp 2 x 2 ở màn vừa.

## Đơn giản hóa giao diện cấu hình (24/11/2026, PR #120 đến #125)

Chủ dự án thấy các trang cấu hình rối, không tập trung. Kế hoạch gốc: `KE-HOACH-DON-GIAN-HOA-GIAO-DIEN-CAU-HINH.md` (cùng thư mục `docs/`). Chỉ đổi giao diện, không đổi hành vi gửi tin, hạn mức, cầu dao, chế độ thử.

- **Thanh menu** chia nhóm "Hằng ngày", "Chiến dịch", "Cài đặt", và mục "Khác" gấp lại (`NavMenu`, `<details class="nav-more">`).
- **`/zalo`** chia 4 tab chọn bằng `?tab=`: `tinh-trang` (mặc định), `gioi-han`, `nhom`, `thu`. Thanh an toàn (Dừng ngay, Tạm dừng tất cả) luôn hiện phía trên các tab. Mọi chuyển hướng sau khi lưu quay về đúng tab.
- **`/zalo/reply` (Trợ lý AI)** chia 4 tab: `hom-nay`, `bat-tat`, `thong-tin`, `nang-cao`.
- **`/campaigns`**: tab "Chiến dịch", "Tạo chiến dịch" (`?tab=moi`), "Đề xuất của AI". Fragment `campaignTabs` và `groupTabs` trong `fragments.html` (đừng dùng thẻ `<nav>` cho fragment mới: `~{fragments :: nav}` khớp cả thẻ `<nav>`, dùng `<div role="navigation">`).
- **Tổng quan** gọn: khối "Lời mời và nhóm" gấp vào "Xem thêm".
- **Chưa làm (cố ý):** `/handoffs` chưa chia tab (một hàng đợi duy nhất); fragment "nấc" dùng chung cho ba công tắc ba nấc; thay chữ "mẹ" bằng "người thật" ở mọi chỗ. Làm sau khi chạy thử xong và chủ dự án thấy cần.

## Bố cục vận hành ADH (27/11/2026)

- Menu dùng chung có dấu mục đang mở và liên kết bàn phím tới nội dung. Điện thoại dùng menu thu gọn. Các màn hình quản trị dùng cùng kiểu bảng, nút và trạng thái. Trang in vẫn dùng quy tắc riêng của từng khổ giấy.
- Tổng quan ưu tiên việc cần làm. Chiến dịch đưa trạng thái gửi, tài khoản gửi, đối chiếu và khách lên trước thiết lập. Zalo giữ lựa chọn tài khoản cạnh các điều khiển dừng. Màn nhóm đưa chủ nhóm và quyền dùng chung lên danh sách.
- Màn khách giữ tìm kiếm và phân loại ở đầu. Nhập đơn ưu tiên tải file. Đề xuất AI ưu tiên tiến độ và đề xuất đang chờ. Cài đặt trợ lý trả lời đưa chế độ trả lời lên đầu tab bật/tắt.
