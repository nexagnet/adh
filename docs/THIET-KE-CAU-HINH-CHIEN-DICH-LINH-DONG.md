# Thiết kế: cấu hình chiến dịch linh động (bộ lọc khách dùng chung cho người và AI)

Ngày 25/11/2026 (cập nhật theo PR 1: ô khu vực, bộ lọc không sửa sau khi tạo). Người yêu cầu: Phùng Việt. Trạng thái: **thiết kế đã được chủ dự án duyệt hướng** (cho chọn số lần mua riêng từng chiến dịch, mặc định vẫn đúng 1 lần; AI được đề xuất cả chiến dịch cho khách mua nhiều lần; bộ lọc phải theo dữ liệu thật, không theo "đơn hoàn"). Chưa có dòng mã nào; mỗi PR dưới đây là một bước riêng.

Viết cho người không rành kỹ thuật: đọc mục 0 là đủ hiểu; các mục sau là chi tiết để duyệt và để người làm tiếp.

## 0. Tóm tắt một trang

- **Vấn đề:** tạo chiến dịch bằng tay chỉ chọn được tên, số khách, khu vực. AI đề xuất thì lại có thêm vài núm (độ mới lần mua, nhóm hàng, thứ tự lấy), nên AI đang có nhiều công cụ hơn người. Các núm đó chỉ dùng lúc tạo, không lưu vào chiến dịch, và chế độ "tự nhận khách mới" bỏ qua hết. Hệ thống còn cứng chỉ mời khách mua đúng 1 lần.
- **Cách làm:** một **bộ lọc khách** duy nhất (cùng một khuôn dữ liệu), dùng ở ba nơi: form tạo chiến dịch của người, đề xuất của AI, và tự nhận khách mới. Bộ lọc được **lưu vào chiến dịch** để sau này xem lại "chiến dịch này mời ai".
- **Luật bất biến:** bộ lọc chỉ **làm hẹp** tập khách đang đủ điều kiện, không bao giờ mở rộng (khách từ chối, bị chặn, cần kiểm tra, đang ở chế độ thử, đã từng được mời vẫn bị loại). Ngoại lệ duy nhất, đã được chủ dự án duyệt: **số lần mua** có thể mở lên 2 đến N lần, nhưng chỉ khi người (hoặc AI, rồi người duyệt) chủ động chọn cho **đúng chiến dịch đó**.
- **Xem trước:** trước khi tạo, bấm "Xem trước" để thấy ngay số khách khớp, chia theo khu vực, và từng điều kiện loại bớt bao nhiêu khách.
- **Mẫu dựng sẵn + lưu mẫu:** có sẵn các chiến dịch hay dùng (ví dụ "khách quay lại sau 90 ngày", "mua áo chưa mua phụ kiện"); bạn lưu thêm mẫu của riêng mình.
- **AI:** nhận cùng danh sách bộ lọc, cùng khuôn, qua cùng bộ kiểm cứng; được đưa thêm số khách khớp của từng kiểu bộ lọc (số tổng hợp ẩn danh). Duyệt đề xuất vẫn chỉ tạo chiến dịch **chờ duyệt**; hạn mức gửi không bao giờ nới.

## 1. Dữ liệu thật mà hệ thống có (và không có)

Hệ thống chỉ biết những gì có trong file đơn J&T / Viettel Post và file đối soát J&T. Bộ lọc **chỉ dựa trên những thứ này**, không bịa thêm:

| Có | Từ đâu | Dùng để lọc gì |
|---|---|---|
| Số lần mua thành công (`successful_purchases`) | các đơn "Giao thành công" gộp theo mã đơn | số lần mua |
| Ngày của lần mua gần nhất (`last_order_on`) | file đơn | độ mới |
| Ngày tạo của từng đơn, trạng thái từng đơn (7 trạng thái chuẩn: mới tạo, đang vận chuyển, giao thành công, hủy, đang hoàn, đã hoàn, chưa xác định) | file đơn | có/không đơn hủy, đơn hoàn, đơn đang giao, số đơn |
| Tên hàng (chữ tự do) | file đơn | nhóm hàng (áo điều hòa, pin, phụ kiện khác; phân loại **gần đúng** theo từ khóa) |
| Tỉnh, vùng | địa chỉ nhận | khu vực |
| Hãng vận chuyển | file đơn | nguồn đơn (J&T / Viettel Post) |
| Tiền COD đã thu | **chỉ file đối soát J&T** | giá trị khách (chỉ khi đã nhập đối soát) |
| Việc "cần người thật", khiếu nại chưa xử lý | `/handoffs` | loại trừ khách đang có việc mở |

**Không có:** loại đơn "đổi hàng", lý do đổi, giá bán từng đơn (chỉ có tiền COD sau đối soát), nguồn khách (TikTok hay kênh khác), tuổi, giới tính.

**Về "đơn đổi":** chủ dự án nói trên thực tế không có đơn hoàn mà chỉ có đơn đổi. Trong file hãng, đơn đổi **không có trạng thái riêng**, nên hệ thống không tự nhận ra được. Thiết kế: một cài đặt **"Cách nhận biết đơn đổi"** ở trang cấu hình, chọn một hoặc nhiều dấu hiệu rồi hệ thống dùng chúng để đánh dấu đơn: (a) mã đơn của shop bắt đầu hoặc chứa chữ nào đó (ví dụ `DOI`), (b) tên hàng chứa chữ nào đó (ví dụ "đổi size"), (c) trạng thái gốc của hãng chứa chữ nào đó. **Chưa cài dấu hiệu thì bộ lọc "đơn đổi" hiện mờ, ghi rõ "chưa cài cách nhận biết đơn đổi"**, không đoán. Ở các bộ lọc trạng thái (hủy, hoàn, giao thất bại) hệ thống vẫn dùng trạng thái chuẩn có sẵn; nếu thực tế không có đơn hoàn thì xem trước sẽ hiện 0 và người dùng biết ngay, không hại gì.

> **Câu cần chủ dự án trả lời một lần:** đơn đổi của shop được ghi thế nào trong file J&T (mã đơn có chữ nào, hay tên hàng có chữ nào, hay hãng ghi trạng thái nào)? Khi có câu trả lời, cài ở trang cấu hình là xong, không phải sửa mã.

## 2. Danh sách bộ lọc

Mọi bộ lọc đều **tùy chọn**. Không điền = không lọc. Mặc định của chiến dịch mới giống hệt hôm nay (mua đúng 1 lần).

### 2.1 Nhóm "Khách là ai"

| Tên trên giao diện | Ý nghĩa | Ví dụ giá trị |
|---|---|---|
| Số lần mua từ … đến … | số lần giao thành công (gộp đơn tách kiện theo mã đơn) | mặc định 1 đến 1; "từ 2 đến 3" |
| Khu vực | vùng/tỉnh khách ở: ô "Khu vực khách" có sẵn (chọn một) cộng ô "Loại trừ khu vực" (chọn nhiều) | Đồng bằng sông Hồng; trừ Hà Nội |
| Hãng vận chuyển | khách có đơn qua hãng nào | J&T; Viettel Post |
| Khách ghi nhận từ ngày | khách mới nhập về kể từ ngày (dùng cho tự nhận khách mới) | từ 01/11 |

### 2.2 Nhóm "Lịch sử đơn"

| Tên | Ý nghĩa | Ví dụ |
|---|---|---|
| Đơn đổi | có / không có / không quan tâm (cần cài dấu hiệu đơn đổi, mục 1) | "không có đơn đổi" |
| Đơn hủy | có / không có / không quan tâm | "không có đơn hủy" |
| Đơn hoàn hoặc đang hoàn | có / không có / không quan tâm | "không có đơn hoàn" |
| Đơn đang giao | không có đơn nào chưa kết thúc (tránh nhắn khi khách đang chờ hàng mới) | bật |
| Tổng số đơn (kể cả hủy) | đếm mọi vận đơn của khách | không quá 4 |

