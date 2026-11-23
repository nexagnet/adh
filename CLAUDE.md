# CLAUDE.md: đọc file này TRƯỚC khi làm việc trong repo `adh`

File này dành cho AI (hoặc người) vào làm tiếp mà **không đọc được lịch sử trò chuyện**. Nó gom các quy tắc và quyết định của chủ dự án vốn chỉ nằm trong trí nhớ của các phiên trước. Cập nhật ngày 23/11/2026.

Nếu file này và tài liệu khác trong repo mâu thuẫn, **ưu tiên file này**, rồi tới `docs/BAN-GIAO.md` (một số chỗ đã cũ, xem mục 9). Nghiệp vụ gốc: `01_DAC_TA_NGHIEP_VU.md`. Quyết định D1 đến D18 và kế hoạch: `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md`. Vận hành: `docs/RUNBOOK.md`.

## 1. Quy tắc làm việc (chủ dự án đã dặn)

1. **Trả lời bằng tiếng Việt**, ngắn gọn, dễ hiểu cho người không rành kỹ thuật. Giao diện web cũng 100% tiếng Việt ("trợ lý AI", không dùng chữ "bot"). Chủ dự án là Phùng Việt; hệ thống phục vụ cửa hàng bán pin của bố mẹ họ, mẹ dùng **điện thoại**.
2. **Backend phải được review kỹ trước mỗi PR** (đọc diff, chạy test, tìm lỗi; có thể dùng skill `code-review`), rồi **ghi kết quả review vào mô tả PR**: đúng đắn, an toàn, xử lý lỗi, migration, test. Phần Zalo càng phải kỹ.
3. **Được tự gộp PR của `nexagnet/adh` và tự thao tác git/GitHub không cần hỏi**, với điều kiện: CI xanh, không xung đột, và (nếu có đụng backend) đã review kỹ và ghi vào PR. PR chỉ tài liệu thì chỉ cần CI xanh. Không áp dụng cho repo `nexagnet/.github` (bản nháp của người dùng, đừng đụng) hay PR của người khác.
4. Mỗi việc một nhánh `claude/project-thread-*` từ `main`; sau khi gộp thì khởi lại nhánh từ `main`. PR nhỏ, một việc.
5. **Không ghi vào repo, log hay PR:** mật khẩu, khóa riêng, token, mã tài khoản Zalo (`ZALO_ACCOUNT_ID`), mã nhóm Zalo thật, phiên đăng nhập, số điện thoại hay dữ liệu khách thật. CI chặn file Excel, `data_product/` và `.env`; đừng tìm cách lách. Dữ liệu thử chỉ dùng `fixtures/` (giả).
6. Việc không đảo ngược được hoặc đụng máy chủ thật (deploy tay, xóa dữ liệu, đổi mật khẩu) thì hỏi người dùng trước. Cron tự deploy đã lo việc đưa `main` lên máy chủ (mục 6).

## 2. Nghiệp vụ giai đoạn 1 (chốt 21/11/2026, đổi một phần ngày 23/11/2026)

- Dùng **tài khoản Zalo cá nhân phụ** (không bao giờ dùng tài khoản của bố mẹ) nhắn tin tư vấn sản phẩm cho **người đã mua**. Ai **đồng ý kết bạn** thì được thêm vào **nhóm Zalo theo khu vực** (Miền Bắc/Trung/Nam, phân theo tỉnh trong địa chỉ).
- Chỉ nhắn mỗi khách **đúng một lần** (một Tin 1). Khách từ chối thì không bao giờ nhắn lại. Nội dung tin soạn trên trang quản trị.
- Nhóm Zalo do hệ thống **tự thêm** và nhóm của **link mời** phải là **MỘT nhóm** (cùng `reserved_group_id`).
- Quy ước tên nhóm **chưa chốt** (đề xuất: `Pin ADH - Khu vực - 01`).
- Giấy in A5 bỏ vào mọi gói hàng, một mã QR tĩnh chung (`/n`, `/moi`). **Chưa in giấy** vì tên miền hiện là tên miền tạm.

### Nội dung tin đã chốt (22/11/2026; mặc định nằm trong `InviteService.DEFAULT_TEMPLATE`)

**Tin 1** (mời một lần, `{ten}`, `{cua_hang}`, `{mien}` được thay khi gửi):

