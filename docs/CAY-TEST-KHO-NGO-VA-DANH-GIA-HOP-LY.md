# Cây test "khó ngờ nhất" cho việc xếp khách vào nhóm Zalo, kèm đánh giá TÍNH HỢP LÝ

Ngày 24/11/2026. Yêu cầu của chủ dự án: viết thêm một cây test mới, càng nhiều nhánh càng tốt, ưu tiên các tình huống khó ngờ nhất (bất thường, hai quy tắc đụng nhau, nhiều điều kiện cùng lúc), và **đánh giá tính hợp lý** của hành vi (có hợp với mục tiêu kinh doanh và an toàn không), không chỉ kiểm đúng sai theo đặc tả.

Tài liệu này thay phần đề xuất cũ ở `docs/CAY-TEST-VA-DE-XUAT-THUAT-TOAN-TAO-NHOM.md` (bản cũ vẫn giữ làm lịch sử; chỗ "sức chứa 30" của nó đã bị quyết định ngày 24/11 thay thế, xem mục 1).

Mỗi nhánh dưới đây là một test tự động chạy trong CI. Cột "Mức" là đánh giá của người viết: **hợp lý** (làm đúng điều ta muốn, không cần đổi), **chấp nhận được** (hợp lý theo quy tắc nhưng có đánh đổi, ghi rõ), **chưa hợp lý** (có lựa chọn tốt hơn, ghi đề xuất). Không nhánh nào "sai theo đặc tả"; những lỗi sai thật đã tìm ra ở lượt trước đã sửa ở các PR #104 đến #109 (mục 5).

## 1. Thuật toán hiện hành (để đọc các nhánh bên dưới)

- Đếm theo số **khách** (không tính tài khoản phụ và mẹ phó nhóm; nhóm thật khoảng 7 thành viên). Mục tiêu 5 khách mỗi nhóm; trần mềm 8 khách cho khách dư lẻ (chỉnh ở trang Zalo).
- Khách đồng ý kết bạn: (1) nhóm cùng tỉnh còn dưới 5 thì vào; (2) không có thì nhóm cùng vùng, rồi cùng miền, còn dưới 5; (3) không có thì chờ ở hàng chờ khu vực.
- Mỗi nhịp 10 phút: tỉnh có từ 3 khách chờ (chỉnh 2 đến 3) thì tạo nhóm mới cho tối đa 5 khách đến sớm nhất; tỉnh chưa đủ thì gộp lên vùng rồi miền. **Không giới hạn số nhóm tạo mỗi ngày**; chỉ có khung 08:00 đến 20:00, khoảng nghỉ cố định giữa hai lần tạo, và số khách được nhắn mỗi ngày.
- Khách dư lẻ (khu vực chờ chưa đủ ngưỡng): mới đồng ý chỉ vào nhóm cùng tỉnh còn dưới 8; chờ quá 3 ngày được gộp lên nhóm cùng vùng hoặc miền; chờ quá 7 ngày khu vực chỉ cần 2 khách là lập nhóm; chờ quá 14 ngày hiện cảnh báo cho mẹ. (3, 7, 14 ngày và 8 khách chỉnh ở trang Zalo.)
- Lỗi riêng một khách chỉ bỏ qua khách đó; 4 lần liền cùng một nhóm thì tạm dừng nhóm đó; 6 trong 8 lần gần nhất thì dừng cầu dao. Lỗi cả nhóm (cầu nối báo `GROUP_ERROR`) tạm dừng đúng nhóm đó, 3 lần liền thì dừng cầu dao. Lỗi lạ hoặc Zalo cảnh báo thì cầu dao dừng chờ người mở lại.
- Cứ 20 phút và mỗi khi cầu nối nối lại: đối chiếu danh sách bạn (chỉ đọc) để bắt khách lỡ sự kiện kết bạn.

## 2. Bộ lập kế hoạch (hàm thuần): `GroupPlannerScenarioTest`

