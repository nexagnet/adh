# Kế hoạch đơn giản hóa giao diện cấu hình (24/11/2026)

> **Trạng thái:** kế hoạch gốc (chép từ thư mục dự án vào repo ngày 24/11/2026). Phần lớn **đã làm ở PR #120 đến #125**; phần đã làm và việc cố ý chưa làm nằm ở cuối `docs/GIAO-DIEN-LAM-LAI.md`. Các mục "chưa đổi mã" bên dưới là lúc viết kế hoạch.

Chỉ là kế hoạch, chưa đổi mã. Dựa trên `main` ở commit `cc8a87c` (đã có PR #118 bỏ Tin 2), đọc trực tiếp các mẫu trang `zalo.html`, `zalo/reply.html`, `campaigns.html`, `campaign-detail.html`, `handoffs.html`, `dashboard.html` và `NavMenu`. Chưa chụp ảnh app chạy thật.

## Tóm tắt một đoạn

Giao diện rối vì **mọi thứ đều nằm cùng một mức**: công tắc bật tắt, hạn mức, việc phải làm hằng ngày, nhật ký và cả chữ giải thích dài đều xếp chồng trên cùng một trang. Trang `/zalo` có khoảng 15 khối, trang trợ lý AI có 8 khối, menu có 16 mục. Cách sửa: **mỗi trang chỉ một việc chính, việc hằng ngày hiện trước, cấu hình hiếm dùng chuyển sang tab "Cài đặt" hoặc gập vào "Nâng cao"**, chữ ngắn, không đổi logic gửi tin nào.

## 1. Những chỗ rối hiện nay

| # | Chỗ rối | Ví dụ cụ thể |
|---|---|---|
| 1 | `/zalo` quá dài, trộn nhiều việc khác nhau | Đăng nhập, sức khỏe, chế độ thử, hạn mức thêm nhóm, hạn mức gửi tin, hạn mức lời mời, tạo nhóm, phó nhóm, AI đọc từ chối, khách quay lại, khách chờ vào nhóm, kết bạn không rõ, nhật ký, bảng "tự động dừng khi" (469 dòng). Thanh "đi nhanh" có 8 nút chỉ để chống lạc. |
| 2 | Hạn mức nằm rải 5 chỗ | 3 form ở `/zalo` (thêm nhóm, gửi tin, lời mời) + hạn mức tạo nhóm + lịch riêng từng chiến dịch. Người dùng không biết chỗ nào "thắng". |
| 3 | Bốn bộ "nấc" riêng | Nấc hoạt động, nấc trả lời, nấc khách quay lại, nấc tạo nhóm: mỗi bộ có radio riêng, ô tick "tôi đã thử" riêng, câu giải thích riêng. |
| 4 | Chữ kỹ thuật lọt ra giao diện | "Cần bật cả ba công tắc ở máy chủ: APP_ZALO_INBOX_ENABLED, BRIDGE_INBOX_ENABLED, APP_AI_ENABLED" xuất hiện ở cả `/zalo` và `/zalo/reply`. Người không rành kỹ thuật không làm được gì với câu này. |
| 5 | Đoạn giới thiệu đầu trang quá dài | `/zalo` mở bằng 6 dòng chữ liền; `/zalo/reply` có hẳn khối "Cách trợ lý làm việc" 5 gạch đầu dòng; `/campaigns` có hướng dẫn 6 bước + khối thông báo + thẻ số. |
| 6 | Chữ đã lỗi thời | `zalo.html` còn nhắc "Tin 2" ở 4 chỗ dù Tin 2 đã bỏ (V47). Còn lẫn "mẹ" (cứng) với "người thật" và "bạn". |
| 7 | Việc hằng ngày bị chôn trong trang cấu hình | "Khách chờ vào nhóm" và "Kết bạn nhưng không biết là khách nào" là việc phải bấm hằng ngày nhưng nằm giữa trang `/zalo`. Nháp của trợ lý nằm cuối `/zalo/reply` sau 6 khối cấu hình. |
| 8 | Một khối làm hai việc | `/zalo/reply` trộn: công tắc bảo hành, nấc trả lời, bảng thông tin cửa hàng (hay sửa), hồ sơ gửi AI (hiếm đụng), ô "Thử một câu", nháp. |
| 9 | Chi tiết chiến dịch | Đã chia 4 tab (tốt), nhưng tab "Tổng quan" vẫn dồn: duyệt, tự nhận khách, nội dung tin, chạy thử khô, hủy chưa gửi, gửi tay dự phòng, bảng lời mời, xem trước từng khách. |
| 10 | Trang `/campaigns` vừa tạo vừa quản lý | Form tạo 3 bước nằm trên danh sách; danh sách thiếu cột số lần gửi, nguồn (AI hay tay), trạng thái. |
| 11 | Menu quá nhiều | 4 nhóm, 16 mục; nhóm "Mời vào nhóm" một mình có 8 mục, trong đó 2 mục nhắn tay sẽ bỏ. `/zalo/reply` và `/imports/settlement` nằm ngoài lối đi chính. |
| 12 | `/handoffs` 6 khối | Cần xem, bảo hành, cần trả lời, khách quay lại, đã gỡ cờ, AI đã ghi từ chối: việc cần làm lẫn với lịch sử. |
| 13 | Điện thoại | Mẹ dùng điện thoại nhưng chưa rà ở 390px cho các trang cấu hình (mới sửa `/zalo/reply`). |

## 2. Nguyên tắc (để mọi trang mới theo cùng một khuôn)

1. **Một trang, một việc chính.** Đặt tên trang bằng việc, không bằng cơ chế ("Tài khoản Zalo phụ" chứ không "Bộ nối").
2. **Ba tầng.** Tầng 1 "Hôm nay": việc phải bấm và con số cần nhìn. Tầng 2 "Cài đặt": làm một lần, đổi hiếm, để ở tab riêng. Tầng 3 "Nâng cao": gập (`<details>`), mặc định đóng.
3. **Việc hằng ngày luôn ở đầu**, cấu hình ở sau hoặc ở tab "Cài đặt". Mở trang ra là thấy ngay việc cần làm, không phải cuộn.
4. **Chữ ngắn.** Mỗi khối tối đa một câu mô tả (khoảng 25 từ). Giải thích dài đưa vào "Tìm hiểu thêm" (gập). Không nhắc tên biến môi trường trên trang; trang chỉ nói "Chưa sẵn sàng, cần bật ở máy chủ", kèm nút "Xem hướng dẫn".
5. **Mặc định hợp lý, ít ô phải điền.** Trang chính chỉ hiện 2 đến 3 số quan trọng nhất (khách nhắn mỗi ngày, lời mời mỗi ngày). Các số còn lại (nghỉ giữa tin, trần giờ, phút nghỉ tạo nhóm) vào "Nâng cao", có nút "Khôi phục mặc định".
6. **Một khuôn "nấc" duy nhất:** `Tắt` / `Thử (người duyệt từng việc)` / `Tự động`. Nút Tự động bị khóa kèm lý do một dòng cho tới khi đã chạy nấc Thử (xem quyết định số 5). Dùng chung cho thêm nhóm, trả lời khách, khách quay lại, tạo nhóm.
7. **An toàn không bao giờ bị gập.** Dải "Đang chế độ thử / Đang CHẠY THẬT", nút **Dừng ngay** và **Tạm dừng tất cả chiến dịch**, cảnh báo sức khỏe luôn hiện trên mọi trang Zalo. Chỉ di chuyển nơi đặt cấu hình, không đổi giới hạn hay logic nào.
8. **Chữ tiếng Việt 100%, "trợ lý AI", không "bot".** Thống nhất người đọc: "bạn" cho người dùng, "người thật" cho hàng đợi xử lý, bỏ "mẹ" cứng trong chữ trên trang.
9. **Điện thoại trước.** Thiết kế ở 390px: một cột, nút rộng, tab cuộn ngang (đã có `.tabs`), không bảng rộng trong tab đầu.
10. **Tab chạy ở máy chủ** (`?tab=...`, như chi tiết chiến dịch đang làm), không dùng script nội tuyến vì CSP cấm.

## 3. Sơ đồ trang mới

### 3.1 Menu (còn 3 nhóm, 11 mục thay vì 16)

```
HẰNG NGÀY
  Tổng quan
  Việc cần người thật xử lý      (số việc chờ)
  Khách hàng
  Nhập đơn

CHIẾN DỊCH
  Chiến dịch mời                 (có tab "Đề xuất của AI", số đề xuất mới)
  Nhóm Zalo                      (có tab "Chờ vào nhóm")
  Hiệu quả

CÀI ĐẶT
  Tài khoản Zalo phụ             (số cảnh báo)
  Trợ lý AI
  Khác  >  Yêu cầu của khách, Trạng thái đơn, In giấy, Bản đồ
```

Gỡ khỏi menu: "Chờ vào nhóm" (vào tab của Nhóm Zalo), "Lô nhắn hôm nay" (giữ trang, ẩn khỏi menu, xóa sau khi thử xong), "Đề xuất của AI" (thành tab). Các địa chỉ cũ vẫn mở được để không gãy liên kết và test.

### 3.2 Tổng quan (`/`)

```
[ dải: chế độ thử / chạy thật ]
CẦN LÀM HÔM NAY
  - 3 việc cần người thật       [Mở]
  - 1 đề xuất mới của AI        [Xem]
  - Cảnh báo Zalo (nếu có)      [Xem]
TỰ GỬI HÔM NAY   (1 hàng)
  Đang BẬT · đã nhắn 4/10 · mời kết bạn 3/10 · đang trong giờ gửi   [Dừng ngay]
CON SỐ        (4 thẻ: khách, lời mời, đã vào nhóm, nhóm đang nhận)
[ gập ] Đơn theo hãng · Các lần nhập gần đây
```

### 3.3 Tài khoản Zalo phụ (`/zalo`), 4 tab

```
Tab 1  TÌNH TRẠNG (mặc định)
  Đăng nhập: Đã nối ✓            [Đăng xuất]
  Tự gửi tin:  (BẬT/TẮT) công tắc lớn      [Dừng ngay]
  Sức khỏe tài khoản: 1 dòng + cảnh báo nếu có
  Hôm nay: nhắn 4/10 · lời mời 3/10 · giờ gửi 08:00 đến 20:00
Tab 2  GIỚI HẠN AN TOÀN
  Khách nhắn mỗi ngày [10]   Lời mời kết bạn mỗi ngày [10]   [Lưu]
  [ gập Nâng cao ] nghỉ giữa tin, thêm nhóm mỗi giờ/ngày, phút nghỉ,  [Khôi phục mặc định]
  [ gập ] "Hệ thống tự dừng khi..." (bảng hiện có thu thành gập)
Tab 3  NHÓM TỰ ĐỘNG
  Nấc tạo nhóm: Tắt / Thử / Tự động
  Phó nhóm: [người đang chọn]  [Đổi]
  Nhóm đề xuất (bảng hiện có)
  [ gập Nâng cao ] hạn mức thêm người vào nhóm, gán mã nhóm Zalo
Tab 4  CHẾ ĐỘ THỬ VÀ NHẬT KÝ
  Số thử: danh sách + thêm, [Dựng khách thử]
  Nhật ký gửi gần đây (bảng)
  Tạm dừng tất cả chiến dịch
```

Chuyển đi: "Khách chờ vào nhóm" và "Kết bạn nhưng không biết là khách nào" sang tab **Chờ vào nhóm** của trang Nhóm Zalo; "Nhận diện từ chối" và "Khách quay lại" sang trang Trợ lý AI.

### 3.4 Trợ lý AI (`/zalo/reply`), 4 tab

```
Tab 1  HÔM NAY (mặc định)
  Trạng thái 1 dòng:  Sẵn sàng / Chưa sẵn sàng (cần bật ở máy chủ) [Xem hướng dẫn]
  Nháp chờ duyệt (nút Gửi / Bỏ)       Câu đã tự gửi 14 ngày (gập)
Tab 2  BẬT TẮT (mỗi việc một dòng, cùng khuôn nấc)
  Nhận diện khách từ chối      Tắt / Đang bật
  Trả lời khách tự nhắn        Tắt / Thử / Tự động
  Bảo hành và khiếu nại        Tắt / Bật
  Khách từng từ chối quay lại  Tắt / Chỉ tạo việc / Tự động
Tab 3  THÔNG TIN CỬA HÀNG
  Bảng thông tin (ô ưu đãi, ship, bảo hành) + ô "Thử một câu"
Tab 4  NÂNG CAO
  Hồ sơ gửi cho AI (các ô tick) · ngưỡng chắc chắn · "Cách trợ lý làm việc" (gập)
```

### 3.5 Chiến dịch

```
/campaigns       DANH SÁCH   [+ Tạo chiến dịch]   | tab: Chiến dịch · Đề xuất của AI (2)
  Cột: tên · nguồn (tay/AI) · trạng thái · số lần gửi · đã mời · đã vào nhóm
  Một dòng nhắc: "Chưa gửi gì cho tới khi bạn duyệt" (bỏ hướng dẫn 6 bước, đưa thành link)
/campaigns/new   TẠO MỚI (3 bước, trang riêng, mỗi bước một màn trên điện thoại)
/campaigns/{id}  4 tab hiện có, đổi nội dung:
   Tổng quan   : trạng thái, nút Duyệt/Dừng, tự nhận khách mới (công tắc), 4 số phễu, bảng lời mời
   Lịch và điều kiện : "Dùng mặc định hệ thống" (mặc định) + [Tùy chỉnh] mở ra form
   Chuỗi tin   : số lần gửi + nội dung tin; xem trước từng khách (gập)
   Nhóm và khách quay lại : như hiện nay
   Gập "Dự phòng" (chạy thử khô, gửi tay, hủy chưa gửi) xuống cuối tab Chuỗi tin
```

### 3.6 Việc cần người thật (`/handoffs`), 3 tab

`Cần xử lý` (gộp: cần xem + cần trả lời + bảo hành + khách quay lại, mỗi loại một nhãn màu) · `Đã xử lý` (gỡ cờ, AI đã ghi từ chối, có nút Hoàn tác) · `Cài đặt` (link sang Trợ lý AI).

## 4. Thứ tự làm (PR nhỏ, không migration, mỗi PR một việc)

| PR | Nội dung | Rủi ro |
|---|---|---|
| A | **Dọn chữ:** bỏ "Tin 2" còn sót, bỏ tên biến môi trường khỏi trang (thay bằng "Chưa sẵn sàng" + link hướng dẫn), rút đoạn giới thiệu, thống nhất "người thật". Chưa đổi bố cục. | Thấp. Phải sửa test kiểm chữ. |
| B | **Menu gọn** (3 nhóm), tab "Đề xuất của AI" và "Chờ vào nhóm" gắn vào trang cha. Địa chỉ cũ giữ nguyên. | Thấp. Cập nhật `NavMenu` và test của nó. |
| C | **`/zalo` thành 4 tab:** chỉ di chuyển khối, các địa chỉ POST giữ nguyên. | Trung bình (xem bẫy 1, 2). |
| D | **Chuyển phần AI** (đọc từ chối, khách quay lại) từ `/zalo` sang trang Trợ lý AI, đồng thời chia `/zalo/reply` thành 4 tab. | Trung bình. |
| E | **Khuôn "nấc" dùng chung** (một mẫu trang con cho 4 nơi) và trình bày nút Tự động bị khóa kèm lý do. | Trung bình. Không đổi điều kiện mở khóa trừ khi bạn quyết (câu hỏi 5). |
| F | **Chiến dịch:** tách `/campaigns/new`, thêm cột vào danh sách, gọn tab Tổng quan, "Dùng mặc định" ở tab Lịch. | Trung bình. |
| G | **Tổng quan và `/handoffs`** theo sơ đồ 3.2 và 3.6. | Thấp đến trung bình. |
| H | **Rà điện thoại 390px**, chụp ảnh trước sau từng trang, sửa lề, nút. Có thể làm ngay sau mỗi PR. | Thấp. |
| I | Sau khi bạn báo "thử xong": xóa `/today`, `/friends`, gửi tay (đã có trong `docs/GIAO-DIEN-LAM-LAI.md`). | Cần bạn báo trước. |

Mỗi PR: chạy `./mvnw verify`, chụp ảnh trước sau ở máy tính và điện thoại, ghi vào mô tả PR. PR chỉ mẫu trang (không đụng backend) thì chỉ cần CI xanh theo quy tắc của repo; PR nào sửa controller thì review kỹ như thường lệ.

## 5. Rủi ro và bẫy khi sửa

1. **Chuyển hướng sau khi lưu.** Nhiều form `/zalo/...` bấm xong sẽ `redirect:/zalo`. Khi thành tab, lưu xong phải về đúng tab, nếu không người dùng tưởng không lưu được. Cách làm: thêm trường ẩn `tab`, hoặc mỗi controller redirect kèm `?tab=`.
2. **Neo `#che-do-thu` đang được nơi khác trỏ tới** (dải chế độ thử, chi tiết chiến dịch, `/campaigns`). Phải đổi sang `?tab=` ở tất cả, tìm bằng `grep "/zalo#"`.
3. **Test kiểm chữ tiếng Việt cụ thể** (nhiều test, `CustomerListFilterTest` khóa thứ tự khung lọc). Sửa test cùng PR, không bỏ test.
4. **CSP cấm script và style nội tuyến**: tab và gập phải dùng `?tab=` và `<details>`.
5. **Mỗi lần gộp vào `main` là lên máy chủ trong khoảng 10 phút.** Bạn đang chuẩn bị chạy thử với 4 số. Không làm đổi chỗ các nút Dừng ngay, Tạm dừng, số thử, "Dựng khách thử" giữa lúc bạn đang thử; PR đụng `/zalo` (C, D) nên gộp vào thời điểm bạn không đang chạy.
6. **Không được làm mất lớp an toàn.** Gập "Nâng cao" chỉ áp dụng cho ô chỉnh số; các giới hạn vẫn nguyên, dải chế độ thử và nút dừng không bao giờ gập. Không thêm nút bật chạy thật lên web (`APP_ZALO_LIVE`).
7. **Tab chỉ nạp dữ liệu của tab đang mở** để nhẹ máy (máy chủ 2 GB RAM): phải tách chuẩn bị dữ liệu trong controller, không chỉ ẩn bằng giao diện.
8. **Penpot không dùng được trên cloud**, nên bản vẽ ở đây là sơ đồ chữ. Nếu muốn bản vẽ đẹp, bạn mở Penpot ở cửa sổ riêng để dựng sau (trang riêng "Bản chỉnh sửa").
9. **Hạn mức nằm ở nhiều chỗ nhưng có quan hệ "chiến dịch chỉ được chặt hơn hệ thống".** Khi gom vào một form, giữ nguyên quy tắc này và thông báo rõ ở trang chiến dịch ("đang dùng mặc định hệ thống: 10 khách mỗi ngày").
10. Số liệu "khoảng 15 khối, 16 mục menu" là đếm từ mã, chưa kiểm bằng ảnh chụp app thật; PR đầu tiên nên chụp ảnh hiện trạng làm mốc.

## 6. Cần bạn quyết

1. **Khi nào bắt đầu?** Khuyên: làm ngay PR A và B (chữ và menu, ít rủi ro), còn C đến G đợi sau vòng thử Zalo đầu tiên với 4 số, hoặc làm khi bạn báo không đang thử.
2. **Gộp "Đề xuất của AI" thành tab của Chiến dịch** và "Chờ vào nhóm" thành tab của Nhóm Zalo (menu bớt 2 mục)? Khuyên: có.
3. **Chuyển phần "AI đọc tin riêng, khách quay lại" khỏi `/zalo` sang trang Trợ lý AI?** Khuyên: có, vì đó là cấu hình của trợ lý, không phải của tài khoản.
4. **Gọi tên người đọc:** bỏ chữ "mẹ" cứng trên trang, dùng "bạn" và "người thật"? Khuyên: có, vì giai đoạn 2 có đội bán hàng.
5. **Ô tick "tôi đã thử xong" ở các nấc Tự động:** (a) giữ ô tick, chỉ đổi cách trình bày; hay (b) tự khóa/mở theo việc đã chạy (ví dụ đã có từ 5 nháp được duyệt)? Khuyên: (a) trước, vì (b) đổi logic an toàn.
6. **Chỉ hiện 2 số ở trang chính (khách nhắn mỗi ngày, lời mời mỗi ngày)**, các số còn lại vào "Nâng cao"? Khuyên: có.
7. **Có muốn bản vẽ Penpot** cho các trang mới trước khi code, hay đi thẳng vào mã rồi chỉnh theo ảnh chụp? Khuyên: đi thẳng vào mã, chụp ảnh điện thoại để bạn duyệt từng PR.