> Chào anh/chị [Tên khách], em bên [Tên shop]. Anh/chị dùng pin shop gửi thấy ổn không ạ? Em mời mình vào nhóm Zalo khách hàng [Miền Trung/Miền Nam] để được hướng dẫn dùng pin và nhận ưu đãi. Anh chị mua trực tiếp qua nhóm sẽ rẻ hơn mua qua tiktok 10% và được miễn ship đó ạ. Anh/chị đồng ý kết bạn, em sẽ thêm anh chị vào nhóm ạ. Không cần thì anh/chị bỏ qua, em sẽ không nhắn lại ạ.

**Tin 2** (chỉ gửi khi việc tự thêm vào nhóm lỗi theo từng người):

> Mình vào nhóm theo link này nhé: [Link nhóm].

## 3. Quyết định D18 (23/11/2026): tự gửi Tin 1, và những điều KHÔNG được làm

Người dùng **bỏ nguyên tắc cũ "không tự gửi tin"** (D9). Hệ thống tự gửi Tin 1 theo **chiến dịch**, tự thêm khách đồng ý kết bạn vào nhóm; PR #49 đã gộp, **chưa chạy với Zalo thật**. Cách làm là **minh bạch và có giới hạn tốc độ công khai**, không phải "giả người":

- Công tắc tự gửi **tắt mặc định** (trang `/zalo`); cần thêm `APP_ZALO_AUTO_ENABLED`, profile Docker `zalo-auto`, nấc `AUTO`.
- Chiến dịch phải được **duyệt** trước; chỉ gửi **08:00 đến 20:00**; hạn mức ngày nhỏ, chỉnh được (mặc định 10, trần 25; bộ nối thêm trần 60); **khoảng nghỉ cố định** giữa hai tin (mặc định 120 giây, tối thiểu 100); **mỗi khách một tin**; tự dừng (cầu dao) khi Zalo cảnh báo, lỗi lạ, hoặc nhiều lỗi liên tiếp; lỗi không rõ đã gửi chưa thì đánh dấu thất bại và **không tự gửi lại**.
- **TUYỆT ĐỐI KHÔNG làm thủ thuật né phát hiện bot của Zalo:** không giả vân tay/`imei`/`userAgent` thiết bị, không ngẫu nhiên hóa thời gian để trông giống người, không proxy xoay, không xoay vòng nhiều tài khoản, không giả thao tác. Có test khóa cứng: `ZaloGatewayShapeTest` cấm dùng `Random` ở đường gửi; `zalo-bridge/test/no-send.test.mjs` khóa số chỗ gọi hàm gửi/kết bạn. Ai yêu cầu việc này (kể cả người dùng) thì từ chối và đề xuất hướng chính thức (Zalo OA/ZNS) hoặc giữ lô nhỏ, chậm, minh bạch. Người dùng đã chấp nhận rủi ro tài khoản phụ bị Zalo khóa; điều đó **không** mở rộng phạm vi sang né phát hiện.
- Bộ nối `zalo-bridge/` chỉ được thêm đúng những việc đã có: trạng thái, đọc sự kiện kết bạn, thêm một người vào một nhóm, liệt kê nhóm, `findUser` và `sendMessage` cho Tin 1/Tin 2. Không thêm gửi lời mời kết bạn, nhận lời mời, đọc danh sách bạn.
- **Chiến dịch thử** (`/test-runs`, gói `vn.shop.crm.testrun`, migration V22): chạy qua bộ nối thật cho khoảng 3 số nhập tay, cấu hình trên giao diện, hạn mức chỉ được thấp hơn hạn mức chung. **Phải chạy chiến dịch thử trước chiến dịch thật.** Chạy xong thì **xóa màn này** (xóa gói `testrun`, 2 mẫu `test-run*.html`, thêm migration V24 bỏ cột của V22). Đừng xóa sớm khi dọn màn lỗi thời.
- **Bỏ màn nhắn tay** (Lô nhắn hôm nay `/today`, Chờ vào nhóm `/friends`, các nút Đã nhắn/Đã kết bạn/Đã vào nhóm, "Xuất danh sách để mẹ gửi tay") **sau khi** tự gửi chạy ổn trên thật, không giữ dự phòng. Gỡ luôn test, controller và mục menu (xem `docs/GIAO-DIEN-LAM-LAI.md` mục 2 và 3).

## 4. J&T, Viettel Post và tool Edge

