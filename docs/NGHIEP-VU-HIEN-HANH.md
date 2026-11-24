# Nghiệp vụ hiện hành của Adh (bản đọc, 23/11/2026)

**Tài liệu này là gì.** Một bản duy nhất mô tả Adh đang làm gì và sẽ làm gì, theo thứ tự thực tế từ lúc khách mua hàng đến lúc khách vào nhóm và mua lại. Viết bằng lời thường, có ví dụ ngắn. Dựa trên mã trong `main` của `nexagnet/adh` (tới PR #91), `CLAUDE.md` và các tài liệu thiết kế D18 đến D21. Bản này thay cho `NGHIEP-VU-GIAI-DOAN-1.md` (bản cũ, dừng ở PR #65, nhiều chỗ đã lỗi thời).

**Không có trong file này:** mật khẩu, khóa, số điện thoại, mã tài khoản Zalo, mã nhóm thật.

**Ba nhãn dùng xuyên suốt:**

- **[CHẠY ĐƯỢC]** đã có mã, đã kiểm bằng dữ liệu thật hoặc không cần Zalo (nhập đơn, khách, bản đồ, giấy in).
- **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]** đã lập trình và có test, nhưng mặc định tắt, và **chưa từng chạy với Zalo thật hay DeepSeek thật**. Đừng hiểu là "đã dùng được".
- **[CHƯA LÀM]** bạn đã quyết hoặc đã bàn, nhưng chưa có mã.

---

## 1. Tóm tắt một trang

Cửa hàng của bố mẹ bán chủ yếu **áo điều hòa và phụ kiện** (trong đó có pin), phần lớn khách mua qua TikTok Shop. Hàng đi bằng Viettel Post (đầu năm, tới 23/06) rồi chuyển hẳn sang J&T.

Adh lấy file đơn của hai hãng này, gộp theo số điện thoại, và tìm những **khách đã nhận hàng thành công đúng một lần**. Với nhóm khách đó, hệ thống dùng **một tài khoản Zalo phụ** (không phải tài khoản của bố mẹ) để:

1. tự nhắn **một tin mời** vào nhóm Zalo khách hàng cùng khu vực (Tin 1), nói rõ ưu đãi mua trực tiếp rẻ hơn TikTok 10% và miễn ship;
2. khách nào **đồng ý kết bạn** thì được **tự thêm vào nhóm** theo khu vực (Bắc, Trung, Nam), mẹ là phó nhóm;
3. khi chưa có nhóm hợp, hệ thống có thể **tự tạo nhóm mới** theo khu vực;
4. khách nhắn riêng lại thì **AI (DeepSeek)** đọc: nhận ra người nói "đừng nhắn nữa" và ghi vào sổ từ chối, trả lời các câu hỏi đơn giản về nhóm và ưu đãi, và chuyển mọi việc còn lại cho **người thật**;
5. một **AI khác đề xuất chiến dịch** (khu vực nào, ngày nào, giờ nào) kèm lý do; bạn đọc rồi bấm duyệt hoặc bỏ qua.

Mọi thứ tự động đều **tắt mặc định**. Máy chủ đang ở **chế độ thử**: dù bật hết, hệ thống chỉ được nhắn các số thử bạn khai báo, **khách thật không bị chạm**. Muốn chạy thật phải đặt một biến trên máy chủ (không có nút trên web).

Giai đoạn 1 (đã code xong) dừng ở "AI mời vào nhóm". Giai đoạn 2 (AI trả lời trong nhóm, đội bán hàng nhiều người, tra nhiều sản phẩm) **chưa làm**, chờ bạn duyệt.

---

## 2. Ai là ai

- **Bạn (Phùng Việt):** chủ dự án; người bật công tắc, duyệt đề xuất, chạy thử.
- **Bố mẹ:** chủ cửa hàng. **Mẹ** là người dùng chính, dùng **điện thoại**, là **phó nhóm** của các nhóm Zalo, trả lời khách và chốt đơn bằng **Zalo thật của mẹ**. Giao diện vì thế 100% tiếng Việt, chữ rõ, gọi là "trợ lý AI" chứ không gọi "bot".
- **Tài khoản Zalo phụ (MỘT tài khoản):** dùng cho tất cả: nhắn Tin 1, thêm người vào nhóm, tạo nhóm, và trả lời với tư cách "Trợ lý AI của <tên shop>". Bạn đã chấp nhận rủi ro tài khoản này bị Zalo khóa. **Không bao giờ dùng tài khoản của bố mẹ.**
- **Khách:** người đã mua và nhận hàng thành công. Nhiều người lớn tuổi.
- **Đăng nhập web:** chỉ có **một** tài khoản quản trị (`admin`). Việc "người thật xử lý" hiện là hàng đợi chung trên web, chưa chia cho từng người.

---

## 3. Đường đi từ đầu đến cuối

```
Khách mua (TikTok) ─► hãng giao hàng (Viettel Post / J&T)
        │
        ▼
1. Nhập file đơn vào Adh (làm tay)           →  hệ thống biết khách qua vận đơn
2. Phân loại: mua 1 lần / từ 2 lần / cần kiểm tra
3. Chia khu vực: Bắc / Trung / Nam (theo tỉnh)
4. Khách mua 1 lần được "duyệt" (tay, hoặc chiến dịch tự nhận khách mới)
5. Chiến dịch: ai, khi nào, bao nhiêu khách một ngày, mấy lần nhắn
        │   (AI có thể đề xuất chiến dịch; bạn duyệt)
        ▼
6. Tin 1 tự gửi (08:00–20:00, nghỉ cố định, tối đa 50 khách/ngày)
        │
        ├─ khách im lặng ........ không nhắn lại (hoặc nhắc tối đa 2 lần nếu chiến dịch bật)
        ├─ khách nhắn "đừng nhắn nữa" → AI ghi sổ từ chối, không bao giờ nhắn lại
        ├─ khách nhắn hỏi ........ AI trả lời câu đơn giản, còn lại chuyển người thật
        └─ khách đồng ý kết bạn
                  ▼
7. Tự thêm vào nhóm khu vực (nhóm có sẵn, hoặc tự tạo nhóm mới)
        │   lỗi riêng từng người → Tin 2 (link nhóm)
        ▼
8. Khách ở trong nhóm, mẹ (phó nhóm) tư vấn và chốt đơn bằng Zalo thật
        ▼
9. Mua lại → đo hiệu quả chiến dịch, đối soát J&T
```

Song song có **lối vào thứ hai**: tờ giấy A5 bỏ vào đơn hàng mới, in mã QR; khách tự quét để vào nhóm (mục 14).

---

## 4. Từng bước, chi tiết

### 4.1 Nhập đơn **[CHẠY ĐƯỢC]**

Adh **không** lấy đơn TikTok. Adh chỉ biết khách qua **vận đơn của hãng giao hàng** (tên, số điện thoại, địa chỉ người nhận, trạng thái giao).

- **Viettel Post:** xuất Excel từ cổng quản lý vận đơn, đưa vào màn *Nhập đơn*. Đã kiểm với file thật. Tháng 1 và 2 của Viettel Post trống là **bình thường** (bán chủ yếu trong năm nay), đừng coi là thiếu dữ liệu.
- **J&T: làm tay, đã chốt.** Bạn hoặc mẹ tự đăng nhập cổng khách hàng J&T, xuất Excel (mỗi lần xem tối đa 1 tháng), rồi đưa vào Adh. Hệ thống **không tự đăng nhập J&T, không lưu mật khẩu hay cookie J&T**. Có tool riêng chạy trên máy bạn (`tools/jt-edge-sync`, dùng hồ sơ Edge riêng, bạn tự đăng nhập một lần): tool chỉ nhận file tải về và hỏi xác nhận trước khi nhập.
- Nhập nhiều file một lúc được. Cùng vận đơn nằm ở hai file thì lấy bản mới. Nhập lại cùng file **không làm tăng số lần mua**.
- Hệ thống gợi ý "nên xuất từ ngày nào" (từ đơn mới nhất đã nhập, lùi 14 ngày, và lùi tới đơn cũ chưa có kết quả cuối trong 30 ngày) và nhắc ở Tổng quan nếu quá 7 ngày chưa nhập. Bạn đã chốt giữ nguyên các mốc 14, 30, 7.
- Số điện thoại được chuẩn hóa về một dạng. Số thiếu hoặc sai vào danh sách lỗi, **không đoán**.

*Ví dụ:* sáng thứ Hai bạn xuất file J&T của tuần trước, kéo vào *Nhập đơn*, hệ thống báo "thêm 18 khách mới, 2 dòng lỗi số điện thoại", bạn tải file lỗi để xem.

### 4.2 Phân loại khách **[CHẠY ĐƯỢC]**

- Chỉ đơn **giao thành công** mới tính là một lần mua. Đơn hủy, hoàn, đang giao, **trạng thái chưa rõ** đều không tính.
- Nhiều vận đơn cùng một mã đơn bán hàng là **một** lần mua. Vận đơn không có mã đơn thì mỗi vận đơn tính một lần, và nếu có thể bị đếm dư thì khách bị gắn cờ **"cần kiểm tra"**.
- Đúng 1 lần mua thành công là **ONE_TIME** ("mua một lần"): đây là danh sách để mời. Từ 2 lần là **REPEAT**: chỉ có danh sách riêng cho bố mẹ tự tư vấn, **không bao giờ được mời**, kể cả có duyệt.
- Đơn "đã lấy hàng / đang giao" quá 30 ngày hãng không cập nhật là **đơn kẹt**: không tính thành công, không tính hủy, khách bị gắn cờ kiểm tra.
- Màn *Trạng thái đơn*: những trạng thái lạ của hãng (ví dụ "Kiện vấn đề", "Kết thúc") mặc định **chưa gán nghĩa** nên không tính là thành công. Bạn gán nghĩa tay ở đó.
- Hệ thống tự phân loại lại khách mỗi đêm lúc 03:15.

