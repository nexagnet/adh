# Hướng dẫn thử bộ nối Zalo tự động (D16) trên tài khoản thật

Dành cho chủ cửa hàng và người cài đặt. Bộ nối này **mặc định tắt**. Nó dùng thư viện không chính thức (`zca-js`) để biết khi nào khách chấp nhận kết bạn với **tài khoản Zalo phụ** và thêm khách vào nhóm Zalo của khu vực. Cách này **trái điều khoản của Zalo**: tài khoản phụ có thể bị hạn chế hoặc khóa. Chủ cửa hàng đã chấp nhận rủi ro này cho tài khoản phụ (22/11/2026). Tuyệt đối không dùng tài khoản chính của mẹ.

Toàn bộ phần mã đã được kiểm bằng test với **bộ nối giả**. **Chưa có lần nào chạy bản này với Zalo thật.** Mục "Những điều chưa ai kiểm" ở cuối nói rõ cái gì còn trống. Hãy đi từng bước nhỏ, dừng ngay khi thấy bất thường.

## Ranh giới (không đổi, có test khóa)
> **D18 (23/11/2026):** chủ shop đã bỏ nguyên tắc "không tự gửi tin" (gửi từ từ, như người thật, tài khoản phụ). Mới ghi tài liệu, chưa có mã; các ý "không có chức năng gửi tin" dưới đây mô tả bộ nối hiện tại. Xem ADR-0002 mục 12.


- Bộ nối chỉ làm **ba việc**: báo trạng thái, đọc sự kiện "đã thành bạn", thêm **một** người vào **một** nhóm. Không có chức năng gửi tin, gửi lời mời kết bạn, tìm người theo số, liệt kê bạn bè.
- **Không có cách nào làm cho giống người thật bấm** (không ngẫu nhiên giờ giấc, không đổi dấu vân tay thiết bị, không dùng proxy). Chỉ có giới hạn tốc độ và khung giờ 07:00 đến 22:00.
- Khách chưa chấp nhận kết bạn thì không bị tác động. Người lạ kết bạn không lưu lại gì.
- Gặp cảnh báo của Zalo, mất phiên đang lúc thêm người, lỗi lạ hoặc hết giờ chờ: **dừng ngay, không tự thử lại**. Chỉ người bấm "Mở lại" trên trang quản trị mới chạy tiếp.

## 1. Cài đặt (một lần, người cài đặt làm)

1. Tạo ba giá trị bí mật, ghi vào file `.env` trên máy chủ (cạnh file docker-compose), **không** đưa vào Git và **không** đưa vào bản sao lưu:
   - `APP_ZALO_BRIDGE_KEY`: chuỗi ngẫu nhiên dài, ví dụ `openssl rand -hex 24`.
   - `ZALO_SESSION_KEY`: đúng 64 ký tự hex, `openssl rand -hex 32`. Khóa này mã hóa phiên Zalo lưu trên đĩa. Mất khóa thì chỉ cần quét QR lại.
   - `ZALO_ACCOUNT_ID`: mã Zalo (chỉ chữ số) của **tài khoản phụ**. Bộ nối từ chối mọi tài khoản khác. **Chưa biết mã?** Đặt tạm `ZALO_ACCOUNT_ID=0`, chạy dịch vụ, vào trang **Zalo tài khoản phụ → Quét mã QR** và quét bằng điện thoại của **tài khoản phụ** (không phải tài khoản chính). Hệ thống sẽ báo "Sai tài khoản" nhưng **hiện mã Zalo của tài khoản vừa quét** ngay trên trang đó (chỉ admin thấy, không lưu, không ghi log); chép mã đó vào `ZALO_ACCOUNT_ID`, chạy lại dịch vụ rồi quét lần nữa. Không chụp màn hình trang đó.
2. Thêm vào `.env`:
   ```
   APP_ZALO_AUTO_ENABLED=true
   COMPOSE_PROFILES=zalo-auto
   ```
   Cần **cả ba**: cờ này, profile Docker, và chọn nấc ở trang quản trị. Thiếu một trong ba là bộ nối không chạy.
