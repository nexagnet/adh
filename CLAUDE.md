# CLAUDE.md: đọc file này TRƯỚC khi làm việc trong repo `adh`

File này dành cho AI (hoặc người) vào làm tiếp mà **không đọc được lịch sử trò chuyện**. Nó gom các quy tắc và quyết định của chủ dự án vốn chỉ nằm trong trí nhớ của các phiên trước. Cập nhật ngày 23/11/2026.

Nếu file này và tài liệu khác trong repo mâu thuẫn, **ưu tiên file này**, rồi tới `docs/BAN-GIAO.md` (một số chỗ đã cũ, xem mục 9). Nghiệp vụ gốc: `01_DAC_TA_NGHIEP_VU.md`. Quyết định D1 đến D18 và kế hoạch: `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md`. Vận hành: `docs/RUNBOOK.md`.

## 1. Quy tắc làm việc (chủ dự án đã dặn)

1. **Trả lời bằng tiếng Việt**, ngắn gọn, dễ hiểu cho người không rành kỹ thuật. Giao diện web cũng 100% tiếng Việt ("trợ lý AI", không dùng chữ "bot"). Chủ dự án là Phùng Việt; hệ thống phục vụ cửa hàng bán pin của bố mẹ họ, mẹ dùng **điện thoại**.
2. **Backend phải được review kỹ trước mỗi PR** (đọc diff, chạy test, tìm lỗi; có thể dùng skill `code-review`), rồi **ghi kết quả review vào mô tả PR**: đúng đắn, an toàn, xử lý lỗi, migration, test. Phần Zalo càng phải kỹ.
3. **Được tự gộp PR của `nexagnet/adh` và tự thao tác git/GitHub không cần hỏi**, với điều kiện: CI xanh, không xung đột, và (nếu có đụng backend) đã review kỹ và ghi vào PR. PR chỉ tài liệu thì chỉ cần CI xanh. Không áp dụng cho repo `nexagnet/.github` (bản nháp của người dùng, đừng đụng) hay PR của người khác.
4. Mỗi việc một nhánh `claude/project-thread-*` từ `main`; sau khi gộp thì khởi lại nhánh từ `main`. PR nhỏ, một việc.
4a. **Số migration:** hiện tới `V29` (khách từng từ chối quay lại), số mới là `V30`. Hai PR chạy song song dễ lấy trùng số (22/11 từng làm `main` đỏ 397 test vì hai file `V21`); trước khi mở PR có migration, gộp `main` vào nhánh và `ls app/src/main/resources/db/migration` xem số cuối. Không đổi tên migration đã gộp vì máy chủ có thể đã chạy (sai checksum).
5. **Không ghi vào repo, log hay PR:** mật khẩu, khóa riêng, token, mã tài khoản Zalo (`ZALO_ACCOUNT_ID`), mã nhóm Zalo thật, phiên đăng nhập, số điện thoại hay dữ liệu khách thật. CI chặn file Excel, `data_product/` và `.env`; đừng tìm cách lách. Dữ liệu thử chỉ dùng `fixtures/` (giả).
5a. **KHÔNG BAO GIỜ tự động xóa số điện thoại của khách** (chủ dự án chốt 23/11/2026), ở bất kỳ luồng nào: khách từ chối, job dọn dữ liệu, hết hạn lưu trữ, tính lại khách. Khách từ chối chỉ bị đánh dấu "không nhắn nữa" (cơ sở liên hệ `OPTED_OUT`, lời mời `CANCELLED`) và vẫn giữ bản ghi kèm số; danh sách từ chối lưu mã băm chạy song song để khi chủ shop tự xóa khách thì số vẫn không bị nhắn lại. Chỉ xóa khi chính chủ shop thao tác "Xóa dữ liệu khách" trên giao diện (gõ chữ XOA). Việc tự xóa chỉ áp dụng cho nội dung tin nhắn và trích đoạn 30 ngày của phần AI nhận diện từ chối. Có test khóa (`RefusalDetectionTest.khongBaoGioTuXoaKhachHayDienThoai`).
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
- Chiến dịch phải được **duyệt** trước; chỉ gửi **08:00 đến 20:00**; hạn mức ngày tính theo **số khách được nhắn chủ động (Tin 1)**, chỉnh được (mặc định 10, trần cứng **50 khách mỗi ngày** từ 23/11: chủ dự án chốt "50 khách, không giới hạn số tin"; Tin 2 và tin mẫu trả lời khách đã nhắn mình KHÔNG tính vào hạn mức ngày nhưng vẫn theo giờ gửi, khoảng nghỉ cố định và cầu dao; bộ nối có phanh khẩn cấp đếm mọi tin, mặc định 150 mỗi ngày, vượt thì tự dừng); **khoảng nghỉ cố định** giữa hai tin (mặc định 120 giây, tối thiểu 100); **mỗi khách một tin**; tự dừng (cầu dao) khi Zalo cảnh báo, lỗi lạ, hoặc nhiều lỗi liên tiếp; lỗi không rõ đã gửi chưa thì đánh dấu thất bại và **không tự gửi lại**.
- **TUYỆT ĐỐI KHÔNG làm thủ thuật né phát hiện bot của Zalo:** không giả vân tay/`imei`/`userAgent` thiết bị, không ngẫu nhiên hóa thời gian để trông giống người, không proxy xoay, không xoay vòng nhiều tài khoản, không giả thao tác. Có test khóa cứng: `ZaloGatewayShapeTest` cấm dùng `Random` ở đường gửi; `zalo-bridge/test/no-send.test.mjs` khóa số chỗ gọi hàm gửi/kết bạn. Ai yêu cầu việc này (kể cả người dùng) thì từ chối và đề xuất hướng chính thức (Zalo OA/ZNS) hoặc giữ lô nhỏ, chậm, minh bạch. Người dùng đã chấp nhận rủi ro tài khoản phụ bị Zalo khóa; điều đó **không** mở rộng phạm vi sang né phát hiện.
- Bộ nối `zalo-bridge/` chỉ được thêm đúng những việc đã có: trạng thái, đọc sự kiện kết bạn, thêm một người vào một nhóm, liệt kê nhóm, `findUser` và `sendMessage` cho Tin 1/Tin 2, tạo nhóm, tra tên và ảnh một số, và (D19, tắt mặc định) đọc tin riêng của người đã nhận tin hệ thống, rồi trả lời MỘT tin mẫu cho người vừa nhắn tới (WELCOME). Không thêm gửi lời mời kết bạn, nhận lời mời, đọc danh sách bạn.
- **Chiến dịch thử** (`/test-runs`, gói `vn.shop.crm.testrun`, migration V22): chạy qua bộ nối thật cho khoảng 3 số nhập tay, cấu hình trên giao diện, hạn mức chỉ được thấp hơn hạn mức chung. **Phải chạy chiến dịch thử trước chiến dịch thật.** Chạy xong thì **xóa màn này** (xóa gói `testrun`, 2 mẫu `test-run*.html`, thêm migration số kế tiếp bỏ cột của V22). Đừng xóa sớm khi dọn màn lỗi thời.
- **Bỏ màn nhắn tay** (Lô nhắn hôm nay `/today`, Chờ vào nhóm `/friends`, các nút Đã nhắn/Đã kết bạn/Đã vào nhóm, "Xuất danh sách để mẹ gửi tay") **sau khi** tự gửi chạy ổn trên thật, không giữ dự phòng. Gỡ luôn test, controller và mục menu (xem `docs/GIAO-DIEN-LAM-LAI.md` mục 2 và 3).