*Ví dụ:* chị Lan có 2 vận đơn khác mã đơn, đều giao thành công: chị là REPEAT, không nhận Tin 1. Anh Minh có 1 vận đơn giao thành công: ONE_TIME, đủ điều kiện mời.

**[CHƯA LÀM] hai việc bạn đã quyết:** (a) tự gán nghĩa cho trạng thái J&T lạ dựa trên file dữ liệu J&T thật và có giao diện chỉnh; (b) dùng file đối soát J&T để **đổi** phân loại một lần / từ hai lần (hiện file đối soát chỉ bổ sung mã đơn và ngày ký nhận, không đổi phân loại).

### 4.3 Khu vực: Bắc, Trung, Nam **[CHẠY ĐƯỢC]**

- Tỉnh của khách suy ra từ **địa chỉ vận đơn** (tên tỉnh xuất hiện cuối cùng). Không chắc thì ghi **"Chưa rõ"**, không đoán. Tây Nguyên xếp miền Trung. Cấp bậc: tỉnh, vùng (8 vùng), miền (3 miền).
- Lý do kinh doanh: mùa lạnh chủ yếu khách miền Nam mua; người ở gần nhau nói chuyện cùng hoàn cảnh.
- **Bản đồ khách** theo tỉnh (chỉ số đếm, không địa chỉ, không số điện thoại) và **nhắc việc thời tiết** cho mẹ (tắt mặc định, chỉ nhắc trên web, không nhắn khách). Ngưỡng nhắc: lạnh dưới 13°C (Bắc), 16°C (Trung), 22°C (Nam); nóng từ 37°C; chỉ khi tỉnh có từ 10 khách. Dự báo lấy từ MET Norway (chỉ gửi tọa độ thành phố tỉnh lỵ, không gửi dữ liệu khách).

### 4.4 Duyệt khách được phép mời **[CHẠY ĐƯỢC] và [CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

- Mặc định hệ thống **không liên hệ ai**. Khách là "ứng viên" khi: ONE_TIME, không bị gắn "cần kiểm tra", không nằm trong sổ từ chối, không bị chặn. Ứng viên phải được **duyệt** thì mới vào chiến dịch.
- Mỗi lần duyệt ghi **cơ sở liên hệ**: "chủ cửa hàng chịu trách nhiệm, khách đã mua và nhận hàng". Bạn đã nhận rủi ro pháp lý về việc nhắn người chưa đồng ý (D3); chưa có xác nhận của luật sư.
- **Hai cách duyệt:**
  1. **Duyệt tay** ở màn *Danh sách mời* (từng khách hoặc "Duyệt N khách").
  2. **Chiến dịch tự nhận khách mới** (chốt 23/11, thay cho việc duyệt từng người): ở trang chiến dịch bấm "Bật tự nhận khách mới". Từ lúc bật, **khách mới nhập về** mà mua một lần, không cần kiểm tra, chưa từ chối, chưa từng được mời, đúng khu vực của chiến dịch thì **được tự duyệt** (người duyệt ghi là `chien-dich-tu-nhan`) rồi được mời. Mỗi lúc chỉ **một** chiến dịch được tự nhận. Khách **có từ trước lúc bật không bao giờ bị tự duyệt** (vì mẹ có thể chủ ý giữ lại). Hàng chờ có trần: không quá 300 và không quá (hạn mức ngày x số ngày link còn hạn), mỗi nhịp nhận tối đa 50 khách. Tắt ngay khi bỏ duyệt, tạm dừng tất cả hoặc tắt công tắc tự gửi. **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**
- **Khi nào được mời:** bạn đã chốt **mời ngay khi được duyệt và vào chiến dịch**, không chờ thêm số ngày sau khi giao.

*Ví dụ:* bạn bật "tự nhận khách mới" cho chiến dịch Miền Nam vào thứ Hai. Thứ Ba bạn nhập file mới có 12 khách mới ở miền Nam: cả 12 tự được duyệt và xếp hàng nhận Tin 1. 335 khách đã duyệt từ trước thì vẫn chỉ được mời theo cách cũ (chiến dịch thường).

### 4.5 Chiến dịch **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

Chiến dịch là "một đợt mời": chọn **ai** (khu vực: miền, vùng, tỉnh hoặc "chưa rõ"; tối đa 300 khách một chiến dịch), **số ngày link còn hạn** (1 đến 90), **nội dung Tin 1**, rồi tới **lịch và nhịp**, **số lần gửi**.

**Quy tắc cốt lõi: hệ thống đặt trần, chiến dịch chỉ được chặt hơn.** Chiến dịch không bao giờ nới được giới hạn an toàn của hệ thống. Ô để trống nghĩa là "theo hệ thống", nên chiến dịch cũ chạy y như trước.

**Phải duyệt hai lớp.** Tạo chiến dịch chưa gửi gì. Phải bấm **"Duyệt cho hệ thống tự gửi"** ở trang chi tiết; bỏ duyệt là dừng ngay. Ngoài ra còn công tắc tự gửi ở trang `/zalo` (xem mục 5). Chiến dịch do AI đề xuất cũng chỉ tạo ra **chiến dịch chưa duyệt**.

**Mỗi khách chỉ có một lời mời trong đời.** Khách đã có lời mời ở bất kỳ trạng thái nào thì không vào chiến dịch mới.

**Lịch và nhịp riêng của chiến dịch (D21, V37):** ngày bắt đầu và kết thúc, khung giờ riêng, hạn mức ngày riêng, khoảng nghỉ riêng. Chỉ được **hẹp hơn** hệ thống: khung giờ là phần giao với 08:00 đến 20:00, hạn mức chỉ thấp hơn, khoảng nghỉ chỉ dài hơn. Nhập giá trị nới hơn thì bị từ chối. Chiến dịch chưa tới ngày thì hiện "đang chờ".

**Mời 1 đến 3 lần (D21, V38 đến V40):**
- Mặc định **1 lần** (chỉ Tin 1). Có thể chọn 2 hoặc 3 lần; **trần cứng 3 lần cho một khách** (trong mã, không chỉnh được).
- Lần 2 và 3 là **tin nhắc**. Khoảng cách tối thiểu **3 ngày** (chọn 3 đến 30 ngày) kể từ lần gửi thành công trước.
- Chiến dịch nhiều lần dùng mẫu Tin 1 khác, **bỏ câu "em sẽ không nhắn lại ạ"** (vì còn tin nhắc thì câu đó sai). Mã tự thêm vào cuối **mọi** lần gửi một câu xin ngừng ("nhắn dừng").
- Tin nhắc phải khác tin trước, không có link hay số điện thoại, không chữ ép ("cuối cùng", "chỉ còn", hạn gấp), không hứa hẹn.
- **Tự ngừng ngay** (kiểm lại ngay trước mỗi lần gửi): khách đã kết bạn, vào nhóm, từ chối, nhắn "dừng", **nhắn riêng bất cứ điều gì** (khi đó chuyển người thật, không nhắc nữa), lời mời hết hạn, chiến dịch bỏ duyệt hoặc tạm dừng hoặc quá ngày kết thúc.
- Tin nhắc **tính vào hạn mức ngày** như Tin 1. Chỉ gửi khi hệ thống **đang nghe được tin riêng** của khách (ba cờ đọc tin riêng bật), vì không nghe được lời xin ngừng thì không nhắc.
- Lỗi không rõ đã gửi chưa thì đánh dấu và **không bao giờ tự gửi lại**.
- Chiến dịch có `gap_days` dài hơn số ngày link còn hạn thì lần nhắc đó **không bao giờ gửi** (nên đặt số ngày link đủ dài).

*Ví dụ:* chiến dịch "Miền Nam, 40 khách, 2 lần, nhắc cách 5 ngày, link 14 ngày": ngày 1 gửi Tin 1; ngày 6 khách nào chưa kết bạn, chưa nhắn gì thì nhận tin nhắc (kèm "nhắn dừng"). Khách đã kết bạn ngày 3 thì không nhận tin nhắc.

**[CHƯA LÀM] (D21 bước 4 còn dở):** nhiều khu vực trong một chiến dịch, lọc theo độ mới của lần mua, thứ tự lấy khách, chọn ngày trong tuần, hoãn khi mưa lớn (chỉ được hoãn, không bao giờ gửi nhanh hơn), tăng dần theo ngày, tự dừng khi tỷ lệ từ chối cao, thử A/B. Cũng chưa làm: nhóm đích và tự tạo nhóm theo chiến dịch, khách quay lại và tự nhận thành thiết lập riêng của chiến dịch (hiện là công tắc chung ở `/zalo`), và gom giao diện chiến dịch thành một trang nhiều tab (chỉ làm sau khi tự gửi chạy ổn trên thật).

### 4.6 Tự gửi Tin 1 (D18) **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

Cách làm là **công khai và có giới hạn**, không "giả người".

**Nội dung Tin 1** (mặc định; sửa ở trang *Chiến dịch*; `{ten}`, `{cua_hang}`, `{mien}` được thay khi gửi):

> Chào anh/chị [Tên khách], em bên [Tên shop]. Anh/chị dùng sản phẩm shop gửi thấy ổn không ạ? Em mời mình vào nhóm Zalo khách hàng [Miền Trung / Miền Nam] để được hướng dẫn sử dụng sản phẩm và nhận ưu đãi. Anh chị mua trực tiếp qua nhóm sẽ rẻ hơn mua qua TikTok 10% và được miễn ship đó ạ. Anh/chị đồng ý kết bạn, em sẽ thêm anh chị vào nhóm ạ. Không cần thì anh/chị bỏ qua, em sẽ không nhắn lại ạ.

