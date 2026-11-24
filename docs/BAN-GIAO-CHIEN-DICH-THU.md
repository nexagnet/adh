# Bàn giao cho AI làm tiếp: chạy Chiến dịch thử và những gì phải biết trước khi đụng vào repo

Viết ngày 23/11/2026 (sau PR #89, `main` ở commit gộp #89). Dành cho AI hoặc người vào làm tiếp mà **không đọc được lịch sử trò chuyện**. Chủ dự án (Phùng Việt) nói: "bàn giao mọi thứ lên repo, để AI khác làm Chiến dịch thử". File này là **cửa vào duy nhất** cho việc đó; nó trỏ sang các file chi tiết, không chép lại hết.

**Thứ tự đọc:** (1) `CLAUDE.md` (quy tắc, quyết định D18 đến D21, máy chủ); (2) file này; (3) `docs/HUONG-DAN-CHAY-THU-TOAN-DIEN.md` (hướng dẫn từng bước cho chủ shop); (4) `docs/BAN-GIAO.md` mục 0 (nhất là 0.8, luồng D21). Khi hai nơi mâu thuẫn: `CLAUDE.md` thắng, rồi tới file này, rồi `BAN-GIAO.md` mục 0. Mục 1 đến 10 của `BAN-GIAO.md` đã cũ.

Trả lời chủ dự án **bằng tiếng Việt, ngắn gọn, dễ hiểu cho người không rành kỹ thuật**; giao diện web cũng 100% tiếng Việt ("trợ lý AI", không dùng chữ "bot").

---

## 1. "Làm chiến dịch thử" nghĩa là gì (đọc kỹ, dễ hiểu nhầm)

- **Màn Chiến dịch thử đã XÓA (24/11/2026, V45).** Chủ dự án chốt: "làm đúng pilot: xóa toàn bộ giao diện và logic chiến dịch thử, làm thật trên 4 số tôi cung cấp". "Làm chiến dịch thử" nay nghĩa là chạy **chiến dịch thật ở chế độ thử** (chỉ các số đã khai báo ở "Chế độ thử" được chạm), qua luồng thật: Tin 1, tài khoản phụ **tự gửi lời mời kết bạn**, tự thêm vào nhóm, tự tạo nhóm. Việc còn lại là **chạy trên Zalo thật với các số thử**, đọc kết quả và sửa lỗi họ báo.
- **Một AI chạy trên đám mây không tự chạy được.** AI không có tài khoản Zalo, không SSH được vào máy chủ (khóa không nằm ở đâu cả, root bị cấm), không có điện thoại để kết bạn. Người làm các bước thật là **chủ dự án** (bấm công tắc trên web, cầm điện thoại các số thử, chạy lệnh trên máy chủ hoặc nhờ người SSH). Vai trò của bạn là: **chuẩn bị, hướng dẫn từng bước nhỏ, đọc kết quả họ dán lại, tìm nguyên nhân, sửa mã bằng PR nhỏ, và cuối cùng dọn**. Một phiên Remote Control trên máy của người dùng từng chỉ nhận lệnh khi chính người dùng nhắn trực tiếp trong thread, không nhận qua điều phối.
- **Phép thử nay đi đúng luồng thật** (khách thử là khách trong hệ thống, dựng bằng nút "Dựng khách thử"), nên thử được cả phần AI (nhận diện từ chối, trả lời, khách quay lại, tự nhận khách mới, tin nhắc, AI đề xuất chiến dịch) ở các bước sau (mục 5).
- **Chưa thứ gì ở giai đoạn 1 từng chạy với Zalo thật hay DeepSeek thật.** Mọi thứ tự động đều tắt mặc định. Đừng nói "đã chạy được" cho tới khi chủ dự án báo kết quả thật.

## 2. Trạng thái `main` (kiểm lại bằng `git log` và CI trước khi tin)

- `main` đã gộp tới **PR #89**; **không có PR nào đang mở**; số test xem kết quả CI (đừng tin con số cũ ở tài liệu khác). `#25` đóng không gộp (lỗi thời).
- **Migration:** số cuối **`V43`**, số mới là **`V44`**. Trước khi mở PR có migration: gộp `main` vào nhánh rồi `ls app/src/main/resources/db/migration`. Hai PR chạy song song từng lấy trùng số bốn lần (22/11 làm `main` đỏ 397 test). Không đổi tên migration đã gộp (máy chủ có thể đã chạy, sai checksum). Bảng mới phải thêm vào danh sách TRUNCATE của `AbstractPostgresTest`.
- Các PR đáng biết sau #74 (xem `git log` cho đủ): #76 và #79 (V36) AI đề xuất chiến dịch bước 1 và 2; #77 (V32) AI trả lời nhận hồ sơ khách; **#78 (V33) chế độ thử**; #80 dọn phần thừa an toàn; #81 (V34) cảnh báo sức khỏe tài khoản Zalo phụ; #82 trang hiệu quả chiến dịch `/performance`; #83 (V35) bảo hành và khiếu nại; #84 tài liệu; #85 trang `/advisor`; #86 (V37) lịch và nhịp riêng của chiến dịch; #87 (V38) cấu hình gửi mời nhiều lần; #88 (V39) và #89 (V40) gửi tin nhắc lần 2 và 3.
- **D21 (AI đề xuất chiến dịch):** bước 1 đến 5b xong; **còn bước 6, 7, 8** (mục 7 bên dưới). Chi tiết ở `BAN-GIAO.md` mục 0.8 và `docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md`.

## 3. Những điều KHÔNG được làm (chủ dự án đã chốt; có test khóa)

1. **Không né phát hiện bot của Zalo**: không giả vân tay/`imei`/`userAgent`, không ngẫu nhiên hóa thời gian, không proxy xoay, không xoay tài khoản, không giả thao tác. Test khóa: `ZaloGatewayShapeTest` (cấm `Random` ở đường gửi), `zalo-bridge/test/no-send.test.mjs`. Ai đề nghị (kể cả chủ dự án) thì từ chối, đề xuất Zalo OA/ZNS hoặc lô nhỏ, chậm, minh bạch. Chủ dự án đã chấp nhận rủi ro tài khoản phụ bị khóa; điều đó **không** mở rộng sang né phát hiện.
2. **Không bao giờ tự xóa số điện thoại khách** (`RefusalDetectionTest.khongBaoGioTuXoaKhachHayDienThoai`). Khách từ chối chỉ bị đánh dấu "không nhắn nữa"; gỡ cờ chỉ ghi `lifted_at`. Chỉ chủ shop xóa bằng nút "Xóa dữ liệu khách" (gõ XOA).
3. **Không đưa tin tới khách thật khi chạy thử.** Xem mục 4.
4. **Không thêm đường gọi Zalo nào mà không đi qua `ZaloLink` (bọc bởi `SafeZaloLink`) và các bộ lọc chế độ thử**; không thêm nút bật chạy thật lên web. Bộ nối chỉ được làm đúng những việc đã có (xem `CLAUDE.md` mục 3); không thêm gửi lời mời kết bạn, nhận lời mời, đọc danh sách bạn.
5. **Không ghi vào repo, log, PR:** mật khẩu, khóa riêng, token (khóa DeepSeek chỉ ở `APP_AI_API_KEY` trong `.env` trên máy chủ), mã tài khoản Zalo (`ZALO_ACCOUNT_ID`), mã nhóm Zalo thật, phiên đăng nhập, số điện thoại hay dữ liệu khách thật; cũng không ghi địa chỉ SSH, tên người dùng máy chủ ngoài những gì `CLAUDE.md` đã có. CI chặn file Excel, `data_product/` và `.env`; đừng tìm cách lách. Dữ liệu thử chỉ dùng `fixtures/` (giả).
6. **Không đụng repo `nexagnet/.github`** (bản nháp của người dùng) và PR của người khác.
7. **Không code giai đoạn 2** (AI trả lời trong nhóm, Chatwoot, đội bán hàng, tra cứu nhiều sản phẩm, Redis, LangChain4j) khi chưa có lời duyệt mới. Giai đoạn 1 chạy trên máy 2 GB RAM, phải miễn phí.
8. **Việc không đảo ngược hoặc đụng máy chủ thật** (deploy tay, xóa dữ liệu, đổi mật khẩu, đổi `.env`, tạo lại container) thì **hỏi người dùng trước**.

## 4. Dữ liệu thật trên máy chủ và Chế độ thử (quan trọng nhất)

- Cơ sở dữ liệu trên máy chủ chứa **khách thật** (khoảng 636 khách, trong đó khoảng **335 đã được duyệt**). Chủ dự án dặn **tuyệt đối không nhắn khách thật khi chạy thử**, chỉ nhắn các số họ chuẩn bị riêng.
- Vì vậy từ 23/11/2026 (V33, PR #78) hệ thống **luôn ở chế độ thử** trừ khi `APP_ZALO_LIVE=true` trong `.env` trên máy chủ. Không có nút web để bật chạy thật, cố ý. Ở chế độ thử chỉ các số khai báo ở `/zalo` mục "Chế độ thử" (bảng `zalo_test_number`) được nhắn, thêm nhóm, tạo nhóm, tự nhận, AI đọc hoặc trả lời, **kể cả khi công tắc tự gửi đang bật và 335 khách đã duyệt sẵn**. Ba lớp: `ContactService` và các hàng chờ lọc bằng `TestMode.onlyTestSql`; `ContactService.mayContact` ở chỗ sắp gọi Zalo; `SafeZaloLink` ở bean `ZaloLink` duy nhất. Test: `TestModeTest`, `FollowupTestModeTest`.
- **Điều cấm cụ thể:** không đặt `APP_ZALO_LIVE=true` lúc thử; **không duyệt, không tạo, không bấm "Duyệt cho hệ thống tự gửi" một chiến dịch thường** (không phải Chiến dịch thử) cho đến khi chủ dự án nói đã thử xong và muốn nhắn khách thật. Khi họ quyết: **tạm dừng tất cả chiến dịch trước**, đặt `APP_ZALO_LIVE=true`, tạo lại container app, rồi duyệt lại từng chiến dịch (`HUONG-DAN-CHAY-THU-TOAN-DIEN.md` mục 7).
- Nếu `/contacts` hiện "Đủ điều kiện mời" nhiều hơn số số thử của chủ dự án thì **dừng và báo**: chế độ thử có thể đang tắt.
- **Thứ tự bắt buộc:** khai báo số thử TRƯỚC, nhập đơn giả SAU. Số đã là khách hoặc nằm trong sổ từ chối bị từ chối khi khai báo. **Mỗi số điện thoại chỉ nhận Tin 1 một lần trong đời** (cầu nối nhớ trong `messages.json`); chia số thành Nhóm A (Chiến dịch thử, 2 đến 3 số) và Nhóm B (khách giả, từ 5 số), không dùng lẫn. Số đã "Xóa dữ liệu khách" vào danh sách chặn vĩnh viễn nên không dùng lại làm số thử.
- Không nhập file J&T thật và không chạy tool Edge trong lúc thử, để số liệu không lẫn.

## 5. Thứ tự việc nên làm khi chạy thử

Mỗi bước nói rõ **ai làm**. Dừng ngay ở bước nào thấy lạ; đừng đi tiếp để "cho xong".

**Bước 0: AI kiểm repo trước (không cần chủ dự án)**
1. Đọc `CLAUDE.md`, file này, `HUONG-DAN-CHAY-THU-TOAN-DIEN.md`, rồi `docs/HUONG-DAN-THU-ZALO-TU-DONG.md` (đăng nhập QR, gán mã nhóm, mục 3 khi bị cảnh báo hoặc khóa).
2. `git fetch`, kiểm CI của `main` xanh; chạy test mục tiêu nếu cần (mục 8).
3. Soạn cho chủ dự án một **danh sách kiểm ngắn** (mục 8 của hướng dẫn chạy thử): số thử đã khai báo, tài khoản phụ đang nghe, nhóm thử đã gán mã, `APP_SHOP_NAME` đã đặt, không ai đang nhập J&T thật.

**Bước 1: chủ dự án (hoặc người SSH) chuẩn bị máy chủ** (AI chỉ hướng dẫn, không tự làm)
- Máy chủ phải có **bản compose mới**: `~/adh/infra/docker-compose.shared.yml` trên máy chủ **chậm so với `main`** vì cron chỉ kéo ảnh Docker (mục 6). Cần kiểm có dòng `APP_ZALO_LIVE: ${APP_ZALO_LIVE:-false}` và trần `APP_ZALO_AUTO_MSG_MAX_DAILY` 50. Nếu thiếu, cần cập nhật thư mục (hỏi chủ dự án trước khi sửa cron hay tạo lại container).
- Đặt `APP_SHOP_NAME` (đang là "Cửa hàng", vào Tin 1 và chữ ký "Trợ lý AI của ...").
- Cờ AI và đọc tin riêng (theo báo cáo 23/11 đã bật cho bộ nối, cờ đọc tin riêng và cờ AI với `deepseek-flash`; **kiểm lại**, vì chưa có người xác nhận).
- **Không tạo lại container bộ nối trước khi đọc log** (log cũ mất theo container).

**Bước 2: Giai đoạn A, chiến dịch thật cho 2 đến 3 số thử**: làm đúng `HUONG-DAN-CHAY-THU-TOAN-DIEN.md` mục 4, giai đoạn A. Tóm tắt: nấc "Tự thêm trong hạn mức"; bật tự gửi với hạn mức 5 khách/ngày và nghỉ 120 giây; khai báo số thử, Dựng khách thử, duyệt chiến dịch, chỉ trong 08:00 đến 20:00. Kết bạn **tự động** (Tin 1 rồi lời mời kết bạn, trần mặc định 10 lời mời/ngày); ghi lại nếu Zalo từ chối lời mời hoặc không phát sự kiện kết bạn.
- **Thu thập (người dùng dán lại, đã che số và nội dung):** nhật ký gần đây ở `/zalo`; nhật ký bộ nối trên máy chủ (không chứa số điện thoại hay nội dung); `findUid`/`sendText` của `zca-adapter.mjs` **chưa từng chạy với Zalo thật**, nên lỗi lạ rất có thể ở đó.
- **Việc AI làm sau lần thử đầu:** (a) điền `ZALO_PERSON_ERROR_CODES` bằng mã lỗi thật chắc chắn là lỗi riêng một người (ví dụ "không tìm thấy"); mã lạ làm ngắt cầu dao, đó là chủ ý; (b) cập nhật các chỗ ghi "chưa kiểm chứng" (`BAN-GIAO.md` mục 1A và 0.x, `ZALO-BRIDGE-API.md`, `ADR-0002`) bằng kết quả thật; (c) sửa lỗi bằng PR nhỏ.
- **Nếu cầu dao nhảy hay Zalo cảnh báo:** dừng, đừng bấm Mở lại liên tục, đừng đăng nhập đi đăng nhập lại, đọc lý do và log trước. Xem mục 5 (khi gặp lỗi) và mục 6 (dừng khẩn cấp từ nhẹ đến mạnh) của hướng dẫn chạy thử.
- Giai đoạn A không êm thì **dừng ở đây**, chưa sang B.

**Bước 3: Giai đoạn B, khách giả như khách thật (Nhóm B, từ 5 số)**: mục 4, giai đoạn B của hướng dẫn chạy thử (nhập vài đơn giả, duyệt, chiến dịch, mỗi số một vai: từ chối rồi quay lại; vào nhóm rồi hỏi ưu đãi; hỏi ngoài phạm vi; xin gặp người thật; im lặng). Thử luôn: tự tạo nhóm (ít nhất 2 khách cùng tỉnh, chủ shop duyệt; đây là lần **thử tay 1 nhóm thật** còn nợ), tự nhận khách mới, rồi mới chuyển nấc AI sang Tự động. **Mọi nấc AI để "Chỉ soạn nháp" / "Chỉ tạo việc" trước**, chạy vài ngày, xem nháp, rồi mới Tự động (có tick xác nhận). Trước đó thử khoảng 30 câu ở `/zalo/reply` "Thử một câu" (không cần Zalo) để bắt lỗi AI và `ReplyValidator`; thử DeepSeek thật vài chục câu tiếng Việt giả **trước** khi bật nấc nào.

**Bước 4: thử tin nhắc lần 2 và 3 (D21 bước 5b), trên số thử** (cách thử đầy đủ ở `BAN-GIAO.md` mục 0.8, "Cách thử tin nhắc")
- Giữ chế độ thử; khai báo hai số thử; bật cả ba cờ đọc tin riêng (`APP_ZALO_INBOX_ENABLED`, `BRIDGE_INBOX_ENABLED`, `APP_AI_ENABLED` kèm khóa và tên mô hình) và công tắc tự gửi.
- Tạo chiến dịch gửi 2 lần ("Số lần gửi mời" ở trang chiến dịch, "Dùng mẫu Tin 1 cho nhiều lần"), tin nhắc lần 2 cách **3 ngày** (sàn cứng). Chờ đủ 3 ngày thật, hoặc thử nhanh **chỉ trên máy dev** bằng cách lùi `invitation.sent_at` của lời mời thử (không làm trên dữ liệu máy chủ).
- Bốn tình huống: (1) để yên: phải nhận tin nhắc đúng hạn, có câu "nhắn dừng"; (2) kết bạn trước hạn: không nhận; (3) nhắn riêng bất kỳ câu nào trước hạn: không nhận; (4) nhắn "dừng": AI ghi từ chối. Xem bảng `invitation_touch` và dòng "Đã gửi: Tin 1, lần 2, lần 3" ở trang chiến dịch. **Chỉ khi đúng cả bốn mới bật cho khách thật.** Tin nhắc chỉ chạy khi Adh chạy ổn định (cầu nối chỉ giữ tin riêng 6 giờ).

**Bước 5: thử AI đề xuất chiến dịch (D21)**: `APP_AI_ADVISOR_ENABLED=true` (cần `APP_AI_ENABLED`, tốt nhất cả `APP_WEATHER_ENABLED`). Mở `/advisor`, bấm "Nhờ AI đề xuất", **trước hết đọc JSON đã gửi ở "Xem số liệu đã gửi cho AI"** để chắc không có gì định danh, đọc lý do, Duyệt một đề xuất (chỉ tạo chiến dịch CHƯA duyệt tự gửi). Khuôn JSON có thể lệch với DeepSeek thật: lượt chạy ghi `INVALID` kèm lý do, không hỏng gì khác.

**Bước 6: dọn dẹp, chỉ khi chủ dự án báo "thử xong"**: mục 6 bên dưới.

## 6. Việc dọn sau khi thử xong (một PR, đừng làm sớm)

Chỉ làm khi chủ dự án **báo thử xong**. Một PR, review backend kỹ, ghi vào mô tả PR. Danh sách chính thức ở `HUONG-DAN-CHAY-THU-TOAN-DIEN.md` mục 7 và `docs/GIAO-DIEN-LAM-LAI.md`; dưới đây là các chỗ đã **soát lại ngày 23/11/2026** (một số chỗ ở tài liệu cũ chưa đúng):

1. **Chiến dịch thử: ĐÃ XÓA (24/11/2026, V45; `TestMode` đã bỏ truy vấn `test_target`).**
2. **Bảng:** `test_run` và `test_target` được **tạo ở V14** (`V14__campaign_scope_test_run.sql`); V22 chỉ **thêm cột** vào `test_run`. Migration mới (số kế tiếp, lúc viết là `V44`) phải `DROP TABLE test_target, test_run`. Bỏ hai tên bảng khỏi danh sách TRUNCATE ở `AbstractPostgresTest`.
3. **Bẫy:** `app/src/main/java/vn/shop/crm/safety/TestMode.java` (khoảng dòng 122) còn truy vấn `FROM test_target`. **Phải sửa trước khi bỏ bảng**, nếu không chế độ thử sẽ lỗi lúc chạy. Chạy `grep -rn "test_target\|test_run\|testrun" app/src` để bắt hết.
4. **Màn nhắn tay:** "Lô nhắn hôm nay" (`/today`, `TodayController`, `today.html`, `TodayQueueTest`), "Chờ vào nhóm" (`/friends`, `FriendsController`, `friends.html`, `FriendQueueTest`, `FriendFlowTest`), mục menu của hai màn này, mục nhắn tay ở khối "Việc cần làm hôm nay" của Tổng quan; các nút Đã nhắn, Đã kết bạn, Đã vào nhóm, Đã gửi link nhóm, Khách đã rời nhóm, Khách từ chối bấm tay (`POST /campaigns/{id}/invitations/{id}/...`), "Xuất danh sách để mẹ gửi tay"; xem lại nút "Chạy thử (không gửi gì)" (`/campaigns/{id}/dry-run`). Dọn chữ cũ "mẹ gửi tay, hệ thống không tự gửi" (ví dụ chú thích Tin 2 trong `InviteService`).
5. **Giữ:** `/zalo`, `/zalo/reply`, `/handoffs`, `/campaigns`, `/contacts`, `/groups` và gán mã nhóm `/zalo/groups` (cố ý hai trang liên kết qua lại), `/customers`, `/privacy`, `/print`, `/map`, `/bot`, `/advisor`, `/performance`.
6. Sau khi gộp: cập nhật `CLAUDE.md` mục 3, 4a, 7 (bỏ câu "đừng xóa sớm", số migration), `BAN-GIAO.md` mục 0.6, và file này.
7. **Dữ liệu giả:** hệ thống không bao giờ tự xóa số, nên chủ dự án tự xóa từng khách giả bằng "Xóa dữ liệu khách" (gõ XOA). Số đã xóa vào danh sách chặn vĩnh viễn.

## 7. Việc còn dở và câu hỏi chờ chủ dự án

**Mã chưa làm (D21):**
- **PR 4 phần còn dở** (PR nhỏ riêng): phạm vi khách mở rộng (nhiều khu vực, độ mới lần mua), tăng dần theo ngày bằng bậc thang cố định, thử A/B. (**Đã làm 23/11, V41:** ngày trong tuần được gửi, hoãn khi mưa lớn, tự dừng khi khách từ chối từ 5% sau 20 tin: xem `docs/THIET-KE-AI-DE-XUAT-CHIEN-DICH.md` mục 6, PR 4.) Mỗi núm mới đi qua `CampaignPolicy.effective`/`blocker` và **chỉ làm chặt hơn** trần hệ thống.
- **PR 6 (đã làm một phần, V42):** nhóm đích riêng của chiến dịch (`CampaignGroupTarget`): "chỉ gửi link nhóm", tắt gom vào nhóm tự tạo, Tin 2 riêng. **Chưa làm:** ghim một nhóm cố định cho chiến dịch và mẫu tên nhóm riêng (đề xuất tạo nhóm hiện gom khách của mọi chiến dịch theo khu vực, nên cần thiết kế thêm). **PR 7 (đã làm, V43):** nấc khách quay lại riêng của chiến dịch (chỉ chặt hơn hệ thống; tự nhận khách mới đã nằm ở trang chiến dịch từ V30).
- **PR 8:** phần gom trang chiến dịch thành 4 tab (`?tab=tong-quan|lich|tin|nhom`) **ĐÃ làm** (chỉ giao diện). Phần còn lại, gỡ cấu hình trùng ở `/zalo`, `/test-runs`, `/today`, **chỉ làm sau khi tự gửi chạy ổn trên thật** và sau khi dọn ở mục 6.
- Đưa `PerformanceService.anonymized()` vào `AdvisorSnapshot` (dùng nhãn phạm vi và số, **không bao giờ tên chiến dịch**).
- Job dọn `advisor_run.snapshot_json` / `ai_output` (bảng lớn dần, chưa có job dọn).

**Chờ chủ dự án quyết (đừng tự quyết):**
- **Cách phân biệt đơn qua nhóm** cho `/performance`: đề xuất ghi mã đơn bán trong nhóm bắt đầu bằng **`NHOM-`** rồi đặt tiền tố `NHOM` ở trang. Chủ dự án **chưa trả lời**; mặc định đang coi mọi đơn sau khi vào nhóm là đơn qua nhóm (ước tính). Doanh thu chỉ có khi đã nhập file đối soát J&T thật; không có đơn TikTok trong hệ thống.
- Ba câu hỏi D21 (`THIET-KE-AI-DE-XUAT-CHIEN-DICH.md` mục 7): nhóm hàng dùng AI phân loại tên hàng hay bảng từ khóa; A/B từ đầu hay chờ có lịch sử; ngưỡng tự dừng khi tỷ lệ từ chối cao (đề xuất 5% sau 20 tin đầu).
- **Sao lưu ra ngoài máy chủ:** **đang hoãn**. Sao lưu hằng ngày 03:17 và trước mỗi lần deploy vẫn chạy, nhưng file nằm cùng máy (`/var/backups/adh/`, chứa dữ liệu khách thật); mất máy là mất hết. `infra/backup.sh` hỗ trợ `BACKUP_REMOTE_CMD` (rclone hoặc scp; `docs/RUNBOOK.md`). Chưa có bằng chứng đã thử khôi phục trên máy chủ thật (`infra/restore-test.sh`). Hỏi chủ dự án chọn nơi lưu.
- **Cron cập nhật cả thư mục `~/adh`** (`git fetch` và `git merge --ff-only origin/main` trước khi kéo ảnh, có kiểm RAM, dừng khi không fast-forward): đề xuất, **chưa làm, hỏi trước**.
- Hỏi pháp lý hộ kinh doanh (D1, D3) trước khi nhắn số lượng lớn; hạn trả lời yêu cầu dữ liệu của khách theo loại (chưa xác nhận pháp lý); SMS/ZBS (đang hoãn).
- Kênh báo "cần người thật" cho mẹ ngoài trang Adh; mẹ trả lời khách từ đâu (Zalo của chính mẹ khác cuộc trò chuyện của khách với tài khoản phụ). Hiện chỉ có một tài khoản đăng nhập (quản trị).
- Tên miền thật, rồi mới in giấy A5 (hiện tên miền tạm, **chưa in**). Thử in một tờ A5 và quét QR bằng điện thoại.
- `--doi-soat` với J&T thật (người dùng đăng nhập J&T trong hồ sơ Edge riêng, chạy một vòng đầy đủ, báo lệch).

## 8. Máy chủ Tencent, cron tự deploy, công tắc (theo báo cáo ngày 23/11/2026; KIỂM LẠI trước khi tin)

- Máy VM-0-12-ubuntu, **2 GB RAM, rất chật** (khoảng 300 MB trống, swap đang dùng). Vào bằng SSH tài khoản `dev` (`sudo -n docker` không hỏi mật khẩu); **root bị cấm SSH**. Ứng dụng ở `127.0.0.1:8081`, Caddy làm HTTPS tại `https://adh.43-134-105-89.sslip.io` (tên miền tạm). Ảnh Docker build bằng GitHub Actions rồi kéo từ `ghcr.io/nexagnet`, **không build trên máy**. Đừng thêm dịch vụ nặng. Việc tối ưu RAM (tắt dịch vụ thừa, chỉnh Java và Postgres, zram, gỡ YunJing) **chưa nằm trong repo**.
- **Cron tự deploy mỗi 10 phút** (`infra/auto-deploy.sh`): chỉ triển khai khi CI của đúng commit `main` xanh, ảnh ghcr có, RAM trống đủ (`deploy.sh` từ chối dưới 250 MB), bản đang chạy khỏe. **Mọi PR gộp vào `main` với CI xanh lên máy chủ trong khoảng 10 phút, cả migration.** Dừng: `./auto-deploy.sh pause` (rồi `resume`); xem `status`, log `infra/auto-deploy.log`; bản hỏng bị chặn, thử lại bằng `unblock`. **Hệ quả:** nếu một PR sửa migration có lỗi, nó lên máy chủ thật; PR có migration phải qua review kỹ, và có thể `pause` trước khi gộp. **Cron chỉ kéo ảnh, không cập nhật `infra/`**: thay đổi compose hay script không tự lên.
- **Sao lưu:** `deploy.sh deploy` sao lưu DB trước mỗi lần cập nhật; cron 03:17 hằng ngày. **Lùi ảnh (`./deploy.sh rollback`) không lùi migration**; muốn quay hẳn phải khôi phục từ file sao lưu bằng tay (`docs/RUNBOOK.md`).
- Mật khẩu quản trị (`admin`) đọc từ `APP_ADMIN_PASSWORD` trong `infra/.env` mỗi lần khởi động; đổi `.env` rồi tạo lại container app. Đã đổi 22/11 và **không ghi lại ở đâu**: hỏi người dùng nếu cần.
- **Công tắc (theo báo cáo, chưa xác nhận):** *đang bật ở máy chủ:* bộ nối Zalo (đã quét QR tài khoản phụ 23/11, `ZALO_ACCOUNT_ID` đã đặt), cờ đọc tin riêng, cờ AI với mô hình `deepseek-flash`. *Tắt hoặc chưa đặt:* `APP_ZALO_LIVE` (đúng, phải để trống lúc thử), `APP_SHOP_NAME` (phải đặt), `APP_AI_ADVISOR_ENABLED`, `APP_ZALO_AUTO_ENABLED` có thể đã bật nhưng **công tắc trên web đều tắt mặc định**: tự gửi Tin 1, các nấc tự động, AI trả lời, khách quay lại, tự nhận khách mới, tự tạo nhóm, bảo hành và khiếu nại, tin nhắc. Chủ dự án bật từng cái khi thử. Xem thực tế ở `/zalo` và `/zalo/reply`. Bảng biến môi trường: `infra/docker-compose.shared.yml`.
- Tin nhắn riêng của khách chỉ được cầu nối giữ 6 giờ, trong bộ nhớ: nếu Adh ngừng chạy quá 6 giờ thì lời xin ngừng có thể không tới được Adh. Vì vậy tin nhắc chỉ nên bật khi Adh chạy ổn định.

## 9. Rủi ro cần biết

1. **Tài khoản Zalo phụ có thể bị khóa** (thư viện `zca-js` không chính thức, trái điều khoản Zalo). Một tài khoản cho tất cả (mời, thêm nhóm, tạo nhóm, trợ lý AI): bị khóa là mất hết. Lớp cảnh báo `ZaloHealthService` chỉ đọc và báo, không dừng gì; việc dừng là của cầu dao.
2. **Chưa thử thật:** `findUid`/`sendText` của `zca-adapter.mjs`, mã lỗi người theo từng người, khuôn JSON và độ dài đầu ra của DeepSeek, đọc tin riêng, tạo nhóm, tin nhắc.
3. **Hai PR song song:** trùng số migration và xung đột danh sách TRUNCATE (đã xảy ra bốn lần).
4. **Migration tự lên máy chủ trong 10 phút** (mục 8); không có migration hạ cấp.
5. **Tin nhắc:** chỉ gửi cho lời mời còn hạn; chiến dịch có `gap_days` dài hơn số ngày link còn hạn thì lần nhắc đó không bao giờ gửi.
6. **Nhầm "chạy thử" với "chạy thật":** mọi thay đổi `.env` hoặc đặt `APP_ZALO_LIVE=true` phải do chủ dự án quyết bằng lời của họ trong thread.

## 10. Cách làm việc trong repo (nhắc nhanh; chi tiết ở `CLAUDE.md` mục 1 và 5)

- Mỗi việc một nhánh `claude/project-thread-*` từ `main`; PR nhỏ, một việc; sau khi gộp thì khởi lại nhánh từ `main` (đẩy sau squash cần `--force-with-lease` với sha cũ của nhánh từ xa, sau khi kiểm `git diff --stat`).
- **Backend phải review kỹ trước mỗi PR** (đúng đắn, an toàn, xử lý lỗi, migration, test) và **ghi kết quả review vào mô tả PR**; phần Zalo càng phải kỹ. Được tự gộp PR của `nexagnet/adh` khi CI xanh, không xung đột, và (nếu đụng backend) đã review kỹ và ghi; PR chỉ tài liệu chỉ cần CI xanh.
- Test Java: `cd app && ./mvnw verify` (CI dùng JDK 25; chỉ có JDK 21 thì thêm `-Djava.version=21 -Dmaven.compiler.release=21`); test dùng PostgreSQL nhúng nên **không chạy bằng root**. Test Node: `cd zalo-bridge && npm ci --ignore-scripts && npm test`. Sau mọi thay đổi mẫu trang chạy lại test (nhiều test kiểm chữ tiếng Việt cụ thể). Khi test thất bại, chạy lại đúng các test lỗi.
- Giao diện 100% tiếng Việt; CSP cấm script/style nội tuyến.