### 2.3 Nhóm "Mua gì, mua khi nào"

| Tên | Ý nghĩa | Ví dụ |
|---|---|---|
| Lần mua gần nhất cách đây từ … đến … ngày | độ mới | 90 đến 365 ngày |
| Đã mua nhóm hàng | bất kỳ / tất cả trong danh sách chọn | Áo điều hòa |
| **Chưa** mua nhóm hàng | loại khách đã từng mua nhóm này | chưa mua Pin, chưa mua Phụ kiện khác |
| Giá trị đã thu (COD) từ … đến … đồng | cộng tiền COD của các đơn đã đối soát; **chỉ khách có đối soát**, khách chưa có đối soát không được tính (ghi rõ trên màn hình) | từ 1.500.000 |

Nhóm hàng là phân loại **gần đúng** theo từ khóa trong tên hàng (đã có `ProductGroup`); chủ dự án chưa chốt bộ từ khóa, màn xem trước giúp phát hiện phân loại sai.

### 2.4 Nhóm "Loại trừ để an toàn"

| Tên | Ý nghĩa | Mặc định |
|---|---|---|
| Loại khách đang có việc cần người thật hoặc khiếu nại chưa xử lý | không nhắn mời lúc khách đang có chuyện chưa giải quyết | **bật** (người tắt được; AI luôn bật) |

### 2.5 Cố định, bộ lọc không bao giờ đụng tới

Khách từ chối (sổ từ chối), bị chặn, "cần kiểm tra", ngoài số thử khi đang ở chế độ thử, đã từng được mời (mỗi khách một Tin 1), chưa có cơ sở liên hệ được duyệt. Đây là điều kiện **trong truy vấn**, không phải lựa chọn.

### 2.6 Thứ tự lấy khách (không phải bộ lọc, chọn lấy ai trước)

Giữ ba kiểu hiện có (duyệt sớm nhất; mua gần đây nhất; mua lâu nhất) và thêm: "nhiều lần mua nhất trước", "giá trị COD cao nhất trước" (chỉ khi đã có đối soát).

## 3. Cách kết hợp

- Các bộ lọc **kết hợp bằng "và"**: khách phải thỏa **tất cả** điều kiện đã điền. Ví dụ **"mua từ 1 đến 3 lần **và** không có đơn đổi **và** không có đơn hủy"** = khách mua 1, 2 hoặc 3 lần, trong tất cả đơn của họ không có đơn đổi, không có đơn hủy.
- Trong một ô nhiều lựa chọn (khu vực, nhóm hàng): chọn "**bất kỳ** trong số này" hoặc "**tất cả**" (chỉ nhóm hàng có lựa chọn này).
- **Loại trừ** có ô riêng (không có đơn đổi, chưa mua nhóm hàng, trừ khu vực), không phải gõ biểu thức.
- **"Hoặc"** không có trong một chiến dịch (dễ nhầm, khó đọc lại). Muốn "hoặc" thì tạo **hai chiến dịch**. Vì mỗi khách chỉ được mời một lần, khách khớp cả hai chiến dịch chỉ nhận ở chiến dịch tạo trước; không bao giờ nhắn hai lần.
- Cách đếm "có đơn X" tính **trên tất cả đơn của khách** (không chỉ lần mua gần nhất).

## 4. Màn hình tạo chiến dịch (phác thảo chữ)

Trang `/campaigns`, nút "Tạo chiến dịch mới" mở form. Trên điện thoại các khối xếp dọc, có thể gập; khối đầu luôn mở.

