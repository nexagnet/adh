# Hướng dẫn vận hành (giai đoạn 1)

Tài liệu này có hai phần: **A. Dùng hằng ngày** (cho bố mẹ) và **B. Cài đặt và bảo trì máy chủ** (cho chủ cửa hàng).
Nghiệp vụ gốc nằm ở `01_DAC_TA_NGHIEP_VU.md`. Mọi con số trong ví dụ là dữ liệu giả.

> Quy tắc nền: hệ thống **mặc định không liên hệ ai**. Khách chỉ được mời khi mua đúng 1 lần, đã được duyệt, và chưa từ chối.
> Mẹ luôn trả lời khách trên Zalo thật. Web này chỉ là công cụ quản lý dữ liệu; hỏng cũng không ảnh hưởng việc bán hàng.

---

## A. Dùng hằng ngày

### 1. Đăng nhập
Mở địa chỉ web của cửa hàng, nhập tên đăng nhập và mật khẩu. Sai mật khẩu 5 lần liên tiếp thì bị khóa 15 phút, đợi rồi thử lại.

### 2. Nhập đơn mới
1. Vào hãng vận chuyển, xuất danh sách vận đơn ra Excel, lưu thành `.xlsx` (file `.xls` cũ phải lưu lại sang `.xlsx`).
2. Vào **Nhập đơn → Nhập file mới**, chọn đúng hãng và file.
3. Kiểm tra cột được chọn, bấm **Xem trước kết quả**, đọc số lỗi và số khách "cần kiểm tra", rồi **Xác nhận nhập**.
4. Nhập lại cùng một file không làm tăng số lần mua. Đừng nhập file cũ sau file mới (có thể ghi đè trạng thái mới bằng trạng thái cũ).
5. Trạng thái đơn lạ hiện ở **Trạng thái đơn**: chọn nghĩa cho từng trạng thái (ví dụ "Đã ký nhận" = Giao thành công).

### 3. Duyệt khách được mời
Vào **Danh sách mời**. Chỉ khách **mua đúng 1 lần**, không bị "cần kiểm tra", chưa từ chối mới hiện ở đây.
Tích ô xác nhận trách nhiệm rồi bấm duyệt. Duyệt **chưa gửi tin cho ai**. Khách mua từ 2 lần không bao giờ vào danh sách này; họ ở **Khách mua từ 2 lần** để bố mẹ chủ động tư vấn.

### 4. Nhóm Zalo
Mẹ tạo nhóm trên Zalo thật, dán link vào **Nhóm Zalo**, đặt trạng thái **Đang nhận khách**. Mỗi nhóm nhắm khoảng 5 khách.
Sau mỗi đợt mời, mẹ đếm số khách thật trong nhóm (không tính mẹ và bot) và bấm **Cập nhật số khách**. Nhóm gần đủ sẽ tự ngừng được cấp link mới.
Nên bắt đầu với 2 đến 3 nhóm.

### 5. Mời khách
1. **Chiến dịch mời → Tạo lô thử**: chọn số khách (tối đa 20 mỗi lô), xem lại nội dung tin. Tin phải là chăm sóc sau mua, không quảng cáo sản phẩm.
2. Vào chiến dịch, bấm **Chạy thử** để xem hệ thống sẽ gửi gì (không gửi gì thật).
3. Bấm **Xuất danh sách để mẹ gửi tay**: tải file CSV (số điện thoại, tên, nội dung tin có link riêng). Mẹ tự nhắn bằng điện thoại hoặc Zalo của mình. Các lời mời trong file được đánh dấu "đã gửi"; hệ thống không biết tin có tới khách hay không.
4. Mỗi khách chỉ được mời **một lần**. Mỗi ngày chỉ xuất tối đa theo hạn mức ở **Gửi tin** (mặc định 20).
5. Cột "Đã bấm" chỉ là **lượt bấm link**, không có nghĩa khách đã vào nhóm.
6. Khách bấm "không muốn nhận tin nữa" trên trang mời sẽ vào danh sách từ chối vĩnh viễn.

**Đường mời hiện dùng: Zalo, mẹ gửi tay** (quyết định của chủ cửa hàng ngày 21/11/2026). Mẹ nhắn từng khách bằng Zalo của mình, lô nhỏ, đúng hạn mức ngày; không dùng phần mềm tự gửi và không đăng nhập Zalo bằng công cụ. **SMS và Zalo thông báo (ZBS): chưa dùng**, đang tắt, chưa có bộ gửi (xem phần B.7).