| Mã | Tình huống | Hành vi hệ thống | Mức | Vì sao và đề xuất |
|---|---|---|---|---|
| PL-01 | 1000 khách cùng một tỉnh về một lúc | 200 nhóm, mỗi nhóm đủ 5; người kết bạn sớm nhất vào nhóm đầu; không ai dư | hợp lý | Đúng mục tiêu 5. Số nhóm thật bị chặn bởi khung giờ và khoảng nghỉ cố định, không phải bởi trần ngày |
| PL-02 | n khách (1 đến 40) một tỉnh, ngưỡng 3 | Lấy 5 mỗi lần; dư đúng 1 hoặc 2 khách khi n chia 5 dư 1 hoặc 2 (còn n dưới 3 thì dư hết) | chấp nhận được | Khách dư không bị bỏ: đi đường "khách dư lẻ" vào nhóm gần (CT-01). Có thể chia đều (6 khách thành 3 + 3) nhưng chủ dự án chốt "tối đa 5 khách đến sớm nhất" |
| PL-03 | 63 tỉnh, mỗi tỉnh đúng 1 khách | Gộp lên vùng rồi miền; sau khi hết kế hoạch mỗi miền còn dưới 3 khách | hợp lý | Không ai bị kẹt khi miền đã đủ người |
| PL-04 | 10 tỉnh x 3 khách trong cùng vùng | 10 nhóm 3 người, mỗi tỉnh một nhóm, không gộp thành 6 nhóm 5 người | chấp nhận được | Ưu tiên phạm vi nhỏ nhất (gần nhau, dễ trao đổi, giao hàng). Đánh đổi: nhiều nhóm nhỏ, nhưng nhóm sẽ đầy dần khi khách mới về |
| PL-05 | Hai tỉnh đều đủ, 10 và 12 khách | Chọn tỉnh nhiều khách hơn trước | hợp lý | Giảm hàng chờ nhanh nhất |
| PL-06 | Hai tỉnh bằng nhau | Chọn tỉnh có người kết bạn sớm hơn | hợp lý | Người chờ lâu hơn được phục vụ trước |
| PL-07 | Trộn ngẫu nhiên đầu vào 20 lần | Luôn cùng một kết quả | hợp lý | Kết quả ổn định, dễ giải thích cho mẹ |
| PL-08 | 8 khách kết bạn cùng một phút | Chọn theo mã lời mời nhỏ nhất | hợp lý | Cần một thứ tự phụ để không ngẫu nhiên |
| PL-09 | Khách không có giờ kết bạn lẫn khách có giờ | Khách không có giờ xếp sau cùng | hợp lý | Khách có giờ thật không bị chen |
| PL-10 | Hai tỉnh cùng vùng, mỗi tỉnh 2 khách | Một nhóm vùng 4 người | hợp lý | Cách duy nhất để không tạo nhóm 2 người |
| PL-11 | Tỉnh có 3 khách, vùng có 23 khách | Vẫn lập nhóm tỉnh 3 người trước | chấp nhận được | Nhất quán với PL-04. Nếu chủ dự án muốn nhóm lớn hơn, đổi ngưỡng tạo nhóm tỉnh từ 3 lên 4 hoặc 5 (cấu hình sẵn có) |
| PL-12 | Sáu khách sáu tỉnh khác miền | Không lập nhóm | hợp lý | Không bao giờ trộn khác miền |
| PL-13 | Tây Bắc, Đông Bắc, Đồng bằng sông Hồng mỗi nơi 1 khách | Nhóm miền Bắc 3 người | hợp lý | Cùng miền thì chấp nhận gộp |
| PL-14 | 100 khách chưa rõ tỉnh hoặc tỉnh lạ | Không lập nhóm, không ảnh hưởng ba khách hợp lệ cùng lúc | hợp lý | Không đoán khu vực. Khách chưa rõ tỉnh quá 2 ngày được báo cho mẹ |
| PL-15 | Mẹ từ chối nhóm tỉnh | Đề xuất kế tiếp là nhóm vùng, rồi miền, cùng những khách đó; từ chối cả ba thì thôi | chấp nhận được | Mẹ thấy lại cùng ba khách dưới tên rộng hơn, hơi lặp. Đề xuất: ghi chú lý do từ chối để mẹ chọn "từ chối luôn khách này" (chưa làm) |
| PL-16 | Khách chờ quá 7 ngày | Ngưỡng hạ xuống 2; 1 khách thì không bao giờ lập nhóm | hợp lý | Không tạo nhóm 1 người |
| PL-17 | Khu vực có khách chờ quá lâu và khu vực khác đông hơn | Khu vực chờ lâu được ưu tiên | hợp lý | Chống bỏ đói; vì mỗi nhịp chỉ tạo một nhóm nên khu vực đông vẫn được phục vụ ngay nhịp sau |
| PL-18 | 2 khách Cần Thơ chờ quá 7 ngày và 1 khách Sóc Trăng mới | Nhóm Cần Thơ 2 người; khách Sóc Trăng ở lại | **chưa hợp lý** | Một nhóm vùng 3 người tốt hơn. Đề xuất: khi đã hạ ngưỡng, nếu cấp vùng gom được nhiều người hơn thì chọn cấp vùng. Chưa làm vì hiếm gặp và khách Sóc Trăng sẽ vào nhóm gần (CT-01) |
| PL-19 | Mẹ đã từ chối đủ ba cấp, khách chờ 30 ngày | Không đề xuất gì | chấp nhận được | Tôn trọng quyết định của mẹ; cảnh báo chờ quá 14 ngày vẫn hiện để mẹ không quên |
| PL-20 | 150 bộ dữ liệu ngẫu nhiên (1 đến 120 khách, ngưỡng 2 đến 4) | Bất biến luôn đúng: mỗi nhóm 2 đến 5 người đúng ngưỡng; không ai vào hai nhóm; không ai nằm ngoài phạm vi nhóm; hết kế hoạch thì mỗi miền còn dưới ngưỡng | hợp lý | Khóa các tính chất chung, bắt được lỗi ở những góc ta không nghĩ tới |
| PL-21 | 20 000 khách chờ | Dưới 2 giây | hợp lý | Không là điểm nghẽn |
| PL-22 | Ngưỡng 2 so với 3 | Ngưỡng 2 lập nhóm 2 người, ngưỡng 3 thì không | hợp lý | Cấu hình có tác dụng |
| PL-23 | Tối đa 2 người mỗi nhóm | Mỗi nhóm đúng 2 người | hợp lý | Giới hạn nhóm được tôn trọng |
| PL-24 | Quá hạn, từ chối, chưa rõ tỉnh cùng lúc | Khu vực quá hạn đi trước; mẹ từ chối thì sang khu vực kế | hợp lý | Các quy tắc không đá nhau |