```
┌ Chọn mẫu (không bắt buộc) ─────────────────────────────┐
│ [Không dùng mẫu ▾]   mẫu dựng sẵn + mẫu của bạn          │
└─────────────────────────────────────────────────────────┘
┌ 1. Thông tin chung ─────────────────────────────────────┐
│ Tên chiến dịch  [____________]                          │
│ Số khách tối đa [ 30 ]   Link hiệu lực [14] ngày        │
└─────────────────────────────────────────────────────────┘
┌ 2. Mời những khách nào ─────────────────────────────────┐
│ Số lần mua: từ [1] đến [1]   (mặc định chỉ mua 1 lần;   │
│   chọn lớn hơn 1 là mời cả khách mua nhiều lần)          │
│ Khu vực:   ( ) Mọi nơi  ( ) Chọn: [☑ Miền Bắc ...]      │
│            [ ] Trừ các khu vực đã chọn                  │
│ Hãng:      [☑ J&T] [☑ Viettel Post]                      │
└─────────────────────────────────────────────────────────┘
┌ 3. Lịch sử đơn ─────────────────────────────────────────┐
│ Đơn đổi:   (•) Không quan tâm ( ) Không có ( ) Có       │
│ Đơn hủy:   (•) Không quan tâm ( ) Không có ( ) Có       │
│ Đơn hoàn:  (•) Không quan tâm ( ) Không có ( ) Có       │
│ [ ] Bỏ khách đang có đơn chưa giao xong                  │
│ Tổng số đơn không quá [__]                               │
└─────────────────────────────────────────────────────────┘
┌ 4. Mua gì, mua khi nào ─────────────────────────────────┐
│ Lần mua gần nhất cách đây từ [__] đến [__] ngày          │
│ Đã mua: [☑ Áo điều hòa] [ ] Pin [ ] Phụ kiện khác        │
│          (•) bất kỳ nhóm đã chọn  ( ) tất cả nhóm        │
│ Chưa từng mua: [ ] Áo điều hòa [☑ Pin] [☑ Phụ kiện]      │
│ Giá trị đã thu từ [____] đến [____] đồng                 │
│   (chỉ tính khách có file đối soát)                      │
└─────────────────────────────────────────────────────────┘
┌ 5. An toàn ─────────────────────────────────────────────┐
│ [☑] Bỏ khách đang có việc cần người thật / khiếu nại     │
│ Lấy khách theo thứ tự: [Duyệt sớm nhất ▾]                │
└─────────────────────────────────────────────────────────┘
┌ Xem trước (cập nhật khi bấm "Xem trước") ───────────────┐
│ Khớp: 48 khách  →  sẽ mời tối đa 30                      │
│ Theo khu vực: Miền Bắc 31 · Miền Trung 9 · Miền Nam 8    │
│ Từng điều kiện loại bớt bao nhiêu (tính lần lượt):       │
│   Đủ điều kiện mời, chưa từng được mời ........ 400      │
│   sau "mua 1 đến 3 lần" ....................... 410      │
│   sau "không có đơn đổi" ...................... 380      │
│   sau "chưa mua Pin" .......................... 48       │
│ ⚠ Khách mua nhiều lần: sẽ được duyệt liên hệ riêng cho   │
│   chiến dịch này (xem mục 7).                            │
│ [Xem trước]   [Tạo chiến dịch]   [Lưu thành mẫu]         │
└─────────────────────────────────────────────────────────┘
```

- **Xem trước** là một nút bấm (gửi form, trang hiện kết quả), **không cần JavaScript**, chạy được trên điện thoại. Chỉ đếm, không tạo gì, không chạm Zalo.
- Số khớp dưới 1 thì nút "Tạo chiến dịch" mờ và nói rõ điều kiện nào làm cạn khách (dòng đầu tiên về 0 trong bảng loại bớt).
- Mọi chữ tiếng Việt, gọi "trợ lý AI" không "bot". Điều kiện chưa dùng được (đơn đổi chưa cài, giá trị COD chưa có đối soát) hiện mờ kèm một dòng giải thích.
- Trang chi tiết chiến dịch có thêm khối **"Chiến dịch này mời ai"** hiển thị bộ lọc đã lưu bằng chữ dễ đọc (ví dụ "Mua từ 1 đến 3 lần; không có đơn đổi; không có đơn hủy; chưa mua Pin"). Bộ lọc đặt lúc tạo chiến dịch và **không sửa sau đó** (các lời mời đã chọn theo bộ lọc cũ); muốn đổi thì hủy các lời mời chưa gửi rồi tạo chiến dịch mới.