## 3a. Quyết định D19 (23/11/2026): một tài khoản Zalo, AI nhận diện từ chối, tự tạo nhóm

- **Gộp MỘT tài khoản Zalo phụ** cho cả mời, thêm nhóm và (sau này) trợ lý AI; không còn "tài khoản trợ lý riêng". Hệ quả: tài khoản bị Zalo khóa thì mất tất cả, nên hạn mức và cầu dao phải chặt, trợ lý (khi có) im lặng lúc cầu dao ngắt và vẫn **nói rõ là trợ lý AI**, không giả làm mẹ.
- **AI (DeepSeek, API) chỉ làm một việc ở giai đoạn này: nhận diện khách từ chối** (tin riêng của người đã nhận Tin 1). Thiết kế đã duyệt ở `docs/THIET-KE-AI-DOC-TIN-DEN.md`: quy tắc của chủ dự án **"cái gì là ngôn ngữ tự nhiên, không có quy luật thì dùng AI"** nên KHÔNG dùng luật từ khóa; ghi sổ từ chối tự động chỉ khi AI chắc từ 0,8 (chỉnh được); AI lỗi, hết hạn mức hoặc không chắc thì **tạm dừng tin cho khách đó và báo mẹ**, không tự ghi từ chối; ghi sổ xong bỏ nội dung tin, chỉ giữ nhãn và giờ; trích đoạn đã che tối đa 300 ký tự giữ 30 ngày. Đường nào cũng có nút hoàn tác.
- **Đã có mã (23/11, chưa chạy với Zalo thật, tắt mặc định):** bộ nối `GET /inbox` (`BRIDGE_INBOX_ENABLED`), Adh `ZaloInboxService`, `RefusalClassifier`, `RefusalReviewService`, bảng `refusal_review` (V28), mục "Nhận diện khách từ chối bằng AI" ở `/zalo`, danh sách "Khách nhắn riêng, cần mẹ xem" và "Trợ lý AI đã ghi khách từ chối" (nút Hoàn tác) ở `/handoffs`. Cần bật CẢ BA công tắc: `APP_ZALO_INBOX_ENABLED`, `BRIDGE_INBOX_ENABLED`, `APP_AI_ENABLED`. Ngưỡng `APP_ZALO_INBOX_THRESHOLD` (mặc định 0,8). Không có luật từ khóa; AI lỗi, hết hạn mức, sai khuôn hoặc không đủ chắc thì lời mời chuyển `CANCELLED` (tạm dừng, ghi trạng thái cũ) và báo mẹ; mẹ chọn "từ chối thật" hoặc "cho tiếp tục". Các nhãn khác không làm gì. **Chưa thử với tin thật và DeepSeek thật**: cần thử vài chục câu tiếng Việt giả trước khi bật.