3. Chạy lại. Trên máy chủ dùng chung (`infra/docker-compose.shared.yml`): `./deploy.sh deploy`; script kéo ảnh dựng sẵn `ghcr.io/nexagnet/adh-zalo-bridge:<thẻ>` (do GitHub Actions dựng, không build trên máy chủ) khi `.env` có `COMPOSE_PROFILES=zalo-auto`. Với `docker-compose.prod.yml` (máy riêng): `docker compose up -d --build` (build ảnh tại chỗ). Nếu lỗi, gửi log `docker compose logs zalo-bridge`. Nếu RAM trống thấp (dưới khoảng 150 MB sau khi bật), tắt bộ nối ngay (bỏ profile) và báo.
4. Kiểm: trong menu có mục **Zalo tài khoản phụ**. Trang này lúc đầu báo "Cần quét mã QR để đăng nhập". Bộ nối **không công bố cổng ra ngoài**; không mở cổng 3100.
5. Gán mã nhóm: sau khi đăng nhập xong, vào **Zalo tài khoản phụ → Xem nhóm Zalo và gán mã**. Trang liệt kê các nhóm Zalo mà tài khoản phụ đang tham gia (tên, mã, số thành viên); chọn nhóm của Adh rồi bấm **Gán**. Tài khoản phụ phải đã ở trong nhóm. Nhóm không có mã thì không bao giờ được thêm tự động. Có thể nhập tay ở **Nhóm Zalo → Sửa** nếu trang không đọc được. Trang cảnh báo nhóm bật "chỉ quản trị viên được thêm" mà tài khoản phụ không phải quản trị viên (nhóm đó sẽ không thêm được).
6. Tạo nhóm thử: nên do tài khoản của **mẹ làm trưởng nhóm**, tài khoản phụ là thành viên thường, và **không bật** "chỉ quản trị viên được thêm thành viên" (đã thử ở đợt 0: thành viên thường vẫn thêm được khi tắt cài đặt này).

## 2. Thử theo từng nấc (đừng nhảy cóc)

Mỗi nấc chạy thử vài ngày với 2 đến 3 người **tự nguyện** (bạn bè, người nhà đã đồng ý) trước khi qua nấc sau.

### Nấc 0: đăng nhập và an toàn
1. Vào **Zalo tài khoản phụ → Quét mã QR**. Dùng **điện thoại của tài khoản phụ** quét. Phải thấy trạng thái "Đang nghe".
2. Thử quét bằng **một tài khoản thử khác** (không phải tài khoản chính của mẹ): trang phải báo "Sai tài khoản", không lưu gì, và nếu tài khoản phụ đang chạy thì nó **vẫn chạy bình thường**.
3. Bấm **Dừng ngay**: băng đỏ hiện trên Tổng quan. Bấm **Mở lại**: nghe lại, không cần quét QR.
4. Xem log: `docker compose logs zalo-bridge` **không được có** số điện thoại, tên hay mã Zalo của ai. Nếu thấy, dừng và báo.

### Nấc 1: "Chỉ nghe" (không thêm ai vào nhóm)
1. Chọn nấc **Chỉ nghe, người thêm tay**.
2. Nhắn mời một người thử như bình thường (nhắn tay, bấm **Đã nhắn**), người đó chấp nhận kết bạn.
3. Phải thấy: sự kiện được ghép vào đúng lời mời (nếu Zalo cho thấy số), hoặc vào mục **Kết bạn nhưng không biết là khách nào** (nếu không có số). Đợt 0 thấy khoảng 27/32 người không lộ số, nên **đa số sẽ rơi vào hàng đợi tay**; đó là bình thường.
4. Với dòng trong hàng đợi tay: chọn đúng khách, bấm **Ghép**.
5. Người **tự gửi** lời mời tới tài khoản phụ (khách chủ động) không được tự chấp nhận và không vào hàng đợi.

### Nấc 2: "Mẹ duyệt từng người"
1. Chọn nấc **Mẹ duyệt từng người**. Khách đã ghép hiện ở **Khách chờ vào nhóm** với nhóm dự kiến cùng khu vực.
2. Bấm **Duyệt thêm vào nhóm** cho một người. Người đó phải vào đúng nhóm; trang hiện kết quả ở nhật ký.
3. Thử từng tình huống lỗi và ghi lại **dạng lỗi Zalo trả**:
   - người đã ở trong nhóm,
   - nhóm đầy,
   - nhóm bật "chỉ quản trị viên được thêm" (dạng lỗi này **chưa ai biết**; theo thiết kế bộ nối sẽ **dừng** vì coi là lỗi lạ, đó là hành vi đúng),
   - người đã chặn tài khoản phụ.
   Sau khi biết chắc một mã lỗi là lỗi riêng một người, thêm mã đó vào `ZALO_PERSON_ERROR_CODES` (cách nhau bằng dấu phẩy) để lần sau không làm cầu dao nhảy. **Đừng thêm mã nào chưa chắc.**
