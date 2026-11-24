# Cây test và đề xuất thuật toán cho việc xếp khách vào nhóm Zalo và tự tạo nhóm

> **Cập nhật 24/11/2026:** các lỗi ở mục 3 đã được sửa ở PR #104 đến #109. Chủ dự án chốt thêm: nhóm khoảng 5 KHÁCH (không tính tài khoản phụ và mẹ), trần mềm 8 khách, không đề xuất "sức chứa 30" ở mục 4.1 nữa. Cây test mới và đánh giá hợp lý ở `docs/CAY-TEST-KHO-NGO-VA-DANH-GIA-HOP-LY.md`. Tài liệu này giữ làm lịch sử.


Viết 24/11/2026 theo yêu cầu của chủ dự án: "lập cây test trường hợp quá nhiều khách đồng ý kết bạn, quá ít khách... lập ra những cây test khó ngờ nhất để đề xuất thuật toán, logic hợp lý".

**Đây là tài liệu đề xuất. Chưa sửa mã, chưa chạy test mới, chưa thử với Zalo thật.** Cột "mã hiện tại" bên dưới là kết quả ĐỌC mã (ghi rõ chỗ nào chỉ suy ra). Một luồng khác đang sửa lỗi phó nhóm (thêm mẹ vào nhóm trước rồi mới đặt phó nhóm) và đề xuất đồng bộ danh sách bạn; tài liệu này chỉ nêu yêu cầu kiểm thử cho hai việc đó, không đụng mã của chúng.

Mã được đọc: `GroupPlanner`, `ZaloGroupCreationService`, `ZaloGroupSettings`, `ZaloGroupProperties`, `ZaloAutoService` (nghe kết bạn, `addNext`), `ZaloAutoSettings` (hạn mức, cầu dao), `GroupService.pickGroup/rank`, `InviteService` (giữ chỗ, `markJoinedExact`), `GeoArea`, và bộ nối `zalo-bridge` (`createGroup`, `EventBuffer`, `classify`). Test hiện có: `GroupPlannerTest` (7 ca), `ZaloGroupCreationTest` (khoảng 28 ca).

---

## 0. Điểm chính (đọc phần này là đủ nếu ít thời gian)

1. **Chỗ nghẽn là việc tạo nhóm, không phải việc thêm người.** Mặc định mỗi ngày tạo tối đa 1 nhóm, mỗi nhóm 5 khách. Có 40 khách đồng ý cùng lúc thì cần ít nhất 8 ngày, có 500 khách thì 100 ngày. Việc thêm người dùng hạn mức riêng (mặc định 50 người mỗi ngày) nên không phải nút thắt. Đề xuất: nhóm lớn hơn (ví dụ mục tiêu 30 khách), tạo nhóm với 3 đến 5 người đầu rồi để đường thêm người sẵn có đưa dần các khách còn lại vào.
2. **Khách "dư" bị kẹt vĩnh viễn.** Ngưỡng tạo nhóm là 3 khách chờ. Sau khi nhóm đầy, 1 đến 2 khách còn dư ở một tỉnh không bao giờ đủ ngưỡng nếu tỉnh và vùng đó không có thêm người. Không có hạn chờ, không có đường thoát, và không có cảnh báo riêng cho mẹ. Đề xuất: "tuổi chờ" làm khách tự được nâng lên phạm vi rộng hơn, và quá hạn thì cho vào nhóm gần nhất hoặc nhóm chung.
3. **Lệch giữa người lập kế hoạch và người thêm khách (lỗi thật, đã đọc mã).** Bộ lập kế hoạch coi một nhóm "còn chỗ" khi nhóm đang mở, có link, chưa đầy (`GroupService.pickGroup`). Còn bộ nối chỉ thêm được vào nhóm có **cả mã nhóm Zalo lẫn link** (`ZaloAutoService.addNext`). Một nhóm mẹ tạo tay, có link nhưng chưa gán mã nhóm Zalo, làm bộ lập kế hoạch thôi đề xuất nhóm mới, còn bộ nối không thêm được ai. Khách chờ mãi, hệ thống không báo gì.
4. **Ba lần liền lỗi riêng người làm cầu dao nhảy cho cả hệ thống** (`lastAddsAllPersonErrors(3)`). Khách hủy kết bạn, chặn tài khoản phụ, hoặc cài đặt riêng tư "không cho thêm vào nhóm" đều có thể cho ra lỗi kiểu này. Ba khách như vậy xếp liền nhau trong hàng đợi là dừng cả gửi tin lẫn thêm nhóm, đòi người vào xem rồi mở lại. Với vài trăm khách thật thì xác suất gặp ba khách liền là cao.
5. **Mất sự kiện kết bạn khi cầu nối khởi động lại.** Bộ đệm sự kiện nằm trong bộ nhớ. Khách đồng ý lúc cầu nối chết thì Adh không bao giờ biết; lời mời nằm ở trạng thái "đã nhắn" mãi, không ai nhắc. Đề xuất: đồng bộ định kỳ (xem mục 4.5), có điều kiện vì quy tắc hiện hành cấm bộ nối đọc danh sách bạn.
6. **Bị từ chối đề xuất ở cấp tỉnh thì đề xuất lại ở cấp vùng.** Mẹ từ chối đề xuất nhóm Cần Thơ, 24 giờ sau hệ thống có thể đề xuất nhóm "Đồng bằng sông Cửu Long" chứa đúng những khách đó (`excludedScopes` chỉ so với từng cấp, không so khu vực bao nhau).
7. **Giữa lúc đề xuất và lúc tạo, khách có thể đã đổi.** Lúc tạo, `eligibleMembers` không kiểm trạng thái thêm vào nhóm (`zalo_add_state`) như `waitingCustomers` đã kiểm. Một khách vừa lỗi riêng người vẫn được đưa vào nhóm mới.
8. **Chưa có cách nào biết nhóm bị giải tán hoặc tài khoản phụ bị đá khỏi nhóm.** Lần thêm tiếp theo vào nhóm đó trả lỗi lạ, rơi vào nhánh "lỗi lạ" của `ZaloAutoService.addOne` và làm cầu dao nhảy (an toàn, nhưng mỗi nhóm hỏng là một lần dừng toàn hệ thống). Đề xuất: lỗi mã nhóm riêng thì tạm dừng đúng nhóm đó.
9. **Quy tắc cần chủ dự án quyết:** CLAUDE.md mục 3 ghi bộ nối "KHÔNG thêm nhận lời mời, đọc danh sách bạn". Đồng bộ danh sách bạn (phương án sửa lỗi 5) trái quy tắc đó, nên cần chủ dự án duyệt riêng. Có hai phương án nhẹ hơn không cần đổi quy tắc (mục 4.5).

---

## 1. Hệ thống hiện làm gì (đã đọc mã, tóm tắt cho dễ đối chiếu)

**Luồng.** Tin 1 → (gửi lời mời kết bạn) → khách đồng ý, bộ nối ghi sự kiện `ADD` → `ZaloAutoService.poll` ghép sự kiện với lời mời theo mã Zalo đã tra lúc gửi Tin 1 (`zalo_target_uid`), hoặc theo số điện thoại → lời mời thành `FRIENDED`, đặt `zalo_add_state` là `QUEUED` (nấc tự động) hoặc `WAITING_APPROVAL` → `addNext` mỗi nhịp 30 giây thêm tối đa MỘT người vào nhóm đã có mã Zalo và link, đúng khu vực.

**Tự tạo nhóm (`ZaloGroupCreationService.step`, mỗi 10 phút).**
1. Lấy khách `FRIENDED`, có mã Zalo, chưa vào nhóm, chiến dịch cho phép tạo nhóm, và **không có nhóm nào hợp khu vực còn chỗ** (`waitingCustomers`). Khách không rõ tỉnh bị bỏ.
2. `GroupPlanner.plan` xét lần lượt cấp tỉnh, vùng, miền. Cấp đầu tiên có khu vực đủ `minCustomers` (mặc định 3, chỉnh 2 đến 5) khách thì chọn; trong cấp đó chọn khu vực có nhiều khách nhất, hòa thì khu vực có người kết bạn sớm nhất. Lấy tối đa 5 khách kết bạn sớm nhất.
3. Nấc Xác nhận: ghi đề xuất, đợi mẹ duyệt (hết hạn sau 48 giờ). Nấc Tự động: tạo luôn nếu `mayCreateNow`.
4. Giới hạn: khung 08:00 đến 20:00, trần mặc định 1 nhóm/ngày (chỉnh đến 3, bằng trần cầu nối), nghỉ cố định 60 phút (cầu nối có sàn 5 phút), chỉ một lần gọi Zalo mỗi nhịp, cầu dao chung.
5. Sau khi tạo: ghi nhóm mới (`target = 5`, khu vực của kế hoạch), đưa người Zalo xác nhận đã vào nhóm thành `JOINED`, người còn lại ở hàng đợi thêm người sẵn có.

