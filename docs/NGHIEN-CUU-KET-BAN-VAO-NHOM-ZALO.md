# Nghiên cứu: khách đồng ý kết bạn thì đưa vào nhóm Zalo đúng vùng

**Ngày:** 22/11/2026. **Phạm vi:** chỉ nghiên cứu, chưa viết code. **Liên quan:** D2, D3, D5, D10, D13 trong `docs/BAN-GIAO.md`; `docs/ADR-0001-chon-kenh-bot.md`; file 03 mục 2.1 và 2.2.

> **Nguyên tắc của tài liệu này:** chỉ xét cách đi đúng điều khoản hoặc bán tự động. **Không** thiết kế cách né phát hiện hay chống spam của Zalo (giả thao tác người, đổi dấu vân tay, xoay vòng tài khoản, nhịp ngẫu nhiên để giả người). Đây cũng là quy tắc D5 của dự án.

## 1. Kết luận ngắn

1. **Không có cách chính thức nào để "tự biết khách vừa chấp nhận kết bạn trên tài khoản Zalo cá nhân phụ rồi tự thêm vào nhóm".** Mọi API chính thức của Zalo (OA, ZBS/ZNS, Bot Platform) đều không có sự kiện "kết bạn" và không có thao tác thêm người vào nhóm cá nhân. Cách duy nhất làm tự động đúng bước này là thư viện không chính thức hoặc tự động hóa giao diện, mà cả hai đều trái điều khoản Zalo và có thể mất tài khoản. **Không khuyến nghị.**
2. **Cách tốt hơn là bỏ bước "kết bạn rồi thêm" và cho khách tự vào bằng link/QR** (giấy in trong gói hàng, trang chọn tỉnh `/moi` đã code). Khách tự bấm là đồng ý rõ ràng, không cần tài khoản phụ nhắn người lạ, không rủi ro khóa tài khoản.
3. Với khách cũ đã có số điện thoại: giữ luồng bán tự động hiện tại (D13) nhưng **đổi bước "thêm vào nhóm" thành "gửi link nhóm cho khách bấm"**. Về dài hạn, khi cửa hàng có hộ kinh doanh và OA đã xác thực, chuyển sang **tin hậu mãi chính thức (ZBS) có nút vào nhóm**.

## 2. Mức độ kiểm chứng (đọc kỹ)

Công cụ của tôi **không đọc được** trang tài liệu chính thức `developers.zalo.me` (chỉ trả về phần mô tả chung, trang tải bằng JavaScript) và `bot.zaloplatforms.com` (bị chặn robots). Vì vậy:

| Điều khẳng định | Mức |
|---|---|
| Bot Platform không có API nhóm/thành viên, sự kiện chỉ là `message.*.received`, `getMe` mẫu có `"can_join_groups": false`, `sendVoice` ghi "does not support sending messages to groups" | **Đã đọc** bản sao tài liệu chính thức trên context7 (một snippet cho mỗi API, không phải trang gốc) |
| "Trên một số bot Marketplace, bot không thể được thêm vào nhóm" | **Đã đọc**, nhưng là ghi chú của dự án OpenClaw, không phải của Zalo |
| OA có API quản lý nhóm GMF (mời, duyệt, xóa thành viên), có sự kiện vào/yêu cầu vào nhóm; **không có** sự kiện kết bạn | **Đã đọc** SDK bên thứ ba (RedAI, bản 1.11.1). Không phải tài liệu Zalo |
| Tin tư vấn OA chỉ gửi được trong 48 giờ sau lần tương tác cuối, người nhận phải đang quan tâm OA, không quảng cáo trực tiếp | **Đã đọc** trong SDK bên thứ ba, **cần đối chiếu** `developers.zalo.me` |
| Giá GMF-10 25.000đ/nhóm/tháng, ZBS khoảng 200đ/tin, mẫu duyệt 2-3 ngày, OA mời trực tiếp chỉ cho người đã quan tâm OA, vào bằng link cần quản trị nhóm duyệt | **Đã đọc** ở luồng nghiên cứu trước (file 03 mục 2), tôi **không đọc lại** được trong luồng này |
| Điều khoản Zalo cấm đăng nhập/sử dụng dịch vụ qua phần mềm bên thứ ba không được Zalo chấp thuận, cấm can thiệp cơ chế vận hành, cấm dùng dịch vụ để quấy rối người khác | **Đã đọc qua bài CafeF** (10/09/2026) trích điều khoản; **chưa đọc** văn bản gốc của Zalo |
| Tài liệu thư viện `zca-js` tự nói dùng nó trái chính sách Zalo và có thể bị vô hiệu hóa | **Đã đọc** ở luồng trước (file 03, dòng 8) |
| Thư viện không chính thức có hàm thêm người vào nhóm, bật/lấy link nhóm | **Đã đọc** tài liệu `@warriorteam/zalo-personal` 1.4.1. **Không thấy** sự kiện "kết bạn được chấp nhận" trong phần đã đọc |
| Mức giới hạn kết bạn/nhắn người lạ mỗi ngày của Zalo; Zalo phát hiện thế nào | **Không biết**, Zalo không công bố. Con số 20 tin/ngày và 6 tin/giờ trong hệ thống là mức tự đặt, không phải mức của Zalo |
| Luật dữ liệu cá nhân (Luật 91/2025, NĐ 356/2025, NĐ 330/2026) | **Nguồn phụ**, theo ghi chú ở file 03. Cần luật sư đối chiếu |