## 3. Mô phỏng nhiều ngày với cơ sở dữ liệu thật: `GroupSimulationTest`

Mỗi nhịp 10 phút trong khung 08:00 đến 20:00 gọi đúng các hàm mà bộ hẹn giờ gọi (đối chiếu bạn, thêm một người, xét tạo nhóm), với bộ nối giả.

| Mã | Tình huống | Hành vi hệ thống | Mức | Vì sao và đề xuất |
|---|---|---|---|---|
| CT-01 | 41 khách của 9 tỉnh, về dồn dập ba ngày đầu rồi rải rác tới ngày thứ 5 | Tạo 7 nhóm 3 đến 5 người; mọi khách vào một nhóm cùng miền sau 10 ngày; không nhóm nào quá 8; không ai bị thêm hai lần; không quá 50 người mỗi ngày | hợp lý | Không bỏ sót ai, không sai miền |
| CT-01a | (cùng mô phỏng) 9 khách Hà Nội ra một nhóm 8 người và một khách vào nhóm Hải Phòng | Không tạo nhóm Hà Nội thứ hai | chấp nhận được | Khi chỉ còn dưới 3 khách chờ thì khách đi vào nhóm gần thay vì lập nhóm nhỏ. Hà Nội 8 thành viên vẫn trong trần mềm |
| CT-01b | (cùng mô phỏng) khách TP.HCM đến sớm vào nhóm Đồng bằng sông Cửu Long còn chỗ, sau đó 7 khách TP.HCM khác lập nhóm riêng | Một khách TP.HCM nằm trong nhóm miền Tây | chấp nhận được | Đúng quy tắc "cùng vùng, rồi cùng miền" nhưng đi sớm hơn lúc nhóm TP.HCM còn chưa tồn tại. Đề xuất (chưa làm): giữ khách nhắn chờ 1 đến 2 ngày nếu tỉnh của họ đã có từ 2 khách chờ |
| CT-01c | (cùng mô phỏng) khách Lạng Sơn và Bắc Ninh | Vào nhóm Hải Phòng (cùng miền Bắc) | chấp nhận được | Không ai bị kẹt, nhưng nhóm Hải Phòng có thêm người ngoài tỉnh. Cách khác là chờ báo mẹ sau 14 ngày; chọn vào nhóm gần vì khách được tư vấn sớm hơn |
| CT-02 | 80 khách Hà Nội đồng ý cùng một lúc, có nhóm lớn còn chỗ | Ngày 1: đúng 50 người đầu (theo thứ tự kết bạn); ngày 2: 30 người còn lại | chấp nhận được | Test hạ hạn mức về 50/ngày và 5/giờ để kiểm cơ chế vượt trần sang ngày sau. **Đã xử lý 24/11/2026:** chủ dự án đồng ý nâng, mặc định thêm nhóm nay là 150/ngày và 15/giờ (trần cứng 300 và 30), nên 80 khách như vậy vào hết trong một ngày |
| CT-03 | Nhóm duy nhất báo lỗi cả nhóm (nhóm giải tán, mất quyền) | Nhóm bị tạm dừng, cầu dao không nhảy, khách quay về hàng chờ; vài phút sau bộ tạo nhóm lập nhóm mới cho 5 khách, khách thứ 6 chờ | hợp lý | Tự lành, không cần người; đúng yêu cầu tách lỗi người và lỗi nhóm |
| CT-04 | Cứ 3 khách thì 1 khách lỗi riêng người | 8 khách vào, 4 khách lỗi (đánh dấu để xử lý tay), cầu dao và nhóm không bị ảnh hưởng | hợp lý | Trước đây 3 lần liền là cầu dao nhảy dù chỉ vì khách xui; nay chỉ ảnh hưởng khi tỷ lệ lỗi cao thật |
| CT-05 | 10 khách đồng ý đúng lúc cầu nối chết (mất sự kiện) | Đối chiếu bạn bè bắt đủ 10 khách, chỉ hỏi mã của khách trong hệ thống, không hỏi người ngoài; sau đó 2 nhóm được lập | hợp lý | Sửa đúng lỗi "mất khách vĩnh viễn" tìm thấy ở lượt trước, không cần bộ đệm bền |
| CT-06 | Hai trong bốn khách từ chối khi đang chờ | Không lập nhóm (còn 2 khách < ngưỡng), không ai bị thêm; số điện thoại vẫn còn trong hệ thống | hợp lý | Khớp quy tắc không bao giờ tự xóa số, khách từ chối không bị nhắn nữa |
| CT-07 | Khách đồng ý lúc 22:30 | Không thêm tối đó và không thêm trước 07:00; thêm được từ 07:00 sáng hôm sau | hợp lý | Khung giờ gửi tin 08:00 đến 20:00 là của việc nhắn tin; việc thêm nhóm dùng khung 07:00 đến 22:00 của bộ nối. Ghi nhận để chủ dự án biết hai khung khác nhau |
| CT-08 | 3 khách chờ và một nhóm đã đủ 5; chạy "thêm người" trước hoặc "tạo nhóm" trước | Cả hai thứ tự đều lập đúng một nhóm 3 người, không ai bị dồn vào nhóm đầy | hợp lý | Hai bộ chạy chen nhau cho cùng kết quả (không đua nhau) |
| CT-09 | Cầu dao nhảy giữa chừng rồi mẹ mở lại | Không ai bị thêm hai lần; khách bị đánh dấu lỗi ở lần cầu dao nhảy không tự thêm lại | hợp lý | "Không rõ đã thêm chưa thì không thêm lại" |
| CT-10 | Cầu nối mất 6 nhịp rồi sống lại | Không mất khách, không tính hạn mức, thêm được ngay khi cầu nối sống lại | hợp lý | Cầu nối chập chờn không làm cạn hạn mức |
| CT-11 | Đề xuất 3 người, 1 người từ chối trước khi mẹ duyệt | Vẫn lập nhóm 2 người | chấp nhận được | Mẹ đã duyệt; còn 2 người vẫn hơn bỏ. Nếu chỉ còn 1 người thì đề xuất hết hạn, không tạo nhóm 1 người (CT-11b, hợp lý) |
| CT-12 | Một khách của đề xuất được thêm vào nhóm khác trước khi đề xuất chạy | Đề xuất chạy với những khách còn lại, không ai nằm hai nhóm | hợp lý | Hai đường không đạp lên nhau |

