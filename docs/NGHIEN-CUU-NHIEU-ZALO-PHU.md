# Nghiên cứu: chạy nhiều tài khoản Zalo phụ song song, mẹ vẫn là phó nhóm (25/11/2026)

**Trạng thái:** đã có quyết định của chủ dự án (25/11/2026, mục 0); **chưa sửa mã**, không đổi mục đích PR nào đã gộp. Mọi chỗ ghi "chưa kiểm chứng" là chưa chạy thử với Zalo thật.

## 0. Quyết định của chủ dự án (25/11/2026, D23)

1. Đổi quy tắc: cho phép nhiều tài khoản phụ **cố định** chạy song song (đã ghi ở `CLAUDE.md` mục 3 và ADR-0002 mục 14).
2. Mục tiêu: **cả chia tải, bền hơn lẫn tăng tổng số khách nhắn mỗi ngày.** Mỗi tài khoản giữ hạn mức của nó (không nới), tổng là tổng các tài khoản; trần tổng là một cài đặt ở `/zalo`, người chỉnh.
3. Thu hồi lời mời chờ quá 7 ngày, gửi link nhóm hoặc nhờ kết bạn lại, và nhận lời mời của khách: **giao luồng khác** (PR #156 đã thu hồi; phần còn lại làm ở luồng "Sửa Z223"). Tài liệu này không làm các việc đó.
4. Mẹ đồng ý kết bạn với mọi tài khoản phụ và làm phó nhóm ở mọi nhóm.
5. Có SIM và điện thoại cho tài khoản thứ hai; ban đầu muốn mua thêm máy chủ chạy song song, sau đó báo máy production đã nâng lên **6 GB RAM**, nên máy thứ hai là tùy chọn (mục 3.7).

**Tiến độ:** PR nền (V57: bảng `zalo_account` có một dòng, cột `account_id` mặc định 1 ở `invitation`, `zalo_msg_log`, `zalo_friend_event`, `zalo_group`, `zalo_group_proposal`, `zalo_health_alert`, `zalo_auto_log`; khóa duy nhất của mã Zalo, số thứ tự sự kiện và cảnh báo sức khỏe nay tính theo tài khoản) đã làm; hành vi chưa đổi vì chỉ có một tài khoản. PR định tuyến (`ZaloAccountContext`, `ZaloAccountRoutes`, `ZaloAccountProperties`: mỗi tài khoản một cầu nối bọc lưới an toàn, tài khoản 1 dùng cấu hình cũ, tài khoản thêm khai báo ở `APP_ZALO_ACCOUNTS_n_*`; chưa có bộ hẹn giờ nào đặt tài khoản nên vẫn chỉ tài khoản 1 chạy) đã làm. Chưa làm: bộ hẹn giờ chạy theo từng tài khoản, gán khách theo tài khoản, hạn mức và cầu dao theo tài khoản.

Phần mục 1, 6, 7 dưới đây là bản nghiên cứu gốc; mục 6 bước 0 và mục 7 đã được các quyết định trên trả lời.

## 1. Kết luận ngắn

1. **Quy tắc hiện hành đang cấm.** `CLAUDE.md` mục 3 và `docs/ADR-0002` mục 4 ghi rõ: không "nhiều tài khoản luân phiên", không "đăng ký tài khoản mới để thay thế tự động", không đổi IP/thiết bị/`imei` để Zalo không nhận ra. Muốn làm thì chủ dự án phải ghi một quyết định mới. Tài liệu này chỉ đề xuất dạng **nhiều tài khoản cố định, khai báo công khai, mỗi khách gắn đúng một tài khoản, không luân phiên**, và **không đề xuất bất kỳ cách nào để Zalo không liên kết các tài khoản với nhau**.
2. **Nhiều tài khoản chưa chắc giải quyết được việc hôm nay.** Danh sách lời mời chờ đầy (Z223, khoảng 49 lời mời) là vì khách chưa bấm đồng ý, không phải vì thiếu tài khoản. Thêm tài khoản chỉ dời vấn đề sang tài khoản khác và nhắn thêm nhiều người lạ hơn. Cả cửa hàng chỉ có khoảng 636 khách, mỗi ngày 10 đến 20 Tin 1, nên khối lượng không đòi nhiều tài khoản.
3. **Nên làm trước (Bước 0):** dọn lời mời chờ quá hạn bằng việc thu hồi lời mời. Thư viện `zca-js` 2.3.0 có sẵn `getSentFriendRequest` (đọc danh sách đã gửi, có thời điểm gửi) và `undoFriendRequest` (thu hồi). Hai việc này chưa có trong bộ nối; thêm vào cần chủ dự án duyệt vì test hiện khóa số chỗ gọi Zalo.
4. Nếu vẫn muốn nhiều tài khoản: **một cầu nối cho mỗi tài khoản** (container riêng), Adh thêm một lớp định tuyến, **gắn khách cố định một tài khoản**, hạn mức và cầu dao theo từng tài khoản **cộng một trần tổng**. Mẹ cần kết bạn với từng tài khoản phụ và được đặt phó nhóm ở nhóm của từng tài khoản.
5. Điều kiện cứng: **mã Zalo của một người khác nhau theo từng tài khoản hỏi** (mã Zalo của mẹ đã được gắn dấu tài khoản và bị coi là cũ khi đổi tài khoản phụ; xem `ZaloDeputyService`, trường `ACCT` và `stale()`). Mọi mã người dùng và mã nhóm trong Adh phải đi kèm tài khoản.

## 2. Hiện trạng: những chỗ đang giả định "chỉ một tài khoản"

| Chỗ | Hiện nay |
|---|---|
| Cầu nối (`zalo-bridge/`) | Một container, `ZALO_ACCOUNT_ID` cố định (từ chối đăng nhập nhầm tài khoản), một volume `/data` giữ phiên mã hóa và các sổ (đã gửi, đếm nhóm, đếm lời mời). Giới hạn bộ nhớ 160 MB. |
| Adh nối cầu nối | Một bean `ZaloLink` (`ZaloAutoConfig`), một `APP_ZALO_BRIDGE_URL` và một khóa. `SafeZaloLink` (chế độ thử) bọc bean đó. |
| Cấu hình, hạn mức, cầu dao | `app_setting` dùng chung toàn hệ thống (`ZaloAutoSettings`); một cầu dao (`cfg.trip`). |
| Dữ liệu | `invitation.zalo_uid` duy nhất toàn cục (V17); `zalo_friend_event.bridge_seq` duy nhất toàn cục (mà `seq` do từng cầu nối tự sinh nên hai cầu nối sẽ trùng); `zalo_msg_log`, `zalo_group`, `zalo_group_proposal`, `zalo_health_alert` không có cột tài khoản. |
| Phó nhóm (mẹ) | Một cặp mã và dấu tài khoản trong `app_setting` (`zalo.deputy.*`), hoặc `ZALO_DEPUTY_ID`. |
| Trợ lý AI | Một `GET /inbox`, một con trỏ `seq`; trả lời chỉ theo mã Zalo của người vừa nhắn tới trong 24 giờ. |

Phần tốt: sổ từ chối lưu **mã băm số điện thoại**, không phụ thuộc tài khoản, nên khách từ chối ở tài khoản này không bao giờ bị tài khoản kia nhắn (giữ đúng mục 5a của `CLAUDE.md`).

## 3. Kiến trúc đề xuất

### 3.1 Một cầu nối mỗi tài khoản, không phải một cầu nối nhiều phiên

- Mỗi tài khoản chạy một container cầu nối **giống hệt hôm nay** (cấu hình, test khóa, sổ riêng, volume riêng, khóa chung riêng). Lỗi hay bị khóa ở tài khoản nào chỉ làm hỏng container đó.
- Không gộp thành một cầu nối nhiều phiên: phải viết lại `bridge.mjs` (hơn 1.100 dòng, mọi sổ và cầu dao đang là của một tài khoản), các test khóa hiện có sẽ phải làm lại, một sự cố làm sập tất cả.
- Adh có bảng `zalo_account` (nhãn, địa chỉ cầu nối nội bộ, dấu tài khoản `accountTag` đã có sẵn ở `/status`, bật/tắt). **Khóa chung nằm ở `.env`, không vào cơ sở dữ liệu.** `ZaloLinkRouter` thay bean `ZaloLink` hiện nay: mọi lời gọi mang `accountId` và đi qua `SafeZaloLink` như cũ (chế độ thử vẫn nằm ở lớp này, không đường nào tới Zalo mà bỏ qua nó).
- Nếu chỉ có một dòng trong `zalo_account`, mọi thứ chạy y như hiện nay.

### 3.2 Chia khách: gắn cố định, không luân phiên

- Lúc khách được đưa vào gửi Tin 1, gán `invitation.account_id` cho tài khoản **còn nhiều chỗ trống nhất** trong hạn mức ngày (và còn dưới mức lời mời chờ cho phép), rồi **đứng yên**. Tin 1, lời mời kết bạn, tin nhờ kết bạn lại, thêm vào nhóm, trả lời AI đều đi bằng đúng tài khoản đó.
- Khách đã được một tài khoản nhắn thì **không bao giờ** tài khoản khác nhắn (khóa ở mức khách, không chỉ mức mã Zalo). Không chuyển khách giữa các tài khoản, kể cả khi một tài khoản bị khóa: khách đó chuyển sang hàng "cần người xử lý", người quyết định.
- Chia theo **chiến dịch hoặc khu vực** cũng được (ví dụ tài khoản 1 lo miền Bắc), dễ hiểu cho mẹ hơn, nhưng dễ lệch tải; để người chọn ở bước sau.

### 3.3 Hạn mức và cầu dao

- Hạn mức mỗi ngày (khách được nhắn Tin 1, lời mời kết bạn, thêm nhóm theo giờ) và khoảng nghỉ cố định **tính riêng từng tài khoản**, giữ nguyên các giá trị chặt hiện nay. Mặc định tài khoản mới rất thấp (ví dụ 3 đến 5 khách mỗi ngày), tăng tay khi thấy khỏe.
- Thêm **trần tổng** cho cả hệ thống. Khuyến nghị đặt trần tổng bằng trần hiện nay (cứng 50 khách mỗi ngày): nhiều tài khoản để **chia tải và bền hơn**, không để nhắn nhiều người lạ hơn. Chỉ nâng khi chủ dự án quyết riêng (câu hỏi 2).
- Cầu dao riêng từng tài khoản cho lỗi riêng. Riêng cảnh báo "Zalo hạn chế/xác minh/đá phiên" thì **dừng chung tất cả** rồi người xem, vì có thể cùng nguyên nhân.
- Cảnh báo sức khỏe (`zalo_health_alert`), lời mời chờ đầy (Z223), sổ `zalo_msg_log` đều theo tài khoản.

### 3.4 Mẹ làm phó nhóm ở mọi nhóm

- **Nhóm thuộc về tài khoản đã kết bạn với khách** (chủ nhóm là tài khoản đó): thêm cột `zalo_group.account_id` (mã nhóm Zalo cũng chỉ có nghĩa trong tài khoản đó). Thứ tự đặt tên nhóm vẫn đếm chung toàn hệ thống nên không trùng tên.
- Mẹ dùng **một Zalo thật duy nhất**, và phải **là bạn của từng tài khoản phụ** (làm một lần: mẹ bấm đồng ý lời mời của mỗi tài khoản).
- Mã Zalo của mẹ **khác nhau ở mỗi tài khoản phụ**, nên thay cặp `zalo.deputy.*` bằng bảng `zalo_account_deputy` (tài khoản, mã mẹ, tên, ảnh, dấu tài khoản). Luồng tra bằng số điện thoại và xác nhận tên/ảnh ở `/zalo` đã có, chỉ cần chạy một lần cho mỗi tài khoản (cầu nối chỉ cho 5 lần tra mỗi giờ, đủ).
- Phó nhóm vẫn đặt ngay lúc tạo nhóm như hôm nay (mẹ vào nhóm trước rồi mới đặt). Nút "Thử lại phó nhóm" thay đổi theo tài khoản của nhóm.
- Khi một tài khoản bị khóa: nhóm của nó còn nếu còn thành viên và mẹ vẫn là phó nhóm, nhưng không còn ai thêm khách mới vào đó. **Chuyển quyền trưởng nhóm cho mẹ là việc làm tay trên điện thoại** (bộ nối không đổi chủ nhóm, ADR-0002 mục 4). Nên làm trước ở các nhóm đã có.
- Chưa kiểm chứng: có cần mẹ là bạn của tài khoản phụ thì mới đặt được phó nhóm không (thiết kế tạo nhóm đã ghi điều kiện này); thử với 1 nhóm thật.

### 3.5 Ai trả lời khi khách nhắn riêng

- **Tài khoản mà khách nhắn tới là tài khoản trả lời**: mã Zalo của khách chỉ có nghĩa trong tài khoản đó, và cầu nối chỉ cho trả lời người vừa nhắn tới trong 24 giờ. Giữ nguyên chốt này.
- Adh đọc `/inbox` của từng cầu nối, mỗi tài khoản một con trỏ `seq`. Bản nháp trả lời, việc "cần người thật", việc từ chối chờ xem đều ghi kèm `account_id` để người thật biết khách đang ở tài khoản nào.
- Trợ lý vẫn tự nhận là **trợ lý AI của shop**, một câu ngắn như hiện nay. Tên và ảnh của từng tài khoản phụ nên nói thật là tài khoản của shop (ví dụ "Shop áo điều hòa Minh Hiệp - hỗ trợ 2"), không giả là người.
- Chưa chốt từ trước (mục 4 của `CLAUDE.md`): mẹ trả lời khách từ đâu. Với nhiều tài khoản, hàng "cần người thật" phải ghi rõ tài khoản.

### 3.7 Máy chủ thứ hai (tùy chọn; chủ dự án sẵn sàng mua thêm, nhưng máy hiện đã 6 GB nên chưa bắt buộc)

- Cầu nối của mỗi tài khoản chạy trong container riêng, nên đặt được ở máy nào cũng được; Adh chỉ cần địa chỉ và khóa chung của từng cầu nối trong `zalo_account` và `.env`. Hôm nay địa chỉ là `http://zalo-bridge:3100` trong mạng Docker nội bộ; máy thứ hai cần địa chỉ nối từ máy chủ Adh.
- Nối qua **mạng riêng có mã hóa giữa hai máy** (ví dụ một đường hầm riêng) hoặc HTTPS có khóa chung và danh sách IP được phép; **không mở cổng cầu nối ra internet công khai** (cầu nối giữ phiên đăng nhập Zalo, ai chạm được là chiếm tài khoản). Việc dựng đường nối là việc hạ tầng, làm khi tới Bước 6.
- Mỗi máy chạy cầu nối có sao lưu thư mục `/data` (phiên mã hóa và các sổ) và khóa `SESSION_KEY` riêng; `deploy.sh` và `auto-deploy.sh` hiện chỉ lo một máy, cần mở rộng cho máy thứ hai.
- Rủi ro: thêm một điểm hỏng (mất đường nối thì Adh coi tài khoản đó là "không nối được", chưa chạm Zalo, không dừng tài khoản khác). Máy chủ Adh và Postgres vẫn ở máy chính.
- Mục đích của máy thứ hai là **đủ RAM và độc lập sự cố**, không phải để Zalo khó liên kết các tài khoản; không thiết kế gì cho mục đích sau (D23).

### 3.6 Thay đổi dữ liệu (một migration, số tiếp theo sau V55, nhớ kiểm lại số trước khi mở PR)

- Thêm `zalo_account`; thêm `account_id` (mặc định tài khoản 1 cho dữ liệu cũ) vào `invitation`, `zalo_msg_log`, `zalo_friend_event`, `zalo_group`, `zalo_group_proposal`, `zalo_health_alert`, `zalo_auto_log` và các bảng trả lời AI.
- Đổi ràng buộc duy nhất: `zalo_friend_event (account_id, bridge_seq)`, `invitation (account_id, zalo_uid)`.
- Không xóa số điện thoại hay dữ liệu khách ở đâu (mục 5a).

## 4. Rủi ro bị Zalo khóa (nói thẳng)

- **Không có số liệu đáng tin về ngưỡng của Zalo.** Mọi hạn mức đều là mức tự đặt. Dùng thư viện không chính thức vẫn trái điều khoản; chủ dự án đã nhận rủi ro này cho một tài khoản, chưa nhận cho nhiều tài khoản.
- **Nhiều tài khoản không làm rủi ro nhỏ đi.** Mỗi tài khoản vẫn nhắn người lạ, có thể bị báo cáo. Nhiều tài khoản cùng chạy từ **một máy chủ và một địa chỉ mạng** (như hôm nay với một tài khoản) có thể bị Zalo coi là liên quan; hãy giả định **chung số phận**, bị xử một thì có thể bị xử cả nhóm. Vì vậy cầu dao chung ở mục 3.3.
- **Tôi cố ý không đề xuất** đổi IP, dùng proxy, đổi dấu vân tay thiết bị hay làm thế nào để các tài khoản trông không liên quan: đó là né phát hiện, ngoài phạm vi (ADR-0002 mục 4).
- **Mỗi tài khoản phải là thật và công khai:** SIM chính chủ đăng ký đúng quy định, một người thật giữ điện thoại và quét QR, tên và ảnh nói rõ là của shop. Không mua tài khoản, không đăng ký hàng loạt, không tự thay tài khoản khi bị khóa (ADR-0002 cấm "đăng ký tài khoản mới để thay thế tự động").
- **Tài khoản mới** chỉ nên bắt đầu rất nhỏ và tăng theo sức khỏe quan sát được (theo `docs/RUNBOOK.md` và trang "Sức khỏe tài khoản phụ"), đúng tinh thần "lô nhỏ, chậm, minh bạch", không phải thủ thuật che giấu.
- Mất tài khoản vẫn là mất bạn bè và nhóm do tài khoản đó tạo; nhiều tài khoản chỉ giới hạn phạm vi mất, không tránh được.

## 5. Chi phí

| Khoản | Ghi chú (giá chưa kiểm chứng, cần hỏi nhà mạng và nhà cung cấp máy chủ) |
|---|---|
| SIM mỗi tài khoản | SIM chính chủ cộng cước duy trì nhỏ hằng tháng. |
| Điện thoại | Mỗi tài khoản cần một điện thoại để quét QR và nhận mã xác minh; chưa kiểm chứng điện thoại có cần luôn bật hay không khi cầu nối chạy. |
| Máy chủ | **Chủ dự án báo (25/11/2026) máy production đã nâng lên 6 GB RAM** (chưa đo lại bằng số liệu trên máy; trước đó là 2 GB, trống khoảng 300 MB). Mỗi cầu nối giới hạn 160 MB, nên vài tài khoản đầu chạy được trên máy hiện tại; **máy thứ hai không còn bắt buộc**, chỉ cần khi số tài khoản nhiều (hoặc muốn độc lập sự cố). Việc cần làm: đo RAM trống và dùng bộ nhớ thực của container cầu nối trước khi thêm tài khoản. |
| Công sức làm mã | Khoảng 6 đến 8 PR nhỏ (mục 6). Phần khó nhất là gắn `account_id` xuyên suốt và test. |
| Công sức vận hành | Mẹ kết bạn từng tài khoản; mỗi tài khoản một lần quét QR khi hết phiên; theo dõi N cầu dao. |

## 6. Các bước theo giai đoạn (mỗi bước là PR nhỏ, tắt mặc định)

0. **Dọn lời mời chờ (không cần tài khoản mới):** bộ nối thêm đọc danh sách lời mời đã gửi và thu hồi lời mời quá N ngày (trần mỗi ngày, khoảng nghỉ cố định, chỉ thu hồi lời mời do chính hệ thống gửi, ghi nhật ký). Trang `/zalo` hiện số lời mời đang chờ. Cần chủ dự án duyệt thêm hai việc này và sửa test khóa. Đo 1 đến 2 tuần tỷ lệ khách đồng ý.
1. **Chuẩn bị dữ liệu, vẫn một tài khoản:** bảng `zalo_account` với một dòng, cột `account_id` mặc định, `ZaloLinkRouter` với một cầu nối. Hành vi y hệt hôm nay; test chứng minh không đổi.
2. **Hạn mức, cầu dao, sức khỏe theo tài khoản, thêm trần tổng.**
3. **Phó nhóm theo tài khoản** (`zalo_account_deputy`), nhóm gắn tài khoản.
4. **Trợ lý AI và hộp thư theo tài khoản.**
5. **Giao diện:** `/zalo` thành danh sách tài khoản (mỗi tài khoản một thẻ: tình trạng, QR đăng nhập, hạn mức, lời mời chờ), phần còn lại chọn tài khoản.
6. **Tài khoản thứ hai ở chế độ thử** (số thử), thử tay một nhóm có mẹ làm phó nhóm.
7. **Bật tài khoản thứ hai thật với hạn mức rất thấp**, theo dõi vài tuần trước khi tăng.

## 7. Cần chủ dự án quyết

1. Có đồng ý **đổi quy tắc** (ghi quyết định mới) cho phép nhiều tài khoản phụ **cố định, thật, công khai, khách gắn một tài khoản, không luân phiên** không? Nếu không, dừng ở Bước 0.
2. Mục tiêu là **chia tải và bền** (khuyến nghị: trần tổng giữ nguyên) hay **tăng tổng số khách nhắn mỗi ngày**?
3. Có cho làm **Bước 0** (đọc và thu hồi lời mời chờ quá hạn) trước, và sau bao nhiêu ngày thì thu hồi?
4. Mẹ có đồng ý **kết bạn với mọi tài khoản phụ** và là phó nhóm ở mọi nhóm không? Có muốn thêm một phó nhóm dự phòng không?
5. Có sẵn **SIM chính chủ và điện thoại** cho tài khoản thứ hai không, và có chấp nhận **nâng máy chủ** (hết miễn phí giai đoạn 1) không?

## 8. Chưa kiểm chứng

- Giới hạn lời mời chờ của Zalo là bao nhiêu và có thay đổi theo tuổi tài khoản không (mới đo một lần: khoảng 49).
- `undoFriendRequest` và `getSentFriendRequest` chạy thật có ổn không, mã lỗi ra sao.
- Điều kiện "phải là bạn mới đặt được phó nhóm" và ai thêm được ai vào nhóm của tài khoản khác.
- Zalo có liên kết các tài khoản cùng máy chủ hay không (không có tài liệu công khai).
- Điện thoại có cần luôn bật khi cầu nối chạy.