## 3. Nghiệp vụ cần làm gồm hai việc nhỏ

- **(a) Biết khách đã đồng ý** (kết bạn, hoặc bấm link, hoặc quan tâm OA).
- **(b) Đưa khách vào đúng nhóm** (theo tỉnh, vùng, miền; còn chỗ; đúng quy tắc chia nhóm đã có).

Việc (b) hệ thống đã làm được phần "gợi ý nhóm". Điểm nghẽn là (a) và thao tác thêm người.

## 4. So sánh các hướng

| # | Hướng | Làm được (a) và (b)? | Điều khoản Zalo | Rủi ro khóa / pháp lý / khách khó chịu | Chi phí | Ổn định | Công sức |
|---|---|---|---|---|---|---|---|
| 1 | **Zalo OA + ZBS (tin hậu mãi theo số điện thoại, có nút vào nhóm)** | (a) Có, nếu khách bấm nút (đếm được lượt bấm, không biết ai vào nhóm thật). (b) Có, nút trỏ tới link nhóm đúng vùng | **Hợp lệ**: chính sách mẫu ZBS (cập nhật 09/07/2026) ghi nhận thông báo hậu mãi cho khách đã mua (đã đọc ở file 03) | Thấp. Mẫu bị duyệt trước, có chứng từ giao dịch, khách có nút từ chối. Zalo có thể tạm ngừng nếu bị khiếu nại nhiều (>5%). Khách ít khó chịu vì tin đến từ OA đã xác thực | OA gói Tăng trưởng khoảng 2,5 triệu/năm cho API, khoảng 200đ/tin. 400 khách ≈ 80.000đ + VAT/đợt | Cao (API chính thức) | Vừa. **Cần hộ kinh doanh và OA xác thực (D1)**. Chưa biết Zalo có duyệt mẫu "mời vào nhóm hỗ trợ" không, cần thử |
| 2 | **OA GMF (nhóm do OA sở hữu)** | (a) Sự kiện vào nhóm/yêu cầu vào nhóm có. (b) OA mời trực tiếp, nhưng **chỉ mời được người đã quan tâm OA**; vào bằng link thì cần duyệt từng người | Hợp lệ | Thấp về khóa. **Lệch nghiệp vụ**: nhóm do OA sở hữu, mẹ không phải trưởng nhóm (xung đột BR-11, D2). OA không rời được nhóm | 25.000đ/nhóm/tháng, 16-32 nhóm ≈ 0,4-0,8 triệu/tháng | Cao | Vừa đến lớn |
| 3 | **Zalo Bot Platform** | Không. Không có API nhóm/thành viên, không có sự kiện kết bạn. Bot có thể không vào được nhóm | Hợp lệ | Không có rủi ro khóa, nhưng **không làm được nghiệp vụ** | Miễn phí | Cao | Không đáng làm cho việc này |
| 4 | **"Zalo Open API" / webhook kết bạn cho tài khoản cá nhân** | Không tồn tại trong các tài liệu tôi đọc. OA có sự kiện quan tâm/bỏ quan tâm, không phải kết bạn | n/a | n/a | n/a | n/a | Không có gì để tích hợp |
| 5 | **Tự động hóa giao diện web/app Zalo** (điều khiển trình duyệt/điện thoại) | Về kỹ thuật có thể đọc thông báo kết bạn và bấm thêm vào nhóm | **Trái**: "can thiệp cơ chế vận hành", "dùng phần mềm không được Zalo chấp thuận" (theo bài CafeF) | **Cao**: mất tài khoản phụ và mất toàn bộ danh bạ khách đã kết bạn; thêm người vào nhóm khi họ chưa bấm đồng ý dễ bị báo cáo | 0đ tiền, nhưng mất tài khoản là mất tài sản | **Thấp**: giao diện đổi là hỏng, Zalo có thể đổi cơ chế bất ngờ (đã xảy ra 04/2026 với E2EE) | Lớn. **Không khuyến nghị** |
| 6 | **Thư viện không chính thức** (`zca-js`, `zalo-personal`) | Có hàm thêm người vào nhóm, link nhóm. Sự kiện "kết bạn được chấp nhận" **chưa xác nhận có** | **Trái**: chính tài liệu thư viện thừa nhận | **Cao** như hướng 5. Thêm: tin gửi người chưa đồng ý nhận tin trên tài khoản bị khóa là mất kênh | 0đ | Thấp (API đổi bất ngờ) | Vừa. **Không khuyến nghị**; ADR-0001 đã đặt điều kiện "chủ shop chấp nhận rủi ro bằng văn bản" |
| 7 | **Bán tự động (hiện tại, D13)**: hệ thống gợi ý và theo dõi, mẹ nhắn, mẹ bấm | (a) Mẹ tự thấy thông báo kết bạn và bấm *Đã kết bạn*. (b) Hệ thống gợi ý nhóm đúng vùng, mẹ thêm/gửi link | **Hợp lệ** vì người thật thao tác, hệ thống không đăng nhập Zalo | **Trung bình**: nhắn kết bạn người lạ, Zalo vẫn có thể hạn chế tài khoản nếu bị báo cáo hoặc gửi nhiều; pháp lý về khách chưa đồng ý nhận tin (chủ shop đã nhận ở D3). Đã giảm bằng lô nhỏ, một lần duy nhất, có câu "nhắn ngừng" | 0đ | Cao (không phụ thuộc API) | **Đã code** |
| 8 | **Thủ công có hỗ trợ** (xuất danh sách, mẹ làm hết) | Giống 7, ít theo dõi hơn | Hợp lệ | Như 7 | 0đ | Cao | Nhỏ. Là phương án dự phòng của 7 |