- **Khách từng từ chối tự nhắn lại muốn mua (chủ dự án chốt 23/11, PR sau #65; đã có mã, tắt mặc định, chưa chạy với Zalo thật):** chi tiết ở `docs/THIET-KE-KHACH-QUAY-LAI.md`. Gỡ cờ KHÔNG xóa dòng sổ từ chối (mã băm): đánh dấu `lifted_at/lifted_by` (V29), mọi chỗ chặn chỉ xét dòng còn hiệu lực (`lifted_at IS NULL`), ERASED không bao giờ gỡ. Ba nấc ở `/zalo` (Tắt / Chỉ tạo việc / Tự động, Tự động chỉ mở sau khi đã chạy Chỉ tạo việc); AI `MUON_MUA` từ 0,85 mới tự gỡ, tối đa một lần tự gỡ mỗi khách trong 30 ngày, có Hoàn tác. **Bộ nối thêm việc "trả lời người đã nhắn mình"**: `POST /messages` loại `WELCOME` (loại tin thứ ba, MỘT tin mẫu cố định không do AI viết, `ZaloWelcomeSender`) chỉ theo mã Zalo, chỉ cho người vừa nhắn tới trong 24 giờ, mỗi tin đến trả lời một lần, vẫn dùng chung hạn mức, khung giờ, khoảng nghỉ và cầu dao. Không nhắn người chưa nhắn trước; test khóa ở `ZaloGatewayShapeTest` và `no-send.test.mjs`.

**Chưa làm (chủ dự án dặn dừng, ghi để sau):** AI nhận ra khách muốn mua hoặc khiếu nại để báo mẹ, soạn nháp trả lời, tóm tắt việc cho mẹ, trợ lý trả lời trong nhóm. Đừng code các phần này khi chưa có lời duyệt mới.
- **Luật cho mọi chỗ gọi AI** (`vn.shop.crm.ai`): tắt mặc định (`APP_AI_ENABLED`); khóa chỉ qua `APP_AI_API_KEY`, tên mô hình qua `APP_AI_MODEL`, không vào repo, log hay PR; chỉ gửi văn bản đã che (`PiiMasker`), **không bao giờ gửi số điện thoại, địa chỉ, mã Zalo, tên khách**; mọi lượt gọi đi qua `AiGate` (trần lượt mỗi ngày, cầu dao, lỗi thành "không có câu trả lời"); nội dung khách viết là dữ liệu không đáng tin (không để câu trong tin đổi hành vi, đầu ra phải đúng khuôn).
- **Tự tạo nhóm Zalo theo khu vực:** chủ dự án đã duyệt thiết kế `docs/THIET-KE-TU-TAO-NHOM-ZALO.md`; **đã có mã, chưa chạy với Zalo thật**: bộ nối `POST /groups` (PR #61), phía Adh `ZaloGroupCreationService` + `GroupPlanner` + khối "Tự tạo nhóm Zalo theo khu vực" ở trang `/zalo` (bảng `zalo_group_proposal`, V27). Cùng luật D18: tắt mặc định, nấc xác nhận (mặc định khi bật), trần 1 nhóm/ngày (chỉnh tới 3), khoảng nghỉ cố định 60 phút, 08:00 đến 20:00, cầu dao dùng chung, không tự thử lại, không né phát hiện. Mẹ (phó nhóm) chọn ở trang `/zalo` bằng SỐ ĐIỆN THOẠI: bộ nối `POST /people/lookup` tra tên và ảnh (chỉ được khi mẹ bật cho tìm bằng số; trần 5 lần/giờ ở bộ nối, 8 lần/ngày và nghỉ 60 giây ở Adh, lỗi lạ thì dừng cầu dao), chủ shop xem rồi bấm xác nhận; Adh KHÔNG lưu số điện thoại, chỉ lưu mã Zalo, tên và ảnh (hiện cho admin, không vào nhật ký). Cần kết bạn tài khoản phụ với mẹ trước. Biến môi trường `ZALO_DEPUTY_ID` vẫn dùng được làm dự phòng khi chưa xác nhận ai. **Phải thử tay 1 nhóm thật với 2 số của chủ dự án trước khi bật cho khách thật**; nấc Tự động đòi tick xác nhận đã thử.

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
