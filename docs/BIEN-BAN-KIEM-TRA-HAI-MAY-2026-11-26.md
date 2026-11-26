# Biên bản kiểm tra hai máy ADH ngày 26/11/2026

Múi giờ Việt Nam. Máy chính là nơi phục vụ ADH production. Máy thứ hai là máy production dùng chung với dự án khác. Chủ dự án dành phần ADH bổ sung trên máy hai cho cầu nối tài khoản Zalo 2. Giữ giai đoạn 2 ở môi trường thử.

## Kết quả quan sát

| Hạng mục | Máy chính | Máy thứ hai |
| --- | --- | --- |
| Ảnh ứng dụng lúc kiểm tra | `ghcr.io/nexagnet/adh-app:f0a690348fc065baa94abd9c3b388aa356736535`, chứa PR #173 | `adh-app:38da3b8de149`, ảnh dựng tại máy ngày 21/11 từ commit `38da3b8de149444b59d5938835d838049d962ec6` |
| Checkout và cấu hình | Container app được tạo chỉ từ `docker-compose.shared.yml` | Repo `nexagnet/adh`, nhánh `claude/deploy-tooling` ở `761e8cc`, dùng `docker-compose.prod.yml`. Checkout và ảnh chạy có SHA khác nhau |
| PostgreSQL | App kết nối PostgreSQL trong cùng dự án Compose trên máy chính | App cũ kết nối PostgreSQL riêng trong cùng dự án Compose trên máy hai. Kiểm tra chỉ đọc thấy Flyway V10, 0 khách và 0 vận đơn |
| Zalo | Một bridge đang chạy ảnh cùng SHA. `.env` có `APP_ZALO_MULTI_ENABLED=true` nhưng biến vắng trong container app đang chạy. Tệp `docker-compose.zalo-2.yml` có trên đĩa và chưa được dùng. Chưa khai báo tuyến hoặc khóa của bridge 2 trong `.env` tại vị trí đã kiểm tra | Chưa có container cầu nối ADH thứ hai. App cũ chưa truyền các cờ Zalo |
| Mạng riêng giữa hai máy | Chưa xác minh được tuyến cho bridge 2. Kiểm tra hai tên giao diện phổ biến `wg0` và `tailscale0` đều không thấy trên hai máy | Cùng kết quả |

Trang gốc máy chính trả HTTP 302 tới luồng đăng nhập. `/readyz` cũng trả 302 vì ảnh production tại thời điểm quan sát chưa có kiểm tra readiness mới. Kết quả này ghi nhận đúng bản đang chạy, không dùng 302 làm điều kiện xác nhận phiên bản mới.

## Thao tác đã thực hiện

1. Máy chính chỉ kiểm tra qua SSH. Đối chiếu ảnh đang chạy, tệp Compose gắn với container, cờ đã lưu và cờ container nhận, kết nối cơ sở dữ liệu và HTTP ở loopback. Không chỉnh tệp cấu hình, khởi động lại, đổi cờ hay triển khai mã mới.
2. Máy hai kiểm tra đúng dự án Compose tên `adh`. Xác minh app và DB cũ không công bố cổng riêng ra host, PostgreSQL thuộc mạng của ADH, và Caddy đang phục vụ nhiều tuyến. Không dùng lệnh dừng toàn dự án hoặc dừng Caddy.
3. Theo yêu cầu của chủ dự án, dừng riêng `adh-app-1` và `adh-postgres-1` trên máy hai. Giữ các container đã dừng, ảnh, volume và tệp cấu hình để có thể khôi phục. Tạm ngưng đúng một dòng cron gọi script sao lưu ADH cũ, giữ nguyên mọi dòng khác.
4. Xác minh lại trên máy hai sau thao tác. App cũ và PostgreSQL đều `exited`, Caddy `running`. Không truy cập repo, dữ liệu hay container của `nexagnet/legal-office`. Caddy dùng chung phải tiếp tục chạy.

## Phần mã và kiểm thử

- Mã triển khai ở nhánh `codex/adh-hoan-thien`, bắt đầu tại `f0a6903`. Commit mã `a431b0e` bổ sung chốt inbox trước Tin nhắc 2/3, xác minh SHA và DB khi deploy, sao lưu/khôi phục, nhất quán nhập liệu, hoàn tất yêu cầu Xem và dọn dữ liệu quá hạn, cùng điều hướng việc theo tài khoản.
- Chạy 164 test Node, các nhóm test Java tập trung gồm phân quyền, định tuyến hai bridge và xử lý inbox. Dùng ShellCheck và test script triển khai, cron, sao lưu trên Linux với lệnh giả. Kiểm cấu hình Compose bằng giá trị mẫu. Sao lưu và khôi phục thành công trên một PostgreSQL cục bộ dùng dữ liệu giả, xóa DB khôi phục thử sau kiểm tra.
- Trên trình duyệt cục bộ, trang Hàng việc và Đơn nháp vừa chiều rộng 360 px. Luồng đăng nhập, mở nhóm việc và mở đơn nháp chạy với tài khoản và dữ liệu giả.
- Chủ dự án chọn Hàng việc và tài khoản nhân viên trong ADH, báo việc qua Zalo và dùng TikTok Shop làm nguồn SKU, giá và tồn. `APP_ADH_STAGE2_ENABLED` mặc định tắt. Mã có phân quyền nhóm, giao việc, API tra cứu chỉ đọc và nháp. Luồng kết nối nhóm và báo Zalo mới được kiểm bằng dữ liệu mô phỏng. Chưa xác minh quyền đọc TikTok Shop với tài khoản thật.

## Nghiệm thu còn mở

- Xác minh một đường mạng riêng giữa app máy chính và cầu nối máy hai, rồi chạy cầu nối duy nhất ở dự án Compose riêng `adh-zalo-bridge-2`. Giữ Caddy đang chạy và mọi dịch vụ ngoài ADH. Không khởi động lại cụm ADH cũ để dùng cho tài khoản 2.
- Trước khi bật tuyến multi, đối chiếu ảnh, xác minh đường tới cầu nối 2, quét QR bằng tài khoản được phép, ghim đúng dấu của hai tài khoản, kiểm `APP_ZALO_LIVE_2=false` và chạy bằng danh sách thử. Ghi bằng chứng riêng cho Tin 1, kết bạn và nhóm. Chưa có nghiệm thu Zalo thật cho tài khoản 2. [Runbook hai tài khoản](HUONG-DAN-NHIEU-TAI-KHOAN-ZALO.md)
- Giai đoạn 2 chưa vận hành trên production. Khi có nhóm thử và quyền TikTok Shop thử, nghiệm thu kênh nhận/gửi và báo việc Zalo, đối chiếu giá/tồn, rồi cập nhật biên bản này. [Thiết kế chọn kênh](ADR-0001-chon-kenh-bot.md) · [Runbook](RUNBOOK.md)