### 5b. Giấy giới thiệu bỏ vào gói hàng (mã QR chung)
Trang **In giấy giới thiệu**: một mã QR tĩnh và một link ngắn giống hệt nhau trên mọi tờ, mẹ in như tờ rơi thường (A6, A5, hoặc 4 tờ A6 trên một tờ A4 rồi cắt).
1. Sửa chữ trên giấy (tiêu đề, nội dung, lời nhắn, hướng dẫn) rồi bấm **Lưu chữ**. Chữ này cũng hiện ở trang khách thấy sau khi quét.
2. Chọn khổ giấy, bấm **In trang này**; trong hộp thoại in chọn đúng khổ, tỷ lệ 100%, tắt "Đầu trang và chân trang". In thử một tờ và quét thử bằng điện thoại trước khi in nhiều.
3. **Chỉ in khi địa chỉ web đã là địa chỉ thật** (nếu chạy ngoài Docker, đặt `LC_ALL=C.UTF-8` để tên cửa hàng có dấu hiện đúng) (trang sẽ cảnh báo "Chưa nên in" nếu chưa đặt `APP_PUBLIC_BASE_URL`). Mã QR chỉ phải in lại nếu đổi tên miền.
4. Khi khách quét, hệ thống tự chọn nhóm còn chỗ (mục tiêu mặc định 5 khách). Mỗi điện thoại (nhớ bằng cookie 90 ngày) chỉ được tính một lần; mỗi địa chỉ mạng tối đa 20 lượt mới mỗi giờ (`app.entry.max-new-per-ip-per-hour`).
5. **Mỗi tuần mẹ đếm lại số khách thật trong từng nhóm** và nhập ở trang **Nhóm Zalo**. Nhóm đủ thì hệ thống tự ngừng đưa khách vào; thêm nhóm mới bằng link mời Zalo rồi đổi sang "Đang nhận khách". Không cần in lại giấy.
6. Hệ thống **không biết ai đã thật sự vào nhóm**, chỉ đếm lượt quét (mỗi lượt tính nửa người cho tới lần đếm lại tiếp theo, đây là giả định).

**Ngoại lệ so với BR-06 (quyết định ngày 21/11/2026):** giấy bỏ vào MỌI gói hàng, kể cả khách mua từ 2 lần, và ai quét cũng vào được nhóm. Khách tự quét, hệ thống không chủ động mời ai. Khách có thể xin ngừng nhận tin trên trang sau khi quét (nhập số điện thoại, vào danh sách từ chối vĩnh viễn, chỉ lưu mã băm).

### 5c. Khu vực của khách và của nhóm Zalo
1. **Danh sách khách** hiện thêm cột "Khu vực" (tỉnh/thành và miền), có thẻ đếm theo miền và bộ lọc khu vực (miền, vùng hoặc từng tỉnh). Khu vực suy ra từ địa chỉ trên vận đơn: địa chỉ không ghi tên tỉnh thì để **Chưa rõ**, hệ thống không đoán bừa. Tải Excel/CSV cũng có thêm hai cột tỉnh và miền.
2. Sau khi cập nhật phần mềm, khu vực của khách cũ tự được tính lại khi khởi động. Nếu thấy sai, bấm **Tính lại phân loại và khu vực của tất cả khách** ở cuối danh sách khách.
3. Ở **Nhóm Zalo**, mỗi nhóm chọn được khu vực: cả miền, một vùng hoặc một tỉnh; để "Chung" thì nhóm nhận khách mọi nơi. Khách được xếp ưu tiên: cùng tỉnh, rồi cùng vùng, rồi cùng miền, rồi nhóm chung. Khách miền khác không bị xếp vào nhóm của miền này (đặt `APP_GROUPING_ALLOW_OTHER_REGION=true` nếu muốn cho phép).
4. Trang quét mã QR chung hỏi "Bạn ở tỉnh/thành nào?" (có thể bỏ qua). Khách đã chọn tỉnh mà chưa có nhóm hợp sẽ thấy "chưa có chỗ" và được ghi lại (chỉ mã tỉnh, không có số điện thoại/tên). Trang Nhóm Zalo hiện khu vực nào đang thiếu nhóm; mở nhóm mới thì khách quét lại mã sẽ được xếp vào.