## 5. Mẫu chiến dịch dựng sẵn

Mẫu = một bộ lọc đặt sẵn; chọn mẫu rồi vẫn sửa được. Dựng sẵn trong mã (không phải dữ liệu khách):

1. **Khách mới mua** (mặc định hôm nay): mua đúng 1 lần, mua trong 60 ngày gần đây.
2. **Khách quay lại sau 90 ngày**: mua 1 lần, lần mua gần nhất cách đây 90 đến 365 ngày (lúc quên mất shop).
3. **Mua áo điều hòa, chưa mua phụ kiện/pin**: đã mua Áo điều hòa, chưa mua Pin và Phụ kiện khác (bán thêm).
4. **Khách ổn định (mua ≤ 3 lần, không đơn đổi, không đơn hủy)**: số lần mua 1 đến 3, không có đơn đổi, không có đơn hủy. Đây là ví dụ của chủ dự án; mở khách mua 2 đến 3 lần.
5. **Khách mua nhiều lần (từ 2 lần)**: số lần mua từ 2, không đơn hủy, không đơn đổi (tri ân, mời vào nhóm).
6. **Khách từng đổi hàng**: có đơn đổi (cần cài dấu hiệu đơn đổi); dùng để chăm sóc, không để quảng cáo.
7. **Khách giá trị cao**: tổng tiền COD đã thu từ mức người dùng nhập, lấy "COD cao nhất trước" (cần đối soát).
8. **Khách trong một vùng mưa/nóng**: chỉ gắn khu vực; để AI hoặc người ghép với lịch và hoãn khi mưa có sẵn của chiến dịch.

**Mẫu của bạn:** nút "Lưu thành mẫu" đặt tên (tối đa 60 ký tự) và lưu bộ lọc hiện tại; xóa/sửa tên ở danh sách mẫu. Mẫu chỉ chứa bộ lọc và thứ tự lấy, **không** chứa khách, số điện thoại, hay nội dung tin.

## 6. AI đề xuất dùng cùng bộ lọc

**Một nguồn sự thật:** khuôn bộ lọc nằm ở một chỗ trong mã (danh sách khóa, kiểu, miền giá trị, chữ tiếng Việt). Form của người, câu hướng dẫn cho AI, và bộ kiểm cứng `ProposalValidator` **đều sinh ra từ đó**, nên không thể lệch nhau.