4. Thử hạn mức: đặt tạm 2 người/ngày, duyệt 3 người: người thứ ba phải bị chặn. Trả lại 50/ngày sau khi thử.
5. Thử ngoài khung giờ (sau 22:00): không thêm ai.

### Những việc chỉ chạy lâu mới thấy
- **Đứt kết nối nghe (mã 1006)**: đợt 0 thấy khoảng 40 phút một lần. Bộ nối phải tự nối lại (nhật ký ghi "mất kết nối nghe" rồi "đang nghe"). Để chạy ít nhất vài giờ và xem.
- **Phiên hết hạn**: phải hiện băng "Phiên Zalo hết hạn, cần quét mã QR lại". Thời gian sống thật của phiên **chưa biết**.
- **Mở Zalo tài khoản phụ ở nơi khác** (Zalo Web hoặc máy tính): bộ nối bị đá và phải dừng (không tự giành lại). Đừng làm việc này khi đang có khách chờ.
- Lỗi 600 ("zpw_sek") đã gặp một lần ở đợt 0: nếu gặp, bộ nối dừng và cần quét lại.

### Nấc 3: "Tự thêm" (chỉ sau nhiều tuần ổn)
Chỉ bật khi nấc 2 đã chạy ổn nhiều tuần với khách thật và không có cảnh báo nào của Zalo. Chọn nấc **Tự thêm trong hạn mức** và tick ô xác nhận đã chạy thử. Vẫn giữ hạn mức mặc định (50 người/ngày, tối đa 5/giờ, cách nhau ít nhất 2 phút). **Chưa ai biết 50 người/ngày có an toàn với Zalo không**; nếu muốn chắc hơn, bắt đầu bằng 10 đến 20 người/ngày và tăng dần.

### Một nhóm cho cả hai đường (23/11/2026)
Khách vào nhóm bằng link hay do bộ nối tự thêm thì **luôn là cùng một nhóm**. Khi khách kết bạn, hệ thống giữ chỗ một nhóm (nhóm gợi ý ở tin gửi link); bộ nối tự thêm đúng nhóm đó, không tự chọn nhóm khác. Muốn vậy mỗi nhóm cần **cả link mời lẫn mã nhóm Zalo** và đang ở trạng thái "Đang nhận khách". Nhóm được giữ cho khách mà thiếu mã hoặc thiếu link thì khách đó **chờ** (báo "chưa có nhóm hợp"), không bị thêm vào nhóm khác.

## 3. Khi Zalo cảnh báo hoặc khóa tài khoản
Bộ nối tự dừng khi gặp yêu cầu xác minh, captcha hay báo hạn chế. **Không bấm Mở lại để thử tiếp.** Xem nhật ký, đọc thông báo trên điện thoại tài khoản phụ, để vài ngày rồi mới quyết. Nếu tài khoản bị khóa: dừng hẳn bộ nối (đăng xuất, bỏ profile `zalo-auto`), quay về nhóm bằng link/QR và thêm tay như trước. Dữ liệu khách không mất.

## 4. Những điều chưa ai kiểm (nói thẳng)

| Điều | Tình trạng |
|---|---|
| Bộ điều hợp `zca-js` trong dịch vụ Node chạy với Zalo thật | Chưa. Đợt 0 chạy thư viện riêng, không phải mã này |
| Ảnh Docker của bộ nối build được | Chưa build (không có daemon khi viết) |
| Lỗi Zalo trả khi nhóm bật "chỉ quản trị viên được thêm", khi nhóm đầy, khi bị chặn | Chưa biết dạng lỗi; bộ nối dừng khi gặp lỗi lạ |
| Thêm người **không** phải bạn vào nhóm | Chưa thử |
| 50 người/ngày có bị Zalo hạn chế không | Chưa biết; ngưỡng của Zalo không công bố |
| Thời gian sống của phiên Zalo | Chưa biết (đã thấy dùng lại được hơn 1 giờ) |
| `getUserInfo` trên khách thật có trả số không | Chưa; mẫu nhỏ cho thấy phần đông không lộ số |
| Chức năng liệt kê nhóm (`getAllGroups`, `getGroupInfo`) với Zalo thật | Chưa; nếu lỗi thì nhập mã tay ở trang Nhóm Zalo |
