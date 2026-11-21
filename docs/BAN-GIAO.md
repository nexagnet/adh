# Bàn giao dự án Adh (ngày 21/11/2026)

Tài liệu này gom mọi thứ phiên làm việc trước biết, để phiên sau (người hoặc Claude) làm tiếp mà không phải hỏi lại. Đọc kèm:
`01_DAC_TA_NGHIEP_VU.md` (nghiệp vụ, nguồn sự thật), `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md` (quyết định D1–D12, kế hoạch),
`docs/RUNBOOK.md` (vận hành), `docs/thiet-ke-penpot/` (thiết kế giao diện đã duyệt).

**Người dùng (chủ dự án): Phùng Việt. Luôn trả lời bằng tiếng Việt, ngắn gọn, dễ hiểu cho người không rành kỹ thuật.**
Hệ thống phục vụ cửa hàng của bố mẹ người dùng: chăm sóc khách cũ qua nhóm Zalo.

---

## 1. Trạng thái tổng quát

Mã nguồn trên nhánh `main` của `nexagnet/adh`, đã gộp các PR #3, #4, #5. **225 test đạt**, CI (`test`, `lint`) xanh.

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| 1A | Nhập file Viettel Post/J&T, chuẩn hóa SĐT, phân loại `ONE_TIME`/`REPEAT` | Xong, có test. Viettel Post kiểm chứng với file thật; **J&T đã xác nhận cấu trúc file xuất trực tiếp trên cổng khách hàng ngày 21/11/2026** nhưng **chưa chạy thử importer với file J&T thật** (xem mục 8) |
| 1B | Sổ cơ sở liên hệ, danh sách từ chối (chỉ lưu mã băm), danh sách mời, quyền dữ liệu của khách | Xong, có test |
| 1C | Nhóm Zalo, chiến dịch mời theo lô nhỏ, link riêng `/i/{token}`, đếm lượt bấm | Xong, có test |
| 1D | Cổng chặn gửi, chạy thử, xuất danh sách cho mẹ gửi tay, nhật ký gửi | Xong. **Chỉ có đường "mẹ gửi tay"**. Không có bộ gửi SMS/ZBS |
| 1E | Lõi bot trợ lý (hỏi đáp thường gặp, báo mẹ, chạy thử, tắt khẩn cấp) | Xong phần lõi. **Chưa có kênh Zalo thật** (xem `docs/ADR-0001-chon-kenh-bot.md`) |
| 1F | Docker, HTTPS (Caddy), sao lưu/khôi phục, giới hạn đăng nhập sai | Xong phần mã và script; **Docker chưa chạy thử** |
| Việt hóa | Giao diện 100% tiếng Việt (bean `labels`, trang lỗi tiếng Việt) | Xong (PR #3) |
| Giấy in | Trang `/print` + trang khách `/moi` dùng một mã QR tĩnh chung | Xong (PR #3) |
| 1G | Phân vùng khách Bắc/Trung/Nam theo địa chỉ, nhóm Zalo theo khu vực | Xong (PR #4) |
| 1H | Bản đồ khách theo tỉnh `/map` + nhắc việc thời tiết | Xong (PR #5) |
| Giao diện Penpot | Làm lại giao diện theo thiết kế đã duyệt | **Đã code** (PR #8 thanh bên + việc hôm nay; PR kế tiếp: thuật ngữ tiếng Việt, chi tiết khách 2 cột, chiến dịch, giấy in A5, `/n`). Chưa xem bằng mắt trên trình duyệt thật (xem mục 9) |
| 2F | Đồng bộ đơn mới từ J&T | Phương án A đã code (PR #9): nhập nhiều file, gộp theo mã vận đơn, Địa chỉ hành chính, trạng thái J&T, nhắc nhập file. **Chưa chạy với file J&T thật.** Cổng nạp chung `ShipmentIngest` chưa làm (chưa có nguồn thứ hai) |

### PR và nhánh
- Đã gộp: **#3** (giai đoạn 1C–1F, Việt hóa, giấy in QR chung), **#4** (phân vùng), **#5** (bản đồ + thời tiết).
- Nhánh làm việc của phiên này: `claude/project-thread-b00yjf`. Sau mỗi lần gộp phải khởi lại từ `main` (`git checkout -B <nhánh> origin/main`), rồi đẩy lên (nhánh cũ đã bị xóa phía xa nên đẩy thường là được).
- **Không đụng vào**: PR #1 của `nexagnet/adh` và các bản nháp ở repo `nexagnet/.github` (#11, #42, #43, #45). Đó là của người dùng, không phải của phiên này.
- Người dùng đã cho phép Claude **tự gộp PR của `nexagnet/adh`** khi CI xanh và không xung đột (không áp dụng cho `nexagnet/.github` hay PR của người khác). Việc mới thì mở PR mới từ `main`, gộp rồi báo lại.

---

## 2. Quyết định của chủ shop (tóm tắt, chi tiết ở file 03 mục 0)

| # | Quyết định |
|---|---|
| D1 | Cá nhân kinh doanh, chưa có hộ kinh doanh, sẽ đăng ký |
| D2 | Giữ nhóm Zalo cá nhân, mẹ làm trưởng nhóm |
| D3 | Chủ shop tự chịu rủi ro pháp lý khi mời khách cũ; vẫn giữ cổng chặn, sổ từ chối, nhật ký |
| D4 | SMS brandname là kênh chính (**tạm không áp dụng, xem D9**) |
| D5 | Gửi chậm, theo lô nhỏ (tối đa 20/lô, 07:00–22:00, nghỉ ngẫu nhiên). **Không làm thủ thuật né phát hiện** |
| D6 | Ngân sách SMS 500 nghìn – 1,5 triệu/tháng |
| D7 | VPS Singapore 2 CPU / 2 GB RAM / 40 GB; không ghi SĐT đầy đủ vào log |
| D8 | Chưa có file mẫu hai hãng; chủ shop tự xuất và che SĐT |
| D9 | **Tạm chưa dùng SMS, làm qua Zalo trước**: xuất danh sách, mẹ gửi tay |
| D10 | Giấy in bỏ vào **mọi gói hàng**, kể cả khách mua từ 2 lần, dùng **một mã QR tĩnh chung** (ngoại lệ so với BR-06) |
| D11 | Phân vùng khách theo miền/tỉnh, xếp nhóm Zalo theo khu vực (lý do: mùa lạnh chủ yếu khách miền Nam mua) |
| D12 | Bản đồ phân bố khách + nhắc việc thời tiết (chỉ nhắc trên web, không tự nhắn khách) |

Lựa chọn khác của người dùng trong các phiên: giao diện **100% tiếng Việt**; "trợ lý AI" thay cho chữ "bot" (theo thiết kế Penpot); bố mẹ không rành kỹ thuật nên chữ to, mỗi màn một việc; người dùng đã **duyệt thiết kế Penpot** (21/11/2026) và dặn "sửa xong Penpot thì code luôn"; chọn **phương án A cho J&T** (nhập file tăng dần).

### Giả định do phiên trước tự đặt (chủ shop chưa xác nhận, chỉnh được)
- Mẹ **đếm lại thành viên nhóm Zalo mỗi tuần** (`GroupService.RECOUNT_DAYS = 7`). Lượt bấm chờ tính tỷ lệ 0,5 (`app.invitations.assumed-accept-rate`) chỉ để chia nhóm, **không phải số người thật vào nhóm**.
- Nhóm Zalo mục tiêu khoảng **5 khách/nhóm**, số khách **không tính tài khoản mẹ và trợ lý** (2 tài khoản cửa hàng).
- **Phân vùng:** tỉnh là tên xuất hiện **cuối cùng** trong địa chỉ vận đơn, bỏ qua tên đứng sau "xã/phường/quận/huyện/đường/phố"; không chắc thì "Chưa rõ", **không đoán bừa**. Tây Nguyên xếp miền Trung. Sau sáp nhập tỉnh (1/7/2025) tên tỉnh mới đều trùng một tên tỉnh cũ nên vẫn nhận được, nhưng khách cũ ở tỉnh bị sáp nhập có thể hiện tên tỉnh mới (vẫn cùng miền hoặc vùng kế bên). Danh sách 63 tỉnh nằm ở `geo/VnGeo.java`; sửa cách đoán thì tăng `VnGeo.VERSION` để khởi động lại tự tính lại cho khách cũ.
- **Xếp nhóm theo khu vực:** cùng tỉnh > cùng vùng > cùng miền > nhóm chung; nhóm của miền khác bị loại (cấu hình `app.grouping.allow-other-region`, mặc định không). Nhóm mới mặc định là "Chung" cho tới khi mẹ gắn khu vực. Khách chưa rõ khu vực: nhóm chung trước, rồi mọi nhóm.
- **Mã QR chung `/moi`** hỏi "Bạn ở tỉnh/thành nào?" (bỏ qua được). Khách chọn tỉnh mà chưa có nhóm hợp được ghi một dòng "chờ nhóm" (chỉ mã tỉnh + mã ngẫu nhiên của trình duyệt, **không SĐT/tên/địa chỉ mạng**) để mẹ biết cần mở nhóm ở đâu. Chống quét lặp bằng cookie `adh_moi` (90 ngày) và giới hạn 20 lượt mới/địa chỉ mạng/giờ.
- **Thời tiết:** ngưỡng nhắc lạnh bất thường là nhiệt độ thấp nhất 48 giờ tới ≤13°C (Bắc), ≤16°C (Trung), ≤22°C (Nam); nóng khi cao nhất ≥37°C; chỉ nhắc ở tỉnh có từ 10 khách. Tất cả là giả định, chỉnh trong `application.yml` mục `app.weather`.
- **Bản đồ** là sơ đồ minh họa tự vẽ (chấm theo tọa độ tỉnh lỵ trong đường viền đất liền gần đúng ở `map/MapService.java`), **không phải bản đồ ranh giới tỉnh**. Muốn bản đồ thật cần file GeoJSON có giấy phép rõ ràng rồi thay phần vẽ.
- Hạn phản hồi yêu cầu dữ liệu của khách hiện là 15 ngày cho mọi loại (`app.privacy.request-due-days`) và **chưa có xác nhận pháp lý**. Thiết kế Penpot muốn hạn theo loại (ngừng liên hệ/xuất dữ liệu 2 ngày làm việc, xóa 20 ngày theo file 03 mục 1; nguồn phụ, cần đối chiếu Công báo).

---

## 3. Cấu hình và công tắc

Biến môi trường (xem `app/src/main/resources/application.yml`; mặc định an toàn là **tắt mọi thứ gửi ra ngoài**):

| Biến / khóa | Ý nghĩa | Mặc định |
|---|---|---|
| `APP_ADMIN_PASSWORD` | Mật khẩu đăng nhập; **ứng dụng không khởi động nếu để trống** | không có |
| `APP_ADMIN_USERNAME` | Tên đăng nhập | `admin` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Kết nối PostgreSQL | local |
| `APP_PUBLIC_BASE_URL` | Địa chỉ web thật (ghép link mời và mã QR). **Chưa đặt thì trang in cảnh báo "Chưa nên in"** | `http://localhost:8080` |
| `APP_SHOP_NAME`, `APP_SHOP_PHONE` | Tên và số điện thoại cửa hàng hiện trên trang khách và giấy in. Chạy ngoài Docker nên đặt `LC_ALL=C.UTF-8` để tên có dấu hiện đúng | `Cửa hàng`, trống |
| `APP_SENDING_ENABLED` | Gửi thật qua dịch vụ ngoài. Hiện **chưa có bộ gửi thật** nên bật cũng chưa gửi gì | `false` |
| `app.sending.*` | Giờ gửi 07:00–22:00, hạn mức 20/ngày, nghỉ 30–120 giây, ngân sách tháng, tự dừng khi lỗi/từ chối cao; `scheduler-enabled: false` | xem yml |
| `APP_WEATHER_ENABLED` | Bật thời tiết (MET Norway). Chỉ gửi tọa độ tỉnh lỵ của tỉnh có khách | `false` |
| `APP_WEATHER_USER_AGENT` | Tên ứng dụng gửi cho MET Norway (họ yêu cầu có định danh; có thể thêm tên miền cửa hàng) | `adh-shop-crm/1.0` |
| `app.grouping.allow-other-region` | Cho xếp khách vào nhóm miền khác khi hết nhóm hợp | `false` |
| `app.entry.max-new-per-ip-per-hour` | Giới hạn lượt mới của mã QR chung | 20 |
| `app.invitations.max-batch` | Tối đa khách mỗi lô mời | 20 |
| `app.security.max-login-failures`, `lock-minutes` | Sai 5 lần khóa 15 phút theo cặp (IP, tên đăng nhập) | 5, 15 |
| `app.bot.*` | Bot im lặng 30 phút sau khi mẹ trả lời; tối đa 3 trả lời/5 phút | xem yml |

Công tắc trên giao diện: **Tạm dừng tất cả chiến dịch** (`/campaigns`), công tắc bot toàn hệ thống và chế độ chạy thử (`/bot`), trạng thái nhóm Zalo.

---

## 4. Chạy, kiểm thử, triển khai

- **Chạy thử trên máy** (không cần Docker/PostgreSQL): `cd app && ./mvnw spring-boot:test-run -Dspring-boot.run.mainClass=vn.shop.crm.DevApplication`, mở <http://localhost:8080>, đăng nhập `admin` / `dev-only-password`. Dữ liệu mẫu giả ở `fixtures/`.
- **Kiểm thử:** `cd app && ./mvnw verify` (PostgreSQL nhúng thật, JaCoCo ngưỡng 80% dòng). CI chạy đúng lệnh này, cộng một job `lint` chặn file Excel, `data_product/`, `.env`, build output trong git.
- **Môi trường cloud/sandbox:** cần **JDK 25** (đã có ở `/opt/jdk25`; Adoptium bị chặn proxy, tải từ download.oracle.com nếu thiếu). PostgreSQL nhúng **không chạy bằng root**: chạy Maven bằng `runuser -u ubuntu -- env JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH ./mvnw ...` và `chown -R ubuntu` thư mục repo; thêm `git config --global --add safe.directory <repo>` nếu git báo quyền sở hữu. Không có daemon Docker trong sandbox.
- **Triển khai máy chủ:** `infra/docker-compose.prod.yml` (ứng dụng + PostgreSQL + Caddy HTTPS, giới hạn bộ nhớ cho VPS 2 GB), `infra/backup.sh`, `infra/restore-test.sh`. Hướng dẫn từng bước: `docs/RUNBOOK.md` mục B.
- **Cơ sở dữ liệu:** Flyway, migration `V1`…`V10` ở `app/src/main/resources/db/migration` (V9 phân vùng, V10 thời tiết). Việc mới dùng `V11`. Khi thêm bảng mới, **nhớ thêm vào danh sách `TRUNCATE` trong `AbstractPostgresTest`** (test dùng chung một cơ sở dữ liệu).
- **Công nghệ:** Java 25, Spring Boot 4.2, Spring Security, JdbcClient (SQL thuần), Thymeleaf, Flyway, PostgreSQL; zxing sinh mã QR trên máy chủ; Jackson 3 (`tools.jackson`). Chính sách bảo mật nội dung (CSP): **không script hay style nội tuyến** (`default-src 'self'; style-src 'self'`), nên tương tác phải làm bằng liên kết/biểu mẫu hoặc file `.js` riêng.

### Bản đồ mã nguồn (`app/src/main/java/vn/shop/crm`)
`imports` (nhập file), `shipping` (hãng, trạng thái chuẩn), `customers` (phân loại, danh sách), `contact` (cơ sở liên hệ, từ chối, yêu cầu dữ liệu), `invite` (nhóm Zalo, chiến dịch, lời mời, trang khách `/i`, mã QR chung `/moi`, in giấy `/print`), `send` (cổng chặn, chạy thử, xuất cho mẹ gửi tay), `bot` (lõi bot trợ lý), `geo` (63 tỉnh, vùng, đoán tỉnh), `map` (bản đồ, thời tiết), `security`, `common` (nhãn tiếng Việt `Labels`, SĐT, QR), `web` (tổng quan, trạng thái đơn, lỗi).

Đường dẫn chính (cần đăng nhập, trừ `/login`, `/moi`, `/i/**`): `/` tổng quan, `/imports`, `/customers`, `/contacts`, `/campaigns`, `/groups`, `/print`, `/sending`, `/bot`, `/handoffs`, `/status-mappings`, `/privacy`, `/map`.

---

## 5. Phần CHƯA kiểm chứng (đừng coi là đã chạy được)

- **Docker/Caddy/HTTPS** chưa được chạy thử (sandbox không có Docker). `infra/` và `docs/RUNBOOK.md` mục B mới ở mức viết xong, chưa dựng thật. Sao lưu/khôi phục (`backup.sh`, `restore-test.sh`) cũng chưa thử trên máy chủ thật.
- **In giấy và quét mã QR thật**: PDF khổ A6/A5/4 tờ A4 đã kiểm tra kích thước, mã QR đã giải mã lại bằng test, nhưng chưa in ra giấy và quét bằng điện thoại thật. **Chỉ in khi `APP_PUBLIC_BASE_URL` là địa chỉ thật.**
- **Kênh Zalo thật cho bot**: chưa chạy spike nào (ADR-0001 ghi "CHƯA QUYẾT").
- **Bộ gửi SMS/ZBS**: không có, và chưa chọn nhà cung cấp. Đừng viết khi chưa có chủ shop quyết.
- **J&T**: cấu trúc file đã được xác nhận trên cổng khách hàng (mục 8) nhưng importer chưa được chạy với một file J&T thật; `fixtures/jt_sample.csv` vẫn là file giả lập có cột khác file thật.
- **Thời tiết thật**: gọi MET Norway chưa chạy thử với mạng thật (test dùng bản giả); điều khoản đã đọc ngày 21/11/2026 nhưng trang giấy phép `api.met.no/doc/License` chưa đọc riêng.
- Pháp lý: không phải lời khuyên pháp lý; các điều NĐ 91/2020, Luật 91/2025, NĐ 356/2025, NĐ 330/2026 trong file 03 là **nguồn phụ, cần đối chiếu Công báo**.
- Dữ liệu khách chưa mã hóa đĩa (sao lưu mã hóa được bằng `age`, tùy chọn). Hiện **chỉ một tài khoản đăng nhập**.

---

## 6. Việc phải hỏi chủ shop (đang mở)

1. **Đ1**: bố và mẹ có cần **hai tài khoản web riêng** không (nhật ký ghi đúng người)? Thiết kế Penpot vẽ hai tài khoản; code mới có một.
2. **Đ2**: "đăng nhập của AI bot" = tài khoản Zalo riêng của trợ lý (không phải đăng nhập web), đúng không?
3. **Kênh trợ lý trong nhóm Zalo**: chưa chọn (Zalo Bot chính thức, Zalo OA/GMF, tài khoản cá nhân riêng có rủi ro khóa). Xem ADR-0001.
4. **Mẹ nhận báo việc từ trợ lý bằng đường nào** (màn "Việc cần mẹ xử lý" đang ghi "chưa chọn").
5. **Địa chỉ ngắn và tên miền cho mã QR** (cần HTTPS; thiết kế dùng đường ngắn `/n`).
6. **SMS**: khi nào làm lại, nhà cung cấp nào (eSMS/SpeedSMS), cá nhân chưa có hộ kinh doanh có đăng ký brandname được không.
7. **J&T**: xem mục 8.

---

## 7. Việc tiếp theo, theo thứ tự ưu tiên

1. **Làm giao diện theo Penpot đã duyệt** (mục 9). Đây là việc người dùng đã bảo "code luôn". Chia PR nhỏ: (a) thanh bên trái + số việc cần làm + "Việc cần làm hôm nay"; (b) thuật ngữ 100% tiếng Việt ("bot"→"trợ lý AI", "CSV"→"Excel", bỏ mã nội bộ, `admin`→"Bố/Mẹ"); (c) chi tiết khách 2 cột + khung xóa dữ liệu màu đỏ; (d) trang khách + 3 trạng thái phụ + chiến dịch + cài đặt gửi tin; (e) giấy in A5 + đường `/n`.
2. **Bộ nhập J&T** (mục 8): đọc theo tên cột, tải nhiều file cùng lúc (mỗi lần xuất tối đa 500 dòng), gộp theo mã vận đơn, tách tỉnh từ "Địa chỉ hành chính"; cổng nạp chung `ShipmentIngest`; nhắc nhập file định kỳ. Không làm đăng nhập tự động hay đọc email OTP.
3. Chạy thử **Docker/HTTPS thật** trên VPS theo RUNBOOK, đặt `APP_PUBLIC_BASE_URL`, in thử một tờ giấy và quét bằng điện thoại.
4. Hai tài khoản bố/mẹ (sau khi trả lời Đ1).
5. Chọn kênh trợ lý và chạy spike ADR-0001; chọn cách báo việc cho mẹ.
6. Bản đồ ranh giới tỉnh thật (cần file GeoJSON có giấy phép), nếu chủ shop muốn.
7. Giai đoạn 2 (file 03): tài khoản nhân viên (2B), nhiệm vụ theo nhóm (2C), ghi đơn thủ công (2D), báo cáo (2E).

---

## 8. Đồng bộ đơn mới từ J&T (mục 2F file 03)

**Người dùng đã chọn phương án A: nhập file tăng dần.** Người dùng muốn hệ thống **mỗi ngày tự động xuất file đơn từ J&T rồi nhập vào**, thay vì mẹ tải tay. Chưa có code cho phần này.

**Đã kiểm chứng (21/11/2026):** trang tài liệu API chính thức `api-docs.jtexpress.vn` có các mục Tạo đơn, Hủy đơn, Tra hành trình, Tra giá tiền, Mã hóa API, F.A.Q. **Không thấy mục "liệt kê đơn theo thời gian"** (chỉ thấy tên mục từ kết quả tìm kiếm). Công cụ đọc web bị chặn bởi `robots` nên **chưa đọc được nội dung** (xác thực, giới hạn, trường dữ liệu, có SĐT hay không, webhook, môi trường thử). Trustsales nói phải tạo tài khoản jtexpress.vn rồi liên hệ J&T lấy token và mã khách hàng. Webhook của Goship là của Goship, không phải của J&T.

**Mâu thuẫn tài liệu:** file 03 mục 2F viết "đã kiểm chứng với 6 file J&T thật", còn README ghi J&T chưa kiểm chứng và thư mục `data_product/` không có trong repo. Migration `V3__jt_status_mapping.sql` có nói trạng thái J&T lấy từ file "ĐƠN HÀNG" thật xuất 21/11/2026. Coi như **repo không có file J&T thật nào**; muốn viết test chắc chắn cần một file đã che SĐT/tên/địa chỉ từ người dùng.

### Kết quả xác nhận trực tiếp trên cổng khách hàng J&T (21/11/2026, luồng chạy trên máy người dùng, chỉ đọc)
Không đưa dữ liệu thật, SĐT, tên khách hay mã tài khoản vào repo. Đã **kiểm chứng** trên `khachhang.jtexpress.vn`:
1. Màn **Đơn hàng** (`/orderTable`) có nút **"Xuất (n)"** tải ngay file `.xlsx`, tên dạng `Data <dd-MM-yyyy-HH-mm-ss> <mã tài khoản> ĐƠN HÀNG.xlsx`, **một sheet, dòng 1 là tiêu đề**. Lọc theo thời gian (Thời gian tạo / Ngày lấy hàng / Thời gian ký nhận, mặc định 7 ngày), theo trạng thái (thẻ), địa chỉ gửi, người tạo, thanh toán, dịch vụ. Tìm theo mã vận đơn (tối đa 500), SĐT, mã đơn khách hàng, tên người nhận. Bảng hiện 20 đơn/trang nhưng số trên nút Xuất khớp tổng đơn đã lọc.
2. File Excel mẫu có **14 cột**: Mã vận đơn, Trạng thái vận đơn, SĐT người nhận, Địa chỉ người nhận, Tên người nhận, Địa chỉ hành chính, Nội dung hàng hóa, Thời gian tạo đơn, Trọng lượng tính phí, Cước phí, Phương thức kết toán, Giao một phần, Tiền thu hộ COD, COD thực thu.
   - **SĐT đầy đủ, không bị che** (10 số). Mã vận đơn 12 chữ số. Ngày tạo là **chuỗi** `yyyy-MM-dd HH:mm` (không có giây). Tiền là số thuần. "Giao một phần" là chữ "Tick 1 phần".
   - **Không có cột tỉnh riêng**: tỉnh nằm trong **"Địa chỉ hành chính"** dạng "Xã-mã, Huyện, Tỉnh". Ghi chú cho phân vùng (D11): hiện `customer.address` lấy từ "Địa chỉ người nhận"; nếu cột này chỉ có số nhà/đường thì `VnGeo.detect` ra "Chưa rõ". **Việc cần làm:** thêm trường nhập "Địa chỉ hành chính" (từ khóa `dia chi hanh chinh`) trong `imports/FieldKey.java` và ghép vào địa chỉ khi lưu (hoặc truyền riêng cho `VnGeo.detect`), rồi thêm test.
   - File **không có** ngày lấy hàng, ngày ký nhận, mã đơn khách hàng, dù hộp "Cấu hình cột" có các mục này. Không có mã đơn khách hàng nghĩa là gộp đơn tách kiện (BR về mã đơn) không làm được cho J&T; khách có thể bị gắn cờ "cần kiểm tra" nhiều hơn.
   - Importer hiện đoán cột bằng từ khóa (`FieldKey`), đã chấp nhận ngày `uuuu-MM-dd HH:mm`, "SĐT người nhận", "Địa chỉ người nhận", "Tên người nhận", "Trạng thái vận đơn", "Thời gian tạo..." (khớp theo "chứa từ khóa"); cần **chạy thử với file thật** để chắc chắn, đặc biệt với cột "Mã vận đơn" 12 chữ số (Excel dễ đổi thành số khoa học) và số 0 đầu của SĐT.
3. **12 thẻ trạng thái**: Tất cả, Đợi lấy hàng, Đã lấy hàng, Đang vận chuyển, Giao thành công (con: Kí nhận CPN, Đã đối soát), Đang giao hàng, Đăng ký chuyển hoàn, Đang chuyển hoàn, Ký nhận chuyển hoàn, Kiện vấn đề, Hủy, Kết thúc. Bảng ánh xạ `V3__jt_status_mapping.sql` mới có 5 trạng thái (Đã lấy hàng, Đang vận chuyển, Giao thành công, Hủy, Ký nhận chuyển hoàn). **Các trạng thái còn lại** (Đợi lấy hàng, Đang giao hàng, Đăng ký chuyển hoàn, Đang chuyển hoàn, Kiện vấn đề, Kết thúc, Kí nhận CPN, Đã đối soát) chưa gán nghĩa: để chủ shop chọn ở màn Trạng thái đơn, hoặc đề xuất sẵn nhưng **không tự coi là "Giao thành công"** nếu chưa chắc ("Kết thúc", "Đã đối soát", "Kí nhận CPN" đều có thể là đã giao xong nhưng cần hỏi).
4. Menu **Thiết lập** (`/setting`) chỉ có: Nội dung ghi chú, Danh sách đen, Cấu hình thông báo đẩy, Danh sách người nhận, Dịch vụ gia tăng. **Không thấy API, token, webhook, tích hợp hay lịch xuất tự động.** Thanh trên còn: Hoàn 1 phần, Sản phẩm, Dòng tiền, Yêu cầu, Chat.

### Bổ sung (cùng ngày, luồng xác thực J&T chạy trên máy người dùng, chỉ đọc)
5. **Đăng nhập J&T có OTP gửi qua email** => không thể tự đăng nhập ổn định, và không nên đọc email OTP để vượt qua. Không khuyên làm bot đăng nhập.
6. Màn Đơn hàng **chỉ xem tối đa 1 tháng mỗi lần** và **xuất tối đa 500 dòng mỗi trang**: phải chọn toàn bộ dòng của trang đó rồi mới xuất Excel đủ. Muốn lấy nhiều đơn phải xuất nhiều lần (nhiều khoảng thời gian/nhiều trang), mỗi lần một file.
7. Đã xem thêm menu tài khoản: **Hồ sơ** (4 thẻ: Thông tin shop, Kho hàng/Trả hàng, Quản lý tài khoản con, Điều khoản và chính sách), **Dòng tiền** (Lịch sử đối soát có cột "Tải về"; nút Rút tiền, Trả cước, Báo cáo), **Yêu cầu** (chỉ là vé hỗ trợ). **Không có API, token, webhook, email báo cáo hay lịch xuất tự động** ở bất kỳ menu nào đã xem.

**Còn chưa biết:** nút **Báo cáo** ở Dòng tiền (chưa bấm nên chưa biết tải gì); thẻ **Quản lý tài khoản con** (tài khoản con có thể cho một tài khoản riêng chỉ-xuất-file, nhưng vẫn dính OTP email); menu con của "Đợi lấy hàng"; giới hạn khi xuất nhiều tháng (đã biết mỗi lần chỉ xem được 1 tháng); "Cấu hình cột" có thêm cột vào file xuất không; mã tài khoản trong tên file có cố định không.

**Ý nghĩa và kết luận:** **phương án A (nhập file Excel J&T tăng dần) khả thi và có đủ SĐT, nhưng chỉ bán tự động**: mẹ (hoặc người được giao) đăng nhập J&T bằng OTP email, chọn khoảng thời gian, chọn toàn bộ dòng của trang rồi bấm "Xuất", sau đó thả file vào hệ thống. Thiết kế bộ nhập J&T:
- Đọc theo **TÊN cột** (14 cột ở trên), không theo vị trí cột; ngày tạo là chuỗi `yyyy-MM-dd HH:mm`; tách tỉnh từ "Địa chỉ hành chính".
- Mỗi lần xuất tối đa 500 dòng nên cần **tải lên nhiều file J&T trong một lần** và **gộp theo mã vận đơn** (khóa hãng + mã vận đơn; cùng một đơn nằm ở hai file thì lấy bản mới hơn, ghi lịch sử đổi trạng thái); báo cáo xem trước hiển thị số đơn mới, đơn đổi trạng thái, đơn trùng.
- Nhắc mỗi sáng (hoặc theo tuần) nếu chưa có file mới là tính năng thay cho tự động hóa; **không** tự đăng nhập J&T, **không** đọc email OTP, **không** lưu mật khẩu J&T trên máy chủ.
- **Chưa có bằng chứng J&T hỗ trợ tự động hóa chính thức** (API/webhook/báo cáo định kỳ); cần hỏi nhân viên kinh doanh hoặc bưu cục J&T nếu muốn đi hướng đó (phương án B, mục 2F file 03).

**Về việc "tự động xuất file hằng ngày":** muốn vậy hệ thống phải tự đăng nhập tài khoản J&T thay mẹ. Rủi ro: (1) phải lưu mật khẩu J&T trên máy chủ, (2) có thể vi phạm điều khoản của J&T và bị khóa tài khoản, (3) **đã xác nhận đăng nhập J&T có OTP qua email** nên không thể tự động ổn định, và đọc email OTP để vượt qua là hướng không nên làm; hỏng khi J&T đổi giao diện. File 03 mục 2F cũng ghi rõ **không đăng nhập cổng web của hãng bằng mật khẩu của shop**. Các cách tự động an toàn hơn đã đề xuất với người dùng (chưa nhận trả lời):
1. Hỏi J&T xem tài khoản shop có bật được **báo cáo đơn tự gửi mail hằng ngày** hoặc **API riêng** (phương án B) không.
2. **Thư mục dùng chung** (Google Drive/OneDrive): mẹ chỉ thả file J&T vào, hệ thống nhập mỗi ngày.
3. Trang nhập file kéo thả kèm **nhắc mỗi sáng** nếu hôm qua chưa có file mới.
Chỉ khi người dùng **hiểu rủi ro và đồng ý rõ ràng bằng lời** mới thử bot đăng nhập; khi đó mật khẩu phải cất kín (biến môi trường/kho bí mật), **không để trong repo, log hay bộ nhớ chung**.

**Thiết kế nên làm trước (không phụ thuộc cách lấy file):** một cổng nạp chung `ShipmentIngest` cho mọi nguồn (file, thư mục chia sẻ, API sau này); khóa theo (hãng + mã vận đơn); "đơn mới kể từ lần trước"; ghi lịch sử đổi trạng thái; nhập lặp không đếm trùng; khách mới chỉ vào danh sách, **không tự động được mời** (vẫn qua cổng cơ sở liên hệ). Logic phân loại 1A giữ nguyên.

**Câu hỏi phải hỏi J&T** (quyết định có làm được API hay không): đơn tạo trên app/web của J&T có được đẩy về webhook/API không; dữ liệu trả về có **SĐT người nhận** không (không có thì không tạo được khách); có API lấy lại danh sách đơn cũ không; điều kiện và chi phí cấp tài khoản API.

> Phần xác nhận J&T ở trên là kết quả đọc trực tiếp ngày 21/11/2026. Nếu có thêm kết quả (OTP, báo cáo mail, API), ghi bổ sung ngay dưới đây.

---

### Báo cần xuất Excel từ ngày nào (J&T, đồng bộ làm tay)

- Chủ shop chốt (21/11/2026): đồng bộ J&T làm **tay** (mẹ xuất Excel rồi thả vào Adh), không tích hợp chính thức, không tự đăng nhập.
- `imports/ExportWindowService`: mốc "xuất từ" = **thời gian tạo đơn lớn nhất đã nhập** (không dùng giờ trong tên file) trừ `app.imports.overlap-days` (14, đề xuất chưa đo thực tế); lùi thêm tới đơn cũ nhất chưa có kết quả cuối (Giao thành công, Hủy, Ký nhận chuyển hoàn) nếu đơn đó trong `app.imports.lookback-days` (30). Đơn treo quá cũ (ví dụ "Đã lấy hàng" từ tháng 6 đến 7/2026) chỉ được đếm riêng, không kéo mốc lùi mãi và không bao giờ là mua thành công.
- Chia khoảng: J&T chỉ lọc tối đa 1 tháng và 500 đơn mỗi lần, nên khoảng dài hơn 31 ngày hoặc ước tính gần 500 đơn được chia thành các khoảng liền nhau. Hiện ở trang Nhập file mới và thành việc trên Tổng quan khi quá `app.imports.remind-days`.
- **Chưa làm:** file "Lịch sử đối soát" (Mã đơn KH, thời gian ký nhận, tiêu đề ở dòng 3, kỳ đối soát thứ 2/4/6) chưa có importer, cần file mẫu thật; trạng thái "Kiện vấn đề" vẫn cố ý để chủ shop chọn nghĩa ở màn Trạng thái đơn; chưa cảnh báo khi một file đúng 500 dòng (có thể bị cắt).

## 9. Giao diện Penpot đã duyệt (đã code, chưa xem bằng mắt)

> Cập nhật: các mục (a)–(e) của mục 7.1 đã code. Còn thiếu: tài khoản Bố và Mẹ riêng (chờ Đ1), cách báo việc cho mẹ (chờ chọn), xem thử bằng trình duyệt thật và in thử giấy A5, "Khách đổi nhóm" ở màn xem trước nhập file. Giấy in mặc định A5; chữ mặc định theo kiểu "anh/chị" (đổi được ở trang In giấy giới thiệu).

- Người dùng **đã duyệt** thiết kế (21/11/2026) và dặn "sửa xong Penpot thì code luôn".
- Bản sao trong repo: `docs/thiet-ke-penpot/`
  - `thay-doi-thiet-ke-penpot.md`: **danh sách thay đổi cần code** (đọc trước tiên).
  - `doi-chieu-penpot-nghiep-vu.md`: đối chiếu thiết kế với nghiệp vụ, lý do từng thay đổi.
  - `giay-in-gioi-thieu-nhom-zalo.md` và `giay-in-A5-mau.png`: tờ giấy in A5 mẫu và 10 dòng chữ sửa được.
- File Penpot gốc: file **"adh"**, trang **"Bản chỉnh sửa (đã rà soát)"** (16 màn + 4 trạng thái phụ: đăng nhập bị khóa 15 phút, trang khách đã ngừng nhận tin, link hết hạn, các nhóm đã đủ người) và trang **"Giấy in giới thiệu"** (A5 dọc, 10 dòng chữ sửa được). Trang gốc "Page 1" giữ nguyên. Truy cập qua connector Penpot cloud (plugin Penpot bị trình duyệt đóng băng nếu tab Penpot không hiện trên màn hình, cần cửa sổ riêng). Trong phiên đã bàn giao này, **không gọi công cụ Penpot**; mọi thông tin nằm trong các file trên.
- Tóm tắt thay đổi chính: (1) thanh trên cùng → **thanh bên trái chia nhóm, 12 mục có đếm việc cần làm** (Khách hàng: Tổng quan, Nhập đơn, Khách mua 1 lần, Khách mua từ 2 lần, Trạng thái đơn; Mời vào nhóm: Danh sách mời, Chiến dịch mời, Nhóm Zalo, Cài đặt gửi tin; Trợ lý AI: Việc cần mẹ xử lý, Tài khoản trợ lý AI; Khác: Yêu cầu của khách) — **các mục mới thêm sau thiết kế là "Bản đồ khách" và "In giấy giới thiệu"**, nên xếp vào nhóm Khách hàng và Mời vào nhóm; (2) mỗi màn một việc chính, Tổng quan mở đầu bằng "Việc cần làm hôm nay", chi tiết khách chia 2 cột, phần xóa dữ liệu nằm riêng trong khung đỏ; (3) chữ nội dung ≥17px, nhãn nhỏ ≥16px (CSS hiện có `.badge` và `.mono` 15px cần tăng); (4) số liệu khớp giữa màn Tổng quan, Danh sách mời, Chiến dịch (chỉ khách **đã duyệt** tính là đủ điều kiện mời); trang khách có nút "Tôi không muốn nhận tin nữa" (đã có) và 3 trạng thái phụ; chiến dịch có nút "Xuất danh sách để mẹ gửi tay" (đã có); hạn yêu cầu dữ liệu theo loại; trợ lý AI "chưa chọn cách kết nối"; (5) bỏ chữ kỹ thuật: "bot"→"trợ lý AI", "CSV"→"Excel", bỏ `DRY_RUN`, "kill switch", `BR-16`, `admin`, mã nội bộ; chỉ giữ tên riêng Viettel Post, J&T Express, Zalo, SMS, QR.
- **Việc code giấy in:** đường chung ngắn `/n` (hiện là `/moi`, giữ `/moi` làm bí danh; chưa in giấy nào nên đổi mã QR được), mã QR sinh trên máy chủ (đã có, `QrCodes`, `/print/qr.png|svg`), trang cài đặt chữ (đã có `PrintTexts`, `/print/texts`: kiểm tra đủ 10 dòng theo thiết kế), trang in A5 (hiện có A6/A5/4 tờ A4; thiết kế là A5 dọc), đường chung dùng thuật toán chia nhóm hiện có, đếm lượt bấm gộp, **chỉ POST mới tính lượt bấm** (trang `/moi` đã làm vậy).
- **Chỗ hay vướng khi làm giao diện:** (a) CSP cấm script/style nội tuyến; (b) nhiều test kiểm tra chữ tiếng Việt cụ thể (ví dụ "Bot", "Chạy thử") nên đổi thuật ngữ phải sửa test theo; (c) Thymeleaf 3.1 không còn `#request`/`#httpServletRequest`, muốn biết đường dẫn hiện tại để tô sáng mục thanh bên thì đưa vào model bằng `@ControllerAdvice`/`@ModelAttribute`; (d) nhãn tiếng Việt cho mã kỹ thuật nằm ở `common/Labels.java`, đừng để template hiện mã gốc (`OPEN`, `READY`, `DRY_RUN`...); (e) thanh bên cố định + `main { margin-left }` chỉ cần CSS, không phải sửa từng template vì mọi trang dùng `~{fragments :: nav}`.

---

## 10. Quy tắc làm việc với dự án (giữ nguyên)

- Trả lời người dùng bằng **tiếng Việt**; giao diện 100% tiếng Việt.
- **Mặc định không liên hệ**; mỗi khách được mời **một lần**; sổ từ chối chỉ lưu mã băm; không SĐT đầy đủ trong log; không đưa dữ liệu khách ra dịch vụ ngoài (thời tiết chỉ nhận tọa độ).
- Gửi thật, kênh bot thật, thời tiết: **tắt mặc định**, chỉ chủ shop bật.
- **Không làm thủ thuật né phát hiện** khi gửi tin (giả lập thao tác, đổi dấu vân tay, xoay vòng tài khoản); không dùng tài khoản Zalo của mẹ.
- Không ghi bí mật (mật khẩu, token) vào repo, log hay bộ nhớ chung.
- Thay đổi mới: PR mới từ `main`, gộp khi CI xanh, báo lại bằng tiếng Việt. Không gộp PR chưa xong; việc dở thì để PR nháp ghi rõ phần đã xong và phần còn dở.