**Các con số quan trọng khi suy ra thông lượng (đọc từ `ZaloGroupProperties`, `ZaloAutoProperties`, `application.yml`):**

| Giới hạn | Mặc định | Trần cứng | Ghi chú |
|---|---|---|---|
| Nhóm tạo mỗi ngày | 1 | 3 | cầu nối cũng trần 3, vượt thì dừng cầu dao |
| Khách mỗi nhóm lúc tạo | 5 | 5 | cầu nối `MAX_GROUP_MEMBERS = 5` |
| Mục tiêu khách mỗi nhóm (`target`) | 5 (cứng trong mã khi tạo) | 500 (kiểm ở `GroupService.validateTarget`) | admin sửa tay được |
| Khách chờ tối thiểu để tạo | 3 | 2 đến 5 | |
| Nghỉ giữa hai lần tạo | 60 phút | sàn 10 (Adh), 5 (cầu nối) | |
| Người thêm vào nhóm mỗi ngày | 50 | 100 | mỗi giờ 5 (trần 10), nghỉ 2 phút |
| Khung giờ | tạo nhóm 08:00-20:00; thêm người 07:00-22:00 | | cầu nối 07:00-22:00 |
| Giữ chỗ nhóm | 24 giờ | | chỉ có ở đường tay và đường gửi link, bộ nối tự thêm không giữ chỗ |
| Sự kiện kết bạn ở cầu nối | tối đa 1000, giữ 48 giờ, chỉ trong bộ nhớ | | mất khi khởi động lại |

Ghi chú: `GroupPlannerTest` và `ZaloGroupCreationTest` đã phủ: dưới ngưỡng, ưu tiên tỉnh, gộp vùng, gộp miền, khác miền không gộp, hòa thì người sớm nhất, khu vực bị từ chối, trần ngày, nghỉ cố định, ngoài giờ, cầu dao, không rõ kết quả, nhóm chưa có link, khách mất điều kiện lúc tạo. Những nhánh dưới đây đánh dấu **[có test]** là đã có, còn lại **[chưa có test]**.

**Ký hiệu cột đánh giá:** ✔ mã làm đúng ý thiết kế; ⚠ chạy được nhưng có điểm yếu hoặc chưa chắc (lý do nêu rõ); ✖ lệch, nên sửa; ? chưa biết vì phụ thuộc hành vi Zalo thật.

---

## 2. Cây test

Quy ước: mỗi nút lá là một ca. `N` là số khách `FRIENDED` đang chờ nhóm. Mặc định cấu hình như bảng trên, nấc Tự động, trong khung giờ, cầu dao không nhảy, chưa có nhóm nào, trừ khi nêu khác.

### A. Số lượng khách đồng ý

```
A. Số khách chờ (cùng một tỉnh, chưa có nhóm)
├─ A1  N = 0
├─ A2  N = 1
├─ A3  N = 2
├─ A4  N = đúng ngưỡng (3)
├─ A5  N = 5 (vừa đầy một nhóm)
├─ A6  N = 6 (dư 1 so với một nhóm)
├─ A7  N = 40
├─ A8  N = 500
├─ A9  N tăng từng người trong ngày (1, 2, 3, ... qua từng nhịp 10 phút)
└─ A10 N = 7 rồi nhóm đầy còn dư 2 (phần dư)
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| A1 | Không đề xuất, không gọi Zalo, không báo lỗi | `plan` trả rỗng, `Step.NOTHING` | ✔ [có test: khongDuNguongThiKhongTaoNhom, macDinhTat] |
| A2 | Không tạo nhóm 1 người. Khách ghi nhận "đang chờ nhóm" và có hạn rõ | Không tạo. Khách ở lại `FRIENDED`, hiện trong "khách chờ nhóm" theo tỉnh (`GroupService.waitingByProvince`), không có hạn, không có hành động tiếp | ⚠ Đúng ngưỡng nhưng khách không có đường thoát (xem 4.3) |
| A3 | Như A2 | Như A2. Thêm: nếu `minCustomers` chỉnh về 2 thì hai người đủ ngưỡng và tạo nhóm; `execute` đòi `members.size() >= 2` | ✔ [có test: duoiNguongKhongTaoNhomMotHaiNguoi] ⚠ Chưa biết Zalo có cho tạo nhóm 2 người (tài khoản phụ thành ba) không (?) |
| A4 | Tạo đúng 1 nhóm 3 khách | Đề xuất `waiting=3`, nhóm 3 người | ✔ [có test] |
| A5 | 1 nhóm, đủ 5 người, nhóm vừa tạo "đầy" (score = 5 ≥ target 5) | Đúng. Khách thứ 6 sau đó không có chỗ, phải chờ nhóm mới | ✔ |
| A6 | Nhóm 1: 5 người. Khách thứ 6 ở lại chờ, không đủ ngưỡng | `plan` chọn 5 sớm nhất, dư 1 ở lại, không được xem lại tới khi có thêm người | ⚠ Phần dư kẹt (xem A10) |
| A7 | 40 khách cùng lúc nên được xếp xong trong thời gian hợp lý (ví dụ ≤ 3 ngày), không phải 8 ngày | Trần 1 nhóm/ngày × 5 = 5 khách mỗi ngày, cần 8 ngày. Trần 3 nhóm/ngày cũng chỉ 15 khách/ngày, cần 3 ngày | ✖ Thông lượng thấp, xem 4.1 |
| A8 | 500 khách: xong trong vài ngày, không vượt hạn mức an toàn | 100 ngày ở mặc định, 34 ngày ở trần 3 nhóm/ngày. Hạn mức thêm người 50/ngày cho 500 người cũng là 10 ngày (đó mới là giới hạn thật nếu nhóm đủ lớn) | ✖ Cần nhóm lớn (4.1) |
| A9 | Khách đến lẻ tẻ: nhóm chỉ tạo khi đủ 3, khách chờ không quá hạn | Nhịp 10 phút xét lại; khi khách thứ 3 vào thì đề xuất. Khách 1 và 2 chờ theo khách thứ 3 | ✔ tính đúng; ⚠ hạn chờ không ai theo dõi |
| A10 | Phần dư (1 đến 2 khách) không kẹt vô hạn | Kẹt tới khi tỉnh hoặc vùng có thêm khách. Với tỉnh ít khách, có thể mãi mãi | ✖ **Lỗi thiết kế quan trọng nhất.** Xem 4.3 |

**Điều kiện đi kèm A10 (khó ngờ):** nhóm còn đúng 1 chỗ trống thì `pickGroup` còn trả nhóm đó, nên `waitingCustomers` lọc ra hết khách của tỉnh đó, đề xuất không bao giờ ra. Bộ nối thêm khách đầu hàng vào chỗ cuối, nhóm đầy, các khách còn lại mới thành "chờ", rồi mới xét ngưỡng 3. Hai khách còn lại không đủ ngưỡng, **dù trước đó 3 khách đã đủ**. [chưa có test]

### B. Phân bố địa lý

```
B. Khách rải trên các tỉnh
├─ B1  Mỗi tỉnh đúng 1 khách, nhiều tỉnh khác miền (ví dụ 20 khách, 20 tỉnh)
├─ B2  Mỗi tỉnh 1 khách, cùng một vùng (ví dụ 5 tỉnh Đồng bằng sông Cửu Long)
├─ B3  Một tỉnh đông (12), các tỉnh khác 1 đến 2
├─ B4  Hai tỉnh cùng 4 khách (hòa)
├─ B5  Ranh giới
│   ├─ B5a  Tỉnh đủ ngưỡng nằm cạnh vùng khác (Quảng Nam ở Nam Trung Bộ, Kon Tum ở Tây Nguyên)
│   ├─ B5b  Vùng đủ ngưỡng nhưng không có tỉnh nào đủ
│   ├─ B5c  Miền đủ ngưỡng nhưng không có vùng nào đủ
│   └─ B5d  Chỉ riêng Tây Nguyên (xếp vào miền Trung)
├─ B6  Khách không rõ tỉnh (province rỗng hoặc mã lạ)
├─ B7  Khách rõ tỉnh nhưng nhóm sẵn có là "Chung" (không gắn khu vực)
└─ B8  Đã có nhóm miền Nam còn chỗ, khách ở tỉnh Nam mới đến
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| B1 | 20 người, 20 tỉnh, 3 miền: không tạo nhóm 1 người; gom theo miền khi miền đủ 3 | Cấp tỉnh, vùng đều không đủ 3 nên xuống cấp miền. Mỗi miền ≥ 3 thì ra đề xuất (lấy miền nhiều khách nhất trước) | ✔ về nguyên tắc [có test: vungChuaDuThiGopLenMien] |
| B2 | 5 tỉnh một vùng, 1 người mỗi tỉnh: 1 nhóm vùng 5 người | Cấp tỉnh không đủ, cấp vùng đủ 5, ra 1 nhóm | ✔ |
| B3 | Nhóm tỉnh đông (12) tạo trước, mấy khách lẻ ở tỉnh khác trong vùng sau đó gộp vào nhóm vùng | Tỉnh đông lấy 5 sớm nhất, 7 còn lại vẫn "chờ" (kể cả khi nhóm đã tạo, nhóm tỉnh đó còn 0 chỗ) → vòng sau lại ra đề xuất tỉnh đó: ngày 2 nhóm tỉnh 5 người, ngày 3 nhóm 2 người **không tạo được**, 2 người dư | ⚠ Mỗi ngày chỉ giải được 5 người; phần dư 2 xem A10 |
| B4 | Hòa thì khách đến sớm hơn được ưu tiên, kết quả ổn định giữa các lần chạy | `comparingInt(-size).thenComparing(earliest).thenComparing(key)` | ✔ [có test: bangSoKhachThiLayNoiCoNguoiKetBanSomNhat] |
| B5a | Tỉnh đủ ngưỡng thì tạo nhóm tỉnh dù vùng có nhiều khách hơn | Đúng, cấp tỉnh xét trước, không so với số khách ở cấp trên | ⚠ Có thể tạo nhóm tỉnh 3 người trong khi cả vùng có 25 khách cần xếp. Xem 4.2 |
| B5b | Gộp lên vùng | Đúng | ✔ |
| B5c | Gộp lên miền | Đúng, nhóm gắn `R:<miền>` | ✔ |
| B5d | Tây Nguyên gộp được với Bắc Trung Bộ và Nam Trung Bộ ở cấp miền Trung nhưng không ở cấp vùng | `SubRegion.TAY_NGUYEN` là vùng riêng, miền Trung | ✔ |
| B6 | Khách không rõ tỉnh nên được nhóm "chung" hoặc mẹ xếp tay; có cảnh báo | `waitingCustomers` bỏ qua khách không có tỉnh hợp lệ, **không bao giờ đề xuất** cho họ. Nhưng `addNext` lại xếp được họ vào BẤT KỲ nhóm nào đang mở (`rank(g, null)` trả 1 cho nhóm có khu vực) | ⚠ Hai nơi hiểu khác nhau. Khách không rõ tỉnh có thể bị thêm vào nhóm miền Bắc dù họ ở miền Nam; nếu không có nhóm nào thì chờ vô hạn [chưa có test] |
| B7 | Có nhóm "Chung" còn chỗ thì khách có khu vực được xếp (rank 3) và không tạo nhóm mới | `rank` trả 3 cho nhóm chung với khách có tỉnh; `pickGroup` còn trả nhóm này, nên bộ lập kế hoạch không đề xuất | ⚠ Khách miền Nam bị đưa vào nhóm chung dù ý định chia theo khu vực; chấp nhận được nhưng cần chủ dự án biết |
| B8 | Không tạo nhóm miền mới khi nhóm miền còn chỗ | Đúng (lọc ở `waitingCustomers`) | ✔ [có test: nhomDaCoChoThiKhongDeXuat] |