**Đọc bảng:** chỉ hướng 1, 2, 7, 8 vừa hợp lệ vừa làm được. Hướng 3 và 4 hợp lệ nhưng không làm được. Hướng 5 và 6 làm được nhưng trái điều khoản.

## 5. Có cách mời tốt hơn "nhắn người lạ, chờ kết bạn, rồi thêm" không?

Có. Cách hiện tại chỉ có lợi thế là khách cũ không có giấy in. Nó có ba điểm yếu: khách chưa đồng ý nhận tin (rủi ro pháp lý, khách khó chịu), tài khoản phụ có thể bị hạn chế, và mẹ phải làm nhiều thao tác tay. Các ý dưới đây xếp theo mức nên làm trước.

### 5.1 Khách tự vào bằng QR/link (đang có, nên làm trước)
Tờ giấy in A5 bỏ vào mọi gói hàng (D10) với một mã QR chung, trang `/moi` hỏi tỉnh rồi trỏ tới nhóm hợp vùng. **Ưu:** khách chủ động, đồng ý rõ ràng (BR-08), 0đ, không rủi ro khóa tài khoản, không cần kết bạn. **Nhược:** chỉ tới khách đang nhận hàng mới, không tới khách cũ; khách có thể không quét; không biết ai vào nhóm thật, chỉ đếm lượt bấm.