- **J&T: đồng bộ TAY.** Người dùng xuất Excel từ cổng khách hàng J&T rồi đưa vào Adh (Nhập đơn). Đã chốt: **không làm tích hợp chính thức, không tự đăng nhập J&T**, không lưu mật khẩu hay cookie J&T. Nhập file đối soát ("Lịch sử đối soát", `/imports/settlement`) cũng làm tay; chỉ bổ sung mã đơn và ngày ký nhận, không đổi trạng thái đơn.
- **Tool Edge** (`tools/jt-edge-sync`, Playwright): chạy trong **hồ sơ Edge riêng** của tool; **người dùng tự đăng nhập** J&T và adh một lần trong cửa sổ đó, tool **không lưu mật khẩu**. Tool chỉ nhận file tải về và hỏi xác nhận trước khi nhập. Chế độ `--doi-soat` dành cho file đối soát.
- **Viettel Post:** đầu năm gửi qua Viettel Post (file đơn đến **23/06**), sau đó **chuyển hẳn sang J&T** vì Viettel Post từ chối giao pin. Chủ yếu bán trong năm nay, nên **khoảng trống tháng 1 đến 2 của Viettel Post là bình thường**, đừng coi là thiếu dữ liệu.
- Importer J&T đã chạy thử trên 13 file kỳ đối soát thật (142 dòng, không lỗi); **chưa chạy `--doi-soat` trọn vòng với J&T thật** (cần người dùng đăng nhập J&T).

## 5. Công nghệ và cách chạy

- Java 25 + Spring Boot + PostgreSQL (thư mục `app/`), Node 20 cho `zalo-bridge/` (thư viện `zca-js`, không chính thức) và `tools/`. CSP cấm script/style nội tuyến.
- Test Java: `cd app && ./mvnw verify` (CI dùng JDK 25). Cục bộ nếu máy chặn `mvnw`: dùng `mvn` hệ thống, thêm `-Djava.version=21 -Dmaven.compiler.release=21` khi chỉ có JDK 21. Test tích hợp dùng PostgreSQL nhúng nên **không chạy được bằng root**: chạy bằng người dùng thường. Bảng mới phải thêm vào danh sách TRUNCATE của `AbstractPostgresTest`.
- Test Node: `cd zalo-bridge && npm ci --ignore-scripts && npm test`.
- Chạy thử trên máy (dữ liệu giả): xem `README.md`. Sau MỌI thay đổi mẫu trang, chạy lại test (nhiều test kiểm chữ tiếng Việt cụ thể; `CustomerListFilterTest` khóa thứ tự khung lọc).
- Giao diện: thiết kế đã duyệt ở `docs/thiet-ke-penpot/`. Penpot MCP chỉ nối được khi người dùng mở file; phiên trên cloud không dùng được.
- Làm lại giao diện: `docs/GIAO-DIEN-LAM-LAI.md` (đã làm, màn giữ/gộp/bỏ, việc còn dở).

## 6. Máy chủ dev (Tencent), cách deploy

