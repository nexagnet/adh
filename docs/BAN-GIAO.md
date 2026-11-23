# Bàn giao dự án Adh (lập 21/11/2026, cập nhật lần cuối 22/11/2026 sau PR #38; riêng quyết định D18 ngày 23/11/2026 chỉ ghi tài liệu, xem mục 2)

Tài liệu này gom mọi thứ phiên làm việc trước biết, để phiên sau (người hoặc Claude) làm tiếp mà không phải hỏi lại. Đọc kèm:
`01_DAC_TA_NGHIEP_VU.md` (nghiệp vụ, nguồn sự thật), `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md` (quyết định D1–D18, kế hoạch),
`docs/RUNBOOK.md` (vận hành), `docs/thiet-ke-penpot/` (thiết kế giao diện đã duyệt).

**Người dùng (chủ dự án): Phùng Việt. Luôn trả lời bằng tiếng Việt, ngắn gọn, dễ hiểu cho người không rành kỹ thuật.**
Hệ thống phục vụ cửa hàng của bố mẹ người dùng: chăm sóc khách cũ qua nhóm Zalo.

Tài liệu này chỉ ghi điều đã xác minh trong repo hoặc do người dùng nói ra; chỗ nào chưa thử thật đều ghi rõ "chưa kiểm chứng" (mục 5). **Không có dữ liệu khách thật, số điện thoại, mã Zalo, phiên đăng nhập Zalo, khóa hay mật khẩu nào trong repo** (CI chặn file Excel, `data_product/` và `.env`); đừng đưa vào.

## 0. Bắt đầu nhanh cho phiên mới

1. Đọc theo thứ tự: mục 1 (trạng thái) và 2 (quyết định) của tài liệu này, mục 7 (việc tiếp theo), rồi `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md` mục 9 đến 11 nếu đụng đến Zalo.
2. Lấy mã nguồn: nhánh `main` của `nexagnet/adh` (đã gộp hết, **không còn PR nào đang mở ở thời điểm bàn giao**). Việc mới: mở nhánh từ `main`, mở PR, gộp khi CI xanh (người dùng đã cho phép, xem mục 1 phần "PR và nhánh").
3. Chạy thử và kiểm thử: mục 4. Cần JDK 25 cho phần Java; phần Node (`zalo-bridge/`) chạy `npm ci --ignore-scripts && npm test` (Node 20 trở lên).
4. Trước khi làm tiếp phần Zalo, đọc kỹ mục 10 (quy tắc): **không né phát hiện của Zalo, không gửi tin hàng loạt, không dùng tài khoản của mẹ**, và người dùng muốn **review kỹ từng PR phần Zalo** trước khi gộp.
5. **Không deploy và không động vào máy chủ** cho đến khi người dùng nói (mục 6, câu về máy chủ).

---

## 1. Trạng thái tổng quát

Mã nguồn trên nhánh `main` của `nexagnet/adh`, đã gộp các PR #1 đến #38 (trừ #25 đã đóng vì lỗi thời). Khoảng 420 test Java (đếm `@Test`, chưa tính test tham số hóa) và 54 test Node (chạy lại ngày 22/11/2026 trong phiên bàn giao, đạt cả 54; Java không chạy lại được trong phiên đó vì thiếu JDK 25). CI trên `main` ở commit gộp #38 đạt: `ci` (job `test`, `bridge`, `lint`) và `image` (dựng ảnh ứng dụng và ảnh cầu nối Zalo). Số chính xác xem kết quả CI, vì số test tăng theo từng PR.

