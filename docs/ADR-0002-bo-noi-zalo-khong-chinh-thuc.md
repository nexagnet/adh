# ADR-0002: Bộ nối Zalo không chính thức cho luồng "kết bạn → vào nhóm" (D16)

Ngày: 22/11/2026. Trạng thái: **đã chọn hướng, CHƯA CODE.** Quyết định D16 ở `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md`; phạm vi nghiệp vụ ở mục 1I của file đó.

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
- **Bật từng nấc, mỗi nấc chạy thử trước nấc sau:** `LISTEN_ONLY` (chỉ nhận sự kiện và gợi ý, người thêm tay) → `CONFIRM` (hiện nút "thêm người này", bấm là cầu nối thêm) → `AUTO` (tự thêm trong hạn mức). **Đề xuất dừng ở `CONFIRM`** cho tới khi chạy ổn nhiều tuần.
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
- Đăng nhập bằng **quét QR ở trang quản trị** (chỉ người đã đăng nhập thấy, ảnh QR không lưu đĩa, hết hạn nhanh). Mật khẩu Zalo và mã OTP **không bao giờ** qua Adh.
- Không có đường đăng nhập lại tự động; khi nghi lộ phiên: bấm "Đăng xuất" (hủy phiên cả hai phía) và đổi khóa mã hóa.
- Giới hạn còn lại phải nói thẳng: lúc đang chạy, phiên đã giải mã nằm trong bộ nhớ cầu nối; ai chiếm được VPS thì vẫn lấy được. Cách giảm: VPS cập nhật, tài khoản phụ **không** dùng chung số điện thoại hay mật khẩu với tài khoản chính, không chứa gì nhạy cảm.
- Bản thân kho `zca-js` có `SECURITY.md` và nhận báo lỗi riêng; vẫn nên chạy `npm audit` và ghim lockfile khi code.

### 3.6 Tách khỏi tài khoản chính
- Chỉ **tài khoản Zalo phụ** (D13) đăng nhập vào cầu nối. **Tài khoản của bố mẹ không bao giờ** (giữ R13 phần này). Cầu nối kiểm: nếu tài khoản vừa đăng nhập không phải mã tài khoản phụ đã khai báo thì từ chối và đăng xuất.
- Hậu quả nếu tài khoản phụ bị khóa: mất danh sách bạn và nhóm do tài khoản phụ tạo; D13/D15 chạy tay vẫn dùng được bằng một tài khoản phụ mới. Vì vậy **nhóm nên do tài khoản của mẹ làm trưởng nhóm** (mẹ chỉ cần là chủ nhóm, không đăng nhập cầu nối) và tài khoản phụ chỉ là thành viên có quyền thêm. Cần xác nhận được ở bước chạy thử (Q1).

## 4. Ranh giới: ngoài phạm vi
Không làm, và nếu có yêu cầu thì ghi "ngoài phạm vi" rồi báo lại chủ shop:
- Bất kỳ thứ gì để **giả dạng người dùng bình thường nhằm né phát hiện của Zalo**: giả lập gõ/click, độ trễ ngẫu nhiên giả người, đổi dấu vân tay thiết bị/`imei`/`userAgent` hoặc xoay vòng, proxy xoay, nhiều tài khoản luân phiên, đăng ký tài khoản mới để thay thế tự động.
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
- **Q8 (CHƯA TRẢ LỜI, tạm giữ `CONFIRM`).** Dừng ở nấc `CONFIRM` (người bấm "thêm" cho từng khách, máy làm phần thao tác) hay cho `AUTO` (máy tự thêm khi khách đủ điều kiện, không cần bấm)? Chủ shop chưa rõ câu hỏi nên đang được giải thích lại. Cho tới khi trả lời: `CONFIRM`.
- **Q9 (đã trả lời 22/11/2026).** Hạn mức mở đầu **50 người/ngày**, rải từ sáng đến tối (07:00–22:00), **chỉnh được trên giao diện** (3.4).
- **Q10 (đã trả lời 22/11/2026).** Chủ shop **chấp nhận mất tài khoản phụ nếu bị khóa**. Người quét QR khi phiên hết hạn: chưa ghi rõ là ai, mặc định là người cầm điện thoại của tài khoản phụ. Nhóm vẫn nên do tài khoản của mẹ làm chủ để không mất nhóm khi tài khoản phụ bị khóa.
- **Q1 (cũ, còn nguyên):** tài khoản phụ có quyền thêm thành viên vào nhóm không — chỉ biết qua đợt 0.
