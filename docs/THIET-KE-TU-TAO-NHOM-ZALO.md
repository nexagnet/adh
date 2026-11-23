# Thiết kế: tự tạo nhóm Zalo theo khu vực (đã duyệt 23/11/2026; đã có mã, chưa chạy với Zalo thật)

Viết 23/11/2026. Bạn đã đồng ý cho hệ thống **tự tạo nhóm Zalo**, phải hợp với cách chia khu vực và số lượng người, và tôi tự chọn thuật toán tốt nhất. Dưới đây là thuật toán, rủi ro và những điều chưa kiểm với Zalo thật. **Cập nhật 23/11/2026:** bạn đã duyệt; mã đã viết (bộ nối `POST /groups`, phía Adh `ZaloGroupCreationService`), chưa chạy với Zalo thật.

## 1. Hiện trạng (đã có trong Adh)

- Nhóm Zalo được ghi trong Adh với **phạm vi** (miền, vùng hoặc tỉnh) và mục tiêu khoảng 5 khách mỗi nhóm (mỗi nhóm thường khoảng 7 tài khoản gồm mẹ và tài khoản của hệ thống).
- Khách đã kết bạn mà **chưa có nhóm hợp khu vực** đang hiện thành việc "mở nhóm mới ở khu vực đó" cho mẹ (số `waitingGroup` trên Tổng quan). Ưu tiên xếp khách: cùng tỉnh, rồi cùng vùng, rồi cùng miền, rồi nhóm chung.
- Nhóm tự thêm và nhóm của link mời là **một** nhóm (cùng mã nhóm Zalo). Bộ nối hiện chỉ biết **thêm một người vào nhóm có sẵn** và liệt kê nhóm.

Phần mới: thay việc "mẹ tạo nhóm tay rồi dán link" bằng "hệ thống tạo nhóm, lấy link mời và ghi vào Adh".

## 2. Thuật toán chọn khi nào tạo và tạo ở cấp nào

Mỗi lần chạy (một lần mỗi 10 phút, chỉ trong 08:00 đến 20:00):

1. Lấy các khách **đã kết bạn nhưng chưa có nhóm hợp khu vực**, gom theo tỉnh.
2. Nếu hệ thống đã có nhóm đang nhận khách và còn chỗ ở đúng phạm vi đó (tỉnh, vùng hoặc miền) thì **không tạo**, để khách được thêm vào nhóm đó như bình thường.
3. Tìm phạm vi **nhỏ nhất** còn đủ người chờ, kiểm từ nhỏ đến lớn: tỉnh, rồi vùng (nhiều tỉnh cạnh nhau), rồi miền. Phạm vi nào có **từ 3 khách chờ trở lên** (ngưỡng chỉnh được) thì chọn phạm vi đó. Tỉnh nhỏ, ít khách thì tự gộp lên vùng: không tạo nhóm 1 đến 2 người.
4. Nhóm mới lấy tối đa 5 khách đang chờ đầu tiên (người kết bạn sớm nhất trước). Khách còn lại vào nhóm tiếp theo.
5. Tên nhóm: `Pin ADH - <Khu vực> - <số thứ tự hai chữ số>`, ví dụ `Pin ADH - Miền Nam - 01`; số thứ tự là số nhóm đã có ở phạm vi đó cộng một. (Đúng với đề xuất tên nhóm trong CLAUDE.md; bạn chưa chốt quy ước nên đổi được bằng một dòng cấu hình.)
6. Sau khi tạo: bật link mời của nhóm, ghi **mã nhóm và link** vào Adh (nhóm mới ở trạng thái đang nhận khách), rồi khách chờ được thêm vào bằng đúng đường thêm người hiện có. Link mời vẫn để khách tự bấm khi việc thêm trực tiếp lỗi, như hiện nay.

**Vì sao thuật toán này:** nhỏ nhất trước giữ nhóm đúng "cùng tỉnh" như thiết kế cũ; ngưỡng 3 tránh nhóm vắng; gộp vùng/miền tránh sinh hàng chục nhóm nhỏ; mỗi lần chỉ xét việc tạo, không tạo hàng loạt.

## 3. Hộp an toàn (cùng tinh thần D18, không né phát hiện)

