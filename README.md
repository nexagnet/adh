# Hệ thống chăm sóc khách cũ qua Zalo — Giai đoạn 1

Tài liệu gốc: [`01_DAC_TA_NGHIEP_VU.md`](01_DAC_TA_NGHIEP_VU.md) (nghiệp vụ, nguồn sự thật),
[`02_NGHIEN_CUU_KY_THUAT.md`](02_NGHIEN_CUU_KY_THUAT.md) (nghiên cứu cũ, có chỗ sai),
[`03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md`](03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md) (đính chính + kế hoạch hai giai đoạn).

> **Đọc [`docs/BAN-GIAO.md`](docs/BAN-GIAO.md) trước** (cập nhật 22/11/2026, `main` đã gộp PR #3 đến #32). Có trạng thái từng mốc, các quyết định, giả định, công tắc cấu hình, phần chưa kiểm chứng, kết quả xác nhận J&T và danh sách việc còn phải code. Thiết kế giao diện đã duyệt nằm ở [`docs/thiet-ke-penpot/`](docs/thiet-ke-penpot/).

## Trạng thái

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| **1A** | Nền tảng, nhập file Viettel Post/J&T, chuẩn hóa SĐT, phân loại `ONE_TIME`/`REPEAT`, xuất CSV | **Xong, đã kiểm chứng bằng test** |
| **1B** | Sổ cơ sở liên hệ, danh sách từ chối, danh sách mời, quyền dữ liệu của khách (xem/xuất/ngừng liên hệ/xóa + hạn phản hồi), ghi chú tay | **Xong, đã kiểm chứng bằng test** |
| **1C** | Nhóm Zalo, chiến dịch mời theo lô nhỏ, link riêng + trang khách bấm link, đếm lượt bấm (chế độ thử) | **Xong, đã kiểm chứng bằng test** |
| **1D** | Cổng chặn gửi, bộ gửi chạy thử và xuất danh sách cho mẹ gửi tay, nhật ký gửi, tự dừng | **Xong, đã kiểm chứng bằng test.** Đường mời hiện dùng: Zalo, mẹ gửi tay. SMS/ZBS chưa dùng, chưa có bộ gửi |
| **1E** | Lõi bot (FAQ, báo mẹ khi khách muốn mua/khiếu nại, DRY_RUN, tắt khẩn cấp) | **Xong phần lõi, đã kiểm chứng bằng test.** Chưa có kênh Zalo thật: cần chạy thử chọn phương án (`docs/ADR-0001-chon-kenh-bot.md`) |
| **1F** | Đóng gói Docker + HTTPS, sao lưu và thử khôi phục, giới hạn đăng nhập sai, hướng dẫn vận hành | **Xong phần mã và script**, kèm cách cài lên máy chủ dùng chung (`infra/deploy.sh`, ảnh build sẵn trên ghcr, PR #11 và #12). Sao lưu/khôi phục trên máy chủ thật chưa có bằng chứng đã thử (xem `docs/RUNBOOK.md`) |
| **1G** | Phân vùng khách Bắc/Trung/Nam theo địa chỉ, xếp nhóm Zalo theo khu vực | **Xong, có test** (PR #4, #15, #20) |
| **1H** | Bản đồ khách theo tỉnh (Leaflet + OpenStreetMap), nhắc việc thời tiết | **Xong, có test**, thời tiết tắt mặc định, chưa thử với mạng thật (PR #5, #19) |
| **Giao diện Penpot** | Thanh bên trái, "Việc cần làm hôm nay", thuật ngữ tiếng Việt, giấy in A5, đường `/n` | **Đã code** (PR #8, #10, #17); chưa xem bằng trình duyệt thật, chưa in thử giấy A5 |
| **D13** | Nhắn bằng tài khoản Zalo phụ, chiến dịch theo khu vực, lô nhắn hôm nay, chạy thử `/test-runs` | **Xong, có test** (PR #16, #18); người thật nhắn tay và bấm đánh dấu |
| **1I** | Kết bạn → vào nhóm ngay (D15, D16) | **Bộ nối Zalo không chính thức (D16) đã code, mặc định TẮT, chưa chạy với Zalo thật** (PR #29 đến #32). Đường bán tự động của D15 (gợi ý nhóm ngay lúc kết bạn, giữ chỗ, nhắc việc, "chờ nhóm") **chưa code** |
| **2F (làm sớm)** | Nhập đơn J&T: nhiều file, Địa chỉ hành chính, báo cần xuất từ ngày nào, tool cập nhật bằng Edge | **Đã code** (PR #9, #13, #22); **chưa chạy với một file J&T thật đã che thông tin** trong test |

Ngoài ra đã gộp vào `main`: giấy giới thiệu với mã QR chung `/n` (và `/moi`) và `/print` (PR #3, #10), danh sách khách lọc/sắp xếp theo cột (PR #21), tính "mua gần nhất" theo đơn giao thành công (PR #23).

Mặc định hệ thống **không gửi tin tự động, không đăng nhập Zalo, không gọi dịch vụ ngoài nào (trừ: thời tiết nếu bạn bật `APP_WEATHER_ENABLED`, chỉ gửi tọa độ tỉnh; và trình duyệt của bạn tải ô bản đồ từ OpenStreetMap khi mở trang Bản đồ khách, không kèm dữ liệu khách)**. Chỉ đọc file bạn tải lên; muốn mời khách thì xuất danh sách hoặc dùng Lô nhắn hôm nay cho mẹ nhắn tay. **Ngoại lệ có chủ ý, tắt mặc định (D16):** bộ nối Zalo không chính thức `zalo-bridge/` (cần `APP_ZALO_AUTO_ENABLED`, profile Docker `zalo-auto` và chọn nấc ở trang `/zalo`), chỉ dùng tài khoản phụ; xem `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`.

Hướng dẫn dùng và cài máy chủ: [`docs/RUNBOOK.md`](docs/RUNBOOK.md).

## Thử ngay trên máy (không cần Docker, không cần cài PostgreSQL)

Cần Java 25. Dữ liệu thử là dữ liệu **giả**.

```powershell
cd app
./mvnw spring-boot:test-run "-Dspring-boot.run.mainClass=vn.shop.crm.DevApplication"
```

Mở <http://localhost:8080>, đăng nhập `admin` / `dev-only-password` (chỉ dùng khi thử trên máy mình).
Vào **Nhập đơn → Nhập file mới**, chọn hãng và file trong thư mục [`fixtures/`](fixtures/):

- `viettelpost_sample.csv` (hãng Viettel Post) rồi `jt_sample.csv` (hãng J&T Express).
- Kết quả mong đợi: 2 khách mua 1 lần, 2 khách mua từ 2 lần, 6 chưa có đơn giao thành công, 5 cần kiểm tra,
  3 trạng thái chưa rõ nghĩa (vào **Trạng thái đơn** để chọn nghĩa, ví dụ "Đã ký nhận" = Giao thành công).

Dữ liệu thử nằm trong `app/target/dev-pg`; xóa thư mục này để làm lại từ đầu.
Tắt ứng dụng bằng Ctrl+C. Nếu cổng 54329 còn bận, tắt tiến trình `postgres` của lần chạy trước.

## Chạy với PostgreSQL thật (máy chủ)

```powershell
cd infra
copy .env.example .env      # rồi sửa mật khẩu trong .env
docker compose --env-file .env up -d
cd ../app
$env:DB_PASSWORD = "<mật khẩu trong .env>"
$env:APP_ADMIN_PASSWORD = "<mật khẩu quản trị>"
./mvnw spring-boot:run
```

Ứng dụng **từ chối khởi động** nếu chưa đặt `APP_ADMIN_PASSWORD`. Không có mật khẩu mặc định.
Chạy thật trên máy chủ (HTTPS, sao lưu, giới hạn bộ nhớ cho VPS 2 GB): dùng `infra/docker-compose.prod.yml`, xem `docs/RUNBOOK.md`.

## Kiểm thử

```powershell
cd app
./mvnw test
```

Hơn 380 test Java (số chính xác xem kết quả CI) và khoảng 50 test Node trong `zalo-bridge/` (`npm test`), chạy trên PostgreSQL nhúng thật (không giả lập). Các tiêu chí nghiệm thu của file 01:

| Tiêu chí nghiệm thu (file 01, mục 12) | Test |
|---|---|
| 1. Nhập cùng file hai lần không tăng số lần mua | `ImportAcceptanceTest.nhapCungMotFileHaiLanKhongLamTangSoLanMua` |
| 2. VTP + J&T cùng SĐT, mỗi hãng 1 đơn thành công ⇒ `REPEAT` | `motDonThanhCongOMoiHangCungSdtLaRepeat` |
| 3. 1 giao thành công + 2 hủy/hoàn ⇒ `ONE_TIME` | `motDonGiaoThanhCongVaHaiDonHuyHoanLaOneTime` |
| 4. Trạng thái chưa xác định không tự coi là thành công | `trangThaiChuaXacDinhKhongDuocTuCoiLaThanhCong` |
| 5. Xem/xuất hai danh sách, xem từng đơn tạo ra kết quả | `xemDuocTungDonTaoRaKetQuaPhanLoai`, `WebSecurityTest` |


## CI và Claude Review

GitHub Actions chạy `test` và `lint` trên pull request và mỗi lần push lên `main`.
`test` dùng Java 25, Maven Wrapper và PostgreSQL nhúng, với ngưỡng bao phủ dòng
80% qua JaCoCo. `lint` kiểm tra workflow và chặn file Excel, dữ liệu trong
`data_product/`, file `.env` thật và build output.

Để chạy cùng bước Maven với job `test` trên máy, vào thư mục `app/` và chạy
`./mvnw --batch-mode --no-transfer-progress verify`.

Khi hai job đều xanh trên pull request nội bộ đã sẵn sàng, workflow gọi Reviewer
của `nexagnet/.github` theo commit đã ghim, dùng Actions secret
`CLAUDE_CODE_OAUTH_TOKEN` của tổ chức. Các thay đổi CI và đường dẫn nhạy cảm cần
người duyệt theo quy tắc của Reviewer dùng chung.

## Mốc 1B: cơ sở liên hệ và quyền dữ liệu (đã cài)

- **Mặc định KHÔNG được liên hệ.** Khách chỉ vào *Danh sách mời* (`/contacts`) khi đồng thời: mua đúng 1 lần, không bị gắn "cần kiểm tra", đã được duyệt, và SĐT không nằm trong danh sách từ chối. Điều kiện nằm ngay trong truy vấn, nên khách mua từ 2 lần **không bao giờ** vào danh sách này (có test ép dòng sai vẫn bị chặn).
- Duyệt hàng loạt bắt buộc tích ô *"Chủ cửa hàng chịu trách nhiệm"* (quyết định D3). Mỗi lần duyệt ghi bằng chứng (vận đơn giao thành công), người duyệt và nhật ký. **Duyệt chưa gửi tin cho ai.**
- *Từ chối / ngừng liên hệ* là vĩnh viễn trong ứng dụng (không có nút gỡ), kể cả khi nhập lại file.
- *Xóa dữ liệu một khách* (gõ `XOA` để xác nhận): xóa khách, mọi vận đơn, dòng lỗi nhập chứa SĐT đó và các lô nhập chưa xác nhận. Chỉ giữ **mã băm** SĐT để không nhập lại hay liên hệ lại, và nhật ký chỉ có 3 số cuối.
- Sau khi xác nhận nhập, **file gốc không được giữ lại** trong cơ sở dữ liệu (file chứa SĐT, địa chỉ cả danh sách khách).
- *Yêu cầu của khách* (`/privacy`): ghi nhận ngày khách gửi, hạn phản hồi mặc định 15 ngày (`app.privacy.request-due-days`). **Số ngày này chưa có xác nhận pháp lý.**
- Mã băm SĐT dùng SHA-256 thuần: số điện thoại có ít giá trị nên người cố tình vẫn dò ngược được. Mục đích là không lộ số khi nhìn cơ sở dữ liệu hay bản sao lưu.

## Quy tắc đếm lần mua (đã cài)

- Khách = số điện thoại nhận hàng đã chuẩn hóa về `+84…` (gộp hai hãng). Số thiếu/sai/bị che (`0912****78`) vào danh sách lỗi, không đoán.
- Chỉ vận đơn có trạng thái chuẩn **Giao thành công** mới được tính. Trạng thái chưa có trong bảng ánh xạ ⇒ *chưa xác định*, không bao giờ tính.
- Cùng mã đơn hàng của shop (và cùng SĐT) ⇒ **một** lần mua dù tách nhiều kiện. Không có mã đơn ⇒ mỗi vận đơn là một lần mua riêng, và khách bị gắn cờ **cần kiểm tra** nếu kết quả có thể đếm dư.
- Một lần mua có vận đơn giao thành công kèm vận đơn hoàn vẫn tính là thành công (khách đã nhận hàng).
- Nhập lại file chỉ cập nhật theo khóa (hãng + mã vận đơn). Mọi thay đổi trạng thái được ghi lịch sử (`shipment_status_history`).

## Giới hạn đã biết của 1A (cố ý, ghi lại để không ngộ nhận)

- **Viettel Post đã kiểm chứng với file thật** (6 file "Danh sách vận đơn" T1–T6/2026, khoảng 850 vận đơn, cột SĐT người
  nhận là "ĐT Nhận", dòng tiêu đề ở dòng 9). Ô SĐT có hai số ("a, b") thì lấy số hợp lệ đầu tiên; số còn lại vẫn nằm trong
  dữ liệu gốc của vận đơn. Trạng thái "Đã hủy giao" chưa gán nghĩa, chủ shop tự chọn trong **Trạng thái đơn**.
  File thật nằm ở `data_product/` (chứa dữ liệu khách thật, **không đưa lên git/chia sẻ**).
- **J&T:** cấu trúc file xuất trên cổng khách hàng J&T đã được xác nhận (14 cột, SĐT đầy đủ, tỉnh nằm trong "Địa chỉ hành chính"; chi tiết ở `docs/BAN-GIAO.md` mục 8). Bộ nhập đã đọc theo tên cột và gộp nhiều file (PR #9), nhưng `fixtures/jt_sample.csv` vẫn là file giả lập có cột khác và test chưa dùng file J&T thật.
  Cần một file mẫu thật (đã ẩn thông tin cá nhân) để chắc chắn.
  Chọn cột và gán nghĩa trạng thái làm được ngay trên giao diện, không cần sửa code.
- Ánh xạ trạng thái Viettel Post lấy từ hướng dẫn "Quản lý vận đơn" của hãng. "Chờ xử lý" cố ý **chưa gán** vì chưa rõ nghĩa.
- Chỉ đọc `.xlsx` (sheet đầu tiên) và CSV UTF-8. File `.xls` cũ phải lưu lại sang `.xlsx`.
- Nhập một file **cũ hơn** sau file mới có thể ghi đè trạng thái mới bằng trạng thái cũ; màn xem trước báo số vận đơn "đổi trạng thái" để bạn nhận ra.
- Đăng nhập sai quá 5 lần liên tiếp bị khóa 15 phút theo cặp (IP, tên đăng nhập). HTTPS do Caddy trong `infra/docker-compose.prod.yml`; chạy ứng dụng trực tiếp (không qua Caddy) thì **không đưa ra Internet**.
- Dữ liệu khách (SĐT, địa chỉ) lưu trong PostgreSQL, **chưa mã hóa đĩa hay mã hóa dữ liệu**; bản sao lưu mã hóa được bằng `age` (tùy chọn).
- Hiện chỉ có **một** tài khoản đăng nhập (OWNER). Tài khoản riêng cho bố và mẹ đang chờ chủ shop xác nhận (xem mục 4B file 03).