## 4. Cầu nối và quy tắc an toàn

| Mã | Tình huống | Hành vi | Mức | Ghi chú |
|---|---|---|---|---|
| BR-01 | Cầu nối khởi động lại sau nhiều giờ | Mã số thứ tự sự kiện mới luôn lớn hơn mã cũ Adh đã nhớ | hợp lý | Sinh ra từ thời gian nên không quay lại; test mới khóa |
| BR-02 | Đối chiếu bạn bè bị gọi dồn dập | Giữa hai lần đọc Zalo cách nhau tối thiểu 10 phút, tối đa 48 lần mỗi ngày, ngoài khung giờ từ chối | hợp lý | Test trong `bridge.test.mjs` |
| BR-03 | Adh hỏi 500 mã, danh sách bạn có thêm người ngoài | Chỉ trả những mã Adh hỏi mà là bạn | hợp lý | Không lộ bạn ngoài hệ thống; test khóa `getAllFriends` đúng một chỗ gọi |
| BR-04 | Mã lỗi lạ khi thêm người | Cầu nối dừng (thất bại kín) | hợp lý | Chỉ mã đã khai báo ở `BRIDGE_GROUP_ERROR_CODES` mới là lỗi nhóm; mã đó phải điền sau khi chạy thật |
| BR-05 | Lỗi riêng người xen kẽ thành công nhiều lần | Không làm gì cả | hợp lý | Khóa ở `ZaloAutoTest` và CT-04 |
| BR-06 | 6 trong 8 lần gần nhất là lỗi riêng người, lẫn nhiều nhóm | Cầu dao dừng | hợp lý | Nghi Zalo hạn chế việc thêm người |