Tin không kèm link: khách chỉ cần đồng ý kết bạn. Tin này là tin tư vấn có khuyến mại.

**Các chốt chặn (mã bắt buộc, không dựa vào lời hứa):**

| Chốt | Giá trị |
|---|---|
| Giờ gửi | chỉ **08:00 đến 20:00** |
| Hạn mức | tính theo **số khách được nhắn chủ động** (Tin 1 và tin nhắc): mặc định **10 khách/ngày**, chỉnh được tới **trần cứng 50 khách/ngày** (bạn chốt 23/11: "50 khách, không giới hạn số tin") |
| Không tính vào hạn mức | Tin 2, tin mẫu trả lời khách đã nhắn mình, câu AI trả lời. Nhưng chúng **vẫn** theo giờ gửi, khoảng nghỉ và cầu dao |
| Khoảng nghỉ | **cố định** giữa hai tin: mặc định 120 giây, tối thiểu 100 giây. **Không ngẫu nhiên** |
| Mỗi khách | đúng **một Tin 1** trong đời. Hệ thống đánh dấu "đã gửi" **trước** khi gọi Zalo để hai nhịp chồng nhau không gửi trùng |
| Lỗi không rõ | đánh dấu thất bại, **không tự gửi lại** |
| Kiểm lại trước khi gửi | khách còn đủ điều kiện (chưa từ chối, chưa bị chặn, chưa bị xóa) |
| Cầu dao | tự ngắt khi Zalo cảnh báo, mất phiên đăng nhập, lỗi lạ, 3 lỗi riêng từng người liên tiếp, hoặc 5 lần "không tìm thấy" liên tiếp. Ngắt rồi giữ nguyên tới khi người bấm "Mở lại" |
| Phanh khẩn cấp ở bộ nối | đếm **mọi** tin, mặc định 150 mỗi ngày, vượt thì tự dừng |

Muốn tự gửi chạy cần **ba thứ cùng lúc**: biến môi trường tự gửi bật, dịch vụ cầu nối Zalo đang chạy, và công tắc/nấc trên trang `/zalo`; thêm chiến dịch đã duyệt. Chế độ thử (mục 11) chặn khách thật ở trên cả những thứ này.

**Hệ thống tìm người nhận trên Zalo bằng số điện thoại.** Người đặt Zalo không cho tìm bằng số thì báo "không tìm thấy", lời mời đóng thất bại. Khách đó không bị nhắn gì nhưng vẫn vào nhóm được bằng mã QR. **Chưa ai biết tỷ lệ này**, vì chưa chạy thật.

**Cấm tuyệt đối thủ thuật né phát hiện bot của Zalo:** không giả vân tay, `imei`, `userAgent` thiết bị; không ngẫu nhiên hóa thời gian cho giống người; không proxy xoay; không xoay vòng nhiều tài khoản; không giả thao tác. Có test khóa cứng trong mã. Ai yêu cầu (kể cả bạn) thì hệ thống và AI làm việc sẽ **từ chối** và đề xuất hướng chính thức (Zalo OA/ZNS) hoặc giữ lô nhỏ, chậm, minh bạch. Việc bạn chấp nhận rủi ro bị khóa **không** mở rộng sang việc né phát hiện.

### 4.7 Kết bạn rồi vào nhóm **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

- **Tài khoản phụ tự gửi lời mời kết bạn (từ 24/11/2026):** ngay sau Tin 1, hệ thống tự gửi MỘT lời mời kết bạn kèm một câu ngắn cho người đó (mỗi người một lời mời, không bao giờ gửi lại). Không gửi cho ai đã kết bạn, vào nhóm, nhắn riêng, từ chối, bị hủy hay hết hạn. Hạn mức lời mời riêng mỗi ngày (mặc định 10, đặt 0 là tắt, trần cứng 50, chỉnh ở `/zalo`), cùng khoảng nghỉ cố định, khung giờ 08:00 đến 20:00 và cầu dao với tin. Hệ thống vẫn không tự nhận lời mời, không làm gì để né Zalo. Khách đồng ý thì hệ thống **nghe sự kiện** "có người vừa đồng ý kết bạn" rồi đối chiếu với mã Zalo đã lưu lúc gửi Tin 1 để biết đó là khách nào. Không đối chiếu được thì khách vào **hàng ghép tay**. **Lỡ sự kiện (24/11/2026):** cứ 20 phút một lần và mỗi khi cầu nối nối lại, hệ thống hỏi cầu nối trong số khách đã nhận Tin 1 mà chưa ghi kết bạn, ai đã là bạn của tài khoản phụ, rồi xử lý như có sự kiện. Việc này CHỈ ĐỌC và chỉ hỏi khách của hệ thống: không lưu bạn ngoài hệ thống, không đọc tên hay số của họ.
- **Chọn nhóm cho khách vừa đồng ý (chốt 24/11/2026):** (1) nhóm cùng tỉnh còn dưới 5 khách; (2) không có thì nhóm cùng vùng, rồi cùng miền, còn dưới 5 khách; (3) không có thì khách vào **hàng chờ của khu vực**. **Nhóm miền khác bị loại**, kể cả khi nhóm miền mình đầy.
- Mỗi nhóm mục tiêu **5 khách** (chỉ đếm khách; **không tính** mẹ và tài khoản của cửa hàng). Nhóm đủ 5 thì coi là "đầy" với khách mới; chỉ **khách dư lẻ** (đã chờ lâu) mới được vào nhóm cùng tỉnh đang 5 đến 7 khách, tối đa **8 khách** (trần mềm, chỉnh ở `/zalo`). "Đầy" tính: khách thật + (lượt bấm link chờ x 0,5) + số chỗ đang giữ. Tỷ lệ 0,5 chỉ là giả định để chia nhóm.
- **Khách dư lẻ:** chờ chưa quá 3 ngày thì chỉ vào nhóm cùng tỉnh; từ **3 ngày** được gộp lên nhóm cùng vùng, rồi cùng miền; từ **7 ngày** khu vực chỉ cần **2 khách** là tạo nhóm và được ưu tiên; từ **14 ngày** mà vẫn chưa có chỗ thì hệ thống **báo mẹ**. Ba mốc ngày và trần 8 khách chỉnh ở `/zalo`.
- **Giữ chỗ 24 giờ** cho khách vừa kết bạn để hai khách kết bạn cùng ngày không bị dồn vào nhóm chỉ còn một chỗ.
- Muốn tự thêm được, nhóm cần đủ: có link mời, có mã nhóm Zalo, đang "nhận khách", và tài khoản cửa hàng đã ở trong nhóm. Thiếu một thứ thì khách **chờ**, không bị đẩy sang nhóm khác.
- **Nhóm tự thêm và nhóm của link là MỘT nhóm** (cùng một nhóm được giữ chỗ cho mỗi khách).
- Chưa có nhóm hợp: khách giữ trạng thái "đã kết bạn, chờ nhóm", hệ thống ghi một dòng "chờ nhóm" theo mã tỉnh (không tên, không số). Có tự tạo nhóm thì xem mục 4.8.
- **Hạn mức thêm nhóm tách riêng khỏi hạn mức gửi tin:** mặc định 50 người/ngày, 5 người/giờ, cách nhau 2 phút, trần cứng 100/ngày (khách vượt hạn mức hôm nay sẽ chuyển sang ngày hôm sau, theo thứ tự đồng ý); khung giờ mặc định của cấu hình là 07:00 đến 22:00 (khác với 08:00 đến 20:00 của tin nhắn). Chỉnh ở trang `/zalo`. Có bốn nấc: Tắt / Chỉ nghe (người thêm tay) / Mẹ duyệt từng người / Tự thêm trong hạn mức.
- **Lỗi khi thêm vào nhóm được tách ba loại (24/11/2026):** lỗi **riêng người** (họ chặn việc được thêm) chỉ bỏ qua người đó; liền 4 lần cùng một nhóm thì nhóm đó tạm dừng, 6 trong 8 lần gần nhất thì dừng cầu dao (nghi Zalo hạn chế). Lỗi **cả nhóm** (nhóm đầy, bị giải tán) đánh dấu nhóm, khách được chuyển nhóm khác; 3 lần liền ở các nhóm khác nhau thì dừng cầu dao. Lỗi lạ hoặc Zalo cảnh báo dừng cầu dao ngay và **chờ người mở lại**. Người dùng chưa có mã lỗi thật của Zalo nên danh sách mã nằm ở biến `BRIDGE_PERSON_ERROR_CODES` và `BRIDGE_GROUP_ERROR_CODES` để điền sau khi thử.
- **Khách nhắn Tin 1 mà không kết bạn quá 14 ngày** chỉ hiện nhãn "không phản hồi", **không bao giờ nhắn lại** (ngoài tin nhắc của chiến dịch nhiều lần).

*Ví dụ:* anh Minh ở Cần Thơ bấm đồng ý kết bạn lúc 9 giờ. Hệ thống ghép anh với lời mời, chọn nhóm "Áo điều hòa ADH - Miền Nam - 01" (còn chỗ), giữ chỗ 24 giờ, rồi thêm anh vào nhóm trong hạn mức. Mẹ (phó nhóm) thấy anh xuất hiện trong nhóm và chào.

**Tin 2 (link nhóm):** nếu việc thêm vào nhóm **lỗi riêng với người đó** (người ta bật "chỉ quản trị viên được thêm", hoặc chặn tài khoản...), hệ thống tự gửi **Tin 2** cho người đã kết bạn, mỗi người một lần, ưu tiên trước Tin 1:

