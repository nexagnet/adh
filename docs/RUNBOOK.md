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
0. **Thử trước trên Zalo thật** (chưa đụng khách thật): xem [`HUONG-DAN-THU-ZALO.md`](HUONG-DAN-THU-ZALO.md), phần chế độ thử và [`HUONG-DAN-CHAY-THU-TOAN-DIEN.md`](HUONG-DAN-CHAY-THU-TOAN-DIEN.md) (màn Chạy thử trên Zalo đã xóa 24/11/2026).
0b. **Nhắn theo khu vực, từ từ**: tạo chiến dịch chọn khu vực (ví dụ một miền), rồi mỗi ngày mở **Lô nhắn hôm nay**, nhắn tay từng người theo hạn mức ngày/giờ và bấm *Đã nhắn*. Không nhắn ồ ạt; hệ thống không tự gửi. *(Bản hiện tại. D18, 23/11/2026: chủ shop đã bỏ nguyên tắc không tự gửi, hệ thống sẽ tự gửi Tin 1 khi tạo chiến dịch, từ từ; chưa có mã, mục này sẽ được sửa khi có.)*
1. **Chiến dịch mời → Tạo lô thử**: chọn khu vực và số khách, xem lại nội dung tin. Tin phải là chăm sóc sau mua, không quảng cáo sản phẩm (tin tư vấn của D13 là ngoại lệ đã được chủ cửa hàng chấp nhận).
2. Vào chiến dịch, bấm **Chạy thử** để xem hệ thống sẽ gửi gì (không gửi gì thật).
3. Bấm **Xuất danh sách để mẹ gửi tay**: tải file CSV (số điện thoại, tên, nội dung tin có link riêng). Mẹ tự nhắn bằng điện thoại hoặc Zalo của mình. Các lời mời trong file được đánh dấu "đã gửi"; hệ thống không biết tin có tới khách hay không.
4. Mỗi khách chỉ được mời **một lần**. Mỗi ngày chỉ xuất tối đa theo hạn mức ở **Gửi tin** (mặc định 20).
5. Cột "Đã bấm" chỉ là **lượt bấm link**, không có nghĩa khách đã vào nhóm.
6. Khách bấm "không muốn nhận tin nữa" trên trang mời sẽ vào danh sách từ chối vĩnh viễn.

