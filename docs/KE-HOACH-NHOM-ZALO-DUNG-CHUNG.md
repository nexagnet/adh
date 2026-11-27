# Kế hoạch triển khai nhóm Zalo dùng chung

Tài khoản phụ 1 thêm khách đã kết bạn với nó vào nhóm do tài khoản phụ 2 lập bằng đúng phiên Zalo của tài khoản 1. Nhóm vẫn có một bản ghi trong ADH và giữ nguyên chủ nhóm. Tài khoản 3, 4 và các tài khoản thêm sau dùng cùng cơ chế cấp quyền theo từng cặp nhóm và tài khoản.

Tài liệu viết lại kế hoạch đã gộp ở PR #185, dựa trên `main` tại `9415f5b`. Đây là tài liệu bàn giao cho Claude Code. **Độ phức tạp cao** vì việc chọn nhóm, giữ chỗ, gọi Zalo và ghi kết quả phải nhất quán qua hai hệ thống. Các bước dưới đây cần được duyệt trước khi triển khai. Chưa xác minh cách đọc thành viên và cấu hình sao lưu thực tế trên hai máy.

## 1. Phạm vi và dữ liệu phải bảo toàn

- Giữ ID nhóm, tài khoản sở hữu, mã nhóm gốc, link, khu vực, số đếm và lịch sử. Giữ nguyên `invitation.account_id`, `group_id`, `reserved_group_id` và `reserved_until` khi chạy migration, mô phỏng, tắt hoặc bật quyền dùng chung. Các thay đổi nghiệp vụ về sau chỉ phát sinh từ thao tác mới được xác nhận. Một lượt giữ chỗ cũ hết hạn theo đúng mốc đã lưu.
- Khách đã được gắn nhóm hoặc giữ chỗ còn hạn thì tiếp tục đi đúng nhóm đó. Nếu chưa được cấp quyền, sai phiên hoặc nhóm không còn nhận khách, hiện lý do và giữ nguyên nhóm cùng thời hạn. Chỉ khách chưa có nhóm hoặc chỗ đang hiệu lực mới được chọn nhóm khác.
- Tài khoản của lời mời quyết định bộ nối, mã khách và mã nhóm nhìn từ phiên đó. Tuyệt đối tránh dùng mã khách hay mã nhóm của phiên khác. Quy tắc liên hệ, chế độ thử, hạn mức, khoảng nghỉ và cầu dao hiện hành vẫn áp dụng. Tạo nhóm, quản lý chủ nhóm và đặt phó nhóm tiếp tục đi qua bộ nối chủ nhóm.
- Dùng chung tắt mặc định. Quy trình mới phải có công tắc cấp hệ thống và theo nhóm để dừng **cấp chỗ mới**. Cho phép thu hồi từng quyền. Mọi thao tác đang chờ xác minh vẫn tra cứu được.
- Mô phỏng dùng dữ liệu thử, không sửa dữ liệu nghiệp vụ và không gọi lệnh thay đổi trên Zalo. Thử một cặp khách–nhóm thật ở môi trường thử và triển khai production là các quyết định riêng. Giữ cấu hình Z216. Máy thứ hai có cầu nối ADH và dự án `legal-office`, dự án đó nằm ngoài phạm vi.

## 2. Xếp hạng rủi ro và thứ tự xử lý

P0 là rủi ro tới hạn có thể tác động sai khách hoặc sai nhóm. P1 là rủi ro cao có thể làm sai trạng thái, sai sức chứa hoặc gián đoạn vận hành. P2 là rủi ro trung bình ảnh hưởng khả năng theo dõi và giải tỏa hàng chờ. Mức dưới đây đánh giá hậu quả khi xảy ra, với khả năng là nhận định từ code và chưa đo trên production.