1. **Đưa gì cho AI:** trong số liệu ẩn danh thêm mục "bộ lọc dùng được" (tên khóa, ý nghĩa, miền giá trị) và **số khách khớp** của các kiểu bộ lọc phổ biến theo từng khu vực (ví dụ số khách mua 2 đến 3 lần, số khách chưa mua pin, phân bố độ mới). Chỉ **số tổng hợp**; ô dưới 10 khách bị ẩn thành "dưới 10"; không số điện thoại, không tên, không địa chỉ, không tên hàng gốc, không tên khách. Đơn đổi chỉ xuất hiện khi đã cài dấu hiệu.
2. **AI trả gì:** mỗi đề xuất có thêm khóa tùy chọn `filter` đúng khuôn trên. Thiếu thì dùng mặc định (mua 1 lần). Các khóa cũ (`min_days_since_purchase`, `product_group`, `pick_order`, `weekdays`) vẫn hiểu được để không vỡ dữ liệu cũ.
3. **Kiểm cứng (không dùng AI để kiểm AI):** khóa lạ → bỏ đề xuất; sai kiểu, ngoài miền (ví dụ số lần mua > 20, khoảng ngược), mâu thuẫn (vừa "đã mua pin" vừa "chưa mua pin") → bỏ đề xuất và ghi lý do; số khớp **thật** tính ở server lúc kiểm: đề xuất khớp ít hơn 1 khách thì bỏ, `batch_size` bị kẹp xuống không vượt số khớp.
4. **Mua nhiều lần với AI:** được phép, nhưng đề xuất hiện nhãn rõ "Mời cả khách mua nhiều lần", độ tin cậy bị chặn trần thấp hơn nếu chưa có dữ liệu phản hồi của nhóm đó, và lý do phải dẫn số liệu có trong bản số liệu.
5. **Người vẫn duyệt:** bấm "Triển khai" chỉ tạo chiến dịch **chưa duyệt tự gửi** (hoãn khi mưa bật sẵn); chưa gửi gì. Bộ lọc của đề xuất được **lưu vào chiến dịch** như chiến dịch người tạo.
6. **Hạn mức không nới:** số khách mỗi ngày, khoảng nghỉ, khung giờ, cầu dao, chế độ thử, một Tin 1 cho một khách, 50 khách/ngày: không có bộ lọc nào chạm tới. Bộ lọc chỉ chọn "ai", không chọn "bao nhiêu và khi nào".
7. **Ghi lại để học:** bảng "Kết quả các đề xuất đã triển khai" thêm cột bộ lọc, để AI và người thấy kiểu bộ lọc nào cho phản hồi tốt.
8. **Bảo mật:** `AdvisorSnapshot` vẫn chỉ có số và mã do mã đặt; khóa bộ lọc là danh sách cố định nên không có chuỗi tự do từ cơ sở dữ liệu lọt vào.

## 7. Quy tắc mua nhiều lần: ảnh hưởng tới tin nhắn, nhóm, an toàn

**Quy tắc cũ** (`CLAUDE.md` mục 2, `NGHIEP-VU-HIEN-HANH.md` mục 93): khách mua từ 2 lần (REPEAT) không bao giờ được mời. **Quy tắc mới (chủ dự án duyệt 25/11/2026):** mỗi chiến dịch có "số lần mua tối đa được mời", **mặc định 1** (giống cũ). Chiến dịch có thể cho tới N lần; khách REPEAT chỉ được liên hệ khi **chính chiến dịch của họ** cho phép số lần mua của họ. Quy tắc cũ vẫn đúng với mọi chiến dịch không chọn khác. Phải cập nhật `CLAUDE.md`, `NGHIEP-VU-HIEN-HANH.md`, `01_DAC_TA_NGHIEP_VU.md`.