### 5.2 Sau khi khách kết bạn, gửi link nhóm thay vì tự thêm
Khi khách đã chấp nhận kết bạn, mẹ nhắn một câu kèm link nhóm đúng vùng (hệ thống tạo sẵn câu và nút sao chép). **Ưu:** khách tự bấm vào nên đúng BR-08; không bị thêm vào nhóm khi chưa muốn (ít bị báo cáo, ít rời nhóm); mẹ bớt thao tác "chọn từng người để thêm". **Nhược:** thêm một bước cho khách; nếu nhóm bật chế độ duyệt thành viên thì mẹ vẫn phải duyệt. Đây là thay đổi nhỏ nhất so với D13 nhưng cần chủ shop quyết, vì D13 đang ghi "thêm vào nhóm".

### 5.3 Ưu đãi nhỏ khi vào nhóm
Ví dụ giảm phí giao lần sau, hướng dẫn dùng sản phẩm độc quyền, hoặc bảo hành mở rộng. **Ưu:** tăng tỷ lệ vào nhóm mà không cần nhắn nhiều người lạ; có thể gắn mã (khách nhắn mã trong nhóm) để biết ai thật sự vào. **Nhược:** thêm chi phí; ưu đãi bằng tiền phải theo quy định khuyến mại (cần hỏi kế toán); làm nhóm thành nơi săn ưu đãi thay vì hỏi cách dùng. Nên thử nhỏ và đo.

### 5.4 Mời vào đúng lúc: sau khi trạng thái đơn là "Giao thành công"
Hệ thống đã nhập trạng thái đơn từ file hãng vận chuyển. Chỉ mời khách mà đơn đã giao xong vài ngày, khi họ đã dùng sản phẩm. **Ưu:** khách ít khó chịu hơn, nội dung "hỗ trợ sau mua" hợp lý hơn, đúng tinh thần BR-16. **Nhược:** phụ thuộc file J&T/Viettel Post chính xác (J&T vẫn chưa kiểm chứng).

### 5.5 Tin hậu mãi chính thức qua ZBS (hướng 1)
Là cách mời khách cũ sạch nhất: có chứng từ giao dịch, có nút từ chối, tin từ OA có tên cửa hàng nên khách tin hơn số lạ. **Nhược:** cần hộ kinh doanh và OA xác thực (D1), chi phí OA, mẫu phải được duyệt, khách không dùng Zalo thì không gửi được.

### 5.6 Cho khách theo dõi OA thay vì vào nhóm
Nếu mục tiêu chỉ là "gửi hướng dẫn, nhận câu hỏi", OA có thể thay nhóm: khách quét QR quan tâm OA, hỏi đáp 1-1. **Ưu:** hết chuyện xếp nhóm theo vùng, không giới hạn 5 người/nhóm, OA Manager có hộp thư nhiều nhân viên. **Nhược:** mất "nhóm nhỏ 5 khách" đã chốt trong nghiệp vụ; tin gửi chủ động có hạn chế (khung 48 giờ, phí, không quảng cáo trực tiếp, theo SDK bên thứ ba, chưa đối chiếu). Chỉ nên cân nhắc nếu chủ shop sẵn sàng đổi mô hình.

### 5.7 Kênh khác thay Zalo
- **SMS:** quảng cáo cần khách đăng ký trước (file 03 mục 2.1), khách cũ chưa đồng ý nên gần như bị chặn.
- **Gọi điện hoặc nhắn tay bằng số của cửa hàng:** hợp lệ nhưng tốn công, làm được với nhóm rất nhỏ.
- **Messenger/Facebook:** chưa nghiên cứu trong luồng này; khách của cửa hàng chủ yếu dùng Zalo theo mô tả dự án.

## 6. Khuyến nghị

**Theo ba tầng, từ làm ngay đến làm sau:**

