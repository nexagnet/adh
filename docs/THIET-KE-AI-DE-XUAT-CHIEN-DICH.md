# Thiết kế: AI đề xuất chiến dịch và thời điểm gửi (D21, chủ dự án chốt hướng 23/11/2026)

**Trạng thái:** chủ dự án đã chốt làm theo toàn bộ thiết kế này, gồm mục 5 (gom nghiệp vụ nhắn tin vào chiến dịch, nhiều lần gửi, các núm cấu hình ở 5.3a, dữ liệu cho AI ở 5.3b). Mục 7 còn ba câu hỏi mở chưa chặn việc làm. Thứ tự PR ở mục 6. PR 1 (số liệu ẩn danh, #76) và PR 2 (AI đề xuất, kiểm cứng, nhật ký) đã có mã; PR 2 chưa có giao diện nên chưa dùng được từ web.

**Yêu cầu của chủ dự án (23/11/2026):** lấy thời tiết, khách, khu vực... đưa cho AI (DeepSeek) **đề xuất chiến dịch và thời gian áp dụng, kèm lý do**. Chiến dịch hiện nay (tên, một khu vực, số khách, số ngày link, nội dung tin) quá đơn giản; cấu hình chưa đủ để phát huy hệ thống và AI.

**Nguyên tắc cứng:** AI chỉ **đề xuất**. AI không tạo chiến dịch, không duyệt tự gửi, không gửi gì. Người dùng xem lý do rồi bấm duyệt bằng tay. Mọi ràng buộc D18 (hạn mức, khung giờ 08:00 đến 20:00, nghỉ cố định, cầu dao, 50 khách/ngày, mỗi khách một Tin 1) và cấm né phát hiện bot **không đổi**: đề xuất chỉ được nằm **bên trong** các giới hạn đó, không bao giờ nới chúng.

## 1. Luồng dữ liệu

```
dữ liệu thật (khách, đơn, lời mời, thời tiết)
   │  gom thành SỐ LIỆU TỔNG HỢP ẨN DANH (PR 1)
   ▼
AdvisorSnapshot ──► prompt (chỉ có số, mã khu vực, nhãn cố định)
   │  AiGate (tắt mặc định, trần lượt, cầu dao)
   ▼
DeepSeek trả JSON  ──► kiểm cứng bằng mã (PR 2): sai khuôn / vượt giới hạn / khu vực lạ thì bỏ
   ▼
bảng campaign_proposal (PROPOSED) ──► trang "Đề xuất của AI" (PR 3): lý do, số liệu, độ tin cậy
   │  người bấm Duyệt (hoặc Bỏ qua)
   ▼
InviteService.createCampaign (đường tạo chiến dịch HIỆN CÓ), nguồn = AI_PROPOSAL
   ▼
chiến dịch CHƯA được duyệt tự gửi; việc "Duyệt cho hệ thống tự gửi" vẫn là một cú bấm riêng
```

Đề xuất duyệt xong chỉ tạo **chiến dịch**. Muốn gửi, người dùng vẫn phải bấm "Duyệt cho hệ thống tự gửi" ở trang chi tiết như mọi chiến dịch (và công tắc tự gửi, nấc, cầu dao vẫn quyết định). Chiến dịch tạo từ đề xuất đi qua đúng cổng chặn của chế độ thử/cách ly mà luồng khác đang làm, vì đi chung `InviteService`.

## 2. Số liệu đưa cho AI (PR 1: `vn.shop.crm.advisor`)

`AdvisorSnapshotService` dựng `AdvisorSnapshot` chỉ gồm **con số, mã khu vực và nhãn cố định**:

| Nhóm | Nội dung |
|---|---|
| Lịch | ngày, tháng, mùa theo miền (bảng cố định trong mã), các dịp sắp tới trong 45 ngày (Black Friday, 12.12, Giáng sinh, Tết dương, Tết âm); bảng dịp nằm trong mã, cần sửa mỗi năm |
| Giới hạn hiện hành | hạn mức khách/ngày, nghỉ giữa hai tin, khung giờ gửi, trần khách mỗi chiến dịch (để AI không đề xuất vượt) |
| Tổng | số khách, khách mua một lần, đủ điều kiện mời chưa mời, chờ duyệt, phễu lời mời (đã nhắn, kết bạn, vào nhóm, từ chối) |
| Theo **vùng** (8 vùng + "chưa rõ") | số khách, mua một lần, đủ điều kiện, chờ duyệt, khách mới 30 ngày, thời gian từ lần mua gần nhất (≤30, 31–90, 91–180, >180 ngày, chưa rõ), phễu lời mời, thời tiết 48 giờ tới, tối đa 3 tỉnh lớn nhất |
| Khung giờ gửi | số tin đã nhắn và số khách kết bạn theo bốn khung (08–11, 11–14, 14–17, 17–20) |

**Không bao giờ có:** tên, số điện thoại, địa chỉ, mã Zalo, mã khách, nội dung tin, tên hàng. Hai lớp bảo vệ: (1) kiểu dữ liệu của snapshot chỉ có số, mã khu vực và nhãn do mã đặt, không có chuỗi tự do lấy từ cơ sở dữ liệu; (2) ẩn ô nhỏ: một vùng hoặc tỉnh có dưới **10 khách** thì không được liệt kê riêng (gộp vào "vùng nhỏ khác" hoặc bỏ), để không suy ngược ra từng người. Test khóa: nhập khách có tên, số, địa chỉ độc nhất rồi kiểm JSON không chứa; JSON không có chuỗi giống số điện thoại, email, đường dẫn.

Thời tiết lấy từ bảng `weather_forecast` đã có (MET Norway, 48 giờ), trung bình **theo số khách** của từng tỉnh trong vùng. Chưa có dự báo thì ghi rõ "chưa có", không bịa.

## 3. AI trả về gì (PR 2)

Một lượt gọi (`AiGate`), tối đa **3 đề xuất**, đúng khuôn JSON (thiếu hoặc thừa khóa là bỏ cả lượt):

```json
{"proposals":[{
  "title":"Đợt nóng đầu mùa, Đông Nam Bộ",
  "area":"S:DONG_NAM_BO",
  "batch_size":40,
  "start_date":"2026-11-25", "end_date":"2026-12-02",
  "window_from":"09:00", "window_to":"11:00",
  "daily_cap":10,
  "message_angle":"Nhấn mạnh mát khi đi làm ngoài trời",
  "reasons":["Dự báo nóng 36°C ở vùng có 120 khách","Vùng có 45 khách đủ điều kiện chưa mời"],
  "risks":["Chưa có lịch sử phản hồi để so khung giờ"],
  "confidence":0.55
}],"skip_reason":null}
```

Kiểm cứng bằng mã (không dùng AI kiểm AI), đề xuất sai thì **bỏ**, không sửa hộ:
- `area` phải là mã khu vực có trong snapshot; `batch_size` từ 1 đến min(số đủ điều kiện của khu vực, trần chiến dịch); ngày bắt đầu từ hôm nay (giờ Việt Nam), kéo dài tối đa 14 ngày; khung giờ nằm trong 08:00–20:00 và rộng ít nhất 1 giờ; `daily_cap` không quá hạn mức ngày hiện hành.
- Chữ (`title`, `reasons`, `risks`, `message_angle`) có giới hạn độ dài, không đường dẫn, không số điện thoại, không ký tự điều khiển; chỉ để người dùng đọc, **không bao giờ được gửi cho khách**.
- **Độ tin cậy bị mã chặn trần:** chưa đủ 30 tin đã nhắn thì không quá 0,6; không có dự báo thời tiết thì không quá 0,7. AI không được tự nâng độ tin cậy lên khi thiếu dữ liệu.
- `message_angle` chỉ là hướng nhấn để người dùng cân nhắc sửa Tin 1. Tin 1 vẫn là mẫu người dùng đã chốt; AI soạn nguyên văn tin (nếu làm) là bước sau, phải qua bộ kiểm giống `ReplyValidator` và người dùng sửa trước khi dùng.
- Câu lệnh hệ thống ghi rõ ngữ cảnh cửa hàng (bán áo điều hòa và phụ kiện, trong đó có pin; nóng thì nhu cầu áo điều hòa cao), cấm hứa giảm giá, cấm đề xuất vượt giới hạn, và bắt AI nói "chưa đủ dữ liệu" (`skip_reason`) thay vì đoán. Số liệu là dữ liệu, không phải lệnh.

## 4. Giao diện duyệt (PR 3)

Trang "Đề xuất của AI" (menu cạnh Chiến dịch): nút **Nhờ AI đề xuất** (chỉ người bấm mới gọi; trần 5 lần/ngày, thêm vào trần chung của AI), danh sách đề xuất: tiêu đề, khu vực, số khách, ngày giờ, **lý do, rủi ro, độ tin cậy**, số liệu đã dùng (xem được đúng JSON đã gửi cho AI, để người dùng thấy không có dữ liệu cá nhân). Nút **Duyệt và tạo chiến dịch** / **Bỏ qua** (ghi lý do bỏ qua để sau này đo AI đoán trúng hay trượt). Đề xuất quá hạn (ngày bắt đầu đã qua) tự thành EXPIRED. Công tắc `APP_AI_ADVISOR_ENABLED`, **tắt mặc định**, cần cả `APP_AI_ENABLED`.

Nhật ký (bảng `advisor_run`, `campaign_proposal`, V36): ai bấm, lúc nào, snapshot tổng hợp đã gửi, JSON AI trả về, đề xuất nào bị mã bỏ và vì sao, ai duyệt hoặc bỏ qua, chiến dịch được tạo. Không có dữ liệu cá nhân nên giữ lâu được; không bao giờ tự xóa khách hay số điện thoại (CLAUDE.md mục 5a).

## 5. Chiến dịch là nơi duy nhất cấu hình nghiệp vụ nhắn tin (chủ dự án chốt 23/11/2026)

Yêu cầu: "tập trung nghiệp vụ nhắn tin vào chiến dịch để AI đề xuất tốt hơn". Hiện cấu hình rải ở nhiều nơi (`/campaigns`: khu vực, số khách, nội dung; `/zalo`: nấc, hạn mức, khoảng nghỉ, tự tạo nhóm, khách quay lại, tự nhận khách mới; `/zalo/reply`; `/test-runs`; `/today`). AI chỉ đề xuất tốt khi mọi núm chỉnh nằm trong MỘT mô hình mà nó hiểu và mã kiểm được.

### 5.1 Nguyên tắc: hệ thống đặt TRẦN, chiến dịch chọn giá trị BÊN DƯỚI trần

| Tầng | Chứa gì | Ở đâu |
|---|---|---|
| **Hệ thống (an toàn, toàn cục)** | công tắc tổng, cầu dao, tạm dừng tất cả, tài khoản Zalo, khóa AI, trần cứng (50 khách/ngày, nghỉ tối thiểu 100 giây, khung giờ tối đa 08:00–20:00, trần số lần 3, phanh khẩn cấp bộ nối), chế độ cách ly khách thật | `/zalo` (giữ nguyên, không chuyển vào chiến dịch) |
| **Chiến dịch (nghiệp vụ)** | phạm vi khách, lịch, nhịp, chuỗi tin, nhóm đích, khách quay lại, tự nhận khách mới, chế độ thử/thật | trang chi tiết chiến dịch, chia tab |

Giá trị hiệu lực luôn là `min/hẹp hơn(hệ thống, chiến dịch)`, tính ở MỘT hàm duy nhất (`CampaignPolicy.effective(...)`) mà đường gửi bắt buộc dùng. Chiến dịch **không bao giờ nới** được giới hạn hệ thống; ô để trống nghĩa là "theo hệ thống", nên chiến dịch cũ chạy y như trước. Mọi chốt chặn hiện có (công tắc, nấc, khung giờ, nghỉ cố định, cầu dao, 50 khách/ngày, không `Random`, không né phát hiện) vẫn chạy **sau** bước này.

### 5.2 Mô hình chiến dịch (các tab)

1. **Khách:** khu vực (một hoặc nhiều mã R:/S:/P:), độ mới của lần mua (`min/max_days_since_purchase`), thứ tự lấy khách (`pick_order`: duyệt sớm nhất, mua mới nhất, mua cũ nhất), số khách tối đa, tự nhận khách mới (chuyển từ nút hiện có).
2. **Lịch và nhịp:** `start_on`/`end_on`, khung giờ riêng (chỉ hẹp hơn 08:00–20:00), hạn mức khách/ngày riêng (chỉ thấp hơn hạn mức chung), nghỉ giữa hai tin riêng (chỉ cao hơn sàn).
3. **Chuỗi tin (mục 5.3):** số lần gửi, nội dung và khoảng cách từng lần.
4. **Nhóm đích:** kiểu (nhóm khu vực tự thêm / link nhóm cố định / tự tạo nhóm khi đầy), mẫu tên nhóm, Tin 2 (link nhóm).
5. **Khách quay lại:** chính sách riêng của chiến dịch (Tắt / Chỉ tạo việc / Tự động) cho khách từng từ chối nhắn lại muốn mua.
6. **Chế độ:** Thật hoặc Thử (cách ly khách thật do luồng khác làm; chiến dịch Thử chỉ chạm số thử, giữ nhật ký riêng).
7. **Đề xuất AI:** nguồn (`MANUAL`/`AI_PROPOSAL`), đề xuất gốc, lý do, và kết quả so với dự đoán (sau này đo AI đúng hay sai).

Trang giữ ở `/zalo` chỉ còn phần hệ thống ở bảng trên. Trợ lý trả lời khách tự nhắn (D20) và nhận diện từ chối (D19) vẫn là chức năng toàn cục vì khách nhắn tới không thuộc chiến dịch nào; riêng "bảng thông tin ưu đãi" sẽ cho phép chiến dịch ghi đè sau này để câu trả lời khớp ưu đãi của đợt đó.

### 5.3 Nhiều lần gửi (1, 2 hoặc nhiều lần) và các chốt bắt buộc

Mặc định **1 lần** (đúng như D18 hiện nay). Chiến dịch được chọn tối đa **`MAX_TOUCHES = 3`** lần (hằng số cứng trong mã, không chỉnh được ở giao diện hay biến môi trường). Mỗi chiến dịch có chuỗi bước `campaign_step(campaign_id, step_no, template, gap_days)`: bước 1 là Tin 1 (mời), bước 2 và 3 là tin nhắc.

Chốt an toàn (mã chặn, không dựa vào lời hứa của AI hay người):
- **Sổ đếm cứng:** bảng `invitation_touch(invitation_id, step_no, sent_at, outcome)` với khóa duy nhất `(invitation_id, step_no)` và `CHECK (step_no <= 3)`. Mỗi khách tối đa một lời mời trong đời (như hiện nay) nên tổng số lần nhắn chủ động tới một khách không bao giờ quá 3. Lỗi không rõ đã gửi chưa thì đánh dấu thất bại và **không tự gửi lại**, giống Tin 1.
- **Khoảng cách tối thiểu giữa hai lần:** `gap_days` từ **3 ngày** trở lên (sàn cứng trong mã), tính từ lần gửi thành công trước.
- **Tự ngừng ngay (kiểm lại ngay trước mỗi lần gửi, không chỉ lúc xếp hàng):** khách đã đồng ý kết bạn, đã vào nhóm, đã từ chối (sổ từ chối, kể cả đường bấm ngừng trên link), đã nhắn xin ngừng hoặc AI nhận diện từ chối (kể cả lời mời bị tạm dừng chờ mẹ xem), **khách đã nhắn riêng tới bất cứ điều gì** (khi đó chuyển người thật, không nhắc nữa), lời mời hết hạn, chiến dịch bỏ duyệt, tạm dừng hay hết `end_on`.
- **Nội dung riêng từng lần, và Tin 1 khớp với số lần:** Tin 1 mẫu hiện có câu "em sẽ không nhắn lại ạ", sai nếu còn tin nhắc. Chiến dịch từ 2 lần trở lên dùng mẫu Tin 1 khác (không có câu đó; mã từ chối lưu chiến dịch nếu Tin 1 còn câu "không nhắn lại" khi `max_touches > 1`). Mỗi tin nhắc phải khác nội dung tin trước (mã so sánh, không cho trùng) và không được tạo cảm giác ép ("cuối cùng", "chỉ còn", hạn gấp) vì AI soạn và người dùng sửa đều đi qua bộ kiểm đầu ra như `ReplyValidator`.
- **Cách xin ngừng ở cuối MỌI lần gửi do mã tự thêm** (một câu cố định, ví dụ "Anh/chị không muốn nhận tin nữa cứ nhắn \"dừng\" ạ."), không phụ thuộc người soạn có nhớ hay không. Khách nhắn "dừng" đi qua AI nhận diện từ chối đã có.
- **Hạn mức:** tin nhắc cũng là tin chủ động, nên **tính vào hạn mức ngày** và theo đúng khung giờ, nghỉ cố định, cầu dao và phanh khẩn cấp của bộ nối như Tin 1. Cách tính: hạn mức ngày đếm số khách được nhắn chủ động trong ngày (Tin 1 hoặc tin nhắc); một khách chỉ nhận tối đa một tin chủ động mỗi ngày. Tương thích với bộ đếm "mỗi khách một Tin 1": Tin 1 vẫn đúng một lần cho mỗi khách (khóa duy nhất hiện có không đổi); tin nhắc là loại tin mới (`FOLLOWUP`), không đụng đến quy tắc Tin 1.
- **Bộ nối:** thêm loại `FOLLOWUP` dùng đúng hàm gửi sẵn có (không thêm lời mời kết bạn, không thêm đường gửi mới), chỉ theo mã Zalo của người đã nhận Tin 1; test khóa (`no-send.test.mjs`, `ZaloGatewayShapeTest`) cập nhật đúng một chỗ gọi và giữ cấm `Random`.
- **Cách ly khách thật:** chiến dịch ở chế độ Thử chỉ gửi tin nhắc cho số thử; bộ đếm chạy theo `scope` như `zalo_msg_log` hiện nay. Phần này thuộc luồng "cách ly chiến dịch thử khỏi khách thật": thiết kế dùng cùng một cổng `ZaloMessageService` và cùng `scope`, không tạo đường gửi song song.
- **AI chỉ đề xuất số lần ≤ trần**; đề xuất hơn 1 lần phải ghi lý do và rủi ro, và mặc định đề xuất 1 lần khi chưa có lịch sử phản hồi (tin nhắc nhiều lần làm tăng khả năng bị báo spam, ảnh hưởng tài khoản phụ duy nhất).

### 5.3a Núm cấu hình nên có thêm (xếp theo thứ tự làm; mọi núm chỉ làm chặt hơn, không nới giới hạn hệ thống)

| Nhóm | Núm | Ghi chú |
|---|---|---|
| Đối tượng | khu vực nhiều mã; số lần mua (hiện chỉ mua một lần); thời gian từ lần mua cuối (min/max ngày); loại hàng đã mua (nhóm hàng: áo điều hòa, pin, phụ kiện; xem câu hỏi mở); tình trạng đơn (giao rồi bao nhiêu ngày); loại trừ khách đã vào nhóm | khách từ chối, bị chặn, cần kiểm tra **luôn** bị loại, không có núm để bật lại |
| Lịch | ngày bắt đầu/kết thúc; ngày trong tuần được gửi; khung giờ riêng, có thể khác theo vùng (múi giờ giống nhau nhưng thói quen khác); tránh ngày lễ do người dùng chọn | khung giờ luôn nằm trong 08:00–20:00 |
| Điều kiện hoãn | hoãn gửi cho một vùng khi dự báo mưa lớn hoặc thời tiết xấu (dùng `weather_forecast`) | chỉ **hoãn**, không bao giờ làm gửi nhanh hơn hay nhiều hơn; hết điều kiện thì tiếp tục trong hạn mức |
| Nhịp | hạn mức khách/ngày riêng; nghỉ riêng; tăng dần theo ngày (đợt khởi động nhẹ rồi nâng tới hạn mức chiến dịch) | tăng dần là bậc thang cố định do người đặt, không ngẫu nhiên |
| Mục tiêu và đo lường | mục tiêu chính (kết bạn, vào nhóm, mua lại); mỗi chiến dịch tự ghi phễu: đã nhắn, kết bạn, vào nhóm, từ chối, mua lại; theo bước, khung giờ, ngày trong tuần | số liệu này quay lại làm dữ liệu cho AI |
| Thử A/B | chia chiến dịch thành hai nhánh khác nội dung hoặc khung giờ để so sánh | chia **theo quy tắc cố định** (chẵn lẻ mã khách), không `Random`; mỗi nhánh vẫn đúng hạn mức; AI chỉ đề xuất thử, người duyệt |
| Tự dừng | dừng khi tỷ lệ từ chối vượt ngưỡng (ví dụ 5% trong 20 tin đầu), khi Zalo cảnh báo, khi hết hạn | chiến dịch tự chuyển "Tạm dừng" và báo, không tự tiếp tục |

### 5.3b Thông tin nên cho AI thêm (từng bước, luôn dạng tổng hợp ẩn danh)

1. **Có ngay (PR 1):** đếm khách, độ mới lần mua, kho khách mời được, phễu lời mời theo vùng và khung giờ, thời tiết 48 giờ, mùa, dịp bán hàng, giới hạn gửi hiện hành.
2. **Lịch sử kết quả từng chiến dịch (sau PR 5):** tên đợt, khu vực, số lần, ngày và khung giờ, phễu theo bước, tỷ lệ từ chối; để AI học từ chính kết quả của shop. Đây là dữ liệu giá trị nhất, chưa có vì chưa gửi thật.
3. **Ngày trong tuần và theo giờ, tốc độ phản hồi** (kết bạn sau bao lâu).
4. **Sản phẩm:** chỉ **số lượng theo nhóm hàng** (không gửi tên hàng, vì tên hàng là chữ tự do từ file đơn). Cần quyết cách gán nhóm hàng (xem mục 7).
5. **Thông tin cửa hàng và chính sách:** bảng thông tin cửa hàng đã có (ưu đãi vào nhóm, bảo hành), đưa nguyên văn cho AI để đề xuất không mâu thuẫn; không có số điện thoại hay link.
6. **Thời tiết xa hơn:** hiện chỉ 48 giờ nên đề xuất lịch chủ yếu 1 đến 3 ngày; muốn đề xuất dài hơn thì cần dự báo nhiều ngày hoặc khí hậu theo tháng (bảng mùa hiện có là xấp xỉ).

### 5.4 Chuyển dần, không phá chiến dịch đang có

- Thêm cột `NULL` = "theo hệ thống" vào `invitation_campaign`; dữ liệu cũ không đổi, hành vi cũ giữ nguyên (có test so sánh: chiến dịch cũ cho cùng kết quả gửi trước và sau).
- Mỗi giá trị chuyển vào chiến dịch theo thứ tự: sender đọc qua `CampaignPolicy.effective`, rồi giao diện chuyển nút sang trang chiến dịch, cuối cùng mới xóa chỗ cũ ở `/zalo` hoặc `/test-runs` (không xóa sớm; theo CLAUDE.md mục 3 và 7).
- Khi mô hình đủ, bản đề xuất của AI **chính là một chiến dịch ở dạng nháp** (cùng các trường), nên Duyệt chỉ là biến nháp thành chiến dịch thật chưa duyệt tự gửi.

## 6. Chia PR (nhỏ, một việc, tắt mặc định, không nới giới hạn)

1. **PR 1 (PR này):** thiết kế + `AdvisorSnapshotService` (số liệu tổng hợp ẩn danh) + test không lộ dữ liệu cá nhân. Chưa gọi AI, chưa giao diện, chưa migration.
2. **PR 2 (đã có mã):** `CampaignAdvisor` (prompt, `AiGate`, kiểm cứng bằng `ProposalValidator`), `AdvisorService.run` (công tắc `APP_AI_ADVISOR_ENABLED` tắt mặc định, cần cả `APP_AI_ENABLED`; trần `APP_AI_ADVISOR_MAX_RUNS`, mặc định 5 lần mỗi ngày), bảng `advisor_run` và `campaign_proposal` (V36). Lượt gọi này được nới đến 1500 chữ đầu ra và 30 giây chờ (có trần cứng 2000 và 60 giây ở `AiClient.Options`) vì dài hơn lượt phân loại. Chưa tạo chiến dịch.
3. **PR 3 (đã có mã):** trang `/advisor` "Đề xuất của AI" (menu cạnh Chiến dịch mời; `AdvisorController`, `ProposalService`, mẫu `advisor.html` và `advisor-run.html`): nút Nhờ AI đề xuất (chỉ hiện khi công tắc bật), danh sách đề xuất với lý do, rủi ro, độ tin cậy, xem đúng JSON đã gửi và chữ AI trả về ở `/advisor/runs/{id}`, **Duyệt và tạo chiến dịch** (qua `InviteService.createCampaign`, mẫu tin là mẫu mặc định của cửa hàng, KHÔNG dùng chữ của AI; số khách = min(đề xuất, số còn đủ điều kiện, trần chiến dịch); khóa dòng nên bấm hai lần không tạo hai chiến dịch; chiến dịch CHƯA duyệt tự gửi, chưa bật tự nhận khách mới) và **Bỏ qua** kèm lý do (≤ 300 ký tự). Đề xuất có ngày bắt đầu đã qua tự thành EXPIRED. Ngày giờ, khung giờ và hạn mức ngày AI gợi ý CHƯA được áp vào chiến dịch (chờ PR 4): trang nói rõ điều này. Không migration mới; liên kết chiến dịch nằm ở `campaign_proposal.campaign_id`.
4. **PR 4 (đã có mã, V37):** `vn.shop.crm.zalo.CampaignPolicy` + cột `start_on`, `end_on`, `window_from`, `window_to`, `daily_cap`, `gap_seconds` trên `invitation_campaign` (NULL = theo hệ thống, nên chiến dịch cũ chạy y như trước). `effective(raw)` = giá trị CHẶT hơn của hệ thống và chiến dịch (khung giờ là phần giao, hạn mức là số nhỏ hơn, nghỉ là số lớn hơn); `save` từ chối giá trị nới hơn trần bằng thông báo tiếng Việt; `saveClamped` dùng khi duyệt đề xuất của AI (kẹp trong trần, không bao giờ nới). `ZaloInviteSender.sendNextInvite` chỉ dùng `blocker(campaignId)` để BỎ QUA chiến dịch chưa tới ngày, ngoài khung giờ riêng, đủ hạn mức ngày riêng hoặc chưa đủ nghỉ riêng (chiến dịch bị bỏ qua không làm tắc chiến dịch khác); mọi chốt của `ZaloMessageService.blocker` vẫn chạy sau đó. Ô "Lịch và nhịp riêng" ở trang chiến dịch; duyệt đề xuất ở `/advisor` đặt ngày, khung giờ và hạn mức ngày AI đề xuất vào chiến dịch. **Chưa làm** (để PR sau, cùng bảng ở 5.3a): phạm vi khách mở rộng (nhiều khu vực, độ mới lần mua, thứ tự lấy khách), ngày trong tuần, hoãn khi mưa lớn, tăng dần theo ngày, tự dừng khi tỷ lệ từ chối cao, A/B. Nghỉ riêng và hạn mức riêng chỉ đếm Tin 1 tự gửi của chiến dịch đó (`sent_channel = ZALO_AUTO`).
5. **PR 5a (đã có mã, V38):** phần dữ liệu và cấu hình của chuỗi tin, CHƯA gửi tin nhắc nào. `invitation_campaign.max_touches` (mặc định 1, CHECK 1 đến 3), bảng `campaign_step` (bước 2 và 3: nội dung ≤ 500 ký tự, `gap_days` 3 đến 30), bảng `invitation_touch` (sổ đếm cứng: khóa `(invitation_id, step_no)`, `step_no` 1 đến 3; chưa có ai ghi vào). `vn.shop.crm.invite.CampaignSteps` (lưu, kiểm; `MAX_TOUCHES = 3`, `MIN_GAP_DAYS = 3`, `STOP_FOOTER`), `FollowupValidator` (kiểm cứng: không link, không số điện thoại, chỉ {ten}/{cua_hang}/{mien}, không chữ ép hay hứa hẹn, không "sẽ không nhắn lại", khác tin liền trước; Tin 1 của chiến dịch nhiều lần không còn câu "sẽ không nhắn lại"), `InviteService.MULTI_TEMPLATE` (Tin 1 gợi ý cho nhiều lần). Ô "Số lần gửi mời" ở trang chiến dịch, nói rõ tin nhắc chưa được gửi; Tin 1 chỉ sửa được khi chưa gửi cho khách nào.
6. **PR 5b (XONG, chia 5b-1 V39 và 5b-2 V40; mô tả những gì đã làm ở `docs/BAN-GIAO.md` mục 0.8; phần dưới là yêu cầu gốc, đã đáp ứng đủ, riêng "AI đề xuất số lần" chưa làm):** gửi tin nhắc. Cần: loại tin `FOLLOWUP` đi qua `ZaloMessageService` (cùng `blocker`, tính vào hạn mức ngày, một khách tối đa một tin chủ động mỗi ngày), bộ nối dùng đúng hàm gửi sẵn có theo mã Zalo của người đã nhận Tin 1 (cập nhật `ZaloGatewayShapeTest` và `zalo-bridge/test/no-send.test.mjs` đúng một chỗ gọi, giữ cấm `Random`), ghi `invitation_touch` TRƯỚC khi gọi (lỗi không rõ thì ghi UNKNOWN và không tự gửi lại), tự ngừng kiểm lại ngay trước mỗi lần gửi (khách kết bạn, vào nhóm, từ chối, nhắn xin ngừng, AI nhận diện từ chối kể cả lời mời bị tạm dừng, khách nhắn riêng bất cứ điều gì, lời mời hết hạn, chiến dịch bỏ duyệt hay tạm dừng, ngoài `start_on`/`end_on`), `gap_days` tính từ lần gửi thành công trước, câu `STOP_FOOTER` do mã thêm vào cuối mọi lần gửi của chiến dịch nhiều lần (kể cả Tin 1), chế độ thử chỉ nhắn số thử, `CampaignPolicy` áp cho tin nhắc, và ghi Tin 1 vào `invitation_touch`. AI đề xuất số lần ≤ trần (mặc định 1) sau khi PR này xong.
7. **PR 6:** nhóm đích và tự tạo nhóm theo chiến dịch; **PR 7:** khách quay lại và tự nhận khách mới thành tab của chiến dịch.
8. **PR 8:** gom giao diện chiến dịch thành một trang nhiều tab, gỡ các chỗ cấu hình trùng ở `/zalo`, `/test-runs`, `/today` (theo kế hoạch dọn giao diện đã duyệt, sau khi tự gửi chạy ổn trên thật).
9. Sau đó (cần duyệt thêm): AI gợi ý nội dung từng lần theo đợt; đề xuất "thử nghiệm" chia khung giờ để có lịch sử so sánh; lịch sử từng chiến dịch (phễu theo bước và khung giờ) đưa vào bản tổng hợp để AI học từ kết quả; chạy hằng ngày chỉ để sinh đề xuất (vẫn người duyệt).

## 7. Điều cần người dùng biết

**Câu hỏi mở cần chủ dự án chốt (chưa chặn PR 1):** (a) nhóm hàng (áo điều hòa / pin / phụ kiện) gán thế nào: AI phân loại tên hàng một lần rồi lưu nhóm vào khách (đúng quy tắc "ngôn ngữ tự nhiên thì dùng AI", chỉ gửi tên hàng đã che), hay người dùng khai báo bảng từ khóa; (b) có muốn thử A/B từ đợt đầu hay chờ có lịch sử; (c) ngưỡng tự dừng khi khách từ chối nhiều (đề xuất 5% trong 20 tin đầu).

- Hiện **chưa có lịch sử phản hồi thật** (chưa gửi cho khách thật), nên những đề xuất đầu tiên dựa vào thời tiết, mùa, độ mới của lần mua và quy mô khu vực; độ tin cậy sẽ thấp và AI phải nói rõ. Lịch sử tích lũy dần sau khi chạy thật.
- Bản đề xuất đầu tiên chỉ hữu ích khi `APP_WEATHER_ENABLED` đã bật (thời tiết) và đã đồng bộ đơn (khách, tỉnh).
- Việc gửi dữ liệu ra DeepSeek: chỉ số liệu tổng hợp theo vùng như bảng ở mục 2; không có gì định danh. Vẫn là dịch vụ bên ngoài nên nên xem JSON ở trang đề xuất trước khi bật.
