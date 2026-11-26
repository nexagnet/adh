# Hướng dẫn chạy NHIỀU tài khoản Zalo phụ cố định (D23)

Dành cho chủ dự án và người quản trị máy chủ. Quyết định D23 (25/11/2026): cho nhiều tài khoản Zalo phụ **cố định** chạy song song để chia tải, bền hơn và nhắn được nhiều khách hơn mỗi ngày. Nghiên cứu và lý do: `docs/NGHIEN-CUU-NHIEU-ZALO-PHU.md`. Luật: `CLAUDE.md` mục 3 và `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md` mục 14.

**Trạng thái:** nhiều tài khoản **tắt mặc định** và chưa được kiểm chứng với tài khoản Zalo thứ hai thật. Tài khoản thêm phải được xác nhận riêng trước khi được xếp lịch.

## 1. Luật (không được lách)

- Mỗi tài khoản là **SIM chính chủ thật**, người thật quét QR; tên và ảnh đại diện nói rõ là của shop.
- Mỗi khách gắn **cố định một tài khoản** lúc nhắn Tin 1; không bao giờ chuyển hay nhắn bởi tài khoản khác.
- Mỗi tài khoản giữ hạn mức khách mỗi ngày, khoảng nghỉ cố định, khung giờ 08:00 đến 22:00 và cầu dao **như tài khoản duy nhất hôm nay** (không nới). Có thêm **trần tổng** của mọi tài khoản cộng lại (mặc định 50, tối đa 200, chỉnh ở `/zalo` tab Giới hạn).
- Zalo báo hạn chế, lỗi lạ hoặc không rõ kết quả ở **một** tài khoản thì **tất cả** dừng chờ người xem.
- **Vẫn CẤM và có test khóa:** luân phiên hay xoay vòng tài khoản để né, tự thay tài khoản khi bị khóa, đổi IP/proxy/dấu vân tay/`imei`/`userAgent` hay làm bất cứ gì để Zalo không liên kết các tài khoản. Tách máy chủ **chỉ vì đủ RAM**.
- Tài khoản mới thêm bắt đầu rất thấp (3 khách mỗi ngày, 3 lời mời kết bạn): nâng dần ở `/zalo` khi thấy ổn.

## 2. Chuẩn bị mỗi tài khoản mới (việc của người, không làm bằng mã)

1. SIM chính chủ, điện thoại đăng nhập Zalo; **đổi tên và ảnh đại diện** nói rõ là của shop.
2. **Mẹ (Zalo thật) kết bạn với tài khoản mới.** Mã Zalo của mẹ khác nhau theo từng tài khoản hỏi, nên mỗi tài khoản phải tự tra và xác nhận mẹ bằng số điện thoại ở `/zalo` (mục Phó nhóm), rồi mẹ sẽ là phó nhóm ở nhóm của mọi tài khoản.
3. Tạo khóa riêng: `openssl rand -hex 24` cho khóa chung cầu nối, `openssl rand -hex 32` cho khóa mã hóa phiên.

## 3. Cách A: tài khoản thứ hai chạy CÙNG máy chủ (nhanh nhất, máy 6 GB đủ)

Mỗi cầu nối Node chiếm khoảng 100 MB. Trong `infra/.env` trên máy chủ:

```
COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml
COMPOSE_PROFILES=zalo-auto,zalo-bridge-2
APP_ZALO_BRIDGE_KEY_2=<khóa chung của cầu nối thứ hai>
ZALO_SESSION_KEY_2=<khóa mã hóa phiên, 64 ký tự hex>
ZALO_ACCOUNT_ID_2=0
APP_ZALO_LIVE_2=false
```

Rồi chạy lại các container: `./deploy.sh deploy` (hoặc `docker compose up -d` với đủ cờ `-f`). Từ bản có `deploy.sh` đọc `.env`, cả `deploy.sh` lẫn cron tự deploy dùng đúng `COMPOSE_FILE` và `COMPOSE_PROFILES` ở trên, nên các lần cập nhật sau không làm mất cấu hình nhiều tài khoản. Cron vẫn **không** kéo tệp compose, script hay `.env` lên máy chủ: chép `deploy.sh`, `backup.sh`, `restore-test.sh`, `docker-compose.shared.yml`, `docker-compose.zalo-2.yml` bản mới TRƯỚC khi sửa `.env` (làm tay, hỏi người SSH).

## 4. Cách B: tài khoản thứ hai ở MÁY CHỦ KHÁC

Máy thứ hai chỉ chạy cầu nối (`infra/docker-compose.bridge-only.yml`). Volume của cầu nối giữ phiên và hàng tin riêng đã mã hóa, cùng sổ hạn mức. PostgreSQL và ứng dụng vẫn ở máy chính. Ứng dụng gọi cầu nối qua **đường hầm riêng** (WireGuard hoặc Tailscale), giữ cổng 3100 trong mạng riêng vì cầu nối dùng HTTP với khóa chung.