> Mình vào nhóm theo link này nhé: [Link nhóm].

Khách tự bấm link để vào. Hệ thống **không biết** người đó vào nhóm thật chưa (xem mục 9).

### 4.8 Tự tạo nhóm Zalo theo khu vực, mẹ làm phó nhóm **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

Khi có khách đã kết bạn mà **chưa có nhóm hợp khu vực**, hệ thống lập **đề xuất tạo nhóm mới**.

- **Cách chọn phạm vi:** xét từ nhỏ đến lớn: tỉnh, rồi vùng, rồi miền. Phạm vi nhỏ nhất có **từ 3 khách chờ** (chỉnh được; khách chờ từ 7 ngày thì chỉ cần 2) thì chọn. Tỉnh ít khách thì tự gộp lên vùng rồi miền khi gộp đủ ngưỡng, không tạo nhóm 1 đến 2 người. Nhóm mới lấy tối đa 5 khách chờ lâu nhất, mẹ là phó nhóm. Mỗi lượt (10 phút) làm một việc, chỉ trong khung giờ và theo khoảng nghỉ cố định.
- **Tên nhóm (chốt 23/11):** `Áo điều hòa ADH - <Khu vực> - <số thứ tự>`, ví dụ `Áo điều hòa ADH - Miền Nam - 01`. Không dùng tiền tố "Pin" vì cửa hàng bán chủ yếu áo điều hòa.
- **Ba nấc:** Tắt / **Xác nhận** (chủ shop bấm duyệt từng nhóm; mặc định khi bật) / Tự động (chỉ mở sau khi đã chạy Xác nhận và tick xác nhận đã thử).
- **Giới hạn:** **không có trần số nhóm mới mỗi ngày** (chốt 24/11/2026), nghỉ cố định **60 phút** giữa hai lần, chỉ 08:00 đến 20:00, không ngẫu nhiên, dùng chung cầu dao với gửi tin, **lỗi không rõ thì không tự thử lại**.
- **Tài khoản cửa hàng là người tạo (chủ nhóm); mẹ là phó nhóm.** Bạn chọn mẹ bằng **số điện thoại của mẹ** ở trang `/zalo`: bộ nối tra tên và ảnh (chỉ được khi mẹ bật cho tìm bằng số; trần 5 lần mỗi giờ ở bộ nối, 8 lần mỗi ngày và nghỉ 60 giây ở Adh), bạn nhìn đúng người rồi bấm xác nhận. **Adh không lưu số điện thoại**, chỉ lưu mã Zalo, tên và ảnh. Mẹ phải là bạn Zalo của tài khoản phụ trước. Hệ thống **không** đăng nhập hay cấu hình gì tài khoản của mẹ.
- **Phải thử tay 1 nhóm thật với 2 số của bạn** trước khi bật cho khách thật. Nhiều chuyện chưa biết: số thành viên tối thiểu để tạo nhóm, độ dài tên, giới hạn số nhóm tạo mỗi ngày, mã lỗi.
- Nếu tạo nhóm tự động thất bại, đường cũ vẫn còn: người tạo nhóm tay rồi dán link vào Adh.

### 4.9 Khi khách nhắn riêng: AI làm gì **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

AI là **DeepSeek (mô hình flash)**, gọi qua API, tắt mặc định. Quy tắc bạn đặt: **"cái gì là ngôn ngữ tự nhiên, không có quy luật thì dùng AI"**, nên **không có luật từ khóa**.

**Luật chung cho mọi chỗ gọi AI:**
- Chỉ gửi **văn bản đã che** thông tin cá nhân. **Không bao giờ gửi** số điện thoại, địa chỉ, mã Zalo, tên khách.
- Mọi lượt đi qua một cổng chung có **trần lượt mỗi ngày** và cầu dao; AI lỗi thì thành "không có câu trả lời".
- Tin khách viết là **dữ liệu không đáng tin**: câu trong tin không được đổi hành vi hệ thống; đầu ra AI phải đúng khuôn, sai là bỏ.
- Phải bật **cả ba cờ**: `APP_ZALO_INBOX_ENABLED` (Adh đọc tin riêng), `BRIDGE_INBOX_ENABLED` (bộ nối giữ tin riêng, chỉ trong bộ nhớ, tối đa 6 giờ), `APP_AI_ENABLED` (kèm khóa và tên mô hình).

**Ai được AI xử lý?** Chỉ **người đã nhận tin của hệ thống** (Tin 1 hoặc Tin 2, hoặc đã vào nhóm). Người lạ nhắn vào không được xử lý.

Mỗi tin đến được AI gắn một nhãn (từ chối, muốn mua, khiếu nại, câu hỏi, khác), rồi đi theo một trong các đường dưới đây.

#### a) Nhận ra khách từ chối (D19)

- AI chắc **từ 0,8 trở lên** rằng khách từ chối: **tự ghi sổ từ chối**, lời mời hủy, không bao giờ nhắn lại. Luôn có nút **Hoàn tác**.
- AI **lỗi, hết hạn mức, sai khuôn, hoặc không chắc**: hệ thống **tạm dừng tin cho khách đó** (lời mời chuyển "đã hủy", ghi lại trạng thái cũ) và **báo người thật**. Mẹ chọn "từ chối thật" hoặc "cho tiếp tục". Nghiêng về an toàn: không chắc thì dừng và hỏi.
- Ghi sổ xong thì **bỏ nội dung tin**, chỉ giữ nhãn và giờ; trích đoạn đã che tối đa 300 ký tự giữ 30 ngày.

*Ví dụ:* khách nhắn "mình không cần đâu, đừng nhắn nữa nhé". AI chắc 0,95: ghi sổ từ chối. Khách khác nhắn "để mình xem lại đã" AI chỉ chắc 0,5: hệ thống tạm dừng và hiện ở danh sách "Khách nhắn riêng, cần mẹ xem".

#### b) AI tự trả lời khách tự nhắn tới (D20)

- **Phạm vi hẹp:** chỉ **"mời vào nhóm"** và **"ưu đãi khi vào nhóm"**. Mọi thứ khác (giá, còn hàng, bảo hành, đổi trả, phàn nàn, muốn mua, muốn gặp người thật, tin mơ hồ) **chuyển người thật**.
- **Khách nhắn trước thì không giới hạn số câu trả lời** (mỗi tin đến một câu). Khác với tin chủ động, vốn bị hạn mức.
- **Đường đi:** AI phân loại phạm vi (nghi ngờ thì coi là ngoài phạm vi; cần chắc từ 0,8 mới coi là trong phạm vi) → AI soạn câu **chỉ từ "Bảng thông tin cửa hàng"** → **bộ kiểm cứng bằng mã** → nháp hoặc gửi.
- **Bộ kiểm cứng** (không dùng AI kiểm AI) chặn: câu rỗng hoặc quá 500 ký tự; đường link; số điện thoại, email; **mọi con số không có trong bảng thông tin** (nên không bịa giá hay phần trăm); nhắc nền tảng khác ngoài TikTok; hứa hẹn ("cam kết", "đảm bảo", tặng quà, mã giảm); đổi trả và hoàn tiền; chê hàng hay đối thủ; giả làm người hoặc chủ shop; xin địa chỉ, số điện thoại, mật khẩu, chuyển khoản. Câu không qua kiểm thì **không bao giờ gửi**.
- **Ba nấc:** Tắt / **Chỉ soạn nháp** / Tự động (Tự động chỉ mở sau khi đã chạy nháp và tick xác nhận).
- **Mỗi câu kèm chữ ký "Trợ lý AI của <shop>"** và cách gặp người thật. **Không bao giờ giả làm người hay giả làm mẹ.** Khách từng từ chối không bao giờ được trả lời ở đường này.
- **Hồ sơ của đúng khách đang nhắn** (khu vực, hàng đã mua, nhóm đã vào, các mốc, vài lượt trao đổi trước) được đưa cho AI để trả lời đúng người (ví dụ đã vào nhóm rồi thì không mời lại). **Không bao giờ** gửi số điện thoại, địa chỉ, mã vận đơn, mã đơn, thanh toán, mã Zalo, tên khách, link nhóm. Trường nào cho gửi chỉnh ở `/zalo/reply`; mỗi nháp ghi rõ đã gửi những trường nào (chỉ tên trường, không có giá trị).
- Chữ của khách và câu AI soạn tự xóa sau 30 ngày.
- Có ô **"Thử một câu"** để chạy thử chuỗi này bằng câu giả mà không gửi gì; bạn nên thử khoảng 30 câu (trong và ngoài phạm vi, cả câu cố lừa AI) trước khi bật.

*Ví dụ:* khách đã vào nhóm nhắn "vào nhóm có ưu đãi gì vậy?". AI soạn: "Dạ, anh/chị mua trực tiếp qua nhóm được giảm 10% so với giá TikTok và miễn phí ship ạ. (Trợ lý AI của <shop>; cần gặp người thật xin nhắn 'gặp người thật' ạ.)". Khách nhắn "áo này bảo hành mấy tháng?" thì bảng thông tin để trống ô bảo hành nên AI **không trả lời**, việc chuyển người thật.

#### c) Khách từng từ chối nay nhắn lại muốn mua (chốt 23/11)