## 5. Lỗi thật đã tìm ra và sửa trong đợt này

| Lỗi | Sửa ở |
|---|---|
| Có trần số nhóm tạo mỗi ngày trái với yêu cầu "chỉ giới hạn số khách nhắn" | PR #104 |
| Nhóm chỉ có link (chưa có mã Zalo) bị tính là còn chỗ nên khách chờ mãi; định nghĩa "đầy" khác nhau giữa hai nơi; tên nhóm trùng; sức chứa mặc định sai (5 khách, trần mềm 8) | PR #105 |
| Khách dư của tỉnh ít người không bao giờ có nhóm; khách chưa rõ tỉnh bị xếp nhầm miền; không có cảnh báo người chờ lâu | PR #106 và #108 |
| Ba lần lỗi riêng người liền nhau làm cả hệ thống dừng; lỗi cả nhóm không tách riêng | PR #107 |
| Mất sự kiện kết bạn khi cầu nối khởi động lại làm khách kẹt mãi ở "đã nhắn" | PR #109 |

## 6. Việc còn mở (cần chủ dự án quyết hoặc cần Zalo thật)

1. **(Đã chốt 24/11/2026: nâng lên 150/ngày và 15/giờ)** Trần số người được thêm vào nhóm mỗi ngày (trước là 50) và mỗi giờ (trước là 5) có giữ khi chủ dự án đã nói "chỉ giới hạn số khách nhắn tin mỗi ngày" không? Hiện giữ vì đây là việc gọi Zalo riêng và có rủi ro bị hạn chế; CT-02 cho thấy 80 khách đã đồng ý phải chờ 2 ngày.
2. Mã lỗi thật của Zalo khi nhóm giải tán hoặc mất quyền (điền `BRIDGE_GROUP_ERROR_CODES`) và mã lỗi riêng người (`BRIDGE_PERSON_ERROR_CODES`) chỉ biết sau khi chạy thật.
3. PL-18 và CT-01b là hai chỗ "chưa tối ưu" đã nêu cách cải thiện; chưa làm vì ít gặp và chưa chắc chủ dự án muốn.
4. Hàm `getAllFriends` của thư viện không chính thức chưa thử với tài khoản thật: cần thử xem thời gian trả lời và việc Zalo có coi là bất thường không (có khoảng nghỉ 10 phút và trần 48 lần mỗi ngày để giảm rủi ro).