| Mốc | Nội dung | Trạng thái |
|---|---|---|
| 1A | Nhập file Viettel Post/J&T, chuẩn hóa SĐT, phân loại `ONE_TIME`/`REPEAT` | Xong, có test. Viettel Post kiểm chứng với file thật; **J&T đã xác nhận cấu trúc file xuất trực tiếp trên cổng khách hàng ngày 21/11/2026** nhưng **chưa chạy thử importer với file J&T thật** (xem mục 8) |
| 1B | Sổ cơ sở liên hệ, danh sách từ chối (chỉ lưu mã băm), danh sách mời, quyền dữ liệu của khách | Xong, có test |
| 1C | Nhóm Zalo, chiến dịch mời theo lô nhỏ, link riêng `/i/{token}`, đếm lượt bấm | Xong, có test |
| 1D | Cổng chặn gửi, chạy thử, xuất danh sách cho mẹ gửi tay, nhật ký gửi | Xong. **Chỉ có đường "mẹ gửi tay"**. Không có bộ gửi SMS/ZBS |
| 1E | Lõi bot trợ lý (hỏi đáp thường gặp, báo mẹ, chạy thử, tắt khẩn cấp) | Xong phần lõi. **Chưa có kênh Zalo thật** (xem `docs/ADR-0001-chon-kenh-bot.md`) |
| 1F | Docker, HTTPS (Caddy), sao lưu/khôi phục, giới hạn đăng nhập sai | Xong phần mã và script. Có thêm bản cài lên **máy chủ dùng chung** không Caddy riêng (`infra/docker-compose.shared.yml`, `infra/deploy.sh`, ảnh `ghcr.io/nexagnet/adh-app` build bằng GitHub Actions, PR #11, #12). Theo PR #12 và `tools/jt-edge-sync/README.md`, ứng dụng đã được dựng trên một VPS; **việc sao lưu/khôi phục trên máy chủ thật, quét QR từ giấy in thật chưa có bằng chứng đã thử** |
| Việt hóa | Giao diện 100% tiếng Việt (bean `labels`, trang lỗi tiếng Việt) | Xong (PR #3) |
| Giấy in | Trang `/print` + trang khách `/moi` dùng một mã QR tĩnh chung | Xong (PR #3) |
| 1G | Phân vùng khách Bắc/Trung/Nam theo địa chỉ, nhóm Zalo theo khu vực | Xong (PR #4) |
| 1H | Bản đồ khách theo tỉnh `/map` + nhắc việc thời tiết | Xong (PR #5) |
| Giao diện Penpot | Làm lại giao diện theo thiết kế đã duyệt | **Đã code** (PR #8 thanh bên + việc hôm nay; PR #10 thuật ngữ tiếng Việt, chi tiết khách 2 cột, chiến dịch, giấy in A5, `/n`; PR #17 chỉnh khớp thiết kế). Chưa xem bằng mắt trên trình duyệt thật và chưa in thử giấy A5 (xem mục 9) |
| 1I | Kết bạn → vào nhóm ngay (D15, D16) | **D16 đã code (22/11/2026, PR #29, #30, #31), mặc định TẮT, CHƯA chạy với Zalo thật.** Bộ nối Zalo không chính thức (`zca-js` 2.3.0) là dịch vụ Node `zalo-bridge/` riêng sau giao diện `ZaloGateway`; trang quản trị `/zalo` (nấc, hạn mức, quét QR, duyệt, hàng đợi tay, nhật ký). Thiết kế ở `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md` (mục 9 kết quả đợt thử 0, mục 10 quyết định khi code), hợp đồng `docs/ZALO-BRIDGE-API.md`, thử thật theo `docs/HUONG-DAN-THU-ZALO-TU-DONG.md`. **Đường bán tự động của D15 (mẹ thêm tay) đã code (PR #34 đến #36, 22/11/2026)**: gợi ý nhóm ngay lúc bấm *Đã kết bạn* và nút gộp, giữ chỗ nhóm 24 giờ, nhắc khách kết bạn quá 2 ngày chưa vào nhóm, "chờ nhóm", nút báo khách rời nhóm, chọn nhóm khác nhóm gợi ý (file 03 mục 1I.1 a–e, R3–R8); cần thử với khách thật; câu hỏi Q1–Q7 xem mục 6 |
| 2F | Đồng bộ đơn mới từ J&T | Phương án A đã code (PR #9): nhập nhiều file, gộp theo mã vận đơn, Địa chỉ hành chính, trạng thái J&T, nhắc nhập file. **Chưa chạy với file J&T thật.** Cổng nạp chung `ShipmentIngest` chưa làm (chưa có nguồn thứ hai) |

### PR và nhánh
- Đã gộp **#1 đến #38**, trừ #25 (đã đóng). Bản đồ PR (tiêu đề lấy từ GitHub):
  - #1, #2: CI (hướng dẫn chạy bước kiểm thử, hạn mức Claude Reviewer).
  - #3: giai đoạn 1C–1F (nhóm Zalo, cổng gửi, khung bot, đóng gói). #4: phân vùng và xếp nhóm theo khu vực. #5 và #19: bản đồ, thời tiết, bản đồ thật Leaflet. #15 và #20: sửa đoán tỉnh.
  - #6, #7 và #33: tài liệu bàn giao. #8, #10, #17: giao diện Penpot. #38: chữ giao diện khớp D17 (SMS, kênh Zalo, hai đường vào nhóm).
  - #9, #13, #14, #22, #23: J&T (bộ nhập, báo cần xuất từ ngày nào, báo cáo xác minh, tool Edge, "mua gần nhất"). #21: lọc và sắp xếp danh sách khách. #11 và #12: triển khai máy chủ dùng chung.
  - #16, #18: D13 (nhắn bằng tài khoản phụ, chiến dịch theo khu vực, lô nhắn hôm nay, chạy thử).
  - #24, #26, #27, #28: ghi D15, D16, ADR-0002 và kết quả đợt thử 0 của zca-js (chỉ tài liệu).
  - #29, #30, #31, #32: bộ nối Zalo D16 (lõi Java, dịch vụ Node, trang quản trị `/zalo`, hiện mã tài khoản khi quét sai).
  - #34: ghi D17 và gửi link nhóm cho khách tự bấm. #35: luồng D15 (gợi ý nhóm, giữ chỗ, chờ nhóm, nhắc việc, rời nhóm). #36: việc nhỏ (chọn nhóm khác, cột nhóm gợi ý, khách đổi nhóm, cảnh báo đúng 500 dòng J&T). #37: CI dựng ảnh Docker cho cầu nối Zalo.
- **#25** (nghiên cứu kết bạn → nhóm Zalo, bản nháp) **đã đóng không gộp (22/11/2026)**: kết luận "không dùng thư viện không chính thức" và "chưa thấy sự kiện kết bạn" đã bị thay bởi D16 và kết quả đợt thử 0 (ADR-0002 mục 9). Phần còn hữu ích của nó chưa được đưa vào tài liệu nào mà chỉ còn trên nhánh `claude/project-thread-6102cc` (gửi link nhóm cho khách tự bấm thay vì thêm hộ; mời sau khi đơn "Giao thành công"; tin hậu mãi ZBS khi có hộ kinh doanh và OA); đã ghi thành câu hỏi 10 ở mục 6.
- Nhánh làm việc: mỗi phiên dùng một nhánh `claude/project-thread-*`. Sau mỗi lần gộp phải khởi lại từ `main` (`git checkout -B <nhánh> origin/main`), rồi đẩy lên.
- **Không đụng vào** các bản nháp ở repo `nexagnet/.github` (#11, #42, #43, #45). Đó là của người dùng.
- Người dùng đã cho phép Claude **tự gộp PR của `nexagnet/adh`** khi CI xanh và không xung đột (cho phép ngày 21/11/2026; không áp dụng cho `nexagnet/.github` hay PR của người khác). Việc mới thì mở PR mới từ `main`, gộp rồi báo lại. **Riêng phần Zalo (bộ nối D16, luồng kết bạn → vào nhóm), người dùng yêu cầu review kỹ từng PR** (đọc diff, chạy test, tìm lỗi) và ghi kết quả review vào mô tả PR trước khi gộp; các PR #30, #31 đã làm vậy, và #31 rà lại cả #29.


---

## 1A. D18: tự gửi tin cho khách + chiến dịch thử (23/11/2026, PR đang mở, ĐÃ CODE NHƯNG CHƯA CHẠY VỚI ZALO THẬT)

**Quyết định của người dùng (đổi D9, 23/11/2026):** bỏ "không tự gửi tin"; hệ thống tự gửi Tin 1 theo chiến dịch, khách đồng ý kết bạn thì tự thêm vào nhóm Zalo theo khu vực; nhóm tự thêm và nhóm qua link là **một nhóm** (cùng `reserved_group_id`). Tin 2 (link nhóm) tự gửi khi việc thêm nhóm không làm được (lỗi theo từng người).

**Cách làm, theo hướng minh bạch và có hạn mức (đã thống nhất, không đổi):**
- Công tắc tự gửi tin **mặc định TẮT** (trang `/zalo`, panel "Tự gửi tin cho khách"); cần cả `APP_ZALO_AUTO_ENABLED=true`, profile `zalo-auto`, nấc `AUTO`.
- Chỉ gửi khi chiến dịch đã được **duyệt tự gửi** (`/campaigns/{id}`, nút duyệt/thu hồi); chỉ trong giờ 08:00–20:00; hạn mức ngày chỉnh được nhưng có trần (`APP_ZALO_AUTO_MSG_MAX_DAILY`, mặc định 25; mặc định ban đầu 10/ngày); **khoảng cách cố định** giữa hai tin (mặc định 120 giây, tối thiểu 100 giây; bộ nối thêm lớp thứ hai: trần 60/ngày, khoảng cách 90 giây).
- **Mỗi khách một tin mỗi loại** (3 lớp: Adh claim `READY→SENT`, sổ băm HMAC ở bộ nối, người dùng duyệt chiến dịch). Không bao giờ gửi cho người đã từ chối. Lỗi lạ hoặc không rõ đã gửi hay chưa => đánh dấu `FAILED` và **ngắt cầu dao**, không tự gửi lại.
- **Không dùng thời gian ngẫu nhiên, không giả người, không đổi dấu vết thiết bị, không proxy.** `SendProperties` chỉ còn `gapSeconds` cố định; bộ nối vẫn không có `sendFriendRequest/acceptFriendRequest/getAllFriends`. Bộ nối chỉ thêm `findUser` và `sendMessage` (test `no-send.test.mjs` khóa số lần xuất hiện).
- Tự dừng khi Zalo cảnh báo, hoặc 3 lỗi theo người liên tiếp (không phải "không tìm thấy"), hoặc 5 "không tìm thấy" liên tiếp.

**Chiến dịch thử (`/test-runs`)** — chạy qua bộ nối THẬT, chỉ cho các số điện thoại nhập tay (dự kiến 3 người), tách khỏi khách thật, giới hạn/nhóm đích/nội dung cấu hình trên giao diện, hạn mức chỉ được THẤP hơn hạn mức chung. Mã cô lập ở gói `vn.shop.crm.testrun` (`TestRunAutomation` cắm vào qua `ZaloFriendTap`/`ZaloTickHook`) và cột V22: **xóa màn hình này sau khi thử xong** = xóa gói `testrun`, 2 mẫu `test-run*.html`, thêm migration V24 bỏ các cột của V22.

**Đã làm:** bộ nối (`POST /messages`, sổ tin, 72 test Node đạt); Adh (`ZaloMessenger`, `ZaloMessageService`, `ZaloInviteSender`, chiến dịch thử, trang quản trị, migration V21–V22, 20 test mới `ZaloAutoSendTest`; lần chạy gần nhất 126/127 test mục tiêu đạt, test lỗi duy nhất là chữ trang chiến dịch thử, đã sửa chữ nhưng chưa chạy lại).

**Còn thiếu (làm tiếp, theo thứ tự):**
1. Chạy lại toàn bộ `mvn test` (JDK 25 trên CI; cục bộ dùng `-Djava.version=21 -Dmaven.compiler.release=21`).
2. ~~Test cho `TestRunAutomation`~~ **xong** (`TestRunAutomationTest`, 7 test).
3. ~~`ZaloGatewayShapeTest` + test `/messages` của `ZcaBridgeGateway`~~ **xong**.
4. ~~Tài liệu~~ **xong**: `docs/ZALO-BRIDGE-API.md`, `zalo-bridge/README.md`, hướng dẫn mục 2A, ghi vào ADR-0002 mục 12 (không tạo ADR mới). Còn: file 01 (đổi D9) nếu người dùng muốn ghi nghiệp vụ.
5. Thử thật (chưa ai làm): xem "Cách chạy thử" dưới đây.

**Cách chạy thử chiến dịch thử (sau khi PR gộp và cron tự deploy xong):**
1. `/zalo`: bộ nối đã đăng nhập tài khoản phụ; nấc `AUTO` (hoặc nấc cho phép tự thêm nhóm); gán mã nhóm Zalo cho nhóm thử ở `/zalo/groups`.
2. `/zalo` panel "Tự gửi tin": bật công tắc, đặt hạn mức (vd 5) và khoảng cách (vd 120 giây).
3. `/test-runs`: tạo chiến dịch thử, nhập 3 số, chọn nhóm đích, sửa nội dung nếu cần, bấm Bắt đầu. Chỉ trong 08:00–20:00.
4. Trên điện thoại 3 người: nhận Tin 1, bấm kết bạn → hệ thống thêm vào nhóm; nếu việc thêm lỗi thì nhận Tin 2 (link nhóm).
5. Xem nhật ký bộ nối (không có SĐT/nội dung trong log) để biết **mã lỗi thật** của Zalo, rồi điền `ZALO_PERSON_ERROR_CODES` (vd mã "không tìm thấy" của `findUser`). Mã lạ sẽ làm ngắt cầu dao: đó là chủ ý.

**Rủi ro còn lại:** `findUid/sendText` của `zca-adapter.mjs` chưa chạy với Zalo thật; tài khoản phụ vẫn có thể bị Zalo khóa (người dùng đã chấp nhận); tin có link nhóm có thể bị coi là spam; V21/V22/V23 là migration mới (Flyway chạy tự động khi deploy; chưa có migration hạ cấp). Tiền đề: đây là công cụ giúp người dùng bớt mệt, chạy chậm, nhỏ lô, có thể tắt bất kỳ lúc nào bằng công tắc ở `/zalo`.

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
| D9 | **Tạm chưa dùng SMS, làm qua Zalo trước**: xuất danh sách, mẹ gửi tay (**phần "mẹ gửi tay" bị D18 thay thế ngày 23/11/2026**; SMS/ZBS vẫn chưa dùng) |
| D10 | Giấy in bỏ vào **mọi gói hàng**, kể cả khách mua từ 2 lần, dùng **một mã QR tĩnh chung** (ngoại lệ so với BR-06) |
| D11 | Phân vùng khách theo miền/tỉnh, xếp nhóm Zalo theo khu vực (lý do: mùa lạnh chủ yếu khách miền Nam mua) |
| D12 | Bản đồ phân bố khách + nhắc việc thời tiết (chỉ nhắc trên web, không tự nhắn khách) |
| D13 | **Nghiệp vụ giai đoạn 1 (chốt 21/11/2026):** dùng **tài khoản Zalo cá nhân phụ** nhắn tin tư vấn sản phẩm cho khách mua **đúng 1 lần**; ai đồng ý **kết bạn** thì thêm vào **nhóm Zalo cùng khu vực**; chỉ nhắn **một lần duy nhất** mỗi người; **nội dung tin do chủ shop soạn ở trang quản trị**. ~~Hệ thống vẫn không tự gửi tin~~ **(đã bỏ bởi D18, 23/11/2026)** và không đăng nhập Zalo **(từ D16 có bộ nối đăng nhập tài khoản phụ, mặc định tắt)** |
| D14 | **22/11/2026:** bản đồ khách dùng bản đồ thật (Leaflet + OpenStreetMap, ranh giới 63 tỉnh), thêm lớp thời tiết chạy theo giờ (thay sơ đồ tự vẽ của D12). Đã code (PR #19) |
| D15 | **Bổ sung D13 (chốt 22/11/2026, đã code):** khách đồng ý kết bạn từ tin của tài khoản phụ thì **thêm vào nhóm phù hợp luôn** theo phân vùng D11. Vẫn bán tự động: mẹ thêm khách bằng Zalo, hệ thống gợi ý nhóm, giữ chỗ, nhắc việc, ghi lại. Quy tắc, trạng thái, phụ thuộc, câu hỏi mở: file 03 mục 1I |
| D16 | **Bổ sung D15 (chốt 22/11/2026, đã code bằng bộ nối ở PR #29–#31, mặc định tắt):** chủ shop chọn dùng **thư viện Zalo không chính thức `zca-js`** để nhận biết khách đã kết bạn và thêm vào nhóm, **dù biết trái điều khoản Zalo và tài khoản phụ có thể bị khóa**. Bộ nối riêng (Node) sau giao diện `ZaloGateway`, **mặc định tắt**, bật từng nấc, nhật ký, hạn mức, cầu dao dừng ngay, phiên mã hóa, chỉ tài khoản phụ. **Không** làm giả hành vi để né phát hiện, **không** gửi tin hàng loạt. Chi tiết: `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md`, file 03 mục 1I.9 |
| D17 | **Chốt 22/11/2026 (sau D16), đã code phần code được:** (1) **không** làm hai tài khoản web Bố/Mẹ, giữ một tài khoản đăng nhập (nhật ký ghi tên tài khoản gốc); (2) kênh trợ lý trong nhóm và cách báo việc cho mẹ: **Zalo** (chưa chọn phương án kỹ thuật, chưa chạy thử, **chưa code**); (3) vào nhóm theo **cả hai đường**: khách tự bấm link nhóm (**đã code**, PR #34) hoặc bộ nối D16 tự thêm khi khách kết bạn (mặc định tắt); (4) code luôn các việc 1I bán tự động còn thiếu (**đã code**, PR #35, #36); (5) **máy chủ và deploy để sau**. Chi tiết: file 03 mục 1I.10, ADR-0001, ADR-0002 mục 11 |
| D18 | **Chốt 23/11/2026, mới ghi tài liệu, chưa có mã (luồng Zalo khác làm phần mã):** **bỏ D9 "không tự gửi tin"** (phần mẹ gửi tay của D9, D13, D15, R13). Phạm vi mở rộng giai đoạn 1: hệ thống **tự gửi tin qua tài khoản Zalo phụ như người thật, gửi từ từ** (giãn cách, lô nhỏ) để tránh Zalo quét bot. Khi **khởi tạo chiến dịch** (chọn phạm vi khách) hệ thống tự gửi **Tin 1** và **tự thêm khách đồng ý kết bạn vào nhóm phù hợp**. Khách vẫn có thể vào nhóm qua link, nhưng **nhóm tự thêm và nhóm của link phải là MỘT nhóm**. Chủ shop chấp nhận rủi ro khóa tài khoản phụ. Quyết định này chỉ cho giãn cách và lô nhỏ; **không** cho đổi dấu vân tay/proxy xoay/nhiều tài khoản luân phiên (vẫn ngoài phạm vi). Câu hỏi còn mở (giãn cách, lô, hạn mức gửi; Tin 2 có tự gửi không; có giữ nấc duyệt không): file 03 mục 1I.11 |

Lựa chọn khác của người dùng trong các phiên: giao diện **100% tiếng Việt**; "trợ lý AI" thay cho chữ "bot" (theo thiết kế Penpot); bố mẹ không rành kỹ thuật nên chữ to, mỗi màn một việc; người dùng đã **duyệt thiết kế Penpot** (21/11/2026) và dặn "sửa xong Penpot thì code luôn"; chọn **phương án A cho J&T** (nhập file tăng dần).

### Quyết định về Zalo ngày 22/11/2026 (D15 đến D17), ghi đủ để khỏi hỏi lại

Do chính người dùng chốt trong phiên làm việc (không phải giả định):
- **Một tài khoản đăng nhập web.** Không làm hai tài khoản Bố/Mẹ riêng.
- **Kênh trợ lý trong nhóm và cách báo việc cho mẹ: Zalo.** Chưa chọn phương án kỹ thuật (P1 Zalo OA/GMF, P2 `zca-js` bằng tài khoản riêng, P3 Zalo Bot chính thức; ADR-0001), chưa chạy spike nào. Thiết kế báo mẹ (đề xuất, chưa được người dùng duyệt riêng): trợ lý nhắc mẹ ngay trong nhóm nơi khách hỏi, cắm vào `HandoffService.open` (ADR-0001 mục "Báo việc cho mẹ qua Zalo"). **Chưa có dòng code nào.**
- **Hai đường vào nhóm cùng tồn tại, mỗi khách đi một trong hai:** (a) gửi link nhóm cho khách tự bấm (đã code, chạy được cả khi bộ nối tắt); (b) khi khách đồng ý kết bạn thì tài khoản phụ tự thêm vào nhóm (bộ nối D16, **tắt mặc định**). "tk trợ lý" trong lời người dùng được hiểu là **tài khoản Zalo phụ của bộ nối D16**; tài khoản Zalo riêng cho trợ lý AI trong nhóm (mốc 1E) là việc khác.
- **Hạn mức bộ nối:** 50 người mỗi ngày, rải từ 07:00 đến 22:00, tối đa 5 người mỗi giờ, cách nhau ít nhất 2 phút; chỉnh được trên trang `/zalo` nhưng không vượt trần cứng cấu hình (`app.zalo.auto.max-daily`, mặc định 100/ngày; trần giờ 10, khoảng cách tối thiểu 2 phút, trong `ZaloAutoProperties`). Q8 người dùng chọn nấc `AUTO`, nhưng **mặc định vẫn `CONFIRM` (mẹ duyệt từng người)** cho đến khi chạy thử xong; chọn `AUTO` phải tick xác nhận đã chạy thử.
- **Quét QR:** khi phiên Zalo hết hạn, **mẹ hoặc người dùng quét QR ngay trên trang quản trị** (chỉ tài khoản admin), bằng điện thoại của tài khoản phụ.
- **Chấp nhận mất tài khoản phụ** nếu Zalo khóa (đã biết cách này trái điều khoản Zalo). Tài khoản của bố mẹ thì **không bao giờ** dùng.
- **KHÔNG né phát hiện của Zalo và KHÔNG gửi tin hàng loạt.** Người dùng từng hỏi "làm cho giống người thật" và đã bị từ chối: bộ nối chỉ có giới hạn tốc độ và khung giờ, không có lớp nào che việc dùng thư viện (ADR-0002 mục 4, 8). Bộ nối chỉ có **ba việc**: báo trạng thái, đọc sự kiện "đã thành bạn", thêm **một** người vào **một** nhóm. Không có hàm gửi tin hay gửi lời mời; `ZaloGatewayShapeTest` khóa điều này.
- **Review kỹ từng PR phần Zalo** trước khi gộp (xem mục 1).
- **Máy chủ và deploy để sau.** Người dùng sẽ cho địa chỉ máy 2 GB RAM khi muốn deploy. Repo không ghi địa chỉ máy chủ nào; đừng đoán.

### Cách phân phối khách vào nhóm theo khu vực

- **Khu vực của khách:** suy ra từ địa chỉ vận đơn (`geo/VnGeo.java`): tỉnh → vùng → miền (Bắc/Trung/Nam), không chắc thì "Chưa rõ", không đoán.
- **Nhóm:** mỗi nhóm có mục tiêu khoảng **5 khách** (`zalo_group.target_customers`, mặc định 5), gắn một khu vực (tỉnh, vùng, miền; mặc định "Chung"). Một nhóm coi là **đầy** khi `số khách thật + lượt chờ × tỷ lệ giả định 0,5 + số chỗ đang giữ ≥ mục tiêu` (`GroupService`); khách thật không tính tài khoản mẹ, trợ lý và tài khoản phụ.
- **Thứ tự chọn nhóm:** cùng tỉnh, rồi cùng vùng, rồi cùng miền, rồi nhóm chung. Nhóm của miền khác **bị loại** (cấu hình `app.grouping.allow-other-region`, mặc định tắt). Khách chưa rõ khu vực: nhóm chung trước, rồi mọi nhóm.
- **Giữ chỗ 24 giờ** (`app.friend-flow.reserve-hours`): bấm *Đã kết bạn* thì nhóm gợi ý được giữ một chỗ cho khách đó đến khi bấm *Đã vào nhóm* hoặc hết hạn; hết hạn thì gợi ý lại từ đầu, để nhiều khách kết bạn cùng ngày không bị dồn vào một nhóm.
- **Chờ nhóm:** chưa có nhóm hợp khu vực (chưa có hoặc đã đầy) thì khách giữ trạng thái đã kết bạn kèm nhãn "chờ nhóm", **không đẩy sang miền khác**; hệ thống ghi một dòng "chờ nhóm" theo mã tỉnh (không SĐT, không tên) để mẹ biết cần mở nhóm ở đâu. Việc này chờ mẹ mở nhóm (đề xuất, người dùng chưa trả lời riêng, Q2 và Q3 ở mục 6).
- **Nhắc việc (chỉ trên web, không nhắn khách):** khách đã kết bạn quá 2 ngày chưa vào nhóm hiện thành việc "quá hạn" (`overdue-days`); đã nhắn quá 14 ngày chưa kết bạn hiện nhãn "không phản hồi" (`no-reply-days`), không nhắn lại. Nút *Khách đã rời nhóm* giảm số khách của nhóm.
- **Bộ nối D16 chỉ thêm khách đã ghép được** với một lời mời `SENT` của cửa hàng, chưa từ chối, không `REPEAT`, nhóm đích có **mã nhóm Zalo** (`zalo_group.zalo_group_id`, nhập tay) và đúng khu vực; không thì khách vào hàng đợi tay.

### Kết quả chạy thử `zca-js` (đợt thử 0, 22/11/2026; chi tiết ADR-0002 mục 9)

Thư viện `zca-js` 2.3.0 chạy riêng bằng một tài khoản thử tự nguyện, **không phải** mã trong `zalo-bridge/`:
- **Sự kiện kết bạn:** `ADD` phát đúng lúc người kia chấp nhận lời mời của tài khoản phụ, chỉ mang mã Zalo (uid), không kèm tên hay số. Người kia tự gửi lời mời tới thì là `REQUEST` với `isSelf = false` (không được tự chấp nhận).
- **Tra số điện thoại:** `findUser(số)` đổi số thành uid chỉ khi người đó cho phép tìm bằng số (không thì lỗi 212). Trong 32 bạn của tài khoản phụ, chỉ 5 người lộ số (27 không lộ); nên **phần lớn khách sẽ rơi vào hàng đợi ghép tay**. Chưa thử `getUserInfo` trên khách thật.
- **Thêm vào nhóm:** tài khoản phụ là trưởng nhóm thì thêm được; chỉ là **thành viên thường** (nhóm không bật "chỉ quản trị viên được thêm") **vẫn thêm được**. `createGroup` báo lỗi 261 khi chưa là bạn.
- **Phiên:** dùng lại phiên đã lưu thành công sau hơn một giờ; thời gian sống thật chưa biết.
- **Kết nối nghe đứt mã 1006 sau khoảng 40 phút** chạy liên tục; bộ nối đã được viết để tự nối lại có backoff và giới hạn số lần (chưa kiểm với Zalo thật). Lỗi 600 ("zpw_sek") gặp một lần, không lặp lại; bộ nối coi là lỗi phiên và dừng.
- Không thấy Zalo cảnh báo hay hạn chế, nhưng số thao tác rất ít nên **không suy ra gì** về ngưỡng an toàn của 50 người mỗi ngày.

### Giả định do phiên trước tự đặt (chủ shop chưa xác nhận, chỉnh được)
- Mẹ **đếm lại thành viên nhóm Zalo mỗi tuần** (`GroupService.RECOUNT_DAYS = 7`). Lượt bấm chờ tính tỷ lệ 0,5 (`app.invitations.assumed-accept-rate`) chỉ để chia nhóm, **không phải số người thật vào nhóm**.
- Nhóm Zalo mục tiêu khoảng **5 khách/nhóm**, số khách **không tính tài khoản mẹ và trợ lý** (2 tài khoản cửa hàng).
- **Phân vùng:** tỉnh là tên xuất hiện **cuối cùng** trong địa chỉ vận đơn, bỏ qua tên đứng sau "xã/phường/quận/huyện/đường/phố"; dấu phẩy là ranh giới giữa các cấp địa chỉ (chữ cuối của cấp trước như "Sơn Dương", "Điền Xá" không phải tiền tố "đường/xã"; đã sửa ở `VnGeo.VERSION = 2`); không chắc thì "Chưa rõ", **không đoán bừa**. Tây Nguyên xếp miền Trung. Sau sáp nhập tỉnh (1/7/2025) tên tỉnh mới đều trùng một tên tỉnh cũ nên vẫn nhận được, nhưng khách cũ ở tỉnh bị sáp nhập có thể hiện tên tỉnh mới (vẫn cùng miền hoặc vùng kế bên). Danh sách 63 tỉnh nằm ở `geo/VnGeo.java`; sửa cách đoán thì tăng `VnGeo.VERSION` để khởi động lại tự tính lại cho khách cũ.
- **Xếp nhóm theo khu vực:** cùng tỉnh > cùng vùng > cùng miền > nhóm chung; nhóm của miền khác bị loại (cấu hình `app.grouping.allow-other-region`, mặc định không). Nhóm mới mặc định là "Chung" cho tới khi mẹ gắn khu vực. Khách chưa rõ khu vực: nhóm chung trước, rồi mọi nhóm.
- **Mã QR chung `/moi`** hỏi "Bạn ở tỉnh/thành nào?" (bỏ qua được). Khách chọn tỉnh mà chưa có nhóm hợp được ghi một dòng "chờ nhóm" (chỉ mã tỉnh + mã ngẫu nhiên của trình duyệt, **không SĐT/tên/địa chỉ mạng**) để mẹ biết cần mở nhóm ở đâu. Chống quét lặp bằng cookie `adh_moi` (90 ngày) và giới hạn 20 lượt mới/địa chỉ mạng/giờ.
- **Thời tiết:** ngưỡng nhắc lạnh bất thường là nhiệt độ thấp nhất 48 giờ tới ≤13°C (Bắc), ≤16°C (Trung), ≤22°C (Nam); nóng khi cao nhất ≥37°C; chỉ nhắc ở tỉnh có từ 10 khách. Tất cả là giả định, chỉnh trong `application.yml` mục `app.weather`.
- **Bản đồ** (từ 22/11/2026) là bản đồ thật: Leaflet 1.9.4 (giấy phép BSD-2, chép sẵn ở `static/vendor/leaflet/`) vẽ nền OpenStreetMap và ranh giới 63 tỉnh (`static/map/vn-provinces.geojson`, nguồn Natural Earth, miền công cộng, tạo bằng `tools/build-vn-geojson.mjs`). Ranh giới là của 63 tỉnh TRƯỚC sáp nhập 1/7/2025, khớp danh sách tỉnh trong `geo/VnGeo.java`. Nền ảnh tải thẳng từ `tile.openstreetmap.org` trên trình duyệt (CSP chỉ mở thêm đúng địa chỉ này cho ảnh; Referer chỉ gửi tên miền, đúng yêu cầu của OSM). Hoàng Sa, Trường Sa chỉ có nhãn, chưa vẽ ranh giới đảo. Lớp thời tiết chạy theo giờ lấy từ MET Norway, lưu ở cột `weather_forecast.hourly`.
- Hạn phản hồi yêu cầu dữ liệu của khách hiện là 15 ngày cho mọi loại (`app.privacy.request-due-days`) và **chưa có xác nhận pháp lý**. Thiết kế Penpot muốn hạn theo loại (ngừng liên hệ/xuất dữ liệu 2 ngày làm việc, xóa 20 ngày theo file 03 mục 1; nguồn phụ, cần đối chiếu Công báo).

---

### D13: nhắn bằng tài khoản Zalo phụ (đã code)

- **Cách làm:** Chiến dịch mời, Xuất danh sách, người gửi nhắn tay bằng tài khoản phụ, rồi bấm trên trang chiến dịch: *Đã kết bạn*, *Đã vào nhóm* (hệ thống gợi ý nhóm cùng tỉnh, vùng, miền, và tăng số khách thật của nhóm thêm 1) hoặc *Khách từ chối* (vào danh sách từ chối vĩnh viễn, không nhắn lại). Trạng thái lời mời: READY, SENT, FRIENDED, JOINED, CLICKED, CANCELLED (V12). Khách có lời mời (mọi trạng thái) không bao giờ vào chiến dịch mới, nên chỉ nhắn một lần.
- **Nội dung tin** soạn ở trang Chiến dịch mời (khung "Nội dung tin mặc định", lưu trong cài đặt `invite.template`, tối đa 800 ký tự). `{link}` không còn bắt buộc; có `{ten}`, `{cua_hang}`.
- **Giữ nguyên (câu này mô tả trạng thái trước D18 và D16; từ D18 hệ thống sẽ tự gửi, từ D16 tài khoản phụ có thể nối vào bộ nối tùy chọn):** hệ thống không tự gửi, không đăng nhập Zalo, tài khoản phụ không kết nối vào hệ thống (BR-11); chỉ khách mua 1 lần đã duyệt (BR-06); cổng chặn, danh sách từ chối, hạn mức lô và giờ gửi; đường QR trên giấy in (D10) vẫn chạy song song.
- **Mâu thuẫn cần chủ shop biết:** (1) tin **tư vấn sản phẩm** là tin quảng cáo, khác với quy tắc cũ BR-16 "chỉ kết nối, không quảng cáo" (quy tắc đó vẫn áp dụng cho chữ trên giấy in và trang quét QR); (2) nhắn cho người **chưa đồng ý nhận tin** (chỉ để lại số khi nhận hàng) có rủi ro pháp lý về dữ liệu cá nhân và tin nhắn rác; chủ shop đã nhận rủi ro (D3), nên nên có câu cho khách biết cách xin ngừng nhận tin; (3) Zalo có thể **hạn chế tài khoản phụ** nếu nhắn hoặc gửi kết bạn cho nhiều người lạ, nên giữ lô nhỏ, chậm, và tuyệt đối không dùng công cụ tự động gửi tin lên tài khoản này; (4) người gửi phải tự đánh dấu các bước, hệ thống không biết khách có thật sự kết bạn hay vào nhóm.
- **Chiến dịch theo khu vực + lô nhắn hôm nay (22/11/2026, V14):** tạo chiến dịch chỉ cho khách một miền/vùng/tỉnh hoặc "chưa rõ" (`invitation_campaign.geo_scope`, cùng cách ghi mã với nhóm: `R:NAM`, `S:...`, `P:...`, `U`); tối đa `app.invitations.max-campaign` (300) khách mỗi chiến dịch. Trang **Lô nhắn hôm nay** (`/today`, `SendService.today`) chỉ gợi ý đủ hạn mức còn lại của ngày (`daily-limit`) và giờ (`hourly-limit`, 60 phút gần nhất), trong giờ 07–22, không khi tạm dừng; người thật nhắn tay rồi bấm *Đã nhắn* (`SendService.markMessaged`, kênh `ZALO_PHU`, ghi nhật ký gửi). Ghi nhận *Đã nhắn* **không bị chặn theo giờ/hạn mức** vì việc đã xảy ra (chặn thì hệ thống không biết và có thể nhắn lại), nhưng vẫn từ chối khách không còn đủ điều kiện liên hệ. Nhịp gợi ý `suggested-gap-minutes` (3 phút) chỉ để hiển thị, **không ép buộc, không ngẫu nhiên hóa** để giả người thật. Hệ thống vẫn không tự gửi, không đăng nhập Zalo.
- **Chạy thử trên Zalo thật (22/11/2026, V14):** `/test-runs` (`testrun/TestRunService`). Vài số điện thoại do người dùng tự nhập, lưu ở bảng riêng `test_run`/`test_target` (không khóa ngoại tới khách thật), có đánh dấu tay Đã nhắn/Đã kết bạn/Đã vào nhóm/Từ chối và gợi ý nhóm theo tỉnh đã chọn. **Không** đổi trạng thái lời mời của khách thật, không ghi `send_log`/`contact_event`/danh sách từ chối, không tăng số khách của nhóm (có test `TestRunTest.chayHetQuyTrinhMaDuLieuThatKhongDoi` so ảnh chụp dữ liệu trước/sau). Hướng dẫn thử từng bước cho người dùng: [`HUONG-DAN-THU-ZALO.md`](HUONG-DAN-THU-ZALO.md).
- **Luồng D15 "kết bạn xong thì thêm vào nhóm ngay" (đã code 22/11/2026):** bấm *Đã kết bạn* gợi ý nhóm cùng khu vực và **giữ chỗ 24 giờ** (V19); nút gộp *Đã kết bạn và đã vào nhóm*; trang **Chờ vào nhóm** (`/friends`) và việc trên Tổng quan: khách đã kết bạn chưa vào nhóm, nhãn quá hạn sau 2 ngày, "chờ nhóm" khi chưa có nhóm hợp (không đẩy sang miền khác), "không phản hồi" sau 14 ngày (chỉ nhãn, không nhắn lại); nút *Khách đã rời nhóm* giảm số khách của nhóm. Mốc thời gian là giả định, chỉnh ở `app.friend-flow.*`. Đặc tả: file 03 mục 1I (R1–R14). Q2 và Q3 chưa được trả lời riêng; code theo đề xuất (chờ mẹ mở nhóm).
- **D16 (bộ nối Zalo không chính thức): đã code, mặc định tắt.** Đợt thử 0 đã chạy (ADR-0002 mục 9): sự kiện `ADD` phát đúng; khoảng 27/32 bạn thật không lộ số nên phần lớn khách sẽ rơi vào hàng đợi ghép tay; thành viên thường thêm được người vào nhóm; kết nối nghe đứt mã 1006 sau khoảng 40 phút. Còn **chưa biết**: ngưỡng thao tác an toàn mỗi ngày, lỗi khi nhóm bật "chỉ quản trị viên được thêm", nhóm đầy hay khách chặn, thời gian sống của phiên. Không dùng bản fork/đăng lại của zca-js. Nếu không bật, mọi thứ vẫn chạy tay như D13/D15.
- **Gửi link nhóm cho khách tự bấm (D17, đã code):** khách ở trạng thái *Đã kết bạn* có khung "Gửi link nhóm cho khách tự bấm" ở trang chiến dịch: tin soạn sẵn kèm link nhóm cùng khu vực (mẫu sửa ở trang Chiến dịch mời, `invite.group-link-template`; có `{ten}`, `{cua_hang}`, `{nhom}`, `{link_nhom}`, `{link}`), nút *Sao chép tin* và nút *Đã gửi link nhóm* (V18 `invitation.group_link_sent_at`, sự kiện `GROUP_LINK_SENT`). Hệ thống không gửi. Mỗi khách một lần; gửi link thì khách thôi nằm trong hàng thêm tự động của bộ nối D16.
- **Đã làm sau đó (PR #35, #36):** chọn nhóm khác nhóm gợi ý khi đánh dấu *Đã vào nhóm*; file xuất gửi tay có cột `khu_vuc` và `nhom_goi_y`; nhãn "không phản hồi" cho khách đã nhắn mà quá lâu chưa kết bạn. Không còn việc D13/D15 nào bị bỏ sót trong danh sách cũ.

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
| `APP_WEATHER_ENABLED` | Bật thời tiết (MET Norway) và lớp thời tiết chạy trên bản đồ. Chỉ gửi tọa độ tỉnh lỵ; mặc định hỏi cả 63 tỉnh (`app.weather.all-provinces`) | `false` |
| `APP_MAP_TILE_URL` | Địa chỉ ô bản đồ nền (mặc định OpenStreetMap công cộng; chỉ dùng cho lượng truy cập nhỏ). Đổi thì sửa luôn `app.map.tile-attribution` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` |
| `APP_ZALO_AUTO_ENABLED`, `APP_ZALO_BRIDGE_KEY`, `APP_ZALO_AUTO_MAX_DAILY`; (dịch vụ cầu nối) `ZALO_SESSION_KEY`, `ZALO_ACCOUNT_ID`, `ZALO_PERSON_ERROR_CODES`; `COMPOSE_PROFILES=zalo-auto` | Bộ nối Zalo không chính thức (D16). Cần **cả** cờ, profile Docker và chọn nấc ở trang `/zalo`. Hạn mức mặc định 50/ngày, 5/giờ, cách nhau 2 phút, trần cứng 100/ngày | tắt |
| `APP_WEATHER_USER_AGENT` | Tên ứng dụng gửi cho MET Norway (họ yêu cầu có định danh; có thể thêm tên miền cửa hàng) | `adh-shop-crm/1.0` |
| `app.grouping.allow-other-region` | Cho xếp khách vào nhóm miền khác khi hết nhóm hợp | `false` |
| `app.entry.max-new-per-ip-per-hour` | Giới hạn lượt mới của mã QR chung | 20 |
| `app.invitations.max-batch` | Tối đa khách mỗi lần chạy thử/xuất danh sách | 20 |
| `app.invitations.max-campaign` | Tối đa khách trong một chiến dịch (mỗi ngày chỉ nhắn theo hạn mức) | 300 |
| `APP_SENDING_DAILY_LIMIT`, `APP_SENDING_HOURLY_LIMIT` | Hạn mức tin mỗi ngày và mỗi 60 phút (0 = không giới hạn theo giờ) | 20, 6 |
| `app.security.max-login-failures`, `lock-minutes` | Sai 5 lần khóa 15 phút theo cặp (IP, tên đăng nhập) | 5, 15 |
| `app.bot.*` | Bot im lặng 30 phút sau khi mẹ trả lời; tối đa 3 trả lời/5 phút | xem yml |

Công tắc trên giao diện: **Tạm dừng tất cả chiến dịch** (`/campaigns`), công tắc bot toàn hệ thống và chế độ chạy thử (`/bot`), trạng thái nhóm Zalo.

---

## 4. Chạy, kiểm thử, triển khai

- **Chạy thử trên máy** (không cần Docker/PostgreSQL): `cd app && ./mvnw spring-boot:test-run -Dspring-boot.run.mainClass=vn.shop.crm.DevApplication`, mở <http://localhost:8080>, đăng nhập `admin` / `dev-only-password`. Dữ liệu mẫu giả ở `fixtures/`.
- **Kiểm thử:** `cd app && ./mvnw verify` (PostgreSQL nhúng thật, JaCoCo ngưỡng 80% dòng). Phần Node: `cd zalo-bridge && npm ci --ignore-scripts --no-audit --no-fund && npm test` (Node 20 trở lên, dùng bộ điều hợp giả, không cần Zalo).
- **CI** (`.github/workflows/`): `ci.yml` có ba job chạy trên mỗi PR và mỗi lần đẩy lên `main`: `test` (lệnh `verify` ở trên, Java 25), `bridge` (test của `zalo-bridge`, kèm `npm audit` chỉ để tham khảo) và `lint` (chặn file Excel, `data_product/`, `.env`, build output trong git; kiểm tra workflow bằng actionlint). `nexagnet-claude-reviewer.yml` gọi Reviewer dùng chung của `nexagnet/.github` (đã ghim commit, cần secret `CLAUDE_CODE_OAUTH_TOKEN` của tổ chức) sau khi `ci` xanh trên PR nội bộ của chính repo (đòi job `test` và `lint` đạt); khi chạy sau một lần đẩy lên `main` nó ở trạng thái "skipped", đó là bình thường. Mọi action trong workflow ghim theo mã commit.
- **Build ảnh Docker:** `image.yml` chạy khi gộp vào `main` (hoặc chạy tay), dựng và đẩy lên `ghcr.io/nexagnet/adh-app:<mã commit đầy đủ>` và `ghcr.io/nexagnet/adh-zalo-bridge:<mã commit đầy đủ>` (kèm thẻ `latest`). `infra/deploy.sh` dùng đúng thẻ mã commit. Ảnh cầu nối chỉ được dựng sẵn, **không tự chạy**; compose hiện vẫn `build:` cục bộ cho cầu nối. Cả hai ảnh đã dựng xanh trên `main` ở commit gộp #38; **chưa ai chạy thử ảnh cầu nối**.
- **Môi trường cloud/sandbox:** cần **JDK 25** (đã có ở `/opt/jdk25`; Adoptium bị chặn proxy, tải từ download.oracle.com nếu thiếu). PostgreSQL nhúng **không chạy bằng root**: chạy Maven bằng `runuser -u ubuntu -- env JAVA_HOME=/opt/jdk25 PATH=/opt/jdk25/bin:$PATH ./mvnw ...` và `chown -R ubuntu` thư mục repo; thêm `git config --global --add safe.directory <repo>` nếu git báo quyền sở hữu. Không có daemon Docker trong sandbox.
- **Triển khai máy chủ:** `infra/docker-compose.prod.yml` (ứng dụng + PostgreSQL + Caddy HTTPS, giới hạn bộ nhớ cho VPS 2 GB), `infra/backup.sh`, `infra/restore-test.sh`. Hướng dẫn từng bước: `docs/RUNBOOK.md` mục B.
- **Cơ sở dữ liệu:** Flyway, migration `V1`…`V23` (V21 tự gửi tin D18, V22 chiến dịch thử, V23 đối soát J&T) ở `app/src/main/resources/db/migration` (V9 phân vùng, V10 thời tiết, V12 luồng kết bạn, V14 chiến dịch theo khu vực và chạy thử, V17 bộ nối Zalo, V18 gửi link nhóm, V19 giữ chỗ nhóm, V20 đếm khách đổi nhóm khi nhập). Việc mới dùng `V24`. Khi thêm bảng mới, **nhớ thêm vào danh sách `TRUNCATE` trong `AbstractPostgresTest`** (test dùng chung một cơ sở dữ liệu).
- **Công nghệ:** Java 25, Spring Boot 4.2, Spring Security, JdbcClient (SQL thuần), Thymeleaf, Flyway, PostgreSQL; zxing sinh mã QR trên máy chủ; Jackson 3 (`tools.jackson`). Chính sách bảo mật nội dung (CSP): **không script hay style nội tuyến** (`default-src 'self'; style-src 'self'`), nên tương tác phải làm bằng liên kết/biểu mẫu hoặc file `.js` riêng.

### Bản đồ mã nguồn (`app/src/main/java/vn/shop/crm`)
`imports` (nhập file), `shipping` (hãng, trạng thái chuẩn), `customers` (phân loại, danh sách), `contact` (cơ sở liên hệ, từ chối, yêu cầu dữ liệu), `invite` (nhóm Zalo, chiến dịch, lời mời, trang khách `/i`, mã QR chung `/moi`, in giấy `/print`), `send` (cổng chặn, chạy thử, xuất cho mẹ gửi tay, lô nhắn hôm nay), `testrun` (đợt chạy thử tách khỏi khách thật), `bot` (lõi bot trợ lý), `geo` (63 tỉnh, vùng, đoán tỉnh), `map` (bản đồ, thời tiết), `security`, `common` (nhãn tiếng Việt `Labels`, SĐT, QR), `web` (tổng quan, trạng thái đơn, lỗi).

Đường dẫn chính (cần đăng nhập, trừ `/login`, `/moi`, `/i/**`): `/` tổng quan, `/imports`, `/customers`, `/contacts`, `/campaigns`, `/today` (lô nhắn hôm nay), `/test-runs` (chạy thử Zalo), `/groups`, `/print`, `/sending`, `/bot`, `/handoffs`, `/status-mappings`, `/privacy`, `/map`.

---

## 5. Phần CHƯA kiểm chứng (đừng coi là đã chạy được)

- **Triển khai:** bản cài máy chủ dùng chung (`deploy.sh`, `docker-compose.shared.yml`) đã được dùng để dựng ứng dụng trên một VPS (PR #11, #12 sửa lỗi gặp khi deploy thật; tool J&T ghi đã chạy với địa chỉ của ứng dụng đó ngày 22/11/2026), nhưng sandbox của AI không có Docker, SSH hay quyền vào VPS nên **bản ghi chính xác về VPS (tên miền, HTTPS, phiên bản đang chạy) không nằm trong repo**: hỏi chủ shop. `docker-compose.prod.yml` (Caddy riêng) chưa có bằng chứng đã chạy. Sao lưu tự động và khôi phục (`backup.sh`, `restore-test.sh`) chưa có bằng chứng đã thử trên máy chủ thật. Chưa có workflow tự deploy: người có SSH chạy `infra/deploy.sh deploy`, hoặc (đã viết, chưa cài lên máy chủ, mới thử bằng lệnh giả) `infra/auto-deploy.sh` chạy bằng cron theo `docs/RUNBOOK.md` mục 2c.
- **In giấy và quét mã QR thật**: PDF khổ A6/A5/4 tờ A4 đã kiểm tra kích thước, mã QR đã giải mã lại bằng test, nhưng chưa in ra giấy và quét bằng điện thoại thật. **Chỉ in khi `APP_PUBLIC_BASE_URL` là địa chỉ thật.**
- **Kênh Zalo thật cho trợ lý trong nhóm, và báo việc cho mẹ qua Zalo**: chưa chạy spike nào, chưa code (kênh Zalo đã chọn nhưng phương án kỹ thuật "CHƯA CHỌN", ADR-0001).
- **Bộ gửi SMS/ZBS**: không có, và chưa chọn nhà cung cấp. Đừng viết khi chưa có chủ shop quyết.
- **J&T**: cấu trúc file đã được xác nhận trên cổng khách hàng (mục 8) nhưng importer chưa được chạy với một file J&T thật; `fixtures/jt_sample.csv` vẫn là file giả lập có cột khác file thật.
- **Thời tiết thật**: gọi MET Norway chưa chạy thử với mạng thật (test dùng bản giả); điều khoản đã đọc ngày 21/11/2026 nhưng trang giấy phép `api.met.no/doc/License` chưa đọc riêng.
- **Giao diện Penpot trên trình duyệt thật:** đã code (PR #8, #10, #17, #38) và có test kiểm chữ, nhưng chưa ai mở bằng trình duyệt thật để xem cỡ chữ, thanh bên, màn hình điện thoại. Giấy in A5 chưa in thử (đã nêu ở trên).
- **Sao lưu và khôi phục thật:** `infra/backup.sh` và `infra/restore-test.sh` chưa có bằng chứng đã chạy trên máy chủ thật (đã nêu ở mục Triển khai); nên thử khi deploy.
- **Luồng kết bạn → vào nhóm với khách thật:** logic và giữ chỗ đã có test, nhưng chưa dùng với khách thật. Chưa biết khách có bấm link nhóm rồi vào nhóm hay không (hệ thống không biết, mẹ đối chiếu trên Zalo rồi bấm *Đã vào nhóm*) và tin kèm link có bị Zalo coi là spam không.
- Pháp lý: không phải lời khuyên pháp lý; các điều NĐ 91/2020, Luật 91/2025, NĐ 356/2025, NĐ 330/2026 trong file 03 là **nguồn phụ, cần đối chiếu Công báo**.
- Dữ liệu khách chưa mã hóa đĩa (sao lưu mã hóa được bằng `age`, tùy chọn). Hiện **chỉ một tài khoản đăng nhập**.
- **Bộ nối Zalo D16:** mã đã được kiểm bằng bộ nối giả (test Java và 54 test Node đều chạy không cần Zalo), **bộ điều hợp `zca-js` trong `zalo-bridge/` chưa chạy với Zalo thật** (đợt thử 0 chạy thư viện riêng), **ảnh Docker của bộ nối đã được CI dựng (PR #37) nhưng chưa chạy thử**. Chưa biết: lỗi Zalo trả khi nhóm bật "chỉ quản trị viên được thêm"/nhóm đầy/bị chặn (bộ nối dừng khi gặp lỗi lạ), 50 người/ngày có an toàn không, thời gian sống của phiên, cách lấy `ZALO_ACCOUNT_ID` và mã nhóm Zalo trong giao diện. Xem bảng cuối `docs/HUONG-DAN-THU-ZALO-TU-DONG.md`.

---

## 6. Việc phải hỏi chủ shop (đang mở)

1. ~~**Đ1**: hai tài khoản web Bố/Mẹ~~ **Đã trả lời 22/11/2026: không** (D17). Giữ một tài khoản.
2. **Đ2**: "đăng nhập của AI bot" = tài khoản Zalo riêng của trợ lý (không phải đăng nhập web), đúng không? Chưa có câu trả lời riêng; vẫn hiểu như vậy.
3. **Kênh trợ lý trong nhóm Zalo**: **đã chọn Zalo** (22/11/2026, D17). Còn mở: phương án kỹ thuật (Zalo Bot chính thức, Zalo OA/GMF, tài khoản cá nhân riêng có rủi ro khóa) và các spike. Xem ADR-0001.
4. **Mẹ nhận báo việc từ trợ lý**: **đã chọn Zalo** (D17). Thiết kế đề xuất (trợ lý nhắc mẹ ngay trong nhóm, không cần đường gửi riêng) ở ADR-0001 mục "Báo việc cho mẹ"; chưa code, chờ phương án kênh trợ lý.
5. **Địa chỉ ngắn và tên miền cho mã QR** (cần HTTPS; thiết kế dùng đường ngắn `/n`).
6. **SMS**: khi nào làm lại, nhà cung cấp nào (eSMS/SpeedSMS), cá nhân chưa có hộ kinh doanh có đăng ký brandname được không.
7. **J&T**: xem mục 8.
8. **D15, D16 (kết bạn → vào nhóm ngay)**: Q1–Q7 ở file 03 mục 1I.6. Người dùng chọn "làm theo đề xuất" cho các việc code (D17), nên Q4 (mốc 24 giờ, 2 ngày, 14 ngày), Q6 (báo khách rời nhóm bằng tay) và Q7 (không tự đăng tin chào) đang theo đề xuất; Q5 phần "ghi theo người" đã trả lời là không. **Q2 (cách báo khách trong tin đầu) và Q3 (khi chưa có nhóm khu vực: chờ mẹ mở nhóm, hay cho vào nhóm khác) chưa được trả lời riêng**, code đang theo đề xuất (chờ mẹ mở nhóm, không đẩy sang miền khác). Q1 (ai thêm khách vào nhóm) đã được trả lời gián tiếp: tài khoản phụ làm được ở nhóm khi là thành viên thường (đợt thử 0), nhưng vẫn cần thử với khách thật.
9. **D16 (bộ nối Zalo không chính thức)**: không còn câu hỏi mở riêng cho D16. Đã trả lời 22/11/2026: **Q8** chọn `AUTO` (bật bằng công tắc trên giao diện, **mặc định `CONFIRM`** đến khi chạy thử xong các điểm chưa kiểm chứng); **Q9** hạn mức 50 người/ngày rải từ sáng đến tối, chỉnh được trên giao diện (có trần cứng cấu hình, mặc định 100); **Q10** chấp nhận mất tài khoản phụ nếu bị khóa; mẹ hoặc chủ shop quét QR khi phiên hết hạn, ngay trên trang quản trị bằng tài khoản admin. Xem `docs/ADR-0002-bo-noi-zalo-khong-chinh-thuc.md` mục 8.
10. **Từ nghiên cứu PR #25 (đã đóng), nếu chủ shop muốn bớt rủi ro**: (a) đổi bước "thêm khách vào nhóm" thành "gửi link nhóm cho khách tự bấm" (đường này **đã code theo D17**; khách tự đồng ý rõ ràng, ít bị báo cáo, nhưng thêm một bước cho khách); (b) chỉ mời khách khi đơn đã "Giao thành công" vài ngày; (c) khi có hộ kinh doanh và OA xác thực, thử tin hậu mãi ZBS có nút vào nhóm. Các điều khoản và điều kiện OA/ZBS trong bản nghiên cứu đó chưa đọc được từ tài liệu gốc của Zalo, cần đối chiếu.
11. **Máy chủ và deploy (người dùng hoãn, 22/11/2026):** chờ người dùng cho địa chỉ máy 2 GB RAM (chưa có địa chỉ nào được xác nhận để dùng). Cho tới khi có địa chỉ, đừng deploy hay đoán tên máy.
12. **Hạn trả lời yêu cầu dữ liệu của khách theo loại** (hiện 15 ngày cho mọi loại, chưa xác nhận pháp lý).

---

## 7. Việc tiếp theo, theo thứ tự ưu tiên (cập nhật 22/11/2026, sau PR #38)

Đã code xong và gộp: giao diện Penpot, bộ nhập J&T, bản đồ, phân vùng, D13, D15 (đường bán tự động), D16 (bộ nối Zalo, tắt mặc định), D17 (gửi link nhóm, các việc nhỏ), CI dựng ảnh cầu nối. **Không còn việc 1I nào "còn phải code, không chờ ai quyết".** Còn lại, theo thứ tự người dùng muốn:

1. **Báo việc cho mẹ qua Zalo (chưa code).** Hiện "Việc cần mẹ xử lý" chỉ hiện trên web, mẹ không mở thì bỏ sót. Thiết kế đề xuất ở `docs/ADR-0001-chon-kenh-bot.md` mục "Báo việc cho mẹ qua Zalo": trợ lý nhắc mẹ trong chính nhóm nơi khách hỏi, tối đa một lần mỗi việc, điểm cắm là sau `HandoffService.open(...)` qua `BotChannel.send`. **Phụ thuộc việc 2** (chưa có kênh gửi nào để cắm vào; viết trước chỉ thêm mã chết). Đường "gửi tin riêng cho mẹ" khác ranh giới của bộ nối (ADR-0002 mục 4), cần quyết định riêng của người dùng.
2. **Chọn cách nối trợ lý trong nhóm Zalo** (kênh đã chọn là Zalo, D17): P1 Zalo OA/GMF, P2 `zca-js` bằng tài khoản riêng, P3 Zalo Bot chính thức. Phải chạy các spike ở bảng trong ADR-0001 (P1-1 đến P1-4, P3-1, P2-1; hiện **đều ghi "CHƯA CHẠY"**) rồi người dùng quyết. Lõi bot (`BotEngine`, `BotChannel`) đã có, adapter kênh thật chưa. Nhớ: mẹ là trưởng nhóm (D2); P1 làm lệch BR-08/BR-11 nên người dùng quyết, không tự đổi nghiệp vụ.
3. **Deploy khi người dùng cho địa chỉ máy 2 GB RAM** (người dùng hoãn đến lúc đó, D17). Dùng `infra/deploy.sh` và `infra/docker-compose.shared.yml` (ảnh ghcr đã dựng sẵn bằng CI) theo `docs/RUNBOOK.md`; khi đó đổi compose của cầu nối sang kéo ảnh dựng sẵn thay vì `build:` để khỏi build Node trên máy 2 GB; đặt `APP_PUBLIC_BASE_URL` thật trước khi in giấy; thử sao lưu và khôi phục. Chưa deploy được thì không làm.
4. **Thử thật bộ nối Zalo** theo `docs/HUONG-DAN-THU-ZALO-TU-DONG.md`, từng nấc (Chỉ nghe → Mẹ duyệt từng người → nhiều tuần mới AUTO), với vài người tự nguyện, bằng tài khoản phụ (không dùng tài khoản của mẹ). Cần máy có Docker và một tài khoản phụ; phụ thuộc việc 3 nếu chạy trên máy chủ. Sau lần thử đầu: thêm mã lỗi Zalo đã thấy và chắc chắn là lỗi theo từng người vào `ZALO_PERSON_ERROR_CODES` (ADR-0002 mục 10 điểm 5), điền `ZALO_ACCOUNT_ID` và mã nhóm Zalo; cập nhật bảng "những điều chưa ai kiểm" cuối hướng dẫn và mục 5 ở đây.

**Còn phải code nhưng chờ quyết hoặc chờ dữ liệu:** hạn trả lời yêu cầu dữ liệu theo loại (cần xác nhận pháp lý); bộ gửi SMS/ZBS (D9, đang hoãn, đừng viết khi chưa có quyết; **riêng việc tự gửi tin Zalo bằng tài khoản phụ đã được D18 chốt ngày 23/11/2026, phần mã do luồng Zalo khác làm**); cổng nạp chung `ShipmentIngest` (chưa có nguồn thứ hai); giai đoạn 2 trong file 03 (tài khoản nhân viên 2B, nhiệm vụ theo nhóm 2C, ghi đơn thủ công 2D, báo cáo 2E).

**Không phải code, cần kiểm thật hoặc làm ngoài code:**
- Chạy bộ nhập J&T với một file J&T thật đã che thông tin (kiểm cột "Mã vận đơn" 12 chữ số và số 0 đầu của SĐT); bật thử thời tiết (`APP_WEATHER_ENABLED`) với mạng thật.
- Mở giao diện bằng trình duyệt thật (cỡ chữ, thanh bên); in thử một tờ giấy A5 và quét mã QR bằng điện thoại, sau khi có tên miền thật.
- Xác nhận VPS (nếu đã dựng) đang chạy gì; sao lưu và khôi phục trên máy chủ thật.
- Mốc 0: đăng ký hộ kinh doanh (D1), hỏi pháp lý/kế toán về cơ sở liên hệ, hỏi eSMS/SpeedSMS khi muốn làm lại SMS.

---

## 8. Đồng bộ đơn mới từ J&T (mục 2F file 03)

**Người dùng đã chọn phương án A: nhập file tăng dần.** Người dùng muốn hệ thống **mỗi ngày tự động xuất file đơn từ J&T rồi nhập vào**, thay vì mẹ tải tay. Hệ thống **không** tự đăng nhập J&T; phần đã có: bộ nhập J&T (PR #9), báo "cần xuất từ ngày nào" (PR #13), và `tools/jt-edge-sync` (PR #22) để Claude trong Edge hoặc tool Node chạy trên máy người dùng tải file rồi nhập (người dùng vẫn đăng nhập J&T và adh).

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
- **Đã làm (V23, `SettlementImportService`, trang `/imports/settlement`):** nhập file "Lịch sử đối soát" (16 cột, tiêu đề dòng 3, mỗi file một kỳ, dòng "Tổng" cuối) theo TÊN cột; lưu mã vận đơn, mã đơn KH, ký nhận, tiền vào `settlement_period`/`settlement_line` (không lưu tên, SĐT, địa chỉ); chỉ bổ sung `order_ref` và `delivered_on` (đơn đã Giao thành công) cho đơn có sẵn, không đổi trạng thái; báo đơn ký nhận mà Adh chưa tính Giao thành công; nhập lại kỳ thì thay kỳ cũ; xóa khách thì xóa dòng đối soát của khách. Còn lại: trạng thái "Kiện vấn đề" vẫn cố ý để chủ shop chọn nghĩa ở màn Trạng thái đơn. Cảnh báo khi một file J&T đúng 500 dòng (có thể bị cắt) **đã làm** (PR #36).

## 9. Giao diện Penpot đã duyệt (đã code, chưa xem bằng mắt)

> Cập nhật: các mục (a)–(e) của mục 7.1 đã code. Còn thiếu: cách báo việc cho mẹ (đã chọn Zalo, chưa code; không làm hai tài khoản Bố/Mẹ theo D17), xem thử bằng trình duyệt thật và in thử giấy A5. Giấy in mặc định A5; chữ mặc định theo kiểu "anh/chị" (đổi được ở trang In giấy giới thiệu).

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

### Làm lại giao diện quản trị (23/11/2026)

Chi tiết, danh sách màn giữ/gộp/bỏ và việc còn dở: [`GIAO-DIEN-LAM-LAI.md`](GIAO-DIEN-LAM-LAI.md). Tóm tắt: PR #45 (điện thoại: menu thu gọn, bảng thành thẻ, hướng dẫn gập) và #46 (gộp mục Khách hàng) đã gộp. **Giữ `/test-runs` tới khi chủ shop chạy chiến dịch thật rồi mới xóa.** Màn nhắn tay (Lô nhắn hôm nay, Chờ vào nhóm, các nút Đã nhắn/Đã kết bạn/Đã vào nhóm) bỏ hẳn **sau khi** phần tự gửi (D18) gộp; chữ "không tự gửi tin" còn ở nhiều mẫu trang và lỗi thời.

---

## 10. Quy tắc làm việc với dự án (giữ nguyên)

- Trả lời người dùng bằng **tiếng Việt**; giao diện 100% tiếng Việt.
- **Mặc định không liên hệ**; mỗi khách được mời **một lần**; sổ từ chối chỉ lưu mã băm; không SĐT đầy đủ trong log; không đưa dữ liệu khách ra dịch vụ ngoài (thời tiết chỉ nhận tọa độ).
- Gửi thật, kênh bot thật, thời tiết: **tắt mặc định**, chỉ chủ shop bật.
- **Không làm thủ thuật né phát hiện** của Zalo (giả lập thao tác hay độ trễ ngẫu nhiên cho giống người, đổi dấu vân tay/`imei`/`userAgent`, proxy xoay, xoay vòng tài khoản); **không gửi tin hàng loạt tự động**; không dùng tài khoản Zalo của bố mẹ (chỉ tài khoản phụ). Nếu ai yêu cầu những việc này, từ chối và báo người dùng. Bộ nối `zalo-bridge/` chỉ được có ba việc (trạng thái, đọc sự kiện kết bạn, thêm một người vào một nhóm) và `ZaloGatewayShapeTest` khóa điều đó: đừng thêm hàm gửi tin hay gửi lời mời.
- **Phần Zalo: review kỹ từng PR** (đọc diff, chạy test, tìm lỗi, ghi kết quả vào mô tả PR) trước khi gộp. Bộ nối và nấc `AUTO` mặc định tắt; muốn đổi mặc định phải có người dùng đồng ý bằng lời.
- Không ghi bí mật (mật khẩu, token) vào repo, log hay bộ nhớ chung.
- Thay đổi mới: PR mới từ `main`, gộp khi CI xanh, báo lại bằng tiếng Việt. Không gộp PR chưa xong; việc dở thì để PR nháp ghi rõ phần đã xong và phần còn dở.