- Chỉ khi **chính khách nhắn trước**. Hệ thống không bao giờ nhắn người chưa nhắn.
- **Ba nấc:** Tắt (mặc định) / **Chỉ tạo việc** / Tự động (Tự động chỉ mở sau khi đã chạy Chỉ tạo việc và tick xác nhận).
- Nấc Tự động: AI chắc **từ 0,85** rằng khách "muốn mua" thì **tự gỡ cờ** (chỉ đánh dấu "đã gỡ", không xóa gì), đưa vào hàng chờ thêm nhóm sẵn có, và gửi **MỘT tin mẫu cố định** (không do AI viết, nói rõ là trợ lý tự động và cho đường từ chối), chỉ trong 24 giờ kể từ lúc khách nhắn. **Tối đa một lần tự gỡ mỗi khách trong 30 ngày.** Dưới 0,85, AI lỗi hay sai khuôn thì chỉ tạo việc cho người thật.
- Mẹ gỡ tay thì **không** có tin mẫu (mẹ tự nhắn trên Zalo). Luôn có nút Hoàn tác.
- **Khách từ chối vẫn bị giữ cờ "không nhắn nữa"**, sổ từ chối (mã băm) vẫn còn; gỡ chỉ ghi `lifted_at`. Khách đã yêu cầu **xóa dữ liệu** thì không bao giờ gỡ được.

#### d) Bảo hành và khiếu nại (V35)

Khách đã nhận tin nhắn riêng báo lỗi (AI gắn nhãn khiếu nại): AI **phân loại** (lỗi sản phẩm, giao hàng, hướng dẫn, hoàn tiền, khác), chấm **mức gấp** (nguy cơ an toàn như pin nóng, phồng, mùi khét là CAO), **tóm tắt**, gợi ý có nên xin ảnh, và soạn một **bản nháp**; hệ thống tạo **MỘT việc** cho người thật kèm **3 đơn gần nhất** của khách để mẹ khỏi tra cứu.

- **Không bao giờ tự gửi gì cho khách. Không hứa bảo hành hay hoàn tiền.** Nháp qua bộ kiểm riêng (không số, không link, không lời hứa).
- AI lỗi thì vẫn tạo việc ("Chưa phân loại"), việc không bao giờ mất vì AI hỏng.
- **Chưa đọc ảnh:** bộ nối chỉ đọc chữ và DeepSeek chỉ nhận chữ. AI chỉ gợi ý "nên xin khách ảnh"; mẹ xem ảnh trên Zalo.
- Công tắc riêng ở `/zalo/reply`, tắt mặc định. Xử lý xong thì chữ khách, tóm tắt và nháp bị xóa ngay.

### 4.10 Việc cần người thật xử lý **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

Trang `/handoffs` là **hàng đợi chung** (cột `assigned_to` để dành cho đội bán hàng ở giai đoạn 2, không gắn cứng mẹ). Gồm bảy khối: việc trợ lý chuyển; khách nhắn riêng cần xem (nghi từ chối, nút "Từ chối thật" / "Cho tiếp tục"); bảo hành và khiếu nại (gấp xếp trước); khách nhắn riêng cần người thật trả lời; khách từng từ chối nay nhắn lại (nút "Gỡ cờ" / "Giữ"); khách quay lại đã gỡ cờ trong 14 ngày (Hoàn tác); trợ lý AI đã ghi khách từ chối trong 14 ngày (Hoàn tác).

Báo việc cho mẹ hiện **chỉ trên web**; kênh báo ngoài trang Adh (ví dụ qua Zalo của mẹ) **chưa chốt**.

### 4.11 AI đề xuất chiến dịch và thời điểm gửi (D21) **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

Yêu cầu của bạn: lấy thời tiết, khách, khu vực đưa cho AI để **đề xuất chiến dịch và thời gian áp dụng, kèm lý do**. Trang `/advisor` ("Đề xuất của AI").

- **AI chỉ đề xuất.** Không tạo chiến dịch, không duyệt gửi, không gửi gì. Bạn đọc lý do rồi bấm **"Duyệt và tạo chiến dịch"** hoặc **"Bỏ qua"** (ghi lý do bỏ qua).
- **Chỉ gửi AI số liệu tổng hợp ẩn danh:** đếm khách theo vùng, độ mới của lần mua, phễu lời mời, thời tiết 48 giờ, mùa, các dịp sắp tới (Black Friday, 12.12, Giáng sinh, Tết dương, Tết âm), giới hạn hiện hành. **Không bao giờ** có tên, số điện thoại, địa chỉ, mã Zalo, nội dung tin, tên hàng. Vùng hoặc tỉnh dưới **10 khách** bị ẩn để không suy ngược ra từng người. Bạn xem được **đúng JSON đã gửi** ở "Xem số liệu đã gửi cho AI".
- **AI trả tối đa 3 đề xuất** (khu vực, số khách, ngày bắt đầu và kết thúc, khung giờ, hạn mức ngày, hướng nhấn, lý do, rủi ro, độ tin cậy). **Mã kiểm cứng và bỏ** đề xuất sai: khu vực lạ, vượt trần, ngày đã qua, kéo dài quá 14 ngày, khung giờ ngoài 08:00 đến 20:00. **Độ tin cậy bị mã chặn trần:** chưa đủ 30 tin đã nhắn thì không quá 0,6; không có dự báo thời tiết thì không quá 0,7.
- **Duyệt chỉ tạo chiến dịch chưa duyệt tự gửi**, dùng mẫu tin mặc định của cửa hàng (**không dùng chữ AI soạn**). Việc bấm "Duyệt cho hệ thống tự gửi" vẫn là một cú bấm riêng. Lịch, khung giờ và hạn mức AI gợi ý được đặt vào chiến dịch nhưng **kẹp trong trần hệ thống**.
- Giới hạn: bấm "Nhờ AI đề xuất" tối đa **5 lần mỗi ngày**; đề xuất quá ngày bắt đầu tự hết hạn.
- Cần `APP_AI_ADVISOR_ENABLED` (và cả `APP_AI_ENABLED`); nên bật thêm thời tiết để AI có dữ liệu. Chưa có lịch sử phản hồi thật nên đề xuất đầu tiên sẽ có **độ tin cậy thấp** (đúng thiết kế).
- **Mặc định AI đề xuất 1 lần gửi** khi chưa có lịch sử phản hồi (tin nhắc làm tăng khả năng bị báo spam, ảnh hưởng tài khoản phụ duy nhất).

**[CHƯA LÀM]:** đưa số liệu hiệu quả từng chiến dịch (`/performance`, dạng ẩn danh) vào dữ liệu cho AI học; AI đề xuất số lần gửi; AI gợi ý nội dung từng lần; thử A/B; việc dọn bảng nhật ký của AI (sẽ lớn dần).

### 4.12 Bảng thông tin cửa hàng **[CÓ MÃ, TẮT, CHƯA THỬ THẬT]**

Nguồn **duy nhất** để AI trả lời khách, sửa ở `/zalo/reply`. Mặc định (bạn chốt 23/11): cửa hàng bán chủ yếu **áo điều hòa và phụ kiện áo điều hòa (trong đó có pin)**; ưu đãi vào nhóm là **giảm 10% so với giá TikTok và miễn ship**; lý do: TikTok thu phí sàn nên mua qua Zalo shop nhường lại 10%; liên hệ bảo hành qua Zalo thuận tiện hơn; cách vào nhóm. **Ô bảo hành để trống** nên AI không nói gì cụ thể về bảo hành. **Không ghi chính sách đổi trả của TikTok**; AI được chỉ dẫn không nhắc, không chê TikTok. Ô nào để trống thì AI không nói về việc đó.

### 4.13 Chế độ thử và chạy thật **[CÓ MÃ, MẶC ĐỊNH BẬT]**

Dữ liệu trên máy chủ là **khách thật** (khoảng 636 khách, trong đó khoảng 335 đã duyệt). Bạn dặn: **tuyệt đối không nhắn khách thật khi chạy thử**. Vì vậy hệ thống **luôn ở chế độ thử** trừ khi trên máy chủ đặt `APP_ZALO_LIVE=true`.

- Ở chế độ thử, **chỉ các số khai báo ở `/zalo` mục "Chế độ thử"** mới được: nhận Tin 1 và Tin 2, được thêm vào nhóm, được đưa vào nhóm tự tạo, được chiến dịch tự nhận, được AI đọc hoặc trả lời, **kể cả khi công tắc tự gửi đã bật và 335 khách đã duyệt sẵn**.
- **Ba lớp chặn:** (1) danh sách mời, chiến dịch và mọi hàng chờ chỉ có khách thử; (2) chỗ sắp gọi Zalo cho một khách kiểm lại; (3) đường nối duy nhất tới Zalo từ chối mọi số hay mã Zalo không thuộc số thử, và **tin riêng của người không phải số thử bị bỏ ngay, nội dung không bao giờ tới AI**.
- **Khai báo số thử TRƯỚC, nhập đơn giả SAU.** Số đã là khách hoặc nằm trong sổ từ chối bị từ chối khi khai báo.
- Mọi trang quản trị có dải "Đang ở chế độ thử" ở đầu trang. Trang `/zalo` cho biết bao nhiêu khách thật đang được bảo vệ.
- **Cố ý không có nút "chạy thật" trên web**, để một cú bấm nhầm không đưa tin tới khách thật. Chỉ bạn quyết bằng lời, rồi người quản máy chủ đặt biến và tạo lại container; trước đó nên **tạm dừng tất cả chiến dịch**, sau đó duyệt lại từng chiến dịch.
- **Không còn "Chiến dịch thử" riêng (đã xóa 24/11/2026, V45).** Muốn thử thì khai báo số thử, bấm "Dựng khách thử" (tạo khách giả từ chính các số đó), rồi chạy **chiến dịch thật** chỉ gồm các số thử: đi đúng luồng thật (Tin 1, tự gửi lời mời kết bạn, khách đồng ý thì tự thêm vào nhóm, tự tạo nhóm), nên thử được cả phần AI. Chiến dịch thật chỉ chạm số thử vì hệ thống đang ở chế độ thử.
- **Mỗi số điện thoại chỉ nhận Tin 1 MỘT lần trong đời** (bộ nối nhớ). Nên chia số cho các vai khi thử cả chuỗi và AI (ít nhất 5 số, mỗi số một vai: từ chối rồi quay lại; vào nhóm rồi hỏi ưu đãi; hỏi ngoài phạm vi; xin gặp người thật; im lặng), không dùng lẫn. Thứ tự và từng bước nằm ở `docs/HUONG-DAN-CHAY-THU-TOAN-DIEN.md`.
- Khi thử, đặt hạn mức **5 khách/ngày**, nghỉ 120 giây. Mọi nấc AI để "Chỉ soạn nháp" / "Chỉ tạo việc" vài ngày trước khi sang Tự động.