### C. Vòng đời của khách từ lúc đồng ý đến lúc vào nhóm

```
C. Khách đổi ý hoặc thay đổi trong lúc chờ
├─ C1  Đồng ý rồi từ chối nhận tin (AI nhận diện hoặc bấm tay) trước khi vào nhóm
├─ C2  Đồng ý rồi hủy kết bạn
├─ C3  Đồng ý rồi chặn tài khoản phụ
├─ C4  Từ chối sau khi đã nằm trong đề xuất chờ mẹ duyệt
├─ C5  Từ chối ngay giữa lúc đang gọi tạo nhóm
├─ C6  Đã nằm trong nhóm Zalo khác (do mẹ thêm tay hoặc tự bấm link)
├─ C7  Hai khách trùng số điện thoại
├─ C8  Cùng một tài khoản Zalo gắn với hai khách (hai số, một người)
├─ C9  Kết bạn lúc giữ chỗ hết hạn (24 giờ)
├─ C10 Khách đồng ý rồi rời nhóm vừa được thêm
└─ C11 Khách đồng ý kết bạn nhưng không bao giờ trả lời tin nhắn (im lặng)
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| C1 | Khách bị bỏ khỏi đề xuất và khỏi danh sách chờ; không bao giờ bị thêm vào nhóm | `waitingCustomers` và `eligibleMembers` đều kiểm `contacts.mayContact` → `stillEligible` (có sổ từ chối). Không bao giờ xóa số điện thoại (quy tắc 5a) | ✔ [có test: khachKhongConDuDieuKienLucTaoThiBoRa] |
| C2 | Hủy kết bạn thì không còn thêm được vào nhóm; không làm hỏng cả hệ thống | Hệ thống không nghe sự kiện hủy kết bạn (chỉ `ADD`). Lần thêm vào nhóm sau đó có thể trả lỗi riêng người hoặc lỗi lạ (?) | ✖ Nếu lỗi riêng người thì 3 lần liền làm cầu dao nhảy (xem F5). Chưa biết Zalo cho thêm người không phải bạn vào nhóm hay không (?) |
| C3 | Như C2 | Như C2 | ✖ [chưa có test] |
| C4 | Đề xuất vẫn chạy với số người còn lại; còn dưới 2 người thì hết hạn, không gọi Zalo | `execute` kiểm lại `eligibleMembers`, dưới 2 thì `EXPIRED/KHONG_CON_DU_KHACH` | ✔ [có test: conQuaItKhachLucTaoThiHetHanKhongGoiZalo] |
| C5 | Đúng như C4, nhưng nếu khách từ chối sau khi đã gọi Zalo thì chỉ ghi nhận "đã từ chối", mẹ mời ra khỏi nhóm tay (`markDeclined` đã làm) | Đã xử lý ở luồng từ chối | ✔ |
| C6 | Không thêm hay tạo nhóm mới cho người đã ở nhóm khác | `waitingCustomers` lọc `group_id IS NULL`, `eligibleMembers` kiểm `group_id IS NULL`, chỉ khi hệ thống ĐÃ biết. Nếu mẹ thêm tay trên Zalo mà chưa bấm "Đã vào nhóm" thì hệ thống không biết | ⚠ Thêm trùng người vào hai nhóm; Zalo có thể báo "đã là thành viên" như lỗi riêng người (?) |
| C7 | Một số điện thoại một khách | Cột `customer.phone` là `UNIQUE` nên không có hai khách trùng số | ✔ (`customer.phone` là `UNIQUE`, V1) |
| C8 | Một mã Zalo chỉ gắn một lời mời | Chỉ mục duy nhất `uq_invitation_zalo_uid`; `attach` kiểm trong câu lệnh, thất bại thì sự kiện ghi `BLOCKED/TRUNG_UID` | ✔ nhưng người thứ hai bị chặn không có thông báo gì cho mẹ [chưa có test] |
| C9 | Khách kết bạn muộn hơn 24 giờ sau khi giữ chỗ: chỗ giữ trả về, khách vẫn được xếp | Đường bộ nối không giữ chỗ. Đường tay có giữ 24 giờ, hết hạn thì `waitingCustomers` tính lại (điều kiện `reserved_until <= now()`) | ✔ [có test cho giữ chỗ ở luồng khác] |
| C10 | Khách rời nhóm vừa vào thì ghi nhận, không tính vào số khách nhóm | `markLeft` chỉ do người báo tay; bộ nối không nghe sự kiện rời nhóm | ⚠ Số khách trong nhóm lệch dần, nhóm "đầy" giả. Mẹ phải đếm lại hằng tuần (`RECOUNT_DAYS`) |
| C11 | Khách im lặng sau khi kết bạn vẫn được thêm vào nhóm đúng như đã được hứa trong Tin 1 | Đúng | ✔ |

### D. Thời gian và mất sự kiện

```
D. Thời điểm và độ tin cậy sự kiện
├─ D1  Khách đồng ý lúc 21:00
├─ D2  Khách đồng ý lúc 03:00
├─ D3  Cầu nối khởi động lại: sự kiện đang trong bộ nhớ
│   ├─ D3a  mất sự kiện chưa được Adh lấy
│   ├─ D3b  Adh đã lấy nhưng chưa ghi xong (con trỏ chưa cập nhật)
│   └─ D3c  Adh lỡ liên tục hơn 48 giờ
├─ D4  Đề xuất chờ mẹ duyệt quá 48 giờ
├─ D5  Mẹ duyệt đề xuất lúc 19:59 hoặc 20:00
├─ D6  Đồng hồ máy chủ lệch hoặc đổi múi giờ
└─ D7  Sự kiện kết bạn trùng (cùng uid gửi hai lần)
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| D1 | Sự kiện được ghi nhận ngay. Việc tạo nhóm chờ tới 08:00 hôm sau, nhưng thêm người vào nhóm sẵn có vẫn được tới 22:00 | `poll` không giới hạn khung giờ. `addNext` theo 07:00-22:00, tạo nhóm theo 08:00-20:00 (hai khung khác nhau) | ✔ Đúng thiết kế. ⚠ Hai khung giờ khác nhau dễ gây hiểu nhầm, nên ghi cạnh nhau trên trang Zalo [chưa có test cho D1] |
| D2 | Như D1, chờ tới sáng | Như D1 | ✔ |
| D3a | Khách đồng ý lúc cầu nối chết không bị lãng quên | `EventBuffer` trong bộ nhớ (chủ ý, vì có thể chứa số điện thoại). Mất thì lời mời ở `SENT`, nhãn "không phản hồi" sau 14 ngày; khách đã là bạn thật nhưng không vào nhóm | ✖ **Không có đường nào phát hiện.** Cần đồng bộ (4.5) |
| D3b | Không ghép trùng, không bỏ sót | Con trỏ chỉ cập nhật sau khi giao dịch xong (`cfg.setCursor` sau `tx.executeWithoutResult`). Sự kiện đã ghi thì `bridge_seq` trùng được bỏ qua (`seen > 0`) | ✔ [có test cho ingest ở ZaloAutoTest] |
| D3c | Lỡ lâu thì có cảnh báo | Sự kiện quá 48 giờ bị bỏ ở cầu nối, không có dấu vết | ✖ Cần cảnh báo "cầu nối mất liên lạc quá N giờ, có thể đã lỡ sự kiện" (sức khỏe tài khoản đã có cảnh báo cầu nối mất 15 phút, nhưng chưa gắn với việc lỡ sự kiện) |
| D4 | Đề xuất chờ quá lâu hết hạn, hệ thống đề xuất lại theo số khách hiện tại | `expireOld` đặt `EXPIRED/QUA_HAN`; vòng sau `planIfDue` tính lại | ✔ [có test: deXuatQuaHanThiHetHan] |
| D5 | Duyệt sát giờ đóng, tạo ở nhịp trong khung hoặc chờ tới sáng hôm sau | `execute` kiểm `mayCreateNow`; không trong khung thì `WAIT`, đề xuất giữ `APPROVED` | ✔ [có test: daDuyetNhungNgoaiGioThiChoDenKhiDuocPhep]. ⚠ Chờ qua đêm có thể vượt hạn 48 giờ của đề xuất nếu duyệt muộn |
| D6 | Đổi múi giờ không làm sai trần ngày | Tính theo `clock.getZone()` (Adh) và `vnDayStart` (cầu nối) | ⚠ Hai nơi tính "đầu ngày" bằng hai cách; nếu máy chủ không đặt giờ Việt Nam thì lệch (chưa kiểm cấu hình `Clock` của Adh) |
| D7 | Không xử lý hai lần | `zalo_friend_event.bridge_seq` là khóa duy nhất; `attach` kiểm trùng uid | ✔ |

