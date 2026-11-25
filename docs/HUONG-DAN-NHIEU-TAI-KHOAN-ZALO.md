# Hướng dẫn chạy NHIỀU tài khoản Zalo phụ cố định (D23)

Dành cho chủ dự án và người quản trị máy chủ. Quyết định D23 (25/11/2026): cho nhiều tài khoản Zalo phụ **cố định** chạy song song để chia tải, bền hơn và nhắn được nhiều khách hơn mỗi ngày. Nghiên cứu và lý do: `docs/NGHIEN-CUU-NHIEU-ZALO-PHU.md`. Luật: `CLAUDE.md` mục 3 và `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md` mục 14.

**Trạng thái:** phần mã đã xong và **tắt mặc định**, **chưa chạy với tài khoản Zalo thứ hai thật**. Khi chưa bật, hệ thống chạy y như một tài khoản.

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
ZALO_ACCOUNT_ID_2=<mã Zalo của tài khoản thứ hai, điền sau khi đăng nhập được>
```

Rồi chạy lại các container: `./deploy.sh deploy` (hoặc `docker compose up -d` với đủ cờ `-f`). Từ bản có `deploy.sh` đọc `.env`, cả `deploy.sh` lẫn cron tự deploy dùng đúng `COMPOSE_FILE` và `COMPOSE_PROFILES` ở trên, nên các lần cập nhật sau không làm mất cấu hình nhiều tài khoản. Cron vẫn **không** kéo tệp compose, script hay `.env` lên máy chủ: chép `deploy.sh`, `backup.sh`, `restore-test.sh`, `docker-compose.shared.yml`, `docker-compose.zalo-2.yml` bản mới TRƯỚC khi sửa `.env` (làm tay, hỏi người SSH).

## 4. Cách B: tài khoản thứ hai ở MÁY CHỦ KHÁC

Máy thứ hai chỉ chạy cầu nối (`infra/docker-compose.bridge-only.yml`), **không có cơ sở dữ liệu hay dữ liệu khách**. Ứng dụng ở máy chính gọi cầu nối qua **đường hầm riêng** (WireGuard hoặc Tailscale); **không mở cổng cầu nối ra Internet** (cầu nối chỉ nói HTTP và dùng khóa chung).

1. Máy thứ hai: tạo `.env.bridge` có `ADH_TAG`, `APP_ZALO_BRIDGE_KEY`, `ZALO_SESSION_KEY`, `ZALO_ACCOUNT_ID`, `BRIDGE_BIND` (địa chỉ đường hầm của máy này); chạy `docker compose -f docker-compose.bridge-only.yml --env-file .env.bridge up -d`.
2. Máy chính, trong `infra/.env`: `COMPOSE_FILE=docker-compose.shared.yml:docker-compose.zalo-2.yml`, `COMPOSE_PROFILES=zalo-auto` (KHÔNG có `zalo-bridge-2`), `ZALO_BRIDGE_2_URL=http://<địa-chỉ-đường-hầm-máy-hai>:3100` và `APP_ZALO_BRIDGE_KEY_2=<khóa chung của cầu nối đó>`; rồi tạo lại container app.

## 5. Bật chạy thật (theo thứ tự, đừng nhảy cóc)

1. Vẫn ở **chế độ thử** (`APP_ZALO_LIVE` chưa bật): thử với các số thử.
2. Mở `/zalo`: khi có từ hai tài khoản khai báo, hiện thanh chọn tài khoản. Chọn tài khoản 2 → **Đăng nhập bằng QR** (người thật quét) → kiểm "đúng tài khoản".
3. Ở tài khoản 2: chọn nấc, đặt hạn mức (bắt đầu rất thấp), tra và xác nhận phó nhóm (mẹ).
4. Ở tab Giới hạn: đặt **trần tổng** (mặc định 50).
5. Đặt `APP_ZALO_MULTI_ENABLED=true` và tạo lại container app. Từ đó bộ hẹn giờ chạy lần lượt từng tài khoản; **khách mới** chia cho tài khoản còn nhiều chỗ trống nhất rồi gắn cố định.
6. Tạm ngưng một tài khoản thêm: nút "Tạm ngưng tài khoản này" trên `/zalo` (khách đã gắn tài khoản đó ở yên, chờ ngày bật lại).

## 6. Việc cố ý chưa làm

- Chuyển khách sang tài khoản khác khi một tài khoản bị khóa: **không làm** (cấm); khách của tài khoản bị khóa chờ tài khoản đó hoặc người xử lý.
- Báo cáo hiệu quả và màn nhắn tay vẫn nhìn chung mọi tài khoản.
- Chưa thử với Zalo thật: cần thử tay một lượt đầy đủ ở tài khoản thứ hai (Tin 1, lời mời kết bạn, thêm nhóm, tạo nhóm, trả lời) trước khi tăng hạn mức.