1. Máy thứ hai: tạo `.env.bridge` có `ADH_TAG` trùng thẻ máy chính, `APP_ZALO_BRIDGE_KEY`, `ZALO_SESSION_KEY`, `BRIDGE_BIND` là địa chỉ đường hầm của máy này và `ZALO_ACCOUNT_ID=0` nếu chưa biết mã. Chạy `docker compose -f docker-compose.bridge-only.yml --env-file .env.bridge up -d`. Đặt `BRIDGE_INBOX_ENABLED=true` ở đây nếu cần đọc tin riêng qua cầu nối 2.
2. Máy chính, trong `infra/.env`: đặt `COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml`, `COMPOSE_PROFILES=zalo-auto`, `ZALO_BRIDGE_2_URL=http://<địa-chỉ-đường-hầm-máy-hai>:3100`, `APP_ZALO_BRIDGE_KEY_2=<khóa chung của cầu nối đó>` và `APP_ZALO_LIVE_2=false`. Tạo lại container app. Máy hai tiếp tục dùng Compose riêng.
3. Khi cập nhật bản chạy, đối chiếu `ADH_TAG` trên hai máy. Trình tự triển khai trên máy chính chỉ cập nhật các dịch vụ của máy chính. Chỉnh `ADH_TAG` trong `.env.bridge` trên máy hai, kéo ảnh với `docker compose -f docker-compose.bridge-only.yml --env-file .env.bridge pull`, rồi tạo lại cầu nối bằng `up -d`. Giữ volume và khóa phiên hiện có.

## 5. Bật chạy thật (theo thứ tự, đừng nhảy cóc)

1. Giữ `APP_ZALO_LIVE_2=false`. Tài khoản 2 chỉ được liên hệ số trong danh sách thử, kể cả khi tài khoản 1 đang chạy thật.
2. Mở `/zalo`, chọn tài khoản 2, vào tab **Cấu hình** rồi chọn **Đăng nhập bằng QR**. Nếu đang đặt mã `0`, lần quét đầu sẽ hiện mã để đối chiếu. Sau khi xác nhận đúng, ghi mã đó vào `ZALO_ACCOUNT_ID` trong `.env.bridge` trên máy hai (hoặc `ZALO_ACCOUNT_ID_2` trong `.env` nếu chạy cùng máy), tạo lại cầu nối 2 và quét lại.
3. Khi hai cầu nối đã đăng nhập đúng, chọn **Xác minh và bật tài khoản này** ở tài khoản 2. Ứng dụng khóa dấu của hai tài khoản Zalo, từ chối cầu nối trùng hoặc khác với dấu đã được xác nhận. Bộ hẹn giờ vẫn chờ `APP_ZALO_MULTI_ENABLED=true`.
4. Ở tài khoản 2, chọn nấc, đặt hạn mức thấp, tra và xác nhận phó nhóm. Ở tab Giới hạn, đặt **trần tổng** (mặc định 50).
5. Đặt `APP_ZALO_MULTI_ENABLED=true` và tạo lại container app để chạy thử tài khoản 2. Khi cầu nối thử sẵn sàng và còn hạn mức, hệ thống ưu tiên giao số thử chưa được gắn cho cầu nối này. Khách thường tiếp tục được phân cho tài khoản đang chạy thật.
6. Chỉ sau khi thử đạt, đặt `APP_ZALO_LIVE_2=true` và tạo lại container app. Chế độ chạy thật của tài khoản 2 yêu cầu đồng thời `APP_ZALO_LIVE=true`.
7. Tạm ngưng tài khoản thêm bằng nút **Tạm ngưng tài khoản này**. Khách đã gắn tài khoản đó chờ ngày bật lại.

Khi cầu dao chung dừng các tài khoản, hãy xem cảnh báo và chọn **Mở lại** ở tài khoản đã báo lỗi. Nếu bridge nguồn đang ngoại tuyến hoặc đã gỡ khỏi cấu hình, khôi phục kết nối hoặc tuyến tới đúng bridge đó trước khi xác nhận. Các tài khoản tiếp tục dừng trong lúc chưa thể xác nhận trên bridge nguồn.

Trong lần cập nhật sang bản có `APP_ZALO_LIVE_2`, giữ `APP_ZALO_MULTI_ENABLED=false` trong `infra/.env` đến khi đã xác nhận bản mới hoạt động. Tạm dừng cron bằng `./auto-deploy.sh pause` nếu thẻ trước còn là bản cũ. Nếu cần `./deploy.sh rollback` về bản cũ, tắt `APP_ZALO_MULTI_ENABLED` trong `.env` trước khi chạy. Cả lùi tự động lẫn lùi tay dùng lại `.env`, còn bản cũ chỉ đọc `APP_ZALO_LIVE` chung. Chỉ bật lại cron sau khi hai thẻ dùng để triển khai và lùi đều hiểu cấu hình chạy thử riêng.

## 6. Việc cố ý chưa làm

- Chuyển khách sang tài khoản khác khi một tài khoản bị khóa: **không làm** (cấm); khách của tài khoản bị khóa chờ tài khoản đó hoặc người xử lý.
- Báo cáo hiệu quả và màn nhắn tay vẫn nhìn chung mọi tài khoản.
- Chưa thử với Zalo thật: cần thử tay một lượt đầy đủ ở tài khoản thứ hai (Tin 1, lời mời kết bạn, thêm nhóm, tạo nhóm, trả lời) trước khi tăng hạn mức.