### E. Tình trạng nhóm

```
E. Nhóm
├─ E1  Nhóm vừa tạo đã đầy (5 khách) và còn người chờ
├─ E2  Nhóm bị giải tán trên Zalo
├─ E3  Tài khoản phụ bị đá khỏi nhóm
├─ E4  Nhóm có link nhưng chưa gán mã nhóm Zalo (mẹ tạo tay)
├─ E5  Nhóm có mã nhóm Zalo nhưng chưa có link (bật link lỗi lúc tạo)
├─ E6  Nhóm tạm dừng hoặc đóng
├─ E7  Tên nhóm
│   ├─ E7a  Đã có 3 nhóm "Miền Nam" rồi xóa 1
│   └─ E7b  Hai nhóm cùng tên
├─ E8  Phó nhóm (mẹ)
│   ├─ E8a  Mẹ chưa kết bạn với tài khoản phụ
│   ├─ E8b  Mẹ chưa là thành viên nhóm lúc đặt phó nhóm
│   └─ E8c  Mã Zalo của mẹ trùng với một khách
├─ E9  Tạo nhóm: một vài người trong danh sách không vào được
└─ E10 Số khách thật trong nhóm khác số hệ thống tính
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| E1 | Khách vượt quá sức chứa thì chờ nhóm mới hoặc nhóm khác cùng khu vực, không bị bỏ | `full()` là `score ≥ target`. Người thứ 6 ở lại `FRIENDED/QUEUED`, `addNext` trả `NO_GROUP`; bộ lập kế hoạch xét lại | ✔ nhưng ⚠ phụ thuộc ngưỡng và tốc độ tạo (A10) |
| E2 | Nhóm giải tán thì hệ thống đóng nhóm đó, không làm dừng cả hệ thống, mẹ được báo | Không nghe sự kiện nhóm. Lần thêm sau trả lỗi mà `classify` không nhận ra thành `SYSTEM_ERROR/Zxxx`, vào nhánh `default` của `addOne`: `fail(...)` rồi `tripAndStop` | ✖ Cầu dao nhảy mỗi lần một nhóm hỏng. Cần phân loại "lỗi của nhóm" riêng (4.6) [chưa có test] |
| E3 | Như E2 | Như E2 | ✖ |
| E4 | Bộ lập kế hoạch và bộ nối phải thống nhất nhóm nào "còn chỗ" | **Lệch** như đã nêu ở điểm chính 3. `waitingCustomers` dùng `groups.pickGroup(all, province)`, còn `addNext` chỉ dùng nhóm có `hasZaloId() && hasLink() && OPEN` | ✖ Khách chờ mãi, không có đề xuất mới, không có cảnh báo [chưa có test] |
| E5 | Nhóm không có link thì không nhận khách tự động, ghi chú nhắc mẹ | `record` để nhóm ở trạng thái mặc định `PREPARING` (không phải `OPEN`) khi thiếu link, thêm ghi chú | ✔ [có test: khongCoLinkThiNhomTamDungVaGhiChu] ⚠ Nhóm chưa mở này không được tính là "còn chỗ" nên bộ lập kế hoạch có thể tạo nhóm mới ngay vòng sau (số nhóm nhân đôi) |
| E6 | Nhóm không còn mở thì không nhận | `pickGroup` lọc `OPEN` | ✔ |
| E7a | Tên không trùng gây rối | `nextName` đếm số nhóm cùng `geo_scope` rồi cộng 1. Xóa một nhóm thì số lặp lại | ⚠ Nhẹ: Zalo cho trùng tên, nhưng mẹ khó phân biệt |
| E7b | Như E7a | Như E7a | ⚠ |
| E8a | Phó nhóm lỗi không chặn tạo nhóm; mẹ được nhắc | Bộ nối báo `deputy = FAILED`, nhóm vẫn ghi `DONE`; `deputy_state` hiện cho mẹ | ✔ (luồng khác đang sửa thứ tự thêm mẹ rồi mới đặt phó nhóm; **không đụng ở tài liệu này**) |
| E8b | Mẹ phải ở trong nhóm trước khi đặt phó nhóm | Hiện đặt phó nhóm ngay sau khi tạo mà mẹ chưa chắc là thành viên (mẹ không nằm trong `members`) | ✖ Đang được luồng khác sửa. Test cần có: tạo nhóm, mẹ là thành viên, phó nhóm OK |
| E8c | Mã mẹ trùng với khách thì không đặt hai vai | `deputyFor` trả null nếu mẹ nằm trong danh sách khách | ✔ |
| E9 | Zalo xác nhận 3 trên 5 người vào, 2 người còn lại ở lại hàng đợi thêm người, không mất | `record` đánh `QUEUED` cho người chưa vào nếu đang `WAITING_APPROVAL` | ✔ [có test: nguoiChuaVaoDuocOLaiHangDoiThemNguoiSanCo]. ⚠ Chưa biết Zalo trả lỗi riêng cho từng người hay làm hỏng cả lệnh tạo (?) |
| E10 | Số khách hệ thống tính khớp số thật, hoặc ít nhất lệch có kiểm soát | Số khách chỉ tăng khi hệ thống ghi nhận; người rời nhóm không thấy (C10); mẹ thêm tay mà không báo cũng không thấy | ⚠ Nhóm "đầy" giả hoặc "trống" giả; phụ thuộc mẹ đếm lại |

### F. Giới hạn an toàn và cầu dao

```
F. Giới hạn
├─ F1  Trần nhóm/ngày đã hết (1/1) nhưng còn khách chờ
├─ F2  Chưa đủ khoảng nghỉ 60 phút sau lần tạo trước
├─ F3  Cầu dao đang ngắt
├─ F4  Cầu dao ngắt GIỮA CHỪNG
│   ├─ F4a  trước khi gọi Zalo
│   ├─ F4b  sau khi Zalo đã tạo nhóm nhưng chưa ghi vào Adh
│   └─ F4c  khi đang thêm người thứ 3 trong 5 người
├─ F5  Zalo trả lỗi lạ khi thêm người
│   ├─ F5a  lỗi một người (PERSON_ERROR) một lần
│   ├─ F5b  lỗi người ba lần liền
│   └─ F5c  lỗi lạ không phân loại được
├─ F6  Hạn mức thêm người/giờ đã hết (5/5) giữa lúc nhóm mới còn 3 người chưa vào
├─ F7  Hạn mức thêm người/ngày đã hết còn 100 khách chờ
├─ F8  Còn một dòng "CREATING" sót (sập giữa chừng)
└─ F9  Bộ nối từ chối vì tạo quá sớm (RATE_LIMITED) hoặc chưa kết nối
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| F1 | Chờ sang ngày, không đề xuất mới trong ngày | `planIfDue` thoát khi `startedToday() >= daily` | ✔ [có test: tuDongTaoNgayNhungToiDaMotNhomMoiNgay] |
| F2 | Chờ hết nghỉ rồi tạo | `mayCreateNow` | ✔ [có test: khoangNghiCoDinhGiuaHaiLanTaoTrongCungNgay] |
| F3 | Không làm gì, không đếm trần | `effectiveMode` trả OFF | ✔ [có test: cauDaoDangNhayThiKhongLamGi] |
| F4a | Chưa gọi Zalo thì trả đề xuất về hàng đợi, không đếm trần | `UNREACHABLE` trả `APPROVED` | ✔ [có test: chuaChamZaloThiTraVeHangDoiVaKhongTinhTran] |
| F4b | Biết nhóm đã có thật. Mẹ kiểm bằng mắt rồi gán vào Adh | Dòng `CREATING` sót, nhịp sau dừng cầu dao `TAO_NHOM_INTERRUPTED`; người bấm "đã kiểm tra" thì `FAILED/KIEM_TRA_TAY` | ✔ [có test: conLaiMotDongDangTaoThiDungNganKhongGoiThem]. ⚠ Nhóm thật nằm trên Zalo mà Adh không biết, khách trong đó cũng không được ghi `JOINED`; mẹ phải làm tay toàn bộ (cần hướng dẫn rõ trên trang) |
| F4c | Không có (tạo cả 5 người trong một lệnh) | Chỉ có F4b | n/a |
| F5a | Một khách lỗi: ghi `FAILED`, xếp Tin 2 (link) nếu chiến dịch cho phép, không dừng cầu dao | `addOne` ghi `fail`, `queueLink`, kiểm tra 3 lần liền | ✔ |
| F5b | Ba khách riêng lẻ liên tiếp lỗi: dừng (nghi cấu hình nhóm sai) | `tripAndStop("NHIEU_LOI_NGUOI")` | ⚠ Đúng ý với "quyền thêm thành viên sai" nhưng **quá nhạy với dữ liệu thật**: ba khách hủy kết bạn liền nhau cũng dừng. Nên phân biệt "ba khách khác nhau, cùng một nhóm" và "ba khách khác nhau, ba nhóm khác nhau", hoặc lấy mẫu rộng hơn (4.6) |
| F5c | Dừng cầu dao, không tự thử lại | Nhánh `default` của `addOne` | ✔ |
| F6 | Hàng đợi chờ giờ sau, không mất người | `mayAddNow` | ✔ |
| F7 | Chờ sang ngày. **Cảnh báo cho mẹ khi hàng chờ dài hơn hạn mức còn lại** | Chỉ chờ, không cảnh báo | ⚠ Nên có số "khách đang chờ nhóm/chờ hạn mức" trên Tổng quan |
| F8 | Dừng, người kiểm tra | Như F4b | ✔ |
| F9 | Trả về hàng đợi, thử lại nhịp sau | `RATE_LIMITED` hoặc `notCreated` thì đề xuất về `APPROVED` | ✔ [có test: zaloHanCheThiDungVaKhongTuThuLai] |