### 4.14 Hiệu quả chiến dịch **[CHẠY ĐƯỢC phần đếm; tiền cần file đối soát]**

Trang `/performance`. Mỗi chiến dịch một dòng phễu: **đã mời → đã kết bạn → đã vào nhóm → người mua và lần mua → doanh thu**. "Lần mua qua nhóm" là lần giao thành công **sau khi vào nhóm** (và trước khi rời nhóm). Doanh thu là tổng tiền thu hộ trong file "Lịch sử đối soát" của J&T; lần mua chưa đối soát vẫn đếm nhưng chưa có tiền.

- **Hệ thống không có đơn TikTok**, nên không đo được doanh thu TikTok thật. So với TikTok bằng: **mức phí TikTok hòa vốn** = (ưu đãi + ship miễn) / giá TikTok ước tính (nếu TikTok thu nhiều hơn mức này thì bán qua nhóm có lời hơn); **lời lỗ cụ thể** khi bạn nhập "TikTok thu bao nhiêu %"; và **so sánh mua lại trong 30 ngày** giữa khách vào nhóm và khách được mời nhưng chưa vào nhóm. Bốn giá trị bạn nhập tay: % ưu đãi (mặc định 10), miễn ship, % TikTok thu, tiền tố mã đơn của đơn qua nhóm.
- Số liệu trang này **chỉ là số tổng hợp**, không tên, không số điện thoại; bản xuất CSV và bản cho AI ẩn ô dưới 5 người vào nhóm.
- Việc chờ bạn quyết: xem mục 9.

### 4.15 Đối soát J&T **[CHẠY ĐƯỢC phần nhập; phần đổi phân loại CHƯA LÀM]**

- File **"Lịch sử đối soát"** của J&T (mỗi file một kỳ). Bạn nhập tay ở `/imports/settlement`, hoặc chạy tool Edge chế độ `--doi-soat`.
- Hệ thống đọc theo tên cột, lưu mã vận đơn, mã đơn khách hàng, ngày ký nhận và tiền. **Không lưu tên, số điện thoại, địa chỉ.** Hiện chỉ **bổ sung** mã đơn và ngày ký nhận, nâng đơn J&T đã thu COD lên "giao thành công", và báo những đơn J&T ghi đã ký nhận mà Adh chưa tính thành công. Nhập lại cùng kỳ thì thay kỳ cũ.
- Đã chạy thử trên 13 file kỳ đối soát thật (142 dòng, không lỗi). **Chưa chạy `--doi-soat` trọn vòng với J&T thật** (cần bạn đăng nhập J&T).

### 4.16 Giấy A5 và mã QR (lối vào thứ hai) **[CHẠY ĐƯỢC, CHƯA IN]**

- Tờ giấy **A5 bỏ vào đơn hàng mới** (in sau; khách cũ đã mua nhận Tin 1 theo chiến dịch). **Một mã QR tĩnh chung** (đường `/n`, bí danh `/moi`).
- Khách quét mã: hệ thống chọn một nhóm còn chỗ (cùng cách chọn như trên) và hỏi "Bạn ở tỉnh/thành nào?" (bỏ qua được). **Không hỏi số điện thoại, không OTP.** Chỉ lưu mã tỉnh và một mã ngẫu nhiên của trình duyệt (90 ngày) để quét lại không bị đếm thêm; giới hạn 20 lượt mới mỗi địa chỉ mạng mỗi giờ. Lượt bấm chỉ là ước lượng "đang chờ", không phải số người thật đã vào nhóm.
- Có dòng ngừng nhận tin (gọi hoặc nhắn Zalo vào số cửa hàng) và nút "Tôi không muốn nhận tin từ cửa hàng nữa" trên trang quét.
- **Chưa in tờ nào:** tên miền hiện chỉ là tên miền tạm; in rồi đổi tên miền thì phải in lại. Chữ trên giấy sửa được ở trang *In giấy giới thiệu*; mặc định giấy nói trong nhóm có "trợ lý AI của cửa hàng".

### 4.17 Quyền dữ liệu của khách **[CHẠY ĐƯỢC]**

- Khách có quyền xem, xuất, xóa dữ liệu: màn *Yêu cầu của khách* ghi nhận yêu cầu, hạn theo loại (hiện 15 ngày cho mọi loại, **chưa có xác nhận pháp lý**), nhãn "quá hạn".
- Trên trang khách có "Xuất dữ liệu", "Ngừng liên hệ", và **"Xóa dữ liệu khách"** (gõ chữ XOA). Đây là **cách duy nhất** để xóa số điện thoại (xem mục 5).

---

## 5. Quy tắc an toàn (không đổi nếu bạn không nói)

1. **KHÔNG BAO GIỜ tự xóa số điện thoại của khách** (bạn chốt 23/11/2026), ở bất kỳ luồng nào: khách từ chối, dọn dữ liệu, hết hạn lưu trữ, tính lại khách. Khách từ chối chỉ bị đánh dấu **"không nhắn nữa"** (lời mời hủy, cơ sở liên hệ "từ chối") và **vẫn giữ bản ghi kèm số**. Sổ từ chối lưu **mã băm** chạy song song để khi chính chủ shop xóa khách thì số vẫn không bị nhắn lại. Chỉ chủ shop xóa bằng nút "Xóa dữ liệu khách". Việc tự xóa chỉ áp dụng cho **nội dung tin nhắn và trích đoạn** (30 ngày). Có test khóa.
2. **Mỗi khách tối đa một Tin 1 trong đời.** Từ chối là vĩnh viễn (trừ khi chính khách nhắn lại muốn mua, mục 4.9c). Chiến dịch nhiều lần thì tổng không quá 3 tin chủ động tới một khách.
3. **Chỉ khách ONE_TIME đã duyệt** nhận Tin 1. REPEAT không bao giờ.
4. **Chỉ tài khoản Zalo phụ**, không bao giờ tài khoản của bố mẹ. Mẹ vẫn dùng Zalo thật; Adh hỏng hay bộ nối tắt không được làm mẹ không làm việc được.
5. **AI không chốt đơn**, không bịa giá, tồn kho, bảo hành, và không nhận số điện thoại, địa chỉ, mã Zalo, tên khách.
6. **Mặc định tắt mọi thứ gửi ra ngoài** (tự gửi, bộ nối, thời tiết, AI, từng nấc AI).
7. **Cầu dao** tự dừng gửi khi có dấu hiệu bất thường; ngắt rồi chỉ người bấm "Mở lại". Lớp **cảnh báo sức khỏe tài khoản** (V34) chỉ ĐỌC và báo: khẩn khi mất phiên không do người bấm, Zalo hạn chế hoặc từ chối hàng loạt, lỗi lạ, đăng nhập nhầm; lưu ý khi tỷ lệ tin lỗi 24 giờ từ 30% (cần tối thiểu 5 tin) hoặc bộ nối mất quá 15 phút. Người tự bấm Dừng thì không phải cảnh báo. Hiện trên Tổng quan, trang `/zalo`, nhãn menu; có thể báo ra ngoài bằng một địa chỉ webhook tùy chọn (văn bản thuần, không có dữ liệu khách). Lớp này **không dừng gì**; dừng là việc của cầu dao.
8. **Không né phát hiện của Zalo**, kể cả khi được yêu cầu (mục 4.6). Có test khóa cứng.
9. **Bộ nối chỉ được thêm việc khi bạn duyệt và test khóa được sửa theo.** Hiện bộ nối có: trạng thái, nghe sự kiện kết bạn, thêm một người vào một nhóm, liệt kê nhóm, tìm người theo số, gửi tin (Tin 1, Tin 2, tin nhắc, tin mẫu cho khách quay lại, câu AI trả lời), tạo nhóm, tra tên và ảnh để chọn phó nhóm, đọc tin riêng đến. gửi lời mời kết bạn (một người đã nhận Tin 1, một lời mời mỗi người). **Không có**: nhận lời mời, đọc danh sách bạn, đăng tin vào nhóm.
10. **Không thêm đường gọi Zalo nào mà không đi qua cổng chung và các bộ lọc chế độ thử**; không thêm nút "bật chạy thật" lên web.
11. **Không dữ liệu thật trong repo, log hay PR** (số điện thoại, mã tài khoản Zalo, mã nhóm thật, mật khẩu, file Excel). Dữ liệu thử chỉ dùng dữ liệu giả.
12. **Khách nhắn từ chối thì không bao giờ bị nhắn tiếp**, kể cả tin nhắc.

---

## 6. Phạm vi giai đoạn 1 và giai đoạn 2