1. **Làm ngay (đã gần xong): khách tự vào bằng QR/link.** In giấy A5, đo lượt bấm ở `/moi`. Đây là kênh chính, an toàn nhất.
2. **Giữ luồng bán tự động D13 cho khách cũ, đổi một điểm:** sau khi khách chấp nhận kết bạn, **gửi link nhóm cho khách tự bấm** thay vì thêm vào nhóm. Kết hợp mời sau khi đơn "Giao thành công" và thử một ưu đãi nhỏ. Giữ nguyên lô nhỏ, một lần duy nhất, câu xin ngừng nhận tin.
3. **Khi có hộ kinh doanh và OA xác thực:** chạy spike tin hậu mãi ZBS có nút vào nhóm (hướng 1). Nếu mẫu được duyệt, đây là kênh mời khách cũ chính thức và sẽ thay phần lớn việc nhắn người lạ bằng tài khoản phụ. GMF (hướng 2) chỉ xét nếu chủ shop chấp nhận nhóm do OA sở hữu.

**Không làm:** tự động hóa giao diện, thư viện không chính thức, Bot Platform cho việc này.

**Vì sao không "tự động hết" bước kết bạn → thêm vào nhóm:** vì đó là đúng điểm Zalo không cung cấp API, và cách duy nhất là phần mềm bên thứ ba trái điều khoản. Mất tài khoản phụ nghĩa là mất toàn bộ khách đã kết bạn trên đó, lớn hơn nhiều so với vài phút mẹ tiết kiệm được.

## 7. Việc cần chủ shop quyết

1. Đổi D13 từ "thêm vào nhóm" thành "gửi link nhóm cho khách bấm"? (khuyến nghị: đổi)
2. Có thử ưu đãi nhỏ khi vào nhóm không, và ưu đãi gì?
3. Khi nào đăng ký hộ kinh doanh và OA xác thực, để mở đường ZBS (D1)?
4. Hệ thống cần theo dõi khách "đã nhắn nhưng chưa kết bạn sau N ngày" (mục chưa làm trong BAN-GIAO), N bằng bao nhiêu?

## 8. Việc kiểm chứng tiếp theo (cần người mở trang bằng trình duyệt thật)

| # | Cần kiểm | Cách |
|---|---|---|
| V1 | Đọc trực tiếp tài liệu GMF, ZBS, điều kiện OA trên `developers.zalo.me` (công cụ của tôi không đọc được) | Chủ shop hoặc phiên có trình duyệt mở và dán lại các đoạn: ai được mời vào nhóm, phí, điều kiện mẫu "hậu mãi" |
| V2 | Zalo có duyệt mẫu ZBS "mời vào nhóm hỗ trợ" không | Gửi mẫu thử khi có OA |
| V3 | Văn bản gốc điều khoản sử dụng Zalo (hiện chỉ có qua bài CafeF) | Đọc tại trang điều khoản của Zalo |
| V4 | Nhóm cá nhân bật/tắt "duyệt thành viên" có ảnh hưởng thế nào tới link nhóm | Thử bằng nhóm thử và số người quen đã đồng ý (đã có `HUONG-DAN-THU-ZALO.md`) |
| V5 | Giới hạn thật khi một tài khoản phụ nhắn người lạ | Không có tài liệu công khai; chỉ quan sát khi chạy lô nhỏ thật và dừng ngay khi có cảnh báo |

## Nguồn

- Zalo Bot Platform (bản sao tài liệu): https://context7.com/websites/bot_zaloplatforms/llms.txt?tokens=10000
- OpenClaw, kênh Zalo: https://docs.openclaw.ai/channels/zalo.md
- RedAI Zalo SDK (bên thứ ba): https://unpkg.com/@warriorteam/redai-zalo-sdk@1.11.1/SERVICES_ADDED.md và https://unpkg.com/@warriorteam/redai-zalo-sdk@1.39.2/README.md
- `@warriorteam/zalo-personal` (không chính thức): https://cdn.jsdelivr.net/npm/@warriorteam/zalo-personal@1.4.1/docs/groups.md
- CafeF về điều khoản Zalo: https://cafef.vn/thao-tac-co-the-khien-tai-khoan-zalo-cua-ban-bi-khoa-vinh-vien-188260910160926853.chn
- Trong repo: `03_DINH_CHINH_KY_THUAT_VA_KE_HOACH.md` mục 2, `docs/ADR-0001-chon-kenh-bot.md`, `docs/BAN-GIAO.md` (D13), `docs/HUONG-DAN-THU-ZALO.md`, `/mnt/project-files/penpot-review/giay-in-gioi-thieu-nhom-zalo.md`