| Mã | Rủi ro | Căn cứ và khả năng | Thứ tự xử lý |
|---|---|---|---|
| **P0-01** | Thêm nhầm khách, nhầm nhóm hoặc dùng nhầm tài khoản. | Có khoảng trống trong kiểm nhóm đích của đường thử hiện tại. Rủi ro tăng khi có nhóm trùng tên và nhiều phiên. | **1**. Xác nhận danh tính nhóm, quyền và phạm vi thử trước khi có bất kỳ đường ghi mới. |
| **P1-01** | Zalo thêm thành công nhưng ADH không ghi nhận, hoặc ghi nhận hai lần. | Nhật ký đang có thể ghi kết quả trước giao dịch cập nhật lời mời. Lỗi có thể xuất hiện khi DB chập chờn hoặc app bị dừng. | **2**. Lưu ý định bền, hoàn tất kết quả và sức chứa trong cùng giao dịch, xử lý kết quả chưa rõ. |
| **P1-02** | Hai luồng cùng chiếm chỗ cuối, hoặc cùng một khách bị đếm hai lần. | Luồng tự thêm dùng khóa JVM, còn các đường giữ chỗ có khóa PostgreSQL. Thêm thành viên ngoài ADH cũng có thể làm số thực tế lệch. | **3**. Dùng một quy tắc cấp chỗ cho mọi đường ghi. Gắn với P1-01 trước khi bật tự thêm dùng chung. |
| **P1-03** | Một tài khoản mất quyền, toàn bộ nhóm hoặc các tài khoản còn lại bị dừng. | Xử lý lỗi nhóm hiện có thể tạm dừng nhóm. Phạm vi lỗi sẽ khác khi nhiều tài khoản cùng dùng một nhóm. | **4**. Khoanh lỗi đã xác nhận vào đúng cặp, giữ cơ chế an toàn hiện tại cho lỗi hệ thống. |
| **P1-04** | Quay ảnh app hoặc khôi phục DB làm mất dấu tác vụ Zalo đã chạy. | Lùi ảnh giữ nguyên schema. Khi nạp bản sao cũ, trạng thái trên Zalo có thể mới hơn DB. Mức sẵn sàng phục hồi thực tế chưa được kiểm tra. | **5**. Làm cơ chế dừng và đối chiếu sau khôi phục. Diễn tập trước lượt thử trên Zalo. |
| **P2-01** | Một lượt đọc giữ hàng chờ quá lâu, người vận hành không biết bị chặn ở đâu. | Đối chiếu lời mời có giới hạn một lượt thủ công theo tài khoản và có thể đợi mãi nếu tài khoản tạm ngưng. | **6**. Hiện nguyên nhân, tuổi hàng chờ và cho hủy phần chưa đọc. Có thể làm độc lập sau khi ổn định các bước trên. |

## 3. Mẫu code cần bám theo