**Giai đoạn 1 (chốt 23/11/2026): đã code xong, đến hết "AI mời vào nhóm".** Gồm: nhập đơn, khách, khu vực, sổ cơ sở liên hệ và từ chối, chiến dịch (lịch, nhịp, nhiều lần gửi), tự gửi Tin 1 (D18), tự nhận khách mới, tự thêm vào nhóm, Tin 2, tự tạo nhóm, mẹ phó nhóm, AI nhận diện từ chối, khách quay lại, AI trả lời tin riêng (D20), bảo hành và khiếu nại, AI đề xuất chiến dịch (D21), hàng đợi người thật, hiệu quả chiến dịch, bản đồ và thời tiết, giấy in A5. Chạy trên **máy chủ 2 GB RAM, phải miễn phí**: **không thêm** Redis, Chatwoot, LangChain4j hay dịch vụ nặng nào.

**Giai đoạn 2 (máy lớn, [CHƯA LÀM], chưa có lời duyệt):** AI trả lời **trong nhóm** Zalo (có lõi `BotEngine` nhưng chưa có kênh thật), **đội bán hàng nhiều người** xử lý ngoại lệ (Chatwoot; cột `assigned_to`), tra cứu nhiều sản phẩm, tóm tắt việc. **Đừng code phần này khi chưa có lời duyệt mới.**

**Sẽ xóa sau khi bạn báo "thử xong"** (một PR, kèm migration `V46`; Chiến dịch thử `/test-runs` đã xóa ở V45): màn nhắn tay (Lô nhắn hôm nay `/today`, Chờ vào nhóm `/friends`, các nút Đã nhắn / Đã kết bạn / Đã vào nhóm / Đã gửi link nhóm / Khách đã rời nhóm / Khách từ chối bấm tay, "Xuất danh sách để mẹ gửi tay"). **Đừng xóa sớm.** Trang `/groups` và `/zalo/groups` cố ý giữ hai trang liên kết qua lại.

---

## 7. Các con số cần nhớ

| Việc | Giá trị |
|---|---|
| Khung giờ gửi tin | 08:00 đến 20:00 |
| Khách nhắn chủ động mỗi ngày | mặc định 10, trần cứng **50** |
| Nghỉ giữa hai tin | cố định 120 giây (tối thiểu 100) |
| Phanh khẩn cấp ở bộ nối | 150 tin/ngày (mọi loại tin) |
| Lỗi liên tiếp làm cầu dao ngắt | 3 lỗi riêng từng người, 5 lần "không tìm thấy", hoặc Zalo cảnh báo |
| Số lần mời một khách | mặc định 1, tối đa 3 |
| Khoảng cách giữa các lần mời | tối thiểu 3 ngày (3 đến 30) |
| Số ngày link mời còn hạn | 1 đến 90 |
| Khách mỗi chiến dịch | tối đa 300 |
| Thêm vào nhóm | mặc định 50 người/ngày, 5/giờ, cách 2 phút; trần 100/ngày; giờ mặc định 07:00 đến 22:00 |
| Mục tiêu một nhóm | khoảng 5 khách (không tính mẹ và tài khoản cửa hàng) |
| Giữ chỗ nhóm cho khách vừa kết bạn | 24 giờ |
| Tự tạo nhóm | không trần nhóm/ngày, nghỉ 60 phút, từ 3 khách chờ (chỉnh từ 2), tối đa 5 khách lúc tạo |
| "Không phản hồi" | 14 ngày sau Tin 1 |
| AI ghi sổ từ chối | chắc từ 0,8 |
| AI tự gỡ cờ khách quay lại | chắc từ 0,85, tối đa 1 lần/khách/30 ngày |
| AI coi là "trong phạm vi" | chắc từ 0,8 |
| AI đề xuất chiến dịch | tối đa 5 lần nhờ/ngày, tối đa 3 đề xuất mỗi lần; độ tin cậy ≤ 0,6 khi chưa đủ 30 tin đã nhắn |
| Lưu chữ khách và nháp AI | 30 ngày rồi tự xóa (chỉ chữ, không bao giờ số điện thoại) |
| Nhắc nhập đơn | quá 7 ngày chưa nhập |
| Tin riêng bộ nối giữ | tối đa 6 giờ, trong bộ nhớ |

---

## 8. Nơi hệ thống chạy

- Máy chủ Tencent (2 GB RAM, **rất chật**), HTTPS qua Caddy ở **tên miền tạm** (chưa có tên miền thật nên **chưa in giấy QR**). Ảnh Docker build sẵn bằng GitHub Actions, không build trên máy.
- **Cron tự triển khai mỗi 10 phút:** mọi thay đổi gộp vào `main` với CI xanh **lên máy chủ trong khoảng 10 phút, cả migration**. Sao lưu cơ sở dữ liệu trước mỗi lần cập nhật và hằng ngày lúc 03:17. Lùi ảnh **không lùi migration**. Cron chỉ kéo ảnh, **không cập nhật thư mục cấu hình** nên thay đổi trong file compose không tự lên máy chủ.
- Bộ nối Zalo chạy từ 22/11; ngày 23/11 bạn đã **quét QR đăng nhập tài khoản phụ thành công**. Chưa có tin nào gửi cho khách thật.
- Theo báo cáo ngày 23/11 (**cần kiểm lại**): đã bật cờ bộ nối, cờ đọc tin riêng và cờ AI với mô hình flash. **Tên shop (`APP_SHOP_NAME`) chưa đặt**, nên Tin 1 và chữ ký trợ lý sẽ ghi "Cửa hàng": phải đặt tên thật trước khi cho ai nhận tin.

---

## 9. Chỗ mã và tài liệu mâu thuẫn, và cách đọc

Quy ước: khi hai nơi mâu thuẫn, **`CLAUDE.md` thắng**, rồi tới `docs/BAN-GIAO.md` mục 0; mã đang chạy (`main`) là bằng chứng cuối cùng. Các chỗ lệch mình đã gặp:

1. **"Mỗi khách đúng một tin"** (CLAUDE.md mục 2, 3) và **mời 1 đến 3 lần** (D21). Hiểu đúng: Tin 1 chỉ **một lần cho một khách**; chiến dịch bật nhiều lần thì thêm tối đa 2 tin nhắc, **mặc định vẫn 1 lần**. Các chỗ ghi "một tin" nên đọc là "mặc định một tin".
2. **Hạn mức ngày:** tài liệu cũ ghi "mặc định 10, trần 25" hoặc "đếm theo tin". Mã hiện tại: mặc định **10 khách**, trần **50 khách**, chỉ Tin 1 và tin nhắc tính. Trần 25 từng bị cài nhầm trong file compose (và không có tác dụng vì tên biến không khớp); đã sửa ở nhánh chính nhưng **thư mục cấu hình trên máy chủ có thể còn bản cũ** (cron chỉ kéo ảnh). Phải kiểm trước khi thử.
3. **Tin nhắc có tính vào hạn mức ngày, còn Tin 2 và tin trả lời thì không.** Dễ nhầm vì cùng gọi là "tin". Đúng là: chỉ Tin 1 và tin nhắc.
4. **Mẹ là "phó nhóm"** (quyết định 23/11) khác **"mẹ là trưởng nhóm"** của quyết định D2 cũ. Hiện: tài khoản cửa hàng là chủ nhóm (vì nó tạo nhóm), mẹ là phó nhóm. Đề xuất cũ "mã Zalo của mẹ qua biến môi trường" vẫn dùng được làm dự phòng, nhưng cách chính là chọn mẹ bằng số điện thoại trên `/zalo`.
5. **Khách mới đồng bộ hằng ngày có được nhắn không?** Quy tắc bạn nói là "có". Mã hiện làm qua **"tự nhận khách mới"** (bật riêng, một chiến dịch mỗi lúc, chỉ khách mới từ lúc bật, khách có sẵn không bao giờ tự duyệt). Bản nghiệp vụ cũ (`NGHIEP-VU-GIAI-DOAN-1.md`) ghi việc này là "chưa có mã" và nêu cách khác ("chiến dịch đang áp dụng" duyệt trước theo quy tắc): đã được thay bằng cách tự nhận này.
6. **AI "ba việc còn dừng":** bản cũ ghi AI chỉ mới nhận diện từ chối, còn nhận ra khách muốn mua/khiếu nại, soạn nháp, tóm tắt "chờ duyệt". Thực tế mã đã có: khách quay lại muốn mua, AI trả lời tin riêng, bảo hành và khiếu nại, AI đề xuất chiến dịch (đều tắt, chưa thử thật). **Vẫn chưa làm:** tóm tắt việc hằng ngày cho mẹ và trợ lý trả lời trong nhóm.
7. **Tên nhóm:** bản cũ đề xuất `Pin ADH - Khu vực - 01`. **Đã chốt** `Áo điều hòa ADH - Khu vực - 01` (cửa hàng bán chủ yếu áo điều hòa).
8. **Chữ "pin" và "mẹ gửi tay":** Tin 1 mặc định trong mã nay nói "sản phẩm" (không còn "pin"), nhưng nếu mẹ từng lưu bản Tin 1 cũ có chữ "pin" ở trang *Chiến dịch* thì bản lưu đó vẫn dùng; cần sửa tay. Một số chữ trong giao diện và chú thích mã còn ghi "mẹ gửi tay, hệ thống không tự gửi" (ví dụ chú thích Tin 2 trong mã mời); dọn cùng lúc xóa màn nhắn tay.
9. **Khung giờ:** gửi tin 08:00 đến 20:00, nhưng **thêm vào nhóm** có khung giờ mặc định riêng 07:00 đến 22:00 trong cấu hình. Hai thứ tách nhau; nếu muốn thống nhất cần bạn quyết.
10. **Chiến dịch thử đã xóa (24/11/2026):** migration `V45` bỏ hai bảng `test_run` và `test_target`; `TestMode` không còn truy vấn `test_target`.
11. **Tài liệu thiết kế D21 ghi "PR 2 chưa có giao diện nên chưa dùng được từ web"**: đã cũ. Trang `/advisor` có từ PR 3 (#85).
12. **Tài liệu `BAN-GIAO.md` mục 1 đến 10** đã cũ (ghi "chưa deploy", "không tự gửi tin"...). Đừng làm theo; đã thay bởi mục 0 của nó và `CLAUDE.md`.
13. **Số liệu máy chủ** (khoảng 636 khách, 335 đã duyệt, công tắc nào đang bật) là theo báo cáo ngày 23/11, không phải kiểm trực tiếp; cần kiểm lại trên máy chủ trước khi tin.

---

## 10. Việc còn mở và chỗ chưa chốt

### 10.1 Việc bạn tự làm (theo thứ tự nên làm)

1. **Chuẩn bị máy chủ:** đặt tên shop thật (`APP_SHOP_NAME`); kiểm file cấu hình trên máy chủ có dòng chế độ thử và trần 50 khách; kiểm các cờ AI và đọc tin riêng; **không tạo lại container bộ nối trước khi đọc log**.
2. **Thử AI không cần Zalo:** điền Bảng thông tin cửa hàng, thử khoảng 30 câu ở "Thử một câu", đọc nháp.
3. **Chạy thử** một chiến dịch thật chỉ gồm các số thử (kết bạn tự động), đọc nhật ký bộ nối, điền `ZALO_PERSON_ERROR_CODES` bằng mã lỗi thật, cập nhật các mục "chưa kiểm chứng". Dừng ngay nếu cầu dao nhảy hay Zalo cảnh báo; đừng bấm "Mở lại" liên tục.
4. **Khách giả** (Nhóm B, từ 5 số): từ chối rồi quay lại; vào nhóm rồi hỏi ưu đãi; ngoài phạm vi; xin gặp người thật; im lặng; thử **tự tạo nhóm với ít nhất 2 khách cùng tỉnh** (đây là lần **thử tay 1 nhóm thật** còn nợ); thử **tự nhận khách mới**; rồi mới chuyển nấc AI sang Tự động.
5. **Thử tin nhắc lần 2 và 3** trên số thử (bốn tình huống: để yên thì nhận; kết bạn trước hạn thì không; nhắn riêng bất kỳ câu nào thì không; nhắn "dừng" thì AI ghi từ chối). Chỉ khi đúng cả bốn mới bật cho khách thật.
6. **Thử AI đề xuất chiến dịch** (`/advisor`): đọc JSON đã gửi trước, đọc lý do, duyệt thử một đề xuất.
7. **Chạy `--doi-soat` với J&T thật** (bạn đăng nhập J&T trong hồ sơ Edge riêng), báo chỗ lệch.
8. Khi quyết chạy thật: tạm dừng tất cả chiến dịch → đặt `APP_ZALO_LIVE=true` → tạo lại container app → duyệt lại từng chiến dịch.
9. **Báo "thử xong"** để dọn: xóa màn nhắn tay (kèm migration `V46`); xóa khách giả bằng nút "Xóa dữ liệu khách".
10. **Tên miền thật**, rồi mới in giấy A5; thử in một tờ và quét QR bằng điện thoại.

### 10.2 Việc chưa làm (cần lời duyệt hoặc đang xếp hàng)

- D21 còn dở: nhiều khu vực và lọc theo độ mới của lần mua, ngày trong tuần, hoãn khi mưa lớn, tăng dần theo ngày, tự dừng khi từ chối nhiều, thử A/B, nhóm đích theo chiến dịch, gom giao diện chiến dịch thành nhiều tab, đưa số liệu hiệu quả vào dữ liệu cho AI, dọn bảng nhật ký AI.
- Tự gán nghĩa trạng thái J&T và dùng file đối soát đổi phân loại một lần / từ hai lần (mục 4.2).
- AI đọc ảnh bảo hành; tóm tắt việc hằng ngày; kênh báo "cần người thật" cho mẹ ngoài trang Adh.
- Cron cập nhật cả thư mục cấu hình trên máy chủ (`git merge --ff-only` trước khi kéo ảnh): đề xuất, **chưa làm, hỏi bạn trước**.
- Giai đoạn 2 (mục 6).

### 10.3 Chỗ chưa chốt, đang chờ bạn quyết

1. **Cách phân biệt đơn qua nhóm** cho `/performance`: đề xuất ghi mã đơn bán trong nhóm bắt đầu bằng **`NHOM-`** rồi đặt tiền tố `NHOM` ở trang. Bạn chưa trả lời, nên hiện mặc định coi **mọi đơn sau khi vào nhóm là đơn qua nhóm** (số "ước tính"). Doanh thu chỉ có khi đã nhập file đối soát J&T thật.
2. **Nhóm hàng** (áo điều hòa / pin / phụ kiện) gán thế nào để AI đề xuất theo loại hàng: AI phân loại tên hàng một lần rồi lưu, hay bạn khai báo bảng từ khóa.
3. **Thử A/B** từ đợt đầu hay chờ có lịch sử.
4. **Ngưỡng tự dừng khi khách từ chối nhiều** (đề xuất 5% sau 20 tin đầu).
5. **Có cho AI biết tên gọi của khách** (ví dụ "Lan") để xưng hô thân hơn không? Mặc định: **không**.
6. **Ai đánh dấu "đã vào nhóm" sau Tin 2?** Khách bấm link tự vào nhóm thì hệ thống không biết. Mẹ đếm lại thành viên mỗi tuần rồi sửa tay, hay chấp nhận chỉ ước lượng?
7. **Mẹ trả lời khách từ đâu?** Zalo của mẹ khác với cuộc trò chuyện khách đang có với tài khoản phụ; lúc thử, người thật trả lời bằng điện thoại giữ tài khoản phụ. Cùng với đó: **kênh báo việc cho mẹ ngoài web**, và ai thay mẹ khi mẹ nghỉ.
8. **Sao lưu ra ngoài máy chủ** đang hoãn: file sao lưu nằm cùng máy (chứa dữ liệu khách thật); mất máy là mất hết. Chưa có bằng chứng đã thử khôi phục trên máy chủ thật.
9. **Mật khẩu quản trị** đã đổi 22/11 và **không ghi lại ở đâu**; hỏi bạn khi cần.
10. **Pháp lý:** chưa đăng ký hộ kinh doanh (D1); chưa có xác nhận về việc nhắn khách chưa đồng ý (D3), về chuyển dữ liệu (nội dung tin khách đi sang DeepSeek), và về hạn trả lời yêu cầu dữ liệu. SMS/ZBS đang hoãn.
11. **Rủi ro với TikTok Shop** (đánh giá của người viết, **chưa kiểm chứng** điều khoản hiện hành): Tin 1 nói thẳng "mua trực tiếp rẻ hơn TikTok 10%". Các sàn thương mại điện tử thường cấm kéo khách ra ngoài nền tảng và có thể hạn chế gian hàng. TikTok Shop là kênh bán chính nên đây có thể là rủi ro lớn hơn cả việc Zalo khóa tài khoản. Nên đọc điều khoản người bán trước khi chạy thật; nếu có rủi ro thì bỏ câu so sánh giá khỏi Tin 1 và Bảng thông tin.
12. **Một tài khoản cho tất cả:** tài khoản phụ bị khóa là mất cùng lúc gửi tin, thêm nhóm, tạo nhóm và trợ lý. Nên viết sẵn "kế hoạch khi bị khóa" (ai báo, dừng bộ nối, khách vẫn vào nhóm bằng link và QR, mẹ vẫn là phó nhóm của mọi nhóm nên nhóm không mất người quản lý).

### 10.4 Điều chưa ai kiểm với Zalo và DeepSeek thật

Gửi Tin 1 và Tin 2; hàm tìm người theo số và gửi tin của bộ nối (chưa từng chạy thật, lỗi lạ rất có thể nằm ở đây); mã lỗi Zalo trả cho từng loại lỗi riêng một người; tự thêm vào nhóm bằng bộ nối; tạo nhóm; chọn phó nhóm bằng số; đọc tin riêng; tin nhắc lần 2 và 3; ngưỡng thao tác an toàn của Zalo (kể cả mức 50 khách mỗi ngày); thời gian sống của phiên đăng nhập; lỗi khi nhóm đầy hay nhóm bật "chỉ quản trị viên được thêm"; tin kèm link nhóm có bị Zalo coi là spam không; và với DeepSeek: khuôn JSON, độ dài đầu ra, nhận diện từ chối với vài chục câu tiếng Việt thật. Cũng chưa: in A5 và quét QR bằng điện thoại, xem giao diện trên điện thoại thật cho mọi trang, `--doi-soat` trọn vòng, khôi phục sao lưu trên máy chủ thật.

---

## 11. Từ điển ngắn

- **Tin 1:** tin mời chủ động, một lần cho một khách. **Tin 2:** tin gửi link nhóm khi việc tự thêm lỗi. **Tin nhắc:** lần 2 và 3 của chiến dịch nhiều lần.
- **ONE_TIME / REPEAT:** khách mua một lần / từ hai lần.
- **Cầu dao:** cơ chế tự ngắt việc gửi khi có dấu hiệu bất thường.
- **Chế độ thử:** chỉ nhắn các số thử khai báo; khách thật không bị chạm.
- **Sổ từ chối:** danh sách người không được nhắn nữa, lưu mã băm của số điện thoại.
- **Cờ "không nhắn nữa":** đánh dấu trên khách; không xóa số điện thoại.
- **Bộ nối (cầu nối) Zalo:** dịch vụ nhỏ chạy cạnh Adh, nói chuyện với Zalo qua thư viện không chính thức.
- **Trợ lý AI:** tên gọi trên giao diện cho phần AI; luôn tự nhận là trợ lý, không giả làm người.