Cách thực hiện an toàn (điều kiện vẫn nằm **trong truy vấn**, không dựa vào "đừng duyệt"):
- Cột `max_purchases` ở chiến dịch (1 đến 20, mặc định 1). Hàm `stillEligible` (đang được mọi nơi gửi, kết bạn, thêm nhóm, tin nhắc dùng) cho phép khách REPEAT **chỉ khi** khách có lời mời trong một chiến dịch có `max_purchases` ≥ số lần mua thành công của khách. Khách ONE_TIME giữ nguyên. Nên mọi chốt chặn cũ tiếp tục chặn đúng.
- **Cơ sở liên hệ:** khách mua nhiều lần chưa có dòng "ĐỦ ĐIỀU KIỆN" thì lúc tạo chiến dịch hệ thống ghi dòng cơ sở liên hệ với người duyệt là chính người tạo (hoặc "ai-de-xuat-duoc-duyet" khi đến từ đề xuất AI) và bằng chứng "chiến dịch #… cho khách mua tới N lần", đúng cách tự nhận khách mới đang làm. Khách từ chối/bị chặn/cần kiểm tra vẫn bị loại.
- **Khách mua thêm sau khi được mời:** khách đã nhận Tin 1 trong chiến dịch 1 lần rồi mua thêm: hôm nay khách chuyển sang REPEAT và các việc tiếp theo (kết bạn, thêm nhóm) bị chặn bởi `stillEligible`. Với quy tắc mới, nếu chiến dịch của họ có `max_purchases` đủ lớn thì tiếp tục, nếu không thì giữ hành vi cũ (dừng); **không** tự đổi chiến dịch cho khách.
- **Đã làm ở PR 3 (V60):** cột `max_purchases` ở chiến dịch (mặc định 1); `stillEligible` xét khách REPEAT theo chiến dịch của họ; `approveForCampaign` duyệt riêng khách mua nhiều lần (chỉ khách mua từ 2 lần chưa có dòng cơ sở liên hệ nào; khách mua 1 lần chưa duyệt vẫn phải duyệt trước như mọi khi). Khách mua từ 2 lần mà có đơn không mã đơn (cần kiểm tra) vẫn bị loại. Chế độ tự nhận khách mới chưa nhận khách mua nhiều lần (khách mới xuất hiện hầu như đều mua một lần).
- **Tin nhắn:** Tin 1 mẫu hiện nói "Bác từng mua áo bên em" — đúng cho cả khách mua nhiều lần, không cần đổi. Có thể soạn **Tin 1 riêng** cho chiến dịch nhiều lần (đã có ô sửa Tin 1 theo chiến dịch); không thêm chỗ điền mới trong PR này (mẫu có chỗ điền lạ vẫn bị từ chối).
- **Nhóm Zalo:** khách mua nhiều lần vào nhóm theo khu vực như mọi khách; chọn nhóm, gom khách dư lẻ, phó nhóm không đổi.
- **Từ chối & AI trả lời:** khách từ chối vẫn không bao giờ nhắn lại; AI trả lời tin riêng đã phân loại theo khách, không phụ thuộc số lần mua.
- **Đo hiệu quả (`/performance`):** "mua sau khi vào nhóm" và "mua lại 30 ngày" sẽ có thêm khách mua nhiều lần; cần thêm cột chiến dịch hoặc ghi chú để không so sánh lẫn giữa chiến dịch 1 lần và nhiều lần.
- **Giới hạn:** vẫn mỗi khách MỘT Tin 1 trong đời (chỉ mục duy nhất theo khách). Khách mua nhiều lần mà từng được mời rồi thì **không** được mời lại.

## 8. Cách làm kỹ thuật (cho người làm tiếp)

- Bản ghi `CustomerFilter` (bất biến) + `CustomerFilterSql` sinh câu truy vấn **có tham số**, mỗi khóa là điều kiện nằm trong danh sách trắng; không ghép chữ do người dùng nhập vào SQL. Mở rộng từ `ContactService.Pick` (giữ tương thích, `Pick` chuyển thành vỏ mỏng). Ba đường dùng chung: `eligibleNotInvited`, `countEligibleNotInvited`, `newCandidates`.
- Cột `customer_filter JSONB` ở `invitation_campaign` (và `filter_json` ở `campaign_proposal`). Bộ lọc cũ ở đề xuất chuyển hóa sang JSON khi đọc.
- Phễu "từng điều kiện loại bớt": đếm lũy tiến từng điều kiện (tối đa khoảng 10 truy vấn đếm nhẹ, có chỉ mục `shipment(receiver_phone)`).
- Dấu hiệu đơn đổi lưu ở `app_setting`; đánh dấu đơn đổi tính lúc truy vấn (không thêm cột vào `shipment`, không đổi cách nhập đơn).
- Mẫu của người dùng: bảng `campaign_filter_template (id, name, filter_json, created_by, created_at)`; mẫu dựng sẵn nằm trong mã.
- Tất cả chữ giao diện tiếng Việt; không script/style nội tuyến (CSP). Test: tính đúng từng bộ lọc (kể cả tổ hợp "mua ≤ 3 lần và không đơn đổi"), bộ lọc không bao giờ lấy khách từ chối/chặn/cần kiểm tra/khách đã mời, chế độ thử vẫn chặn, validator bỏ khóa lạ và giá trị ngoài miền, snapshot không lộ chữ tự do, số khớp dưới 10 bị ẩn.