| Mục | Mẫu trong repo và cách dùng |
|---|---|
| Đặt tên và chia trách nhiệm | [GroupService](../app/src/main/java/vn/shop/crm/invite/GroupService.java#L105) quản nhóm và sức chứa. Dùng kiểu tên `ZaloAccountRegistry` cho nguồn tài khoản và `ZaloAutoService` cho luồng thực thi. Giữ đường tra nhóm sở hữu riêng với đường tra nhóm được quyền. |
| Phân quyền và định tuyến | [SecurityConfig](../app/src/main/java/vn/shop/crm/security/SecurityConfig.java#L38) bảo vệ đường quản trị Zalo. Dùng kiểm tra OWNER, CSRF cho POST và [ZaloAccountRoutes](../app/src/main/java/vn/shop/crm/zalo/ZaloAccountRoutes.java) để chọn đúng bộ nối. [Registry](../app/src/main/java/vn/shop/crm/zalo/ZaloAccountRegistry.java#L51) lọc tài khoản đang hoạt động. |
| Lỗi và nhật ký | [ZaloAutoService](../app/src/main/java/vn/shop/crm/zalo/ZaloAutoService.java#L587) ghi trạng thái trước gọi, phân loại kết quả và kích hoạt cầu dao. Dùng mã ổn định và nội dung tiếng Việt có hành động rõ. Ghi ID tác vụ, nhóm ADH, tài khoản thực hiện, trạng thái, mã lỗi và thời gian. Thông tin khách, mã Zalo thật và bí mật phiên không vào log. |
| Truy cập dữ liệu | [InviteService](../app/src/main/java/vn/shop/crm/invite/InviteService.java#L600) dùng `JdbcClient`, tham số SQL và khóa trong giao dịch. [CampaignFriendReconciliation](../app/src/main/java/vn/shop/crm/zalo/CampaignFriendReconciliation.java#L139) minh họa việc xác định tiến trình nào được ghi kết quả sau khởi động lại. Cơ chế đọc được thử lại của PR #184 không dùng để tự gọi lại lệnh thêm thành viên. |
| Kiểm thử | Mở rộng [ZaloMultiAccountGroupTest](../app/src/test/java/vn/shop/crm/zalo/ZaloMultiAccountGroupTest.java), [ZaloAutoTest](../app/src/test/java/vn/shop/crm/zalo/ZaloAutoTest.java), [GeoGroupingTest](../app/src/test/java/vn/shop/crm/invite/GeoGroupingTest.java), [GroupPlannerScenarioTest](../app/src/test/java/vn/shop/crm/zalo/GroupPlannerScenarioTest.java) và [CampaignFriendReconciliationTest](../app/src/test/java/vn/shop/crm/zalo/CampaignFriendReconciliationTest.java). Dùng JUnit, PostgreSQL nhúng, MockMvc và bộ nối giả. Chưa có mẫu sẵn cho việc đối chiếu tư cách thành viên sau lỗi thêm vào nhóm. |

## 4. Các bước code và ca kiểm thử bắt buộc

### Bước 1 · P0-01 · Chốt nhóm, tài khoản và phạm vi thử

**Code dự kiến.** Thêm migration Flyway và nơi lưu quyền theo `(group_id, account_id)` với mã nhóm bên bộ nối của tài khoản đó, dấu tài khoản đã ghim, phiên bản quyền, người duyệt, thời điểm và trạng thái xác minh. Đặt khóa duy nhất cho mã nhóm đang dùng trong phạm vi tài khoản. Đường quản trị ghi mã nhóm gốc và đường cấp quyền mới cùng khóa theo tài khoản và mã nhóm rồi kiểm tra xung đột ở cả hai nguồn. Migration chỉ thêm cấu trúc. Nhóm cũ giữ đường chủ nhóm, không cần tạo hàng loạt quyền mới.

**Quy trình quản trị.** OWNER chọn nhóm ADH, mở bằng chứng từ tài khoản chủ nhóm và tài khoản được cấp quyền, rồi duyệt đúng cặp. Tên trùng chỉ dùng để tìm ứng viên. Chỉ cấp khi có bằng chứng cùng nhóm, chẳng hạn đối chiếu cùng link mời trên hai phiên được kiểm soát. Kiểm tra thành viên và quyền thêm qua dữ liệu `adminOnlyAdd` và `admin` hiện có. Nếu bằng chứng cũ, thiếu hoặc xung đột, để ở trạng thái chờ rà soát. Đổi mã nhóm, thay đổi dấu tài khoản hoặc thu hồi quyền làm bằng chứng cũ mất hiệu lực. Nhóm nghi trùng vào danh sách rà soát riêng, giữ nguyên các bản ghi.

**Chốt trước khi gọi.** Kiểm tra tài khoản khách, phiên đang hoạt động, ánh xạ và phiên bản quyền lần cuối tại điểm gọi. Pilot có danh sách cho phép riêng theo **tài khoản thực hiện, lời mời thử, nhóm ADH và mã nhóm đúng phiên**. Kiểm ở luồng nghiệp vụ và lớp bao bộ nối bằng ngữ cảnh đã xác minh. [SafeZaloLink](../app/src/main/java/vn/shop/crm/zalo/SafeZaloLink.java#L70) hiện chỉ kiểm mã khách ở đường thêm vào nhóm. Bổ sung đối chiếu nhóm đích cho phạm vi mới, với test hồi quy cho đường cũ. Cấp quyền và gọi thêm không giữ khóa DB qua mạng. Nếu quyền bị thu hồi sau khi đã qua mốc phát lệnh, xử lý kết quả của lượt đã gọi theo bước 2.

| Ca kiểm thử | Kết quả đạt |
|---|---|
| Hai nhóm trùng tên, hoặc mã nhóm cùng số ở hai phiên nhưng bằng chứng khác nhóm | Quyền chưa có hiệu lực. Bộ nối giả nhận **0** lệnh thêm. |
| Cấp cùng mã nhóm cho hai nhóm ADH đồng thời, gồm cả xung đột với mã gốc | Tối đa một ánh xạ được chấp nhận. Hiện lỗi có thể xử lý trên giao diện. |
| Dùng đúng khách thử với nhóm khác, đúng nhóm với khách khác, hoặc phiên bị đổi ngay trước khi cấp lượt gọi | Từng trường hợp bị từ chối, không thay đổi lượt giữ chỗ cũ. |
| OWNER và người dùng khác gửi POST cấp quyền, gồm POST thiếu CSRF | Chỉ yêu cầu đúng quyền và có CSRF được xử lý. |
| Qua router và scheduler với bốn cầu nối độc lập, tài khoản 1 và 3 có quyền, 2 là chủ nhóm, 4 chưa được cấp | 1, 2 và 3 dùng đúng mã nhóm nhìn từ phiên mình khi được duyệt cho tác vụ. 4 không có lệnh thêm vào nhóm. Không có nhánh chuyển khách sang tài khoản khác. |

### Bước 2 · P1-01 · Ghi ý định bền và hoàn tất kết quả

**Code dự kiến.** Thêm bảng tác vụ với ID lượt, lời mời, nhóm ADH, tài khoản thực hiện, mã nhóm đúng phiên và phiên bản quyền đã kiểm, trạng thái, thời điểm, kết quả và lý do. Ràng buộc duy nhất chặn hai tác vụ mở cho cùng lời mời. Trong giao dịch cấp lượt, ghi ý định và giữ chỗ tác vụ trước khi gọi. Chỉ tiến trình nhận quyền mới được phát lệnh. Ghi mốc phát lệnh bền trước khi gọi mạng để nhận diện trường hợp app dừng.

**Kết thúc lượt.** Khi Zalo xác nhận thành công, ghi trong một giao dịch PostgreSQL gồm kết quả cuối, `group_id`, `JOINED`, dấu tài khoản đã thực hiện và số chỗ. [`markJoinedExact`](../app/src/main/java/vn/shop/crm/invite/InviteService.java#L728) hiện kiểm chủ nhóm. Sửa cùng đường chọn nhóm để việc xác nhận dựa trên tác vụ và đúng nhóm thực tế. Việc đã thêm được xác nhận sau khi thu hồi quyền vẫn ghi vào đúng nhóm và lịch sử, không cấp quyền mới. Nếu trong lúc chờ khách đã bị hủy hoặc đã có kết quả ở một nhóm khác, lưu bằng chứng của lượt gọi rồi chuyển sang xử lý tay để tránh ghi đè lịch sử.

**Lỗi chưa rõ.** Nếu timeout, mất tiến trình hoặc commit lỗi sau phát lệnh, giữ đúng một chỗ tác vụ và hiện **Cần đối chiếu**. Tác vụ còn mở vẫn giữ được chỗ sau khi lượt giữ chỗ cũ hết hạn. Chỉ cho thử lại hoặc giải phóng sau khi có bằng chứng xác nhận khách chưa vào đúng nhóm. Một danh sách thành viên thiếu hoặc cũ không đủ bằng chứng. Nếu chưa có API đọc đáng tin, OWNER xác nhận trên Zalo và ghi lý do vào nhật ký. Kết quả đọc lời mời kết bạn của PR #184 không xác nhận tư cách thành viên.

| Ca kiểm thử | Kết quả đạt |
|---|---|
| App dừng trước mốc phát lệnh | Bộ nối giả chưa nhận lệnh. Có thể nhận lại việc khi PostgreSQL chứng minh chưa phát lệnh. |
| App dừng sau mốc phát lệnh nhưng trước khi biết kết quả, hoặc bộ nối đã thêm rồi ngắt kết nối | Khởi động lại không gọi thêm lần hai. Lượt vào **Cần đối chiếu** và giữ đúng một chỗ. |
| Ép giao dịch thất bại sau khi bộ nối trả `OK` | Không có nhật ký hoàn tất lệch trạng thái khách. Lượt giữ chỗ và có thể đối chiếu. |
| Hai xác nhận thành công đồng thời, hoặc thu hồi quyền lúc đang gọi | Chỉ một lần đổi trạng thái và số đếm. Nhóm và tài khoản trong lịch sử khớp tác vụ đã phát. |
| Ghi nhận tay vào nhóm khác sau khi lượt tự động đã phát lệnh | Lưu bằng chứng kết quả từ bộ nối, không ghi đè nhóm đã ghi nhận. Cảnh báo chênh lệch để OWNER quyết định. |
| Kết quả chắc chắn chưa thêm | Phần chỗ của tác vụ được giải phóng. `reserved_group_id` và `reserved_until` cũ giữ nguyên. |

### Bước 3 · P1-02 · Một đường cấp chỗ cho mọi luồng

**Code dự kiến.** Tập trung tính sức chứa và cấp chỗ trong lớp nghiệp vụ dùng chung của `GroupService`, `InviteService` và `EntryService`. Điểm đầu an toàn là dùng một khóa giao dịch PostgreSQL chung cho cả đường cũ và mới. Chỉ tối ưu sang khóa dòng nhóm khi đã kiểm tất cả đường ghi dùng cùng thứ tự khóa. Trong giao dịch, đọc lại nhóm, trạng thái mở, lời mời, giữ chỗ, quyền và sức chứa rồi cấp chỗ. Dùng thứ tự khóa cố định xuyên suốt, tránh luồng giữ khóa nhóm rồi đợi lời mời gặp luồng làm ngược. Gọi mạng sau khi thoát giao dịch.

**Đường phải rà.** Tự thêm, giữ chỗ tay, đánh dấu vào và rời nhóm, link lời mời, đường vào nhóm công khai, chỉnh mục tiêu và kiểm đếm lại. [GroupService](../app/src/main/java/vn/shop/crm/invite/GroupService.java#L125) ước tính lượng chờ từ lượt bấm và lượt vào qua trang mở. Giữ cách tính cũ cho mục chưa có ID khách, hiện riêng số thành viên thực tế nếu có nguồn đáng tin. Gộp giữ chỗ cũ và chỗ tác vụ theo ID lời mời. Lượt đã vào chỉ tính một lần. Khi hạ mục tiêu, giữ phần đã cấp và chặn cấp mới nếu hết chỗ.

**Chọn nhóm.** Giữ nhóm đã gắn hoặc giữ chỗ còn hạn. Với khách mới, dùng các mức ưu tiên địa bàn và chính sách khách chưa rõ tỉnh hiện hành. Khi tính năng bật, trong cùng mức ưu tiên chọn nhóm đủ điều kiện gần đạt mục tiêu nhất, hòa thì chọn theo ID nhóm. Giữ khác biệt giữa mục tiêu mềm và chặn cấp chỗ. Khi tắt tính năng, giữ cách xếp cũ. [ZaloGroupCreationService](../app/src/main/java/vn/shop/crm/zalo/ZaloGroupCreationService.java#L271) phải dùng cùng danh sách nhóm có quyền khi đề xuất và kiểm lại trước khi tạo để tránh lập nhóm mới dù còn chỗ hợp lệ.

| Ca kiểm thử | Kết quả đạt |
|---|---|
| PostgreSQL thật, bốn bộ nối giả, tài khoản 3 và 4 tranh chỗ cuối, thêm một giao dịch giữ chỗ tay hoặc chọn qua link | Chỉ một giao dịch chiếm chỗ cuối. Không deadlock. Các đường đọc và ghi thấy cùng kết quả sau commit. |
| Một lời mời có giữ chỗ cũ, lượt bấm đã tính và tác vụ đang gọi | Không tính cùng khách hai lần. Khi xác nhận thành công, số đếm biến đổi đúng một lần. |
| Nhóm đã gắn mất quyền, hạ sức chứa hoặc tắt dùng chung | Không chuyển nhóm hay gia hạn giữ chỗ. Chỗ đã cấp và lịch sử vẫn tra được. |
| Migration trên DB mẫu có giữ chỗ sống, hết hạn, khách đã vào nhóm và link cũ | Các ID, link, chủ nhóm, mốc giữ chỗ và lịch sử bằng dữ liệu trước migration. Đường chủ nhóm hiện tại vẫn chạy. |

### Bước 4 · P1-03 · Khoanh lỗi quyền và hiển thị đúng lịch sử

**Code dự kiến.** Tách lỗi quyền của cặp nhóm–tài khoản, lỗi thực sự của nhóm, lỗi riêng khách và tín hiệu hạn chế của phiên. [ZaloAutoService](../app/src/main/java/vn/shop/crm/zalo/ZaloAutoService.java#L627) hiện có thể tạm dừng nhóm ở nhánh `GROUP_ERROR`. Ánh xạ lỗi quyền đã xác minh sang tạm ngưng đúng cặp. Chỉ dùng trạng thái dừng cả nhóm khi có chứng cứ về nhóm đã đóng, đầy hoặc lỗi chung đã xác nhận. Tín hiệu Zalo hạn chế, nhầm phiên hoặc lỗi chưa biết vẫn đi qua cơ chế an toàn hiện hành. Thay truy vấn lịch sử nối theo chủ nhóm bằng tra nhóm ADH qua `group_id` tại những màn chỉ đọc. Luồng chọn để thực hiện vẫn kiểm quyền.

| Ca kiểm thử | Kết quả đạt |
|---|---|
| Tài khoản 1 mất quyền, 2 là chủ và 3 được cấp | Cặp nhóm–1 bị tạm ngưng. 2 và 3 tiếp tục đủ điều kiện trong phạm vi test. 4 vẫn bị từ chối nếu thiếu quyền. |
| Nhóm thật sự đóng hoặc tài khoản bị Zalo hạn chế | Phạm vi dừng khớp loại lỗi và cầu dao hiện hành. Không dùng tài khoản khác để chạy thay. Z216 giữ nguyên. |
| Mở lại lịch sử sau khi quyền bị thu hồi | Vẫn hiện đúng nhóm, chủ nhóm và tài khoản đã thêm. Thao tác cũ không được đưa về hàng gửi lại. |

### Bước 5 · P1-04 · Quay lui và khôi phục mà không phát lại thao tác

**Code và vận hành dự kiến.** Trong ứng dụng và [runbook](RUNBOOK.md), phân biệt tắt cấp chỗ mới, lùi ảnh và khôi phục DB. Trước khi lùi ảnh, tắt cấp mới, kiểm tác vụ đã phát lệnh và xác nhận ảnh cũ còn đọc đúng các nhóm đã dùng chung. Nếu ảnh cũ không đọc hoặc bảo vệ được lịch sử, giữ dừng thao tác và dùng bản sửa tương thích để tiến lên. Flyway không tự lùi schema theo ảnh.

Khi phục hồi DB, mặc định khóa các thao tác gửi hoặc thêm người cho tới khi rà khoảng thời gian từ bản sao lưu đến lúc sự cố. Trạng thái khóa phục hồi phải được xác lập trước khi scheduler chạy, với nguồn tín hiệu ngoài bản DB được phục hồi để trạng thái cũ không ghi đè nó. Kiểm tra khả năng sao lưu ngoài máy, thử nạp bản sao vào DB riêng và tập hợp bằng chứng theo thời gian từ nhật ký bộ nối hoặc xác nhận của quản trị viên. Các lượt không thể xác định vẫn bị giữ để đối chiếu. Chưa có bằng chứng rằng sao lưu ngoài máy đã được bật trên production.

| Ca kiểm thử | Kết quả đạt |
|---|---|
| Nạp DB cũ trong môi trường thử. Bộ nối giả vẫn giữ dấu một khách đã được thêm sau thời điểm sao lưu | Scheduler không gửi hoặc thêm người trước khi mở khóa. Lượt đã thêm được ghi nhận từ bằng chứng, không gọi lại. |
| Tắt dùng chung khi một lượt chưa gọi và một lượt đã gọi nhưng chưa rõ kết quả | Lượt chưa gọi dừng phát lệnh. Lượt đã gọi còn dấu vết và giữ chỗ, chủ nhóm và giữ chỗ cũ giữ nguyên. |
| Thử ảnh cũ với schema cộng thêm sau khi có dữ liệu nhóm dùng chung | Xác nhận đọc đúng và không phát lại. Nếu không đạt thì quy trình vận hành chặn cách quay lui bằng ảnh đó. |

### Bước 6 · P2-01 · Hiển thị và hủy phần đối chiếu chưa đọc

**Code dự kiến.** Mở rộng `CampaignFriendReconciliation` và giao diện của PR #184 để hiện chiến dịch đang giữ lượt, thời gian chờ, lý do, lần đọc thành công gần nhất và thời gian dự kiến đọc tiếp nếu biết. OWNER có nút hủy phần chưa nhận xử lý, có CSRF và xác nhận. Mục đã đọc và nhật ký giữ nguyên. Mục đang đọc chỉ được kết thúc trong đúng lượt đã nhận việc, tránh kết quả trả muộn cập nhật sang lượt mới. Dùng cùng cách hiển thị thời gian và nguyên nhân cho việc đối chiếu nhóm khi có đủ nguồn đọc. Hai loại đối chiếu có hàng việc riêng.

| Ca kiểm thử | Kết quả đạt |
|---|---|
| Mất phiên, hết hạn mức hoặc tài khoản bị tắt khi đang chạy | Giao diện hiện đúng lý do theo tài khoản và chiến dịch. Nhịp tài khoản khác tiếp tục nếu cầu dao chung cho phép. |
| Hủy khi còn mục chưa đọc và có một mục đang xử lý, rồi bắt đầu lượt khác | Mục chưa nhận không được gọi. Kết quả cũ không ghi đè lượt mới. Nhật ký của các mục đã đọc vẫn xem được. |
| Người không có quyền hoặc POST thiếu CSRF cố hủy | Từ chối yêu cầu, dữ liệu và tiến độ giữ nguyên. |

## 5. Danh sách thay đổi dự kiến và phụ thuộc

| Khu vực | Thay đổi dự kiến |
|---|---|
| `app/src/main/resources/db/migration/` | Thêm migration quyền, tác vụ giữ chỗ và nhật ký cần thiết. Chọn số migration sau khi cập nhật `main`. Dùng migration cộng thêm và kiểm thử nâng cấp. |
| `invite/GroupService`, `InviteService`, `EntryService` | Tra quyền và sức chứa, gom các đường cấp chỗ, giữ link cũ, ghi nhóm đích và chuẩn hóa truy vấn lịch sử. |
| `zalo/ZaloAutoService`, `SafeZaloLink`, `safety/TestMode`, `zalo/ZaloGroupCreationService` | Chốt nhóm đích, ghi tác vụ trước khi gọi, thực thi và phân loại lỗi cùng nhịp riêng theo tài khoản. |
| Quản trị Zalo, giao diện nhóm và chiến dịch | Duyệt quyền từng cặp và rà trùng. Hiện lỗi, số chỗ, mục cần đối chiếu, báo cáo mô phỏng và điều khiển hủy theo quyền. |
| Bộ nối và hợp đồng API | Kiểm contract và test số lần gọi, dấu tài khoản, nhóm và mã khách. Chỉ đề xuất thêm API đọc thành viên sau khi chứng minh tính đầy đủ của dữ liệu. Pipeline pilot dùng bộ nối giả ở bước thử tự động. |
| `docs/RUNBOOK.md` và test Java/Node | Quy trình dừng, khôi phục và bàn giao khi đổi phiên. Các test hồi quy theo từng mã rủi ro ở mục 4. |

P0-01 có thể triển khai trước dưới cờ tắt. P1-01 và P1-02 phải đạt cùng nhau trước khi bật đường tự thêm dùng chung. P1-03 và diễn tập P1-04 phải đạt trước pilot. P2-01 có thể đi PR riêng, nhưng trạng thái **Cần đối chiếu** của lỗi thêm nhóm phải có từ bước 2. Nếu triển khai chia nhiều PR, giữ cờ tắt đến khi có đủ các phần. Xem [quy tắc review](../CLAUDE.md), [hướng dẫn nhiều tài khoản](HUONG-DAN-NHIEU-TAI-KHOAN-ZALO.md) và [hợp đồng bridge](ZALO-BRIDGE-API.md).

## 6. Chạy mô phỏng, thử một cặp và nghiệm thu

1. **Mô phỏng chỉ đọc.** Dùng cùng bộ phân bổ ở mốc giờ cố định, theo thứ tự khách xác định. Báo nhóm đang giữ và giờ hết hạn, nhóm theo quy tắc cũ, nhóm đề xuất, tài khoản sẽ thêm, chỗ còn và lý do. Trừ chỗ giả lập trong bộ nhớ để các dòng sau nhìn thấy chỗ còn đúng. So sánh dữ liệu nghiệp vụ trước và sau. Cả bốn bridge giả nhận **0** lệnh thay đổi trên Zalo.
2. **Pilot có phê duyệt riêng.** Dùng một nhóm thử do tài khoản 2 lập và một khách thử chỉ kết bạn với tài khoản 1. Giới hạn đúng tài khoản, khách và nhóm đã duyệt. Đạt khi Zalo hiện khách trong nhóm, ADH ghi tài khoản **1** thêm, nhóm vẫn thuộc **2**, và chỉ dùng **một chỗ**. Thử thao tác tắt và kiểm tra giữ chỗ cũ cùng lịch sử. Tài khoản 3 và 4 mới cần kiểm bằng bộ nối giả ở đợt đầu.
3. **Chuẩn bị mở rộng.** Xác minh từng cầu nối và dấu tài khoản riêng, quyền nhóm, cách báo lỗi cho người vận hành và bằng chứng sao lưu. Đề xuất mở từng nhóm sau khi chủ dự án duyệt kết quả pilot. Mẫu triển khai hiện có dành cho cầu nối 2. Việc cấu hình thêm máy hoặc tài khoản 3 và 4 thuộc một đợt triển khai riêng.

Lệnh kiểm sau khi code, từ thư mục gốc repo trên Linux hoặc Git Bash. Trên Windows có thể chạy `mvnw.cmd` trong `app`.

```sh
(cd app && ./mvnw --batch-mode --no-transfer-progress verify)
(cd zalo-bridge && npm ci --ignore-scripts --no-audit --no-fund && npm test)
git diff --check
```

**Điều kiện kết thúc.** Với mỗi lỗi cần sửa ở mục 4, kiểm thử hồi quy phải thất bại đúng lý do trước khi sửa và đạt sau khi sửa. Các kiểm thử giữ hành vi cũ vẫn phải đạt. So sánh dữ liệu trước và sau migration, mô phỏng, tắt quyền và thử khôi phục. Lưu kết quả cùng PR triển khai bằng dữ liệu giả hoặc số tổng hợp. PR tài liệu hiện tại bàn giao kế hoạch. Việc viết code, thử Zalo và deploy cần phê duyệt riêng.