**Đường mời hiện dùng: Zalo, mẹ gửi tay** (quyết định của chủ cửa hàng ngày 21/11/2026; **D18, 23/11/2026, đã chọn bỏ "gửi tay" và để hệ thống tự gửi như người thật, từ từ, nhưng chưa có mã nên hiện vẫn gửi tay**). Mẹ nhắn từng khách bằng Zalo của mình, lô nhỏ, đúng hạn mức ngày; không dùng phần mềm tự gửi và không đăng nhập Zalo bằng công cụ. **SMS và Zalo thông báo (ZBS): chưa dùng**, đang tắt, chưa có bộ gửi (xem phần B.7).

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
1. Mục **Bản đồ khách**: bản đồ thật của Việt Nam (nền OpenStreetMap, ranh giới 63 tỉnh), tỉnh nào càng đậm màu xanh càng nhiều khách. Kéo để di chuyển, cuộn chuột hoặc bấm +/− để phóng to. Bấm một tỉnh (hoặc tên tỉnh trong bảng bên dưới) để xem số khách, các nhóm Zalo phù hợp và dự báo thời tiết. Chỉ có số đếm, không có địa chỉ hay số điện thoại. Thư viện bản đồ (Leaflet) và ranh giới tỉnh nằm ngay trong phần mềm; chỉ các ô ảnh nền được trình duyệt tải từ `tile.openstreetmap.org` (máy chủ cửa hàng không gọi). Nền bản đồ OpenStreetMap công cộng dành cho lượng dùng nhỏ như trang quản trị này (xem chính sách tại https://operations.osmfoundation.org/policies/tiles/); nếu sau này nhiều người dùng, đặt `APP_MAP_TILE_URL` sang máy chủ ô bản đồ khác (và sửa `app.map.tile-attribution`).
2. Thời tiết **tắt mặc định**. Bật bằng biến môi trường `APP_WEATHER_ENABLED=true` (trên máy chủ: thêm vào file `.env` cạnh `docker-compose`) rồi chạy lại. Hệ thống hỏi dự báo của cả 63 tỉnh (tỉnh có khách hỏi trước; tắt phần hỏi tỉnh không có khách bằng `app.weather.all-provinces: false`), chỉ gửi tọa độ thành phố tỉnh lỵ (không gửi gì về khách) tới MET Norway (miễn phí, giấy phép CC BY 4.0, đã ghi nguồn trên trang; mỗi tỉnh hỏi lại sau khoảng 3 giờ, đúng giới hạn của họ). Nút **Cập nhật thời tiết ngay** hỏi lại tất cả tỉnh. Nếu muốn nêu tên cửa hàng cho MET Norway biết, đặt `APP_WEATHER_USER_AGENT` (ví dụ tên miền của cửa hàng).
   - **Lớp thời tiết chạy trên bản đồ:** phía trên bản đồ chọn **Nhiệt độ**, **Mưa** hoặc **Mây**, rồi bấm **Chạy** để xem 48 giờ tới chuyển theo từng giờ (kéo thanh trượt để dừng ở một giờ). Mỗi tỉnh hiện một ô màu theo số liệu của giờ đó; phóng to bản đồ để thấy con số. Đây là dự báo theo điểm (thành phố tỉnh lỵ), **không phải ảnh radar**. Ảnh radar RainViewer chỉ miễn phí cho cá nhân/học tập, chỉ có 2 giờ đã qua, phóng to tối đa mức 7 nên không dùng; Open-Meteo bản miễn phí chỉ cho phi thương mại nên cũng không dùng.
3. **Nhắc việc thời tiết** hiện trên Bản đồ và Tổng quan khi một tỉnh có từ 10 khách trở lên mà 48 giờ tới dự báo lạnh bất thường (thấp nhất ≤13°C miền Bắc, ≤16°C miền Trung, ≤22°C miền Nam) hoặc nóng (cao nhất ≥37°C). Chỉ là nhắc việc cho mẹ, **hệ thống không tự nhắn gì cho khách**. Ngưỡng chỉnh trong `application.yml` mục `app.weather`.

### 6. Việc cần người thật xử lý (khi bật bot)
Bot báo khi trong nhóm có khách muốn mua, khiếu nại hoặc cần người thật. Mẹ vào Zalo trả lời, rồi bấm **Đã nhận** và **Xong**. Bot không bao giờ tự chốt đơn.
Muốn tắt bot ngay: **Bot → TẮT bot ngay**. Mẹ vẫn trả lời khách bình thường.

### 7. Khi khách yêu cầu về dữ liệu
Vào trang khách đó, ghi nhận yêu cầu (xem, xuất, ngừng liên hệ, xóa) để bộ đếm hạn chạy. Theo dõi ở **Yêu cầu của khách**.
- **Ngừng liên hệ**: vĩnh viễn, kể cả khi nhập lại file.
- **Xóa dữ liệu**: gõ `XOA` để xác nhận. Hệ thống xóa khách, vận đơn, lời mời; chỉ giữ mã băm số điện thoại để không nhập lại hay liên hệ lại.

### 8. Khi nghi ngờ có chuyện không ổn
Bấm **Chiến dịch mời → Tạm dừng TẤT CẢ chiến dịch**. Hệ thống cũng tự tạm dừng khi gửi lỗi liên tiếp, nhiều khách từ chối, hoặc hết ngân sách; lý do hiện trên trang **Gửi tin**. Bỏ tạm dừng chỉ sau khi đã tìm hiểu nguyên nhân.

### 8b. Quét lại mã QR Zalo tài khoản phụ (khi bật bộ nối Zalo tự động)
Chỉ áp dụng khi đã bật bộ nối (xem `docs/HUONG-DAN-THU-ZALO-TU-DONG.md`). Khi Tổng quan hoặc Lô nhắn hôm nay hiện băng đỏ **"Phiên Zalo của tài khoản phụ hết hạn, cần quét mã QR lại"**:
1. Đăng nhập trang quản trị bằng tài khoản admin (chỉ admin mở được trang này), vào **Zalo tài khoản phụ → Quét mã QR**.
2. Dùng **điện thoại của tài khoản phụ** quét mã và bấm xác nhận. Không bao giờ quét bằng điện thoại của mẹ (tài khoản chính): hệ thống sẽ từ chối và đăng xuất.
3. Không chụp màn hình hay gửi mã QR cho ai: ai quét được là vào được tài khoản phụ. Hệ thống không hỏi mật khẩu hay mã OTP Zalo.
4. Thấy "Đăng nhập xong" là được. Nếu bộ nối đang **dừng** (băng "Bộ nối Zalo đã DỪNG"), đọc nhật ký rồi mới bấm **Mở lại**; quét QR lại chỉ tự mở lại khi dừng do mất phiên hoặc do đăng xuất.
Khi nghi ngờ: bấm **Dừng ngay** trên trang Zalo tài khoản phụ.

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

### 2b. Cài trên máy chủ dùng chung (đã có Caddy/dự án khác chiếm cổng 80/443)
Dùng khi máy chủ đã chạy dự án khác (ví dụ legal-office) với Caddy cài sẵn. Không dùng `docker-compose.prod.yml` vì Caddy riêng trong đó sẽ tranh cổng 80/443.
- Ảnh ứng dụng được GitHub Actions (`.github/workflows/image.yml`) build khi có commit vào `main` và đẩy lên `ghcr.io/nexagnet/adh-app:<mã commit>`. Máy chủ chỉ kéo về, không build Java. Lần đầu: vào trang gói trên GitHub, cho phép máy chủ kéo (đặt gói công khai, hoặc `docker login ghcr.io` bằng token chỉ đọc gói).
- Bản dùng chung (`docker-compose.shared.yml`) chỉ có PostgreSQL 18 (tối đa 256 MB) và ứng dụng (tối đa 512 MB, Java tối đa 320 MB). Ứng dụng chỉ nghe ở `127.0.0.1:8081`, không ra Internet.
- Cài và cập nhật:
```bash
git clone https://github.com/nexagnet/adh.git && cd adh/infra
cp .env.example .env && chmod 600 .env     # sửa DB_PASSWORD, APP_ADMIN_PASSWORD, APP_SHOP_NAME
./deploy.sh deploy                         # kéo ảnh của commit mới nhất trên main và chạy; tự lùi nếu không khỏe
./deploy.sh status | logs | backup | rollback
```
- **Tệp compose và profile lấy từ `infra/.env`** (D23, nhiều tài khoản Zalo): `deploy.sh` đọc `COMPOSE_FILE` (các tệp cách nhau dấu hai chấm; tệp đầu BẮT BUỘC là `docker-compose.shared.yml`, mỗi tệp phải có cạnh script) và `COMPOSE_PROFILES` (cách nhau dấu phẩy) ở `.env`; không đặt thì mặc định chỉ `docker-compose.shared.yml`, không profile. Biến cùng tên trong môi trường shell/cron bị bỏ qua có chủ ý. `deploy.sh deploy` kiểm cấu hình (`docker compose config`) TRƯỚC khi sao lưu hay đổi gì, kéo ảnh `zalo-bridge` / `zalo-bridge-2` khi profile của chúng bật, rồi chỉ chạy `up -d` cho dự án compose `adh` (không `down`, không `--remove-orphans`, không đụng container dự án khác). `auto-deploy.sh` gọi `deploy.sh` nên tự theo cùng cấu hình. **Cập nhật tay các script trong `infra/` (deploy.sh, backup.sh, restore-test.sh) lên máy chủ TRƯỚC khi thêm tệp thứ hai vào `COMPOSE_FILE`**: cron chỉ kéo ảnh Docker, không kéo script hay tệp compose.
- **Kiểm tệp compose trên máy chủ có lệch `main` không (chỉ đọc):** `cd infra && diff <(curl -fsS https://raw.githubusercontent.com/nexagnet/adh/main/infra/docker-compose.shared.yml) docker-compose.shared.yml` (làm tương tự với `docker-compose.zalo-2.yml`). Ứng dụng chỉ nhận những biến mà tệp compose truyền vào; thiếu dòng thì biến trong `.env` không tới container (ví dụ `BRIDGE_REPLY_GAP_SEC`, `APP_AI_ADVISOR_MODEL`).
- `deploy.sh deploy` từ chối chạy nếu RAM còn trống dưới 250 MB (đặt `MIN_FREE_MB` để đổi), tự sao lưu trước khi cập nhật, và chờ ứng dụng trả lời trước khi coi là xong.
- Sao lưu và thử khôi phục dùng chung script cũ: `COMPOSE_FILE=docker-compose.shared.yml ./backup.sh` (deploy.sh đã đặt sẵn). Cron: `17 3 * * * cd /đường/dẫn/adh/infra && COMPOSE_FILE=docker-compose.shared.yml DOCKER_SUDO="sudo " ./backup.sh >> /var/log/adh-backup.log 2>&1` (bỏ `DOCKER_SUDO` nếu người chạy thuộc nhóm docker).
- **Mở ra Internet** chỉ khi đã có tên miền trỏ về máy chủ: đặt `APP_PUBLIC_BASE_URL=https://<tên miền>` trong `.env`, chạy lại `./deploy.sh deploy`, rồi thêm VÀO CADDY CỦA MÁY một khối riêng (không sửa khối của dự án khác):
```
<tên miền> {
	encode gzip
	request_body { max_size 12MB }
	reverse_proxy 127.0.0.1:8081
}
```
  Chạy `caddy validate` rồi `systemctl reload caddy`. Chưa có tên miền thì KHÔNG mở ra ngoài và KHÔNG in giấy QR: hồ sơ `prod` đặt cookie đăng nhập chỉ qua HTTPS nên truy cập bằng IP/HTTP sẽ không giữ được phiên.

### 2c. Tự cập nhật bằng cron (máy chủ dùng chung)
`infra/auto-deploy.sh` thay cho việc gõ tay `./deploy.sh deploy`: mỗi lượt cron hỏi GitHub commit mới nhất của `main` và **chỉ triển khai khi** (1) workflow `ci` của đúng commit đó đã xanh, (2) ảnh `ghcr.io/nexagnet/adh-app:<commit>` đã có trên ghcr, (3) RAM trống ≥ 250 MB và đĩa trống ≥ 1500 MB, (4) bản đang chạy đang khỏe. Việc triển khai thật vẫn là `deploy.sh deploy <commit>`: sao lưu cơ sở dữ liệu **trước** khi ứng dụng mới chạy migration (sao lưu lỗi thì dừng, không đổi gì), chờ ứng dụng khỏe tối đa 3 phút, không khỏe thì lùi ảnh về thẻ trước. Không đụng container hay ảnh của dự án khác; chỉ dọn ảnh `adh-app` cũ (giữ thẻ đang chạy và thẻ trước).
- **Khóa:** `flock` trên `infra/.auto-deploy.lock`; lượt trước chưa xong thì lượt sau thoát im lặng. **Không chạy lần triển khai đầu tiên:** phải có `.current-tag` do `./deploy.sh deploy` tạo bằng tay.
- **Lỗi:** bản đã khởi động rồi mà không khỏe (đã lùi) bị **chặn, không thử lại** vì migration có thể đã chạy một phần; lỗi tạm (mạng, kéo ảnh) thử lại tối đa 3 lượt. Có commit mới thì tự thử bản mới; muốn thử lại cùng commit: `./auto-deploy.sh unblock`.
- **Lưu ý cơ sở dữ liệu:** lùi ảnh KHÔNG lùi migration Flyway. Nếu bản mới đã đổi cấu trúc rồi mới hỏng, bản cũ có thể không chạy được với cấu trúc mới; log ghi đường dẫn bản sao lưu trước khi cập nhật để khôi phục (mục 4 và `infra/restore-test.sh`). Việc khôi phục này làm bằng tay, script không tự khôi phục vì sẽ mất dữ liệu ghi sau lúc sao lưu.
- **Log:** `infra/auto-deploy.log` (tự cắt khi quá 1 MB); chỉ ghi khi trạng thái đổi, kèm RAM/swap/đĩa và bộ nhớ từng container `adh` trước và sau mỗi lần triển khai. Xem nhanh: `./auto-deploy.sh status`.
- **Tạm dừng / bật lại:** `./auto-deploy.sh pause` và `./auto-deploy.sh resume` (cron vẫn chạy nhưng không làm gì khi đang tạm dừng). Thử không đổi gì: `./auto-deploy.sh --dry-run`.
- **Cài (người có quyền trên máy chủ làm, sau khi đã deploy tay lần đầu thành công):**
```bash
cd ~/adh/infra && git pull
./auto-deploy.sh --dry-run          # xem nó sẽ quyết định gì, không đổi gì
crontab -e                          # thêm MỘT dòng (10 phút một lần, ưu tiên thấp cho máy nhỏ):
*/10 * * * * nice -n 10 ionice -c 3 /home/ubuntu/adh/infra/auto-deploy.sh >/dev/null 2>&1
```
  Muốn chỉ cập nhật ban đêm (ít khách dùng, ứng dụng ngừng khoảng 1 phút khi khởi động lại): đổi `*/10 * * * *` thành `*/10 1-4 * * *`. Người chạy cron phải chạy được `docker` (thuộc nhóm docker, hoặc `sudo` không hỏi mật khẩu cho docker) và ghi được vào `infra/` và `BACKUP_DIR`. Repo công khai nên không cần token; nếu repo riêng tư, đặt `ADH_AUTO_GITHUB_TOKEN` (quyền đọc Actions) trong `infra/.env` (chmod 600). Muốn được báo khi có lỗi: đặt `ADH_AUTO_NOTIFY_CMD` trong dòng cron (lệnh nhận nội dung ở tham số cuối), nếu không thì xem `status`/log.
- **Gỡ:** xóa dòng cron (`crontab -e`) hoặc `./auto-deploy.sh pause`.
- **Thử script (không cần Docker):** `infra/test-auto-deploy.sh` (dùng `git`, `curl`, `docker`, `deploy.sh` giả) và `infra/test-deploy.sh` (deploy.sh, backup.sh, restore-test.sh với `docker` giả: đọc `COMPOSE_FILE`/`COMPOSE_PROFILES` từ `.env`, không dừng hay xóa gì). CI chạy cả hai.

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

### 5c. Cảnh báo tài khoản Zalo phụ bị hạn chế
Khi bật bộ nối Zalo, hệ thống tự theo dõi (mỗi phút) và hiện cảnh báo bằng chữ dễ hiểu ở **Tổng quan**, trang **Zalo tài khoản phụ** (khối "Sức khỏe tài khoản phụ", có lịch sử) và nhãn đỏ ở menu. Khẩn: bị đăng xuất/hết phiên, Zalo hạn chế hoặc từ chối hàng loạt, lỗi lạ (hiện kèm mã để điền `ZALO_PERSON_ERROR_CODES`), đăng nhập nhầm tài khoản hoặc mở nơi khác, tỷ lệ tin lỗi 24 giờ từ 60%. Lưu ý: tỷ lệ lỗi từ 30%, cầu nối không gọi được quá 15 phút. Cảnh báo chỉ để người biết; việc dừng gửi vẫn do cầu dao. Người tự bấm Dừng hoặc Đăng xuất thì không báo.
- **Báo ra ngoài (tùy chọn):** đặt `APP_ALERT_WEBHOOK_URL=https://...` trong `infra/.env` (hệ thống POST một dòng văn bản thuần mỗi khi có cảnh báo mới hoặc nặng lên; ví dụ một chủ đề ntfy.sh bí mật, mở app ntfy trên điện thoại). Địa chỉ có thể chứa khóa nên chỉ đặt ở `.env`, không ghi vào repo. Tin báo không chứa số điện thoại, tên khách hay mã Zalo. Đổi `.env` rồi tạo lại container app.

### 6. Việc cần làm khi có sự cố

Khi kiểm tra Tin 1, lọc log app theo `Tin 1 event=` rồi đối chiếu `account`, `campaign` và `invitation` với nhật ký gửi trong ADH. `claim_skipped` nghĩa là lời mời chưa được nhận để gửi, có thể do đã bỏ duyệt hoặc lời mời đổi trạng thái. `claimed` đánh dấu bắt đầu xử lý. `delivery` ghi kết quả của bước gửi, và `send_exception` ghi loại lỗi. Nếu chỉ thấy `claimed`, kiểm tra sổ gửi và cầu dao trước khi thử lại. Các dòng kỹ thuật này không ghi số điện thoại, mã Zalo, nội dung tin hay khóa.
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

### 9. Triển khai ứng dụng và hai cầu nối từ GitHub Actions

Workflow **cd** chỉ xử lý commit hiện tại của `main` khi cả **ci** và **image** đều đạt trên cùng SHA. Môi trường GitHub `production` chỉ nhận nhánh `main`. Mỗi máy có một khóa SSH riêng với lệnh bắt buộc `deploy <sha>`, dùng host key đã xác minh. Khóa triển khai không mở shell, chuyển tiếp cổng hoặc đăng nhập máy khác.

- Máy chính gọi đường triển khai hiện có, giữ sao lưu và cơ chế quay lui của `auto-deploy.sh` và `deploy.sh`. Workflow xác minh ảnh app và cầu nối 1 đúng SHA, trang đăng nhập trả HTTP 200, PostgreSQL sẵn sàng và cầu nối đáp ứng kiểm tra sức khỏe. Cron vẫn là đường dự phòng và dùng chung khóa triển khai.
- Máy hai chỉ cập nhật dịch vụ `zalo-bridge` của project Compose `adh-zalo-bridge-2` trong `/home/deploy/adh-bridge2`. Giữ lại volume và phiên. Nếu ảnh mới không khỏe, script khởi chạy lại ảnh trước và workflow báo lỗi.
- Máy chủ giữ tệp cấu hình Compose, `.env` và công tắc E2E riêng. Deploy ảnh không đặt lại cờ gửi thật. Thay đổi cấu hình hoặc chạy lại cùng SHA được ghi riêng trong nhật ký vận hành.

#### Thiết lập hoặc phục hồi CD

1. Tạo environment `production` giới hạn ở nhánh `main`. Đặt `CD_MAIN_HOST` và `CD_BRIDGE2_HOST` dạng `user@host`. Đặt `CD_MAIN_SSH_KEY`, `CD_BRIDGE2_SSH_KEY` và `CD_KNOWN_HOSTS` trong environment secrets. Lấy host key từ bản đã xác minh qua đường quản trị.
2. Cài `infra/github-deploy.sh` vào thư mục ADH cạnh Compose trên mỗi máy. Đặt `restrict,command="/bin/bash <đường dẫn>/github-deploy.sh main"` hoặc `bridge2` trước khóa công khai tương ứng trong `authorized_keys`. Chạy thử lệnh sai và xác minh bị từ chối.
3. Dùng **Actions → cd → Run workflow** trên `main` để chạy lại một bản có CI và ảnh đã đạt. Đọc nhật ký job để xác định máy nào đã cập nhật nếu lượt triển khai thất bại giữa chừng.