### 5d. Bản đồ khách và thời tiết
1. Mục **Bản đồ khách**: mỗi chấm là một tỉnh/thành, càng to và đậm càng nhiều khách. Bấm chấm (hoặc tên tỉnh trong bảng) để xem số khách, các nhóm Zalo phù hợp và dự báo thời tiết. Chỉ có số đếm, không có địa chỉ hay số điện thoại. Đây là sơ đồ minh họa, không phải bản đồ hành chính.
2. Thời tiết **tắt mặc định**. Bật bằng biến môi trường `APP_WEATHER_ENABLED=true` rồi khởi động lại. Hệ thống chỉ hỏi những tỉnh đang có khách, chỉ gửi tọa độ thành phố tỉnh lỵ (không gửi gì về khách) tới MET Norway (miễn phí, giấy phép CC BY 4.0, đã ghi nguồn trên trang). Nút **Cập nhật thời tiết ngay** hỏi lại tất cả tỉnh. Nếu muốn nêu tên cửa hàng cho MET Norway biết, đặt `APP_WEATHER_USER_AGENT` (ví dụ tên miền của cửa hàng).
3. **Nhắc việc thời tiết** hiện trên Bản đồ và Tổng quan khi một tỉnh có từ 10 khách trở lên mà 48 giờ tới dự báo lạnh bất thường (thấp nhất ≤13°C miền Bắc, ≤16°C miền Trung, ≤22°C miền Nam) hoặc nóng (cao nhất ≥37°C). Chỉ là nhắc việc cho mẹ, **hệ thống không tự nhắn gì cho khách**. Ngưỡng chỉnh trong `application.yml` mục `app.weather`.

### 6. Việc cần mẹ xử lý (khi bật bot)
Bot báo khi trong nhóm có khách muốn mua, khiếu nại hoặc cần người thật. Mẹ vào Zalo trả lời, rồi bấm **Đã nhận** và **Xong**. Bot không bao giờ tự chốt đơn.
Muốn tắt bot ngay: **Bot → TẮT bot ngay**. Mẹ vẫn trả lời khách bình thường.

### 7. Khi khách yêu cầu về dữ liệu
Vào trang khách đó, ghi nhận yêu cầu (xem, xuất, ngừng liên hệ, xóa) để bộ đếm hạn chạy. Theo dõi ở **Yêu cầu của khách**.
- **Ngừng liên hệ**: vĩnh viễn, kể cả khi nhập lại file.
- **Xóa dữ liệu**: gõ `XOA` để xác nhận. Hệ thống xóa khách, vận đơn, lời mời; chỉ giữ mã băm số điện thoại để không nhập lại hay liên hệ lại.

### 8. Khi nghi ngờ có chuyện không ổn
Bấm **Chiến dịch mời → Tạm dừng TẤT CẢ chiến dịch**. Hệ thống cũng tự tạm dừng khi gửi lỗi liên tiếp, nhiều khách từ chối, hoặc hết ngân sách; lý do hiện trên trang **Gửi tin**. Bỏ tạm dừng chỉ sau khi đã tìm hiểu nguyên nhân.

---

## B. Cài đặt và bảo trì máy chủ (chủ cửa hàng)

### 1. Yêu cầu
VPS 2 CPU, 2 GB RAM, 40 GB đĩa (D7); Docker và Docker Compose; một tên miền trỏ về IP máy chủ; cổng 80 và 443 mở.
Bật swap 2 GB nếu chưa có (máy 2 GB RAM chạy Java và PostgreSQL khá sát). Giới hạn bộ nhớ đã đặt sẵn: PostgreSQL 512 MB, ứng dụng 768 MB (Java tối đa khoảng 512 MB), Caddy 128 MB.

### 2. Cài lần đầu
```bash
git clone <địa chỉ repo> && cd adh/infra
cp .env.example .env        # sửa mật khẩu, APP_DOMAIN, APP_SHOP_NAME; chmod 600 .env
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```
Caddy tự xin chứng chỉ HTTPS cho `APP_DOMAIN`. Ứng dụng **từ chối khởi động** nếu `APP_ADMIN_PASSWORD` trống. Chỉ cổng 80/443 ra Internet; PostgreSQL và ứng dụng không công bố cổng.

### 3. Cập nhật phiên bản
```bash
cd adh && git pull && cd infra
./backup.sh                  # sao lưu trước khi cập nhật
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```
Cơ sở dữ liệu được nâng cấp tự động (Flyway) khi ứng dụng khởi động.

### 4. Sao lưu hằng ngày
`infra/backup.sh` chạy `pg_dump`, nén, kiểm tra file, giữ `KEEP_DAYS` ngày (mặc định 14). Thêm vào cron của máy chủ:
```
17 3 * * * /đường/dẫn/adh/infra/backup.sh >> /var/log/adh-backup.log 2>&1
```
- **Mã hóa (khuyến nghị):** cài `age`, tạo khóa (`age-keygen`), đặt khóa **công khai** vào `BACKUP_AGE_RECIPIENT`, cất khóa **riêng** ở nơi khác máy chủ. Mất khóa riêng là mất bản sao lưu.
- **Chép ra ngoài máy chủ (khuyến nghị):** đặt `BACKUP_REMOTE_CMD`, ví dụ `rclone copy --no-traverse` hoặc một lệnh `scp`. Bản sao lưu chỉ nằm trên cùng máy chủ thì mất máy là mất hết.
- File sao lưu chứa số điện thoại và địa chỉ khách thật: không đưa lên git, không chia sẻ.