## 9. Thứ tự các PR (mỗi bước một PR, tắt được, không nới hạn mức)

| PR | Nội dung | Migration | Rủi ro |
|---|---|---|---|
| **A** (PR này) | tài liệu thiết kế, cập nhật quy tắc | không | không |
| **1** | `CustomerFilter` + SQL; lưu vào chiến dịch; form có đủ bộ lọc **trừ** số lần mua > 1; tự nhận khách mới dùng bộ lọc; dấu hiệu đơn đổi; hiển thị "Chiến dịch này mời ai" | V59 | thấp (chỉ làm hẹp) |
| **2** | Xem trước số khách khớp, chia khu vực, bảng từng điều kiện loại bớt | không | thấp (chỉ đếm) |
| **3** | Số lần mua nhiều hơn 1 theo chiến dịch (`max_purchases`), `stillEligible` theo chiến dịch, ghi cơ sở liên hệ, cập nhật quy tắc cũ | V60 | **cao nhất** (đụng chốt chặn dùng chung); review kỹ, test khóa |
| **4** | AI dùng cùng bộ lọc: khuôn JSON, hướng dẫn sinh từ khuôn, số khớp ẩn danh trong số liệu, `ProposalValidator`, nối vào "Triển khai", bảng kết quả | V61 | trung bình (chưa chạy DeepSeek thật) |
| **5** | Mẫu dựng sẵn + lưu mẫu của người dùng | V62 | thấp |

Số migration chỉ là dự kiến: trước khi mở mỗi PR phải gộp `main` vào nhánh và xem số cuối (`CLAUDE.md` mục 4a).

## 10. Việc cố ý chưa làm

Bộ lọc "hoặc" trong một chiến dịch; lọc theo lý do đổi hàng (không có dữ liệu); lọc theo nguồn khách TikTok (hệ thống không có đơn TikTok); thử A/B giữa hai bộ lọc; AI tự đề xuất sửa bộ lọc của chiến dịch đang chạy; thêm chỗ điền `{so_lan_mua}` vào mẫu tin.

## 8. Đã làm ở PR 4 (AI dùng cùng bộ lọc)

- Migration V61: `campaign_proposal.filter_json`. Đề xuất AI có khóa tùy chọn `filter` (khóa: `purchases_min`, `purchases_max`, `exchange`, `cancelled`, `returned`, `no_open_orders`, `max_shipments`, `bought`, `bought_mode`, `not_bought`, `cod_min`, `cod_max`, `skip_open_cases`).
- `ProposalValidator.filter` kiểm chặt từng khóa; lỗi thì bỏ đề xuất kèm lý do, không sửa hộ. Không dùng cùng lúc `product_group` và `filter.bought`.
- Bản chụp ẩn danh có thêm `insights.audience` (số khách khớp các kiểu lọc phổ biến, ô dưới 10 khách ẩn; cờ `exchange_ready`, `settlement_available`). Không có thông tin cá nhân.
- `AdvisorService.checkMatches`: đếm số khách khớp thật; không khớp thì bỏ đề xuất, khớp ít hơn lô thì kẹp lô và ghi rủi ro.
- Chiến dịch mời khách mua nhiều lần (`purchases_max` > 1) không bị chặn bởi số khách mua 1 lần của khu vực, vẫn không quá trần chiến dịch và hạn mức ngày x số ngày.
- Duyệt đề xuất tạo chiến dịch chờ duyệt mang đúng bộ lọc và số lần mua; trang `/advisor` hiện nhãn "Mời cả khách mua nhiều lần" và các dòng mô tả bộ lọc.
