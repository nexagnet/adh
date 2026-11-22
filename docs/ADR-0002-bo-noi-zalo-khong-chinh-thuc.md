# ADR-0002: Bộ nối Zalo không chính thức cho luồng "kết bạn → vào nhóm" (D16)

Ngày: 22/11/2026. Trạng thái: **đã chọn hướng; đợt chạy thử 0 đã xong ngày 22/11/2026 (mục 9); đang code theo giai đoạn, xem mục 10.** Quyết định D16 ở `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md`; phạm vi nghiệp vụ ở mục 1I của file đó.

## 1. Bối cảnh và quyết định

Chủ shop (Phùng Việt, 22/11/2026) chọn dùng **thư viện không chính thức giả lập Zalo máy tính/Zalo Web** để làm hai việc của mục 1I: (a) biết khách đã chấp nhận kết bạn trên tài khoản phụ, (b) thêm khách đó vào nhóm đúng khu vực. Đã báo rõ cho chủ shop: cách này **trái điều khoản của Zalo** và **tài khoản phụ có thể bị khóa**. Chủ shop chấp nhận rủi ro cho tài khoản phụ.

Quyết định này **thay đổi một phần** hướng của `docs/NGHIEN-CUU-KET-BAN-VAO-NHOM-ZALO.md` (PR #25, khuyến nghị không làm tự động) và của R13/D15 (cấm mọi công cụ tự động trên Zalo). Những gì **không đổi**: không giả dạng người để né phát hiện (D5), không gửi tin hàng loạt cho người chưa đồng ý, mọi tài khoản thật của bố mẹ không đụng tới.

## 2. Khảo sát thư viện

Đã tải mã nguồn `RFS-ADRENO/zca-js` về đọc trực tiếp (`git clone`) và hỏi `npm` cho số liệu gói. Phần không kiểm chứng được ghi ở mục 2.3.

### 2.1 Ứng viên chính: `zca-js` (npm `zca-js`)

| Mục | Kết quả | Mức kiểm chứng |
|---|---|---|
| Bản mới nhất | 2.3.0, đăng npm 14/11/2026; commit gần nhất 15/11/2026 ("tự lấy phiên bản API Zalo mới nhất khi đăng nhập"); 685 commit; tạo 07/2024 | Đã xác minh (npm, git log) |
| Bảo trì | Có người bảo trì và nhận đóng góp; **chỉ một tài khoản npm sở hữu gói** (rủi ro một người) | Đã xác minh |
| Giấy phép | **MIT** | Đã xác minh (`LICENSE`, `npm view`) |
| Cách hoạt động | "Giả lập trình duyệt để dùng Zalo Web" (chat.zalo.me). README cảnh báo: dùng có thể bị **khóa hoặc cấm tài khoản**, tác giả không chịu trách nhiệm | Đã xác minh (README) |
| Môi trường | Node ≥ 18, 8 phụ thuộc (`ws`, `pako`, `semver`, `crypto-js`, `form-data`, `spark-md5`, `json-bigint`, `tough-cookie`). **Là mã Node, không chạy trong ứng dụng Java của Adh** → phải là một dịch vụ riêng | Đã xác minh |
| Sự kiện kết bạn | Có `friend_event`; loại `ADD` (đã thành bạn) phát với `isSelf=false`, kèm mã người dùng (uid). Cũng có `REQUEST`, `REJECT_REQUEST`, `UNDO_REQUEST`, `REMOVE` | Đã xác minh **trong mã**; **chưa thử thật** có phát đủ và đúng lúc không |
| Thêm vào nhóm | `addUserToGroup(uid, groupId)` gọi `/api/group/invite/v2`; kết quả có `errorMembers`, `error_data` (báo lỗi từng người); thư viện **không kiểm** quản trị hay bạn bè, máy chủ Zalo quyết định. Có thêm `inviteUserToGroups(uid, groupId[])` (`/api/group/invite/multi`). Cũng có `getGroupInfo`, `getGroupMembersInfo`, `getPendingGroupMembers`, sự kiện nhóm | Đã xác minh trong mã; **chưa kiểm chứng** thành viên thường có thêm được người không (câu hỏi Q1) |
| Tra thông tin | `getUserInfo(uid)` có trường `phoneNumber` (có thể rỗng nếu người đó ẩn), `isFr`; `findUser(sốĐT)`; `getFriendRequestStatus(uid)` | Đã xác minh kiểu dữ liệu; **chưa thử** số điện thoại có trả về thật không |
| Đăng nhập | `loginQR()`: quét mã bằng điện thoại của tài khoản phụ. Hoặc đăng nhập lại bằng `{imei, cookie[], userAgent}` | Đã xác minh |
| Giữ kết nối | `listener.start({retryOnClose})`, `keepAlive`. Mã đóng `3000 = DuplicateConnection`: **cùng tài khoản mở Zalo Web/PC nơi khác thì kết nối nghe bị đá** | Đã xác minh trong mã; chạy thật chưa thử |
| Nhạy với thay đổi của Zalo | Cần cập nhật khi Zalo đổi giao thức (lịch sử commit cho thấy sửa thường xuyên) | Đã xác minh (lịch sử commit) |

### 2.2 Thư viện khác đã tìm thấy

| Gói | Ghi chú | Kết luận |
|---|---|---|
| `zalo-unofficial`, `@nguyennhuy/zalo-api`, `@smvsoftware/zca-js`, `@softtynet/zca-js`, `@hwqnq/zca-js`, `zalo-toolkit` | Bản đăng lại hoặc fork của zca-js dưới tên khác (nhiều gói trỏ về cùng kho). Chưa đọc mã từng gói | **Không dùng.** Rủi ro chuỗi cung ứng: người đăng khác có thể thêm mã độc đánh cắp phiên Zalo |
| `openzca` (MIT), `@openclaw/zalouser`, các plugin OpenClaw/AgentOS | Lớp bọc zca-js cho công cụ khác (CLI, agent) | Không cần: thêm một tầng phụ thuộc mà không thêm tính năng ta cần |
| `zlapi` (Python, `Its-VrxxDev/zlapi`), `zalo-api-final` (JS) | Cùng loại không chính thức; chưa đọc mã, chưa xác minh bảo trì | Không chọn: Adh không dùng Python, và zca-js được dùng rộng nhất trong số tìm thấy |
| API chính thức (OA, ZBS/ZNS, Bot Platform) | Không có thao tác "biết khách kết bạn trên tài khoản cá nhân" hay "thêm vào nhóm cá nhân" (kết luận của PR #25) | Không đáp ứng nghiệp vụ này |

### 2.3 Chưa kiểm chứng được (cần chạy thử hoặc hỏi người biết)
- Số sao, số issue, báo cáo "bị khóa tài khoản" trong issue của zca-js: **GitHub API không truy cập được từ môi trường này**, tìm web không ra số liệu. Không dùng làm cơ sở.
- Điều khoản Zalo nào cụ thể cấm: chưa đọc được bản gốc (xem PR #25). Chỉ dựa vào cảnh báo của chính thư viện và việc nó giả lập trình duyệt.
- **Ngưỡng của Zalo** (bao nhiêu lần thêm nhóm/kết bạn mỗi ngày thì bị cảnh báo): không công bố, không có số đáng tin. Mọi con số giới hạn ở mục 4 là **giả định thận trọng**, không phải số đã chứng minh an toàn.
- Sự kiện `ADD` có phát ổn định khi khách chấp nhận lời mời hay khi khách tự gửi lời mời; `phoneNumber` có trả về; thành viên thường có quyền thêm; phiên sống bao lâu. Mọi thứ này phải thử bằng tài khoản phụ với số điện thoại tự nguyện (qua `/test-runs`) **trước** khi dùng cho khách thật.

### 2.4 Chọn

**`zca-js` bản gốc từ npm `zca-js`, ghim đúng phiên bản (2.3.0 lúc này), có `package-lock.json`, kiểm tra tính toàn vẹn.** Nâng phiên bản chỉ bằng PR có người xem; không dùng bản fork hay bản đăng lại.

## 3. Kiến trúc cắm vào hệ thống hiện có

```
Trang quản trị (Java, Adh)                  Dịch vụ cầu nối (Node, zca-js)
 InviteService  ──►  ZaloGateway (giao diện) ──►  ZcaBridgeGateway ──HTTP nội bộ + khóa chung──►  zalo-bridge
                       ▲                                                                              │
              ManualZaloGateway (mặc định:                                                           Zalo Web
              không làm gì, người bấm tay như D13)                                                 (tài khoản phụ)
```

Nguyên tắc: **Adh (Java) là nơi quyết định, cầu nối (Node) chỉ là tay chân bị bó hẹp.**

### 3.1 Các phần
1. **`ZaloGateway` (giao diện Java, gói `vn.shop.crm.zalo`)** với ba thao tác: `trangThai()`, `layDanhSachSuKienKetBan(sau)`, `themVaoNhom(uid, nhomZaloId)`. Hai bản cài: `ManualZaloGateway` (mặc định, không gọi gì, giữ nguyên D13/D15 bán tự động) và `ZcaBridgeGateway` (gọi cầu nối). Mã còn lại của Adh không biết thư viện nào ở dưới.
2. **Dịch vụ cầu nối `zalo-bridge/` (Node, container riêng, chỉ trong mạng nội bộ Docker)**. Chỉ mở cổng cho Adh, có khóa chung. **Chỉ lộ ra ba việc:** trạng thái, đọc sự kiện kết bạn, thêm một người vào một nhóm. **Không lộ** `sendMessage`, `sendFriendRequest`, `findUser` hàng loạt hay lệnh tùy ý: dù Adh hay ai chiếm được khóa chung cũng **không thể** dùng cầu nối để gửi tin hàng loạt. Đây là chốt chặn mạnh nhất với ranh giới "không gửi hàng loạt".
3. **Compose profile `zalo-auto`:** dịch vụ cầu nối **không được khởi động** nếu không bật profile này. Mặc định tắt từ gốc.
4. **Bảng nhật ký `zalo_auto_log` (migration mới khi code):** thời điểm, hành động (`NGHE`, `GHEP`, `THEM_NHOM`, `DUNG`), mã lời mời, mã nhóm, kết quả, mã lỗi. **Không ghi số điện thoại, không ghi tên.** Mã Zalo (uid) lưu ở cột riêng của lời mời để thêm nhóm được, không đưa vào log.
5. **Cầu dao (`zalo_auto_state`):** `OFF`, `LISTEN_ONLY`, `CONFIRM`, `AUTO`, `TRIPPED`. Xem 3.3.

### 3.2 Luồng (khi bật)
1. Người cầm tài khoản phụ vẫn **nhắn tay** như D13 và bấm *Đã nhắn*. Cầu nối **không nhắn, không gửi lời mời kết bạn.**
2. Khách chấp nhận kết bạn → cầu nối nhận sự kiện `ADD` + uid → Adh gọi `getUserInfo` (qua cầu nối) lấy số điện thoại nếu có.
3. **Cổng điều kiện (kiểm trong Adh, không phải trong cầu nối)** — thiếu một điều kiện là **bỏ qua và chỉ ghi nhật ký**:
   - uid khớp (qua mã băm số điện thoại) một lời mời đang ở `SENT` của **chính cửa hàng này**; không khớp thì **không làm gì** (không tra thêm, không thêm nhóm). Khớp được số điện thoại thì lời mời chuyển `FRIENDED` như bấm tay (R1);
   - khách không nằm trong danh sách từ chối, không phải `REPEAT` (R2);
   - có nhóm hợp khu vực, còn chỗ, tài khoản phụ là thành viên và có quyền (R3, R7). Không có thì ghi "chờ nhóm", **không đẩy sang nhóm miền khác**;
   - cầu dao đang `AUTO` hoặc đã có người bấm duyệt (chế độ `CONFIRM`), hạn mức còn (3.4), trong khung giờ.
4. Gọi `themVaoNhom`. Thành công → như bấm *Đã vào nhóm* (R5, R11: +1 khách, không tăng hai lần). Lỗi riêng một người (khách chặn bị thêm, nhóm đầy) → ghi lại, **không thử lại tự động**, hiện thành việc cho người xử lý.
5. Khách vào bằng mã QR chung (D10) **không đổi**: khách tự bấm vào nhóm bằng link, cầu nối không liên quan (QR không cho ta mã Zalo của khách để thêm).

Đường nào cầu nối hỏng hoặc bị tắt, hệ thống rơi về D13/D15 bán tự động: người bấm tay. Không có việc gì chỉ làm được bằng cầu nối.

### 3.3 Công tắc và cầu dao
- Mặc định **`OFF`**. Bật phải làm ở hai nơi: biến môi trường `APP_ZALO_AUTO_ENABLED=true` **và** công tắc trên trang quản trị (ghi vào bảng cài đặt, ghi người bấm).
- **Bật từng nấc, mỗi nấc chạy thử trước nấc sau:** `LISTEN_ONLY` (chỉ nhận sự kiện và gợi ý, người thêm tay) → `CONFIRM` (hiện nút "thêm người này", bấm là cầu nối thêm) → `AUTO` (tự thêm trong hạn mức). **Chủ shop chọn nấc `AUTO` (22/11/2026)**, nhưng **mặc định vẫn ở `CONFIRM`**: nấc `AUTO` chỉ được bật bằng công tắc riêng trên trang quản trị (người bật được ghi nhật ký) **sau khi đợt chạy thử (mục 7, đợt 0) xong các điểm chưa kiểm chứng ở 2.3**, rồi đợt 3 chạy ổn ở `CONFIRM`. Công tắc `AUTO` tắt được ngay lúc nào cũng được; cầu dao `TRIPPED` và hạn mức (3.4) luôn có hiệu lực ở `AUTO`.
- **Dừng ngay (`TRIPPED`)** khi có một trong: phiên hết hạn/bị đăng xuất; mã đóng `DuplicateConnection`; Zalo trả yêu cầu xác minh/captcha hoặc thông báo hạn chế/khóa; **bất kỳ lỗi cấp hệ thống không nhận ra** (thất bại kín: chưa biết mã lỗi khóa thật nên cái gì lạ thì dừng); ≥ 3 lỗi hệ thống liên tiếp; vượt hạn mức; đồng hồ ngoài khung giờ. Khi `TRIPPED`: cầu nối **đóng kết nối nghe và ngừng mọi thao tác**, Adh hiện băng cảnh báo đỏ và ghi `DUNG`. **Không tự thử lại, không tự đăng nhập lại.** Chỉ người đọc nhật ký rồi bấm "Mở lại" mới thoát; phiên bị hết hạn thì người quét QR lại.
- Lỗi theo từng người (khách đã ở trong nhóm, khách chặn bị thêm, nhóm đầy) **không** làm cầu dao nhảy.

### 3.4 Hạn mức (chủ shop chốt 22/11/2026: 50 người/ngày, rải từ sáng đến tối, chỉnh được trên giao diện)
- Thêm vào nhóm: **mặc định 50 người/ngày**, **rải đều trong khung 07:00–22:00 (D5)** bằng trần theo giờ (**tối đa 5 người/giờ**, vì 50 người chia 15 giờ là khoảng 3–4 người/giờ) và **cách nhau ít nhất 2 phút**; khách đủ điều kiện mà hết hạn mức thì xếp hàng sang giờ/ngày sau, không dồn một lúc. Các con số này **chỉnh được trên trang quản trị** (người chỉnh và giá trị cũ/mới được ghi nhật ký); trang chỉ cho chỉnh **đến một trần cứng** đặt bằng biến môi trường (`APP_ZALO_AUTO_MAX_DAILY`, mặc định 100) để gõ nhầm không vượt trần; muốn nâng trần phải đổi cấu hình triển khai. **Lưu ý:** 50 người/ngày gấp 5 lần con số thận trọng đề xuất ban đầu (10); chủ shop đã chọn, Zalo không công bố ngưỡng nên **chưa biết 50 có an toàn không**. Cầu dao (3.3) vẫn dừng ngay khi Zalo cảnh báo. Có thể tự đặt thấp hơn trong tuần chạy đầu rồi tăng dần nếu muốn, đó là lựa chọn của chủ shop. Đây là **trần khối lượng**, không phải màn che: không giả lập gõ phím, không thời gian ngẫu nhiên cho giống người, không đổi dấu vân tay hay địa chỉ mạng. Không có số nào được chứng minh là an toàn; bản chất thư viện là giả lập trình duyệt nên Zalo vẫn có thể phát hiện.
- Số thao tác chỉ tính **thêm vào nhóm** (nghe không tính). Đếm cả lần lỗi.
- Hạn mức nằm trong Adh (một nơi), cầu nối kiểm lại bằng một hạn mức cứng của chính nó (gấp đôi trần cứng ở trên) để lỗi của Adh không vượt trần.

### 3.5 Phiên Zalo: lưu thế nào cho an toàn
Phiên gồm `imei + cookie + userAgent`; ai có phiên là **chiếm được tài khoản phụ** (đọc tin, nghe, thêm nhóm).
- **Không bao giờ lưu dạng rõ** trên đĩa, trong DB, trong git, trong log, trong sao lưu. Mã hóa **AES-256-GCM** trước khi ghi; khóa nằm ở **bí mật Docker/biến môi trường tách khỏi tệp phiên và khỏi bản sao lưu** (sao lưu có tệp phiên mà không có khóa thì vô dụng).
- Tệp phiên ở volume riêng của cầu nối, quyền 600, cầu nối chạy bằng người dùng không phải root, container không công bố cổng ra ngoài, mạng nội bộ chỉ với Adh.
- Đăng nhập bằng **quét QR ở trang quản trị** (chỉ người đã đăng nhập thấy, ảnh QR không lưu đĩa, hết hạn nhanh). Mật khẩu Zalo và mã OTP **không bao giờ** qua Adh. **Chủ shop chốt (22/11/2026):** mẹ hoặc chủ shop quét khi phiên hết hạn, làm ngay trên trang quản trị bằng tài khoản admin. Trang quản trị có mục "Zalo tài khoản phụ" hiện **trạng thái phiên** (đang chạy / hết hạn / `TRIPPED`), nút "Đăng nhập lại" hiện mã QR để quét bằng điện thoại của tài khoản phụ, và **nhắc trên web** (băng cảnh báo trên trang việc hôm nay) khi phiên hết hạn hay cầu dao nhảy; khi chưa quét thì bộ nối đứng yên, khách rơi về chạy tay. Chỉ tài khoản admin thấy mục này và mã QR; mã QR không lưu đĩa. Phiên mới sau khi quét vẫn bị kiểm đúng mã tài khoản phụ đã khai báo (3.6).
- Không có đường đăng nhập lại tự động; khi nghi lộ phiên: bấm "Đăng xuất" (hủy phiên cả hai phía) và đổi khóa mã hóa.
- Giới hạn còn lại phải nói thẳng: lúc đang chạy, phiên đã giải mã nằm trong bộ nhớ cầu nối; ai chiếm được VPS thì vẫn lấy được. Cách giảm: VPS cập nhật, tài khoản phụ **không** dùng chung số điện thoại hay mật khẩu với tài khoản chính, không chứa gì nhạy cảm.
- Bản thân kho `zca-js` có `SECURITY.md` và nhận báo lỗi riêng; vẫn nên chạy `npm audit` và ghim lockfile khi code.

### 3.6 Tách khỏi tài khoản chính
- Chỉ **tài khoản Zalo phụ** (D13) đăng nhập vào cầu nối. **Tài khoản của bố mẹ không bao giờ** (giữ R13 phần này). Cầu nối kiểm: nếu tài khoản vừa đăng nhập không phải mã tài khoản phụ đã khai báo thì từ chối và đăng xuất.
- Hậu quả nếu tài khoản phụ bị khóa: mất danh sách bạn và nhóm do tài khoản phụ tạo; D13/D15 chạy tay vẫn dùng được bằng một tài khoản phụ mới. Vì vậy **nhóm nên do tài khoản của mẹ làm trưởng nhóm** (mẹ chỉ cần là chủ nhóm, không đăng nhập cầu nối) và tài khoản phụ chỉ là thành viên có quyền thêm. Cần xác nhận được ở bước chạy thử (Q1).

## 4. Ranh giới: ngoài phạm vi
Không làm, và nếu có yêu cầu thì ghi "ngoài phạm vi" rồi báo lại chủ shop:
- Bất kỳ thứ gì để **giả dạng người dùng bình thường nhằm né phát hiện của Zalo**: giả lập gõ/click, độ trễ ngẫu nhiên giả người, đổi dấu vân tay thiết bị/`imei`/`userAgent` hoặc xoay vòng, proxy xoay, nhiều tài khoản luân phiên, đăng ký tài khoản mới để thay thế tự động.
- **"Làm sao cho giống người thật làm"** (chủ shop hỏi 22/11/2026): **không làm** như một tính năng thêm. Phần được làm chỉ là **giới hạn tốc độ và khung giờ** của 3.4 (trần theo ngày/giờ, khoảng cách tối thiểu, chỉ 07:00–22:00, xếp hàng sang sau khi hết hạn mức). Đó là giới hạn khối lượng, **không** nhằm làm cho Zalo không phân biệt được với người. Bản thân zca-js vốn đã giả lập trình duyệt Zalo Web (đó là cách nó hoạt động); ta không thêm lớp nào phía trên để che. Nếu có yêu cầu thêm, ghi "ngoài phạm vi" và báo lại.
- **Gửi tin hàng loạt tự động**, tự gửi lời mời kết bạn, tự tra số điện thoại hàng loạt, tự nhắn cho khách chưa đồng ý nhận tin. Cầu nối không có các hàm này (3.1.2).
- Tự thêm vào nhóm người **chưa chủ động chấp nhận kết bạn** (hoặc chưa vào bằng QR) và người trong danh sách từ chối.
- Dùng tài khoản chính của bố mẹ.

## 5. Rủi ro còn lại (nói thẳng)
| Rủi ro | Mức | Cách giảm | Còn lại |
|---|---|---|---|
| Zalo khóa tài khoản phụ | **Cao, đã biết trước** | Hạn mức thấp, cầu dao, bật từng nấc, rơi về chạy tay | Vẫn có thể mất tài khoản phụ |
| Zalo đổi giao thức làm thư viện hỏng | Trung bình–cao, thường xuyên | Cầu nối hỏng thì tắt, về chạy tay; ghim phiên bản, nâng có kiểm | Một người bảo trì thư viện |
| Lộ phiên Zalo | Trung bình | Mã hóa, tách khóa, mạng nội bộ, không công bố cổng | VPS bị chiếm thì vẫn lộ |
| Thêm khách vào nhóm khi khách chưa hiểu | Trung bình | Tin đầu nói rõ (Q2), R12, người ra khỏi nhóm được ghi vào từ chối | Phụ thuộc nội dung tin của chủ shop |
| Dữ liệu cá nhân (Nghị định 13/2023) | Trung bình | Chỉ ghép khách đã nhắn, log không có số/tên, không tra người lạ | Hỏi luật sư trước khi chạy thật |
| Hai người cùng dùng tài khoản phụ (Zalo PC + cầu nối) bị đá nhau | Thấp, gây phiền | Quy ước: lúc cầu nối bật thì không mở Zalo PC cho tài khoản phụ | Có thể tắt cầu nối khi cần |

## 6. Nghiệm thu khi code (bổ sung cho mục 1I.7)
1. Mặc định tắt: không cấu hình gì thì **không có cầu nối, không có lệnh gọi Zalo nào**; test chặn bằng `ManualZaloGateway`.
2. Cầu nối chỉ có ba đường; test kiểm không có đường gửi tin/kết bạn/tra số.
3. Cổng điều kiện: uid lạ, khách từ chối, `REPEAT`, khách chưa `SENT` → không thêm nhóm; chỉ ghi nhật ký.
4. Hạn mức ngày/giờ/khoảng cách và khung giờ; lỗi cũng tính.
5. Mọi lỗi hệ thống lạ làm `TRIPPED`; sau đó không có thao tác nào cho tới khi người mở lại.
6. Tệp phiên không có chuỗi `cookie` dạng rõ; thiếu khóa thì cầu nối không khởi động.
7. Nhật ký không chứa số điện thoại, tên.
8. Đợt chạy thử `/test-runs` với số tự nguyện: xác nhận (a) sự kiện `ADD`, (b) số điện thoại có về không, (c) quyền thêm của tài khoản phụ, **trước** khi mở cho khách thật.

## 7. Lộ trình đề xuất
1. **Đợt 0 (không code Adh):** thử `zca-js` riêng trên máy thử với tài khoản phụ và 2–3 số điện thoại tự nguyện: quét QR, nghe `ADD`, đọc `getUserInfo`, thêm một người vào nhóm thử. Trả lời mục 2.3.
2. **Đợt 1:** `ZaloGateway` + `ManualZaloGateway` + bảng log/cầu dao + giao diện trạng thái (chưa có cầu nối) — mã an toàn, không đổi hành vi.
3. **Đợt 2:** `zalo-bridge` + `ZcaBridgeGateway` ở nấc `LISTEN_ONLY`.
4. **Đợt 3:** nấc `CONFIRM`; chạy ổn nhiều tuần mới cân nhắc `AUTO`.

## 8. Câu hỏi cho chủ shop
- **Q8 (đã trả lời 22/11/2026).** Chủ shop chọn **`AUTO`** (máy tự thêm khi khách đủ điều kiện, không cần bấm). Cách làm: bật bằng công tắc trên giao diện, **mặc định ở `CONFIRM`** cho tới khi chạy thử xong các điểm chưa kiểm chứng (2.3). Hạn mức 50 người/ngày (3.4) và cầu dao (3.3) áp dụng như nhau cho cả hai nấc.
- **Q9 (đã trả lời 22/11/2026).** Hạn mức mở đầu **50 người/ngày**, rải từ sáng đến tối (07:00–22:00), **chỉnh được trên giao diện** (3.4).
- **Q10 (đã trả lời 22/11/2026).** Chủ shop **chấp nhận mất tài khoản phụ nếu bị khóa**. **Mẹ hoặc chủ shop quét QR khi phiên hết hạn**, thao tác trên trang quản trị bằng tài khoản admin (3.5); hệ thống nhắc trên web khi cần quét. Cần viết hướng dẫn quét cho mẹ vào runbook khi code. Nhóm vẫn nên do tài khoản của mẹ làm chủ để không mất nhóm khi tài khoản phụ bị khóa.
- **Q1 (đã có câu trả lời từ đợt 0, 22/11/2026):** có, kể cả khi tài khoản phụ chỉ là thành viên thường, miễn nhóm không bật "chỉ quản trị viên được thêm thành viên". Chi tiết và phần chưa thử ở mục 9.

## 9. Kết quả đợt chạy thử 0 (22/11/2026)

Chạy `zca-js` 2.3.0 riêng, ngoài mã Adh, trên máy thử: đăng nhập QR bằng **tài khoản phụ**, một tiến trình dài nghe sự kiện và nhận lệnh từng việc. Chỉ dùng **tài khoản chính của chủ shop** và **một tài khoản thử tự nguyện** (chủ shop cho phép dùng số của tài khoản này). Tài khoản phụ có sẵn 26 nhóm và 32 bạn bè thật: **không thêm ai vào nhóm có sẵn, không nhắn tin, không gửi lời mời cho ai ngoài tài khoản chính, không dò số lạ.** Nhóm thử là hai nhóm tạo riêng cho việc này (một do tài khoản phụ tạo, một do tài khoản chính tạo). Số điện thoại, mã Zalo (uid) và tên không ghi ở đây.

Ba điểm chưa kiểm chứng ở mục 2.3 và Q1 đã có kết quả như sau.

### 9.1 Sự kiện kết bạn (`friend_event`): phát đủ và phân biệt được
Nghe bằng `selfListen: true`. Quan sát thật:

| Tình huống | Loại | `isSelf` | Dữ liệu |
|---|---|---|---|
| Tài khoản phụ gửi lời mời (bấm tay trên điện thoại hoặc gọi API) | `REQUEST` (2) | `true` | `toUid`, `fromUid`, `src`, `message` |
| **Người kia chấp nhận lời mời của tài khoản phụ** | **`ADD` (0)** | `false` | **chỉ có uid của người kia** (chuỗi), không kèm tên hay số |
| Tài khoản phụ chấp nhận lời mời của người khác | `ADD` (0) | `false` | uid của người kia |
| Người kia **tự gửi** lời mời tới tài khoản phụ | `REQUEST` (2) | **`false`** | `toUid` là tài khoản phụ, `fromUid` là người gửi, `src`, `message` (lời nhắn của họ) |
| Người kia xóa kết bạn | `REMOVE` (1) | `false` | uid của người kia |

Kết luận: **`ADD` phát đúng lúc người kia chấp nhận, khác hẳn `REQUEST`**, nên cổng "khách đã chấp nhận" của 3.2 (bước 2) dùng được. Phải phân biệt hai chiều: lời mời **ta gửi** thì tín hiệu thành bạn là `ADD`; lời mời **khách tự gửi tới ta** là `REQUEST` với `isSelf=false`, và chỉ thành bạn sau khi ta chấp nhận (khi đó cũng có `ADD`). Luồng D16 chỉ cần chiều thứ nhất; chiều thứ hai **không** được tự động chấp nhận, chỉ ghi nhật ký. Sự kiện `ADD` chỉ mang uid, nên việc ghép với khách phải đi qua 9.2.

### 9.2 Số điện thoại: tra được uid, nhưng **không phải lúc nào cũng lấy lại được số**
- `findUser(số)` đổi số thành uid khi người đó **cho phép tìm bằng số điện thoại**. Nếu người đó tắt cài đặt này, Zalo trả lỗi **212 "Không tìm thấy"**: không tìm được, không có đường vòng.
- `getUserInfo(uid)` trả trường `phoneNumber` cho tài khoản thử (đã bật cho phép tìm bằng số).
- Trong danh sách bạn của tài khoản phụ (32 người), chỉ **5 người có `phoneNumber`**, 27 người để trống. Đây là mẫu nhỏ của bạn bè thật, không phải khách, nhưng cho thấy **phần lớn người dùng không để lộ số**.
- Hệ quả cho thiết kế: bước ghép uid với khách bằng mã băm số điện thoại (3.2, bước 2–3) **chỉ thành công với một phần khách**. Khách không lộ số sẽ rơi vào "không ghép được": đúng nguyên tắc thất bại kín, **không thêm nhóm tự động**, ghi nhật ký và để người xử lý tay (cùng lối với D13/D15). **Không** được bù bằng cách dò số hàng loạt hay tra người lạ để ghép (mục 4).
- **Chưa thử:** `getUserInfo` trên uid của khách thật (ngoài tài khoản thử); chưa biết tỉ lệ khách bật cho phép tìm bằng số.

### 9.3 Quyền thêm vào nhóm (Q1): thành viên thường thêm được
- Tài khoản phụ là **trưởng nhóm** (nhóm do chính nó tạo): `addUserToGroup` thành công.
- Tài khoản phụ chỉ là **thành viên thường** (nhóm do tài khoản chính tạo, cài đặt nhóm `addMemberOnly = 0`): `addUserToGroup` **vẫn thành công**: `errorMembers` rỗng, nhóm tăng thêm một thành viên, sự kiện nhóm `join` phát lại với nguồn là tài khoản phụ.
- `createGroup` báo lỗi **261** khi mới gửi lời mời mà chưa là bạn ("cần kết bạn với ít nhất 1 người để tạo nhóm"). Điều này gợi ý người được thêm phải là bạn, hợp với luồng D16 (chỉ thêm người đã chấp nhận kết bạn), nhưng **chưa thử riêng** với `addUserToGroup`.
- **Chưa thử:** nhóm bật "chỉ quản trị viên được thêm thành viên" (nhiều khả năng từ chối nhưng **chưa biết dạng lỗi**, nên cầu nối phải coi mọi lỗi lạ là lỗi theo người hoặc `TRIPPED` theo 3.3, không đoán); thêm người **không** phải bạn; nhóm đầy; khách chặn bị thêm.
- Hệ quả: khuyến nghị ở 3.6 (nhóm do tài khoản của mẹ làm trưởng, tài khoản phụ chỉ là thành viên) **khả thi**, với điều kiện **không bật** "chỉ quản trị viên thêm thành viên" trong cài đặt các nhóm khu vực. Nên thêm bước kiểm này vào hướng dẫn tạo nhóm.

### 9.4 Quan sát khác, ảnh hưởng đến cầu nối
- **Phiên:** đăng nhập QR trả `imei`, `userAgent`, `cookie`. Dùng lại phiên đã lưu để đăng nhập **thành công** hơn một giờ sau, không cần quét lại. Thời gian sống thật của phiên **chưa biết**.
- **Mất kết nối nghe:** kết nối nghe **đóng với mã 1006 sau khoảng 40 phút** chạy liên tục, và tiến trình thử **không tự kết nối lại** (chưa bật `retryOnClose`). Cầu nối phải bật kết nối lại có giới hạn số lần, và xử lý mã đóng theo 3.3 (1006 là đứt kết nối thường, **khác** `DuplicateConnection` 3000 phải `TRIPPED`).
- **Lỗi 600 "zpw_sek bị thiếu hoặc không đúng"** gặp một lần, ở lần chạy đầu, khoảng 5 phút sau khi quét QR; các lần chạy sau không gặp lại. Nguyên nhân chưa rõ. Coi như lỗi phiên: thuộc nhóm lỗi hệ thống dẫn tới `TRIPPED`.
- Không thấy dấu hiệu Zalo cảnh báo hay hạn chế trong đợt thử, nhưng số thao tác rất ít nên **không suy ra gì** về ngưỡng an toàn của 50 người/ngày; mục 2.3 về ngưỡng vẫn **chưa kiểm chứng**.

### 9.5 Cập nhật nghiệm thu
Mục 6 điểm 8 (a), (b), (c) đã thử một lần với tài khoản thử tự nguyện: (a) có, (b) có điều kiện (9.2), (c) có (9.3). Vẫn cần chạy lại ở nấc `LISTEN_ONLY`/`CONFIRM` với khách thật tự nguyện trước khi bật `AUTO`.

## 10. Tiến độ code và các quyết định khi code (22/11/2026)

Làm theo ba giai đoạn nhỏ, mỗi giai đoạn một PR có test, CI xanh. **Chưa chạy với tài khoản Zalo thật** trong quá trình code (không có thiết bị); mọi test dùng bộ nối giả (`FakeZaloGateway`, và chế độ giả của dịch vụ Node). Hướng dẫn chạy thử thật: `docs/HUONG-DAN-THU-ZALO-TU-DONG.md`.

| Giai đoạn | Nội dung | Trạng thái |
|---|---|---|
| 1 | Lõi trong Adh: `ZaloGateway`/`ZaloSession`, `ManualZaloGateway`, `ZcaBridgeGateway` (HTTP, `docs/ZALO-BRIDGE-API.md`), nấc, hạn mức, cầu dao, ghép khách, hàng đợi tay, nhật ký (V17) | Đã code |
| 2 | Dịch vụ Node `zalo-bridge/` (zca-js 2.3.0 ghim, phiên mã hóa, tự nối lại, trần riêng), profile compose `zalo-auto` | Chưa |
| 3 | Trang quản trị "Zalo tài khoản phụ": nấc, hạn mức, quét QR, duyệt, hàng đợi tay, băng cảnh báo | Chưa |

Các quyết định khi code (nói rõ để chủ shop biết, đổi được nếu không hợp):
1. **Hàng đợi tay (khách không lộ số, cỡ 27/32 theo mục 9.2).** Hàng đợi chỉ giữ mã Zalo và giờ kết bạn, **không** tên, **không** số. Người xử lý thấy giờ kết bạn và vài số cuối của mã, tự đối chiếu với bạn mới trên điện thoại rồi chọn đó là khách nào trong danh sách "đã nhắn, chưa ghép". Việc ghép tay vẫn qua cùng cổng điều kiện (R2, từ chối, `REPEAT`). Người lạ có số mà không khớp lời mời nào: chỉ ghi nhật ký, **không** lưu mã Zalo.
2. **Số điện thoại chỉ đi qua bộ nhớ lúc ghép**, không lưu ở bảng nào. Cầu nối chỉ hỏi số của đúng mã Zalo trong sự kiện `ADD`, không dò người khác, và chỉ trả `phone` khi người đó cho thấy.
3. **Mã nhóm Zalo nhập tay** ở cột mới `zalo_group.zalo_group_id` (khác link mời). Nhóm không có mã thì **không bao giờ** được thêm tự động; không có nhóm hợp khu vực thì khách giữ hàng chờ, không đẩy sang miền khác (R7).
4. **Mất phiên khi bộ nối đang rảnh** (`LOGIN_REQUIRED`) chỉ hiện cảnh báo, không làm cầu dao nhảy (không có thao tác nào đang diễn ra để dừng). Mất phiên **trong lúc thêm** người, đăng xuất, cảnh báo của Zalo, mã đóng `DuplicateConnection`, sai tài khoản phụ, lỗi lạ, hết giờ chờ không biết kết quả: **dừng ngay** (không đợi 3 lỗi), không tự thử lại, khách đó để người kiểm tra tay. Quét QR đúng tài khoản xong sau khi mất phiên hoặc đăng xuất thì tự mở lại; các lý do khác chỉ người bấm "Mở lại" mới thoát.
5. **Lỗi theo từng người** chỉ do cầu nối phân loại bằng danh sách mã đã biết. Nhóm bật "chỉ quản trị viên được thêm" **chưa thử**, nên dạng lỗi chưa biết: gặp lỗi chưa có trong danh sách thì bị coi là lỗi lạ và dừng ngay (thất bại kín). Sau lần thử thật đầu tiên, thêm mã lỗi đã thấy vào danh sách để các lỗi cùng loại về sau chỉ là lỗi theo người.
6. **Nấc `AUTO`** cũng thêm cả những khách đang "chờ duyệt" từ lúc còn ở nấc `CONFIRM`. Chọn `AUTO` cần tick xác nhận đã chạy thử xong; mặc định không chọn nấc nào (`OFF`) cho đến khi người bật.
7. Mỗi lần gọi Zalo để thêm người (kể cả lỗi) được ghi vào nhật ký **trước khi gọi** và tính vào hạn mức ngày, giờ và khoảng cách; riêng lần không nối được tới cầu nối (chưa chạm Zalo) thì không tính.
8. Ngoài ba việc của Zalo, cầu nối có thêm các lệnh **quản lý phiên** (quét QR, đăng xuất, dừng, nghe lại) cho trang quản trị; chúng không gửi gì cho ai. Hình dạng giao diện được khóa bằng test (`ZaloGatewayShapeTest`).