### 5. Thử khôi phục (làm định kỳ, ít nhất mỗi tháng)
```bash
./restore-test.sh            # nếu mã hóa: AGE_IDENTITY=/đường/dẫn/khóa-riêng ./restore-test.sh
```
Script nạp bản sao lưu mới nhất vào một cơ sở dữ liệu tạm `crm_restore_test`, kiểm tra các bảng cốt lõi, rồi xóa nó. Không đụng dữ liệu đang chạy. Kết thúc bằng dòng `THỬ KHÔI PHỤC THÀNH CÔNG`. Chưa từng khôi phục thử thì bản sao lưu chưa đáng tin.

**Khôi phục thật** (khi mất dữ liệu): dừng ứng dụng (`docker compose ... stop app`), tạo lại cơ sở dữ liệu rỗng, nạp file `.sql.gz` bằng `gzip -dc file | docker compose ... exec -T postgres psql -U crm -d crm`, rồi bật lại ứng dụng.

### 5b. Bảo mật đã có và chưa có
Đã có: bắt buộc đăng nhập, mật khẩu không có mặc định, khóa đăng nhập sai 5 lần/15 phút theo cặp (IP, tên), cookie phiên chỉ gửi qua HTTPS khi chạy hồ sơ `prod`, phiên hết hạn sau 8 giờ, giới hạn kích thước file tải lên, trang mời công khai chỉ dùng token ngẫu nhiên và không lộ tên hay số điện thoại, log không ghi số điện thoại đầy đủ (chỉ 3 số cuối), Caddy không bật nhật ký truy cập (đường dẫn mời có token).
**Chưa có:** mã hóa đĩa của máy chủ, mã hóa dữ liệu trong PostgreSQL, tài khoản riêng cho bố và mẹ (đang chờ bạn xác nhận), cảnh báo tự động khi máy chủ hỏng hay hết đĩa. Nên bật mã hóa đĩa của nhà cung cấp VPS nếu có, và kiểm tra dung lượng đĩa định kỳ.

### 6. Việc cần làm khi có sự cố
| Tình huống | Làm gì |
|---|---|
| Không vào được web | `docker compose -f docker-compose.prod.yml ps` và `logs --tail 100 app`. Kiểm tra tên miền, chứng chỉ (Caddy), đĩa còn chỗ |
| Quên mật khẩu quản trị | Đổi `APP_ADMIN_PASSWORD` trong `.env` rồi `up -d` lại |
| Bị khóa đăng nhập | Đợi 15 phút, hoặc khởi động lại ứng dụng (bộ đếm nằm trong bộ nhớ) |
| Hệ thống tự tạm dừng gửi | Đọc lý do ở **Gửi tin**, xử lý nguyên nhân, rồi bỏ tạm dừng |
| Bot trả lời sai | **Bot → TẮT bot ngay**, sửa FAQ, thử lại bằng ô "Thử bot" |
| Nghi lộ dữ liệu | Tạm dừng tất cả chiến dịch, đổi mật khẩu, kiểm tra nhật ký, hỏi luật sư về nghĩa vụ thông báo |

### 7. Điều kiện trước khi bật gửi thật
Mời qua Zalo do mẹ gửi tay không cần bật gì ở đây. Biến `APP_SENDING_ENABLED` chỉ dành cho gửi tự động sau này, mặc định `false`, và hiện **SMS/ZBS chưa dùng, chưa có bộ gửi nào được cài**. Nếu sau này muốn dùng SMS/ZBS, chỉ cân nhắc bật khi đã đủ: hộ kinh doanh, nhà cung cấp SMS/ZBS đã chọn và mẫu tin được duyệt, xác nhận pháp lý về liên hệ khách cũ (D3 là bạn tự chịu rủi ro, nhưng vẫn không bỏ cổng chặn), và đã chạy thử một lô nhỏ 10 đến 20 khách theo dõi lỗi, từ chối, khiếu nại.
Nếu máy chủ ở Singapore (D7): dữ liệu khách nằm ngoài Việt Nam, file 03 mục 1 điểm 4 khuyên hỏi luật sư trước khi chạy thật.

### 8. Bot
Chưa có kênh Zalo nào được cài. Xem `docs/ADR-0001-chon-kenh-bot.md` để biết cần chạy thử (spike) những gì trước khi chọn. Cho tới lúc đó bot chỉ để soạn FAQ và thử bằng ô "Thử bot".
