# Hệ thống chăm sóc khách cũ qua Zalo — Giai đoạn 1

Tài liệu gốc: [`01_DAC_TA_NGHIEP_VU.md`](01_DAC_TA_NGHIEP_VU.md) (nghiệp vụ, nguồn sự thật),
[`02_NGHIEN_CUU_KY_THUAT.md`](02_NGHIEN_CUU_KY_THUAT.md) (nghiên cứu cũ, có chỗ sai),
[`03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md`](03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md) (đính chính + kế hoạch hai giai đoạn).

## Trạng thái

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| **1A** | Nền tảng, nhập file Viettel Post/J&T, chuẩn hóa SĐT, phân loại `ONE_TIME`/`REPEAT`, xuất CSV | **Xong, đã kiểm chứng bằng test** |
| **1B** | Sổ cơ sở liên hệ, danh sách từ chối, danh sách mời, quyền dữ liệu của khách (xem/xuất/ngừng liên hệ/xóa + hạn phản hồi), ghi chú tay | **Xong, đã kiểm chứng bằng test** |
| 1C | Nhóm Zalo + lời mời ở chế độ thử (DRY_RUN) | Chưa làm |
| 1D–1F | Gửi thật lô nhỏ, bot AI, đóng gói bàn giao | Chưa làm |

Mốc 1A **không gửi tin, không đăng nhập Zalo, không gọi dịch vụ ngoài nào**. Chỉ đọc file bạn tải lên.

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
Việc đóng gói chạy thật (HTTPS, sao lưu, VPS tại Việt Nam) thuộc mốc 1F.

## Kiểm thử

```powershell
cd app
./mvnw test
```

86 test, chạy trên PostgreSQL nhúng thật (không giả lập), gồm:

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
- **J&T chưa kiểm chứng.** File mẫu `fixtures/jt_sample.csv` là giả lập; tên cột và trạng thái của J&T là **suy đoán**,
  cần một file mẫu thật (đã ẩn thông tin cá nhân) để xác nhận.
  Chọn cột và gán nghĩa trạng thái làm được ngay trên giao diện, không cần sửa code.
- Ánh xạ trạng thái Viettel Post lấy từ hướng dẫn "Quản lý vận đơn" của hãng. "Chờ xử lý" cố ý **chưa gán** vì chưa rõ nghĩa.
- Chỉ đọc `.xlsx` (sheet đầu tiên) và CSV UTF-8. File `.xls` cũ phải lưu lại sang `.xlsx`.
- Nhập một file **cũ hơn** sau file mới có thể ghi đè trạng thái mới bằng trạng thái cũ; màn xem trước báo số vận đơn "đổi trạng thái" để bạn nhận ra.
- Chưa có giới hạn thử đăng nhập sai nhiều lần, chưa có HTTPS: thuộc mốc 1F. **Không đưa ứng dụng ra Internet ở trạng thái này.**
- Dữ liệu khách (SĐT, địa chỉ) lưu trong PostgreSQL (chưa mã hóa đĩa: thuộc mốc 1F).
- Hiện chỉ có **một** tài khoản đăng nhập (OWNER). Tài khoản riêng cho bố và mẹ đang chờ chủ shop xác nhận (xem mục 4B file 03).