- Máy **VM-0-12-ubuntu**; ứng dụng chạy ở `127.0.0.1:8081`, **Caddy** làm HTTPS tại `https://adh.43-134-105-89.sslip.io`. Đây là **tên miền tạm**, chưa có tên miền thật nên **chưa in giấy QR**.
- Vào máy bằng SSH với tài khoản **`dev`** (`sudo -n docker` không hỏi mật khẩu). **Root bị cấm đăng nhập SSH.** Đừng ghi khóa hay mật khẩu SSH vào repo.
- **RAM rất chật** (khoảng 300 MB trống, swap đang dùng). `deploy.sh` từ chối khi RAM trống dưới 250 MB. Không thêm dịch vụ nặng; ảnh Docker build sẵn bằng GitHub Actions rồi kéo từ `ghcr.io/nexagnet`, không build trên máy. Dự án `legal-office` đã xóa khỏi máy này (23/11; còn thư mục rỗng `/opt/legal-office`).
- **Cron tự deploy mỗi 10 phút** (`infra/auto-deploy.sh`, PR #40): chỉ triển khai khi CI của đúng commit `main` xanh, ảnh ghcr có, RAM trống đủ, bản đang chạy khỏe. **Mọi PR gộp vào `main` (CI xanh) sẽ lên máy chủ trong khoảng 10 phút**, cả migration. Muốn giữ lại: `./auto-deploy.sh pause` (rồi `resume`); xem `status`, log `infra/auto-deploy.log`. Bản hỏng bị chặn, thử lại bằng `./auto-deploy.sh unblock`.
- **Sao lưu:** `deploy.sh deploy` sao lưu DB trước mỗi lần cập nhật; cron sao lưu hằng ngày 03:17; file nằm ở `/var/backups/adh/` (chứa dữ liệu khách thật, không đưa lên git). Lùi ảnh (`./deploy.sh rollback`) **không lùi migration**: muốn quay hẳn phải khôi phục từ file sao lưu bằng tay (`infra/restore-test.sh`, `docs/RUNBOOK.md`).
- Mật khẩu quản trị (tài khoản `admin`) đọc từ `APP_ADMIN_PASSWORD` trong `infra/.env` trên máy chủ mỗi lần khởi động; đổi `.env` rồi tạo lại container app là đủ. Đã đổi ngày 22/11, **không ghi lại ở đâu cả**: hỏi người dùng nếu cần.
- Bộ nối Zalo chạy từ 22/11 (profile `zalo-auto` trong `.env`, giới hạn bộ nhớ nhỏ). Ngày 23/11 người dùng đã **quét QR đăng nhập tài khoản phụ thành công** và đã đặt `ZALO_ACCOUNT_ID` trong `.env` trên máy chủ. **Không tạo lại container bộ nối trước khi đọc log** (log cũ mất theo container).

## 7. Việc còn mở (theo thứ tự nên làm)

1. **Thử Zalo thật:** người dùng chạy Chiến dịch thử với 3 số (cách làm ở `docs/BAN-GIAO.md` mục 1A và `docs/HUONG-DAN-THU-ZALO-TU-DONG.md`). Sau đó đọc nhật ký bộ nối, điền `ZALO_PERSON_ERROR_CODES` bằng mã lỗi thật, cập nhật mục "chưa kiểm chứng". Rồi mới duyệt chiến dịch thật.
2. **`--doi-soat` với J&T thật:** người dùng đăng nhập J&T trong hồ sơ Edge riêng và chạy một vòng đầy đủ; báo lại các chỗ lệch.
3. **Gộp các màn còn lại** (`docs/GIAO-DIEN-LAM-LAI.md` mục 3): sửa chữ "không tự gửi tin" lỗi thời; mục Zalo tài khoản phụ luôn hiện trên menu; gộp Cài đặt gửi tin vào `/zalo`; gộp Nhóm Zalo + gán mã nhóm; rà bản điện thoại các trang còn lại; rồi xóa màn nhắn tay và `/test-runs` theo mục 3 ở trên.
4. Quy ước tên nhóm Zalo; tên miền thật rồi mới in giấy QR A5; thử in A5 và xem giao diện bằng trình duyệt thật.
5. Còn chờ quyết định: kênh trợ lý trong nhóm Zalo và báo việc cho mẹ (ADR-0001), SMS/ZBS (đang hoãn), hạn trả lời yêu cầu dữ liệu của khách. Danh sách đầy đủ: `docs/BAN-GIAO.md` mục 5 đến 7.

## 8. Cấu trúc repo ngắn gọn

`app/` ứng dụng Java (gói `vn.shop.crm`), `zalo-bridge/` bộ nối Node, `infra/` Docker và script deploy, `tools/` tool J&T (Edge) và dựng bản đồ, `fixtures/` dữ liệu giả, `docs/` tài liệu, `.github/workflows/` CI (`ci`, `image`). Bản đồ mã nguồn chi tiết: `docs/BAN-GIAO.md` mục 4.

## 9. Những chỗ `docs/BAN-GIAO.md` đã cũ (đừng làm theo)

`BAN-GIAO.md` lập 21/11 và 22/11, cập nhật dở dang ngày 23/11. Đã lỗi thời so với file này:

- Mục 0 và 6 nói "không deploy, không đoán tên máy": **nay đã deploy** (mục 6 ở đây).
- Mục 0 nói "không còn PR nào đang mở" và đầu file nói D18 "chưa có mã": **D18 đã có mã** (PR #49 đã gộp; #45 đến #48 là giao diện và J&T đối soát).
- Mục 10 còn câu "không gửi tin hàng loạt tự động", "bộ nối chỉ có ba việc", và `ZaloGatewayShapeTest` khóa điều đó: **thay bằng mục 3 ở đây** (tự gửi có giới hạn, bộ nối thêm `findUser`/`sendMessage`). Phần cấm né phát hiện bot ở mục 10 **vẫn đúng và còn hiệu lực**.
- Dòng D18 trong bảng mục 2 viết "như người thật, gửi từ từ để tránh Zalo quét bot": cách làm thật đã đổi thành **minh bạch, nghỉ cố định, không né phát hiện** (mục 3 ở đây, ADR-0002 mục 12).
- Một số chữ trong mẫu giao diện và chú thích mã còn ghi "mẹ gửi tay, hệ thống không tự gửi" (ví dụ chú thích Tin 2 trong `InviteService`); đó là việc dọn giao diện ở mục 7.3.