- **Tắt mặc định.** Có ba nấc trên trang Zalo: Tắt, **Xác nhận** (mẹ bấm duyệt từng nhóm; mặc định khi bật), Tự động. Nên chạy Xác nhận cho vài nhóm đầu trước khi cho Tự động.
- Trần cứng: tối đa **1 nhóm mới mỗi ngày** (chỉnh được đến 3), khoảng nghỉ cố định giữa hai lần tạo, chỉ 08:00 đến 20:00, **không ngẫu nhiên hóa thời gian**.
- **Cầu dao dùng chung** với việc gửi tin: lỗi lạ hoặc Zalo cảnh báo thì dừng cả tạo nhóm lẫn gửi tin. Tạo nhóm lỗi không rõ kết quả thì đánh dấu thất bại và **không tự thử lại**, báo mẹ kiểm tra.
- Tên nhóm cố định theo mẫu (không ngẫu nhiên), không ảnh đại diện giả, không nhân bản tài khoản.
- Chỉ tạo nhóm cho khách **đã kết bạn và đã đồng ý** vào nhóm; không bao giờ kéo người lạ.
- Không ghi mã nhóm, tên khách hay link vào log công khai, giống các phần Zalo khác.

## 4. Mẹ làm trưởng nhóm hay phó nhóm

Tôi đề xuất: **tài khoản của hệ thống là người tạo (trưởng nhóm)** vì nó phải tạo nhóm, bật link và thêm người; **mẹ được thêm làm phó nhóm** để quản lý nhóm trên Zalo thật. Đổi ngược lại (đưa quyền trưởng nhóm cho mẹ rồi để tài khoản hệ thống làm phó) cũng làm được nhưng thêm một bước dễ lỗi, nên tôi không làm bước đầu. Cần bạn cho biết mã Zalo của mẹ (đặt bằng biến môi trường trên máy chủ, không ghi vào repo).

## 5. Rủi ro (đã gộp một tài khoản, nên rủi ro cao hơn)

1. **Tài khoản bị Zalo khóa** là rủi ro lớn nhất: giờ mất cả mời, thêm nhóm, tạo nhóm và (sau này) trợ lý. Tạo nhóm là hành động mà Zalo có thể coi bất thường nếu làm nhiều. Vì vậy trần mặc định thấp (1 nhóm một ngày), có nấc Xác nhận, và cầu dao.
2. **Nhóm có thể không tạo được ở thư viện không chính thức** (xem mục 6). Thất bại thì hệ thống rơi về cách cũ: mẹ tạo nhóm tay và dán link.
3. **Khách chưa chắc đồng ý vào nhóm lúc được thêm trực tiếp**: giữ đường link mời làm dự phòng, như hiện nay.
4. **Tạo nhầm phạm vi** (ví dụ gộp khách xa nhau): giảm bằng ngưỡng và thứ tự nhỏ đến lớn; mẹ thấy được lý do ("tạo nhóm vì có N khách chờ ở <khu vực>") và có nấc Xác nhận.

## 6. Những điều chưa kiểm với Zalo thật (thư viện `zca-js` 2.3.0)

Thư viện có hàm `createGroup` (tên nhóm, danh sách thành viên), `enableGroupLink` (lấy link), `addGroupDeputy` (thêm phó nhóm), `changeGroupName`. **Chưa chạy thử hàm nào.** Chưa biết:

- Số thành viên tối thiểu để tạo nhóm (có thể phải có ít nhất hai người ngoài chủ nhóm) và có phải **đã là bạn** mới thêm được ngay lúc tạo hay không.
- Giới hạn độ dài tên nhóm, ký tự `-` có được không.
- Có giới hạn số nhóm tạo mỗi ngày hoặc mỗi tuần và mã lỗi khi vượt.
- Thêm mẹ làm phó nhóm có cần mẹ là bạn của tài khoản hệ thống không.
- Link mời có hạn dùng không, có tự tạo lại không.

Vì vậy bước đầu tiên khi có code là **chạy thử tay với 1 nhóm thật có 2 số của bạn** (giống Chiến dịch thử) rồi mới bật Xác nhận cho khách thật.

## 7. Việc xin bạn quyết

1. Duyệt thuật toán mục 2 (ngưỡng 3 khách, nhỏ nhất trước, tối đa 5 khách mỗi nhóm đầu)? (Có / Sửa / Không)
2. Mặc định nấc **Xác nhận** (mẹ bấm duyệt từng nhóm), trần 1 nhóm mỗi ngày: đồng ý?
3. Tài khoản hệ thống là trưởng nhóm, mẹ là phó nhóm (mục 4): đồng ý? Và bạn có cho tôi mã Zalo của mẹ qua biến môi trường được không?
4. Tên nhóm theo mẫu `Pin ADH - <Khu vực> - 01`: chốt luôn mẫu này chứ?