### G. Đồng thời và thao tác trùng

```
G. Đồng thời
├─ G1  Hai nhịp quét tạo nhóm chạy cùng lúc
├─ G2  Hai nhịp: một nhịp tạo nhóm, nhịp kia thêm người (addNext) cho cùng một khách
├─ G3  Mẹ bấm duyệt đề xuất hai lần
├─ G4  Mẹ duyệt đề xuất vừa hết hạn
├─ G5  Mẹ từ chối, sau 24 giờ hệ thống đề xuất lại
│   └─ G5a  từ chối cấp tỉnh, hệ thống đề xuất cấp vùng chứa chính những khách đó
├─ G6  Nhiều đề xuất cùng chờ duyệt
└─ G7  Hai lần "Mở lại cầu dao" liên tiếp
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| G1 | Chỉ một nhịp được chạy | `ReentrantLock.tryLock` trong `step`; Spring chạy `@Scheduled` trên một luồng nên thực tế tuần tự | ✔ (chỉ đúng trong một tiến trình; nhân bản ứng dụng thì khóa vô nghĩa; hiện chỉ chạy một bản) |
| G2 | Một khách không vào hai nơi | Hai thao tác chạy tuần tự trong cùng nhịp (`service.tick()` rồi các hook). Nhưng `eligibleMembers` **không** loại khách có `zalo_add_state = FAILED/DANG_GOI` | ⚠ Khách vừa được `addNext` gọi Zalo mà kết quả chưa ghi (DANG_GOI) thì vòng sau cầu dao đã dừng nên không xảy ra; khách `FAILED` do lỗi riêng người vẫn lọt vào đề xuất [chưa có test] |
| G3 | Lần hai báo "không còn chờ duyệt", không tạo hai nhóm | `approve` dùng `UPDATE ... WHERE state = 'PROPOSED'` | ✔ |
| G4 | Báo lỗi rõ | `approve` không tìm thấy dòng, ném `ImportException` | ✔ |
| G5 | Không đề xuất lại đúng nhóm khách vừa bị mẹ từ chối | `rejected` chỉ chứa mã khu vực bị từ chối trong 24 giờ, so khớp từng cấp | ✖ **G5a**: cấp tỉnh bị loại thì xuống cấp vùng vẫn đủ ngưỡng và ra đề xuất chứa lại những khách đó. Có thể đúng ý mẹ (gộp rộng hơn) hoặc không [chưa có test] |
| G6 | Tối đa một đề xuất chờ | `step` trả `WAIT` khi còn `PROPOSED` | ✔ |
| G7 | Không làm gì hai lần | `reopen` | ✔ (không đọc kỹ; suy ra) |

### H. Các tình huống khó ngờ khác

```
H. Khó ngờ
├─ H1  Bùng nổ: cầu nối chết 2 ngày, lúc sống lại có 40 khách đã đồng ý (nếu có đồng bộ) 
├─ H2  Khách đồng ý rồi hệ thống đổi sang chế độ thử (APP_ZALO_LIVE=false)
├─ H3  Hai chiến dịch, một chiến dịch tắt "cho phép tạo nhóm"
├─ H4  Chiến dịch "chỉ gửi link nhóm" (không tự thêm)
├─ H5  Nhóm 5 người đã đủ nhưng 3 người trong đó vừa rời
├─ H6  Khách ở tỉnh vừa sáp nhập hoặc đổi tên (mã tỉnh lạ)
├─ H7  Mẹ chỉnh minCustomers từ 3 xuống 2 giữa lúc có đề xuất đang chờ
└─ H8  Zalo giới hạn số thành viên tối thiểu để tạo nhóm cao hơn 2 hoặc 3
```

| Mã | Mong đợi | Mã hiện tại | Đánh giá |
|---|---|---|---|
| H1 | Xếp dần theo thông lượng an toàn, theo thứ tự công bằng, không phá trần | Trần ngày cứng; thứ tự kết bạn sớm nhất trước | ✔ về an toàn, ✖ về tốc độ (A8) |
| H2 | Khách thật không bao giờ bị chạm | Ba lớp lọc ở `ContactService`, `mayContact` và `SafeZaloLink` | ✔ [có test: TestModeTest] |
| H3 | Chiến dịch tắt thì khách của nó không bị đưa vào nhóm mới | `waitingCustomers` và `eligibleMembers` đòi `k.allow_group_creation` | ✔ |
| H4 | Không xếp vào hàng thêm tự động | `attach` đặt `zalo_add_state` rỗng khi `group_delivery IS NOT NULL` | ✔ [có test: CampaignGroupTargetTest] |
| H5 | Số khách thật cập nhật khi người báo | `markLeft` | ⚠ C10 |
| H6 | Khách bị bỏ khỏi bộ lập kế hoạch và hiện ở "chưa rõ khu vực" cho mẹ xử lý | Bị bỏ qua im lặng (B6) | ⚠ |
| H7 | Đề xuất đang chờ giữ nguyên, đề xuất mới theo cấu hình mới | Đề xuất chờ giữ nguyên | ✔ |
| H8 | Số tối thiểu thật là bao nhiêu thì Adh đặt `minCustomers` theo | Chưa biết (?) | ? Cần thử tay 1 nhóm thật |

---

## 3. Tổng hợp chỗ mã cần sửa (theo mức nguy hiểm)

| # | Vấn đề | Mức | Nhánh |
|---|---|---|---|
| 1 | Khách dư dưới ngưỡng kẹt vô hạn, không báo | Cao | A2, A3, A6, A10, B3 |
| 2 | Thông lượng tạo nhóm quá thấp cho 40 trở lên | Cao | A7, A8, H1 |
| 3 | Lập kế hoạch coi nhóm không có mã Zalo là "còn chỗ", bộ nối thì không | Cao (im lặng kẹt khách) | E4 |
| 4 | Ba lỗi riêng người liền nhau làm cầu dao nhảy toàn hệ thống | Cao khi chạy thật | C2, C3, F5b |
| 5 | Lỗi thuộc về nhóm (giải tán, bị đá) làm cầu dao nhảy | Trung bình | E2, E3 |
| 6 | Mất sự kiện kết bạn khi cầu nối khởi động lại | Cao (mất khách) | D3a, D3c |
| 7 | Khách không rõ tỉnh: lập kế hoạch bỏ qua nhưng bộ nối xếp vào nhóm bất kỳ | Trung bình | B6, H6 |
| 8 | Từ chối cấp tỉnh rồi đề xuất cấp vùng chứa lại đúng khách đó | Thấp đến trung bình | G5a |
| 9 | `eligibleMembers` thiếu kiểm trạng thái thêm vào nhóm | Thấp | G2 |
| 10 | Số khách trong nhóm lệch dần (rời nhóm, mẹ thêm tay) | Trung bình | C10, E10 |
| 11 | Tên nhóm đánh số theo số nhóm hiện có nên có thể lặp | Thấp | E7 |

---

## 4. Đề xuất thuật toán và logic

Nguyên tắc giữ nguyên (không đổi, theo CLAUDE.md mục 3): tắt mặc định, nấc xác nhận, trần cứng, khoảng nghỉ cố định, không ngẫu nhiên hóa, không né phát hiện, một tài khoản Zalo, cầu dao dùng chung, không tự thử lại khi không rõ kết quả, không xóa số điện thoại. Mọi đề xuất dưới đây **không nâng trần gọi Zalo mỗi ngày**; chúng làm mỗi lần gọi có nhiều giá trị hơn.

### 4.1 Nhóm lớn hơn, thêm dần (xử lý A7, A8)

Vấn đề: sức chứa 5 khách buộc hệ thống phải tạo nhiều nhóm; việc tạo nhóm là hành động Zalo dễ coi là bất thường nhất và đang bị khóa ở 1 nhóm mỗi ngày.

Đề xuất:
- Thêm hai cấu hình: `targetCustomers` (số khách mỗi nhóm, **mặc định đề xuất 30**, chỉnh được ở `/zalo`, trần theo giới hạn thật của Zalo sau khi kiểm) và giữ `maxMembers = 5` là số người **ngay lúc tạo** (cầu nối đang giới hạn 5; tạo nhóm với số người nhỏ cũng đỡ gây chú ý).
- `record()` ghi `target = targetCustomers` thay vì số 5 cứng.
- Sau khi tạo, phần còn lại của khách cùng khu vực **vào bằng `addNext` sẵn có** (đã có hạn mức 5 người/giờ, nghỉ 2 phút, cầu dao). Không thêm đường gọi Zalo mới.
- Chỉ tạo nhóm mới khi **mọi nhóm cùng khu vực đã đầy hoặc tạm dừng** và còn ít nhất `minCustomers` khách chờ.

Hiệu quả (tính từ các số trong mục 1, giả định mỗi ngày làm hết hạn mức):

| Số khách chờ | Hiện tại (5 khách/nhóm, 1 nhóm/ngày) | Đề xuất (nhóm 30, 1 nhóm/ngày, thêm 50 người/ngày) |
|---|---|---|
| 40 | 8 ngày | 2 ngày (2 nhóm, thêm người 1 ngày) |
| 100 | 20 ngày | 4 ngày (4 nhóm) |
| 500 | 100 ngày | 17 nhóm, nhưng thêm người 10 ngày là giới hạn thật |

Rủi ro và chưa chắc:
- **Giới hạn số thành viên của một nhóm Zalo** thường (có tài liệu nói 200, cần xác nhận) chưa kiểm với tài khoản này (?).
- **Quyền riêng tư**: nhóm 30 người nghĩa là 30 khách thấy tên nhau (và có thể thấy số nếu cài đặt cho phép). Nhóm 5 người ít lộ hơn. Cần chủ dự án quyết và cập nhật nội dung Tin 1 nếu cần ("nhóm khách hàng" đã có trong tin, nhưng không nói số người).
- **Chất lượng nhóm**: nhóm đông thì mẹ khó chăm hơn; mục tiêu "cùng khu vực" vẫn đúng.
- **Khoảng cách tới ý ban đầu 5 khách/nhóm**: CLAUDE.md và thiết kế cũ ghi "khoảng 5 khách mỗi nhóm" (thiết kế 21/11). Đây là thay đổi nghiệp vụ, cần chủ dự án duyệt.

Phương án trung gian nếu chủ dự án muốn giữ nhóm nhỏ: nâng trần tạo nhóm lên 3/ngày (đã có sẵn) nhưng 500 khách vẫn 34 ngày; chỉ giảm nhẹ vấn đề.

### 4.2 Chọn phạm vi nhóm bằng "gom từ dưới lên" thay vì "nhiều khách nhất ở cấp đầu đủ ngưỡng"

Vấn đề: hiện chọn cấp nhỏ nhất đủ 3 khách rồi lấy khu vực đông nhất của cấp đó. Với 40 khách rải đều 10 tỉnh (4 mỗi tỉnh) hệ thống tạo 10 nhóm tỉnh nhỏ thay vì 1 đến 2 nhóm vùng lớn; mất 10 lần tạo nhóm.

Đề xuất (hàm thuần, thay `GroupPlanner.plan`, dễ test):
```
đầu vào: khách chờ (đã có tỉnh), sức chứa C (targetCustomers), ngưỡng nhóm tỉnh Tp (ví dụ 8), ngưỡng nhóm vùng Tv (ví dụ 4), ngưỡng miền Tm (ví dụ 3), tuổi
1. đếm khách theo tỉnh, vùng, miền
2. với mỗi tỉnh có >= Tp khách chờ: ứng viên "nhóm tỉnh"
3. nếu không có ứng viên tỉnh: với mỗi vùng có >= Tv khách chờ: ứng viên "nhóm vùng"
4. nếu không có: với mỗi miền có >= Tm khách chờ: ứng viên "nhóm miền"
5. nếu vẫn không có: không đề xuất (khách còn lại đi theo luật tuổi chờ ở 4.3)
6. trong các ứng viên cùng cấp, xếp theo: (số khách chờ lâu nhất đã chờ bao lâu) giảm dần, rồi số khách chờ giảm dần, rồi mã khu vực
7. lấy tối đa maxMembers (5) khách kết bạn sớm nhất của khu vực làm người tạo
```
Điểm khác: ngưỡng nhóm tỉnh cao hơn nhóm vùng (nhóm tỉnh chỉ đáng khi đủ khách để có nhóm đông đủ cùng tỉnh), ưu tiên khu vực có khách chờ lâu nhất thay vì đông nhất (chống đói).

Rủi ro: ngưỡng Tp, Tv, Tm là giả định, cần chủ dự án chốt (câu hỏi 3 ở mục 6). Nhóm vùng có thể gom khách khá xa nhau (ví dụ Tây Bắc); có thể không mong muốn, nhưng khớp ý "cùng vùng" của quyết định D11.

### 4.3 Hàng chờ công bằng, "tuổi chờ", xử lý khách dư (xử lý A2, A3, A6, A10, B3)

Đề xuất:
- Lưu cho mỗi khách đang chờ nhóm "đã chờ từ lúc nào" (đã có `friended_at`, không cần cột mới).
- Quy tắc nâng cấp theo tuổi (số ngày chỉnh được, mặc định đề xuất):
  - Chờ ≥ 3 ngày: khách tính vào bộ đếm của cấp **rộng hơn** (tỉnh lên vùng, vùng lên miền) để dễ đủ ngưỡng.
  - Chờ ≥ 7 ngày: cho phép tạo nhóm với **ít nhất 2 khách** (ngưỡng tối thiểu của hệ thống), hoặc xếp vào nhóm cùng miền còn chỗ gần nhất kể cả khác vùng (đã có `rank` mức 2), hoặc nhóm "chung" nếu có.
  - Chờ ≥ 14 ngày: đưa vào danh sách "cần mẹ quyết" trên Tổng quan kèm lý do (không đủ khách ở khu vực, đề nghị tạo tay hoặc gửi link nhóm gần nhất). Không tự thêm.
- Khách chờ quá 48 giờ phải xuất hiện thành con số trên Tổng quan: "N khách chờ nhóm, người chờ lâu nhất M ngày".
- Hàng chờ thêm người theo thứ tự kết bạn (đã có `ORDER BY friended_at`), không bị tắc đầu hàng vì `NO_GROUP` được bỏ qua (đã đúng).

Rủi ro: nâng cấp theo tuổi làm khách bị xếp vào nhóm xa hơn mong muốn ban đầu. Giảm bằng cách luôn hiển thị lý do ở đề xuất ("gộp lên vùng vì có N khách chờ quá 3 ngày") và để nấc Xác nhận duyệt.

### 4.4 Đồng nhất định nghĩa "nhóm còn chỗ" (xử lý E4, G2)

- Một hàm duy nhất `GroupService.availableForAutoAdd(province)` dùng cho CẢ `waitingCustomers` và `addNext`: nhóm đang mở, có link, **có mã nhóm Zalo**, chưa đầy.
- Nhóm đang mở nhưng không có mã nhóm Zalo thì **không** được tính là còn chỗ ở bộ lập kế hoạch; trang Zalo hiện cảnh báo "nhóm X có link nhưng chưa gán mã Zalo, bộ nối không thêm được".
- `eligibleMembers` thêm điều kiện `(zalo_add_state IS NULL OR zalo_add_state IN ('WAITING_APPROVAL','QUEUED'))` giống `waitingCustomers`.
- Đề xuất kiểm bất biến bằng test thuộc tính: với mọi khách mà `waitingCustomers` loại vì "có nhóm còn chỗ", thì `addNext` phải tìm được nhóm.

### 4.5 Đồng bộ khách đã đồng ý kết bạn, khỏi lỡ sự kiện (xử lý D3)

Quy tắc hiện hành (CLAUDE.md mục 3): bộ nối "KHÔNG thêm nhận lời mời, đọc danh sách bạn". Có ba cách, từ ít đụng quy tắc nhất:

| Cách | Mô tả | Đụng quy tắc? | Điểm yếu |
|---|---|---|---|
| a | **Ghi bền sự kiện** của cầu nối xuống đĩa (đã mã hóa, chỉ mã Zalo, không số điện thoại), giữ 7 ngày, xóa sau khi Adh xác nhận. Cầu nối khởi động lại không mất sự kiện đã nhận | Không | Không cứu được sự kiện lỡ **trong lúc** cầu nối chết hoặc listener đứt kết nối |
| b | Suy ra "đã là bạn" khi khách **nhắn riêng** tới (đường đọc tin riêng đã có, D19) hoặc khi `sendMessage`/`findUser` cho thấy trạng thái bạn | Không | Chỉ bắt được khách nhắn lại; `findUser` chưa chắc trả trạng thái bạn (?) |
| c | Thêm một việc nhỏ ở cầu nối: **đọc danh sách bạn một lần mỗi ngày** (một lần gọi chậm, trong khung giờ, đối chiếu mã Zalo với `zalo_target_uid` của lời mời `SENT` chưa có sự kiện); chỉ trả mã Zalo trùng, không trả cả danh sách | **Có**, cần chủ dự án duyệt đổi quy tắc, kèm test khóa `no-send.test.mjs` | Thêm một thao tác gọi Zalo; chưa biết hàm của `zca-js` có tốn hạn mức hay bị coi bất thường không (?) |

Đề xuất: làm (a) trước vì không đổi quy tắc và đã giảm đa số mất sự kiện (khởi động lại do triển khai mỗi 10 phút của cron deploy là nguyên nhân hay gặp nhất); làm (c) nếu chủ dự án đồng ý. Dù chọn cách nào, mỗi lần đối chiếu **chỉ đánh dấu `FRIENDED`, không gọi gì thêm** và tuân theo trần.

Test cần có khi làm: khởi động lại giữa lúc có sự kiện; sự kiện trùng; đối chiếu với khách đã từ chối (không đưa lại); không làm tăng số lần gọi Zalo ngoài mức đã công bố.

Lưu ý riêng: luồng khác đang làm đồng bộ danh sách bạn. Tài liệu này không quyết thay; yêu cầu chung là mọi cách đồng bộ phải qua đủ chốt (khung giờ, cầu dao, chế độ thử, không ngẫu nhiên hóa).

### 4.6 Phân loại lỗi tốt hơn, giảm số lần cầu dao nhảy oan (xử lý C2, E2, F5b)

- **Lỗi của nhóm** (nhóm không tồn tại, mình không còn là thành viên, mình không đủ quyền): thêm loại `GROUP_ERROR`. Hành động: đặt nhóm đó `PAUSED` và ghi nhật ký, trả khách về hàng đợi, **không** ngắt cầu dao, nhắc mẹ. Cần điền mã lỗi thật sau khi thử (cùng cách `BRIDGE_PERSON_ERROR_CODES` đang chờ dữ liệu thật).
- **Lỗi riêng người** (đã rời, hủy kết bạn, đã ở trong nhóm, tắt cho phép thêm): đếm theo **cửa sổ trượt** (ví dụ 5 lần lỗi riêng người trong 10 lần thêm gần nhất ở **nhiều nhóm hoặc nhiều khách khác nhau**) thay vì "3 lần liền", và dừng cầu dao chỉ khi tỷ lệ cao. Khách lỗi riêng người đi đường Tin 2 (link) như hiện nay.
- Từ chối thật sự về hành vi mới: không tự thử lại. Giữ nguyên.

Rủi ro: nới ngưỡng cầu dao có thể làm Zalo thấy nhiều lỗi liên tiếp hơn; vì thế cửa sổ trượt phải **bao gồm** cả điều kiện "đã có mã lỗi nghi bị hạn chế thì dừng ngay" (đã có ở `RESTRICTED`).

### 4.7 Khách không rõ tỉnh

- Bộ nối thêm người (`addNext`) **chỉ xếp khách không rõ tỉnh vào nhóm "Chung"**, không vào nhóm có khu vực (sửa `rank(g, null)` cho nhóm có khu vực thành âm).
- Bộ lập kế hoạch hiện bỏ qua khách này đúng; thêm vào trang "Khách chờ" một mục "chưa rõ khu vực" với hành động "chọn tỉnh tay" (hoặc nhắc hỏi tỉnh qua trả lời của trợ lý AI, ngoài phạm vi tài liệu này).

### 4.8 Đồng bộ thông tin về nhóm

- Thêm "đếm lại tự động" sẵn có thông qua `GET /groups` của cầu nối (chỉ đọc, có sẵn, trả danh sách nhóm). Nếu API trả số thành viên thì so với số hệ thống, ghi lệch lớn thành cảnh báo. Nếu không trả (chưa kiểm), giữ cách đếm tay.
- Tên nhóm đánh số theo `max(số thứ tự đã dùng) + 1` đọc từ tên (hoặc lưu `seq` riêng) thay vì đếm số nhóm hiện có.

### 4.9 Từ chối đề xuất

- Khi mẹ từ chối, lưu **tập khách** trong đề xuất đó (đã có `zalo_group_proposal_member`); trong 24 giờ không đề xuất lại bất kỳ nhóm nào chứa quá nửa số khách đó, không chỉ cùng mã khu vực.

---

## 5. Danh sách test nên viết (chưa viết)

Chia theo tầng để chạy nhanh, đúng tinh thần "hàm thuần trước, tích hợp sau".

**Hàm thuần `GroupPlanner` (thêm vào `GroupPlannerTest`):**
- `bonMuoiKhachRaiDeuMuoiTinh` (A7, B1): 40 khách, 10 tỉnh mỗi tỉnh 4, cùng vùng → kỳ vọng thuật toán mới ra 1 đề xuất cấp vùng, không phải 10 đề xuất cấp tỉnh.
- `namTramKhachThiSoNhomCanTinhDuoc` (A8): hàm tính số nhóm tối thiểu và số ngày với `targetCustomers`.
- `phanDuHaiNguoiDuocNangCapTheoTuoi` (A10): 2 khách cùng tỉnh chờ 4 ngày → gộp lên vùng; chờ 8 ngày → nhóm 2 người.
- `tuChoiCapTinhKhongDeXuatLaiCapVungChuaCungKhach` (G5a).
- `hoaThiNguoiChoLauHonDuocUuTien`, `chiTinhMoiDuNguongTinhThiTaoNhomTinh`.
- Thuộc tính: với mọi đầu vào, kế hoạch không bao giờ trộn khác miền, không đưa quá `maxMembers`, và mọi khách trong kế hoạch có tỉnh hợp lệ.

**Tích hợp `ZaloGroupCreationTest`:**
- `nhomCoLinkKhongCoMaZaloKhongDuocTinhLaConCho` (E4): có nhóm link, không `zalo_group_id` → bộ lập kế hoạch vẫn đề xuất (sau khi sửa 4.4).
- `khachLoiRiengNguoiKhongLotVaoDeXuat` (G2).
- `nhomVuaTaoCoMucTieuLonThiKhachConLaiVaoQuaThemNguoi` (4.1).
- `khongTaoNhomMoiKhiNhomCungKhuVucConCho`, `taoNhomMoiKhiMoiNhomDay`.
- `nhomTamDungKhongCoLinkKhongLamNhanDoiSoNhom` (E5).

**Tích hợp `ZaloAutoTest` (bộ thêm người):**
- `baLoiRiengNguoiTuBaNhomKhacNhauKhongNganCauDao` và `baLoiRiengNguoiCungNhomThiDung` (F5b, sau khi sửa 4.6).
- `loiCuaNhomTamDungNhomKhongNganCauDao` (E2, E3).
- `khachKhongRoTinhChiVaoNhomChung` (B6, 4.7).

**Cầu nối (`zalo-bridge/test`):**
- `suKienGhiBenKhongMatKhiKhoiDongLai` (D3a, nếu làm 4.5a).
- `listenerDutKhongMatSuKienDaNhan`.
- Bảo đảm test khóa `no-send.test.mjs` vẫn đếm đúng số chỗ gọi hàm gửi/kết bạn/đọc.

**Ca mô phỏng nhiều ngày (kịch bản, dùng `ZaloTestClock`):**
- Mô phỏng 500 khách, 40 tỉnh, 10 ngày; đo: số nhóm tạo mỗi ngày không vượt trần, số người thêm mỗi ngày không vượt hạn mức, không khách nào chờ quá hạn tuổi trừ khi bị chặn bởi hạn mức, và không bao giờ khách khác miền cùng nhóm.
- Mô phỏng khách đến lẻ tẻ cả tháng, kiểm tra khách chờ lâu nhất không vượt 7 ngày.

---

## 6. Cần chủ dự án quyết

1. **Mục tiêu khách mỗi nhóm:** giữ 5 (an toàn quyền riêng tư, chậm) hay nâng (ví dụ 30, nhanh)? Nếu nâng, bao nhiêu? (4.1)
2. **Có cho nhóm lớn thấy nhau không:** cần đổi nội dung Tin 1 không? (4.1)
3. **Ngưỡng:** nhóm tỉnh cần bao nhiêu khách, nhóm vùng, nhóm miền (đề xuất 8, 4, 3)? (4.2)
4. **Hạn chờ:** sau bao nhiêu ngày thì nâng cấp khu vực, tạo nhóm 2 người, báo mẹ (đề xuất 3, 7, 14)? (4.3)
5. **Đồng bộ danh sách bạn:** duyệt đổi quy tắc "bộ nối không đọc danh sách bạn" cho cách (c), hay chỉ làm (a) và (b)? (4.5)
6. **Khách không rõ tỉnh:** chỉ vào nhóm chung, hay vẫn cho vào nhóm bất kỳ như hiện nay? (4.7)
7. **Cầu dao:** đổi "3 lỗi riêng người liền nhau" sang cửa sổ trượt? (4.6)

## 7. Việc phải thử với Zalo thật trước khi tin vào bất kỳ kết luận "?" ở trên

Cùng đợt thử tay 1 nhóm thật với 2 số của chủ dự án (CLAUDE.md mục 7.1):
- Số thành viên tối thiểu khi tạo nhóm; số thành viên tối đa của nhóm thường.
- Có thêm được người **chưa phải bạn** vào nhóm không; lỗi trả về khi người đó đã hủy kết bạn, đã chặn, đã ở trong nhóm, tắt cho phép thêm vào nhóm.
- Lỗi trả về khi nhóm đã giải tán hoặc tài khoản phụ bị đá.
- `createGroup` trả lỗi cả lệnh hay từng người (`errorMembers`) khi một người không hợp lệ.
- Sự kiện kết bạn (`ADD`) có `isSelf = true` hay không khi **khách chấp nhận lời mời do tài khoản phụ gửi**. Bộ nối hiện bỏ mọi sự kiện `isSelf` (`zca-adapter.mjs`), nên nếu Zalo đánh dấu như vậy thì **không có sự kiện nào** tới Adh. Đây là điều cần thử đầu tiên với luồng lời mời kết bạn tự động mới (PR #100).
- `GET /groups` có trả số thành viên không.
